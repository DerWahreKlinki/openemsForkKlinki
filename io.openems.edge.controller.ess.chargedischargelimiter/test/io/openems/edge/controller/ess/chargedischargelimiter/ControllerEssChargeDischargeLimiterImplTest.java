package io.openems.edge.controller.ess.chargedischargelimiter;



import static  io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_LESS_OR_EQUALS;
import static  io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.STATE_MACHINE;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.AWAITING_HYSTERESIS;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.CHARGED_ENERGY;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.BALANCING_DEFERRAL_REASON;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.ACTIVE_POWER;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.SOC;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import static io.openems.common.test.TestUtils.createDummyClock;
import io.openems.common.test.TimeLeapClock;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.common.test.DummyConfigurationAdmin;

import io.openems.edge.controller.ess.chargedischargelimiter.enums.BalancingDeferralReason;
import io.openems.edge.controller.ess.chargedischargelimiter.enums.State;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;
import io.openems.edge.timedata.test.DummyTimedata;
import io.openems.edge.timeofusetariff.test.DummyTimeOfUseTariffProvider;

public class ControllerEssChargeDischargeLimiterImplTest {

	@Test
	public void test() throws Exception {
		// Initialize mocked Clock
		final var clock = new TimeLeapClock(
				Instant.ofEpochMilli(1546300800000L /* Tuesday, 1. January 2019 00:00:00 */), ZoneId.of("UTC"));
		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
		.addReference("componentManager", new DummyComponentManager(clock)) //
		.addReference("cm", new DummyConfigurationAdmin()) //
		.addReference("ess", new DummyManagedSymmetricEss("ess0") //
				.withSoc(50) //
				.withActivePower(0) //
				.withCapacity(10_000) //
				.withAllowedChargePower(-10_000) //
				.withAllowedDischargePower(10_000)) //
		.activate(MyConfig.create() //
				.setId("ctrl0") //
				.setEssId("ess0") //
				.setMinSoc(15) //
				.setMaxSoc(90) //
				.setForceChargePower(500) //
				.setEnergyBetweenBalancingCycles(0) //
				.build()) //
		.next(new TestCase() //
				.input("ess0", SOC, 50) //
				.input("ess0", ACTIVE_POWER, 0) //
				.output(STATE_MACHINE, State.NORMAL)) //
		.deactivate();
	}
	
	@Test
	public void testNormalWithinSocWindow() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new DummyManagedSymmetricEss("ess0") //
						.withSoc(50) //
						.withActivePower(0) //
						.withCapacity(10_000) //
						.withAllowedChargePower(-10_000) //
						.withAllowedDischargePower(10_000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setMinSoc(15) //
						.setMaxSoc(90) //
						.setEnergyBetweenBalancingCycles(0) //
						.build()) //
				.next(new TestCase() //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.output(STATE_MACHINE, State.NORMAL) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output("ess0", SET_ACTIVE_POWER_LESS_OR_EQUALS, null)) //
				.deactivate();
	}	
	
	@Test
	public void testAboveMaxSocSkipsHysteresisAndBlocksCharge() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new DummyManagedSymmetricEss("ess0") //
						.withSoc(80) //
						.withActivePower(-1000) //
						.withCapacity(10_000) //
						.withAllowedChargePower(-10_000) //
						.withAllowedDischargePower(10_000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setMinSoc(15) //
						.setMaxSoc(90) //
						.setEnergyBetweenBalancingCycles(0) //
						.build()) //
				.next(new TestCase("Initialize NORMAL") //
						.input("ess0", SOC, 80) //
						.input("ess0", ACTIVE_POWER, 0) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Above maxSoc") //
						.input("ess0", SOC, 91) //
						.input("ess0", ACTIVE_POWER, -1000) //
						.output(STATE_MACHINE, State.ABOVE_MAX_SOC) //
						.output(AWAITING_HYSTERESIS, false) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, 0)) //
				.deactivate();
	}
	
	@Test
	public void testBelowMinSocSkipsHysteresisAndBlocksDischarge() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new DummyManagedSymmetricEss("ess0") //
						.withSoc(50) //
						.withActivePower(1000) //
						.withCapacity(10_000) //
						.withAllowedChargePower(-10_000) //
						.withAllowedDischargePower(10_000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setMinSoc(15) //
						.setMaxSoc(90) //
						.setEnergyBetweenBalancingCycles(0) //
						.build()) //
				.next(new TestCase("Initialize NORMAL") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Below minSoc") //
						.input("ess0", SOC, 14) //
						.input("ess0", ACTIVE_POWER, 1000) //
						.output(STATE_MACHINE, State.BELOW_MIN_SOC) //
						.output(AWAITING_HYSTERESIS, false) //
						.output("ess0", SET_ACTIVE_POWER_LESS_OR_EQUALS, 0)) //
				.deactivate();
	}
	
	@Test
	public void testEqualsMaxSocUsesHysteresis() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new DummyManagedSymmetricEss("ess0") //
						.withSoc(80) //
						.withActivePower(-1000) //
						.withCapacity(10_000) //
						.withAllowedChargePower(-10_000) //
						.withAllowedDischargePower(10_000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setMinSoc(15) //
						.setMaxSoc(90) //
						.setEnergyBetweenBalancingCycles(0) //
						.build()) //
				.next(new TestCase("Initialize NORMAL") //
						.input("ess0", SOC, 80) //
						.input("ess0", ACTIVE_POWER, 0) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Equal maxSoc waits for hysteresis") //
						.input("ess0", SOC, 90) //
						.input("ess0", ACTIVE_POWER, -1000) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase("After hysteresis") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 90) //
						.input("ess0", ACTIVE_POWER, -1000) //
						.output(AWAITING_HYSTERESIS, false)) //
				.deactivate();
	}

	/**
	 * Regression test for the constraint gap that used to occur when
	 * transitioning from MAX_SOC_REACHED to BALANCING_WANTED: before the fix,
	 * calculatedPower stayed null on a successful state change, so
	 * applyActivePowerConstraint() skipped setting any constraint for that
	 * cycle and charging above maxSoc was briefly unconstrained.
	 */
	@Test
	public void testMaxSocReachedToBalancingWantedKeepsBlockingCharge() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("timedata", new DummyTimedata("timedata0")) //
				.addReference("ess", new DummyManagedSymmetricEss("ess0") //
						.withSoc(80) //
						.withActivePower(-1000) //
						.withCapacity(10_000) //
						.withAllowedChargePower(-10_000) //
						.withAllowedDischargePower(10_000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setMinSoc(15) //
						.setMaxSoc(90) //
						.setForceChargePower(500) //
						.setEnergyBetweenBalancingCycles(1) // 1 kWh threshold
						.build()) //
				.next(new TestCase("Bootstrap NORMAL, CHARGED_ENERGY initialized from Timedata") //
						.input("ess0", SOC, 80) //
						.input("ess0", ACTIVE_POWER, -1000) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 1000L) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Baseline for energy delta tracking established") //
						.input("ess0", SOC, 80) //
						.input("ess0", ACTIVE_POWER, -1000) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 1000L) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Equal maxSoc waits for hysteresis") //
						.input("ess0", SOC, 90) //
						.input("ess0", ACTIVE_POWER, -1000) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 1000L) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase("After hysteresis: MAX_SOC_REACHED, charged energy exceeds threshold") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 90) //
						.input("ess0", ACTIVE_POWER, -1000) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 2600L) // delta 1600 Wh > 1000 Wh threshold
						.output(STATE_MACHINE, State.MAX_SOC_REACHED) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, 0) //
						.output(CHARGED_ENERGY, 1600)) //
				.next(new TestCase("Balancing wanted while still at maxSoc keeps blocking charge") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 90) //
						.input("ess0", ACTIVE_POWER, -1000) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 2600L) //
						.output(STATE_MACHINE, State.BALANCING_WANTED) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, 0)) //
				.deactivate();
	}

	/**
	 * Regression test for calculateChargedEnergy(): a decreasing lifetime ESS
	 * charge-energy counter (e.g. after a device restart) used to corrupt the
	 * balancing energy bookkeeping with a large negative delta. It must now be
	 * detected and the counter resynced without applying the delta.
	 */
	@Test
	public void testChargedEnergyCounterResetIsHandledGracefully() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("timedata", new DummyTimedata("timedata0")) //
				.addReference("ess", new DummyManagedSymmetricEss("ess0") //
						.withSoc(50) //
						.withActivePower(0) //
						.withCapacity(10_000) //
						.withAllowedChargePower(-10_000) //
						.withAllowedDischargePower(10_000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setMinSoc(15) //
						.setMaxSoc(90) //
						.setEnergyBetweenBalancingCycles(0) // disable balancing to keep the test focused
						.build()) //
				.next(new TestCase("Bootstrap: CHARGED_ENERGY initialized from Timedata") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 5000L) //
						.output(CHARGED_ENERGY, 0)) //
				.next(new TestCase("Baseline for energy delta tracking established") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 5000L) //
						.output(CHARGED_ENERGY, 0)) //
				.next(new TestCase("Normal accumulation") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 6000L) // delta +1000
						.output(CHARGED_ENERGY, 1000)) //
				.next(new TestCase("ESS counter drops (simulated device reset): no negative corruption") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 200L) //
						.output(CHARGED_ENERGY, 1000)) // unchanged, delta ignored
				.next(new TestCase("Accumulation continues correctly from the resynced baseline") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 500L) // delta +300 from resynced 200
						.output(CHARGED_ENERGY, 1300)) //
				.deactivate();
	}

	/**
	 * Verifies that BALANCING_DEFERRAL_REASON is exposed as PRICE_LIMIT when
	 * balancing is due but the current electricity price exceeds the configured
	 * maximum price, giving the UI a reason to show even while the controller
	 * itself stays in NORMAL (it only enters BALANCING_WANTED for a plain YES
	 * decision, not for YES_DEFERRED reached directly from NORMAL).
	 */
	@Test
	public void testBalancingDeferralReasonIsPriceLimit() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("timedata", new DummyTimedata("timedata0")) //
				.addReference("timeOfUseTariff", DummyTimeOfUseTariffProvider.fromHourlyPrices(clock, 200.0)) //
				.addReference("ess", new DummyManagedSymmetricEss("ess0") //
						.withSoc(50) //
						.withActivePower(0) //
						.withCapacity(10_000) //
						.withAllowedChargePower(-10_000) //
						.withAllowedDischargePower(10_000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setMinSoc(15) //
						.setMaxSoc(90) //
						.setEnergyBetweenBalancingCycles(1) // 1 kWh threshold
						.setMaxPrice(10) // ct/kWh; 200 EUR/MWh -> 20 ct/kWh exceeds this
						.build()) //
				.next(new TestCase("Bootstrap NORMAL, CHARGED_ENERGY initialized from Timedata") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 1000L) //
						.output(STATE_MACHINE, State.NORMAL) //
						.output(BALANCING_DEFERRAL_REASON, BalancingDeferralReason.NONE)) //
				.next(new TestCase("Baseline for energy delta tracking established") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 1000L) //
						.output(BALANCING_DEFERRAL_REASON, BalancingDeferralReason.NONE)) //
				.next(new TestCase("Charged energy is accumulated above the threshold") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 3000L) // delta 2000 Wh > 1000 Wh threshold
						.output(BALANCING_DEFERRAL_REASON, BalancingDeferralReason.NONE)) //
				.next(new TestCase("Balancing due, but price limit exceeded -> deferred with reason PRICE_LIMIT") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 3000L) //
						.output(STATE_MACHINE, State.NORMAL) //
						.output(BALANCING_DEFERRAL_REASON, BalancingDeferralReason.PRICE_LIMIT)) //
				.deactivate();
	}

	/**
	 * Regression test for isWithinPriceLimit(): TimeOfUsePrices.getFirst() can
	 * return null when no price data is available, which used to throw an
	 * uncaught NullPointerException while unboxing inside run(). It must now be
	 * handled gracefully.
	 */
	@Test
	public void testPriceLimitCheckWithEmptyPricesDoesNotThrow() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("timedata", new DummyTimedata("timedata0")) //
				.addReference("timeOfUseTariff", DummyTimeOfUseTariffProvider.empty(clock)) //
				.addReference("ess", new DummyManagedSymmetricEss("ess0") //
						.withSoc(50) //
						.withActivePower(0) //
						.withCapacity(10_000) //
						.withAllowedChargePower(-10_000) //
						.withAllowedDischargePower(10_000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setMinSoc(15) //
						.setMaxSoc(90) //
						.setEnergyBetweenBalancingCycles(1) // 1 kWh threshold
						.setMaxPrice(10) // enables the price-limit check
						.build()) //
				.next(new TestCase("Bootstrap NORMAL, CHARGED_ENERGY initialized from Timedata") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 1000L) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Baseline for energy delta tracking established") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 1000L) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Charged energy is accumulated above the threshold") //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 3000L) // delta 2000 Wh > 1000 Wh threshold
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Price check now runs against empty prices without throwing") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 50) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", ACTIVE_CHARGE_ENERGY, 3000L) //
						.output(STATE_MACHINE, State.BALANCING_WANTED)) //
				.deactivate();
	}

}
