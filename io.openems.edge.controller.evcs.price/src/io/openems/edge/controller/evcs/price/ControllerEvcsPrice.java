package io.openems.edge.controller.evcs.price;

import static io.openems.common.channel.PersistencePriority.HIGH;
import static io.openems.common.types.OpenemsType.BOOLEAN;
import static io.openems.common.types.OpenemsType.DOUBLE;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.LONG;
import static io.openems.common.types.OpenemsType.STRING;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusType;
import io.openems.edge.evcs.api.Status;

public interface ControllerEvcsPrice extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		AWAITING_HYSTERESIS(Doc.of(BOOLEAN) //
				.persistencePriority(HIGH)), //
		EVCS_IS_READ_ONLY(Doc.of(Level.INFO) //
				.translationKey(ControllerEvcsPrice.class, "evcsIsReadOnly")), //
		/**
		 * Charging from grid, because the grid buy price is below the price limit.
		 */
		PRICE_CHARGING(Doc.of(BOOLEAN) //
				.persistencePriority(HIGH)), //
		/**
		 * Mirror of the EVCS charge power, for the Modbus/TCP API. The Keba EVCS
		 * implementations do not export their ActivePower via Modbus.
		 */
		EVCS_ACTIVE_POWER(Doc.of(INTEGER) //
				.unit(Unit.WATT) //
				.text("Charge power of the controlled EVCS")), //
		/**
		 * The charge power limit this controller applied in the current cycle.
		 */
		CHARGE_POWER_LIMIT(Doc.of(INTEGER) //
				.unit(Unit.WATT) //
				.text("Charge power limit set by this controller")), //
		/**
		 * Mirror of the EVCS status, for the Modbus/TCP API.
		 */
		EVCS_STATUS(Doc.of(Status.values()) //
				.text("Status of the controlled EVCS")), //
		/**
		 * Mirror of the grid buy price used for the price decision.
		 */
		GRID_BUY_PRICE(Doc.of(DOUBLE) //
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR) //
				.text("Grid buy price used for the price decision")), //
		/**
		 * Replacement cost of power taken from the storage: PV price plus loss
		 * surcharge if the storage will be refilled by PV surplus today, otherwise the
		 * grid buy price.
		 */
		STORAGE_PRICE(Doc.of(DOUBLE) //
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR) //
				.persistencePriority(HIGH) //
				.text("Replacement cost of power from the storage")), //
		/**
		 * Blended price of charging with the minimum hardware power from PV surplus,
		 * storage and grid.
		 */
		BLENDED_PRICE(Doc.of(DOUBLE) //
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR) //
				.persistencePriority(HIGH) //
				.text("Blended price of charging with minimum power from PV, storage and grid")), //
		/**
		 * Expected PV surplus energy until the end of PV production, after house
		 * consumption and the power the car is drawing; from the predictions.
		 */
		STORAGE_SURPLUS_ENERGY(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Expected energy of the storage above its evening target at the end of PV production, "
						+ "after the car; negative if missing")), //
		/**
		 * Energy the storage still needs to reach the evening target.
		 */
		STORAGE_ENERGY_TO_TARGET(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Energy the storage needs to reach its evening target")), //
		/**
		 * Net state of charge of the storage within the usable window.
		 */
		STORAGE_NET_SOC(Doc.of(INTEGER) //
				.unit(Unit.PERCENT) //
				.persistencePriority(HIGH) //
				.text("Net state of charge within the usable window")), //
		/**
		 * True while the charging plan keeps the storage at or above its evening
		 * target at the end of every PV block; the storage power the plan contains is
		 * then valued at the storage price.
		 */
		STORAGE_TARGET_REACHABLE(Doc.of(BOOLEAN) //
				.persistencePriority(HIGH) //
				.text("The charging plan keeps the storage at its evening target")), //
		/**
		 * Power the storage delivers to the car: measured while the car charges (the
		 * storage discharge after the house), the share of the charging plan for the
		 * running quarter-hour before the start; 0 while the storage is blocked.
		 */
		STORAGE_POWER_FOR_CAR(Doc.of(INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(HIGH) //
				.text("Power the storage delivers to the car")), //
		/**
		 * Seconds until the running start or stop hysteresis elapses; empty while no
		 * hysteresis is running.
		 */
		HYSTERESIS_REMAINING(Doc.of(INTEGER) //
				.unit(Unit.SECONDS) //
				.text("Seconds until the start or stop hysteresis elapses")), //
		/**
		 * Start of the planned charging as epoch seconds; empty if the plan contains
		 * no charging.
		 */
		PLAN_START(Doc.of(LONG) //
				.persistencePriority(HIGH) //
				.text("Planned start of charging (epoch seconds)")), //
		/**
		 * Energy the charging plan contains for the price horizon.
		 */
		PLAN_ENERGY(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Planned charging energy")), //
		/**
		 * Part of the planned energy the storage delivers or charges less.
		 */
		PLAN_ENERGY_FROM_STORAGE(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Planned charging energy from the storage")), //
		/**
		 * Planned charge power of the running quarter-hour.
		 */
		PLAN_POWER(Doc.of(INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(HIGH) //
				.text("Planned charge power of the running quarter-hour")), //
		/**
		 * Why the plan charges or does not charge in the running quarter-hour.
		 */
		PLAN_REASON(Doc.of(STRING) //
				.persistencePriority(HIGH) //
				.text("Reason of the plan for the running quarter-hour")), //
		/**
		 * The Time-of-Use-Tariff controller blocks the storage discharge: it delays
		 * the discharge or charges from grid. Storage power then counts like grid
		 * power.
		 */
		STORAGE_DISCHARGE_BLOCKED(Doc.of(BOOLEAN) //
				.persistencePriority(HIGH) //
				.text("Storage discharge blocked by the Time-of-Use-Tariff controller")), //
		/**
		 * "Do not charge today": written by the UI; true pauses charging until the
		 * next midnight, false ends the pause. Reads back the current state.
		 */
		PAUSE_TODAY(Doc.of(BOOLEAN) //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(HIGH) //
				.text("Do not charge until midnight")), //
		/**
		 * End of the pause as epoch seconds; empty if not paused.
		 */
		PAUSED_UNTIL(Doc.of(LONG) //
				.persistencePriority(HIGH) //
				.text("Charging is paused until this time (epoch seconds)")), //
		/**
		 * Part of the planned energy bought from grid.
		 */
		PLAN_ENERGY_FROM_GRID(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Planned charging energy from grid")), //
		/**
		 * Cost of the planned energy: the blended price of every charging
		 * quarter-hour times its energy.
		 */
		PLAN_COST(Doc.of(DOUBLE) //
				.persistencePriority(HIGH) //
				.text("Cost of the planned charging energy [ct]")), //
		/**
		 * State of target charging: NONE while off, else PLANNED, UNREACHABLE,
		 * PRICES_MISSING or REACHED.
		 */
		TARGET_STATUS(Doc.of(STRING) //
				.persistencePriority(HIGH) //
				.text("State of target charging")), //
		/**
		 * Target time of target charging as epoch seconds; empty while off.
		 */
		TARGET_DEADLINE(Doc.of(LONG) //
				.persistencePriority(HIGH) //
				.text("Target charging ends at this time (epoch seconds)")), //
		/**
		 * Energy still missing for the target in this session; empty while off.
		 */
		TARGET_REMAINING_ENERGY(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Energy still missing for the target")), //
		/**
		 * Energy charged since the car was plugged in. The wallbox counter restarts
		 * only with the next charging, so the controller counts from the plug-in.
		 */
		SESSION_ENERGY(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Energy charged since the car was plugged in")), //
		/**
		 * Target charging locks the storage for the car: it discharges for the house
		 * only while it is below its evening target.
		 */
		STORAGE_LOCKED(Doc.of(BOOLEAN) //
				.persistencePriority(HIGH) //
				.text("Storage locked for the car, it serves the house only")), //
		/**
		 * The discharge cap set on the storage while it is locked; empty otherwise.
		 */
		STORAGE_DISCHARGE_LIMIT(Doc.of(INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(HIGH) //
				.text("Discharge cap of the locked storage")), //
		/**
		 * When the plan of target charging was made, as epoch seconds; empty without a
		 * plan.
		 */
		TARGET_PLAN_TIME(Doc.of(LONG) //
				.persistencePriority(HIGH) //
				.text("Target plan made at (epoch seconds)")), //
		/**
		 * Number of quarter-hours with charging in the plan of target charging.
		 */
		TARGET_PLAN_QUARTERS(Doc.of(INTEGER) //
				.persistencePriority(HIGH) //
				.text("Quarter-hours with charging in the target plan")), //
		/**
		 * The car takes nothing for a long time although power is offered, e.g. it
		 * is full: the plan does not count for the energy optimizer, the wallbox
		 * goes on offering.
		 */
		CAR_TAKES_NOTHING(Doc.of(BOOLEAN) //
				.persistencePriority(HIGH) //
				.text("The car takes nothing although power is offered; the plan does not count for the optimizer")); //

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	public default void setPriceCharging(boolean val) {
		this.channel(ChannelId.PRICE_CHARGING).setNextValue(val);
	}

	public default void setEvcsActivePower(Integer val) {
		this.channel(ChannelId.EVCS_ACTIVE_POWER).setNextValue(val);
	}

	public default void setChargePowerLimit(Integer val) {
		this.channel(ChannelId.CHARGE_POWER_LIMIT).setNextValue(val);
	}

	public default void setEvcsStatus(Status val) {
		this.channel(ChannelId.EVCS_STATUS).setNextValue(val);
	}

	public default void setGridBuyPrice(Double val) {
		this.channel(ChannelId.GRID_BUY_PRICE).setNextValue(val);
	}

	public default void setStoragePrice(Double val) {
		this.channel(ChannelId.STORAGE_PRICE).setNextValue(val);
	}

	public default void setBlendedPrice(Double val) {
		this.channel(ChannelId.BLENDED_PRICE).setNextValue(val);
	}

	public default void setStorageSurplusEnergy(Integer val) {
		this.channel(ChannelId.STORAGE_SURPLUS_ENERGY).setNextValue(val);
	}

	public default void setStorageEnergyToTarget(Integer val) {
		this.channel(ChannelId.STORAGE_ENERGY_TO_TARGET).setNextValue(val);
	}

	public default void setStorageNetSoc(Integer val) {
		this.channel(ChannelId.STORAGE_NET_SOC).setNextValue(val);
	}

	public default void setStorageTargetReachable(Boolean val) {
		this.channel(ChannelId.STORAGE_TARGET_REACHABLE).setNextValue(val);
	}

	public default void setStoragePowerForCar(Integer val) {
		this.channel(ChannelId.STORAGE_POWER_FOR_CAR).setNextValue(val);
	}

	public default void setStorageDischargeBlocked(Boolean val) {
		this.channel(ChannelId.STORAGE_DISCHARGE_BLOCKED).setNextValue(val);
	}

	public default void setHysteresisRemaining(Integer val) {
		this.channel(ChannelId.HYSTERESIS_REMAINING).setNextValue(val);
	}

	public default void setPlanStart(Long val) {
		this.channel(ChannelId.PLAN_START).setNextValue(val);
	}

	public default void setPlanEnergy(Integer val) {
		this.channel(ChannelId.PLAN_ENERGY).setNextValue(val);
	}

	public default void setPlanEnergyFromStorage(Integer val) {
		this.channel(ChannelId.PLAN_ENERGY_FROM_STORAGE).setNextValue(val);
	}

	public default void setPlanPower(Integer val) {
		this.channel(ChannelId.PLAN_POWER).setNextValue(val);
	}

	public default void setPlanReason(String val) {
		this.channel(ChannelId.PLAN_REASON).setNextValue(val);
	}

	public default void setPlanEnergyFromGrid(Integer val) {
		this.channel(ChannelId.PLAN_ENERGY_FROM_GRID).setNextValue(val);
	}

	public default void setPlanCost(Double val) {
		this.channel(ChannelId.PLAN_COST).setNextValue(val);
	}

	public default void setTargetStatus(String val) {
		this.channel(ChannelId.TARGET_STATUS).setNextValue(val);
	}

	public default void setTargetDeadline(Long val) {
		this.channel(ChannelId.TARGET_DEADLINE).setNextValue(val);
	}

	public default void setTargetRemainingEnergy(Integer val) {
		this.channel(ChannelId.TARGET_REMAINING_ENERGY).setNextValue(val);
	}

	public default void setSessionEnergy(Integer val) {
		this.channel(ChannelId.SESSION_ENERGY).setNextValue(val);
	}

	public default void setStorageLocked(Boolean val) {
		this.channel(ChannelId.STORAGE_LOCKED).setNextValue(val);
	}

	public default void setStorageDischargeLimit(Integer val) {
		this.channel(ChannelId.STORAGE_DISCHARGE_LIMIT).setNextValue(val);
	}

	public default void setCarTakesNothing(Boolean val) {
		this.channel(ChannelId.CAR_TAKES_NOTHING).setNextValue(val);
	}

	public default void setTargetPlanTime(Long val) {
		this.channel(ChannelId.TARGET_PLAN_TIME).setNextValue(val);
	}

	public default void setTargetPlanQuarters(Integer val) {
		this.channel(ChannelId.TARGET_PLAN_QUARTERS).setNextValue(val);
	}

	/**
	 * Used for Modbus/TCP Api Controller. Provides a Modbus table for the Channels
	 * of this nature, so that the EVCS power, status and price are available to
	 * external systems.
	 *
	 * @param accessMode filters the Modbus-Records that should be shown
	 * @return the {@link ModbusSlaveNatureTable}
	 */
	public static ModbusSlaveNatureTable getModbusSlaveNatureTable(AccessMode accessMode) {
		return ModbusSlaveNatureTable.of(ControllerEvcsPrice.class, accessMode, 100) //
				.channel(0, ChannelId.EVCS_ACTIVE_POWER, ModbusType.FLOAT32) //
				.channel(2, ChannelId.CHARGE_POWER_LIMIT, ModbusType.FLOAT32) //
				.channel(4, ChannelId.EVCS_STATUS, ModbusType.ENUM16) //
				.channel(5, ChannelId.PRICE_CHARGING, ModbusType.UINT16) //
				.channel(6, ChannelId.GRID_BUY_PRICE, ModbusType.FLOAT32) //
				.channel(8, ChannelId.STORAGE_PRICE, ModbusType.FLOAT32) //
				.channel(10, ChannelId.BLENDED_PRICE, ModbusType.FLOAT32) //
				.channel(12, ChannelId.STORAGE_SURPLUS_ENERGY, ModbusType.FLOAT32) //
				.channel(14, ChannelId.STORAGE_ENERGY_TO_TARGET, ModbusType.FLOAT32) //
				.channel(16, ChannelId.STORAGE_TARGET_REACHABLE, ModbusType.UINT16) //
				.channel(17, ChannelId.STORAGE_NET_SOC, ModbusType.UINT16) //
				.channel(18, ChannelId.PAUSE_TODAY, ModbusType.UINT16) //
				.build();
	}

	public default void setEvcsIsReadOnlyChannel(boolean val) {
		this.getEvcsIsReadOnlyChannel().setNextValue(val);
	}

	/**
	 * Gets the Channel for {@link ChannelId#EVCS_IS_READ_ONLY}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getEvcsIsReadOnlyChannel() {
		return this.channel(ChannelId.EVCS_IS_READ_ONLY);
	}

}
