package io.openems.edge.timeofusetariff.forecast;

import static io.openems.common.utils.JsonUtils.buildJsonObject;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;

/**
 * The price outlook: a series of quarter-hours from now on, each with a price
 * [Currency/MWh] and whether it is a published tariff price or a forecast.
 * Pure logic, no OSGi.
 */
public class PriceForecast {

	/** Quarter-hours per day. */
	public static final int QUARTERS_PER_DAY = 96;
	private static final Duration QUARTER = Duration.ofMinutes(15);
	private static final double CURRENCY_PER_MWH_TO_CT_PER_KWH = 0.1;
	private static final double KWH_TO_MWH = 1000;
	/** A forecast value is used for a quarter if it is at most this old. */
	private static final Duration MAX_FORECAST_GAP = Duration.ofHours(1);
	private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	/**
	 * One quarter-hour.
	 *
	 * @param time      start of the quarter-hour
	 * @param price     the price [Currency/MWh]
	 * @param published true for a published tariff price, false for a forecast
	 */
	public record Quarter(ZonedDateTime time, double price, boolean published) {
	}

	/**
	 * A window of consecutive quarter-hours.
	 *
	 * @param from  start (inclusive)
	 * @param to    end (exclusive)
	 * @param price average price [Currency/MWh]
	 */
	public record Window(ZonedDateTime from, ZonedDateTime to, double price) {
	}

	/**
	 * Averages of the four parts of a day: night 00-06, morning 06-12, noon 12-18,
	 * evening 18-24 (local time); null where no quarter is known.
	 */
	public record Periods(Double night, Double morning, Double noon, Double evening) {
	}

	/**
	 * One local day of the outlook.
	 *
	 * @param date           the day
	 * @param quarters       known quarter-hours of this day (from now on)
	 * @param published      how many of them are published tariff prices
	 * @param min            lowest price
	 * @param minAt          start of the cheapest quarter-hour
	 * @param max            highest price
	 * @param average        average price
	 * @param periods        averages of night, morning, noon, evening
	 * @param cheapestWindow cheapest window of the configured length that starts
	 *                       on this day; null if the day holds fewer quarters
	 */
	public record Day(LocalDate date, int quarters, int published, double min, ZonedDateTime minAt, double max,
			double average, Periods periods, Window cheapestWindow) {
	}

	/**
	 * Rating thresholds [ct/kWh]: below level2 one Euro, below level3 two, below
	 * level4 three, below level5 four, otherwise five.
	 */
	public record Thresholds(int level2, int level3, int level4, int level5) {

		/**
		 * Rates a price.
		 *
		 * @param price the price [Currency/MWh]
		 * @return 1 (cheap) to 5 (expensive)
		 */
		public int rate(double price) {
			var ct = price * CURRENCY_PER_MWH_TO_CT_PER_KWH;
			if (ct < this.level2) {
				return 1;
			}
			if (ct < this.level3) {
				return 2;
			}
			if (ct < this.level4) {
				return 3;
			}
			if (ct < this.level5) {
				return 4;
			}
			return 5;
		}
	}

	private final List<Quarter> quarters;

	private PriceForecast(List<Quarter> quarters) {
		this.quarters = List.copyOf(quarters);
	}

	/**
	 * Builds the outlook from now on: tariff prices where published, otherwise the
	 * forecast. The series ends at the first quarter-hour without any value.
	 *
	 * @param now          the current time
	 * @param tariff       the published tariff prices [Currency/MWh]; may be empty
	 * @param forecast     the forecast by start time [Currency/MWh]; may be empty
	 * @param horizonHours maximum length of the series [h]
	 * @return the outlook
	 */
	public static PriceForecast merge(ZonedDateTime now, TimeOfUsePrices tariff,
			NavigableMap<Instant, Double> forecast, int horizonHours) {
		var start = now.truncatedTo(ChronoUnit.HOURS) //
				.plusMinutes(15L * (now.getMinute() / 15));
		var end = start.plusHours(horizonHours);
		var result = new ArrayList<Quarter>();
		for (var t = start; t.isBefore(end); t = t.plus(QUARTER)) {
			var instant = t.toInstant();
			var published = tariff == null || tariff.isEmpty() ? null : tariff.getAt(instant);
			if (published != null) {
				result.add(new Quarter(t, published, true));
				continue;
			}
			var entry = forecast == null ? null : forecast.floorEntry(instant);
			if (entry == null || entry.getValue() == null
					|| Duration.between(entry.getKey(), instant).compareTo(MAX_FORECAST_GAP) > 0) {
				break;
			}
			result.add(new Quarter(t, entry.getValue(), false));
		}
		return new PriceForecast(result);
	}

	/**
	 * Parses the response of EpexPredictor ({@code prices_short}): {@code s} holds
	 * Unix timestamps [s], {@code t} the prices [EUR/kWh].
	 *
	 * @param json the response
	 * @return the prices by start time [Currency/MWh]
	 * @throws OpenemsNamedException on a malformed response
	 */
	public static NavigableMap<Instant, Double> parseEpexPredictor(JsonElement json) throws OpenemsNamedException {
		var s = JsonUtils.getAsJsonArray(json, "s");
		var t = JsonUtils.getAsJsonArray(json, "t");
		if (s.size() != t.size()) {
			throw new OpenemsNamedException(io.openems.common.exceptions.OpenemsError.JSON_PARSE_FAILED,
					"EpexPredictor: " + s.size() + " timestamps but " + t.size() + " prices");
		}
		var result = new TreeMap<Instant, Double>();
		for (var i = 0; i < s.size(); i++) {
			if (t.get(i).isJsonNull()) {
				continue;
			}
			result.put(Instant.ofEpochSecond(JsonUtils.getAsLong(s.get(i))),
					JsonUtils.getAsDouble(t.get(i)) * KWH_TO_MWH);
		}
		return result;
	}

	/**
	 * Gets the quarter-hours of the outlook, starting with the current one.
	 *
	 * @return the quarter-hours
	 */
	public List<Quarter> quarters() {
		return this.quarters;
	}

	/**
	 * Is the outlook empty.
	 *
	 * @return true if no quarter-hour is known
	 */
	public boolean isEmpty() {
		return this.quarters.isEmpty();
	}

	/**
	 * Gets the current quarter-hour.
	 *
	 * @return the first quarter-hour of the series
	 */
	public Optional<Quarter> current() {
		return this.quarters.isEmpty() ? Optional.empty() : Optional.of(this.quarters.get(0));
	}

	/**
	 * Gets the number of hours covered from now.
	 *
	 * @return the hours, rounded down
	 */
	public int hours() {
		return this.quarters.size() / 4;
	}

	/**
	 * Finds the cheapest window of the given length anywhere in the series.
	 *
	 * @param windowHours the window length [h]
	 * @return the window; empty if the series is shorter than the window
	 */
	public Optional<Window> cheapestWindow(int windowHours) {
		return cheapestWindow(this.quarters, windowHours);
	}

	private static Optional<Window> cheapestWindow(List<Quarter> quarters, int windowHours) {
		var n = windowHours * 4;
		if (n <= 0 || quarters.size() < n) {
			return Optional.empty();
		}
		Window best = null;
		var sum = 0d;
		for (var i = 0; i < quarters.size(); i++) {
			sum += quarters.get(i).price();
			if (i >= n) {
				sum -= quarters.get(i - n).price();
			}
			if (i >= n - 1) {
				var avg = sum / n;
				if (best == null || avg < best.price()) {
					var from = quarters.get(i - n + 1).time();
					best = new Window(from, from.plusHours(windowHours), avg);
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * Groups the outlook by local day.
	 *
	 * @param windowHours the length of the cheapest window per day [h]
	 * @return the days in order
	 */
	public List<Day> days(int windowHours) {
		var byDay = new LinkedHashMap<LocalDate, List<Quarter>>();
		for (var q : this.quarters) {
			byDay.computeIfAbsent(q.time().toLocalDate(), k -> new ArrayList<>()).add(q);
		}
		var result = new ArrayList<Day>();
		for (var e : byDay.entrySet()) {
			var qs = e.getValue();
			var min = qs.get(0);
			var max = qs.get(0);
			var sum = 0d;
			var published = 0;
			for (var q : qs) {
				sum += q.price();
				if (q.price() < min.price()) {
					min = q;
				}
				if (q.price() > max.price()) {
					max = q;
				}
				if (q.published()) {
					published++;
				}
			}
			result.add(new Day(e.getKey(), qs.size(), published, min.price(), min.time(), max.price(),
					sum / qs.size(), periods(qs), cheapestWindow(qs, windowHours).orElse(null)));
		}
		return result;
	}

	private static Periods periods(List<Quarter> qs) {
		return new Periods(average(qs, 0, 6), average(qs, 6, 12), average(qs, 12, 18), average(qs, 18, 24));
	}

	private static Double average(List<Quarter> qs, int fromHour, int toHour) {
		var sum = 0d;
		var n = 0;
		for (var q : qs) {
			var h = q.time().getHour();
			if (h >= fromHour && h < toHour) {
				sum += q.price();
				n++;
			}
		}
		return n == 0 ? null : sum / n;
	}

	/**
	 * Converts the outlook to JSON for the UI.
	 *
	 * <pre>
	 * {
	 *   "thresholds": { "level2": 40, "level3": 45, "level4": 50, "level5": 55 }, // [ct/kWh]
	 *   "windowHours": 4,
	 *   "quarters": [{ "time": "2026-11-03T07:15:00+01:00", "price": 568.0, "published": true }],
	 *   "days": [{
	 *     "date": "2026-11-03", "quarters": 67, "published": 67,
	 *     "min": 467.0, "minAt": "...", "max": 594.0, "average": 519.0,
	 *     "periods": { "night": null, "morning": 560.1, "noon": 480.2, "evening": 552.3 },
	 *     "cheapestWindow": { "from": "...", "to": "...", "price": 475.0 } | null
	 *   }]
	 * }
	 * </pre>
	 *
	 * @param thresholds  the rating thresholds
	 * @param windowHours the window length [h]
	 * @param lastUpdate  time of the last successful forecast fetch; may be null
	 * @param zone        the zone for timestamps
	 * @return the JSON
	 */
	public JsonObject toJson(Thresholds thresholds, int windowHours, Instant lastUpdate, ZoneId zone) {
		var quarters = new JsonArray();
		for (var q : this.quarters) {
			quarters.add(buildJsonObject() //
					.addProperty("time", ISO.format(q.time())) //
					.addProperty("price", round(q.price())) //
					.addProperty("published", q.published()) //
					.build());
		}
		var days = new JsonArray();
		for (var d : this.days(windowHours)) {
			days.add(buildJsonObject() //
					.addProperty("date", d.date().toString()) //
					.addProperty("quarters", d.quarters()) //
					.addProperty("published", d.published()) //
					.addProperty("min", round(d.min())) //
					.addProperty("minAt", ISO.format(d.minAt())) //
					.addProperty("max", round(d.max())) //
					.addProperty("average", round(d.average())) //
					.add("periods", buildJsonObject() //
							.add("night", number(d.periods().night())) //
							.add("morning", number(d.periods().morning())) //
							.add("noon", number(d.periods().noon())) //
							.add("evening", number(d.periods().evening())) //
							.build()) //
					.add("cheapestWindow", window(d.cheapestWindow())) //
					.build());
		}
		return buildJsonObject() //
				.add("thresholds", buildJsonObject() //
						.addProperty("level2", thresholds.level2()) //
						.addProperty("level3", thresholds.level3()) //
						.addProperty("level4", thresholds.level4()) //
						.addProperty("level5", thresholds.level5()) //
						.build()) //
				.addProperty("windowHours", windowHours) //
				.add("lastUpdate", lastUpdate == null ? JsonNull.INSTANCE
						: new com.google.gson.JsonPrimitive(ISO.format(lastUpdate.atZone(zone)))) //
				.add("quarters", quarters) //
				.add("days", days) //
				.build();
	}

	private static JsonElement window(Window w) {
		if (w == null) {
			return JsonNull.INSTANCE;
		}
		return buildJsonObject() //
				.addProperty("from", ISO.format(w.from())) //
				.addProperty("to", ISO.format(w.to())) //
				.addProperty("price", round(w.price())) //
				.build();
	}

	private static JsonElement number(Double value) {
		return value == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(round(value));
	}

	private static double round(double value) {
		return Math.round(value * 10) / 10d;
	}

	/**
	 * Average difference tariff minus forecast where both are known, to check the
	 * configured surcharge.
	 *
	 * @param tariff   the published tariff prices [Currency/MWh]
	 * @param forecast the forecast by start time [Currency/MWh]
	 * @return the difference [Currency/MWh]; empty without overlap
	 */
	public static Optional<Double> tariffMinusForecast(TimeOfUsePrices tariff, NavigableMap<Instant, Double> forecast) {
		if (tariff == null || tariff.isEmpty() || forecast == null || forecast.isEmpty()) {
			return Optional.empty();
		}
		var sum = 0d;
		var n = 0;
		for (Map.Entry<Instant, Double> e : tariff.toMap().entrySet()) {
			if (e.getValue() == null) {
				continue;
			}
			var f = forecast.floorEntry(e.getKey());
			if (f == null || Duration.between(f.getKey(), e.getKey()).compareTo(MAX_FORECAST_GAP) > 0) {
				continue;
			}
			sum += e.getValue() - f.getValue();
			n++;
		}
		return n == 0 ? Optional.empty() : Optional.of(sum / n);
	}
}
