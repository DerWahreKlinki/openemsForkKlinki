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
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"),
				() -> new EnergyScheduler.Config(20, 90));

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
	public void testEnergySchedulerMaxSocAlreadyExceeded() {
		var esh = buildEnergyScheduleHandler(new DummyController("ctrl0"),
				() -> new EnergyScheduler.Config(0, 20));

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