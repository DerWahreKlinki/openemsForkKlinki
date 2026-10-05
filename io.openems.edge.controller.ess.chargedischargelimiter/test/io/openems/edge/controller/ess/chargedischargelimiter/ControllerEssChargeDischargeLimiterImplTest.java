package io.openems.edge.controller.ess.chargedischargelimiter;



import static  io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_LESS_OR_EQUALS;
import static  io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.STATE_MACHINE;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.AWAITING_HYSTERESIS;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.CHARGE_LIMITED;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.DISCHARGE_LIMITED;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.LIMITED_BATTERY_POWER;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.CHARGED_ENERGY;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.BALANCING_DEFERRAL_REASON;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.ACTIVE_POWER;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.SOC;
import static io.openems.edge.ess.api.HybridEss.ChannelId.DC_DISCHARGE_POWER;

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
import io.openems.edge.ess.test.DummyHybridEss;
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

	/**
	 * DC-coupled hybrid (AC = battery + PV): "do not charge" has to be expressed as
	 * AC >= PV, otherwise the battery keeps charging from PV above maxSoc.
	 */
	@Test
	public void testHybridAboveMaxSocBlocksPvCharging() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new DummyHybridEss("ess0") //
						.withSoc(80) //
						.withActivePower(0) //
						.withDcDischargePower(0) //
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
						.input("ess0", DC_DISCHARGE_POWER, 0) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Above maxSoc, PV 3000 W, battery charging 1000 W") //
						.input("ess0", SOC, 91) //
						.input("ess0", ACTIVE_POWER, 2000) //
						.input("ess0", DC_DISCHARGE_POWER, -1000) //
						.output(STATE_MACHINE, State.ABOVE_MAX_SOC) //
						.output(CHARGE_LIMITED, true) //
						.output(DISCHARGE_LIMITED, false) //
						.output(LIMITED_BATTERY_POWER, 0) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, 3000)) //
				// Staying above the limit asks for a small discharge on top of PV, so
				// the SoC comes back down instead of drifting further up on the
				// inverter's charge bias (see RECOVER_DISCHARGE_W)
				.next(new TestCase("Still above maxSoc: PV + recovery discharge") //
						.input("ess0", SOC, 91) //
						.input("ess0", ACTIVE_POWER, 2000) //
						.input("ess0", DC_DISCHARGE_POWER, -1000) //
						.output(STATE_MACHINE, State.ABOVE_MAX_SOC) //
						.output(LIMITED_BATTERY_POWER, ControllerEssChargeDischargeLimiterImpl.RECOVER_DISCHARGE_W) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS,
								3000 + ControllerEssChargeDischargeLimiterImpl.RECOVER_DISCHARGE_W)) //
				.next(new TestCase("Back at maxSoc: charging blocked again, no discharge asked for") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 90) //
						.input("ess0", ACTIVE_POWER, 2000) //
						.input("ess0", DC_DISCHARGE_POWER, -1000) //
						.output(STATE_MACHINE, State.MAX_SOC_REACHED) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, 3000)) //
				.deactivate();
	}

	/**
	 * Positive AC power of a hybrid is not "discharging" while the battery charges
	 * from PV: no min-SoC taper (which used to cap the AC output and force grid
	 * import). Charging from PV close to maxSoc is recognised as charging.
	 */
	@Test
	public void testHybridDirectionFromBatteryPower() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new DummyHybridEss("ess0") //
						.withSoc(50) //
						.withActivePower(0) //
						.withDcDischargePower(0) //
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
						.input("ess0", DC_DISCHARGE_POWER, 0) //
						.output(STATE_MACHINE, State.NORMAL)) //
				// The zone is entered by SoC alone, no matter which way the battery
				// runs: the constraint only limits discharge, so it does not get in
				// the way while charging - and a direction-dependent entry made the
				// state bounce on noise (see hybridTaperStateSurvivesBatteryNoise).
				.next(new TestCase("Just above minSoc, PV 3000 W exported, battery charging 500 W") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 17) //
						.input("ess0", ACTIVE_POWER, 2500) //
						.input("ess0", DC_DISCHARGE_POWER, -500) //
						.output(STATE_MACHINE, State.APPROACHING_MIN_SOC)) //
				// SoC jumps to the other end: first out of the min zone, then into the
				// max zone one cycle later (each transition costs the hysteresis)
				.next(new TestCase("SoC jumps to 88 %: leaves the min zone") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 88) //
						.input("ess0", ACTIVE_POWER, 2500) //
						.input("ess0", DC_DISCHARGE_POWER, -500) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Just below maxSoc: enters the max zone") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 88) //
						.input("ess0", ACTIVE_POWER, 2500) //
						.input("ess0", DC_DISCHARGE_POWER, -500) //
						.output(STATE_MACHINE, State.APPROACHING_MAX_SOC)) //
				.deactivate();
	}

	/**
	 * The taper state is not left on BMS noise around 0 W: with the taper
	 * constraint the battery may sit at a few watts, which used to bounce the
	 * state to NORMAL (no constraint for the hysteresis time) and back.
	 */
	@Test
	public void hybridTaperStateSurvivesBatteryNoise() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new DummyHybridEss("ess0") //
						.withSoc(50) //
						.withActivePower(0) //
						.withDcDischargePower(0) //
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
						.input("ess0", DC_DISCHARGE_POWER, 0) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Charging from PV at 88 %: taper") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 88) //
						.input("ess0", ACTIVE_POWER, 2500) //
						.input("ess0", DC_DISCHARGE_POWER, -500) //
						.output(STATE_MACHINE, State.APPROACHING_MAX_SOC)) //
				.next(new TestCase("Battery at +40 W (noise): stays in the taper state") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 88) //
						.input("ess0", ACTIVE_POWER, 3040) //
						.input("ess0", DC_DISCHARGE_POWER, 40) //
						.output(STATE_MACHINE, State.APPROACHING_MAX_SOC)) //
				// The state is not left on the battery direction at all: a sustained
				// discharge inside the zone keeps it, because the constraint limits
				// charging only. Live 2026-09-26/28: with a direction-based exit the
				// state dropped to NORMAL on every load peak and came back on the
				// next charging cycle, several times per hour.
				.next(new TestCase("Discharging once: stays in the taper state") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 88) //
						.input("ess0", ACTIVE_POWER, 3300) //
						.input("ess0", DC_DISCHARGE_POWER, 300) //
						.output(STATE_MACHINE, State.APPROACHING_MAX_SOC)) //
				.next(new TestCase("Sustained discharge: still in the taper state") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 88) //
						.input("ess0", ACTIVE_POWER, 3300) //
						.input("ess0", DC_DISCHARGE_POWER, 300) //
						.output(STATE_MACHINE, State.APPROACHING_MAX_SOC)) //
				.next(new TestCase("SoC leaves the zone: back to NORMAL") //
						.timeleap(clock, 11, ChronoUnit.SECONDS) //
						.input("ess0", SOC, 86) //
						.input("ess0", ACTIVE_POWER, 3300) //
						.input("ess0", DC_DISCHARGE_POWER, 300) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.deactivate();
	}

	/**
	 * Below minSoc the slow-charge constraint is shifted by PV as well: AC <= PV +
	 * slowChargePower, with slowChargePower derived from the battery-side limit
	 * (AllowedChargePower - PV) / 20.
	 */
	@Test
	public void testHybridBelowMinSocShiftsConstraintByPv() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new DummyHybridEss("ess0") //
						.withSoc(50) //
						.withActivePower(0) //
						.withDcDischargePower(0) //
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
						.input("ess0", DC_DISCHARGE_POWER, 0) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Below minSoc with PV 3000 W: block discharging (battery <= 0)") //
						.input("ess0", SOC, 14) //
						.input("ess0", ACTIVE_POWER, 3000) //
						.input("ess0", DC_DISCHARGE_POWER, 0) //
						.output(STATE_MACHINE, State.BELOW_MIN_SOC) //
						.output("ess0", SET_ACTIVE_POWER_LESS_OR_EQUALS, 3000)) //
				.next(new TestCase("Still below minSoc: slow charge on top of PV") //
						.input("ess0", SOC, 14) //
						.input("ess0", ACTIVE_POWER, 3000) //
						.input("ess0", DC_DISCHARGE_POWER, 0) //
						.output(STATE_MACHINE, State.BELOW_MIN_SOC) //
						// slowChargePower = (-10000 - 3000) / 20 = -650 -> AC <= 3000 - 650
						.output("ess0", SET_ACTIVE_POWER_LESS_OR_EQUALS, 2350)) //
				.deactivate();
	}


	/**
	 * DC-coupled hybrid whose set-point is battery power (SolarEdge in
	 * DC_SETPOINT): "do not charge" is battery >= 0, PV must not be added.
	 * Otherwise the battery discharges with the PV power (observed live on
	 * 2026-10-03: 4.2 kW discharge instead of 50 W).
	 */
	@Test
	public void testHybridBatterySetPointAboveMaxSoc() throws Exception {
		final var clock = createDummyClock();

		new ControllerTest(new ControllerEssChargeDischargeLimiterImpl()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("ess", new DummyHybridEss("ess0") //
						.withSoc(80) //
						.withActivePower(0) //
						.withDcDischargePower(0) //
						.withCapacity(10_000) //
						.withAllowedChargePower(-10_000) //
						.withAllowedDischargePower(10_000)) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setEssId("ess0") //
						.setMinSoc(15) //
						.setMaxSoc(90) //
						.setEnergyBetweenBalancingCycles(0) //
						.setSetPointSemantics(SetPointSemantics.BATTERY_ONLY) //
						.build()) //
				.next(new TestCase("Initialize NORMAL") //
						.input("ess0", SOC, 80) //
						.input("ess0", ACTIVE_POWER, 0) //
						.input("ess0", DC_DISCHARGE_POWER, 0) //
						.output(STATE_MACHINE, State.NORMAL)) //
				.next(new TestCase("Above maxSoc, PV 3000 W, battery charging 1000 W") //
						.input("ess0", SOC, 91) //
						.input("ess0", ACTIVE_POWER, 2000) //
						.input("ess0", DC_DISCHARGE_POWER, -1000) //
						.output(STATE_MACHINE, State.ABOVE_MAX_SOC) //
						.output(CHARGE_LIMITED, true) //
						.output(LIMITED_BATTERY_POWER, 0) //
						// battery power, not AC: no PV added
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS, 0)) //
				.next(new TestCase("Still above maxSoc: small recovery discharge only") //
						.input("ess0", SOC, 91) //
						.input("ess0", ACTIVE_POWER, 2000) //
						.input("ess0", DC_DISCHARGE_POWER, -1000) //
						.output(STATE_MACHINE, State.ABOVE_MAX_SOC) //
						.output("ess0", SET_ACTIVE_POWER_GREATER_OR_EQUALS,
								ControllerEssChargeDischargeLimiterImpl.RECOVER_DISCHARGE_W)) //
				.deactivate();
	}
}
