package io.openems.edge.pvinverter.sma;

import java.util.Map;

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
import com.google.common.collect.ImmutableMap;
import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsException;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.sunspec.DefaultSunSpecModel;
import io.openems.edge.bridge.modbus.sunspec.SunSpecModel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.sunspec.AbstractSunSpecPvInverter;
import io.openems.edge.pvinverter.sunspec.SunSpecPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "PV-Inverter.SMA.SunnyTripower", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE //
})
public class PvInverterSmaSunnyTripowerImpl extends AbstractSunSpecPvInverter
		implements PvInverterSmaSunnyTripower, SunSpecPvInverter, ManagedSymmetricPvInverter, ElectricityMeter,
		ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave {

	private static final Map<SunSpecModel, Priority> ACTIVE_MODELS = ImmutableMap.<SunSpecModel, Priority>builder()
			// before 2023
			.put(DefaultSunSpecModel.S_1, Priority.LOW) // from 40002
			// .put(DefaultSunSpecModel.S_101, Priority.LOW) // from 40081
			.put(DefaultSunSpecModel.S_103, Priority.HIGH) // from 40185
			.put(DefaultSunSpecModel.S_120, Priority.LOW) // from 40237
			// .put(DefaultSunSpecModel.S_121, Priority.LOW) // from 40265
			// .put(DefaultSunSpecModel.S_122, Priority.LOW) // from 40297
			.put(DefaultSunSpecModel.S_123, Priority.LOW) // from 40343 before 2023, from 40070 since 2023
			//.put(DefaultSunSpecModel.S_160, Priority.LOW) // from 40621
			// since 2023
			.put(DefaultSunSpecModel.S_701, Priority.HIGH) // from 40096
			.put(DefaultSunSpecModel.S_704, Priority.LOW) // from 40251
			.build();

	// Further available SunSpec blocks provided by SMA Sunny TriPower are:
	// .put(DefaultSunSpecModel.S_11, Priority.LOW) // from 40070
	// .put(DefaultSunSpecModel.S_12, Priority.LOW) // from 40085
	// .put(DefaultSunSpecModel.S_124, Priority.LOW) // from 40369
	// .put(DefaultSunSpecModel.S_126, Priority.LOW) // from 40395
	// .put(DefaultSunSpecModel.S_127, Priority.LOW) // from 40461
	// .put(DefaultSunSpecModel.S_128, Priority.LOW) // from 40473
	// .put(DefaultSunSpecModel.S_131, Priority.LOW) // from 40489
	// .put(DefaultSunSpecModel.S_132, Priority.LOW) // from 40555
	// .put(DefaultSunSpecModel.S_160, Priority.LOW) // from 40621
	// .put(DefaultSunSpecModel.S_129, Priority.LOW) // from 40751
	// .put(DefaultSunSpecModel.S_130, Priority.LOW) // from 40813
	// since 2023:
	// .put(DefaultSunSpecModel.S_703, Priority.LOW) // from 40303
	// .put(DefaultSunSpecModel.S_704, Priority.LOW) // from 40322
	// .put(DefaultSunSpecModel.S_705, Priority.LOW) // from 40389
	// .put(DefaultSunSpecModel.S_706, Priority.LOW) // from 40456
	// .put(DefaultSunSpecModel.S_707, Priority.LOW) // from 40513
	// .put(DefaultSunSpecModel.S_708, Priority.LOW) // from 40656
	// .put(DefaultSunSpecModel.S_709, Priority.LOW) // from 40799
	// .put(DefaultSunSpecModel.S_710, Priority.LOW) // from 40936
	// .put(DefaultSunSpecModel.S_711, Priority.LOW) // from 41073
	// .put(DefaultSunSpecModel.S_712, Priority.LOW) // from 41107
	// .put(DefaultSunSpecModel.S_714, Priority.LOW) // from 41161

	private static final int READ_FROM_MODBUS_BLOCK = 1;

	private final Logger log = LoggerFactory.getLogger(PvInverterSmaSunnyTripowerImpl.class);

	@Reference
	private ConfigurationAdmin cm;

	private Config config;
	private static final int BASE_ADDRESS = 40623; // Starting address for S160 Block / Number of Strings
	private static final int MODULE_START_ADDRESS = BASE_ADDRESS + 17; // Starting address for modules
	private static final int REGISTER_OFFSET = 20; // Number of registers per module
	private boolean staticTasksAdded = false;	
	private int numberOfModules = 0;	

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public PvInverterSmaSunnyTripowerImpl() {
		super(//
				ACTIVE_MODELS, //
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				SunSpecPvInverter.ChannelId.values(), //
				PvInverterSmaSunnyTripower.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.readOnly(),
				config.modbusUnitId(), this.cm, "Modbus", config.modbus_id(), READ_FROM_MODBUS_BLOCK, config.phase())) {
			return;
		}
		this.config = config;
		this.addInitialModbusTask(this.getModbusProtocol());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		super.handleEvent(event);
		switch (event.getTopic()) {

		case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE:

			try {

				this.pvDataHandler();
			} catch (OpenemsNamedException e) {
				log.warn("Cannot write S160 data yet");
			}

			break;
		}

	}
	
	/**
	 * Adds the initial Modbus task to read the number of modules and scale factors.
	 *
	 * @param protocol the {@link ModbusProtocol}
	 * @throws OpenemsException on error
	 */
	private void addInitialModbusTask(ModbusProtocol protocol) throws OpenemsException {
		protocol.addTask(//
				new FC3ReadRegistersTask(BASE_ADDRESS, Priority.HIGH,

						m(PvInverterSmaSunnyTripower.ChannelId.DCA_SF, new SignedWordElement(BASE_ADDRESS)),
						m(PvInverterSmaSunnyTripower.ChannelId.DCV_SF, new SignedWordElement(BASE_ADDRESS + 1)),
						m(PvInverterSmaSunnyTripower.ChannelId.DCW_SF, new SignedWordElement(BASE_ADDRESS + 2)),
						m(PvInverterSmaSunnyTripower.ChannelId.DCWH_SF, new SignedWordElement(BASE_ADDRESS + 3)),
						new DummyRegisterElement(BASE_ADDRESS + 4, BASE_ADDRESS + 5),
						m(PvInverterSmaSunnyTripower.ChannelId.N, new SignedWordElement(BASE_ADDRESS + 6))
						
				));
	}

	private void addStaticModbusTasks(ModbusProtocol protocol, int numberOfModules) throws OpenemsException {
		for (int i = 0; i < numberOfModules; i++) {
			int moduleBaseAddress = MODULE_START_ADDRESS + (i * REGISTER_OFFSET);
			String currentChannelName = "ST" + (i + 1) + "_DC_CURRENT_INTERNAL";
			String voltageChannelName = "ST" + (i + 1) + "_DC_VOLTAGE_INTERNAL";
			String powerChannelName = "ST" + (i + 1) + "_DC_POWER_INTERNAL";
			String energyChannelName = "ST" + (i + 1) + "_DC_ENERGY_INTERNAL";

			protocol.addTask(//
					new FC3ReadRegistersTask(moduleBaseAddress, Priority.LOW,
							m(PvInverterSmaSunnyTripower.ChannelId.valueOf(currentChannelName), //
									new UnsignedWordElement(moduleBaseAddress)),
							m(PvInverterSmaSunnyTripower.ChannelId.valueOf(voltageChannelName), //
									new UnsignedWordElement(moduleBaseAddress + 1)),
							m(PvInverterSmaSunnyTripower.ChannelId.valueOf(powerChannelName), //
									new UnsignedWordElement(moduleBaseAddress + 2)),
							m(PvInverterSmaSunnyTripower.ChannelId.valueOf(energyChannelName), //
									new UnsignedDoublewordElement(moduleBaseAddress + 3))));
		}

	}	
	private void pvDataHandler() throws OpenemsNamedException {

		if (!this.isSunSpecInitializationCompleted()) {
			// Do nothing until SunSpec is initialized
			return;
		}
		if (this.staticTasksAdded == false) {

			try { // We need to know the number of modules
				IntegerReadChannel numberOfModulesChannel = this.channel(PvInverterSmaSunnyTripower.ChannelId.N);
				this.numberOfModules = numberOfModulesChannel.value().getOrError().intValue();
			} catch (OpenemsException e) {
				this.log.error("Number of modules unknown");
				return;
			}
			if (this.numberOfModules > 0) {

				try {
					this.addStaticModbusTasks(this.getModbusProtocol(), this.numberOfModules);
					this.staticTasksAdded = true;
					return;
				} catch (OpenemsException e) {
					this.log.error("Error adding static Modbus tasks", e);
				}
			}

		}

		// modbus Task is active and Sunspec is initialized
		IntegerReadChannel currentScaleFactorChannel = this.channel(PvInverterSmaSunnyTripower.ChannelId.DCA_SF);
		int currentScaleFactor = currentScaleFactorChannel.value().getOrError().intValue();

		IntegerReadChannel voltageScaleFactorChannel = this.channel(PvInverterSmaSunnyTripower.ChannelId.DCV_SF);
		int voltageScaleFactor = voltageScaleFactorChannel.value().getOrError().intValue();

		IntegerReadChannel powerScaleFactorChannel = this.channel(PvInverterSmaSunnyTripower.ChannelId.DCW_SF);
		int powerScaleFactor = powerScaleFactorChannel.value().getOrError().intValue();

		IntegerReadChannel energyScaleFactorChannel = this.channel(PvInverterSmaSunnyTripower.ChannelId.DCWH_SF);
		int energyScaleFactor = energyScaleFactorChannel.value().getOrError().intValue();

		for (int i = 0; i < this.numberOfModules; i++) {

			// Internal values without scale factor
			String currentChannelNameInternal = "ST" + (i + 1) + "_DC_CURRENT_INTERNAL";
			String voltageChannelNameInternal = "ST" + (i + 1) + "_DC_VOLTAGE_INTERNAL";
			String powerChannelNameInternal = "ST" + (i + 1) + "_DC_POWER_INTERNAL";
			String energyChannelNameInternal = "ST" + (i + 1) + "_DC_ENERGY_INTERNAL";

			IntegerReadChannel currentChannelInternal = this.getChannelByName(currentChannelNameInternal);
			IntegerReadChannel voltageChannelInternal = this.getChannelByName(voltageChannelNameInternal);
			IntegerReadChannel powerChannelInternal = this.getChannelByName(powerChannelNameInternal);
			IntegerReadChannel energyChannelInternal = this.getChannelByName(energyChannelNameInternal);

			// Target Channels
			String currentChannelName = "ST" + (i + 1) + "_DC_CURRENT";
			String voltageChannelName = "ST" + (i + 1) + "_DC_VOLTAGE";
			String powerChannelName = "ST" + (i + 1) + "_DC_POWER";
			String energyChannelName = "ST" + (i + 1) + "_DC_ENERGY";

			this.updateChannelValues(currentChannelInternal, currentChannelName, currentScaleFactor);
			this.updateChannelValues(voltageChannelInternal, voltageChannelName, voltageScaleFactor);
			this.updateChannelValues(powerChannelInternal, powerChannelName, powerScaleFactor);
			this.updateChannelValues(energyChannelInternal, energyChannelName, energyScaleFactor);
		}

	}

	private IntegerReadChannel getChannelByName(String channelName) {
		try {
			return this.channel(PvInverterSmaSunnyTripower.ChannelId.valueOf(channelName));
		} catch (IllegalArgumentException e) {
			this.log.error("Channel with name [" + channelName + "] does not exist.", e);
			return null;
		}
	}

	/**
	 * Updates the channel values based on the scale factor.
	 *
	 * @param internalChannel     the internal channel
	 * @param externalChannelName the external channel name
	 * @param scaleFactor         the scale factor
	 * @throws OpenemsNamedException on error
	 */
	private void updateChannelValues(IntegerReadChannel internalChannel, String externalChannelName, int scaleFactor)
			throws OpenemsNamedException {
		if (internalChannel != null) {
			int value = internalChannel.value().getOrError().intValue();
			double scaledValue = value * Math.pow(10, scaleFactor);
			IntegerReadChannel externalChannel = this.getChannelByName(externalChannelName);
			if (externalChannel != null) {
				externalChannel.setNextValue((int) scaledValue);
			}
		}
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode));
	}

	/**
	 * Uses Info Log for further debug features.
	 */
	@Override
	protected void logDebug(Logger log, String message) {
		if (this.config.debugMode()) {
			this.logInfo(this.log, message);
		}
	}
}
