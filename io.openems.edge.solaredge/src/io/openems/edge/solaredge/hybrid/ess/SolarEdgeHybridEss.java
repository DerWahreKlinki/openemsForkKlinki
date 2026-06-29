package io.openems.edge.solaredge.hybrid.ess;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;

import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusType;
import io.openems.edge.solaredge.enums.ControlMode;
import io.openems.edge.solaredge.enums.ChargeDischargeMode;
import io.openems.edge.solaredge.enums.AcChargePolicy;
import io.openems.edge.solaredge.enums.BatteryStatus;
import io.openems.edge.solaredge.charger.SolaredgeDcCharger;

public interface SolarEdgeHybridEss extends OpenemsComponent {

	public static enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/**
		 * Defines the AC charge policy for the storage system.
		 * <ul>
		 * <li>0 - Disable: No AC charging allowed.
		 * <li>1 - Always allowed: Essential for AC coupling operation. Enables
		 * unlimited charging from AC. In 'Maximize Self-Consumption' mode, charging
		 * occurs only with excess power; grid charging is prohibited.
		 * <li>2 - Fixed Energy Limit: Allows AC charging up to a fixed yearly limit
		 * (Jan 1 to Dec 31), crucial for ITC regulation compliance in the US.
		 * <li>3 - Percent of Production: Permits AC charging up to a percentage of the
		 * system's year-to-date production, also for ITC regulation in the US.
		 * </ul>
		 */
		AC_CHARGE_POLICY(Doc.of(AcChargePolicy.values()).accessMode(AccessMode.READ_ONLY)),

		/**
		 * Set Channel for AC_CHARGE_POLICY.
		 */
		SET_AC_CHARGE_POLICY(Doc.of(AcChargePolicy.values()).accessMode(AccessMode.WRITE_ONLY)),

		/**
		 * Scale factor for energy.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: None
		 * <li>
		 * </ul>
		 */
		AC_ENERGY_SCALE(Doc.of(OpenemsType.INTEGER) //

				.persistencePriority(PersistencePriority.HIGH)),

		/**
		 * AC energy.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Watt hours
		 * <li>
		 * </ul>
		 */
		AC_ENERGY(Doc.of(OpenemsType.INTEGER) //

				.persistencePriority(PersistencePriority.HIGH)),

		/**
		 * AC-Power produced by the ESS. Either for grid or consumption.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>
		 * </ul>
		 */
		AC_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH)),

		/**
		 * Scale factor for AC-Power.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Scale factor
		 * <li>
		 * </ul>
		 */
		AC_POWER_SCALE(Doc.of(OpenemsType.INTEGER) //

				.persistencePriority(PersistencePriority.HIGH)),

		/**
		 * Apparent power
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Volt Ampere
		 * <li>
		 * </ul>
		 */
		APPARENT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE) //
				.persistencePriority(PersistencePriority.HIGH)),

		/**
		 * Actual Current to or from the battery .
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: A
		 * <li>
		 * </ul>
		 */
		BATT_ACTUAL_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * actual battery voltage .
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: V
		 * <li>
		 * </ul>
		 */
		BATT_ACTUAL_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * average battery temperature.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: °C
		 * <li>
		 * </ul>
		 */
		BATT_AVG_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * Battery Lifetime Export Energy. "Lifetime" resets every night. Channel not
		 * really useful!
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Wh
		 * <li>
		 * </ul>
		 */
		BATT_LIFETIME_EXPORT_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * Battery Lifetime Import Energy. "Lifetime" resets every night. No useful
		 * information!
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Wh
		 * <li>
		 * </ul>
		 */
		BATT_LIFETIME_IMPORT_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * Max. capactity.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Wh
		 * <li>
		 * </ul>
		 */
		BATT_MAX_CAPACITY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * Max. battery temperature.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>
		 * </ul>
		 */
		BATT_MAX_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEGREE_CELSIUS) //
				.persistencePriority(PersistencePriority.LOW)),
		/**
		 * Internal battery status. SE_BATT_STATUS_OFF(0, "Off"), //
		 * SE_BATT_STATUS_STBY(1, "Standby"), // SE_BATT_STATUS_INIT(2, "Init"), //
		 * SE_BATT_STATUS_CHARGE(3, "Charge"), // SE_BATT_STATUS_DISCHARGE(4,
		 * "Discharge"), // SE_BATT_STATUS_FAULT(5, "Fault"), // // 6 doesn´t exist
		 * SE_BATT_STATUS_IDLE(7, "Idle"); //
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: enum
		 * <li>Unit:
		 * <li>
		 * </ul>
		 */
		BATT_STATUS(Doc.of(BatteryStatus.values()).accessMode(AccessMode.READ_ONLY)),

		/*
		 * Charge/Discharge default Mode / Remote Control Command Mode Storage
		 * Charge/Discharge default Mode sets the default mode of operation when Remote
		 * Control Command Timeout has expired. The supported Charge/Discharge Modes are
		 * as follows: 0 – Off 1 – Charge excess PV power only. Only PV excess power not
		 * going to AC is used for charging the battery. Inverter
		 * NominalActivePowerLimit (or the inverter rated power whichever is lower) sets
		 * how much power the inverter is producing to the AC. In this mode, the battery
		 * cannot be discharged. If the PV power is lower than NominalActivePowerLimit
		 * the AC production will be equal to the PV power. 2 – Charge from PV first,
		 * before producing power to the AC. The Battery charge has higher priority than
		 * AC production. First charge the battery then produce AC. If
		 * StorageRemoteCtrl_ChargeLimit is lower than PV excess power goes to AC
		 * according to NominalActivePowerLimit. If NominalActivePowerLimit is reached
		 * and battery StorageRemoteCtrl_ChargeLimit is reached, PV power is curtailed.
		 * 3 – Charge from PV+AC according to the max battery power. Charge from both PV
		 * and AC with priority on PV power. If PV production is lower than
		 * StorageRemoteCtrl_ChargeLimit, the battery will be charged from AC up to
		 * NominalActivePow-erLimit. In this case AC power =
		 * StorageRemoteCtrl_ChargeLimit- PVpower. If PV power is larger than
		 * StorageRemoteCtrl_ChargeLimit the excess PV power will be directed to the AC
		 * up to the Nominal-ActivePowerLimit beyond which the PV is curtailed. 4 –
		 * Maximize export – discharge battery to meet max inverter AC limit. AC power
		 * is maintained to NominalActivePowerLimit, using PV power and/or battery
		 * power. If the PV power is not sufficient, battery power is used to complement
		 * AC power up to StorageRemoteCtrl_DishargeLimit. In this mode, charging excess
		 * power will occur if there is more PV than the AC limit. 5 – Discharge to meet
		 * loads consumption. Discharging to the grid is not allowed. 7 – Maximize
		 * self-consumption
		 */
		CHARGE_DISCHARGE_DEFAULT_MODE(Doc.of(ChargeDischargeMode.values()).accessMode(AccessMode.READ_ONLY)),

		/**
		 * Set Channel for CHARGE_DISCHARGE_DEFAULT_MODE.
		 */
		SET_CHARGE_DISCHARGE_DEFAULT_MODE(Doc.of(ChargeDischargeMode.values()).accessMode(AccessMode.WRITE_ONLY)),

		/**
		 * Current charge power to or from battery Negative values for charging
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>
		 * </ul>
		 */
		CHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * Charge Power Wanted is the activePower wanted from controllers. and internal
		 * Channel for applyPower()-Method
		 * 
		 * negative values for charging
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>
		 * </ul>
		 */
		CHARGE_POWER_WANTED(Doc.of(OpenemsType.INTEGER) // Charge/Discharge-Power wanted from controllers
				.unit(Unit.WATT)), // defined in external file

		/**
		 * Storage Control Mode is used to set the StorEdge system operating mode: 0 –
		 * Disabled 1 – Maximize Self Consumption – requires a SolarEdge Electricity
		 * meter on the grid or load connection point 2 – Time of Use (Profile
		 * programming) – requires a SolarEdge Electricity meter on the grid or load
		 * connection point 3 – Backup Only (applicable only for systems support backup
		 * functionality) 4 – Remote Control – the battery charge/discharge state is
		 * controlled by an external controller
		 */
		CONTROL_MODE(Doc.of(ControlMode.values()).accessMode(AccessMode.READ_ONLY)),

		/**
		 * Set-Channel for Control Mode.
		 * 
		 */
		SET_CONTROL_MODE(Doc.of(ControlMode.values()).accessMode(AccessMode.WRITE_ONLY)),

		/**
		 * AC-Current L1 produced by the ESS. Either for grid or consumption. Always
		 * positive.
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: A
		 * <li>
		 * </ul>
		 */
		CURRENT_L1(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * AC-Current L2 produced by the ESS. Either for grid or consumption. Always
		 * positive.
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: A
		 * <li>
		 * </ul>
		 */
		CURRENT_L2(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * AC-Current L3 produced by the ESS. Either for grid or consumption. Always
		 * positive.
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: A
		 * <li>
		 * </ul>
		 */
		CURRENT_L3(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * Power from Grid. Used ie. to calculate pv production.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>
		 * </ul>
		 */
		GRID_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH)), //

		/**
		 * Scaling factor for grid power.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: None
		 * <li>Range: 0..100
		 * </ul>
		 */
		GRID_POWER_SCALE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) //
				.persistencePriority(PersistencePriority.HIGH)), // defined in external file

		/**
		 * Charge continues power. Varies with SoC.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>
		 * </ul>
		 */
		MAX_CHARGE_CONTINUES_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.LOW)), // defined in external file

		/**
		 * Storage AC Charge Limit is used to set the AC charge limit according to the
		 * policy set in the previous register. Either fixed in kWh or percentage is set
		 * (e.g. 100KWh or 70%). Relevant only for Storage AC Charge Policy = 2 or 3
		 */
		MAX_CHARGE_LIMIT(Doc.of(OpenemsType.INTEGER) // Percent or kWh
				.unit(Unit.PERCENT) //
				.persistencePriority(PersistencePriority.HIGH).accessMode(AccessMode.READ_ONLY)), // defined in external

		SET_MAX_CHARGE_LIMIT(Doc.of(OpenemsType.INTEGER) // Percent or kWh
				.unit(Unit.PERCENT) //
				.persistencePriority(PersistencePriority.HIGH).accessMode(AccessMode.WRITE_ONLY)), // file

		/**
		 * Charge continues power. Varies with SoC.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>
		 * </ul>
		 */
		MAX_CHARGE_PEAK_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.LOW)), // defined in external file

		/**
		 * Charge Power READ Channel. always positive Reads the charge power
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		MAX_CHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_ONLY)),

		/**
		 * Charge Power WRITE Channel always positive tells the ESS the charge power.
		 * Control mode and charge policy have to be set
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		SET_MAX_CHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.WRITE_ONLY)),

		/**
		 * Discharge continues power. Varies with SoC.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>
		 * </ul>
		 */
		MAX_DISCHARGE_CONTINUES_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * Discharge peak power. Varies with SoC.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>
		 * </ul>
		 */
		MAX_DISCHARGE_PEAK_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * Dicharge max. Power READ Channel. Always positive. Reads the charge power.
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		MAX_DISCHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_ONLY)),
		/**
		 * Discharge Power WRITE Channel always positive. Controls ESS' charge power.
		 * Control mode and charge policy have to be set before.
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		SET_MAX_DISCHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.WRITE_ONLY)),

		/**
		 * DC-Power of the inverter.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>
		 * </ul>
		 */
		DC_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH)),

		/**
		 * Scale for the DC-Power value.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Scale
		 * <li>
		 * </ul>
		 */
		DC_POWER_SCALE(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.HIGH)), //

		/**
		 * Rated Energy.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Wh
		 * <li>
		 * </ul>
		 */
		RATED_ENERGY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(PersistencePriority.LOW)), //

		/**
		 * Charge/Discharge default Mode / Remote Control Command Mode Storage
		 * Charge/Discharge default Mode sets the default mode of operation when Remote
		 * Control Command Timeout has expired. The supported Charge/Discharge Modes are
		 * as follows: 0 – Off 1 – Charge excess PV power only. Only PV excess power not
		 * going to AC is used for charging the battery. Inverter
		 * NominalActivePowerLimit (or the inverter rated power whichever is lower) sets
		 * how much power the inverter is producing to the AC. In this mode, the battery
		 * cannot be discharged. If the PV power is lower than NominalActivePowerLimit
		 * the AC production will be equal to the PV power. 2 – Charge from PV first,
		 * before producing power to the AC. The Battery charge has higher priority than
		 * AC production. First charge the battery then produce AC. If
		 * StorageRemoteCtrl_ChargeLimit is lower than PV excess power goes to AC
		 * according to NominalActivePowerLimit. If NominalActivePowerLimit is reached
		 * and battery StorageRemoteCtrl_ChargeLimit is reached, PV power is curtailed.
		 * 3 – Charge from PV+AC according to the max battery power. Charge from both PV
		 * and AC with priority on PV power. If PV production is lower than
		 * StorageRemoteCtrl_ChargeLimit, the battery will be charged from AC up to
		 * NominalActivePow-erLimit. In this case AC power =
		 * StorageRemoteCtrl_ChargeLimit- PVpower. If PV power is larger than
		 * StorageRemoteCtrl_ChargeLimit the excess PV power will be directed to the AC
		 * up to the Nominal-ActivePowerLimit beyond which the PV is curtailed. 4 –
		 * Maximize export – discharge battery to meet max inverter AC limit. AC power
		 * is maintained to NominalActivePowerLimit, using PV power and/or battery
		 * power. If the PV power is not sufficient, battery power is used to complement
		 * AC power up to StorageRemoteCtrl_DishargeLimit. In this mode, charging excess
		 * power will occur if there is more PV than the AC limit. 5 – Discharge to meet
		 * loads consumption. Discharging to the grid is not allowed. 7 – Maximize
		 * self-consumption
		 */
		REMOTE_CONTROL_COMMAND_MODE(Doc.of(ChargeDischargeMode.values()).accessMode(AccessMode.READ_ONLY)), //

		SET_REMOTE_CONTROL_COMMAND_MODE(Doc.of(ChargeDischargeMode.values()).accessMode(AccessMode.WRITE_ONLY)),

		/**
		 * Remote Control Command Timeout sets the operating timeframe for the
		 * charge/discharge command sets in Remote Control
		 */
		REMOTE_CONTROL_TIMEOUT(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_ONLY).unit(Unit.SECONDS) //
				.persistencePriority(PersistencePriority.HIGH)), //

		/**
		 * Remote Control Command Timeout sets the operating timeframe for the
		 * charge/discharge command sets in Remote Control
		 */
		SET_REMOTE_CONTROL_TIMEOUT(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.WRITE_ONLY).unit(Unit.SECONDS) //
				.persistencePriority(PersistencePriority.HIGH)),

		/**
		 * Storage Backup Reserved Setting sets the percentage of reserved battery SOE
		 * to be used for backup purposes. Relevant only for inverters with backup
		 * functionality.
		 * 
		 * Channel not used. Backup limit is controlled by OpenEMS.
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Percent
		 * <li>
		 * </ul>
		 */
		SET_STORAGE_BACKUP_LIMIT(Doc.of(OpenemsType.INTEGER) // Percent. Only relevant for backup systems
				.unit(Unit.PERCENT) //
				.persistencePriority(PersistencePriority.HIGH)),

		/**
		 * State Of Health.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Percent
		 * <li>
		 * </ul>
		 */
		SOH(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * Storage Backup Reserved Setting sets the percentage of reserved battery SOE
		 * to be used for backup purposes. Relevant only for inverters with backup
		 * functionality.
		 * 
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Percent
		 * <li>
		 * </ul>
		 */
		STORAGE_BACKUP_LIMIT(Doc.of(OpenemsType.INTEGER) // Percent. Only relevant for backup systems
				.unit(Unit.PERCENT) //
				.persistencePriority(PersistencePriority.HIGH)),


		/**
		 * Current useable capacity of battery.
		 *
		 * 
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Wh
		 * </ul>
		 */
		USEABLE_CAPACITY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(PersistencePriority.HIGH)),

		/**
		 * Current useable SoC of battery. Emergency SoC from controller is being
		 * deducted.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: Wh
		 * </ul>
		 */
		USEABLE_SOC(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.persistencePriority(PersistencePriority.HIGH)),
		/**
		 * AC-Current L1 produced by the ESS. Either for grid or consumption.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: A
		 * <li>
		 * </ul>
		 */
		VOLTAGE_L1(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * AC-Current L2 produced by the ESS. Either for grid or consumption.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: A
		 * <li>
		 * </ul>
		 */
		VOLTAGE_L2(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT) //
				.persistencePriority(PersistencePriority.LOW)),

		/**
		 * AC-Current L3 produced by the ESS. Either for grid or consumption.
		 *
		 * <ul>
		 * <li>Interface: Ess
		 * <li>Type: Integer
		 * <li>Unit: A
		 * <li>
		 * </ul>
		 */
		VOLTAGE_L3(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT) //
				.persistencePriority(PersistencePriority.LOW)),

		;

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}

	}

	/**
	 * Gets the Channel for {@link ChannelId#AC_CHARGE_POLICY}.
	 *
	 * @return the Channel
	 */
	public default Channel<AcChargePolicy> getAcChargePolicyChannel() {
		return this.channel(ChannelId.AC_CHARGE_POLICY);
	}

	/**
	 * AC charge policy {@link ChannelId#AC_CHARGE_POLICY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default ControlMode getAcChargePolicy() {
		return this.getAcChargePolicyChannel().value().asEnum();
	}

	/**
	 * Gets the Channel for {@link ChannelId#AC_ENERGY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getAcEnergyChannel() {
		return this.channel(ChannelId.AC_ENERGY);
	}

	/**
	 * AC Energy Channel {@link ChannelId#AC_ENERGY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getAcEnergy() {
		return this.getAcEnergyChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#AC_ENERGY_SCALE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getAcEnergyScaleChannel() {
		return this.channel(ChannelId.AC_ENERGY_SCALE);
	}

	/**
	 * Scale factor for AC_ENERGY See {@link ChannelId#AC_ENERGY_SCALE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getAcEnergyScale() {
		return this.getAcEnergyScaleChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#AC_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getAcPowerChannel() {
		return this.channel(ChannelId.AC_POWER);
	}

	/**
	 * AC-Power produced by ESS. See {@link ChannelId#AC_POWER}
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getAcPower() {
		return this.getAcPowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#AC_POWER_SCALE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getAcPowerScaleChannel() {
		return this.channel(ChannelId.AC_POWER_SCALE);
	}

	/**
	 * See {@link ChannelId#AC_POWER_SCALE}
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getAcPowerScale() {
		return this.getAcPowerScaleChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATT_LIFETIME_EXPORT_ENERGY}.
	 *
	 * @return the Channel
	 */
	public default LongReadChannel getBattLifetimeExportEnergyChannel() {
		return this.channel(ChannelId.BATT_LIFETIME_EXPORT_ENERGY);
	}

	/**
	 * Gets the Actual Energy in [Wh_Σ]. See
	 * {@link ChannelId#BATT_LIFETIME_EXPORT_ENERGY}
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Long> getBattLifetimeExportEnergy() {
		return this.getBattLifetimeExportEnergyChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATT_LIFETIME_IMPORT_ENERGY}.
	 *
	 * @return the Channel
	 */
	public default LongReadChannel getBattLifetimeImportEnergyChannel() {
		return this.channel(ChannelId.BATT_LIFETIME_IMPORT_ENERGY);
	}

	/**
	 * See {@link ChannelId#BATT_LIFETIME_IMPORT_ENERGY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Long> getBattLifetimeImportEnergy() {
		return this.getBattLifetimeImportEnergyChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#CHARGE_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getChargePowerChannel() {
		return this.channel(ChannelId.CHARGE_POWER);
	}

	/**
	 * Returns the value from the Charge Power Channel
	 * {@link ChannelId#CHARGE_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getChargePower() {
		return this.getChargePowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#CHARGE_POWER_WANTED}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getChargePowerWantedChannel() {
		return this.channel(ChannelId.CHARGE_POWER_WANTED);
	}

	/**
	 * See {@link ChannelId#CHARGE_POWER_WANTED}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getChargePowerWanted() {
		return this.getChargePowerWantedChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#CHARGE_POWER_WANTED} Channel.
	 *
	 * @param value the next value
	 */
	public default void setChargePowerWanted(Integer value) {
		this.getChargePowerWantedChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CHARGE_DISCHARGE_DEFAULT_MODE}.
	 *
	 * @return the Channel
	 */
	public default Channel<ChargeDischargeMode> getChargeDischargeDefaultModeChannel() {
		return this.channel(ChannelId.CHARGE_DISCHARGE_DEFAULT_MODE);
	}

	/**
	 * See {@link ChannelId#CHARGE_DISCHARGE_DEFAULT_MODE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default ControlMode getChargeDischargeDefaultMode() {
		return this.getChargeDischargeDefaultModeChannel().value().asEnum();
	}

	/**
	 * Gets the Channel for {@link ChannelId#CONTROL_MODE}.
	 *
	 * @return the Channel
	 */
	public default Channel<ControlMode> getControlModeChannel() {
		return this.channel(ChannelId.CONTROL_MODE);
	}

	/**
	 * See {@link ChannelId#CONTROL_MODE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default ControlMode getControlMode() {
		return this.getControlModeChannel().value().asEnum();
	}

	/**
	 * Gets the Channel for {@link ChannelId#CURRENT_L1}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getCurrentL1Channel() {
		return this.channel(ChannelId.CURRENT_L1);
	}

	/**
	 * See {@link ChannelId#CURRENT_L1}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getCurrentL1() {
		return this.getCurrentL1Channel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#CURRENT_L2}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getCurrentL2Channel() {
		return this.channel(ChannelId.CURRENT_L2);
	}

	/**
	 * See {@link ChannelId#CURRENT_L2}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getCurrentL2() {
		return this.getCurrentL2Channel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#CURRENT_L3}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getCurrentL3Channel() {
		return this.channel(ChannelId.CURRENT_L3);
	}

	/**
	 * See {@link ChannelId#CURRENT_L3}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getCurrentL3() {
		return this.getCurrentL3Channel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#DC_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getDcPowerChannel() {
		return this.channel(ChannelId.DC_POWER);
	}

	/**
	 * DC Power Channel {@link ChannelId#DC_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getDcPower() {
		return this.getDcPowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#DC_POWER_SCALE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getDcPowerScaleChannel() {
		return this.channel(ChannelId.DC_POWER_SCALE);
	}

	/**
	 * Is the Energy Storage System On-Grid? See {@link ChannelId#DC_POWER_SCALE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getDcPowerScale() {
		return this.getDcPowerScaleChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#GRID_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getGridPowerChannel() {
		return this.channel(ChannelId.GRID_POWER);
	}

	/**
	 * Gets the DC Discharge Power in [W]. Negative values for Charge; positive for
	 * Discharge. See {@link ChannelId#GRID_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getGridPower() {
		return this.getGridPowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#GRID_POWER_SCALE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getGridPowerScaleChannel() {
		return this.channel(ChannelId.GRID_POWER_SCALE);
	}

	/**
	 * Gets the DC Discharge Power in [W]. Negative values for Charge; positive for
	 * Discharge. See {@link ChannelId#GRID_POWER_SCALE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getGridPowerScale() {
		return this.getGridPowerScaleChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_CHARGE_CONTINUES_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxChargeContinuesPowerChannel() {
		return this.channel(ChannelId.MAX_CHARGE_CONTINUES_POWER);
	}

	/**
	 * Gets the Active Power in [W]. Negative values for Charge; positive for
	 * Discharge. See {@link ChannelId#ACTIVE_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxChargeContinuesPower() {
		return this.getMaxChargeContinuesPowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_CHARGE_PEAK_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxChargePeakPowerChannel() {
		return this.channel(ChannelId.MAX_CHARGE_PEAK_POWER);
	}

	/**
	 * Gets the Active Power in [W]. Negative values for Charge; positive for
	 * Discharge. See {@link ChannelId#ACTIVE_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxChargePeakPower() {
		return this.getMaxChargePeakPowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_CHARGE_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxChargePowerChannel() {
		return this.channel(ChannelId.MAX_CHARGE_POWER);
	}

	/**
	 * Is the Energy Storage System On-Grid? See
	 * {@link ChannelId#REMOTE_CONTROL_COMMAND_MODE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxChargePower() {
		return this.getMaxChargePowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_DISCHARGE_CONTINUES_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxDishargeContinuesPowerChannel() {
		return this.channel(ChannelId.MAX_DISCHARGE_CONTINUES_POWER);
	}

	/**
	 * Gets the Active Power in [W]. Negative values for Charge; positive for
	 * Discharge. See {@link ChannelId#ACTIVE_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxDischargeContinuesPower() {
		return this.getMaxDishargeContinuesPowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_DISCHARGE_PEAK_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxDischargePeakPowerChannel() {
		return this.channel(ChannelId.MAX_DISCHARGE_PEAK_POWER);
	}

	/**
	 * Gets the Active Power in [W]. Negative values for Charge; positive for
	 * Discharge. See {@link ChannelId#ACTIVE_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxDischargePeakPower() {
		return this.getMaxDischargePeakPowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_DISCHARGE_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxDischargePowerChannel() {
		return this.channel(ChannelId.MAX_DISCHARGE_POWER);
	}

	/**
	 * Is the Energy Storage System On-Grid? See
	 * {@link ChannelId#REMOTE_CONTROL_COMMAND_MODE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxDischargePower() {
		return this.getMaxDischargePowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#REMOTE_CONTROL_COMMAND_MODE}.
	 *
	 * @return the Channel
	 */
	public default Channel<ChargeDischargeMode> getRemoteControlCommandModeChannel() {
		return this.channel(ChannelId.REMOTE_CONTROL_COMMAND_MODE);
	}

	/**
	 * Is the Energy Storage System On-Grid? See
	 * {@link ChannelId#REMOTE_CONTROL_COMMAND_MODE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default ControlMode getRemoteControlCommandMode() {
		return this.getRemoteControlCommandModeChannel().value().asEnum();
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_REMOTE_CONTROL_COMMAND_MODE}.
	 *
	 * @return the Channel
	 */
	public default EnumWriteChannel getSetRemoteControlCommandModeChannel() {
		return this.channel(ChannelId.SET_REMOTE_CONTROL_COMMAND_MODE);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#REMOTE_CONTROL_COMMAND_MODE} Channel.
	 *
	 * @param value the next value
	 * @throws OpenemsNamedException OpenemsNamedException
	 */
	public default void setRemoteControlCommandMode(ChargeDischargeMode value) throws OpenemsNamedException {
		this.getSetRemoteControlCommandModeChannel().setNextWriteValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#REMOTE_CONTROL_TIMEOUT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getRemoteControlTimeoutChannel() {
		return this.channel(ChannelId.REMOTE_CONTROL_TIMEOUT);
	}

	/**
	 * {@link ChannelId#REMOTE_CONTROL_COMMAND_MODE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getRemoteControlTimeout() {
		return this.getRemoteControlTimeoutChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_AC_CHARGE_POLICY}.
	 *
	 * @return the Channel
	 */
	public default EnumWriteChannel getSetAcChargePolicyChannel() {
		return this.channel(ChannelId.SET_AC_CHARGE_POLICY);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#SET_AC_CHARGE_POLICY} Channel.
	 *
	 * @param value the next value
	 * @throws OpenemsNamedException OpenemsNamedException
	 */
	public default void setAcChargePolicy(AcChargePolicy value) throws OpenemsNamedException {
		this.getSetAcChargePolicyChannel().setNextWriteValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CHARGE_DISCHARGE_DEFAULT_MODE}.
	 *
	 * @return the Channel
	 */
	public default EnumWriteChannel getSetChargeDischargeDefaultModeChannel() {
		return this.channel(ChannelId.SET_CHARGE_DISCHARGE_DEFAULT_MODE);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#CHARGE_DISCHARGE_DEFAULT_MODE} Channel.
	 *
	 * @param value the next value
	 * @throws OpenemsNamedException OpenemsNamedException
	 */
	public default void setChargeDischargeDefaultMode(ChargeDischargeMode value) throws OpenemsNamedException {
		this.getSetChargeDischargeDefaultModeChannel().setNextWriteValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_CONTROL_MODE}.
	 *
	 * @return the Channel
	 */
	public default EnumWriteChannel getSetControlModeChannel() {
		return this.channel(ChannelId.SET_CONTROL_MODE);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CONTROL_MODE}
	 * Channel.
	 *
	 * @param value the next value
	 * @throws OpenemsNamedException OpenemsNamedException
	 */
	public default void setControlMode(ControlMode value) throws OpenemsNamedException {

		this.getSetControlModeChannel().setNextWriteValue(value);

	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_MAX_CHARGE_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetMaxChargePowerChannel() {
		return this.channel(ChannelId.SET_MAX_CHARGE_POWER);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#REMOTE_CONTROL_COMMAND_MODE} Channel.
	 *
	 * @param value the next value
	 * @throws OpenemsNamedException OpenemsNamedException
	 */
	public default void setMaxChargePower(Integer value) throws OpenemsNamedException {
		this.getSetMaxChargePowerChannel().setNextWriteValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_MAX_DISCHARGE_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetMaxDischargePowerChannel() {
		return this.channel(ChannelId.SET_MAX_DISCHARGE_POWER);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#SET_MAX_DISCHARGE_POWER} Channel.
	 *
	 * @param value the next value
	 * @throws OpenemsNamedException OpenemsNamedException
	 */
	public default void setMaxDischargePower(Integer value) throws OpenemsNamedException {
		this.getSetMaxDischargePowerChannel().setNextWriteValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_REMOTE_CONTROL_TIMEOUT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetRemoteControlTimeoutChannel() {
		return this.channel(ChannelId.SET_REMOTE_CONTROL_TIMEOUT);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#REMOTE_CONTROL_TIMEOUT} Channel.
	 *
	 * @param value the next value
	 * @throws OpenemsNamedException OpenemsNamedException
	 */
	public default void setRemoteControlTimeout(Integer value) throws OpenemsNamedException {
		this.getSetRemoteControlTimeoutChannel().setNextWriteValue(value);
	}


	/**
	 * Gets the Channel for {@link ChannelId#USEABLE_CAPACITY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getUseableCapacityChannel() {
		return this.channel(ChannelId.USEABLE_CAPACITY);
	}

	/**
	 * Returns the useable capacity from the corresponding Channel
	 * {@link ChannelId#USEABLE_CAPACITY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getUseableCapacity() {
		return this.getUseableCapacityChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#USEABLE_CAPACITY}.
	 *
	 * @param value the next value
	 */
	public default void setUseableCapacity(Integer value) {
		this.getUseableCapacityChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#USEABLE_CAPACITY}.
	 *
	 * @param value the next value
	 */
	public default void setUseableCapacity(int value) {
		this.getUseableCapacityChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#USEABLE_SOC}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getUseableSocChannel() {
		return this.channel(ChannelId.USEABLE_SOC);
	}

	/**
	 * Useable state of charge. Reserve already deducted. See
	 * {@link ChannelId#AC_POWER}
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getUseableSoc() {
		return this.getUseableSocChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#USEABLE_SOC}.
	 *
	 * @param value the next value
	 */
	public default void setUseableSoc(Integer value) {
		this.getUseableSocChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#USEABLE_SOC}.
	 *
	 * @param value the next value
	 */
	public default void setUseableSoc(int value) {
		this.getUseableSocChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#VOLTAGE_L1}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getVoltageL1Channel() {
		return this.channel(ChannelId.VOLTAGE_L1);
	}

	/**
	 * See {@link ChannelId#VOLTAGE_L1}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getVoltageL1() {
		return this.getVoltageL1Channel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#VOLTAGE_L2}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getVoltageL2Channel() {
		return this.channel(ChannelId.VOLTAGE_L2);
	}

	/**
	 * See {@link ChannelId#VOLTAGE_L2}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getVoltageL2() {
		return this.getVoltageL2Channel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#VOLTAGE_L3}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getVoltageL3Channel() {
		return this.channel(ChannelId.VOLTAGE_L3);
	}

	/**
	 * See {@link ChannelId#VOLTAGE_L3}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getVoltageL3() {
		return this.getVoltageL3Channel().value();
	}

	/**
	 * Adds DC-charger to ESS hybrid system. Represents PV production
	 * 
	 * @param charger link to DC charger(s)
	 */
	public void addCharger(SolaredgeDcCharger charger);

	/**
	 * Removes link to pv DC charger.
	 * 
	 * @param charger charger
	 */
	public void removeCharger(SolaredgeDcCharger charger);

	/**
	 * returns ModbusBrdigeId from config.
	 * 
	 * @return ModbusBrdigeId from config
	 */
	public String getModbusBridgeId();

	/**
	 * returns UnitId for ESS from config.
	 * 
	 * @return UnitId for ESS from config
	 */
	public Integer getUnitId();

	/**
	 * Used for Modbus/TCP Api Controller. Provides a Modbus table for the Channels
	 * of this Component.
	 *
	 * @param accessMode filters the Modbus-Records that should be shown
	 * @return the {@link ModbusSlaveNatureTable}
	 */
	public default ModbusSlaveNatureTable getModbusSlaveNatureTable(AccessMode accessMode) {
		return ModbusSlaveNatureTable.of(SolarEdgeHybridEss.class, accessMode, 100) //
				.channel(0, ChannelId.USEABLE_CAPACITY, ModbusType.UINT16) //
				.channel(1, ChannelId.USEABLE_SOC, ModbusType.UINT16) //

				.build();
	}

}
