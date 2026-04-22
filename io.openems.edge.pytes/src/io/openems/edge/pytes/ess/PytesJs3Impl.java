package io.openems.edge.pytes.ess;

import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.cycle.Cycle.DEFAULT_CYCLE_TIME;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.Level;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.BitsWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.component.ClockProvider;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.cycle.Cycle;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.AsymmetricEss;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.ManagedAsymmetricEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.pytes.battery.PytesBattery;
import io.openems.edge.pytes.dccharger.PytesDcCharger;
import io.openems.edge.pytes.enums.EnableDisable;
import io.openems.edge.pytes.enums.InverterOperatingStatus;
import io.openems.edge.pytes.enums.WorkState;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Pytes.Hybrid.ESS", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})
public class PytesJs3Impl extends AbstractOpenemsModbusComponent
		implements PytesJs3, HybridEss, SymmetricEss, ManagedSymmetricEss, AsymmetricEss, ManagedAsymmetricEss,
		OpenemsComponent, ModbusComponent, EventHandler, TimedataProvider, ClockProvider {

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private ComponentManager componentManager;

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	@Reference
	private Power power;

	@Reference
	private Cycle cycle;

	// Power control handlers - created once both battery and charger are available
	private volatile ApplyPowerHandler applyPowerHandler = null;
	private volatile AllowedChargeDischargeHandler allowedChargeDischargeHandler = null;

	// Hysteresis guard for work state transitions
	private LocalDateTime lastDefinedWorkStateTime = LocalDateTime.now();

	private final Logger log = LoggerFactory.getLogger(PytesJs3Impl.class);
	private Config config = null;

	// Reference to attached sub-components - populated via addBattery/addCharger
	private PytesDcCharger charger;
	private PytesBattery battery;

	public PytesJs3Impl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				HybridEss.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				AsymmetricEss.ChannelId.values(), //
				ManagedAsymmetricEss.ChannelId.values(), //
				PytesJs3.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this._setWorkState(WorkState.UNDEFINED);
		this.installListeners();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:
			// Forward battery SoC and power to ESS channels so controllers see them
			// Guard against null - battery registers itself asynchronously after activation
			if (this.battery != null) {
				this._setSoc(this.battery.getSoc().get()); // Integer value
				Integer dcDischargePower = this.battery.getDcDischargePower().get();
				this._setDcDischargePower(dcDischargePower);
				this.logDebug(this.log, "DcDischargePower: " + dcDischargePower + "W");
			}
			if (this.allowedChargeDischargeHandler != null) {
				this.allowedChargeDischargeHandler.accept(this.componentManager);
			}
			this.decodeOperatingMode();
			this.defineWorkState();
			this.logDebug();			
			break;
		}
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //

				// ---------------------------------------------------------------
				// Backup circuit setting (reg 43111, holding register)
				// FC16 write / FC3 read, Priority LOW
				// Datasheet: "Backup circuit setting. 0x0000=disable, 0x0001=enable (default)"
				// ---------------------------------------------------------------
				
				new FC16WriteRegistersTask(43111,
						
						// reg 43111 - Backup circuit setting [write]
						// Uses EnableDisable enum.
						m(PytesJs3.ChannelId.SET_BACKUP_CIRCUIT_SETTING, new UnsignedWordElement(43111))),
				
				
				new FC3ReadRegistersTask(43111, Priority.LOW,

						// reg 43111 - Backup circuit setting [read-back]
						m(PytesJs3.ChannelId.BACKUP_CIRCUIT_SETTING, new UnsignedWordElement(43111))),
				
				// ---------------------------------------------------------------
				// SoC limit settings (reg 43010-43018, holding registers)
				// FC16 write / FC3 read, Priority LOW (configuration, rarely changes)
				// ---------------------------------------------------------------
				new FC16WriteRegistersTask(43010,

						// reg 43010 - Max Charge SOC [write] (1%, range 80-100%, default 100%)
						m(PytesJs3.ChannelId.SET_MAX_CHARGE_SOC, new UnsignedWordElement(43010)),
						
						// reg 43011 - Overdischarge SOC [write] (1%, range 5-40%, default 20%)
						m(PytesJs3.ChannelId.SET_OVERDISCHARGE_SOC, new UnsignedWordElement(43011)),
						
						// reg 43012-43017 - Reserved
						new DummyRegisterElement(43012, 43017),
						
						// reg 43018 - Force Charge SOC [write] (1%, range 4% to reg 43011, default 10%)
						m(PytesJs3.ChannelId.SET_FORCE_CHARGE_SOC, new UnsignedWordElement(43018))),

				new FC3ReadRegistersTask(43010, Priority.HIGH,

						// reg 43010 - Max Charge SOC [read-back]
						m(PytesJs3.ChannelId.MAX_CHARGE_SOC, new UnsignedWordElement(43010)),

						// reg 43011 - Overdischarge SOC [read-back]
						m(PytesJs3.ChannelId.OVERDISCHARGE_SOC, new UnsignedWordElement(43011)),

						// reg 43012-43017 - Reserved
						new DummyRegisterElement(43012, 43017),

						// reg 43018 – Force Charge SOC [read-back]
						m(PytesJs3.ChannelId.FORCE_CHARGE_SOC, new UnsignedWordElement(43018))),
				
				// ---------------------------------------------------------------
				// Remote dispatch registers (reg 44100-44108, holding registers)
				// FC16 write / FC3 read, Priority HIGH
				// Note: NOT saved after power cycle. Must be re-written every control cycle
				// Datasheet: "All remote dispatch registers will not be saved after power cycle"
				// ---------------------------------------------------------------

				new FC16WriteRegistersTask(44100,

						// reg 44100 - Remote dispatch switch [write] (0=OFF, 1=ON)
						// Uses EnableDisable enum.
						m(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_SWITCH, new UnsignedWordElement(44100)),
						
						// reg 44101 - Remote dispatch failsafe timeout [write] (1=1min, range 1-1440, default=5min)
						m(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_FAILSAFE_SETTING, new UnsignedWordElement(44101)),
						
						// reg 44102 - Remote dispatch system limit switch [write] (bitmask)
						// BIT00: System import limit switch (0=Disable, 1=Enable)
						// BIT01: System export limit switch (0=Disable, 1=Enable)
						// Uses RemoteDispatchSystemLimitSwitch enum. No per-bit decode needed.
						m(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH, new UnsignedWordElement(44102)),
						
						// reg 44103 – Remote dispatch system import limit [write] (1=100W)
						m(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_SYSTEM_IMPORT_LIMIT, new UnsignedWordElement(44103)),
						
						// reg 44104 – Remote dispatch system export limit [write] (1=100W)
						m(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_SYSTEM_EXPORT_LIMIT, new UnsignedWordElement(44104)),
						
						// reg 44105 – Remote dispatch real-time control switch [write]
						// 1=Standby (default), 2=Battery charge/discharge control, 3=Grid point control, 4=AC grid port control
						// Uses RemoteDispatchRealtimeControlSwitch enum.
						m(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_SWITCH, new UnsignedWordElement(44105)),
						
						// reg 44106–44107 – Remote dispatch real-time control power [write] (S32, 1=10W)
						// When 44105=2: negative=discharge, positive=charge
						// When 44105=3 or 4: negative=import, positive=export
						m(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_POWER, new SignedDoublewordElement(44106)),
						
						// reg 44108 – Remote dispatch real-time control function switch [write]
						// BIT00-01=PV shutdown, BIT02-03=DO control,
						// BIT04-05=grid charge allowed, BIT06-07=off-grid battery standby
						// Manual Decoding -> installListeners()
						m(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_FUNCTION_SWITCH, new UnsignedWordElement(44108))),

				new FC3ReadRegistersTask(44100, Priority.HIGH,
						
						// reg 44100 – Remote dispatch switch [read-back]
						m(PytesJs3.ChannelId.REMOTE_DISPATCH_SWITCH, new UnsignedWordElement(44100)),
						
						// reg 44101 – Remote dispatch failsafe timeout [read-back]
						m(PytesJs3.ChannelId.REMOTE_DISPATCH_FAILSAFE_SETTING, new UnsignedWordElement(44101)),
						
						// reg 44102 – Remote dispatch system limit switch [read-back]
						m(PytesJs3.ChannelId.REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH, new UnsignedWordElement(44102)),
						
						// reg 44103 – Remote dispatch system import limit [read-back]
						m(PytesJs3.ChannelId.REMOTE_DISPATCH_SYSTEM_IMPORT_LIMIT, new UnsignedWordElement(44103)),
						
						// reg 44104 – Remote dispatch system export limit [read-back]
						m(PytesJs3.ChannelId.REMOTE_DISPATCH_SYSTEM_EXPORT_LIMIT, new UnsignedWordElement(44104)),
						
						// reg 44105 – Remote dispatch real-time control switch [read-back]
						m(PytesJs3.ChannelId.REMOTE_DISPATCH_REALTIME_CONTROL_SWITCH, new UnsignedWordElement(44105)),
						m(PytesJs3.ChannelId.REMOTE_DISPATCH_REALTIME_CONTROL_POWER,
								new SignedDoublewordElement(44106)),
						m(PytesJs3.ChannelId.REMOTE_DISPATCH_REALTIME_CONTROL_FUNCTION_SWITCH,
								new UnsignedWordElement(44108))),

				// Write new values to inverter
				// Each register gets its own task because they are not contiguous
				
				new FC16WriteRegistersTask(43010,
						m(PytesJs3.ChannelId.SET_MAX_CHARGE_SOC, new UnsignedWordElement(43010)),
						m(PytesJs3.ChannelId.SET_OVERDISCHARGE_SOC, new UnsignedWordElement(43011)),
						new DummyRegisterElement(43012, 43017),
						m(PytesJs3.ChannelId.SET_FORCE_CHARGE_SOC, new UnsignedWordElement(43018))),

				// Read current values back from inverter
				new FC3ReadRegistersTask(43010, Priority.LOW,
						m(PytesJs3.ChannelId.SET_MAX_CHARGE_SOC, new UnsignedWordElement(43010)),
						m(PytesJs3.ChannelId.SET_OVERDISCHARGE_SOC, new UnsignedWordElement(43011)),
						new DummyRegisterElement(43012, 43017),
						m(PytesJs3.ChannelId.SET_FORCE_CHARGE_SOC, new UnsignedWordElement(43018))),
				
				new FC4ReadInputRegistersTask(33287, Priority.LOW,
						// reg 33287 - Inverter operating status
						// 0=Stop, 1=Open loop, 2=Soft start, 3=Grid-connected,
						// 4=Off-grid/EPS, 5=Off-grid to on-grid, 6=Bypass, 7=Generator
						m(PytesJs3.ChannelId.INVERTER_OPERATING_STATUS, new UnsignedWordElement(33287))),						

				new FC4ReadInputRegistersTask(33067, Priority.HIGH, //

						m(SymmetricEss.ChannelId.MAX_APPARENT_POWER, new UnsignedWordElement(33067),
								ElementToChannelConverter.SCALE_FACTOR_1),

						// reg 33068 – Safety (grid code) version number
						// Datasheet: "Safety Version." Raw integer, no unit.
						m(PytesJs3.ChannelId.SAFETY_VERSION, new UnsignedWordElement(33068)),

						// reg 33069 – HMI sub-version number
						// Datasheet: Form complete HMI version with reg 33002.
						m(PytesJs3.ChannelId.HMI_SUB_VERSION, new UnsignedWordElement(33069)),

						// reg 33070 – Alarm code data bitmask
						// Datasheet: Used with reg 33095 for fault display.
						// Raw bitmask — no scale.
						m(PytesJs3.ChannelId.ALARM_CODE_DATA, new UnsignedWordElement(33070)),

						// reg 33071 – DC Bus Voltage [mV]
						// Datasheet: 0.1V -> SCALE_FACTOR_2 -> mV
						m(PytesJs3.ChannelId.DC_BUS_VOLTAGE, new UnsignedWordElement(33071),
								ElementToChannelConverter.SCALE_FACTOR_2),

						// reg 33072 – DC Bus Half Voltage [mV]
						// Datasheet: 0.1V -> SCALE_FACTOR_2 -> mV
						m(PytesJs3.ChannelId.DC_BUS_HALF_VOLTAGE, new UnsignedWordElement(33072),
								ElementToChannelConverter.SCALE_FACTOR_2),

						// reg 33073 – AB Line Voltage / A Phase Voltage [mV]
						// Datasheet: 0.1V -> SCALE_FACTOR_2 -> mV
						m(PytesJs3.ChannelId.VOLTAGE_L1, new UnsignedWordElement(33073),
								ElementToChannelConverter.SCALE_FACTOR_2),

						// reg 33074 – BC Line Voltage / B Phase Voltage [mV]
						// Datasheet: 0.1V -> SCALE_FACTOR_2 -> mV
						m(PytesJs3.ChannelId.VOLTAGE_L2, new UnsignedWordElement(33074),
								ElementToChannelConverter.SCALE_FACTOR_2),

						// reg 33075 – CA Line Voltage / C Phase Voltage [mV]
						// Datasheet: 0.1V -> SCALE_FACTOR_2 -> mV
						m(PytesJs3.ChannelId.VOLTAGE_L3, new UnsignedWordElement(33075),
								ElementToChannelConverter.SCALE_FACTOR_2),

						// reg 33076 - A phase current [mA]
						// Datasheet: 0.1A -> SCALE_FACTOR_2 -> mA
						m(PytesJs3.ChannelId.CURRENT_L1, new UnsignedWordElement(33076),
								ElementToChannelConverter.SCALE_FACTOR_2),

						// reg 33077 – B Phase Current [mA]
						// Datasheet: 0.1A -> SCALE_FACTOR_2 -> mA
						m(PytesJs3.ChannelId.CURRENT_L2, new UnsignedWordElement(33077),
								ElementToChannelConverter.SCALE_FACTOR_2),

						// reg 33078 – C Phase Current [mA]
						// Datasheet: 0.1A -> SCALE_FACTOR_2 -> mA
						m(PytesJs3.ChannelId.CURRENT_L3, new UnsignedWordElement(33078),
								ElementToChannelConverter.SCALE_FACTOR_2),
						
						// reg 33079-33080 - Active Power [W] (S32)
						// Datasheet: 1W -> no converter needed
						// Positive = inverter feeding grid/loads; sign convention per SymmetricEss
						m(SymmetricEss.ChannelId.ACTIVE_POWER, new SignedDoublewordElement(33079)),

						// reg 33081–33082 – Reactive power [Var] (S32)
						// Datasheet: 1Var -> No converter needed.
						m(SymmetricEss.ChannelId.REACTIVE_POWER, new SignedDoublewordElement(33081)),

						// reg 33083–33084 – Apparent power [VA] (S32)
						// Datasheet: 1VA -> No converter needed.
						m(PytesJs3.ChannelId.APPARENT_POWER, new SignedDoublewordElement(33083)),

						// reg 33085–33089 – Reserved / energy counters (not mapped here)
						// reg 33090 – AFCI/ARC fault count (not mapped here)
						new DummyRegisterElement(33085, 33090),

						// reg 33091 – Standard working modes
						// Datasheet: Enum: 00=No response, 01=Volt-watt, 02=Volt-var...
						// See StandardWorkingMode enum. No scale.
						m(PytesJs3.ChannelId.STANDARD_WORKING_MODE, new UnsignedWordElement(33091)),

						// reg 33092 – Grid standards (Appendix 3) — not mapped (rarely needed)
						// reg 33093 – Inverter module temperature 1 (0.1°C) — not mapped (33107 is same NTC)
						new DummyRegisterElement(33092, 33093),

						// reg 33094 – Grid frequency [mHz]
						// Datasheet: 0.01Hz -> SCALE_FACTOR_1 -> mHz.
						m(PytesJs3.ChannelId.FREQUENCY, new UnsignedWordElement(33094),
								ElementToChannelConverter.SCALE_FACTOR_1),
						
						// reg 33095 – Inverter current status (Appendix 2)
						// Datasheet: See Appendix 2.
						// Used with reg 33070 for subdivided fault display. No scale.
						m(PytesJs3.ChannelId.INVERTER_CURRENT_STATUS, new UnsignedWordElement(33095)),

						// reg 33096 – Lead-acid battery temperature (0.1°C, S16)
						// Datasheet: 0.1°C resolution
						m(PytesJs3.ChannelId.LEAD_ACID_BATTERY_TEMP, new SignedWordElement(33096)),

						// reg 33097 – Function status bitmask (U16)
						// Datasheet: multi-bit status register.
						// Raw bitmask — no scale.
						m(new BitsWordElement(33097, this)
								.bit(0,  PytesJs3.ChannelId.FUNCTION_STAT_DRM)
								.bit(1,  PytesJs3.ChannelId.FUNCTION_STAT_PARALLEL_RUNNING)
								.bit(2,  PytesJs3.ChannelId.FUNCTION_STAT_MASTER)
								.bit(3,  PytesJs3.ChannelId.FUNCTION_STAT_3PH_UNBALANCED)
								.bit(4,  PytesJs3.ChannelId.FUNCTION_STAT_GEN_START_CONDITIONS)
								.bit(5,  PytesJs3.ChannelId.FUNCTION_STAT_GEN_STARTED)
								.bit(6,  PytesJs3.ChannelId.FUNCTION_STAT_BATT_INDEPENDENT)
								.bit(7,  PytesJs3.ChannelId.FUNCTION_STAT_AFCI_PRESENT)
								.bit(8,  PytesJs3.ChannelId.FUNCTION_STAT_AFCI_SELFTEST_DONE)
								.bit(9,  PytesJs3.ChannelId.FUNCTION_STAT_GRID_CONNECTED)
								.bit(10, PytesJs3.ChannelId.FUNCTION_STAT_DOUBLE_BACKUP)
								.bit(11, PytesJs3.ChannelId.FUNCTION_STAT_RSD_SWITCH)
								.bit(12, PytesJs3.ChannelId.FUNCTION_STAT_EMERGENCY_SWITCH)
								.bit(13, PytesJs3.ChannelId.FUNCTION_STAT_AC_COUPLING)
								.bit(14, PytesJs3.ChannelId.FUNCTION_STAT_RESERVED_14)
								.bit(15, PytesJs3.ChannelId.FUNCTION_STAT_RESERVED_15)),

						// reg 33098 – Current DRM code status (U16)
						// Datasheet: Raw bitmask — no scale.
						m(PytesJs3.ChannelId.CURRENT_DRM_CODE_STATUS, new UnsignedWordElement(33098)),
						
						// reg 33099 – Inverter cabinet temperature (0.1°C, S16)
						// Datasheet: 0.1°C. For off-grid inverter.
						m(PytesJs3.ChannelId.INVERTER_CABINET_TEMP, new SignedWordElement(33099)),

						// reg 33100–33103 – Reserved / not documented for this model
						new DummyRegisterElement(33100, 33103),

						// reg 33104 – Limited power actual value (0.01%, U16)
						// Datasheet: 0.01% resolution.
						m(PytesJs3.ChannelId.LIMITED_POWER_ACTUAL_VALUE, new UnsignedWordElement(33104)),

						// reg 33105 – PF adjustment actual value (0.001 resolution, S16)
						// Datasheet: 100=1.00, 800=0.80. Range(-1.00 : 1.00)
						m(PytesJs3.ChannelId.PF_ADJUSTMENT_ACTUAL_VALUE, new SignedWordElement(33105)),

						// reg 33106 – Limited reactive power (0.01%, S16)
						// Datasheet: 0.01% resolution. Range: -6000 to +6000.
						// Only effective in Working Mode 4 (Fix reactive power).
						m(PytesJs3.ChannelId.LIMITED_REACTIVE_POWER, new SignedWordElement(33106)),

						// reg 33107 – Inverter module temperature 2 (0.1°C, S16)
						// Datasheet: 0.1°C. For off-grid. Same as 33093 (different NTC).
						m(PytesJs3.ChannelId.INVERTER_MODULE_TEMP2, new SignedWordElement(33107)),

						// reg 33108 – Volt-var real-time Vref value (0.1V, U16)
						// Datasheet: 0.1V resolution -> SCALE_FACTOR_2 -> mV. IEEE1547-2018 Vref.
						m(PytesJs3.ChannelId.VOLT_VAR_VREF_RT_VALUES, new UnsignedWordElement(33108),
								ElementToChannelConverter.SCALE_FACTOR_2),

						// reg 33109 – Reserved
						new DummyRegisterElement(33109, 33109),

						// reg 33110 – BMS charging voltage limit (0.1V, U16)
						// Datasheet:0.1V resolution -> SCALE_FACTOR_2 -> mV. (From BMS).
						m(PytesJs3.ChannelId.BMS_CHARGING_VOLTAGE_LIMIT, new UnsignedWordElement(33110),
								ElementToChannelConverter.SCALE_FACTOR_2),

						// reg 33111 – Battery BMS status (U16)
						// Datasheet: 0=normal, 1=comms fail, 2=warning.
						m(PytesJs3.ChannelId.BATTERY_BMS_STATUS, new UnsignedWordElement(33111)),

						// reg 33112 – Inverter initial setting state bitmask (U16)
						// Datasheet: See Appendix 1.
						m(new BitsWordElement(33112, this)
								.bit(0, PytesJs3.ChannelId.INIT_STATE_MODEL_SET)
								.bit(1, PytesJs3.ChannelId.INIT_STATE_GRID_CODE_SET)
								.bit(2, PytesJs3.ChannelId.INIT_STATE_POWER_CURVE_SET)
								.bit(3, PytesJs3.ChannelId.INIT_STATE_MODULE_TYPE_INFINEON)
								.bit(4, PytesJs3.ChannelId.INIT_STATE_FAN_DETECTION_SUPPORTED)
								.bit(5, PytesJs3.ChannelId.INIT_STATE_FCAS_RUNNING)
								.bit(6, PytesJs3.ChannelId.INIT_STATE_AFCI_TEST_ENDED)
								.bit(7, PytesJs3.ChannelId.INIT_STATE_AFCI_ARC_FOUND)
								.bit(8, PytesJs3.ChannelId.INIT_STATE_DSP_CHIP_TYPE_1)
								.bit(9, PytesJs3.ChannelId.INIT_STATE_DSP_CHIP_TYPE_2)
								.bit(10, PytesJs3.ChannelId.INIT_STATE_IGBT_SCREENING_COMPLETED)
								.bit(11, PytesJs3.ChannelId.INIT_STATE_RESERVED_11)
								.bit(12, PytesJs3.ChannelId.INIT_STATE_RESERVED_12)
								.bit(13, PytesJs3.ChannelId.INIT_STATE_RESERVED_13)
								.bit(14, PytesJs3.ChannelId.INIT_STATE_WAVEFORM_READY)),

						// reg 33113 – Batch upgrade support flags (U16)
						// Datasheet: BIT0=DSP support, BIT4=ARM support.
						m(new BitsWordElement(33113, this)
								.bit(0, PytesJs3.ChannelId.BATCH_UPGRADE_DSP)
								.bit(4, PytesJs3.ChannelId.BATCH_UPGRADE_ARM)),

						// reg 33114 – FCAS mode running status (U16)
						// Datasheet: 0=not running (default), 1=running.
						// Uses EnableDisable enum.
						m(PytesJs3.ChannelId.FCAS_MODE_RUNNING_STATUS, new UnsignedWordElement(33114)),

						// reg 33115: Setting Flag Bit (U16, Appendix 7)
						// BitsWordElement maps each bit directly to its Level.FAULT channel.
						// The framework fills all 16 sub-channels atomically on every Modbus read,
						// replacing the old decodeBits() call that ran one cycle later.
						// Bit positions verified against datasheet Appendix 7.
						m(new BitsWordElement(33115, this)
								.bit(0,  PytesJs3.ChannelId.SETTING_FLAG_FLASH_TIMEOUT)    // BIT00: FLASH r/w timeout
								.bit(1,  PytesJs3.ChannelId.SETTING_FLAG_CLEAR_ENERGY)     // BIT01: Clear energy flag
								.bit(2,  PytesJs3.ChannelId.SETTING_FLAG_RESERVED_02)      // BIT02: Reserved
								.bit(3,  PytesJs3.ChannelId.SETTING_FLAG_RESERVED_03)      // BIT03: Reserved
								.bit(4,  PytesJs3.ChannelId.SETTING_FLAG_RESERVED_04)      // BIT04: Reserved
								.bit(5,  PytesJs3.ChannelId.SETTING_FLAG_RESERVED_05)      // BIT05: Reserved
								.bit(6,  PytesJs3.ChannelId.SETTING_FLAG_RESERVED_06)      // BIT06: Reserved
								.bit(7,  PytesJs3.ChannelId.SETTING_FLAG_RESERVED_07)      // BIT07: Reserved
								.bit(8,  PytesJs3.ChannelId.SETTING_FLAG_RESET_DATALOGGER) // BIT08: Reset datalogger
								.bit(9,  PytesJs3.ChannelId.SETTING_FLAG_FACTORY_RECOVER)  // BIT09: Return factory settings
								.bit(10, PytesJs3.ChannelId.SETTING_FLAG_RESERVED_10)      // BIT10: Reserved
								.bit(11, PytesJs3.ChannelId.SETTING_FLAG_RESERVED_11)      // BIT11: Reserved
								.bit(12, PytesJs3.ChannelId.SETTING_FLAG_RESERVED_12)      // BIT12: Reserved
								.bit(13, PytesJs3.ChannelId.SETTING_FLAG_RESERVED_13)      // BIT13: Reserved
								.bit(14, PytesJs3.ChannelId.SETTING_FLAG_RESERVED_14)      // BIT14: Reserved
								.bit(15, PytesJs3.ChannelId.SETTING_FLAG_RESERVED_15)),    // BIT15: Reserved
						
						// reg 33116: Fault Code 01 (U16, Appendix 4) — grid-side faults
						// Bit positions verified against datasheet Appendix 4.
						m(new BitsWordElement(33116, this)
								.bit(0,  PytesJs3.ChannelId.FAULT_REG1_NO_GRID)                      // BIT00: No grid
								.bit(1,  PytesJs3.ChannelId.FAULT_REG1_GRID_OVERVOLTAGE)             // BIT01: Grid overvoltage
								.bit(2,  PytesJs3.ChannelId.FAULT_REG1_GRID_UNDERVOLTAGE)            // BIT02: Grid undervoltage
								.bit(3,  PytesJs3.ChannelId.FAULT_REG1_GRID_OVERFREQ)                // BIT03: Grid overfrequency
								.bit(4,  PytesJs3.ChannelId.FAULT_REG1_GRID_UNDERFREQ)               // BIT04: Grid underfrequency
								.bit(5,  PytesJs3.ChannelId.FAULT_REG1_UNBALANCED_GRID)              // BIT05: Unbalanced grid
								.bit(6,  PytesJs3.ChannelId.FAULT_REG1_GRID_FREQ_FLUCTUATION)         // BIT06: Frequency fluctuation
								.bit(7,  PytesJs3.ChannelId.FAULT_REG1_GRID_REVERSE_CURRENT)          // BIT07: Reverse current
								.bit(8,  PytesJs3.ChannelId.FAULT_REG1_GRID_CURRENT_TRACKING_ERROR)   // BIT08: Current tracking error
								.bit(9,  PytesJs3.ChannelId.FAULT_REG1_METER_COM_FAIL)                // BIT09: Meter comms fail
								.bit(10, PytesJs3.ChannelId.FAULT_REG1_FAILSAFE)                      // BIT10: Failsafe triggered
								.bit(11, PytesJs3.ChannelId.FAULT_REG1_METER_SELECT_FAIL)             // BIT11: Meter select fail
								.bit(12, PytesJs3.ChannelId.FAULT_REG1_EPM_HARD_LIMIT)               // BIT12: EPM hard limit
								.bit(13, PytesJs3.ChannelId.FAULT_REG1_G100_CURRENT_OVER_LIMIT)      // BIT13: G100 over-limit
								.bit(14, PytesJs3.ChannelId.FAULT_REG1_RESERVED_14)                  // BIT14: Reserved
								.bit(15, PytesJs3.ChannelId.FAULT_REG1_ABNORMAL_GRID_PHASE_POLARITY)),// BIT15: Abnormal phase polarity

						// ── reg 33117: Fault Code 02 (U16, Appendix 4) — backup/hub faults ──────────
						m(new BitsWordElement(33117, this)
								.bit(0,  PytesJs3.ChannelId.FAULT_REG2_BACKUP_OVERVOLTAGE)           // BIT00: Backup overvoltage
								.bit(1,  PytesJs3.ChannelId.FAULT_REG2_BACKUP_OVERLOAD)              // BIT01: Backup overload
								.bit(2,  PytesJs3.ChannelId.FAULT_REG2_GRID_BACKUP_OVERLOAD)          // BIT02: Grid/backup overload
								.bit(3,  PytesJs3.ChannelId.FAULT_REG2_OFFGRID_BACKUP_UNDERVOLTAGE)  // BIT03: Off-grid backup undervoltage
								.bit(4,  PytesJs3.ChannelId.FAULT_REG2_HUB_PANEL_OV_CURRENT)         // BIT04: Hub panel over-current
								.bit(5,  PytesJs3.ChannelId.FAULT_REG2_RESERVED_05)                  // BIT05–BIT15: Reserved
								.bit(6,  PytesJs3.ChannelId.FAULT_REG2_RESERVED_06)
								.bit(7,  PytesJs3.ChannelId.FAULT_REG2_RESERVED_07)
								.bit(8,  PytesJs3.ChannelId.FAULT_REG2_RESERVED_08)
								.bit(9,  PytesJs3.ChannelId.FAULT_REG2_RESERVED_09)
								.bit(10, PytesJs3.ChannelId.FAULT_REG2_RESERVED_10)
								.bit(11, PytesJs3.ChannelId.FAULT_REG2_RESERVED_11)
								.bit(12, PytesJs3.ChannelId.FAULT_REG2_RESERVED_12)
								.bit(13, PytesJs3.ChannelId.FAULT_REG2_RESERVED_13)
								.bit(14, PytesJs3.ChannelId.FAULT_REG2_RESERVED_14)
								.bit(15, PytesJs3.ChannelId.FAULT_REG2_RESERVED_15)),

						// reg 33118: Fault Code 03 (U16, Appendix 4) — battery faults
						m(new BitsWordElement(33118, this)
								.bit(0,  PytesJs3.ChannelId.FAULT_REG3_BATTERY_NOT_CONNECTED)        // BIT00: Battery not connected
								.bit(1,  PytesJs3.ChannelId.FAULT_REG3_BATTERY_OVERVOLTAGE_CHECK)    // BIT01: Overvoltage check
								.bit(2,  PytesJs3.ChannelId.FAULT_REG3_BATTERY_UNDERVOLTAGE_CHECK)   // BIT02: Undervoltage check
								.bit(3,  PytesJs3.ChannelId.FAULT_REG3_BATTERY_BMS_ALARM)            // BIT03: BMS alarm
								.bit(4,  PytesJs3.ChannelId.FAULT_REG3_INCONSISTENT_BATTERY_SELECTION)// BIT04: Inconsistent battery selection
								.bit(5,  PytesJs3.ChannelId.FAULT_REG3_LEAD_ACID_TEMP_TOO_LOW)       // BIT05: Lead-acid temp too low
								.bit(6,  PytesJs3.ChannelId.FAULT_REG3_LEAD_ACID_TEMP_TOO_HIGH)      // BIT06: Lead-acid temp too high
								.bit(7,  PytesJs3.ChannelId.FAULT_REG3_SECOND_BATTERY_NOT_CONNECTED) // BIT07: 2nd battery not connected
								.bit(8,  PytesJs3.ChannelId.FAULT_REG3_SECOND_BATTERY_SW_OVERVOLTAGE)// BIT08: 2nd battery SW overvoltage
								.bit(9,  PytesJs3.ChannelId.FAULT_REG3_SECOND_BATTERY_SW_UNDERVOLTAGE)// BIT09: 2nd battery SW undervoltage
								.bit(10, PytesJs3.ChannelId.FAULT_REG3_PARALLEL_BATTERY_COM_ABNORMAL) // BIT10: Parallel comms abnormal
								.bit(11, PytesJs3.ChannelId.FAULT_REG3_LOW_BATTERY_OFFGRID)           // BIT11: Low battery off-grid
								.bit(12, PytesJs3.ChannelId.FAULT_REG3_RESERVED_12)
								.bit(13, PytesJs3.ChannelId.FAULT_REG3_RESERVED_13)
								.bit(14, PytesJs3.ChannelId.FAULT_REG3_RESERVED_14)
								.bit(15, PytesJs3.ChannelId.FAULT_REG3_RESERVED_15)),

						// reg 33119: Fault Code 04 (U16, Appendix 4) — DC/IGBT/AFCI faults
						m(new BitsWordElement(33119, this)
								.bit(0,  PytesJs3.ChannelId.FAULT_REG4_DC_OVERVOLTAGE)              // BIT00: DC overvoltage
								.bit(1,  PytesJs3.ChannelId.FAULT_REG4_DC_BUS_OVERVOLTAGE)          // BIT01: DC bus overvoltage
								.bit(2,  PytesJs3.ChannelId.FAULT_REG4_DC_BUS_UNBALANCED_VOLTAGE)   // BIT02: DC bus unbalanced
								.bit(3,  PytesJs3.ChannelId.FAULT_REG4_DC_BUS_UNDERVOLTAGE)         // BIT03: DC bus undervoltage
								.bit(4,  PytesJs3.ChannelId.FAULT_REG4_DC_BUS_UNBALANCED_VOLTAGE_2) // BIT04: DC bus unbalanced 2
								.bit(5,  PytesJs3.ChannelId.FAULT_REG4_DC_OVERCURRENT_A)            // BIT05: DC overcurrent A
								.bit(6,  PytesJs3.ChannelId.FAULT_REG4_DC_OVERCURRENT_B)            // BIT06: DC overcurrent B
								.bit(7,  PytesJs3.ChannelId.FAULT_REG4_DC_INPUT_INTERFERENCE)       // BIT07: DC input interference
								.bit(8,  PytesJs3.ChannelId.FAULT_REG4_GRID_OVERCURRENT)            // BIT08: Grid overcurrent
								.bit(9,  PytesJs3.ChannelId.FAULT_REG4_IGBT_OVERCURRENT)            // BIT09: IGBT overcurrent
								.bit(10, PytesJs3.ChannelId.FAULT_REG4_GRID_INTERFERENCE_02)        // BIT10: Grid interference 02
								.bit(11, PytesJs3.ChannelId.FAULT_REG4_AFCI_SELF_CHECK)             // BIT11: AFCI self-check
								.bit(12, PytesJs3.ChannelId.FAULT_REG4_ARC_FAULT_RESERVED)          // BIT12: Arc fault (reserved)
								.bit(13, PytesJs3.ChannelId.FAULT_REG4_GRID_CURRENT_SAMPLING_FAULT) // BIT13: Current sampling fault
								.bit(14, PytesJs3.ChannelId.FAULT_REG4_DSP_SELF_CHECK_ERROR)        // BIT14: DSP self-check error
								.bit(15, PytesJs3.ChannelId.FAULT_REG4_BATTERY_DISCHARGE_OVERCURRENT)),// BIT15: Battery discharge overcurrent

						// reg 33120: Fault Code 05 (U16, Appendix 4) — protection faults
						m(new BitsWordElement(33120, this)
								.bit(0,  PytesJs3.ChannelId.FAULT_REG5_GRID_INTERFERENCE)           // BIT00: Grid interference
								.bit(1,  PytesJs3.ChannelId.FAULT_REG5_OVER_DC_COMPONENTS)          // BIT01: Over DC components
								.bit(2,  PytesJs3.ChannelId.FAULT_REG5_OVER_TEMPERATURE)            // BIT02: Over temperature
								.bit(3,  PytesJs3.ChannelId.FAULT_REG5_RELAY_CHECK)                 // BIT03: Relay check
								.bit(4,  PytesJs3.ChannelId.FAULT_REG5_UNDER_TEMPERATURE)           // BIT04: Under temperature
								.bit(5,  PytesJs3.ChannelId.FAULT_REG5_PV_INSULATION_FAULT)         // BIT05: PV insulation fault
								.bit(6,  PytesJs3.ChannelId.FAULT_REG5_12V_UNDERVOLTAGE)            // BIT06: 12V undervoltage
								.bit(7,  PytesJs3.ChannelId.FAULT_REG5_LEAK_CURRENT)                // BIT07: Leakage current
								.bit(8,  PytesJs3.ChannelId.FAULT_REG5_LEAK_CURRENT_SELF_CHECK)     // BIT08: Leakage self-check
								.bit(9,  PytesJs3.ChannelId.FAULT_REG5_DSP_INITIAL)                 // BIT09: DSP initial protection
								.bit(10, PytesJs3.ChannelId.FAULT_REG5_DSP_B)                       // BIT10: DSP B protection
								.bit(11, PytesJs3.ChannelId.FAULT_REG5_BATTERY_OVERVOLTAGE_HW)      // BIT11: Battery overvoltage HW
								.bit(12, PytesJs3.ChannelId.FAULT_REG5_LLC_HW_OVERCURRENT)          // BIT12: LLC HW overcurrent
								.bit(13, PytesJs3.ChannelId.FAULT_REG5_GRID_TRANSIENT_OVERCURRENT)  // BIT13: Grid transient overcurrent
								.bit(14, PytesJs3.ChannelId.FAULT_REG5_BATTERY_COM_FAILURE)         // BIT14: Battery comms failure
								.bit(15, PytesJs3.ChannelId.FAULT_REG5_DSP_COM_FAIL)),              // BIT15: DSP comms fail

						// reg 33121: Operating Status (U16, Appendix 5)
						// BitsWordElement fills OPERATING_STAT_* channels atomically on every Modbus read.
						// Key mode-detection bits:
						//   BIT00 Normal Operation  — 1 = inverter running and feeding grid/load
						//   BIT04 Standby           — 1 = idle, no charge/discharge
						//   BIT07 Backup Overload   — 1 = backup port load exceeded capacity
						//   BIT09 Grid Fault        — 1 = grid abnormal (triggers off-grid transition)
						// When BIT09=1 the inverter transitions to off-grid; reg 33122 BIT06 will
						// simultaneously go to 1, and reg 33132 BIT02 (STORAGE_CTRL_OFFGRID_MODE) = 1.
						// When Battery Fault Status in Appendix 5 is tripped, acquire 33293-33294 alarm details
						// reg 36026: Mapping inverter working status uses the same appendix (U16, Appendix 5)
						m(new BitsWordElement(33121, this)
								.bit(0,  PytesJs3.ChannelId.OPERATING_STAT_NORMAL_OPERATION)        // BIT00: Normal operation
								.bit(1,  PytesJs3.ChannelId.OPERATING_STAT_INITIALIZING)            // BIT01: Initializing
								.bit(2,  PytesJs3.ChannelId.OPERATING_STAT_CONTROLLED_OFF)          // BIT02: Controlled turning off
								.bit(3,  PytesJs3.ChannelId.OPERATING_STAT_FAULT_OFF)               // BIT03: Fault-induced off
								.bit(4,  PytesJs3.ChannelId.OPERATING_STAT_STANDBY)                 // BIT04: Standby
								.bit(5,  PytesJs3.ChannelId.OPERATING_STAT_LIMITED_TEMP_FREQ)       // BIT05: Limited (temp/freq derate)
								.bit(6,  PytesJs3.ChannelId.OPERATING_STAT_LIMITED_EXTERNAL)        // BIT06: Limited (external reason)
								.bit(7,  PytesJs3.ChannelId.OPERATING_STAT_BACKUP_OVERLOAD)         // BIT07: Backup port overload
								.bit(8,  PytesJs3.ChannelId.OPERATING_STAT_LOAD_FAULT)              // BIT08: Load fault
								.bit(9,  PytesJs3.ChannelId.OPERATING_STAT_GRID_FAULT)              // BIT09: Grid fault (grid abnormal)
								.bit(10, PytesJs3.ChannelId.OPERATING_STAT_BATTERY_FAULT)           // BIT10: Battery fault
								.bit(11, PytesJs3.ChannelId.OPERATING_STAT_RESERVED_11)             // BIT11: Reserved
								.bit(12, PytesJs3.ChannelId.OPERATING_STAT_GRID_SURGE_WARN)         // BIT12: Grid surge (warning)
								.bit(13, PytesJs3.ChannelId.OPERATING_STAT_FAN_FAULT_WARN)          // BIT13: Fan fault (warning)
								.bit(14, PytesJs3.ChannelId.OPERATING_STAT_EXTERNAL_FAN_FAIL)       // BIT14: External fan failure
								.bit(15, PytesJs3.ChannelId.OPERATING_STAT_RESERVED_15)),           // BIT15: Reserved

						// ── reg 33122: Operating Mode (U16, Appendix 8) ─────────────────────────────
						// SPECIAL CASE — BitsWordElement NOT used here.
						// Datasheet: "Only one bit is valid at any time."
						// We need the active BIT POSITION (0–8) as the Appendix8 enum index,
						// not a per-bit boolean. decodeOperatingMode() performs this translation.
						// Mode map: BIT00=UPS/off-grid (no alarm), BIT01=Self-use,
						//           BIT02=TOU self-use, BIT03=Feed-in priority,
						//           BIT04=TOU feed-in, BIT05=Backup mode,
						//           BIT06=Off-grid mode, BIT07=Remote battery control,
						//           BIT08=Passive mode.
						// Undefined state: raw == 0 (no bit set) → decodeOperatingMode() stores -1
						// and OPERATING_MODE_DECODE remains undefined. This occurs during startup
						// before the first valid Modbus frame and should not drive any control logic.
						m(PytesJs3.ChannelId.OPERATING_MODE, new UnsignedWordElement(33122)),

						// ── reg 33123: Working Mode Running Status (U16) ────────────────────────────
						// Datasheet: "Every bit represents one working mode. 0=Stop, 1=Run."
						// BIT00=Volt-watt, BIT01=Volt-var, BIT02=Fixed PF, BIT03=Fix reactive power,
						// BIT04=Power-PF, BIT05=Power-Q, BIT06–15=Reserved.
						m(new BitsWordElement(33123, this)
        						.bit(0, PytesJs3.ChannelId.WMODE_VOLT_WATT)
        						.bit(1, PytesJs3.ChannelId.WMODE_VOLT_VAR)
        						.bit(2, PytesJs3.ChannelId.WMODE_FIXED_PF)
        						.bit(3, PytesJs3.ChannelId.WMODE_FIX_REACTIVE)
        						.bit(4, PytesJs3.ChannelId.WMODE_POWER_PF)
        						.bit(5, PytesJs3.ChannelId.WMODE_POWER_Q)),

						// reg 33124: Fault Code 06 (U16, Appendix 4) — parallel/multi-unit faults
						m(new BitsWordElement(33124, this)
								.bit(0,  PytesJs3.ChannelId.FAULT_REG6_SLAVE_LOSE_ERR)              // BIT00: Slave sync signal loss
								.bit(1,  PytesJs3.ChannelId.FAULT_REG6_MASTER_LOSE_ERR)             // BIT01: Master sync signal loss
								.bit(2,  PytesJs3.ChannelId.FAULT_REG6_SLAVE_PRD_ERR)               // BIT02: Slave sync period error
								.bit(3,  PytesJs3.ChannelId.FAULT_REG6_MASTER_PRD_ERR)              // BIT03: Master sync period error
								.bit(4,  PytesJs3.ChannelId.FAULT_REG6_ADDR_CONFLICT)               // BIT04: Address conflict
								.bit(5,  PytesJs3.ChannelId.FAULT_REG6_HEARTBEAT_LOSE)              // BIT05: Heartbeat loss
								.bit(6,  PytesJs3.ChannelId.FAULT_REG6_DCAN_ERR)                    // BIT06: DCAN register error
								.bit(7,  PytesJs3.ChannelId.FAULT_REG6_MUL_MASTER_ERR)              // BIT07: Multiple master error
								.bit(8,  PytesJs3.ChannelId.FAULT_REG6_MODE_CONFLICT)               // BIT08: Mode conflict
								.bit(9,  PytesJs3.ChannelId.FAULT_REG6_S_PLUG_VOLT_ERR)             // BIT09: S-plug voltage error
								.bit(10, PytesJs3.ChannelId.FAULT_REG6_OTHERS_FAULT)                // BIT10: Other device fault
								.bit(11, PytesJs3.ChannelId.FAULT_REG6_CAN_BUS_LOSE)                // BIT11: CAN bus lost
								.bit(12, PytesJs3.ChannelId.FAULT_REG6_MODEL_MISMATCH)              // BIT12: Model mismatch
								.bit(13, PytesJs3.ChannelId.FAULT_REG6_3P_CREATE_FAIL)              // BIT13: 3P parallel create failed
								.bit(14, PytesJs3.ChannelId.FAULT_REG6_ACBK_OPEN)                   // BIT14: AC breaker open
								.bit(15, PytesJs3.ChannelId.FAULT_REG6_RESERVED_15)),               // BIT15: Reserved

						// reg 33125: Fault Code 07 (U16, Appendix 4) — hardware/startup faults
						m(new BitsWordElement(33125, this)
								.bit(0,  PytesJs3.ChannelId.FAULT_REG7_REVE_DC)                     // BIT00: Reverse DC
								.bit(1,  PytesJs3.ChannelId.FAULT_REG7_BATTERY_HW_OVERVOLTAGE_02)   // BIT01: Battery HW overvoltage 02
								.bit(2,  PytesJs3.ChannelId.FAULT_REG7_BATTERY_HW_OVERCURRENT)      // BIT02: Battery HW overcurrent
								.bit(3,  PytesJs3.ChannelId.FAULT_REG7_BUS_MIDPOINT_HW_OVERCURRENT) // BIT03: Bus midpoint HW overcurrent
								.bit(4,  PytesJs3.ChannelId.FAULT_REG7_BATTERY_STARTUP_FAIL)        // BIT04: Battery startup fail
								.bit(5,  PytesJs3.ChannelId.FAULT_REG7_DC3_AVG_OVERCURRENT)         // BIT05: DC3 average overcurrent
								.bit(6,  PytesJs3.ChannelId.FAULT_REG7_DC4_AVG_OVERCURRENT)         // BIT06: DC4 average overcurrent
								.bit(7,  PytesJs3.ChannelId.FAULT_REG7_SOFTRUN_TIMEOUT)             // BIT07: Soft-start timeout
								.bit(8,  PytesJs3.ChannelId.FAULT_REG7_OFFGRID_TO_GRID_TIMEOUT)     // BIT08: Off-grid to grid timeout
								.bit(9,  PytesJs3.ChannelId.FAULT_REG7_DRM_NOT_CONNECT)             // BIT09: DRM not connected
								.bit(10, PytesJs3.ChannelId.FAULT_REG7_RESERVED_10)
								.bit(11, PytesJs3.ChannelId.FAULT_REG7_RESERVED_11)
								.bit(12, PytesJs3.ChannelId.FAULT_REG7_RESERVED_12)
								.bit(13, PytesJs3.ChannelId.FAULT_REG7_RESERVED_13)
								.bit(14, PytesJs3.ChannelId.FAULT_REG7_RESERVED_14)
								.bit(15, PytesJs3.ChannelId.FAULT_REG7_RESERVED_15)),

						// reg 33126–33131 – Reserved / not documented for this model.
						new DummyRegisterElement(33126, 33131),

						// reg 33132: Storage Control Switching Value (U16, Appendix 6)
						// How the inverter uses the battery
						// Key mode-detection bits:
						//   BIT02 Off-grid mode       — 1 = inverter is running off-grid
						//   BIT05 Allow grid charge   — 0 = not allowed, 1 = allowed
						//   BIT04 Reserve battery     — 1 = backup/reserve battery mode active
						// Bit positions verified against datasheet Appendix 6.
						m(new BitsWordElement(33132, this)
								.bit(0,  PytesJs3.ChannelId.STORAGE_CTRL_SELF_USE_MODE)            // BIT00: Self-use mode
								.bit(1,  PytesJs3.ChannelId.STORAGE_CTRL_TIME_OF_USE_MODE)         // BIT01: Time-of-use mode
								.bit(2,  PytesJs3.ChannelId.STORAGE_CTRL_OFFGRID_MODE)             // BIT02: Off-grid mode
								.bit(3,  PytesJs3.ChannelId.STORAGE_CTRL_BATT_WAKEUP)              // BIT03: Battery wakeup switch
								.bit(4,  PytesJs3.ChannelId.STORAGE_CTRL_RESERVE_BATT_MODE)        // BIT04: Reserve battery mode
								.bit(5,  PytesJs3.ChannelId.STORAGE_CTRL_ALLOW_GRID_CHARGE)        // BIT05: Allow grid charge (0=NotAllow, 1=Allow)
								.bit(6,  PytesJs3.ChannelId.STORAGE_CTRL_FEED_IN_PRIORITY)         // BIT06: Feed-in priority mode
								.bit(7,  PytesJs3.ChannelId.STORAGE_CTRL_BATT_OVC)                 // BIT07: Battery OVC function
								.bit(8,  PytesJs3.ChannelId.STORAGE_CTRL_FORCE_CHARGE_PEAKSHAVING) // BIT08: Force charge / peak shaving
								.bit(9,  PytesJs3.ChannelId.STORAGE_CTRL_BATT_CURRENT_CORRECTION)  // BIT09: Battery current correction
								.bit(10, PytesJs3.ChannelId.STORAGE_CTRL_BATT_HEALING_MODE)        // BIT10: Battery healing mode
								.bit(11, PytesJs3.ChannelId.STORAGE_CTRL_PEAK_SHAVING_MODE)        // BIT11: Peak-shaving mode
								.bit(12, PytesJs3.ChannelId.STORAGE_CTRL_RESERVED_12)
								.bit(13, PytesJs3.ChannelId.STORAGE_CTRL_RESERVED_13)
								.bit(14, PytesJs3.ChannelId.STORAGE_CTRL_RESERVED_14)
								.bit(15, PytesJs3.ChannelId.STORAGE_CTRL_RESERVED_15)))

		);

	}

	// -----------------------------------------------------------------------
	// Internal state machine
	// -----------------------------------------------------------------------

	/**
	 * Determines and transitions the internal work state each cycle.
	 * Uses a 20-second hysteresis to avoid too-rapid state changes.
	 * State flow: UNDEFINED → INITIALIZING → NORMAL | WARNING | ERROR | STANDBY.
	 */
	private void defineWorkState() {
		if ((this.battery == null || this.charger == null) && this.getWorkState() != WorkState.UNDEFINED) {
			this.changeState(WorkState.WARNING);
			this.logWarn(log, "ESS not ready yet. Either battery or Charger missing or not fully initialized");
			return;
		}

		switch (this.getWorkState()) {
		case WorkState.ERROR:
			if (this.checkOperationalValues() == true) {
				this.changeState(WorkState.NORMAL);
				break;
			}			
			break;
		case WorkState.WARNING:
			if (this.getState() == Level.WARNING) {
				break; // stay in warning
			}
			if (this.getState() == Level.FAULT) {
				this.changeState(WorkState.ERROR);
				break;
			}
			this.changeState(WorkState.NORMAL);
			break;
		case WorkState.UNDEFINED:
			if (this.battery == null || this.charger == null) { //
				break;
			} else {
				this.changeState(WorkState.INITIALIZING); // Battery and chargers available. Start initialization
			}
			break;
		case WorkState.INITIALIZING:
			if (!this.setDefaultValues()) {
				break; // still writing defaults — wait
			}
			if (this.getState() == Level.OK) {
				this.changeState(WorkState.NORMAL);
			}
			break;
		case WorkState.STANDBY:
			break;
		case WorkState.NORMAL:
			if (this.getState() == Level.WARNING) {
				this.changeState(WorkState.WARNING);
				break;
			}
			if (this.getState() == Level.FAULT) {
				this.changeState(WorkState.ERROR);
				break;
			}
			if (this.checkOperationalValues() == false) {
				this.changeState(WorkState.ERROR);
				break;
			}			
			break;
		default:
			break;
		}

	}

	/**
	 * Writes the configured default values to the inverter during INITIALIZING.
	 * Returns true only when all values have been confirmed by read-back.
	 * Each call writes one register at a time and returns false to re-check next cycle.
	 */
	private boolean setDefaultValues() {
	    Integer currentForceMinSoc = this.getForceChargeSoc().get();
	    Integer currentMinSoc = this.getOverDischargeSoc().get();
	    EnableDisable currentBackupPortState = this.getBackupCircuitSetting();

	    if (currentForceMinSoc == null || currentMinSoc == null || currentBackupPortState == null) {
	        return false; // read-back not yet available
	    }

	    int targetMinSoc = this.config.minSoc();
	    int targetForceMinSoc = targetMinSoc - 1;
	    EnableDisable targetBackupPortState = this.config.enableBackupPort()
	            ? EnableDisable.ENABLE
	            : EnableDisable.DISABLE;

	    try {
	        if (currentForceMinSoc != targetForceMinSoc) {
	            this.setForceChargeSoc(targetForceMinSoc);
		        return false;
	        }
	        if (currentMinSoc != targetMinSoc) {
	            this.setOverdischargeSoc(targetMinSoc);
		        return false;
	        }
	        if (currentBackupPortState != targetBackupPortState) {
	            this.setBackupCircuitSetting(targetBackupPortState);
		        return false;
	        }

	    } catch (OpenemsNamedException e) {
	        this.log.error("Failed to write default values to inverter", e);
	        return false;
	    }

	    return true;
	}
	
	private boolean checkOperationalValues() {
		
		// ToDo
		if ( this.getInverterOperatingStatus() != InverterOperatingStatus.GRID_CONNECTED_OPERATION) {
			return false;
		}
		
		
		return true;
		
	}
	

	/**
	 * Transitions to a new work state with a 20-second hysteresis guard.
	 * @param nextState the target state
	 * @return true if the state was actually changed
	 */
	private boolean changeState(WorkState nextState) {
		var now = LocalDateTime.now();
		// avoid early transitions
		if (!now.minusSeconds(20).isAfter(this.lastDefinedWorkStateTime)) {
			return false;
		}
		this.lastDefinedWorkStateTime = now;
		if (this.getWorkState() == nextState) {
			return false;
		}
		this._setWorkState(nextState);
		return true;
	}

	// -----------------------------------------------------------------------
	// Channel listeners
	// Reg 44108 uses 2-bit groups (not single bits), so BitsWordElement cannot be used here.
	// Manual decoding via 2-bit masking is correct for this register.
	// -----------------------------------------------------------------------

	/**
	 * Installs a listener on REMOTE_DISPATCH_REAL_TIME_CONTROL_FUNCTION_SWITCH (reg 44108).
	 * Decodes the raw bitmask into four independent EnableDisable channels
	 * so individual function states can be monitored and alarmed on separately.
	 */
	private void installListeners() {
		this.getRemoteDispatchRealtimeControlFunctionSwitchChannel().onUpdate(value -> {
			if (value == null || !value.isDefined()) {
				return;
			}
			Integer raw = value.get();
			if (raw == null) {
				return;
			}

			// BIT00–01: PV shutdown — 1=Disable, 2=Enable
			int pvShutdownBits = raw & 0b11;
			if (pvShutdownBits == 1) {
				this._setPvShutdownSwitch(EnableDisable.DISABLE);
			} else if (pvShutdownBits == 2) {
				this._setPvShutdownSwitch(EnableDisable.ENABLE);
			}

			// BIT02–03: DO control — 1=Disable, 2=Enable
			int doControlBits = (raw >> 2) & 0b11;
			if (doControlBits == 1) {
				this._setDoControl(EnableDisable.DISABLE);
			} else if (doControlBits == 2) {
				this._setDoControl(EnableDisable.ENABLE);
			}

			// BIT04–05: Allow grid charge — 1=Allow, 2=Not allow
			int gridChargeBits = (raw >> 4) & 0b11;
			if (gridChargeBits == 1) {
				this._setGridChargeAllowed(EnableDisable.ENABLE);
			} else if (gridChargeBits == 2) {
				this._setGridChargeAllowed(EnableDisable.DISABLE);
			}

			// BIT06–07: Off-grid battery standby — 1=Disable, 2=Enable
			int offGridStandbyBits = (raw >> 6) & 0b11;
			if (offGridStandbyBits == 1) {
				this._setOffGridBatteryStandby(EnableDisable.DISABLE);
			} else if (offGridStandbyBits == 2) {
				this._setOffGridBatteryStandby(EnableDisable.ENABLE);
			}

			this.logDebug(this.log, "Reg44108 decoded: pv=" + pvShutdownBits
					+ " do=" + doControlBits
					+ " gridCharge=" + gridChargeBits
					+ " standby=" + offGridStandbyBits);
		});
		
		this.getFunctionStatGridConnectedChannel().onUpdate(value -> {
			if (value == null || !value.isDefined()) {
				setValue(this, SymmetricEss.ChannelId.GRID_MODE, GridMode.UNDEFINED); 
				return;
			}
			Boolean raw = value.get();

			if (raw) {
				setValue(this, SymmetricEss.ChannelId.GRID_MODE, GridMode.ON_GRID); 
			}	else  {
				setValue(this, SymmetricEss.ChannelId.GRID_MODE, GridMode.OFF_GRID); 
			} 

		});		

	}

	// -----------------------------------------------------------------------
	// Operating mode decoding (reg 33122, Appendix 8)
	// -----------------------------------------------------------------------

	/**
	 * Decodes the Operating Mode register (reg 33122, Appendix 8) into
	 * the {@link PytesJs3.ChannelId#OPERATING_MODE_DECODE} channel.
	 *
	 * <p>Why this method exists instead of BitsWordElement:
	 * The datasheet states "only one bit is valid at any time". What controllers
	 * need is the MODE INDEX (0–8), not 9 independent booleans. BitsWordElement
	 * cannot produce an index; it can only produce per-bit booleans.
	 *
	 * <p>Bit-to-mode map (Appendix 8):
	 * <pre>
	 *   BIT00 = 0 → UPS Mode (off-grid running, no alarm)
	 *   BIT01 = 1 → Self-Use Mode
	 *   BIT02 = 2 → Time of Use in Self-Use Mode
	 *   BIT03 = 3 → Feed-in Priority Mode
	 *   BIT04 = 4 → Time of Use in Feed-in Priority Mode
	 *   BIT05 = 5 → Backup Mode
	 *   BIT06 = 6 → Off-Grid Mode
	 *   BIT07 = 7 → Remote Battery Charge/Discharge Mode
	 *   BIT08 = 8 → Passive Mode
	 * </pre>
	 *
	 * <p>Undefined states and guards:
	 * <ul>
	 *   <li>raw == 0: no bit set — occurs at startup before first valid frame.
	 *       bitPos stays -1; OPERATING_MODE_DECODE is NOT written, leaving it
	 *       undefined. Control code must guard against isDefined() == false.</li>
	 *   <li>Multiple bits set: hardware defect or communication error.
	 *       The lowest active bit wins (first match in the loop). The log
	 *       emits a warning so the anomaly is visible without stopping the system.</li>
	 *   <li>Bits 9–15 set: reserved per datasheet. Ignored by the loop (i <= 8).</li>
	 * </ul>
	 *
	 * <p>Expected behaviour at key events:
	 * <ul>
	 *   <li><b>Grid unplugged</b>: inverter transitions automatically.
	 *       reg 33122 BIT06 goes to 1 → OPERATING_MODE_DECODE = Appendix8.OFF_GRID_MODE.
	 *       Simultaneously reg 33121 BIT09 (OPERATING_STAT_GRID_FAULT) = true,
	 *       and reg 33132 BIT02 (STORAGE_CTRL_OFFGRID_MODE) = true.
	 *       Reading all three channels provides cross-validation.</li>
	 *   <li><b>Backup mode toggled on</b>: reg 33122 BIT05 = 1 →
	 *       OPERATING_MODE_DECODE = Appendix8.BACKUP_MODE.
	 *       reg 33132 BIT04 (STORAGE_CTRL_RESERVE_BATT_MODE) may also become 1.</li>
	 *   <li><b>Grid restored</b>: BIT06 clears, inverter re-synchronises,
	 *       BIT01 (Self-Use) or previous mode bit becomes 1 again.</li>
	 * </ul>
	 */
	private void decodeOperatingMode() {
		var rawOpt = this.channel(PytesJs3.ChannelId.OPERATING_MODE).value();
		if (!rawOpt.isDefined()) {
			// Modbus data not yet available — leave OPERATING_MODE_DECODE unchanged.
			return;
		}

		int raw = (int) rawOpt.get();

		// Guard: raw == 0 means no mode bit is set (startup / comms gap).
		// Do not write -1 into the channel — leave it as previously decoded.
		if (raw == 0) {
			return;
		}

		// Find the lowest-numbered active bit (0–8).
		// Bits 9–15 are reserved per Appendix 8 and are intentionally excluded.
		int bitPos = -1;
		for (int i = 0; i <= 8; i++) {
			if ((raw & (1 << i)) != 0) {
				bitPos = i;
				break;
			}
		}

		if (bitPos == -1) {
			// Non-zero raw but no bit in 0–8 range. Reserved bits only — ignore.
			return;
	}

		// Warn when multiple bits are set simultaneously — hardware anomaly.
		int setBitCount = Integer.bitCount(raw & 0x01FF); // only bits 0–8
		if (setBitCount > 1) {
			this.logWarn(this.log,
					"reg 33122 (Operating Mode): " + setBitCount + " bits set simultaneously "
					+ "(raw=0x" + Integer.toHexString(raw) + "). "
					+ "Using lowest active bit " + bitPos + ". "
					+ "Possible hardware defect or Modbus noise.");
		}

		this.channel(PytesJs3.ChannelId.OPERATING_MODE_DECODE).setNextValue(bitPos);
		this.logDebug(this.log, "OperatingMode decoded: bit " + bitPos
				+ " raw=0x" + Integer.toHexString(raw));
	}

	// -----------------------------------------------------------------------
	// Component wiring — battery, charger
	// -----------------------------------------------------------------------

	@Override
	public void addBattery(PytesBattery battery) {
		this.battery = battery;
		this.setPowerHandlers();
	}

	@Override
	public void removeBattery(PytesBattery battery) {
		if (this.battery == battery) {
			this.battery = null;
		}
		this.setPowerHandlers();
	}

	@Override
	public void addCharger(PytesDcCharger charger) {
		this.charger = charger;
		this.setPowerHandlers();
	}

	@Override
	public void removeCharger(PytesDcCharger charger) {
		if (this.charger == charger) {
			this.charger = null;
		}
		this.setPowerHandlers();
	}

	/**
	 * Creates or destroys the power handlers when battery/charger availability changes.
	 * Both battery AND charger must be present before handlers are created.
	 */
	private void setPowerHandlers() {
		if (this.battery != null && this.charger != null) {
			this.applyPowerHandler = new ApplyPowerHandler(this, this.battery, this.charger);
			this.allowedChargeDischargeHandler = new AllowedChargeDischargeHandler(
					this, this.battery, this.charger, this.config.essSetpoint());
		} else {
			this.applyPowerHandler = null;
			this.allowedChargeDischargeHandler = null;
		}
	}

	@Override
	public String debugLog() {
		if (config.debugMode()) {
			return "SoC:" + this.getSoc().asString() //
					+ "|L:" + this.getActivePower().asString()

					
					+ this.channel(SymmetricEss.ChannelId.REACTIVE_POWER).value().asString() + "\nMaxApparentPower="
					+ this.channel(SymmetricEss.ChannelId.MAX_APPARENT_POWER).value().asString() + "\nSafetyVersion="
					+ this.channel(PytesJs3.ChannelId.SAFETY_VERSION).value().asString() + "\nHmiSubVersion="
					+ this.channel(PytesJs3.ChannelId.HMI_SUB_VERSION).value().asString() + "\nStandardWorkingMode="
					+ this.channel(PytesJs3.ChannelId.STANDARD_WORKING_MODE).value().asString() + "\nAlarmCodeData="
					+ this.channel(PytesJs3.ChannelId.ALARM_CODE_DATA).value().asString() + "\nDcBusVoltage="
					+ this.channel(PytesJs3.ChannelId.DC_BUS_VOLTAGE).value().asString() + "\nDcBusHalfVoltage="
					+ this.channel(PytesJs3.ChannelId.DC_BUS_HALF_VOLTAGE).value().asString() + "\nVoltageL1="
					+ this.channel(PytesJs3.ChannelId.VOLTAGE_L1).value().asString() + "\nVoltageL2="
					+ this.channel(PytesJs3.ChannelId.VOLTAGE_L2).value().asString() + "\nVoltageL3="
					+ this.channel(PytesJs3.ChannelId.VOLTAGE_L3).value().asString() + "\nCurrentL1="
					+ this.channel(PytesJs3.ChannelId.CURRENT_L1).value().asString() + "\nCurrentL2="
					+ this.channel(PytesJs3.ChannelId.CURRENT_L2).value().asString() + "\nCurrentL3="
					+ this.channel(PytesJs3.ChannelId.CURRENT_L3).value().asString() + "\nActivePower="
					+ this.channel(SymmetricEss.ChannelId.ACTIVE_POWER).value().asString() + "\nReactivePower="
					+ this.channel(SymmetricEss.ChannelId.REACTIVE_POWER).value().asString() + "\nApparentPower="
					+ this.channel(PytesJs3.ChannelId.APPARENT_POWER).value().asString() + "\nInverterCurrentStatus="
					+ this.channel(PytesJs3.ChannelId.INVERTER_CURRENT_STATUS).value().asString() + "\nOperatingMode="
					+ this.channel(PytesJs3.ChannelId.OPERATING_MODE).value().asString() + "\nFrequency="
					+ this.channel(PytesJs3.ChannelId.FREQUENCY).value().asString()

			;

		} else {
			return "|SoC:" + this.getSoc().asString() //
					+ "|L:" + this.getActivePower().asString() //
					+ "|DcDischarge:" + this.getDcDischargePower().asString() + "|Allowed:"
					+ this.getAllowedChargePower().asString() + ";" + this.getAllowedDischargePower().asString(); //
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public String getModbusBridgeId() {
		return this.config.modbus_id();
	}


	@Override
	public void retryModbusCommunication() {
		// TODO Auto-generated method stub

	}

	@Override
	public void applyPower(int activePowerL1, int reactivePowerL1, int activePowerL2, int reactivePowerL2,
			int activePowerL3, int reactivePowerL3) throws OpenemsNamedException {
		// TODO Auto-generated method stub

	}

	@Override
	public void applyPower(int targetActivePower, int reactivePower) throws OpenemsNamedException {

		if (this.battery == null) {
			this.applyPowerHandler = null;

			return;
		}

		logDebug(this.log, "ApplyPower: ActivePowerTarget = " + targetActivePower);

		if (this.applyPowerHandler != null) {
			this.applyPowerHandler.apply(targetActivePower, reactivePower, this.config.maxApparentPower(),
					this.config.essSetpoint());
		}

	}

	protected String collectDebugData() {
		// Collect channel values in one stream
		return Stream.of(OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				HybridEss.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				AsymmetricEss.ChannelId.values(), //
				ManagedAsymmetricEss.ChannelId.values(), //
				PytesJs3.ChannelId.values() // //
		).flatMap(Arrays::stream).map(id -> {
			try {
				return id.name() + "=" + this.channel(id).value().asString();
			} catch (Exception e) {
				return id.name() + "=n/a";
			}
		}).collect(Collectors.joining("; \n"));
	}	
	
	/**
	 * Uses Info Log for further debug features.
	 */
	public void logDebug() {
		if (this.config.debugMode()) {

			if (this.config.extendedDebugMode()) {
				this.logInfo(this.log,
						"\n ############################################## ESS Values Start #############################################");
				this.logInfo(log, this.collectDebugData());
				this.logInfo(log,
						"\n ############################################## ESS Values End #############################################");

			}

		}
	}	

	public Logger getLogger() {
		return this.log;
	}

	// for use in handler-classes
	public void debugLog(String message) {
		this.logDebug(this.log, message);
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public int getPowerPrecision() {

		return 10;
	}

	@Override
	public Integer getSurplusPower() {
		// TODO Auto-generated method stub
		return null;
	}

	public int getCycleTime() {
		return this.cycle != null ? this.cycle.getCycleTime() : DEFAULT_CYCLE_TIME;
	}

	@Override
	public boolean isManaged() {
		return !this.config.readOnlyMode();
	}
	
	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public Clock getClock() {
		// TODO Auto-generated method stub
		return null;
	}
	
}
