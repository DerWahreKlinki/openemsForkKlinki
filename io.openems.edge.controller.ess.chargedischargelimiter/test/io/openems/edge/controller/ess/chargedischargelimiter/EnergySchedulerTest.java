package io.openems.edge.controller.ess.chargedischargelimiter;

import static io.openems.edge.controller.ess.chargedischargelimiter.EnergyScheduler.buildEnergyScheduleHandler;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.openems.edge.controller.test.DummyController;
import io.openems.edge.energy.api.test.EnergyScheduleTester;

public class EnergySchedulerTest {

	@Test
	public void testNull() {
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"), () -> null);
		var t = EnergyScheduleTester.from(esh);

		assertEquals(4000 /* no discharge limitation */, t.simulatePeriodIndex(0).ef().commitEss(4000));
		assertEquals(-3894 /* no charge limitation */, t.simulatePeriodIndex(0).ef().commitEss(-4000));
	}

	@Test
	public void testEnergySchedulerMinMaxSoc() {
		// minSoc 20 % and maxSoc 90 % of 22000 Wh, no power limit
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"),
				() -> new EnergyScheduler.Config(4400, 19800, -1, -1));

		assertEquals("", esh.getParentFactoryPid());
		assertEquals("ctrl0", esh.getParentId());

		var t = EnergyScheduleTester.from(esh);

		/*
		 * Test fixture:
		 * currentEnergy = 5000 Wh
		 * totalEnergy   = 22000 Wh
		 *
		 * minSoc = 20 % -> minEnergy = 4400 Wh
		 * allowedDischarge = 5000 - 4400 = 600 Wh
		 */
		assertEquals(600 /* discharge limited by minSoc */,
				t.simulatePeriodIndex(0).ef().commitEss(4000));

		/*
		 * maxSoc = 90 % -> maxEnergy = 19800 Wh
		 * currentEnergy is far below maxEnergy, therefore no charge limitation
		 * by this controller.
		 */
		assertEquals(-3894 /* no charge limitation by maxSoc */,
				t.simulatePeriodIndex(0).ef().commitEss(-4000));
	}

	@Test
	public void testEnergySchedulerPowerAllowedIsNotSimulated() {
		// the power allowed right now is a snapshot and no limit for the schedule
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"),
				() -> new EnergyScheduler.Config(0, 22000, 8000, 2000));
		var t = EnergyScheduleTester.from(esh);
		assertEquals(4000, t.simulatePeriodIndex(0).ef().commitEss(4000));
		assertEquals(-3894, t.simulatePeriodIndex(0).ef().commitEss(-4000));
	}

	@Test
	public void testEnergySchedulerEmptyEssStaysWorthCharging() {
		// 17.11.2026: both ESS at minSoc reported 0 W discharge; applied to every
		// period the optimizer dropped the planned charging from the grid. With
		// energy above minEnergy, discharging is possible whatever the snapshot says
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"),
				() -> new EnergyScheduler.Config(4400, 19800, 10000, 0));
		var t = EnergyScheduleTester.from(esh);
		assertEquals(600 /* 5000 Wh - minEnergy */, t.simulatePeriodIndex(0).ef().commitEss(4000));
		assertEquals(600, t.simulatePeriodIndex(5).ef().commitEss(4000));
		assertEquals(-3894, t.simulatePeriodIndex(0).ef().commitEss(-4000));
	}

	@Test
	public void testEnergySchedulerMaxSocAlreadyExceeded() {
		// maxSoc 20 % of 22000 Wh
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"),
				() -> new EnergyScheduler.Config(0, 4400, -1, -1));

		var t = EnergyScheduleTester.from(esh);

		/*
		 * Test fixture:
		 * currentEnergy = 5000 Wh
		 * totalEnergy   = 22000 Wh
		 *
		 * maxSoc = 20 % -> maxEnergy = 4400 Wh
		 * currentEnergy is already above maxEnergy.
		 * Charging must therefore be blocked.
		 */
		assertEquals(0 /* charging blocked by maxSoc */,
				t.simulatePeriodIndex(0).ef().commitEss(-4000));
	}
}