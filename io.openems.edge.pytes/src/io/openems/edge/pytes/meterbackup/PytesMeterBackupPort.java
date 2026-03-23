package io.openems.edge.pytes.meterbackup;

import static io.openems.common.channel.AccessMode.READ_ONLY;
import static io.openems.common.types.OpenemsType.INTEGER;

import java.nio.channels.Channel;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.common.channel.ChannelId;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

public interface PytesMeterBackupPort extends ElectricityMeter, ModbusComponent, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		
		// -----------------------------------------------------------------------
		// Inverter AC Grid Port - Apparent Power (reg 33083)
		// These are the inverter's own AC port measurements (33073..33094)
		// NOT the backup port. Used here as the primary AC reference. 
		// -----------------------------------------------------------------------
		
		/**
		 * Inverter AC Grid Port - Total Apparent Power (reg 33083, S32)
		 * Datasheet: 1 VA -> no converter needed
		 * Unit: VA
		 */
		APPARENT_POWER(Doc.of(INTEGER) //
				.accessMode(READ_ONLY) //
				.unit(Unit.VOLT_AMPERE)),

		// -----------------------------------------------------------------------
		// Backup AC Port - Voltage and Current per phase
		// These are the actual backup/off-grid port measurements
		// The backup port powers loads during a grid outage
		// Voltage: 0.1 V resolution -> SCALE_FACTOR_2 -> mV
		// Current: 0.1 A resolution -> SCALE_FACTOR_2 -> mA
		// -----------------------------------------------------------------------

		/**
		 * Backup AC port Phase A voltage (reg 33137, U16)
		 * For split-phase models: indicates L1-N voltage
		 * Datasheet: 0.1 V -> SCALE_FACTOR_2 -> mV
		 * Unit: mV
		 */
		BACKUP_VOLTAGE_L1(Doc.of(INTEGER) //
				.accessMode(READ_ONLY) //
				.unit(Unit.MILLIVOLT)),

		/**
		 * Backup AC port Phase A current (reg 33138, U16)
		 * For split-phase models: indicates L1 current
		 * Datasheet: 0.1 A -> SCALE_FACTOR_2 -> mA
		 * Unit: mA
		 */
		BACKUP_CURRENT_L1(Doc.of(INTEGER) //
				.accessMode(READ_ONLY) //
				.unit(Unit.MILLIAMPERE)),
		
		/**
		 * Backup AC port Phase B voltage (reg 33137, U16)
		 * For split-phase models: indicates L1-N voltage
		 * Datasheet: 0.1 V -> SCALE_FACTOR_2 -> mV
		 * Unit: mV
		 */
		BACKUP_VOLTAGE_L2(Doc.of(INTEGER) //
				.accessMode(READ_ONLY) //
				.unit(Unit.MILLIVOLT)),

		/**
		 * Backup AC port Phase B current (reg 33138, U16)
		 * For split-phase models: indicates L1 current
		 * Datasheet: 0.1 A -> SCALE_FACTOR_2 -> mA
		 * Unit: mA
		 */
		BACKUP_CURRENT_L2(Doc.of(INTEGER) //
				.accessMode(READ_ONLY) //
				.unit(Unit.MILLIAMPERE)),
		
		/**
		 * Backup AC port Phase C voltage (reg 33137, U16)
		 * For split-phase models: 0
		 * Datasheet: 0.1 V -> SCALE_FACTOR_2 -> mV
		 * Unit: mV
		 */
		BACKUP_VOLTAGE_L3(Doc.of(INTEGER) //
				.accessMode(READ_ONLY) //
				.unit(Unit.MILLIVOLT)),

		/**
		 * Backup AC port Phase C current (reg 33138, U16)
		 * For split-phase models: 0
		 * Datasheet: 0.1 A -> SCALE_FACTOR_2 -> mA
		 * Unit: mA
		 */
		BACKUP_CURRENT_L3(Doc.of(INTEGER) //
				.accessMode(READ_ONLY) //
				.unit(Unit.MILLIAMPERE)),
		
		// -----------------------------------------------------------------------
		// Backup Side Per-Phase Power (reg 33521..33529)
		// Resolution: 10 W / 10 Var / 10 VA -> SCALE_FACTOR_1 -> W / Var / VA
		// S16 registers
		// Note: Phase C registers (33527-33529) are always 0 for split-phase models.
 		// -----------------------------------------------------------------------

		/**
		 * Backup side Phase A active power (reg 33521, S16)
		 * Calculated using L1-N voltage and L1 current.
		 * Datasheet: 10 W -> SCALE_FACTOR_1 -> W
		 * Unit: W
		 */
		BACKUP_ACTIVE_POWER_L1(Doc.of(INTEGER) //
					.accessMode(READ_ONLY) //
					.unit(Unit.WATT)),
					
		/**
		 * Backup side Phase A reactive power (reg 33522, S16)
		 * Calculated using L1-N voltage and L1 current.
		 * Datasheet: 10 Var -> SCALE_FACTOR_1 -> Var
		 * Unit: Var
		 */
		BACKUP_REACTIVE_POWER_L1(Doc.of(INTEGER) //
					.accessMode(READ_ONLY) //
					.unit(Unit.VOLT_AMPERE_REACTIVE)),
		/**
		 * Backup side Phase A apparent power (reg 33523, S16)
		 * Calculated using L1-N voltage and L1 current.
		 * Datasheet: 10 VA -> SCALE_FACTOR_1 -> VA
		 * Unit: VA
		 */
		BACKUP_APPARENT_POWER_L1(Doc.of(INTEGER) //
					.accessMode(READ_ONLY) //
					.unit(Unit.VOLT_AMPERE)),

		/**
		 * Backup side Phase B active power (reg 33521, S16)
		 * Calculated using L1-N voltage and L1 current.
		 * Datasheet: 10 W -> SCALE_FACTOR_1 -> W
		 * Unit: W
		 */
		BACKUP_ACTIVE_POWER_L2(Doc.of(INTEGER) //
					.accessMode(READ_ONLY) //
					.unit(Unit.WATT)),
					
		/**
		 * Backup side Phase B reactive power (reg 33522, S16)
		 * Calculated using L1-N voltage and L1 current.
		 * Datasheet: 10 Var -> SCALE_FACTOR_1 -> Var
		 * Unit: Var
		 */
		BACKUP_REACTIVE_POWER_L2(Doc.of(INTEGER) //
					.accessMode(READ_ONLY) //
					.unit(Unit.VOLT_AMPERE_REACTIVE)),
		/**
		 * Backup side Phase B apparent power (reg 33523, S16)
		 * Calculated using L1-N voltage and L1 current.
		 * Datasheet: 10 VA -> SCALE_FACTOR_1 -> VA
		 * Unit: VA
		 */
		BACKUP_APPARENT_POWER_L2(Doc.of(INTEGER) //
					.accessMode(READ_ONLY) //
					.unit(Unit.VOLT_AMPERE)),

		/**
		 * Backup side Phase C active power (reg 33521, S16)
		 * Calculated using L1-N voltage and L1 current.
		 * Datasheet: 10 W -> SCALE_FACTOR_1 -> W
		 * Unit: W
		 */
		BACKUP_ACTIVE_POWER_L3(Doc.of(INTEGER) //
					.accessMode(READ_ONLY) //
					.unit(Unit.WATT)),
					
		/**
		 * Backup side Phase C reactive power (reg 33522, S16)
		 * Calculated using L1-N voltage and L1 current.
		 * Datasheet: 10 Var -> SCALE_FACTOR_1 -> Var
		 * Unit: Var
		 */
		BACKUP_REACTIVE_POWER_L3(Doc.of(INTEGER) //
					.accessMode(READ_ONLY) //
					.unit(Unit.VOLT_AMPERE_REACTIVE)),
		/**
		 * Backup side Phase C apparent power (reg 33523, S16)
		 * Calculated using L1-N voltage and L1 current.
		 * Datasheet: 10 VA -> SCALE_FACTOR_1 -> VA
		 * Unit: VA
		 */
		BACKUP_APPARENT_POWER_L3(Doc.of(INTEGER) //
					.accessMode(READ_ONLY) //
					.unit(Unit.VOLT_AMPERE)),

			
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
	// -----------------------------------------------------------------------
	// Accessor methods - Apparent Power (AC Grid Port)
	// -----------------------------------------------------------------------

	/** @return Channel for {@link ChannelId#APPARENT_POWER} */
	public default IntegerReadChannel getApparentPowerChannel() {
		return this.channel(ChannelId.APPARENT_POWER);
	}

	/** @return Total apparent power [VA]. See {@link ChannelId#APPARENT_POWER} */
	public default Value<Integer> getApparentPower() {
		return this.getApparentPowerChannel().value();
	}

	// -----------------------------------------------------------------------
	// Accessor methods - Backup Port Voltages and Currents
	// -----------------------------------------------------------------------

	/**@return Channel for {@link ChannelId#BACKUP_VOLTAGE_L1} */
	public default IntegerReadChannel getBackupVoltageL1Channel() {
		return this.channel(ChannelId.BACKUP_VOLTAGE_L1);
	}

	/**@return Backup port Phase A voltage [mV]. See {@link ChannelId#BACKUP_VOLTAGE_L1} */
	public default Value<Integer> getBackupVoltageL1() {
		return this.getBackupVoltageL1Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_CURRENT_L1} */
	public default IntegerReadChannel getBackupCurrentL1Channel() {
		return this.channel(ChannelId.BACKUP_CURRENT_L1);
	}

	/**@return Backup port phase A current [mA]. See {@link ChannelId#BACKUP_CURRENT_L1} */
	public default Value<Integer> getBackupCurrentL1() {
		return this.getBackupCurrentL1Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_VOLTAGE_L2} */
	public default IntegerReadChannel getBackupVoltageL2Channel() {
		return this.channel(ChannelId.BACKUP_VOLTAGE_L2);
	}

	/**@return Backup port Phase B voltage [mV]. See {@link ChannelId#BACKUP_VOLTAGE_L2} */
	public default Value<Integer> getBackupVoltageL2() {
		return this.getBackupVoltageL2Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_CURRENT_L2} */
	public default IntegerReadChannel getBackupCurrentL2Channel() {
		return this.channel(ChannelId.BACKUP_CURRENT_L2);
	}

	/**@return Backup port phase B current [mA]. See {@link ChannelId#BACKUP_CURRENT_L2} */
	public default Value<Integer> getBackupCurrentL2() {
		return this.getBackupCurrentL2Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_VOLTAGE_L3} */
	public default IntegerReadChannel getBackupVoltageL3Channel() {
		return this.channel(ChannelId.BACKUP_VOLTAGE_L3);
	}

	/**@return Backup port Phase C voltage [mV]. See {@link ChannelId#BACKUP_VOLTAGE_L3} */
	public default Value<Integer> getBackupVoltageL3() {
		return this.getBackupVoltageL3Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_CURRENT_L3} */
	public default IntegerReadChannel getBackupCurrentL3Channel() {
		return this.channel(ChannelId.BACKUP_CURRENT_L3);
	}

	/**@return Backup port phase C current [mA]. See {@link ChannelId#BACKUP_CURRENT_L3} */
	public default Value<Integer> getBackupCurrentL3() {
		return this.getBackupCurrentL3Channel().value();
	}

	// -----------------------------------------------------------------------
	// Accessor methods - Backup Side Per-Phase Power
	// -----------------------------------------------------------------------

	/**@return Channel for {@link ChannelId#BACKUP_ACTIVE_POWER_L1} */
	public default IntegerReadChannel getBackupActivePowerL1Channel() {
		return this.channel(ChannelId.BACKUP_ACTIVE_POWER_L1);
	}

	/**@return Backup Phase A active power [W]. See {@link ChannelId#BACKUP_ACTIVE_POWER_L1} */
	public default Value<Integer> getBackupActivePowerL1() {
		return this.getBackupActivePowerL1Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_REACTIVE_POWER_L1} */
	public default IntegerReadChannel getBackupReactivePowerL1Channel() {
		return this.channel(ChannelId.BACKUP_REACTIVE_POWER_L1);
	}

	/**@return Backup Phase A reactive power [Var]. See {@link ChannelId#BACKUP_REACTIVE_POWER_L1} */
	public default Value<Integer> getBackupReactivePowerL1() {
		return this.getBackupReactivePowerL1Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_APPARENT_POWER_L1} */
	public default IntegerReadChannel getBackupApparentPowerL1Channel() {
		return this.channel(ChannelId.BACKUP_APPARENT_POWER_L1);
	}

	/**@return Backup Phase A apparent power [VA]. See {@link ChannelId#BACKUP_APPARENT_POWER_L1} */
	public default Value<Integer> getBackupApparentPowerL1() {
		return this.getBackupApparentPowerL1Channel().value();
	}
	
	/**@return Channel for {@link ChannelId#BACKUP_ACTIVE_POWER_L2} */
	public default IntegerReadChannel getBackupActivePowerL2Channel() {
		return this.channel(ChannelId.BACKUP_ACTIVE_POWER_L2);
	}

	/**@return Backup Phase B active power [W]. See {@link ChannelId#BACKUP_ACTIVE_POWER_L2} */
	public default Value<Integer> getBackupActivePowerL2() {
		return this.getBackupActivePowerL2Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_REACTIVE_POWER_L2} */
	public default IntegerReadChannel getBackupReactivePowerL2Channel() {
		return this.channel(ChannelId.BACKUP_REACTIVE_POWER_L2);
	}

	/**@return Backup Phase B reactive power [Var]. See {@link ChannelId#BACKUP_REACTIVE_POWER_L2} */
	public default Value<Integer> getBackupReactivePowerL2() {
		return this.getBackupReactivePowerL2Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_APPARENT_POWER_L2} */
	public default IntegerReadChannel getBackupApparentPowerL2Channel() {
		return this.channel(ChannelId.BACKUP_APPARENT_POWER_L2);
	}

	/**@return Backup Phase B apparent power [VA]. See {@link ChannelId#BACKUP_APPARENT_POWER_L2} */
	public default Value<Integer> getBackupApparentPowerL2() {
		return this.getBackupApparentPowerL2Channel().value();
	}
	
	/**@return Channel for {@link ChannelId#BACKUP_ACTIVE_POWER_L3} */
	public default IntegerReadChannel getBackupActivePowerL3Channel() {
		return this.channel(ChannelId.BACKUP_ACTIVE_POWER_L3);
	}

	/**@return Backup Phase C active power [W]. See {@link ChannelId#BACKUP_ACTIVE_POWER_L3} */
	public default Value<Integer> getBackupActivePowerL3() {
		return this.getBackupActivePowerL3Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_REACTIVE_POWER_L3} */
	public default IntegerReadChannel getBackupReactivePowerL3Channel() {
		return this.channel(ChannelId.BACKUP_REACTIVE_POWER_L3);
	}

	/**@return Backup Phase C reactive power [Var]. See {@link ChannelId#BACKUP_REACTIVE_POWER_L3} */
	public default Value<Integer> getBackupReactivePowerL3() {
		return this.getBackupReactivePowerL3Channel().value();
	}

	/**@return Channel for {@link ChannelId#BACKUP_APPARENT_POWER_L3} */
	public default IntegerReadChannel getBackupApparentPowerL3Channel() {
		return this.channel(ChannelId.BACKUP_APPARENT_POWER_L3);
	}

	/**@return Backup Phase C apparent power [VA]. See {@link ChannelId#BACKUP_APPARENT_POWER_L3} */
	public default Value<Integer> getBackupApparentPowerL3() {
		return this.getBackupApparentPowerL3Channel().value();
	}

	
}