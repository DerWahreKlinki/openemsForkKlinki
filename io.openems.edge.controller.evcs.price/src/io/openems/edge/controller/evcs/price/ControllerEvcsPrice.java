package io.openems.edge.controller.evcs.price;

import static io.openems.common.channel.PersistencePriority.HIGH;
import static io.openems.common.types.OpenemsType.BOOLEAN;
import static io.openems.common.types.OpenemsType.DOUBLE;
import static io.openems.common.types.OpenemsType.INTEGER;

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
		EXPECTED_SURPLUS_ENERGY(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Expected PV surplus for the storage until the end of PV production, after the car")), //
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
		 * True if the storage is expected to reach its evening target by PV surplus.
		 */
		STORAGE_TARGET_REACHABLE(Doc.of(BOOLEAN) //
				.persistencePriority(HIGH) //
				.text("Storage is expected to reach its evening target by PV surplus")); //

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

	public default void setExpectedSurplusEnergy(Integer val) {
		this.channel(ChannelId.EXPECTED_SURPLUS_ENERGY).setNextValue(val);
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
				.channel(12, ChannelId.EXPECTED_SURPLUS_ENERGY, ModbusType.FLOAT32) //
				.channel(14, ChannelId.STORAGE_ENERGY_TO_TARGET, ModbusType.FLOAT32) //
				.channel(16, ChannelId.STORAGE_TARGET_REACHABLE, ModbusType.UINT16) //
				.channel(17, ChannelId.STORAGE_NET_SOC, ModbusType.UINT16) //
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
