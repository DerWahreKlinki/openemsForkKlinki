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

		assertEquals(4000 /* no discharge limitation */, t.simulatePeriodIndex(0).ef().setEss(4000));
		assertEquals(-3894 /* no charge limitation */, t.simulatePeriodIndex(0).ef().setEss(-4000));
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
				t.simulatePeriodIndex(0).ef().setEss(4000));

		/*
		 * maxSoc = 90 % -> maxEnergy = 19800 Wh
		 * currentEnergy is far below maxEnergy, therefore no charge limitation
		 * by this controller.
		 */
		assertEquals(-3894 /* no charge limitation by maxSoc */,
				t.simulatePeriodIndex(0).ef().setEss(-4000));
	}

	@Test
	public void testEnergySchedulerPowerAllowed() {
		// the window leaves room, the power allowed right now limits: 2000 W
		// discharge and 8000 W charge in a quarter-hour
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"),
				() -> new EnergyScheduler.Config(0, 22000, 8000, 2000));
		var t = EnergyScheduleTester.from(esh);
		assertEquals(500 /* 2000 W for a quarter-hour */, t.simulatePeriodIndex(0).ef().setEss(4000));
		assertEquals(-2000 /* 8000 W for a quarter-hour */, t.simulatePeriodIndex(0).ef().setEss(-4000));
	}

	@Test
	public void testEnergySchedulerNoPowerAllowed() {
		// an ESS at its minimum: no discharge right now, charging stays possible
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"),
				() -> new EnergyScheduler.Config(0, 22000, -1, 0));
		var t = EnergyScheduleTester.from(esh);
		assertEquals(0, t.simulatePeriodIndex(0).ef().setEss(4000));
		assertEquals(-3894, t.simulatePeriodIndex(0).ef().setEss(-4000));
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
				t.simulatePeriodIndex(0).ef().setEss(-4000));
	}
}