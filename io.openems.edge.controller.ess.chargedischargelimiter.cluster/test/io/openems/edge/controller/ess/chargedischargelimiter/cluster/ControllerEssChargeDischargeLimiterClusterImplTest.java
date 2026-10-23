package io.openems.edge.controller.ess.chargedischargelimiter.cluster;

import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.CHARGE_POWER_ALLOWED;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.CURRENT_ENERGY;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.DISCHARGE_POWER_ALLOWED;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.MAX_ENERGY;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.MIN_ENERGY;
import static io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter.ChannelId.STATE_MACHINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.controller.ess.chargedischargelimiter.enums.State;
import io.openems.edge.controller.test.ControllerTest;

public class ControllerEssChargeDischargeLimiterClusterImplTest {

	private static final String CLUSTER = "chargeDischargeLimiterCluster0";

	@Test
	public void sumsTheLimitersOfTheMembersTest() throws Exception {
		final var sut = new ControllerEssChargeDischargeLimiterClusterImpl();
		final var cluster = new DummyEssCluster("essCluster", "ess0", "ess1");
		final var limiter0 = new DummyChargeDischargeLimiter("chargeDischargeLimiter0", "ess0");
		final var limiter1 = new DummyChargeDischargeLimiter("chargeDischargeLimiter1", "ess1");
		new ControllerTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager() //
						.addComponent(cluster).addComponent(limiter0).addComponent(limiter1)) //
				.addReference("ess", cluster) //
				.addComponent(limiter0) //
				.addComponent(limiter1) //
				.activate(MyConfig.create() //
						.setId(CLUSTER) //
						.setEssId("essCluster") //
						.build()) //
				.next(new TestCase("both members normal") //
						.input("chargeDischargeLimiter0", STATE_MACHINE, State.NORMAL) //
						.input("chargeDischargeLimiter0", MIN_ENERGY, 1751) //
						.input("chargeDischargeLimiter0", MAX_ENERGY, 7880) //
						.input("chargeDischargeLimiter0", CURRENT_ENERGY, 4903) //
						.input("chargeDischargeLimiter0", CHARGE_POWER_ALLOWED, 4000) //
						.input("chargeDischargeLimiter0", DISCHARGE_POWER_ALLOWED, 4500) //
						.input("chargeDischargeLimiter1", STATE_MACHINE, State.NORMAL) //
						.input("chargeDischargeLimiter1", MIN_ENERGY, 4810) //
						.input("chargeDischargeLimiter1", MAX_ENERGY, 21643) //
						.input("chargeDischargeLimiter1", CURRENT_ENERGY, 12024) //
						.input("chargeDischargeLimiter1", CHARGE_POWER_ALLOWED, 4000) //
						.input("chargeDischargeLimiter1", DISCHARGE_POWER_ALLOWED, 4000) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.STATE_MACHINE,
								io.openems.edge.controller.ess.chargedischargelimiter.cluster.State.NORMAL) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.MEMBERS, 2) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.MIN_ENERGY, 6561) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.MAX_ENERGY, 29523) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.CURRENT_ENERGY, 16927) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.CHARGE_POWER_ALLOWED, 8000) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.DISCHARGE_POWER_ALLOWED, 8500) //
						// (16927 - 6561) / (29523 - 6561)
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.NET_SOC, 45) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.TRIGGER, "")) //
				.next(new TestCase("a member approaches its minimum: limited, its discharge power counts reduced") //
						.input("chargeDischargeLimiter1", STATE_MACHINE, State.APPROACHING_MIN_SOC) //
						.input("chargeDischargeLimiter1", DISCHARGE_POWER_ALLOWED, 200) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.STATE_MACHINE,
								io.openems.edge.controller.ess.chargedischargelimiter.cluster.State.MEMBER_LIMITED) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.TRIGGER,
								"ess1: SoC approaching minimum limit. Decreasing power") //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.DISCHARGE_POWER_ALLOWED, 4700)) //
				.next(new TestCase("a member balances: balancing wins over limiting") //
						.input("chargeDischargeLimiter0", STATE_MACHINE, State.FORCE_CHARGE_ACTIVE) //
						.input("chargeDischargeLimiter0", DISCHARGE_POWER_ALLOWED, 0) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.STATE_MACHINE,
								io.openems.edge.controller.ess.chargedischargelimiter.cluster.State.MEMBER_BALANCING) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.TRIGGER, "ess0: Force-Charge-to-SoC") //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.DISCHARGE_POWER_ALLOWED, 200)) //
				.next(new TestCase("a member in error: inherited, its power does not count") //
						.input("chargeDischargeLimiter0", STATE_MACHINE, State.ERROR) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.STATE_MACHINE,
								io.openems.edge.controller.ess.chargedischargelimiter.cluster.State.MEMBER_ERROR) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.TRIGGER, "ess0: Error State") //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.CHARGE_POWER_ALLOWED, 4000) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.MIN_ENERGY, 6561)) //
				.next(new TestCase("a member restarts, its window is unknown for a moment: the last report stands in") //
						.input("chargeDischargeLimiter0", STATE_MACHINE, State.NORMAL) //
						.input("chargeDischargeLimiter0", MIN_ENERGY, null) //
						.input("chargeDischargeLimiter0", MAX_ENERGY, null) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.MIN_ENERGY, 6561) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.MAX_ENERGY, 29523) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.CHARGE_POWER_ALLOWED, 4000)) //
				.deactivate();
		final var report = sut.report();
		assertNotNull(report);
		assertEquals(6561, report.minEnergy());
		assertEquals(4000, report.maxChargePower());
	}

	@Test
	public void reportsNothingWithoutMembersOrWhenSwitchedOffTest() throws Exception {
		final var sut = new ControllerEssChargeDischargeLimiterClusterImpl();
		final var cluster = new DummyEssCluster("essCluster", "ess0");
		final var limiter0 = new DummyChargeDischargeLimiter("chargeDischargeLimiter0", "ess0");
		new ControllerTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager() //
						.addComponent(cluster).addComponent(limiter0)) //
				.addReference("ess", cluster) //
				.addComponent(limiter0) //
				.activate(MyConfig.create() //
						.setId(CLUSTER) //
						.setEssId("essCluster") //
						.setReportToEnergyScheduler(false) //
						.build()) //
				.next(new TestCase("the window of the member is not known yet") //
						.input("chargeDischargeLimiter0", STATE_MACHINE, State.NORMAL) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.MEMBERS, 1) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.MIN_ENERGY, null)) //
				.next(new TestCase("known, but switched off") //
						.input("chargeDischargeLimiter0", MIN_ENERGY, 1751) //
						.input("chargeDischargeLimiter0", MAX_ENERGY, 7880) //
						.output(ControllerEssChargeDischargeLimiterCluster.ChannelId.MIN_ENERGY, 1751)) //
				.deactivate();
		assertNull(sut.report(), "switched off: nothing for the optimizer");
	}
}
