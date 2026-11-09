package io.openems.edge.timeofusetariff.forecast;

import static io.openems.common.test.TestUtils.createDummyClock;
import static io.openems.edge.timeofusetariff.forecast.TimeOfUseTariffForecast.ChannelId.CHEAPEST_WINDOW_PRICE;
import static io.openems.edge.timeofusetariff.forecast.TimeOfUseTariffForecast.ChannelId.CURRENT_PRICE;
import static io.openems.edge.timeofusetariff.forecast.TimeOfUseTariffForecast.ChannelId.CURRENT_RATING;
import static io.openems.edge.timeofusetariff.forecast.TimeOfUseTariffForecast.ChannelId.FORECAST_HOURS;
import static io.openems.edge.timeofusetariff.forecast.TimeOfUseTariffForecast.ChannelId.NO_DATA;
import static io.openems.edge.timeofusetariff.forecast.TimeOfUseTariffForecast.ChannelId.TODAY_MAX_PRICE;
import static io.openems.edge.timeofusetariff.forecast.TimeOfUseTariffForecast.ChannelId.TODAY_MIN_PRICE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.TreeMap;

import org.junit.Test;

import io.openems.common.bridge.http.dummy.DummyBridgeHttpFactory;
import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.test.TimeLeapClock;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.timeofusetariff.test.DummyTimeOfUseTariffProvider;

public class TimeOfUseTariffForecastImplTest {

	private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

	@Test
	public void channelsFollowTariffAndForecast() throws Exception {
		// 2026-11-03 07:20 local
		var now = ZonedDateTime.of(2026, 11, 3, 7, 20, 0, 0, ZONE);
		var clock = new TimeLeapClock(now.toInstant(), ZONE);
		var sut = new TimeOfUseTariffForecastImpl();
		// published: 07:15 at 60 ct, 07:30 at 61 ct
		var tariff = DummyTimeOfUseTariffProvider.fromQuarterlyPrices(clock, 600d, 610d);
		var test = new ComponentTest(sut) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("httpBridgeFactory", DummyBridgeHttpFactory.ofDummyBridge()) //
				.addReference("timeOfUseTariff", tariff) //
				.activate(MyConfig.create() //
						.setId("priceForecast0") //
						.setTimeOfUseTariffId("timeOfUseTariff0") //
						.setWindowHours(2) //
						.build());

		// no forecast yet: only the two published quarters
		test.next(new TestCase() //
				.output(CURRENT_PRICE, 600d) //
				.output(CURRENT_RATING, 5) //
				.output(FORECAST_HOURS, 0) //
				.output(NO_DATA, false) //
				.output(TODAY_MIN_PRICE, 600d) //
				.output(TODAY_MAX_PRICE, 610d) //
				.output(CHEAPEST_WINDOW_PRICE, null));

		// forecast: from 07:00 for 6 hours 48 ct, the cheapest window (2 h) is forecast only
		var forecast = new TreeMap<Instant, Double>();
		var from = now.withMinute(0);
		for (var i = 0; i < 24; i++) {
			forecast.put(from.plusMinutes(15L * i).toInstant(), 480d);
		}
		sut.applyForecast(forecast, now.toInstant());

		test.next(new TestCase() //
				.output(CURRENT_PRICE, 600d) //
				.output(FORECAST_HOURS, 6) //
				.output(TODAY_MIN_PRICE, 480d) //
				.output(TODAY_MAX_PRICE, 610d) //
				.output(CHEAPEST_WINDOW_PRICE, 480d));

		// quarter-hour later the published price of 07:30 is current, rated 5
		clock.leap(15, ChronoUnit.MINUTES);
		test.next(new TestCase() //
				.output(CURRENT_PRICE, 610d) //
				.output(CURRENT_RATING, 5));

		// an hour later only the forecast is left, rated 3 (48 ct: between 45 and 50)
		clock.leap(45, ChronoUnit.MINUTES);
		test.next(new TestCase() //
				.output(CURRENT_PRICE, 480d) //
				.output(CURRENT_RATING, 3));

		var url = sut.buildUrl();
		assertTrue(url, url.startsWith("https://epexpredictor.batzill.com/prices_short?"));
		assertTrue(url, url.contains("region=DE"));
		assertTrue(url, url.contains("surcharge=17.4"));
		assertTrue(url, url.contains("taxPercent=19"));
		assertTrue(url, url.contains("hours=144"));
		assertTrue(sut.debugLog(), sut.debugLog().startsWith("48.0 ct*|Rating:3|Horizon:5 h|Cheapest:"));
	}

	@Test
	public void withoutAnyDataTheOutlookIsEmpty() throws Exception {
		var clock = createDummyClock();
		var sut = new TimeOfUseTariffForecastImpl();
		new ComponentTest(sut) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("httpBridgeFactory", DummyBridgeHttpFactory.ofDummyBridge()) //
				.addReference("timeOfUseTariff", DummyTimeOfUseTariffProvider.empty(clock)) //
				.activate(MyConfig.create() //
						.setId("priceForecast0") //
						.build()) //
				.next(new TestCase() //
						.output(NO_DATA, true) //
						.output(CURRENT_PRICE, null) //
						.output(FORECAST_HOURS, 0));
		assertEquals("No outlook", sut.debugLog());
	}
}
