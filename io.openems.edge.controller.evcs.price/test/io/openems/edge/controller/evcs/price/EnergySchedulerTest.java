package io.openems.edge.controller.evcs.price;

import static io.openems.edge.controller.evcs.price.EnergyScheduler.buildEnergyScheduleHandler;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.edge.controller.evcs.price.EnergyScheduler.Config;
import io.openems.edge.controller.test.DummyController;
import io.openems.edge.energy.api.test.EnergyScheduleTester;

public class EnergySchedulerTest {

	@Test
	public void testNull() {
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"), () -> null);
		var t = EnergyScheduleTester.from(esh);
		assertEquals(0, t.simulatePeriodIndex(0).ef().getManagedConsumption());
	}

	@Test
	public void testNotCharging() {
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"), () -> null);
		final var start = EnergyScheduleTester.from(esh).simulatePeriodIndex(0).period().time().toEpochSecond();
		var notCharging = buildEnergyScheduleHandler(new DummyController("ctrl0"),
				() -> new Config(false, start, List.of(1035, 1035, 1035, 1035)));
		var t = EnergyScheduleTester.from(notCharging);
		assertEquals(0, t.simulatePeriodIndex(0).ef().getManagedConsumption());
		assertEquals(0, t.simulatePeriodIndex(1).ef().getManagedConsumption());
	}

	@Test
	public void testChargingFollowsThePlan() {
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"), () -> null);
		final var start = EnergyScheduleTester.from(esh).simulatePeriodIndex(0).period().time().toEpochSecond();
		// plan: 1035 Wh in the first two quarter-hours, nothing afterwards
		var charging = buildEnergyScheduleHandler(new DummyController("ctrl0"),
				() -> new Config(true, start, List.of(1035, 1035, 0, 0)));
		var t = EnergyScheduleTester.from(charging);
		assertEquals(1035, t.simulatePeriodIndex(0).ef().getManagedConsumption("ctrl0"));
		assertEquals(1035, t.simulatePeriodIndex(1).ef().getManagedConsumption("ctrl0"));
		assertEquals(0, t.simulatePeriodIndex(2).ef().getManagedConsumption());
		assertEquals(0, t.simulatePeriodIndex(24).ef().getManagedConsumption());
	}

	@Test
	public void testEnergyBetween() {
		final var start = ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneId.of("UTC"));
		final var config = new Config(true, start.toEpochSecond(), List.of(100, 200, 300, 400, 500));
		assertEquals(100, config.energyBetween(start, start.plusMinutes(15)));
		assertEquals(1000, config.energyBetween(start, start.plusHours(1)));
		// partly outside of the plan
		assertEquals(500, config.energyBetween(start.plusHours(1), start.plusHours(2)));
		assertEquals(0, config.energyBetween(start.minusHours(1), start));
		assertEquals(0, new Config(true, start.toEpochSecond(), List.of()).energyBetween(start, start.plusHours(1)));
	}

	@Test
	public void testSerializer() {
		final var config = new Config(true, 1760529600L, List.of(1035, 0, 2070));
		final var json = Config.serializer().serialize(config);
		assertEquals(config, Config.serializer().deserialize(json));
	}
}
