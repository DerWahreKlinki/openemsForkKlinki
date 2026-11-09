package io.openems.edge.timeofusetariff.forecast;

import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.DoubleReadChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.StringReadChannel;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Price outlook: published Time-of-Use prices continued with a day-ahead price
 * forecast. For display only.
 */
public interface TimeOfUseTariffForecast extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Price of the current quarter-hour: the tariff if published, otherwise the
		 * forecast.
		 */
		CURRENT_PRICE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR) //
				.persistencePriority(PersistencePriority.MEDIUM) //
				.text("Price of the current quarter-hour; tariff if published, otherwise forecast")),
		/**
		 * Rating of the current price: 1 (cheap) to 5 (expensive), by the configured
		 * thresholds.
		 */
		CURRENT_RATING(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.MEDIUM) //
				.text("Rating of the current price: 1 (cheap) to 5 (expensive)")),
		TODAY_MIN_PRICE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR) //
				.text("Lowest quarter-hour price of today from now on")),
		TODAY_MAX_PRICE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR) //
				.text("Highest quarter-hour price of today from now on")),
		/**
		 * Start of the cheapest window within the whole horizon, ISO-8601 with offset.
		 */
		CHEAPEST_WINDOW_START(Doc.of(OpenemsType.STRING) //
				.text("Start of the cheapest window within the horizon (ISO-8601)")),
		CHEAPEST_WINDOW_PRICE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR) //
				.text("Average price of the cheapest window within the horizon")),
		FORECAST_HOURS(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.HOUR) //
				.text("Hours covered from now: published tariff plus forecast")),
		HTTP_STATUS_CODE(Doc.of(OpenemsType.INTEGER) //
				.text("HTTP status of the last forecast fetch")),
		LAST_UPDATE(Doc.of(OpenemsType.LONG) //
				.text("Unix timestamp [s] of the last successful forecast fetch")),
		FETCH_FAILED(Doc.of(Level.WARNING) //
				.text("Fetching the price forecast failed; the outlook shows the last successful fetch")),
		NO_DATA(Doc.of(Level.INFO) //
				.text("No price outlook available yet")); //

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
	 * Gets the Channel for {@link ChannelId#CURRENT_PRICE}.
	 *
	 * @return the Channel
	 */
	public default DoubleReadChannel getCurrentPriceChannel() {
		return this.channel(ChannelId.CURRENT_PRICE);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CURRENT_PRICE}.
	 *
	 * @param value the next value
	 */
	public default void _setCurrentPrice(Double value) {
		this.getCurrentPriceChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CURRENT_RATING}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getCurrentRatingChannel() {
		return this.channel(ChannelId.CURRENT_RATING);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CURRENT_RATING}.
	 *
	 * @param value the next value
	 */
	public default void _setCurrentRating(Integer value) {
		this.getCurrentRatingChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#TODAY_MIN_PRICE}.
	 *
	 * @return the Channel
	 */
	public default DoubleReadChannel getTodayMinPriceChannel() {
		return this.channel(ChannelId.TODAY_MIN_PRICE);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#TODAY_MIN_PRICE}.
	 *
	 * @param value the next value
	 */
	public default void _setTodayMinPrice(Double value) {
		this.getTodayMinPriceChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#TODAY_MAX_PRICE}.
	 *
	 * @return the Channel
	 */
	public default DoubleReadChannel getTodayMaxPriceChannel() {
		return this.channel(ChannelId.TODAY_MAX_PRICE);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#TODAY_MAX_PRICE}.
	 *
	 * @param value the next value
	 */
	public default void _setTodayMaxPrice(Double value) {
		this.getTodayMaxPriceChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CHEAPEST_WINDOW_START}.
	 *
	 * @return the Channel
	 */
	public default StringReadChannel getCheapestWindowStartChannel() {
		return this.channel(ChannelId.CHEAPEST_WINDOW_START);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#CHEAPEST_WINDOW_START}.
	 *
	 * @param value the next value
	 */
	public default void _setCheapestWindowStart(String value) {
		this.getCheapestWindowStartChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CHEAPEST_WINDOW_PRICE}.
	 *
	 * @return the Channel
	 */
	public default DoubleReadChannel getCheapestWindowPriceChannel() {
		return this.channel(ChannelId.CHEAPEST_WINDOW_PRICE);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#CHEAPEST_WINDOW_PRICE}.
	 *
	 * @param value the next value
	 */
	public default void _setCheapestWindowPrice(Double value) {
		this.getCheapestWindowPriceChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#FORECAST_HOURS}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getForecastHoursChannel() {
		return this.channel(ChannelId.FORECAST_HOURS);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#FORECAST_HOURS}.
	 *
	 * @param value the next value
	 */
	public default void _setForecastHours(Integer value) {
		this.getForecastHoursChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#HTTP_STATUS_CODE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getHttpStatusCodeChannel() {
		return this.channel(ChannelId.HTTP_STATUS_CODE);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#HTTP_STATUS_CODE}.
	 *
	 * @param value the next value
	 */
	public default void _setHttpStatusCode(Integer value) {
		this.getHttpStatusCodeChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#LAST_UPDATE}.
	 *
	 * @return the Channel
	 */
	public default LongReadChannel getLastUpdateChannel() {
		return this.channel(ChannelId.LAST_UPDATE);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#LAST_UPDATE}.
	 *
	 * @param value the next value
	 */
	public default void _setLastUpdate(Long value) {
		this.getLastUpdateChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#FETCH_FAILED}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getFetchFailedChannel() {
		return this.channel(ChannelId.FETCH_FAILED);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#FETCH_FAILED}.
	 *
	 * @param value the next value
	 */
	public default void _setFetchFailed(boolean value) {
		this.getFetchFailedChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#NO_DATA}.
	 *
	 * @return the Channel
	 */
	public default Channel<Boolean> getNoDataChannel() {
		return this.channel(ChannelId.NO_DATA);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#NO_DATA}.
	 *
	 * @param value the next value
	 */
	public default void _setNoData(boolean value) {
		this.getNoDataChannel().setNextValue(value);
	}
}
