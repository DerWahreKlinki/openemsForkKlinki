package io.openems.edge.timeofusetariff.forecast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.junit.Test;

import io.openems.common.utils.JsonUtils;
import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;
import io.openems.edge.timeofusetariff.forecast.PriceForecast.Thresholds;

public class PriceForecastTest {

	private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
	// Tuesday 2026-11-03 07:20 local time
	private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 3, 7, 20, 0, 0, ZONE);
	private static final Thresholds THRESHOLDS = new Thresholds(40, 45, 50, 55);

	// A forecast of a constant price in quarter-hours over 'hours'
	private static NavigableMap<Instant, Double> constantForecast(ZonedDateTime from, int hours, double price) {
		var result = new TreeMap<Instant, Double>();
		for (var i = 0; i < hours * 4; i++) {
			result.put(from.plusMinutes(15L * i).toInstant(), price);
		}
		return result;
	}

	@Test
	public void mergePrefersPublishedPricesAndContinuesWithForecast() {
		// Tariff: current quarter 07:15 and the next, 600 and 610
		var tariff = TimeOfUsePrices.from(NOW.withMinute(15).toInstant(), 600d, 610d);
		// Forecast from 06:00 for 3 hours, 500
		var forecast = constantForecast(NOW.withHour(6).withMinute(0), 3, 500d);

		var sut = PriceForecast.merge(NOW, tariff, forecast, 24);

		var quarters = sut.quarters();
		// 07:15 .. 09:45 = 11 quarters: the last forecast value (08:45) still covers one hour
		assertEquals(11, quarters.size());
		assertEquals(NOW.withMinute(15), quarters.get(0).time());
		assertEquals(600d, quarters.get(0).price(), 0);
		assertTrue(quarters.get(0).published());
		assertEquals(610d, quarters.get(1).price(), 0);
		assertTrue(quarters.get(1).published());
		assertEquals(500d, quarters.get(2).price(), 0);
		assertFalse(quarters.get(2).published());
		assertEquals(2, sut.hours());
	}

	@Test
	public void mergeStopsAtTheFirstGap() {
		var forecast = constantForecast(NOW.withMinute(0), 2, 500d);
		// a gap of two hours, then more values
		forecast.putAll(constantForecast(NOW.withMinute(0).plusHours(4), 2, 400d));

		var sut = PriceForecast.merge(NOW, TimeOfUsePrices.EMPTY_PRICES, forecast, 24);

		// 07:15 .. 09:45 = 11 quarters; the 11:00 values are never reached
		assertEquals(11, sut.quarters().size());
		assertEquals(500d, sut.quarters().get(sut.quarters().size() - 1).price(), 0);
	}

	@Test
	public void mergeWithoutAnyDataIsEmpty() {
		var sut = PriceForecast.merge(NOW, null, null, 24);
		assertTrue(sut.isEmpty());
		assertTrue(sut.current().isEmpty());
		assertTrue(sut.days(4).isEmpty());
		assertTrue(sut.cheapestWindow(4).isEmpty());
	}

	@Test
	public void cheapestWindowAndDays() {
		// 2 days from 07:15: 500, except 02:00-04:00 of the next day with 300
		var forecast = constantForecast(NOW.withMinute(0), 48, 500d);
		var cheapFrom = NOW.plusDays(1).withHour(2).withMinute(0);
		forecast.putAll(constantForecast(cheapFrom, 2, 300d));

		var sut = PriceForecast.merge(NOW, TimeOfUsePrices.EMPTY_PRICES, forecast, 48);

		// cheapest 4 h window: 02:00-04:00 cheap plus 2 h of 500 around it -> 400;
		// the sliding window finds the first such window, starting 00:00
		var window = sut.cheapestWindow(4).get();
		assertEquals(400d, window.price(), 0.01);
		assertEquals(cheapFrom.minusHours(2), window.from());
		assertEquals(cheapFrom.plusHours(2), window.to());

		var days = sut.days(4);
		assertEquals(3, days.size());
		var today = days.get(0);
		assertEquals(LocalDate.of(2026, 11, 3), today.date());
		// 07:15 .. 23:45 = 67 quarters
		assertEquals(67, today.quarters());
		assertEquals(0, today.published());
		assertNull(today.periods().night());
		assertEquals(500d, today.periods().morning(), 0);
		assertEquals(500d, today.periods().evening(), 0);
		assertEquals(500d, today.cheapestWindow().price(), 0);

		var tomorrow = days.get(1);
		assertEquals(96, tomorrow.quarters());
		assertEquals(300d, tomorrow.min(), 0);
		assertEquals(cheapFrom, tomorrow.minAt());
		assertEquals(500d, tomorrow.max(), 0);
		// night 00-06: 16 quarters of 500 and 8 of 300
		assertEquals((16 * 500d + 8 * 300d) / 24, tomorrow.periods().night(), 0.01);
		assertEquals(400d, tomorrow.cheapestWindow().price(), 0.01);

		// the day after holds 00:00 .. 07:00: the horizon of 48 h ends at 07:15
		var dayAfter = days.get(2);
		assertEquals(29, dayAfter.quarters());
		assertEquals(500d, dayAfter.cheapestWindow().price(), 0);
	}

	@Test
	public void rating() {
		// [Currency/MWh]
		assertEquals(1, THRESHOLDS.rate(399d));
		assertEquals(2, THRESHOLDS.rate(400d));
		assertEquals(3, THRESHOLDS.rate(470d));
		assertEquals(4, THRESHOLDS.rate(549d));
		assertEquals(5, THRESHOLDS.rate(550d));
	}

	@Test
	public void parseEpexPredictor() throws Exception {
		var json = JsonUtils.parse("{\"s\":[1793686500,1793687400,1793688300],\"t\":[0.569,null,0.4686]}");
		var result = PriceForecast.parseEpexPredictor(json);
		assertEquals(2, result.size());
		assertEquals(569d, result.get(Instant.ofEpochSecond(1793686500)), 0.001);
		assertEquals(468.6, result.get(Instant.ofEpochSecond(1793688300)), 0.001);
	}

	@Test
	public void tariffMinusForecast() {
		var tariff = TimeOfUsePrices.from(NOW.withMinute(15).toInstant(), 600d, 610d);
		var forecast = constantForecast(NOW.withMinute(0), 2, 500d);
		assertEquals(105d, PriceForecast.tariffMinusForecast(tariff, forecast).get(), 0.001);
		assertTrue(PriceForecast.tariffMinusForecast(TimeOfUsePrices.EMPTY_PRICES, forecast).isEmpty());
	}

	@Test
	public void toJson() {
		var tariff = TimeOfUsePrices.from(NOW.withMinute(15).toInstant(), 600d);
		var forecast = constantForecast(NOW.withMinute(0), 30, 456.78);
		var sut = PriceForecast.merge(NOW, tariff, forecast, 48);

		var json = sut.toJson(THRESHOLDS, 4, NOW.toInstant(), ZONE);

		assertEquals(40, json.getAsJsonObject("thresholds").get("level2").getAsInt());
		assertEquals(4, json.get("windowHours").getAsInt());
		assertEquals("2026-11-03T07:20:00+01:00", json.get("lastUpdate").getAsString());
		var quarters = json.getAsJsonArray("quarters");
		assertEquals(sut.quarters().size(), quarters.size());
		var first = quarters.get(0).getAsJsonObject();
		assertEquals("2026-11-03T07:15:00+01:00", first.get("time").getAsString());
		assertEquals(600d, first.get("price").getAsDouble(), 0);
		assertTrue(first.get("published").getAsBoolean());
		var second = quarters.get(1).getAsJsonObject();
		assertEquals(456.8, second.get("price").getAsDouble(), 0);
		assertFalse(second.get("published").getAsBoolean());
		var days = json.getAsJsonArray("days");
		assertEquals(2, days.size());
		var today = days.get(0).getAsJsonObject();
		assertEquals("2026-11-03", today.get("date").getAsString());
		assertEquals(1, today.get("published").getAsInt());
		assertTrue(today.getAsJsonObject("periods").get("night").isJsonNull());
		assertFalse(today.get("cheapestWindow").isJsonNull());
	}
}
