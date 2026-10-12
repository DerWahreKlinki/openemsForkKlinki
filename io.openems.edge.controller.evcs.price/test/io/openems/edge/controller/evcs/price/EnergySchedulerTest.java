package io.openems.edge.controller.evcs.price;

import static io.openems.edge.controller.evcs.price.EnergyScheduler.buildEnergyScheduleHandler;
import static io.openems.edge.controller.evcs.price.EnergyScheduler.calculateExpectedChargePower;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.openems.edge.controller.evcs.price.EnergyScheduler.Config;
import io.openems.edge.controller.test.DummyController;
import io.openems.edge.energy.api.test.EnergyScheduleTester;

public class EnergySchedulerTest {

	private static Config config(boolean pluggedIn, int storageReserveWh) {
		return new Config(true, 30, 20, 11040, Priority.STORAGE, 4140, 11040, pluggedIn, storageReserveWh);
	}

	@Test
	public void testNull() {
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"), () -> null);
		var t = EnergyScheduleTester.from(esh);
		assertEquals(0, t.simulatePeriodIndex(0).ef().getManagedConsumption());
	}

	@Test
	public void testNotPluggedIn() {
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"), () -> config(false, 5000));
		var t = EnergyScheduleTester.from(esh);
		assertEquals(0, t.simulatePeriodIndex(0).ef().getManagedConsumption());
	}

	@Test
	public void testChargeByPrice() {
		// Test fixture: period 0 has a grid buy price of 293.70 (29.37 ct), between the
		// limits 30 and 20 ct -> 4140 + 6900 * 0.063 = 4575 W -> 1143 Wh per quarter
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"), () -> config(true, 0));
		var t = EnergyScheduleTester.from(esh);
		assertEquals(1143, t.simulatePeriodIndex(0).ef().getManagedConsumption("ctrl0"));
		// period 24: 331.70 (33.17 ct) -> too expensive, no surplus (production 6 W)
		assertEquals(0, t.simulatePeriodIndex(24).ef().getManagedConsumption());
	}

	@Test
	public void testStorageReserve() {
		// Periods 20-23 (05:00-05:45): 30.29 ct, too expensive by price, no surplus;
		// 1500 Wh storage reserve: the car takes the minimum power from the reserve
		// until it is used up; the schedule context is shared over consecutive
		// periods. Later periods may be hours, so the energy is taken from the period
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"), () -> config(true, 1500));
		var t = EnergyScheduleTester.from(esh);
		var remaining = 1500;
		for (var i = 20; i < 24; i++) {
			var p = t.simulatePeriodIndex(i);
			var expected = Math.min(p.period().duration().convertPowerToEnergy(4140), remaining);
			remaining -= expected;
			assertEquals(expected, p.ef().getManagedConsumption("ctrl0"), "period " + i);
		}
		assertEquals(0, remaining);
	}

	@Test
	public void testCalculateExpectedChargePower() {
		final var storage = config(true, 0);
		final var car = new Config(true, 30, 20, 11040, Priority.CAR, 4140, 11040, true, 0);
		// price only
		assertEquals(4575, calculateExpectedChargePower(storage, 293.7, 0));
		assertEquals(0, calculateExpectedChargePower(storage, 331.7, 0));
		assertEquals(11040, calculateExpectedChargePower(storage, 150., 0));
		// surplus: priority CAR takes it (above minimum power), priority STORAGE leaves
		// it to the optimizer
		assertEquals(6000, calculateExpectedChargePower(car, 331.7, 6000));
		assertEquals(0, calculateExpectedChargePower(storage, 331.7, 6000));
		assertEquals(0, calculateExpectedChargePower(car, 331.7, 3000));
		// the higher of price and surplus wins, capped at the maximum hardware power
		assertEquals(11040, calculateExpectedChargePower(car, 150., 6000));
		assertEquals(11040, calculateExpectedChargePower(car, 331.7, 15000));
		// price charging off
		var off = new Config(false, 30, 20, 11040, Priority.STORAGE, 4140, 11040, true, 0);
		assertEquals(0, calculateExpectedChargePower(off, 150., 0));
	}
}
