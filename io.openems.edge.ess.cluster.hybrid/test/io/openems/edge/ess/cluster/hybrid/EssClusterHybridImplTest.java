package io.openems.edge.ess.cluster.hybrid;

import static io.openems.edge.common.startstop.StartStoppable.ChannelId.START_STOP;
import static io.openems.edge.ess.api.AsymmetricEss.ChannelId.ACTIVE_POWER_L1;
import static io.openems.edge.ess.api.HybridEss.ChannelId.DC_CHARGE_ENERGY;
import static io.openems.edge.ess.api.HybridEss.ChannelId.DC_DISCHARGE_ENERGY;
import static io.openems.edge.ess.api.HybridEss.ChannelId.DC_DISCHARGE_POWER;
import static io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER;
import static io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.ACTIVE_POWER;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.GRID_MODE;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.REACTIVE_POWER;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.SOC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStopConfig;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.MetaEss;
import io.openems.edge.ess.test.DummyManagedAsymmetricEss;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;
import io.openems.edge.ess.test.DummyPower;

public class EssClusterHybridImplTest {

	@Test
	public void testCluster() throws Exception {
		final var ess = new EssClusterHybridImpl();
		new ComponentTest(ess) //
				.addReference("power", new DummyPower()) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess1")) //
				.addReference("addEss", new DummyManagedAsymmetricEss("ess2")) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setEssIds("ess1", "ess2") //
						.setStartStop(StartStopConfig.START) //
						.build())
				.next(new TestCase() //
						.input("ess1", GRID_MODE, GridMode.ON_GRID) //
						.input("ess2", GRID_MODE, GridMode.ON_GRID) //
						.output(GRID_MODE, GridMode.ON_GRID) //
						.input("ess1", ACTIVE_POWER, 1234) //
						.input("ess2", ACTIVE_POWER, 9876) //
						.output(ACTIVE_POWER, 11110) //
						.input("ess1", REACTIVE_POWER, 1111) //
						.input("ess2", REACTIVE_POWER, 2222) //
						.output(REACTIVE_POWER, 3333) //
						.input("ess1", ACTIVE_CHARGE_ENERGY, 1) //
						.input("ess2", ACTIVE_CHARGE_ENERGY, 2) //
						.output(ACTIVE_CHARGE_ENERGY, 3L) //
						.input("ess2", ACTIVE_POWER_L1, 1111) //
						.output(ACTIVE_POWER_L1, 1234 / 3 + 1111) //
						.input("ess1", ALLOWED_CHARGE_POWER, 11) //
						.input("ess2", ALLOWED_CHARGE_POWER, 22) //
						.output(ALLOWED_CHARGE_POWER, 33) //
						.input("ess1", ALLOWED_DISCHARGE_POWER, 10) //
						.input("ess2", ALLOWED_DISCHARGE_POWER, 20) //
						.output(ALLOWED_DISCHARGE_POWER, 30) //
				);

		assertEquals(1, ess.getPowerPrecision());
		ess.getPowerPrecision();
	}

	@Test
	public void testGridMode() throws Exception {
		new ComponentTest(new EssClusterHybridImpl()) //
				.addReference("power", new DummyPower()) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess1")) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess2")) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess3")) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setEssIds("ess1", "ess2", "ess3") //
						.setStartStop(StartStopConfig.START) //
						.build())
				.next(new TestCase() //
						.input("ess1", GRID_MODE, GridMode.ON_GRID) //
						.input("ess2", GRID_MODE, GridMode.ON_GRID) //
						.input("ess3", GRID_MODE, GridMode.ON_GRID) //
						.output(GRID_MODE, GridMode.ON_GRID) //
				) //
				.next(new TestCase() //
						.input("ess1", GRID_MODE, GridMode.OFF_GRID) //
						.input("ess2", GRID_MODE, GridMode.OFF_GRID) //
						.input("ess3", GRID_MODE, GridMode.OFF_GRID) //
						.output(GRID_MODE, GridMode.OFF_GRID) //
				) //
				.next(new TestCase() //
						.input("ess1", GRID_MODE, GridMode.OFF_GRID) //
						.input("ess2", GRID_MODE, GridMode.OFF_GRID) //
						.input("ess3", GRID_MODE, GridMode.UNDEFINED) //
						.output(GRID_MODE, GridMode.UNDEFINED) //
				) //
		;
	}

	@Test
	public void testSoc() throws Exception {
		new ComponentTest(new EssClusterHybridImpl()) //
				.addReference("power", new DummyPower()) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess1").withCapacity(50000)) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess2").withCapacity(3000)) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setEssIds("ess1", "ess2") //
						.setStartStop(StartStopConfig.START) //
						.build())
				.next(new TestCase() //
						.input("ess1", SOC, 20) //
						.input("ess2", SOC, 90) //
						.output(SOC, 24) //
				) //
				.next(new TestCase() //
						.input("ess1", SOC, 21) //
						.output(SOC, 25) //
				) //
				.next(new TestCase() //
						.input("ess1", SOC, 100) //
						.output(SOC, 99) //
				) //
		;
	}

	@Test
	public void testStartStop() throws Exception {
		new ComponentTest(new EssClusterHybridImpl()) //
				.addReference("power", new DummyPower()) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess1")) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess2")) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setEssIds("ess1", "ess2") //
						.setStartStop(StartStopConfig.START) //
						.build())
				.next(new TestCase() //
						.input("ess1", START_STOP, StartStop.UNDEFINED) //
						.input("ess2", START_STOP, StartStop.STOP) //
						.output(START_STOP, StartStop.UNDEFINED)) //
				.next(new TestCase() //
						.input("ess1", START_STOP, StartStop.STOP) //
						.input("ess2", START_STOP, StartStop.STOP) //
						.output(START_STOP, StartStop.STOP)) //
				.next(new TestCase() //
						.input("ess1", START_STOP, StartStop.START) //
						.input("ess2", START_STOP, StartStop.STOP) //
						.output(START_STOP, StartStop.UNDEFINED)) //
				.next(new TestCase() //
						.input("ess1", START_STOP, StartStop.START) //
						.input("ess2", START_STOP, StartStop.START) //
						.output(START_STOP, StartStop.START)) //

		;
	}

	@Test
	public void testCalculateMinPowerPrecision() {
		assertEquals(1, EssClusterHybridImpl.calculateMinPowerPrecision(List.of(//
				new DummyManagedSymmetricEss("ess0") //
						.withPowerPrecision(0), //
				new DummyManagedSymmetricEss("ess1") //
						.withPowerPrecision(100) //
		)));
		assertEquals(50, EssClusterHybridImpl.calculateMinPowerPrecision(List.of(//
				new DummyManagedSymmetricEss("ess0") //
						.withPowerPrecision(50), //
				new DummyManagedSymmetricEss("ess1") //
						.withPowerPrecision(100) //
		)));
		assertEquals(1, EssClusterHybridImpl.calculateMinPowerPrecision(List.of()));
	}

	@Test
	public void testIsMetaEssButNoHybridEss() {
		final var ess = new EssClusterHybridImpl();
		assertTrue(ess instanceof MetaEss);
		assertFalse(HybridEss.class.isInstance(ess));
	}

	@Test
	public void testHybridAndAcCoupledEss() throws Exception {
		new ComponentTest(new EssClusterHybridImpl()) //
				.addReference("power", new DummyPower()) //
				.addReference("addEss", new DummyStartStoppableHybridEss("ess1")) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess2")) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setEssIds("ess1", "ess2") //
						.setStartStop(StartStopConfig.START) //
						.build())
				// Hybrid ESS: 6000 W PV, battery charges with 1000 W
				// AC-coupled ESS: battery charges with 2000 W
				.next(new TestCase() //
						.input("ess1", ACTIVE_POWER, 5000) //
						.input("ess1", DC_DISCHARGE_POWER, -1000) //
						.input("ess2", ACTIVE_POWER, -2000) //
						.output(ACTIVE_POWER, -3000) //
						.output(ACTIVE_POWER_L1, -1000 / 3 + -2000 / 3) //
						.input("ess1", ACTIVE_CHARGE_ENERGY, 10) //
						.input("ess1", DC_CHARGE_ENERGY, 100) //
						.input("ess2", ACTIVE_CHARGE_ENERGY, 20) //
						.output(ACTIVE_CHARGE_ENERGY, 120L) //
						.input("ess1", ACTIVE_DISCHARGE_ENERGY, 1000) //
						.input("ess1", DC_DISCHARGE_ENERGY, 300) //
						.input("ess2", ACTIVE_DISCHARGE_ENERGY, 200) //
						.output(ACTIVE_DISCHARGE_ENERGY, 500L) //
						.input("ess1", ALLOWED_CHARGE_POWER, -4000) //
						.input("ess2", ALLOWED_CHARGE_POWER, -4000) //
						.output(ALLOWED_CHARGE_POWER, -8000)) //
				// PV changes, batteries keep their power: Cluster power does not change
				.next(new TestCase() //
						.input("ess1", ACTIVE_POWER, 8000) //
						.output(ACTIVE_POWER, -3000)) //
				// PV is gone, both batteries discharge
				.next(new TestCase() //
						.input("ess1", ACTIVE_POWER, 1500) //
						.input("ess1", DC_DISCHARGE_POWER, 1500) //
						.input("ess2", ACTIVE_POWER, 500) //
						.output(ACTIVE_POWER, 2000)) //
				// Battery power of the Hybrid ESS is not available
				.next(new TestCase() //
						.input("ess1", DC_DISCHARGE_POWER, null) //
						.output(ACTIVE_POWER, 500)) //
		;
	}

	@Test
	public void testOnlyAcCoupledEss() throws Exception {
		new ComponentTest(new EssClusterHybridImpl()) //
				.addReference("power", new DummyPower()) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess1")) //
				.addReference("addEss", new DummyManagedSymmetricEss("ess2")) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setEssIds("ess1", "ess2") //
						.setStartStop(StartStopConfig.START) //
						.build())
				.next(new TestCase() //
						.input("ess1", ACTIVE_POWER, 1000) //
						.input("ess2", ACTIVE_POWER, -400) //
						.output(ACTIVE_POWER, 600)) //
		;
	}
}
