package io.openems.edge.controller.ess.chargedischargelimiter;



import static  io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_LESS_OR_EQUALS;
import static  io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.STATE_MACHINE;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.AWAITING_HYSTERESIS;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.ACTIVE_POWER;
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

import io.openems.edge.ess.test.DummyManagedSymmetricEss;

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


	
}
