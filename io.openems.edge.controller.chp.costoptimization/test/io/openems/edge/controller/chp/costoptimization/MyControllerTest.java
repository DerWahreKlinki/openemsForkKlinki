package io.openems.edge.controller.chp.costoptimization;

import static io.openems.common.test.TestUtils.createDummyClock;
import static io.openems.edge.controller.chp.costoptimization.ControllerChpCostOptimization.ChannelId.STATE_MACHINE;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.types.MeterType;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.generator.test.DummyManagedSymmetricGenerator;
import io.openems.edge.meter.test.DummyElectricityMeter;
import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;
import io.openems.edge.timeofusetariff.test.DummyTimeOfUseTariffProvider;

public class MyControllerTest {

	/**
	 * Regression test: without any TimeOfUseTariff reference at all (the
	 * reference is declared OPTIONAL), the controller used to enter ERROR and
	 * shut the CHP off permanently. It must instead enter WARNING and keep
	 * actively controlling the CHP (e.g. still start it on genuine heat demand),
	 * using safe price defaults (0.0) instead of freezing.
	 */
	@Test
	public void testRunsWithoutTimeOfUseTariff_TemperatureBelowMinStillStartsChp() throws Exception {
		final var clock = createDummyClock();

		var chp = new DummyManagedSymmetricGenerator("chp0") //
				.withGeneratorActivePower(0) //
				.withReadyForOperation(true) //
				.withAverageBufferTankTemperature(550); // 55.0°C, below minBufferTankTemperature

		new ControllerTest(new ControllerChpCostOptimizationImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("chp", chp) //
				.addReference("gridMeter", new DummyElectricityMeter("meter0") //
						.withMeterType(MeterType.GRID) //
						.withActivePower(5000)) //
				// no timeOfUseTariff reference added -> stays null
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setChpId("chp0") //
						.setMeterId("meter0") //
						.setMinGridPower(1000) //
						.setMinBufferTankTemperature(60) //
						.setThresholdBufferTankTemperature(70) //
						.setMaxBufferTankTemperature(75) //
						.setReducePowerThresholdTemperature(200) // disable unrelated near-max power reduction
						.setPriceThreshold(100) //
						.setMaxActivePower(10000) //
						.build()) //
				.next(new TestCase("No prices available -> WARNING, not ERROR") //
						.output(STATE_MACHINE, State.WARNING)) //
				.next(new TestCase("Still WARNING: temperature-driven start is allowed despite missing prices") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.output(STATE_MACHINE, State.CHP_ACTIVE)) //
				.next(new TestCase("CHP is actively driven with a real target power, not frozen") //
						.output(STATE_MACHINE, State.CHP_ACTIVE)) //
				.deactivate();

		if (chp.getLastAppliedPower() == null || chp.getLastAppliedPower() != 5000) {
			throw new AssertionError(
					"Expected CHP to be actively driven with target power 5000W even without TimeOfUseTariff, but last applied power was: "
							+ chp.getLastAppliedPower());
		}
	}

	/**
	 * Regression test for the WARNING state itself: with a TimeOfUseTariff
	 * reference present but returning no price data (e.g. a transient outage),
	 * the state machine's switch had no `case WARNING`, so it fell into
	 * `default: break;` and never transitioned back to NORMAL again - even after
	 * prices became available. It must now recover automatically.
	 */
	@Test
	public void testRecoversFromWarningWhenPricesBecomeAvailableAgain() throws Exception {
		final var clock = createDummyClock();

		var tariff = DummyTimeOfUseTariffProvider.empty(clock);

		var test = new ControllerTest(new ControllerChpCostOptimizationImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("timeOfUseTariff", tariff) //
				.addReference("chp", new DummyManagedSymmetricGenerator("chp0") //
						.withGeneratorActivePower(0) //
						.withReadyForOperation(true) //
						.withAverageBufferTankTemperature(650)) // 65.0°C, within normal range
				.addReference("gridMeter", new DummyElectricityMeter("meter0") //
						.withMeterType(MeterType.GRID) //
						.withActivePower(5000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setChpId("chp0") //
						.setMeterId("meter0") //
						.setMinGridPower(1000) //
						.setMinBufferTankTemperature(60) //
						.setThresholdBufferTankTemperature(70) //
						.setMaxBufferTankTemperature(75) //
						.setReducePowerThresholdTemperature(200) //
						.setPriceThreshold(100) //
						.setMaxActivePower(10000) //
						.build());

		test.next(new TestCase("Empty prices -> WARNING") //
				.output(STATE_MACHINE, State.WARNING));

		test.next(new TestCase("Still WARNING, still no prices: controller keeps deciding, doesn't freeze") //
				.timeleap(clock, 11, ChronoUnit.SECONDS) //
				.output(STATE_MACHINE, State.WARNING));

		// Prices become available again
		tariff.setPrices(TimeOfUsePrices.from(Instant.now(clock), 50.0, 50.0, 50.0, 50.0));

		test.next(new TestCase("Prices available again -> automatic recovery to NORMAL") //
				.timeleap(clock, 11, ChronoUnit.SECONDS) //
				.output(STATE_MACHINE, State.NORMAL));

		test.deactivate();
	}

}
