package io.openems.edge.ess.energyprice;

import static io.openems.common.channel.PersistencePriority.HIGH;
import static io.openems.common.types.OpenemsType.DOUBLE;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.LONG;

import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.DoubleReadChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Price of the energy stored in one ESS.
 */
public interface EssEnergyPrice extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Price of a delivered kWh: average purchase price of the content, losses
		 * included.
		 */
		PRICE(Doc.of(DOUBLE) //
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR) //
				.persistencePriority(HIGH) //
				.text("Price of a delivered kWh: average purchase price of the content, losses included")), //
		STORED_ENERGY(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Content of the ESS as led by the inventory (SoC times capacity)")), //
		STORED_GRID_ENERGY(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Part of the content bought from the grid")), //
		STORED_PV_ENERGY(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Part of the content charged from PV")), //
		UNPRICED_ENERGY(Doc.of(INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Part of the content with unknown price (content at start, charged without price)")), //
		STORED_COST(Doc.of(DOUBLE) //
				.persistencePriority(HIGH) //
				.text("Purchase cost of the priced content [Currency]")), //
		CHARGE_GRID_POWER(Doc.of(INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(HIGH) //
				.text("Part of the current charge power bought from the grid")), //
		CHARGE_PV_POWER(Doc.of(INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(HIGH) //
				.text("Part of the current charge power from PV")), //
		CHARGE_GRID_ENERGY(Doc.of(LONG) //
				.unit(Unit.CUMULATED_WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Energy charged from the grid")), //
		CHARGE_PV_ENERGY(Doc.of(LONG) //
				.unit(Unit.CUMULATED_WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Energy charged from PV")), //
		MISSING_PRICE_ENERGY(Doc.of(LONG) //
				.unit(Unit.CUMULATED_WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Energy charged while no grid buy price was known")), //
		EFFICIENCY(Doc.of(DOUBLE) //
				.unit(Unit.PERCENT) //
				.persistencePriority(HIGH) //
				.text("Round-trip efficiency in use: measured from the history, otherwise configured")), //
		PRICE_MISSING(Doc.of(Level.WARNING) //
				.text("No grid buy price known: charged energy counts without price")), //
		CAPACITY_MISSING(Doc.of(Level.WARNING) //
				.text("The ESS reports no capacity: waiting, the content can not be calibrated")), //
		TIMEDATA_MISSING(Doc.of(Level.WARNING) //
				.text("No Timedata: waiting for the history and the efficiency measurement")), //
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
	 * Gets the Channel for {@link ChannelId#PRICE}.
	 *
	 * @return the Channel
	 */
	public default DoubleReadChannel getPriceChannel() {
		return this.channel(ChannelId.PRICE);
	}

	/**
	 * Gets the price of a delivered kWh in [Currency/MWh]. See
	 * {@link ChannelId#PRICE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Double> getPrice() {
		return this.getPriceChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#PRICE} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setPrice(Double value) {
		this.getPriceChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#STORED_ENERGY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getStoredEnergyChannel() {
		return this.channel(ChannelId.STORED_ENERGY);
	}

	/**
	 * Gets the content of the ESS in [Wh]. See {@link ChannelId#STORED_ENERGY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getStoredEnergy() {
		return this.getStoredEnergyChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#STORED_ENERGY}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setStoredEnergy(Integer value) {
		this.getStoredEnergyChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#STORED_GRID_ENERGY} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setStoredGridEnergy(Integer value) {
		this.channel(ChannelId.STORED_GRID_ENERGY).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#STORED_PV_ENERGY}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setStoredPvEnergy(Integer value) {
		this.channel(ChannelId.STORED_PV_ENERGY).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#UNPRICED_ENERGY}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setUnpricedEnergy(Integer value) {
		this.channel(ChannelId.UNPRICED_ENERGY).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#STORED_COST}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setStoredCost(Double value) {
		this.channel(ChannelId.STORED_COST).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CHARGE_GRID_POWER}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setChargeGridPower(Integer value) {
		this.channel(ChannelId.CHARGE_GRID_POWER).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CHARGE_PV_POWER}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setChargePvPower(Integer value) {
		this.channel(ChannelId.CHARGE_PV_POWER).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MISSING_PRICE_ENERGY} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMissingPriceEnergy(Long value) {
		this.channel(ChannelId.MISSING_PRICE_ENERGY).setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#EFFICIENCY}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setEfficiency(Double value) {
		this.channel(ChannelId.EFFICIENCY).setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#PRICE_MISSING}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getPriceMissingChannel() {
		return this.channel(ChannelId.PRICE_MISSING);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#PRICE_MISSING}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setPriceMissing(boolean value) {
		this.getPriceMissingChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CAPACITY_MISSING}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getCapacityMissingChannel() {
		return this.channel(ChannelId.CAPACITY_MISSING);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CAPACITY_MISSING}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setCapacityMissing(boolean value) {
		this.getCapacityMissingChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#TIMEDATA_MISSING}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getTimedataMissingChannel() {
		return this.channel(ChannelId.TIMEDATA_MISSING);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#TIMEDATA_MISSING}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setTimedataMissing(boolean value) {
		this.getTimedataMissingChannel().setNextValue(value);
	}
}
