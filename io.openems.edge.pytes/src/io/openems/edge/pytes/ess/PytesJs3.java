package io.openems.edge.pytes.ess;

import static io.openems.common.channel.AccessMode.READ_ONLY;
import static io.openems.common.channel.AccessMode.READ_WRITE;
import static io.openems.common.channel.AccessMode.WRITE_ONLY;
import static io.openems.common.channel.PersistencePriority.HIGH;
import static io.openems.common.channel.PersistencePriority.LOW;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.BOOLEAN;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.pytes.battery.PytesBattery;
import io.openems.edge.pytes.dccharger.PytesDcCharger;
import io.openems.edge.pytes.enums.Appendix2;
import io.openems.edge.pytes.enums.Appendix8;
import io.openems.edge.pytes.enums.EnableDisable;
import io.openems.edge.pytes.enums.RemoteDispatchRealtimeControlSwitch;
import io.openems.edge.pytes.enums.RemoteDispatchSystemLimitSwitch;
import io.openems.edge.pytes.enums.StandardWorkingMode;
import io.openems.edge.pytes.enums.WorkState;
import io.openems.edge.pytes.enums.BatteryBmsStatus;

public interface PytesJs3 extends OpenemsComponent, EventHandler {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		// -----------------------------------------------------------------------
		// Internal work state machine — not a Modbus register
		// -----------------------------------------------------------------------

		/**
		 * Internal OpenEMS work state (not a Modbus register).
		 * Managed by defineWorkState() in PytesJs3Impl.
		 * Transitions: UNDEFINED → INITIALIZING → NORMAL | WARNING | ERROR | STANDBY.
		 */
		WORK_STATE(Doc.of(WorkState.values())
				.accessMode(READ_WRITE)
				.persistencePriority(HIGH)),

		// -----------------------------------------------------------------------
		// Holding registers — SoC limits (reg 43010, 43011, 43018)
		// FC16 write / FC3 read. Priority LOW — configuration, survives power cycle.
		// -----------------------------------------------------------------------

		/**
		 * Max Charge SOC — write channel (reg 43010, U16, FC16).
		 * Datasheet: "Max Charge SOC. 1% resolution. Range: 80–100%. Default: 100%.
		 * The inverter stops charging when battery SoC reaches this level."
		 * Unit: %
		 */
		SET_MAX_CHARGE_SOC(Doc.of(INTEGER)
				.accessMode(WRITE_ONLY)
				.unit(Unit.PERCENT)),

		/**
		 * Max Charge SOC — read-back channel (reg 43010, U16, FC3).
		 * Confirms the value currently stored in the inverter's flash.
		 * Unit: %
		 */
		MAX_CHARGE_SOC(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.PERCENT)
				.persistencePriority(LOW)),

		/**
		 * Overdischarge SOC — write channel (reg 43011, U16, FC16).
		 * Datasheet: "Overdischarge SOC. 1% resolution. Range: 5–40%. Default: 20%.
		 * The inverter stops discharging when battery SoC reaches this level.
		 * Must always be >= Force Charge SOC (reg 43018)."
		 * Unit: %
		 */
		SET_OVERDISCHARGE_SOC(Doc.of(INTEGER)
				.accessMode(WRITE_ONLY)
				.unit(Unit.PERCENT)),

		/**
		 * Overdischarge SOC — read-back channel (reg 43011, U16, FC3).
		 * Used by setDefaultValues() to confirm the written value was accepted.
		 * Unit: %
		 */
		OVERDISCHARGE_SOC(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.PERCENT)
				.persistencePriority(LOW)),

		/**
		 * Force Charge SOC — write channel (reg 43018, U16, FC16).
		 * Datasheet: "Force Charge SOC. 1% resolution. Range: 4% up to reg 43011. Default: 10%.
		 * Emergency floor — the inverter forces a grid charge if SoC falls to this level.
		 * Must always be <= Overdischarge SOC (reg 43011)."
		 * setDefaultValues() writes config.minSoc()-1 here.
		 * Unit: %
		 */
		SET_FORCE_CHARGE_SOC(Doc.of(INTEGER)
				.accessMode(WRITE_ONLY)
				.unit(Unit.PERCENT)),

		/**
		 * Force Charge SOC — read-back channel (reg 43018, U16, FC3).
		 * Unit: %
		 */
		FORCE_CHARGE_SOC(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.PERCENT)
				.persistencePriority(LOW)),

		// -----------------------------------------------------------------------
		// Holding register — Backup circuit setting (reg 43111)
		// FC16 write / FC3 read. Priority LOW — survives power cycle.
		// -----------------------------------------------------------------------

		/**
		 * Backup circuit setting — write channel (reg 43111, U16, FC16).
		 * Datasheet: "Backup circuit setting. 0x0000 = disable, 0x0001 = enable. Default: enable."
		 * Controls the physical relay on the backup AC port.
		 * Written during INITIALIZING based on config.enableBackupPort().
		 */
		SET_BACKUP_CIRCUIT_SETTING(Doc.of(EnableDisable.values())
				.accessMode(WRITE_ONLY)
				.text("Backup circuit setting — write to change")),

		/**
		 * Backup circuit setting — read-back channel (reg 43111, U16, FC3).
		 * Confirms the current hardware state of the backup port relay.
		 * To change: write to SET_BACKUP_CIRCUIT_SETTING.
		 */
		BACKUP_CIRCUIT_SETTING(Doc.of(EnableDisable.values())
				.accessMode(READ_ONLY)
				.text("Backup circuit setting — current hardware state")),

		// -----------------------------------------------------------------------
		// Remote Dispatch Registers
		// -----------------------------------------------------------------------

		/** Remote Dispatch Switch — write channel (reg 44100, U16, FC16).
		 * 0 = OFF (disable remote dispatch), 1 = ON. Not saved after power cycle. */
		SET_REMOTE_DISPATCH_SWITCH(Doc.of(EnableDisable.values())
				.accessMode(AccessMode.WRITE_ONLY)
		        .text("Remote dispatch switch. 0 = OFF, 1 = ON")),

		/** Remote Dispatch Switch — read-back channel (reg 44100, U16, FC3). */
		REMOTE_DISPATCH_SWITCH(Doc.of(EnableDisable.values())
				.accessMode(AccessMode.READ_ONLY)
		        .text("Remote dispatch switch. 0 = OFF, 1 = ON")),

		/** Remote Dispatch Failsafe — write channel (reg 44101, U16, FC16).
		 * Timeout in minutes (1–1440). Default 5. If the remote dispatch heartbeat stops,
		 * the inverter reverts to local control after this timeout.
		 * Write 0xFFFF to reset to default. Not saved after power cycle. */
		SET_REMOTE_DISPATCH_FAILSAFE_SETTING(Doc.of(INTEGER)
				.accessMode(AccessMode.WRITE_ONLY)
				.unit(Unit.MINUTE)),

		/** Remote Dispatch Failsafe — read-back channel (reg 44101, U16, FC3). */
		REMOTE_DISPATCH_FAILSAFE_SETTING(Doc.of(INTEGER)
				.accessMode(AccessMode.READ_ONLY)
				.unit(Unit.MINUTE)),

		/** System Limit Switch — write channel (reg 44102, U16, FC16).
		 * BIT00=import limit enabled, BIT01=export limit enabled. Not saved after power cycle. */
		SET_REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH(Doc.of(RemoteDispatchSystemLimitSwitch.values())
				.accessMode(AccessMode.WRITE_ONLY)),

		/** System Limit Switch — read-back channel (reg 44102, U16, FC3). */
		REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH(Doc.of(RemoteDispatchSystemLimitSwitch.values())
				.accessMode(AccessMode.READ_ONLY)),

		/** System Import Limit — write channel (reg 44103, U16, FC16).
		 * Datasheet: 1 unit = 100 W. Write 0xFFFF to reset to rated power.
		 * Active only when REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH BIT00 = 1.
		 * Not saved after power cycle. */
		SET_REMOTE_DISPATCH_SYSTEM_IMPORT_LIMIT(Doc.of(INTEGER)
				.accessMode(AccessMode.WRITE_ONLY)
				.unit(Unit.WATT)),

		/** System Import Limit — read-back channel (reg 44103, U16, FC3). 1 unit = 100 W. */
		REMOTE_DISPATCH_SYSTEM_IMPORT_LIMIT(Doc.of(INTEGER)
				.accessMode(AccessMode.READ_ONLY)
				.unit(Unit.WATT)),

		/** System Export Limit — write channel (reg 44104, U16, FC16).
		 * Datasheet: 1 unit = 100 W. Write 0xFFFF to reset to rated power.
		 * Active only when REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH BIT01 = 1.
		 * Not saved after power cycle. */
		SET_REMOTE_DISPATCH_SYSTEM_EXPORT_LIMIT(Doc.of(INTEGER)
				.accessMode(AccessMode.WRITE_ONLY)
				.unit(Unit.WATT)),

		/** System Export Limit — read-back channel (reg 44104, U16, FC3). 1 unit = 100 W. */
		REMOTE_DISPATCH_SYSTEM_EXPORT_LIMIT(Doc.of(INTEGER)
				.accessMode(AccessMode.READ_ONLY)
				.unit(Unit.WATT)),
		
		/**
		 * Remote Dispatch Real-Time Control Switch — write channel (reg 44105, U16, FC16).
		 * Datasheet: 1=Battery Standby, 2=Battery charge/discharge control,
		 * 3=Grid connection point control, 4=AC grid port control. Default: 1.
		 * Value 0 is invalid per datasheet; UNDEFINED(0) is the sentinel only.
		 */
		SET_REMOTE_DISPATCH_REALTIME_CONTROL_SWITCH(
				Doc.of(RemoteDispatchRealtimeControlSwitch.values())
				.accessMode(AccessMode.WRITE_ONLY)),

		/**
		 * Remote Dispatch Real-Time Control Switch — read-back channel (reg 44105, U16, FC3).
		 */
		REMOTE_DISPATCH_REALTIME_CONTROL_SWITCH(
				Doc.of(RemoteDispatchRealtimeControlSwitch.values())
				.accessMode(AccessMode.READ_ONLY)),
		
		/** Real-Time Control Function Switch — write channel (reg 44108, U16, FC16).
		 * Four 2-bit groups: BIT00–01=PV shutdown, BIT02–03=DO control,
		 * BIT04–05=grid charge allowed, BIT06–07=off-grid battery standby.
		 * Encoding per group: 1=Disable/NotAllow, 2=Enable/Allow.
		 * Use setRemoteDispatchRealtimeControlFunctionSwitch() for a boolean-friendly API.
		 * Not saved after power cycle. */
		SET_REMOTE_DISPATCH_REALTIME_CONTROL_FUNCTION_SWITCH(Doc.of(INTEGER)
				.accessMode(AccessMode.WRITE_ONLY)),

		/** Real-Time Control Function Switch — read-back channel (reg 44108, U16, FC3). */
		REMOTE_DISPATCH_REALTIME_CONTROL_FUNCTION_SWITCH(Doc.of(INTEGER) 
				.accessMode(AccessMode.READ_ONLY)),

		// -----------------------------------------------------------------------
		// Main input registers - inverter status, AC measurements, faults
		// -----------------------------------------------------------------------
		/** Safety (grid code) version number (reg 33068, U16, FC4).
		 * Raw integer identifying the installed grid-code firmware version. No unit. */
		SAFETY_VERSION(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		/** HMI sub-version number (reg 33069, U16, FC4).
		 * Combine with reg 33002 (firmware major version) to form the complete HMI version string. */
		HMI_SUB_VERSION(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		/*
		 * Add Alarm code data to distinguishing displayed Alarm code For external fan
		 * failure, each bit indicates the status of one fan; In conjunction with the
		 * 33095 register address, it is used for subdivided fault information display.
		 * Example: 33095 register read information is 0x1020,
		 */
		ALARM_CODE_DATA(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		DC_BUS_VOLTAGE(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(HIGH)),

		DC_BUS_HALF_VOLTAGE(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(LOW)),

		VOLTAGE_L1(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(HIGH)),

		VOLTAGE_L2(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(HIGH)),

		VOLTAGE_L3(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(HIGH)),

		CURRENT_L1(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(HIGH)),

		CURRENT_L2(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(HIGH)),

		CURRENT_L3(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(HIGH)),

		APPARENT_POWER(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.VOLT_AMPERE)
				.persistencePriority(HIGH)),
		
		STANDARD_WORKING_MODE(Doc.of(StandardWorkingMode.values())
				.accessMode(AccessMode.READ_ONLY)),

		FREQUENCY(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIHERTZ)
				.persistencePriority(HIGH)),

		INVERTER_CURRENT_STATUS(Doc.of(Appendix2.values())
				.accessMode(AccessMode.READ_ONLY)),

		LEAD_ACID_BATTERY_TEMP(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		/**
		 * Function Status (reg 33097, U16, read-only).
		 *
		 * <p>Datasheet: "Function status bitmask." Each bit is independent.
		 * The raw word is stored here; individual bits are decoded by BitsWordElement
		 * into FUNCTION_STAT_* boolean channels below.
		 *
		 * <pre>
		 *   BIT00 DRM function               0=OFF, 1=ON
		 *   BIT01 Parallel running status    0=stopped, 1=running
		 *   BIT02 Master/slave               0=slave, 1=master
		 *   BIT03 3PH unbalance status       0=balanced, 1=unbalanced
		 *   BIT04 Generator start conditions 0=not met, 1=met
		 *   BIT05 Generator started          0=no, 1=yes
		 *   BIT06 Battery mode               0=parallel, 1=independent
		 *   BIT07 AFCI board present         0=no, 1=yes
		 *   BIT08 AFCI self-test done        0=no, 1=finished
		 *   BIT09 Grid connection            0=off-grid, 1=grid-connected
		 *   BIT10 Double backup enabled      0=disabled, 1=enabled
		 *   BIT11 RSD switch (S6 HV only)    0=open, 1=closed
		 *   BIT12 Emergency switch (S6 HV)   0=open, 1=closed
		 *   BIT13 AC coupling running        0=not running, 1=running
		 *   BIT14-15 Reserved
		 * </pre>
		 */

		// decoded boolean sub-channels (filled by BitsWordElement in defineModbusProtocol):
		
		/** reg 33097 BIT00 – DRM function enabled */
		FUNCTION_STAT_DRM(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("DRM function (reg 33097 BIT00)")),
		
		/** reg 33097 BIT01 – Parallel running */
		FUNCTION_STAT_PARALLEL_RUNNING(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Parallel running (reg 33097 BIT01)")),
		
		/** reg 33097 BIT02 – Master (1) or slave (0) */
		FUNCTION_STAT_MASTER(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Master/slave: 1=master (reg 33097 BIT02)")),
		
		/** reg 33097 BIT03 – 3-phase unbalanced operation */
		FUNCTION_STAT_3PH_UNBALANCED(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("3PH unbalanced operation (reg 33097 BIT03)")),
		
		/** reg 33097 BIT04 – Generator start conditions met */
		FUNCTION_STAT_GEN_START_CONDITIONS(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Generator start conditions met (reg 33097 BIT04)")),
		
		/** reg 33097 BIT05 – Generator started successfully */
		FUNCTION_STAT_GEN_STARTED(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Generator started (reg 33097 BIT05)")),
		
		/** reg 33097 BIT06 – Battery independent (1) or parallel (0) */
		FUNCTION_STAT_BATT_INDEPENDENT(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Battery independent mode (reg 33097 BIT06)")),
		
		/** reg 33097 BIT07 – AFCI board present */
		FUNCTION_STAT_AFCI_PRESENT(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("AFCI board present (reg 33097 BIT07)")),
		
		/** reg 33097 BIT08 – AFCI self-test finished */
		FUNCTION_STAT_AFCI_SELFTEST_DONE(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("AFCI self-test finished (reg 33097 BIT08)")),
		
		/** reg 33097 BIT09 – Grid connected (1) or off-grid (0) */
		FUNCTION_STAT_GRID_CONNECTED(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Grid connected (reg 33097 BIT09)")),
		
		/** reg 33097 BIT10 – Double backup enabled */
		FUNCTION_STAT_DOUBLE_BACKUP(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Double backup enabled (reg 33097 BIT10)")),
		
		/** reg 33097 BIT11 – RSD switch closed (S6 HV hybrid only) */
		FUNCTION_STAT_RSD_SWITCH(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("RSD switch closed (reg 33097 BIT11, S6 HV only)")),
		
		/** reg 33097 BIT12 – Emergency switch closed (S6 HV hybrid only) */
		FUNCTION_STAT_EMERGENCY_SWITCH(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Emergency switch closed (reg 33097 BIT12, S6 HV only)")),
		
		/** reg 33097 BIT13 – AC coupling running */
		FUNCTION_STAT_AC_COUPLING(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("AC coupling running (reg 33097 BIT13)")),
		
		/** reg 33097 BIT14 – Reserved */
		FUNCTION_STAT_RESERVED_14(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Reserved (reg 33097 BIT14)")),
		
		/** reg 33097 BIT15 – Reserved */
		FUNCTION_STAT_RESERVED_15(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Reserved (reg 33097 BIT15)")),

		/** Current DRM Code Status (reg 33098, U16, FC4).
		 * Bitmask of active Demand Response Mode conditions per AS/NZS 4755.3. Raw — no scale. */
		CURRENT_DRM_CODE_STATUS(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		INVERTER_CABINET_TEMP(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(LOW)),

		/** Limited Power Actual Value (reg 33104, U16, FC4).
		 * Datasheet: 0.01% resolution. Current active power limit as a percentage of rated power. */
		LIMITED_POWER_ACTUAL_VALUE(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.PERCENT)),

		/** PF Adjustment Actual Value (reg 33105, S16, FC4).
		 * Datasheet: 0.001 resolution. E.g. 1000 = 1.000 (unity PF), 800 = 0.800.
		 * Range: –1.000 to +1.000. No unit declared. */
		PF_ADJUSTMENT_ACTUAL_VALUE(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		/** Limited Reactive Power (reg 33106, S16, FC4).
		 * Datasheet: 0.01% resolution. Range: –6000 to +6000 (–60.00% to +60.00%).
		 * Effective only in Working Mode 4 (Fix reactive power). */
		LIMITED_REACTIVE_POWER(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.PERCENT)),

		INVERTER_MODULE_TEMP2(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(LOW)),

		VOLT_VAR_VREF_RT_VALUES(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(LOW)),

		BMS_CHARGING_VOLTAGE_LIMIT(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(LOW)),

		/** Battery BMS Communication Status (reg 33111, U16, FC4).
		 * Datasheet: 0=normal, 1=communication failure, 2=BMS warning. */
		BATTERY_BMS_STATUS(Doc.of(BatteryBmsStatus.values())
				.accessMode(READ_ONLY)),

		/**
		 * Inverter Initial Setting State (reg 33112, U16, read-only).
		 *
		 * <p>Datasheet: "Initial setting state returned by DSP. See Appendix 1."
		 * Indicates which hardware and firmware initialisation steps have completed.
		 * Read this register during startup to confirm the inverter is fully configured
		 * before issuing control commands.
		 *
		 * <pre>
		 *   BIT00 Model setting complete
		 *   BIT01 National standard (grid code) setting complete
		 *   BIT02 Power curve setting complete
		 *   BIT03 Module ID (0=Onsemi, 1=Infineon — 110kW models only)
		 *   BIT04 Fan detection hardware support (5–20kW: 1=yes)
		 *   BIT05 FCAS function running (0=not running, 1=running)
		 *   BIT06 AFCI self-test ended
		 *   BIT07 AFCI self-test found arc
		 *   BIT08-09 Main DSP chip type (00=F28062, 01=F28374S)
		 *   BIT10 IGBT screening complete
		 *   BIT11-13 Reserved
		 *   BIT14 DSP waveform data ready (0=no waveform, 1=ready)
		 * </pre>
		 */
			
		// Decoded sub-channels:
		/** reg 33112 BIT00 – Model setting complete */
		INIT_STATE_MODEL_SET(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Model setting complete (reg 33112 BIT00)")),
		
		/** reg 33112 BIT01 – National standard (grid code) setting complete */
		INIT_STATE_GRID_CODE_SET(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Grid code setting complete (reg 33112 BIT01)")),
		
		/** reg 33112 BIT02 – Power curve setting complete */
		INIT_STATE_POWER_CURVE_SET(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Power curve setting complete (reg 33112 BIT02)")),
			
		/** reg 33112 BIT03 – Module ID (Infineon=1, Onsemi=0) */
		INIT_STATE_MODULE_TYPE_INFINEON(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Module type Infineon (reg 33112 BIT03)")),
	
		/** reg 33112 BIT04 – Fan detection hardware support */
		INIT_STATE_FAN_DETECTION_SUPPORTED(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Fan detection supported (reg 33112 BIT04)")),
			
		/** reg 33112 BIT05 – FCAS function currently running */
		INIT_STATE_FCAS_RUNNING(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("FCAS function running (reg 33112 BIT05)")),
			
		/** reg 33112 BIT06 – AFCI self-test ended */
		INIT_STATE_AFCI_TEST_ENDED(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("AFCI self-test ended (reg 33112 BIT06)")),
		
		/** reg 33112 BIT07 – AFCI self-test found arc */
		INIT_STATE_AFCI_ARC_FOUND(Doc.of(Level.FAULT)
				.accessMode(READ_ONLY)
				.text("AFCI self-test found arc (reg 33112 BIT07)")),
			
		/** reg 33112 BIT08 – DSP chip type bit 1 */
		INIT_STATE_DSP_CHIP_TYPE_1(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("DSP chip type bit 1 (reg 33112 BIT08)")),
		
		/** reg 33112 BIT09 – DSP chip type bit 2 */
		INIT_STATE_DSP_CHIP_TYPE_2(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("DSP chip type bit 2 (reg 33112 BIT09)")),
			
		/** reg 33112 BIT10 – IGBT screening complete */
		INIT_STATE_IGBT_SCREENING_COMPLETED(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("IGBT screening complete (reg 33112 BIT10)")),
			
		/** reg 33112 BIT11 – Reserved */
		INIT_STATE_RESERVED_11(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Reserved (reg 33112 BIT11)")),
		
				/** reg 33112 BIT12 – Reserved */
		INIT_STATE_RESERVED_12(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Reserved (reg 33112 BIT12)")),
		
				/** reg 33112 BIT13 – Reserved */
		INIT_STATE_RESERVED_13(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Reserved (reg 33112 BIT13)")),
			
		/** reg 33112 BIT14 – DSP waveform data ready */
		INIT_STATE_WAVEFORM_READY(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("DSP waveform data ready (reg 33112 BIT14)")),

		/**
		 * Batch Upgrade Support (reg 33113, U16, read-only).
		 *
		 * <p>Datasheet: "If it supports batch upgrade."
		 * Read before attempting a batch firmware upgrade to confirm both processors support it.
		 *
		 * <pre>
		 *   BIT00 DSP supports batch upgrade  0=no, 1=yes
		 *   BIT04 ARM supports batch upgrade  0=no, 1=yes
		 *   All other bits: unused / always 0
		 * </pre>
		 */
			
		/** reg 33113 BIT00 – DSP processor supports batch upgrade */
		BATCH_UPGRADE_DSP(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("DSP supports batch upgrade (reg 33113 BIT00)")),
		
		/** reg 33113 BIT04 – ARM processor supports batch upgrade */
		BATCH_UPGRADE_ARM(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("ARM supports batch upgrade (reg 33113 BIT04)")),

		/** FCAS Mode Running Status (reg 33114, U16, FC4).
		 * Datasheet: 0=not running (default), 1=running.
		 * FCAS = Frequency Control Ancillary Service (Australian grid services). */
		FCAS_MODE_RUNNING_STATUS(Doc.of(EnableDisable.values())
				.accessMode(READ_ONLY)),

		// ── Appendix 7 ── Register 33115 decoded bits ──
		SETTING_FLAG_FLASH_TIMEOUT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("FLASH read/write timeout")),
		SETTING_FLAG_CLEAR_ENERGY(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Clear energy flag completed (reg 33115 BIT01)")),
		SETTING_FLAG_RESERVED_02(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT02)")),
		SETTING_FLAG_RESERVED_03(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT03)")),
		SETTING_FLAG_RESERVED_04(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT04)")),
		SETTING_FLAG_RESERVED_05(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT05)")),
		SETTING_FLAG_RESERVED_06(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT06)")),
		SETTING_FLAG_RESERVED_07(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT07)")),
		SETTING_FLAG_RESET_DATALOGGER(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reset datalogger completed (reg 33115 BIT08)")),
		SETTING_FLAG_FACTORY_RECOVER(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Return factory setting completed (reg 33115 BIT09)")),
		SETTING_FLAG_RESERVED_10(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT10)")),
		SETTING_FLAG_RESERVED_11(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT11)")),
		SETTING_FLAG_RESERVED_12(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT12)")),
		SETTING_FLAG_RESERVED_13(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT13)")),
		SETTING_FLAG_RESERVED_14(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT14)")),
		SETTING_FLAG_RESERVED_15(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Reserved (reg 33115 BIT15)")),


		// ── Appendix 4 ── Register 33116 decoded bits ──
		FAULT_REG1_NO_GRID(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("No grid")),
		FAULT_REG1_GRID_OVERVOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid overvoltage")),
		FAULT_REG1_GRID_UNDERVOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid undervoltage")),
		FAULT_REG1_GRID_OVERFREQ(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid overfrequency")),
		FAULT_REG1_GRID_UNDERFREQ(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid underfrequency")),
		FAULT_REG1_UNBALANCED_GRID(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Unbalanced grid")),
		FAULT_REG1_GRID_FREQ_FLUCTUATION(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid frequency fluctuation")),
		FAULT_REG1_GRID_REVERSE_CURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid reverse current")),
		FAULT_REG1_GRID_CURRENT_TRACKING_ERROR(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid current tracking error")),
		FAULT_REG1_METER_COM_FAIL(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("METER COM fail")),
		FAULT_REG1_FAILSAFE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("FailSafe")),
		FAULT_REG1_METER_SELECT_FAIL(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Meter Select Fail")),
		FAULT_REG1_EPM_HARD_LIMIT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("EPM Hard Limit Protection")),
		FAULT_REG1_G100_CURRENT_OVER_LIMIT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("G100 Current Over Limit")),
		FAULT_REG1_RESERVED_14(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG1 BIT14)")),
		FAULT_REG1_ABNORMAL_GRID_PHASE_POLARITY(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Abnormal grid phase polarity")),

		// ── Appendix 4 ── Register 33117 decoded bits ──
		FAULT_REG2_BACKUP_OVERVOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Backup overvoltage fault")),
		FAULT_REG2_BACKUP_OVERLOAD(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Backup overload fault")),
		FAULT_REG2_GRID_BACKUP_OVERLOAD(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid Backup overload")),
		FAULT_REG2_OFFGRID_BACKUP_UNDERVOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Off-grid Backup undervoltage")),
		FAULT_REG2_HUB_PANEL_OV_CURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Hub Panel Over-Current")),
		FAULT_REG2_RESERVED_05(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT05)")),
		FAULT_REG2_RESERVED_06(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT06)")),
		FAULT_REG2_RESERVED_07(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT07)")),
		FAULT_REG2_RESERVED_08(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT08)")),
		FAULT_REG2_RESERVED_09(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT09)")),
		FAULT_REG2_RESERVED_10(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT10)")),
		FAULT_REG2_RESERVED_11(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT11)")),
		FAULT_REG2_RESERVED_12(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT12)")),
		FAULT_REG2_RESERVED_13(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT13)")),
		FAULT_REG2_RESERVED_14(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT14)")),
		FAULT_REG2_RESERVED_15(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG2 BIT15)")),

		// ── Appendix 4 ── Register 33118 decoded bits ──
		FAULT_REG3_BATTERY_NOT_CONNECTED(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery not connected")),
		FAULT_REG3_BATTERY_OVERVOLTAGE_CHECK(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery overvoltage check")),
		FAULT_REG3_BATTERY_UNDERVOLTAGE_CHECK(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery undervoltage check")),
		FAULT_REG3_BATTERY_BMS_ALARM(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery BMS Alarm")),
		FAULT_REG3_INCONSISTENT_BATTERY_SELECTION(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Inconsistent battery selection")),
		FAULT_REG3_LEAD_ACID_TEMP_TOO_LOW(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Lead-acid battery temperature too low")),
		FAULT_REG3_LEAD_ACID_TEMP_TOO_HIGH(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Lead-acid battery temperature too high")),
		FAULT_REG3_SECOND_BATTERY_NOT_CONNECTED(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Second battery not connected")),
		FAULT_REG3_SECOND_BATTERY_SW_OVERVOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Second battery software overvoltage")),
		FAULT_REG3_SECOND_BATTERY_SW_UNDERVOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Second battery software undervoltage")),
		FAULT_REG3_PARALLEL_BATTERY_COM_ABNORMAL(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Parallel battery communication abnormal")),
		FAULT_REG3_LOW_BATTERY_OFFGRID(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Low battery (off-grid)")),
		FAULT_REG3_RESERVED_12(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG3 BIT12)")),
		FAULT_REG3_RESERVED_13(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG3 BIT13)")),
		FAULT_REG3_RESERVED_14(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG3 BIT14)")),
		FAULT_REG3_RESERVED_15(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG3 BIT15)")),

		// ── Appendix 4 ── Register 33119 decoded bits ──
		FAULT_REG4_DC_OVERVOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DC overvoltage")),
		FAULT_REG4_DC_BUS_OVERVOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DC Bus overvoltage")),
		FAULT_REG4_DC_BUS_UNBALANCED_VOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DC Bus unbalanced voltage")),
		FAULT_REG4_DC_BUS_UNDERVOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DC Bus undervoltage")),
		FAULT_REG4_DC_BUS_UNBALANCED_VOLTAGE_2(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DC Bus unbalanced voltage 2")),
		FAULT_REG4_DC_OVERCURRENT_A(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DC overcurrent on A circuit")),
		FAULT_REG4_DC_OVERCURRENT_B(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DC overcurrent on B circuit")),
		FAULT_REG4_DC_INPUT_INTERFERENCE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DC input interference")),
		FAULT_REG4_GRID_OVERCURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid overcurrent")),
		FAULT_REG4_IGBT_OVERCURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("IGBT overcurrent")),
		FAULT_REG4_GRID_INTERFERENCE_02(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid interference 02")),
		FAULT_REG4_AFCI_SELF_CHECK(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("AFCI self-check")),
		FAULT_REG4_ARC_FAULT_RESERVED(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Arc fault reserved")),
		FAULT_REG4_GRID_CURRENT_SAMPLING_FAULT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid current sampling fault")),
		FAULT_REG4_DSP_SELF_CHECK_ERROR(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DSP self-check error")),
		FAULT_REG4_BATTERY_DISCHARGE_OVERCURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery Discharge Overcurrent")),

		// ── Appendix 4 ── Register 33120 decoded bits ──
		FAULT_REG5_GRID_INTERFERENCE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid interference")),
		FAULT_REG5_OVER_DC_COMPONENTS(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Over DC components")),
		FAULT_REG5_OVER_TEMPERATURE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Over temperature protection")),
		FAULT_REG5_RELAY_CHECK(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Relay check protection")),
		FAULT_REG5_UNDER_TEMPERATURE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Under temperature protection")),
		FAULT_REG5_PV_INSULATION_FAULT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("PV insulation fault")),
		FAULT_REG5_12V_UNDERVOLTAGE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("12V undervoltage protection")),
		FAULT_REG5_LEAK_CURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Leak current protection")),
		FAULT_REG5_LEAK_CURRENT_SELF_CHECK(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Leak current self-check protection")),
		FAULT_REG5_DSP_INITIAL(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DSP initial protection")),
		FAULT_REG5_DSP_B(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DSP B protection")),
		FAULT_REG5_BATTERY_OVERVOLTAGE_HW(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery overvoltage hardware fault")),
		FAULT_REG5_LLC_HW_OVERCURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("LLC hardware overcurrent")),
		FAULT_REG5_GRID_TRANSIENT_OVERCURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid transient overcurrent")),
		FAULT_REG5_BATTERY_COM_FAILURE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery communication failure")),
		FAULT_REG5_DSP_COM_FAIL(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DSP COM FAIL")),

		// ── Appendix 4 ── Register 33124 decoded bits ──
		FAULT_REG6_SLAVE_LOSE_ERR(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("SlaveLoseErr")),
		FAULT_REG6_MASTER_LOSE_ERR(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("MasterLoseErr")),
		FAULT_REG6_SLAVE_PRD_ERR(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("SlavePrd-Err")),
		FAULT_REG6_MASTER_PRD_ERR(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("MasterPrd-Err")),
		FAULT_REG6_ADDR_CONFLICT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Addr-Conflict")),
		FAULT_REG6_HEARTBEAT_LOSE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("HeartbeatLose")),
		FAULT_REG6_DCAN_ERR(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DCanErr")),
		FAULT_REG6_MUL_MASTER_ERR(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("MulMasterErr")),
		FAULT_REG6_MODE_CONFLICT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("ModeConflict")),
		FAULT_REG6_S_PLUG_VOLT_ERR(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("S-PlugVoltErr")),
		FAULT_REG6_OTHERS_FAULT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Others' Fault")),
		FAULT_REG6_CAN_BUS_LOSE(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("CAN-BUS-LOSE")),
		FAULT_REG6_MODEL_MISMATCH(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("ModelMismatch")),
		FAULT_REG6_3P_CREATE_FAIL(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("3P_CreateFail")),
		FAULT_REG6_ACBK_OPEN(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("ACBK-Open")),
		FAULT_REG6_RESERVED_15(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG6 BIT15)")),

		// ── Appendix 4 ── Register 33125 decoded bits ──
		FAULT_REG7_REVE_DC(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reve-DC")),
		FAULT_REG7_BATTERY_HW_OVERVOLTAGE_02(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery hardware overvoltage 02")),
		FAULT_REG7_BATTERY_HW_OVERCURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery hardware overcurrent")),
		FAULT_REG7_BUS_MIDPOINT_HW_OVERCURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Bus midpoint hardware overcurrent")),
		FAULT_REG7_BATTERY_STARTUP_FAIL(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery startup fail")),
		FAULT_REG7_DC3_AVG_OVERCURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DC 3 average overcurrent")),
		FAULT_REG7_DC4_AVG_OVERCURRENT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DC 4 average overcurrent")),
		FAULT_REG7_SOFTRUN_TIMEOUT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Softrun timeout")),
		FAULT_REG7_OFFGRID_TO_GRID_TIMEOUT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Off-grid to Grid timeout")),
		FAULT_REG7_DRM_NOT_CONNECT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("DRM Not Connect")),
		FAULT_REG7_RESERVED_10(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG7 BIT10)")),
		FAULT_REG7_RESERVED_11(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG7 BIT11)")),
		FAULT_REG7_RESERVED_12(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG7 BIT12)")),
		FAULT_REG7_RESERVED_13(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG7 BIT13)")),
		FAULT_REG7_RESERVED_14(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG7 BIT14)")),
		FAULT_REG7_RESERVED_15(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (REG7 BIT15)")),

		// ── Appendix 5 ── Register 33121 / 36026 decoded bits ──
		OPERATING_STAT_NORMAL_OPERATION(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Normal Operation")),
		OPERATING_STAT_INITIALIZING(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Initializing")),
		OPERATING_STAT_CONTROLLED_OFF(Doc.of(BOOLEAN).accessMode(READ_ONLY).text("Controlled turning OFF (reg 33121 BIT02)")),
		OPERATING_STAT_FAULT_OFF(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Fault leads to turning OFF")),
		OPERATING_STAT_STANDBY(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Stand-by")),
		OPERATING_STAT_LIMITED_TEMP_FREQ(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Limited Operation (temperature/frequency)")),
		OPERATING_STAT_LIMITED_EXTERNAL(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Limited Operation (external reason)")),
		OPERATING_STAT_BACKUP_OVERLOAD(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Backup overload")),
		OPERATING_STAT_LOAD_FAULT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Load fault")),
		OPERATING_STAT_GRID_FAULT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid fault")),
		OPERATING_STAT_BATTERY_FAULT(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Battery fault")),
		OPERATING_STAT_RESERVED_11(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (OPSTAT BIT11)")),
		OPERATING_STAT_GRID_SURGE_WARN(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Grid Surge (Warn)")),
		OPERATING_STAT_FAN_FAULT_WARN(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Fan fault (Warn)")),
		OPERATING_STAT_EXTERNAL_FAN_FAIL(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("External fan failure")),
		OPERATING_STAT_RESERVED_15(Doc.of(Level.FAULT).accessMode(READ_ONLY).text("Reserved (OPSTAT BIT15)")),

		/** Operating Mode Raw Register (reg 33122, U16, FC4).
		 * Datasheet: "Only one bit is valid at any time." One-hot bitmask.
		 * Decoded to the human-readable {@link Appendix8} enum by decodeOperatingMode()
		 * and stored in {@link ChannelId#OPERATING_MODE_DECODE}. */
		OPERATING_MODE(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		OPERATING_MODE_DECODE(Doc.of(Appendix8.values())
				.accessMode(AccessMode.READ_ONLY)),

		/**
		 * Working Mode Running Status (reg 33123, U16, read-only).
		 *
		 * <p>Datasheet: "Every bit represents one working mode. 0=Stop, 1=Run."
		 * For Hawaii standard (4777-A/B/C/N, TOR, UL0240-18) models this register
		 * shows which standard working mode is active. For other grid standards use
		 * reg 33091 (STANDARD_WORKING_MODE) instead.
		 *
		 * <p>Constraint: only one reactive power mode from BIT01–BIT04 can be active
		 * at the same time. BIT00 (Volt-watt) is independent.
		 *
		 * <pre>
		 *   BIT00 Volt-watt             0=stopped, 1=running
		 *   BIT01 Volt-var              0=stopped, 1=running
		 *   BIT02 Fixed power factor    0=stopped, 1=running
		 *   BIT03 Fix reactive power    0=stopped, 1=running
		 *   BIT04 Power-PF              0=stopped, 1=running
		 *   BIT05 Power-Q               0=stopped, 1=running
		 *   BIT06–BIT15 Reserved
		 * </pre>
		 */
			
		/** reg 33123 BIT00 – Volt-watt mode running */
		WMODE_VOLT_WATT(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Volt-watt mode running (reg 33123 BIT00)")),
		
		/** reg 33123 BIT01 – Volt-var mode running */
		WMODE_VOLT_VAR(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Volt-var mode running (reg 33123 BIT01)")),
		
		/** reg 33123 BIT02 – Fixed power factor mode running */
		WMODE_FIXED_PF(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Fixed power factor mode running (reg 33123 BIT02)")),
		
		/** reg 33123 BIT03 – Fixed reactive power mode running */
		WMODE_FIX_REACTIVE(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Fixed reactive power mode running (reg 33123 BIT03)")),
		
		/** reg 33123 BIT04 – Power-PF mode running */
		WMODE_POWER_PF(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Power-PF mode running (reg 33123 BIT04)")),
		
		/** reg 33123 BIT05 – Power-Q mode running */
		WMODE_POWER_Q(Doc.of(BOOLEAN)
				.accessMode(READ_ONLY)
				.text("Power-Q mode running (reg 33123 BIT05)")),

		// ── Appendix 6 ── Register 33132 decoded bits ──
		STORAGE_CTRL_SELF_USE_MODE(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Self-Use Mode")),
		STORAGE_CTRL_TIME_OF_USE_MODE(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Time of Use mode")),
		STORAGE_CTRL_OFFGRID_MODE(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("OFF-grid mode")),
		STORAGE_CTRL_BATT_WAKEUP(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Battery wakeup switch")),
		STORAGE_CTRL_RESERVE_BATT_MODE(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Reserve battery mode")),
		STORAGE_CTRL_ALLOW_GRID_CHARGE(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Allow grid to charge battery")),
		STORAGE_CTRL_FEED_IN_PRIORITY(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Feed in priority mode")),
		STORAGE_CTRL_BATT_OVC(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Batt OVC Function")),
		STORAGE_CTRL_FORCE_CHARGE_PEAKSHAVING(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Battery force charge / peak shaving")),
		STORAGE_CTRL_BATT_CURRENT_CORRECTION(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Battery current correction enable")),
		STORAGE_CTRL_BATT_HEALING_MODE(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Battery healing mode")),
		STORAGE_CTRL_PEAK_SHAVING_MODE(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Peak-shaving mode")),
		STORAGE_CTRL_RESERVED_12(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Reserved (STORAGE BIT12)")),
		STORAGE_CTRL_RESERVED_13(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Reserved (STORAGE BIT13)")),
		STORAGE_CTRL_RESERVED_14(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Reserved (STORAGE BIT14)")),
		STORAGE_CTRL_RESERVED_15(Doc.of(OpenemsType.BOOLEAN).accessMode(READ_ONLY).text("Reserved (STORAGE BIT15)")),
		
		// -----------------------------------------------------------------------------------------------------------------------
		// TODO: What are these??
		// -----------------------------------------------------------------------------------------------------------------------

		SET_REMOTE_CONTROL_AC_GRID_PORT_POWER(Doc.of(INTEGER)
				.accessMode(AccessMode.WRITE_ONLY)
				.unit(Unit.WATT)),

		SET_REMOTE_CONTROL_MODE(Doc.of(INTEGER)
				.accessMode(AccessMode.WRITE_ONLY)),

		SET_REMOTE_DISPATCH_REALTIME_CONTROL_POWER(Doc.of(INTEGER) 
				.accessMode(AccessMode.WRITE_ONLY)),

		REMOTE_DISPATCH_REALTIME_CONTROL_POWER(Doc.of(INTEGER) 
				.accessMode(AccessMode.READ_ONLY)),

		STARTER_BATTERY_VOLTAGE(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.VOLT)
				.persistencePriority(LOW)),

		INVERTED_RATED_APPARENT_POWER(Doc.of(INTEGER)
				.accessMode(READ_ONLY)
				.unit(Unit.VOLT_AMPERE)),

		FUNCTION_STATUS(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		INVERTER_INITIAL_SETTING_STATE(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		BATCH_UPGRADE_BOWL(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		PV_SHUTDOWN_SWITCH(Doc.of(EnableDisable.values())
				.accessMode(READ_ONLY)
				.text("PV shutdown mode")),
		
		GRID_CHARGE_ALLOWED(Doc.of(EnableDisable.values())
				.accessMode(READ_ONLY)
				.text("Battery grid charge allowed")),
		
		DO_CONTROL(Doc.of(EnableDisable.values())
				.accessMode(READ_ONLY)
				.text("DO Control enabled")),
		
		OFF_GRID_BATTERY_STANDBY(Doc.of(EnableDisable.values())
				.accessMode(READ_ONLY)
				.text("Off-grid battery standby")),

		SETTING_FLAG_BIT(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		OPERATING_STATUS(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		WORKING_MODE_RUNNING_STATUS(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),

		STORAGE_CONTROL_SWITCHING_VALUE(Doc.of(INTEGER)
				.accessMode(READ_ONLY)),;

		// -----------------------------------------------------------------------------------------------------------------------
		// -----------------------------------------------------------------------------------------------------------------------
		// -----------------------------------------------------------------------------------------------------------------------

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}



	// -----------------------------------------------------------------------------
	// Helpers: WorkState (internal state)
	// -----------------------------------------------------------------------------

	/**
	 * Returns the Channel for {@link ChannelId#WORK_STATE}.
	 *
	 * @return the {@link Channel}
	 */
	public default Channel<WorkState> getWorkStateChannel() {
		return this.channel(ChannelId.WORK_STATE);
	}

	/**
	 * Gets the current internal work state of this component.
	 *
	 * @return the current {@link WorkState}
	 */
	public default WorkState getWorkState() {
		return this.getWorkStateChannel().value().asEnum();
	}

	/**
	 * Internal method to set {@link WorkState} of this component.
	 *
	 * <p>
	 * The value is written to the corresponding Channel using
	 * {@link io.openems.edge.common.channel.Channel#setNextValue(Object)} and will
	 * be applied in the next processing cycle.
	 * </p>
	 *
	 * @param value the new {@link WorkState} to set
	 */
	public default void _setWorkState(WorkState value) {
		this.getWorkStateChannel().setNextValue(value);
	}

	// Set remote dispatch switch
	/**
	 * Sets the remote dispatch switch.
	 *
	 * @param value the {@link EnableDisable} value
	 * @throws OpenemsNamedException on error
	 */
	public default void setRemoteDispatchSwitch(EnableDisable value) throws OpenemsNamedException {
	    this.setRemoteDispatchSwitchChannel().setNextWriteValue(value);
	}

	public default EnableDisable getRemoteDispatchSwitch() {
	    return this.getRemoteDispatchSwitchChannel().value().asEnum();
	}

	/**
	 * Returns the read-back Channel for {@link ChannelId#REMOTE_DISPATCH_SWITCH}
	 * (reg 44100, FC3).
	 *
	 * @return the {@link Channel}
	 */
	public default Channel<EnableDisable> getRemoteDispatchSwitchChannel() {
	    return this.channel(ChannelId.REMOTE_DISPATCH_SWITCH);
	}

	/**
	 * Returns the write Channel for {@link ChannelId#SET_REMOTE_DISPATCH_SWITCH}
	 * (reg 44100, FC16).
	 *
	 * @return the {@link WriteChannel}
	 */
	public default WriteChannel<EnableDisable> setRemoteDispatchSwitchChannel() {
	    return this.channel(ChannelId.SET_REMOTE_DISPATCH_SWITCH);
	}

	// Enable / Disable Backup Port
	/**
	 * Enables or disables backup AC port.
	 * Register 43111.
	 *
	 * @param value the {@link EnableDisable} value
	 * @throws OpenemsNamedException on error
	 */
	public default void setBackupCircuitSetting(EnableDisable value) throws OpenemsNamedException {
	    this.setBackupCircuitSettingChannel().setNextWriteValue(value);
	}

	public default EnableDisable getBackupCircuitSetting() {
	    return this.getBackupCircuitSettingChannel().value().asEnum();
	}

	/**
	 * Returns the read-back Channel for {@link ChannelId#BACKUP_CIRCUIT_SETTING}
	 * (reg 43111, FC3).
	 *
	 * @return the {@link Channel}
	 */
	public default Channel<EnableDisable> getBackupCircuitSettingChannel() {
	    return this.channel(ChannelId.BACKUP_CIRCUIT_SETTING);
	}

	/**
	 * Returns the write Channel for {@link ChannelId#SET_BACKUP_CIRCUIT_SETTING}
	 * (reg 43111, FC16).
	 *
	 * @return the {@link WriteChannel}
	 */
	public default WriteChannel<EnableDisable> setBackupCircuitSettingChannel() {
	    return this.channel(ChannelId.SET_BACKUP_CIRCUIT_SETTING);
	}

	// Set remote dispatch timeout
	/**
	 * Sets the Remote Dispatch Failsafe timeout (reg 44101, FC16).
	 * If the remote dispatch heartbeat stops, the inverter reverts to local control
	 * after this many minutes. Write 0xFFFF to reset to default (5 minutes).
	 * Range: 1–1440 minutes.
	 *
	 * @param value timeout in minutes
	 * @throws OpenemsNamedException on write error
	 */
	public default void setRemoteDispatchFailsafeSetting(int value) throws OpenemsNamedException {
		this.getSetRemoteDispatchFailsafeSettingChannel().setNextWriteValue(value);
	}

	/**
	 * Returns the write Channel for {@link ChannelId#SET_REMOTE_DISPATCH_FAILSAFE_SETTING}
	 * (reg 44101, FC16). Range: 1–1440 minutes. Default: 5.
	 *
	 * @return the {@link IntegerWriteChannel}
	 */
	/**
	 * Gets the current failsafe timeout read-back value (reg 44101, FC3).
	 *
	 * @return the read-back Channel
	 */
	public default IntegerReadChannel getRemoteDispatchFailsafeSettingChannel() {
		return this.channel(ChannelId.REMOTE_DISPATCH_FAILSAFE_SETTING);
	}

	public default IntegerWriteChannel getSetRemoteDispatchFailsafeSettingChannel() {
		return this.channel(ChannelId.SET_REMOTE_DISPATCH_FAILSAFE_SETTING);
	}


	/**
	 * Sets the remote dispatch system limit switch.
	 *
	 * <p>
	 * BIT00: System Import Limit Switch (0 = Disable, 1 = Enable)
	 * BIT01: System Export Limit Switch (0 = Disable, 1 = Enable)
	 * BIT02-BIT15: Reserved
	 *
	 * @param value the {@link SystemLimitSwitch} value
	 * @throws OpenemsNamedException on error
	 */
	public default void setRemoteDispatchSystemLimitSwitch(RemoteDispatchSystemLimitSwitch value) throws OpenemsNamedException {
	    this.setRemoteDispatchSystemLimitSwitchChannel().setNextWriteValue(value);
	}

	public default RemoteDispatchSystemLimitSwitch getRemoteDispatchSystemLimitSwitch() {
	    return this.getRemoteDispatchSystemLimitSwitchChannel().value().asEnum();
	}

	/**
	 * Returns the read-back Channel for
	 * {@link ChannelId#REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH} (reg 44102, FC3).
	 *
	 * @return the {@link Channel}
	 */
	public default Channel<RemoteDispatchSystemLimitSwitch> getRemoteDispatchSystemLimitSwitchChannel() {
	    return this.channel(ChannelId.REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH);
	}

	/**
	 * Returns the write Channel for
	 * {@link ChannelId#SET_REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH} (reg 44102, FC16).
	 *
	 * @return the {@link WriteChannel}
	 */
	public default WriteChannel<RemoteDispatchSystemLimitSwitch> setRemoteDispatchSystemLimitSwitchChannel() {
	    return this.channel(ChannelId.SET_REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH);
	}

	// Set remote dispatch system import limit
	/**
	 * Sets the system import power limit (reg 44103, FC16).
	 * Resolution: 1 unit = 100 W. Write 0xFFFF to reset to default (1 × rated power).
	 * Active only when REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH BIT00 = 1 (import enabled).
	 *
	 * @param value import limit in units of 100 W
	 * @throws OpenemsNamedException on write error
	 */
	public default void setRemoteDispatchSystemImportLimit(int value) throws OpenemsNamedException {
		this.getSetRemoteDispatchSystemImportLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Returns the write Channel for
	 * {@link ChannelId#SET_REMOTE_DISPATCH_SYSTEM_IMPORT_LIMIT} (reg 44103, FC16).
	 *
	 * @return the {@link IntegerWriteChannel}
	 */
	public default IntegerWriteChannel getSetRemoteDispatchSystemImportLimitChannel() {
		return this.channel(ChannelId.SET_REMOTE_DISPATCH_SYSTEM_IMPORT_LIMIT);
	}

	// Set remote dispatch system export limit
	/**
	 * Sets the system export power limit (reg 44104, FC16).
	 * Resolution: 1 unit = 100 W. Write 0xFFFF to reset to default (1 × rated power).
	 * Active only when REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH BIT01 = 1 (export enabled).
	 *
	 * @param value export limit in units of 100 W
	 * @throws OpenemsNamedException on write error
	 */
	public default void setRemoteDispatchSystemExportLimit(int value) throws OpenemsNamedException {
		this.getSetRemoteDispatchSystemExportLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Returns the write Channel for
	 * {@link ChannelId#SET_REMOTE_DISPATCH_SYSTEM_EXPORT_LIMIT} (reg 44104, FC16).
	 *
	 * @return the {@link IntegerWriteChannel}
	 */
	public default IntegerWriteChannel getSetRemoteDispatchSystemExportLimitChannel() {
		return this.channel(ChannelId.SET_REMOTE_DISPATCH_SYSTEM_EXPORT_LIMIT);
	}

	// Set realtime control power (S32 value)
	/**
	 * Sets the real-time control power setpoint (reg 44106–44107, S32, FC16).
	 * Resolution: 1 unit = 10 W.
	 * When reg 44105=2 (battery control): negative=discharge, positive=charge.
	 * When reg 44105=3 or 4 (grid control): negative=import, positive=export.
	 *
	 * @param value the power setpoint in units of 10 W
	 * @throws OpenemsNamedException on write error
	 */
	public default void setRemoteDispatchRealtimeControlPower(int value) throws OpenemsNamedException {
		this.getSetRemoteDispatchRealtimeControlPowerChannel().setNextWriteValue(value);
	}

	/**
	 * Returns the write Channel for
	 * {@link ChannelId#SET_REMOTE_DISPATCH_REALTIME_CONTROL_POWER} (reg 44106–44107, S32, FC16).
	 *
	 * @return the {@link IntegerWriteChannel}
	 */
	public default IntegerWriteChannel getSetRemoteDispatchRealtimeControlPowerChannel() {
		return this.channel(ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_POWER);
	}

	// Get realtime control function switch
	/**
	 * Gets the current raw value of the Real-Time Control Function Switch (reg 44108).
	 * The raw word encodes four 2-bit function states decoded by installListeners()
	 * into PV_SHUTDOWN_SWITCH, DO_CONTROL, GRID_CHARGE_ALLOWED, OFF_GRID_BATTERY_STANDBY.
	 *
	 * @return the raw bitmask, or {@code null} if not yet available
	 */
	public default Integer getRemoteDispatchRealtimeControlFunctionSwitch() {
		return this.getRemoteDispatchRealtimeControlFunctionSwitchChannel().value().get();
	}

	/**
	 * Returns the read-back Channel for
	 * {@link ChannelId#REMOTE_DISPATCH_REALTIME_CONTROL_FUNCTION_SWITCH}
	 * (reg 44108, FC3).
	 *
	 * @return the {@link Channel}
	 */
	public default Channel<Integer> getRemoteDispatchRealtimeControlFunctionSwitchChannel() {
		return this.channel(ChannelId.REMOTE_DISPATCH_REALTIME_CONTROL_FUNCTION_SWITCH);
	}


	/**
	 * Sets the remote dispatch realtime control function switch.
	 *
	 * @param pvShutdown true = PV shutdown enabled; false = PV shutdown disabled
	 * @param doControl true = DO control enabled; false = DO control disabled
	 * @param gridChargeAllowed true = grid charge allowed; false = grid charge not allowed
	 * @param offGridBatteryStandby true = off-grid battery standby enabled; false = disabled
	 * @throws OpenemsNamedException on error
	 */
	public default void setRemoteDispatchRealtimeControlFunctionSwitch(boolean pvShutdown, boolean doControl,
			boolean gridChargeAllowed, boolean offGridBatteryStandby) throws OpenemsNamedException {

		int value = 0;

		// BIT00-01: PV shutdown switch
		// 1 = Disable, 2 = Enable
		value |= pvShutdown ? 2 : 1;

		// BIT02-03: DO control
		// 1 = Disable, 2 = Enable
		value |= (doControl ? 2 : 1) << 2;

		// BIT04-05: Allow grid charge
		// 1 = Allow, 2 = Not allow
		value |= (gridChargeAllowed ? 1 : 2) << 4;

		// BIT06-07: Off-grid battery standby
		// 1 = Disable, 2 = Enable
		value |= (offGridBatteryStandby ? 2 : 1) << 6;

		this.getSetRemoteDispatchRealtimeControlFunctionSwitchChannel().setNextWriteValue(value);
	}

	/**
	 * Returns the write Channel for
	 * {@link ChannelId#SET_REMOTE_DISPATCH_REALTIME_CONTROL_FUNCTION_SWITCH}
	 * (reg 44108, FC16).
	 *
	 * @return the {@link IntegerWriteChannel}
	 */
	public default IntegerWriteChannel getSetRemoteDispatchRealtimeControlFunctionSwitchChannel() {
		return this.channel(ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_FUNCTION_SWITCH);
	}


	/**
	 * Gets the Channel for {@link ChannelId#PV_SHUTDOWN_SWITCH}.
	 *
	 * @return the Channel
	 */
	public default Channel<EnableDisable> getPvShutdownSwitchChannel() {
		return this.channel(ChannelId.PV_SHUTDOWN_SWITCH);
	}

	/**
	 * Gets the PV shutdown switch.
	 *
	 * @return the {@link EnableDisable} value
	 */
	public default EnableDisable getPvShutdownSwitch() {
		return this.getPvShutdownSwitchChannel().value().asEnum();
	}

	/**
	 * Internal method to set the PV shutdown switch.
	 *
	 * @param value the {@link EnableDisable} value
	 */
	public default void _setPvShutdownSwitch(EnableDisable value) {
		this.getPvShutdownSwitchChannel().setNextValue(value);
	}


	/**
	 * Gets the Channel for {@link ChannelId#GRID_CHARGE_ALLOWED}.
	 *
	 * @return the Channel
	 */
	public default Channel<EnableDisable> getGridChargeAllowedChannel() {
		return this.channel(ChannelId.GRID_CHARGE_ALLOWED);
	}

	/**
	 * Gets if grid charging is allowed.
	 *
	 * @return the {@link EnableDisable} value
	 */
	public default EnableDisable getGridChargeAllowed() {
		return this.getGridChargeAllowedChannel().value().asEnum();
	}

	/**
	 * Internal method to set the grid charging allowed channel
	 *
	 * @param value the {@link EnableDisable} value
	 */
	public default void _setGridChargeAllowed(EnableDisable value) {
		this.getGridChargeAllowedChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#DO_CONTROL}.
	 *
	 * @return the Channel
	 */
	public default Channel<EnableDisable> getDoControlChannel() {
		return this.channel(ChannelId.DO_CONTROL);
	}

	/**
	 * Gets the DO control state.
	 *
	 * @return the {@link EnableDisable} value
	 */
	public default EnableDisable getDoControl() {
		return this.getDoControlChannel().value().asEnum();
	}

	/**
	 * Internal method to set the DO control state channel.
	 *
	 * @param value the {@link EnableDisable} value
	 */
	public default void _setDoControl(EnableDisable value) {
		this.getDoControlChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#OFF_GRID_BATTERY_STANDBY}.
	 *
	 * @return the Channel
	 */
	public default Channel<EnableDisable> getOffGridBatteryStandbyChannel() {
		return this.channel(ChannelId.OFF_GRID_BATTERY_STANDBY);
	}

	/**
	 * Gets the off-grid battery standby state.
	 *
	 * @return the {@link EnableDisable} value
	 */
	public default EnableDisable getOffGridBatteryStandby() {
		return this.getOffGridBatteryStandbyChannel().value().asEnum();
	}

	/**
	 * Internal method to set the off-grid battery standby state.
	 *
	 * @param value the {@link EnableDisable} value
	 */
	public default void _setOffGridBatteryStandby(EnableDisable value) {
		this.getOffGridBatteryStandbyChannel().setNextValue(value);
	}

	/**
	 * Sets the remote dispatch realtime control switch.
	 *
	 * @param value the {@link RemoteDispatchRealtimeControlSwitch} value
	 * @throws OpenemsNamedException on error
	 */
	public default void setRemoteDispatchRealtimeControlSwitch(RemoteDispatchRealtimeControlSwitch value)
	        throws OpenemsNamedException {
	    this.setRemoteDispatchRealtimeControlSwitchChannel().setNextWriteValue(value);
	}

	public default RemoteDispatchRealtimeControlSwitch getRemoteDispatchRealtimeControlSwitch() {
	    return this.getRemoteDispatchRealtimeControlSwitchChannel().value().asEnum();
	}

	/**
	 * Returns the read-back Channel for
	 * {@link ChannelId#REMOTE_DISPATCH_REALTIME_CONTROL_SWITCH} (reg 44105, FC3).
	 *
	 * @return the {@link Channel}
	 */
	public default Channel<RemoteDispatchRealtimeControlSwitch> getRemoteDispatchRealtimeControlSwitchChannel() {
	    return this.channel(ChannelId.REMOTE_DISPATCH_REALTIME_CONTROL_SWITCH);
	}

	/**
	 * Returns the write Channel for
	 * {@link ChannelId#SET_REMOTE_DISPATCH_REALTIME_CONTROL_SWITCH} (reg 44105, FC16).
	 *
	 * @return the {@link WriteChannel}
	 */
	public default WriteChannel<RemoteDispatchRealtimeControlSwitch> setRemoteDispatchRealtimeControlSwitchChannel() {
	    return this.channel(ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_SWITCH);
	}


	// Set remote control mode
	/**
	 * Sets the Remote Control Mode (legacy direct-control register).
	 * Prefer the remote-dispatch path (reg 44100–44108) for production use.
	 *
	 * @param value the mode integer value
	 * @throws OpenemsNamedException on write error
	 */
	public default void setRemoteControlMode(int value) throws OpenemsNamedException {
		this.getSetRemoteControlModeChannel().setNextWriteValue(value);
	}

	/**
	 * Returns the write Channel for {@link ChannelId#SET_REMOTE_CONTROL_MODE}.
	 *
	 * @return the {@link IntegerWriteChannel}
	 */
	public default IntegerWriteChannel getSetRemoteControlModeChannel() {
		return this.channel(ChannelId.SET_REMOTE_CONTROL_MODE);
	}

	// Set power setpoint
	/**
	 * Sets the Remote Control AC Grid Port power setpoint.
	 * Positive = export, negative = import. Resolution: 1 W.
	 *
	 * @param value the power setpoint in watts
	 * @throws OpenemsNamedException on write error
	 */
	public default void setRemoteControlPower(int value) throws OpenemsNamedException {
		this.getSetRemoteControlPowerChannel().setNextWriteValue(value);
	}

	/**
	 * Returns the write Channel for
	 * {@link ChannelId#SET_REMOTE_CONTROL_AC_GRID_PORT_POWER}.
	 *
	 * @return the {@link IntegerWriteChannel}
	 */
	public default IntegerWriteChannel getSetRemoteControlPowerChannel() {
		return this.channel(ChannelId.SET_REMOTE_CONTROL_AC_GRID_PORT_POWER);
	}

	/**
	 * Sets the Max Charge SoC (reg 43010).
	 * Valid range: 80–100%. Default: 100%.
	 *
	 * @param value the SoC percentage to set
	 * @throws OpenemsNamedException on error
	 */
	public default void setMaxChargeSoc(int value) throws OpenemsNamedException {
		this.getSetMaxChargeSocChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getSetMaxChargeSocChannel() {
		return this.channel(ChannelId.SET_MAX_CHARGE_SOC);
	}


	// Overdischarge SoC
	/**
	 * Sets the Overdischarge SoC (reg 43011).
	 * Valid range: 5–40%. Must be >= Force Charge SoC (reg 43018). Default: 20%.
	 *
	 * @param value the SoC percentage to set
	 * @throws OpenemsNamedException on error
	 */
	public default void setOverdischargeSoc(int value) throws OpenemsNamedException {
		this.getSetOverdischargeSocChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getSetOverdischargeSocChannel() {
		return this.channel(ChannelId.SET_OVERDISCHARGE_SOC);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_OVERDISCHARGE_SOC}.
	 *
	 * @return the Channel
	 */
	/**
	 * Gets the read-back Channel for {@link ChannelId#OVERDISCHARGE_SOC} (reg 43011, FC3).
	 * Used by setDefaultValues() to confirm the write was accepted by the inverter.
	 *
	 * @return the {@link IntegerReadChannel}
	 */
	public default IntegerReadChannel getOverDischargeSocChannel() {
		return this.channel(ChannelId.OVERDISCHARGE_SOC);
	}

	/**
	 * Gets the DC Discharge Power in [W]. See {@link ChannelId#DC_DISCHARGE_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getOverDischargeSoc() {
		return this.getOverDischargeSocChannel().value();
	}


	// FORCE CHARGE SOC
	/**
	 * Sets the Force Charge SoC (reg 43018).
	 * Valid range: 4% up to reg 43011. Must be <= Overdischarge SoC. Default: 10%.
	 *
	 * @param value the SoC percentage to set
	 * @throws OpenemsNamedException on error
	 */
	public default void setForceChargeSoc(int value) throws OpenemsNamedException {
		this.getSetForceChargeSocChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getSetForceChargeSocChannel() {
		return this.channel(ChannelId.SET_FORCE_CHARGE_SOC);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_FORCE_CHARGE_SOC}.
	 *
	 * @return the Channel
	 */
	/**
	 * Gets the read-back Channel for {@link ChannelId#FORCE_CHARGE_SOC} (reg 43018, FC3).
	 * Used by setDefaultValues() to confirm the write was accepted by the inverter.
	 *
	 * @return the {@link IntegerReadChannel}
	 */
	public default IntegerReadChannel getForceChargeSocChannel() {
		return this.channel(ChannelId.FORCE_CHARGE_SOC);
	}

	/**
	 * Gets the DC Discharge Power in [W]. See {@link ChannelId#DC_DISCHARGE_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getForceChargeSoc() {
		return this.getForceChargeSocChannel().value();
	}

	//
	/**
	 * Adds Battery to ESS hybrid system.
	 *
	 * @param battery link to Pytes battery
	 */
	public void addBattery(PytesBattery battery);

	/**
	 * Removes link to battery.
	 *
	 * @param PytesBattery battery
	 */
	public void removeBattery(PytesBattery battery);

	/**
	 * Adds DC-charger to ESS hybrid system. Represents PV production
	 *
	 * @param charger link to DC charger(s)
	 */
	public void addCharger(PytesDcCharger charger);

	/**
	 * Removes link to pv DC charger.
	 *
	 * @param charger charger
	 */
	public void removeCharger(PytesDcCharger charger);

	/**
	 * returns ModbusBrdigeId from config.
	 *
	 * @return ModbusBrdigeId from config
	 */
	public String getModbusBridgeId();

	public Integer getUnitId();
}
