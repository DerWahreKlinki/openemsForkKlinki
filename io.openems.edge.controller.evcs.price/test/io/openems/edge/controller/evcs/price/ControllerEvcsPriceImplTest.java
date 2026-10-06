package io.openems.edge.controller.evcs.price;

import static io.openems.common.test.TestUtils.createDummyClock;
import static io.openems.edge.common.sum.Sum.ChannelId.ESS_CAPACITY;
import static io.openems.edge.common.sum.Sum.ChannelId.ESS_DISCHARGE_POWER;
import static io.openems.edge.common.sum.Sum.ChannelId.ESS_MAX_DISCHARGE_POWER;
import static io.openems.edge.common.sum.Sum.ChannelId.ESS_SOC;
import static io.openems.edge.common.sum.Sum.ChannelId.GRID_ACTIVE_POWER;
import static io.openems.edge.common.sum.Sum.ChannelId.GRID_BUY_PRICE;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.AWAITING_HYSTERESIS;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.BLENDED_PRICE;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.EXPECTED_SURPLUS_ENERGY;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_PRICE;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_ENERGY_TO_TARGET;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_TARGET_REACHABLE;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.PRICE_CHARGING;
import static io.openems.edge.controller.evcs.price.Priority.CAR;
import static io.openems.edge.evcs.api.ChargeMode.EXCESS_POWER;
import static io.openems.edge.evcs.api.ChargeMode.FORCE_CHARGE;
import static io.openems.edge.evcs.api.Evcs.ChannelId.MAXIMUM_HARDWARE_POWER;
import static io.openems.edge.evcs.api.Evcs.ChannelId.MAXIMUM_POWER;
import static io.openems.edge.evcs.api.Evcs.ChannelId.MINIMUM_HARDWARE_POWER;
import static io.openems.edge.evcs.api.Evcs.ChannelId.STATUS;
import static io.openems.edge.evcs.api.ManagedEvcs.ChannelId.CHARGE_STATE;
import static io.openems.edge.evcs.api.ManagedEvcs.ChannelId.IS_CLUSTERED;
import static io.openems.edge.evcs.api.ManagedEvcs.ChannelId.SET_CHARGE_POWER_LIMIT;
import static io.openems.edge.evcs.api.ManagedEvcs.ChannelId.SET_CHARGE_POWER_REQUEST;
import static io.openems.edge.meter.api.ElectricityMeter.ChannelId.ACTIVE_POWER;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.test.TimeLeapClock;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.common.sum.DummySum;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.evcs.api.ChargeMode;
import io.openems.edge.evcs.api.ChargeState;
import io.openems.edge.evcs.api.Evcs;
import io.openems.edge.evcs.api.Status;
import io.openems.edge.predictor.api.prediction.Prediction;
import io.openems.edge.predictor.api.test.DummyPredictor;
import io.openems.edge.predictor.api.test.DummyPredictorManager;
import io.openems.edge.evcs.test.DummyManagedEvcs;

public class ControllerEvcsPriceImplTest {

	private static final int DEFAULT_FORCE_CHARGE_MIN_POWER = 7360;
	private static final int DEFAULT_CHARGE_MIN_POWER = 0;

	@Test
	public void excessChargeTest1() throws Exception {
		new ControllerTest(new ControllerEvcsPriceImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(DEFAULT_FORCE_CHARGE_MIN_POWER) //
						.setDefaultChargeMinPower(DEFAULT_CHARGE_MIN_POWER) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.build()) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, -6000) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //
				.deactivate();
	}

	@Test
	public void excessChargeTest2() throws Exception {
		new ControllerTest(new ControllerEvcsPriceImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(DEFAULT_FORCE_CHARGE_MIN_POWER) //
						.setDefaultChargeMinPower(DEFAULT_CHARGE_MIN_POWER) //
						.setPriority(Priority.STORAGE) //
						.setEnergySessionLimit(0) //
						.build()) //
				.next(new TestCase() //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, -5000) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -40000) //
						.input("evcs0", ACTIVE_POWER, 5000) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 22080) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 44800)) //
				.deactivate();
	}

	@Test
	public void forceChargeTest() throws Exception {
		new ControllerTest(new ControllerEvcsPriceImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(FORCE_CHARGE) //
						.setForceChargeMinPower(DEFAULT_FORCE_CHARGE_MIN_POWER) //
						.setDefaultChargeMinPower(DEFAULT_CHARGE_MIN_POWER) //
						.setPriority(CAR) // s
						.setEnergySessionLimit(0) //
						.build()) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, -5000) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -40000) //
						.input("evcs0", ACTIVE_POWER, 5000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 22080)) //
				.deactivate();
	}

	@Test
	public void chargingDisabledTest() throws Exception {
		new ControllerTest(new ControllerEvcsPriceImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(false) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(DEFAULT_FORCE_CHARGE_MIN_POWER) //
						.setDefaultChargeMinPower(DEFAULT_CHARGE_MIN_POWER) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.build()) //
				.next(new TestCase() //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.deactivate();
	}

	@Test
	public void wrongConfigParametersTest() throws Exception {
		var cm = new DummyConfigurationAdmin();
		new ControllerTest(new ControllerEvcsPriceImpl()) //
				.addReference("cm", cm) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(30_000) //
						.setDefaultChargeMinPower(30_000) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.build()) //
				.next(new TestCase() //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 12000)) //
				.deactivate();

		assertEquals(12000,
				(int) (Integer) cm.getConfiguration("ctrlEvcs0").getProperties().get("defaultChargeMinPower"));
	}

	@Test
	public void clusterTest() throws Exception {
		final var clock = createDummyClock();
		new ControllerTest(new ControllerEvcsPriceImpl(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(3_333) //
						.setDefaultChargeMinPower(DEFAULT_CHARGE_MIN_POWER) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.build()) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, -10000) //
						.input("evcs0", IS_CLUSTERED, true) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.output("evcs0", SET_CHARGE_POWER_REQUEST, 10000))
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, -6000) //
						.input("evcs0", IS_CLUSTERED, true) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", STATUS, Status.NOT_READY_FOR_CHARGING) //
						.output("evcs0", SET_CHARGE_POWER_REQUEST, 6000)) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, -6000) //
						.input("evcs0", IS_CLUSTERED, true) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", STATUS, null) //
						.output("evcs0", SET_CHARGE_POWER_REQUEST, 0)) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, -6000) //
						.input("evcs0", IS_CLUSTERED, true) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", STATUS, Status.CHARGING_REJECTED) //
						.output("evcs0", SET_CHARGE_POWER_REQUEST, 6000) //
						.output("evcs0", MAXIMUM_POWER, null)) //
				.deactivate();
	}

	@Test
	public void clusterTestDisabledCharging() throws Exception {
		new ControllerTest(new ControllerEvcsPriceImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(false) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(3_333) //
						.setDefaultChargeMinPower(DEFAULT_CHARGE_MIN_POWER) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.build()) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, -10000) //
						.input("evcs0", IS_CLUSTERED, true) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0))
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, -6000) //
						.input("evcs0", IS_CLUSTERED, true) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", STATUS, Status.NOT_READY_FOR_CHARGING) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, -6000) //
						.input("evcs0", IS_CLUSTERED, true) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", STATUS, null) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, -6000) //
						.input("evcs0", IS_CLUSTERED, true) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", STATUS, Status.CHARGING_REJECTED) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output("evcs0", MAXIMUM_POWER, null)) //
				.deactivate();
	}

	@Test
	public void hysteresisTest() throws Exception {
		final var clock = createDummyClock();
		new ControllerTest(new ControllerEvcsPriceImpl(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(DEFAULT_FORCE_CHARGE_MIN_POWER) //
						.setDefaultChargeMinPower(DEFAULT_CHARGE_MIN_POWER) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.setExcessChargeHystersis(120) //
						.setExcessChargePauseHysteresis(30) //
						.build()) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -6_000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6_000)) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, -200) //
						.input("evcs0", ACTIVE_POWER, 5800) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6_000)) //
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, 500) //
						.input("evcs0", ACTIVE_POWER, 5800) //
						.input("evcs0", Evcs.ChannelId.MINIMUM_HARDWARE_POWER, Evcs.DEFAULT_MINIMUM_HARDWARE_POWER)
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 5300))

				// Active hysteresis
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 5000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 5_300) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase() //
						.timeleap(clock, 6, MINUTES)) //

				// Passed hysteresis
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 5000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, false)) //

				// Active hysteresis
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, -5000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, true))

				.next(new TestCase() //
						.timeleap(clock, 1, MINUTES)) //

				// New charge process starting after another 30 seconds
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, -5000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 5000) //
						.output(AWAITING_HYSTERESIS, false)) //
				.deactivate();
	}

	@Test
	public void awaitDecreasingChargeStateTest() throws Exception {
		final var clock = createDummyClock();
		new ControllerTest(new ControllerEvcsPriceImpl(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(DEFAULT_FORCE_CHARGE_MIN_POWER) //
						.setDefaultChargeMinPower(DEFAULT_CHARGE_MIN_POWER) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.build()) //

				// First cycle: establish a charge power of 6000 W
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //

				// DECREASING state: controller should hold the last charge power
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, 2000) //
						.input("evcs0", ACTIVE_POWER, 6000) //
						.input("evcs0", CHARGE_STATE, ChargeState.DECREASING) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //

				// INCREASING state: controller should also hold the last charge power
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, -10000) //
						.input("evcs0", ACTIVE_POWER, 6000) //
						.input("evcs0", CHARGE_STATE, ChargeState.INCREASING) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //
				.deactivate();
	}

	private static ControllerTest preparePriceTest(ChargeMode chargeMode, double priceLimit) throws Exception {
		return new ControllerTest(new ControllerEvcsPriceImpl(createDummyClock())) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(chargeMode) //
						.setForceChargeMinPower(3680) //
						.setDefaultChargeMinPower(DEFAULT_CHARGE_MIN_POWER) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.setPriceLimit(priceLimit) //
						.setPriceLimitFullPower(20) //
						.setPriceChargePower(11040) //
						.build());
	}

	@Test
	public void priceBelowLimitWithoutExcessPowerTest() throws Exception {
		preparePriceTest(EXCESS_POWER, 30) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 150.) // 15 Cent/kWh
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 300) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) //
						.output(PRICE_CHARGING, true)) //
				.deactivate();
	}

	@Test
	public void negativePriceTest() throws Exception {
		preparePriceTest(EXCESS_POWER, 30) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, -50.) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 300) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) //
						.output(PRICE_CHARGING, true)) //
				.deactivate();
	}

	@Test
	public void priceBetweenLimitsWithoutExcessPowerTest() throws Exception {
		preparePriceTest(EXCESS_POWER, 30) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 250.) // 25 Cent/kWh
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 300) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 7590) // (4140 + 11040) / 2
						.output(PRICE_CHARGING, true)) //
				.deactivate();
	}

	@Test
	public void priceBetweenLimitsWithHigherExcessPowerTest() throws Exception {
		preparePriceTest(EXCESS_POWER, 30) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 250.) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -9000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 9000) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void priceAboveLimitWithoutExcessPowerTest() throws Exception {
		preparePriceTest(EXCESS_POWER, 30) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 401.7) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 300) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void priceBetweenLimitsWithLowerExcessPowerTest() throws Exception {
		preparePriceTest(EXCESS_POWER, 30) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 250.) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 7590) //
						.output(PRICE_CHARGING, true)) //
				.deactivate();
	}

	@Test
	public void priceUnknownTest() throws Exception {
		preparePriceTest(EXCESS_POWER, 30) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, null) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 300) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void priceLimitDeactivatedTest() throws Exception {
		preparePriceTest(EXCESS_POWER, 0) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, -10.) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 300) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void priceDoesNotAffectForceChargeTest() throws Exception {
		preparePriceTest(FORCE_CHARGE, 30) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 250.) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 300) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) // 3680 W x 3 phases
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void calculateChargePowerFromPriceTest() {
		// above and at upper limit
		assertEquals(0, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(401.7, 30, 20, 4140, 11040));
		assertEquals(0, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(300., 30, 20, 4140, 11040));
		// between the limits
		assertEquals(4830, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(290., 30, 20, 4140, 11040));
		assertEquals(7590, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(250., 30, 20, 4140, 11040));
		// at and below lower limit
		assertEquals(11040, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(200., 30, 20, 4140, 11040));
		assertEquals(11040, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(-120., 30, 20, 4140, 11040));
		// lower limit not below upper limit: full power below the upper limit
		assertEquals(11040, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(250., 30, 30, 4140, 11040));
		// unknown price, deactivated, full power below minimum hardware power
		assertEquals(0, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(null, 30, 20, 4140, 11040));
		assertEquals(0, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(100., 0, 20, 4140, 11040));
		assertEquals(0, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(100., 30, 20, 4140, 3000));
	}

	@Test
	public void blendedPriceBelowLimitTest() throws Exception {
		preparePriceTest(EXCESS_POWER, 30) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 426.) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -3364) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) // 13.67 Cent/kWh
						.output(PRICE_CHARGING, true)) //
				.deactivate();
	}

	@Test
	public void blendedPriceAboveLimitTest() throws Exception {
		preparePriceTest(EXCESS_POWER, 30) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 426.) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) // 34.0 Cent/kWh
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void calculateBlendedPriceTest() {
		assertEquals(13.67, ControllerEvcsPriceImpl.calculateBlendedPrice(426., 3364, 4140, 7), 0.01);
		assertEquals(7., ControllerEvcsPriceImpl.calculateBlendedPrice(426., 5000, 4140, 7), 0.01);
		assertEquals(42.6, ControllerEvcsPriceImpl.calculateBlendedPrice(426., -200, 4140, 7), 0.01);
		assertEquals(null, ControllerEvcsPriceImpl.calculateBlendedPrice(null, 3364, 4140, 7));
		assertEquals(null, ControllerEvcsPriceImpl.calculateBlendedPrice(426., 3364, 0, 7));

		assertEquals(true, ControllerEvcsPriceImpl.isBlendedPriceBelowLimit(426., 3364, 4140, 7, 30));
		assertEquals(false, ControllerEvcsPriceImpl.isBlendedPriceBelowLimit(426., 1000, 4140, 7, 30));
		// no excess power, deactivated, unknown price
		assertEquals(false, ControllerEvcsPriceImpl.isBlendedPriceBelowLimit(250., 0, 4140, 7, 30));
		assertEquals(false, ControllerEvcsPriceImpl.isBlendedPriceBelowLimit(426., 3364, 4140, 7, 0));
		assertEquals(false, ControllerEvcsPriceImpl.isBlendedPriceBelowLimit(null, 3364, 4140, 7, 30));
	}

	private static final ChannelAddress SUM_PRODUCTION = new ChannelAddress("_sum", "ProductionActivePower");
	private static final ChannelAddress SUM_CONSUMPTION = new ChannelAddress("_sum", "ConsumptionActivePower");

	// Controller with predictions: production and consumption are constant for
	// the given number of quarter-hours from the clock time on.
	private static ControllerTest prepareStoragePriceTest(TimeLeapClock clock, boolean useStorageSurplus,
			int productionW, int consumptionW, int quarters) throws Exception {
		final var now = Instant.now(clock);
		final var cm = new DummyComponentManager(clock);
		final var sum = new DummySum();
		final var production = new Integer[quarters];
		final var consumption = new Integer[quarters];
		for (var i = 0; i < quarters; i++) {
			production[i] = productionW;
			consumption[i] = consumptionW;
		}
		final var predictorManager = new DummyPredictorManager(
				new DummyPredictor("predictor0", cm, Prediction.from(sum, SUM_PRODUCTION, now, production),
						SUM_PRODUCTION),
				new DummyPredictor("predictor1", cm, Prediction.from(sum, SUM_CONSUMPTION, now, consumption),
						SUM_CONSUMPTION));
		return new ControllerTest(new ControllerEvcsPriceImpl(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", predictorManager) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(3680) //
						.setDefaultChargeMinPower(0) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.setPriceLimit(30) //
						.setPriceLimitFullPower(20) //
						.setPriceChargePower(11040) //
						.setExcessChargeHystersis(0) //
						.setExcessChargePauseHysteresis(0) //
						.setUseStorageSurplus(useStorageSurplus) //
						.setStorageTargetSocNet(80) //
						.setStorageLossSurcharge(1) //
						.build());
	}

	@Test
	public void storageTargetReachableTest() throws Exception {
		// Dummy clock starts at midnight: 96 quarters, production 12 kW, consumption
		// 2 kW, car (plugged: at least 4140 W) -> 5860 W * 24 h = 140.6 kWh for the storage.
		// Storage 20 kWh at 50 %, target 80 % -> 6 kWh missing -> reachable
		// -> storage like PV (8 ct): 2130 W PV at 7 ct + 2010 W storage at 8 ct
		prepareStoragePriceTest(createDummyClock(), true, 12000, 2000, 96) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_MAX_DISCHARGE_POWER, 5000) //
						.input(ESS_DISCHARGE_POWER, 1903) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(EXPECTED_SURPLUS_ENERGY, 140640) //
						.output(STORAGE_ENERGY_TO_TARGET, 6000) //
						.output(STORAGE_TARGET_REACHABLE, true) //
						.output(STORAGE_PRICE, 80.) //
						.output(BLENDED_PRICE, 74.85507246376811) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(PRICE_CHARGING, true)) //
				.deactivate();
	}

	@Test
	public void storageTargetNotReachableTest() throws Exception {
		// Production 6 kW, consumption 2 kW, car 4033 W -> nothing left for the
		// storage although there is 4 kW surplus before the car. Target not
		// reachable -> storage like grid (43.1 ct): 24.5 ct blended -> still below
		// the 30 ct limit -> charging
		final var clock = createDummyClock();
		prepareStoragePriceTest(clock, true, 6000, 2000, 96) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_MAX_DISCHARGE_POWER, 5000) //
						.input(ESS_DISCHARGE_POWER, 1903) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(EXPECTED_SURPLUS_ENERGY, 0) //
						.output(STORAGE_ENERGY_TO_TARGET, 6000) //
						.output(STORAGE_TARGET_REACHABLE, false) //
						.output(STORAGE_PRICE, 431.) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(PRICE_CHARGING, true)) //
				.next(new TestCase() //
						// less PV: 1000 W PV, 3140 W storage at 43.1 ct -> 34.4 ct -> no charging
						.timeleap(clock, 5, MINUTES) //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_DISCHARGE_POWER, 3033) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.output(STORAGE_TARGET_REACHABLE, false) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void storageSurplusDisabledTest() throws Exception {
		// Disabled: channels are written, but the decision uses the grid price for
		// the missing power: 1000 W PV at 7 ct + 3140 W at 43.1 ct = 34.4 ct -> no
		// charging, although the target would be reachable
		prepareStoragePriceTest(createDummyClock(), false, 12000, 2000, 96) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_MAX_DISCHARGE_POWER, 5000) //
						.input(ESS_DISCHARGE_POWER, 3033) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_TARGET_REACHABLE, true) //
						.output(STORAGE_PRICE, 80.) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void calculateExpectedSurplusEnergyTest() {
		final var zone = ZoneId.of("Europe/Berlin");
		final var now = ZonedDateTime.of(2026, 10, 3, 10, 7, 0, 0, zone);
		final var start = now.withMinute(0).toInstant();
		// 8 quarters from 10:00; car 1000 W: surplus 3000, 3000, 0, 0, 2000 x 4
		final var production = Prediction.from(start, 6000, 6000, 1000, 1000, 5000, 5000, 5000, 5000);
		final var consumption = Prediction.from(start, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000);
		// (3000 + 3000 + 2000 * 4) / 4 = 3500 Wh
		assertEquals(3500,
				ControllerEvcsPriceImpl.calculateExpectedSurplusEnergy(production, consumption, 1000, now));
		// without car: (4000 + 4000 + 3000 * 4) / 4 = 5000 Wh
		assertEquals(5000,
				ControllerEvcsPriceImpl.calculateExpectedSurplusEnergy(production, consumption, 0, now));

		// quarters without production (evening) and of the next day are ignored
		final var evening = ZonedDateTime.of(2026, 10, 3, 23, 20, 0, 0, zone);
		final var eveningStart = evening.withMinute(15).toInstant();
		final var production2 = Prediction.from(eveningStart, 6000, 0, 6000, 6000, 6000, 6000);
		final var consumption2 = Prediction.from(eveningStart, 2000, 2000, 2000, 2000, 2000, 2000);
		// 23:15 -> 1000 Wh; 23:30 no production; 23:45 -> 1000 Wh
		assertEquals(2000,
				ControllerEvcsPriceImpl.calculateExpectedSurplusEnergy(production2, consumption2, 0, evening));

		assertNull(ControllerEvcsPriceImpl.calculateExpectedSurplusEnergy(Prediction.EMPTY_PREDICTION, consumption,
				0, now));
		assertNull(ControllerEvcsPriceImpl.calculateExpectedSurplusEnergy(null, consumption, 0, now));
	}

	@Test
	public void calculateAssumedCarPowerTest() {
		assertEquals(4033, ControllerEvcsPriceImpl.calculateAssumedCarPower(4033, Status.CHARGING, 4140 - 1000));
		// plugged in but not drawing: at least the minimum power
		assertEquals(4140, ControllerEvcsPriceImpl.calculateAssumedCarPower(0, Status.READY_FOR_CHARGING, 4140));
		assertEquals(4140, ControllerEvcsPriceImpl.calculateAssumedCarPower(0, Status.ENERGY_LIMIT_REACHED, 4140));
		// not plugged in
		assertEquals(0, ControllerEvcsPriceImpl.calculateAssumedCarPower(0, Status.NOT_READY_FOR_CHARGING, 4140));
		assertEquals(0, ControllerEvcsPriceImpl.calculateAssumedCarPower(0, Status.UNDEFINED, 4140));
	}

	@Test
	public void calculateStoragePriceTest() {
		assertTrue(ControllerEvcsPriceImpl.isStorageTargetReachable(13000, 12000));
		assertTrue(ControllerEvcsPriceImpl.isStorageTargetReachable(0, 0));
		assertFalse(ControllerEvcsPriceImpl.isStorageTargetReachable(11999, 12000));
		assertFalse(ControllerEvcsPriceImpl.isStorageTargetReachable(null, 12000));
		assertFalse(ControllerEvcsPriceImpl.isStorageTargetReachable(13000, null));

		assertEquals(8., ControllerEvcsPriceImpl.calculateStoragePrice(431., 7, 1, true), 0.01);
		assertEquals(43.1, ControllerEvcsPriceImpl.calculateStoragePrice(431., 7, 1, false), 0.01);
		assertNull(ControllerEvcsPriceImpl.calculateStoragePrice(null, 7, 1, true));

		// PV 2130 W at 7 ct, storage 2010 W at 8 ct
		assertEquals(7.49, ControllerEvcsPriceImpl.calculateBlendedPrice(431., 2130, 5000, 4140, 7, 8.), 0.01);
		// storage limited to 1000 W, rest 1010 W from grid at 43.1 ct
		assertEquals(16.05, ControllerEvcsPriceImpl.calculateBlendedPrice(431., 2130, 1000, 4140, 7, 8.), 0.01);
		// no storage price -> storage like grid, same as the two-part blended price
		assertEquals(ControllerEvcsPriceImpl.calculateBlendedPrice(431., 2130, 4140, 7),
				ControllerEvcsPriceImpl.calculateBlendedPrice(431., 2130, 5000, 4140, 7, null), 0.001);
	}

	@Test
	public void calculateEnergyToTargetTest() {
		// Live example: ess0 8755 Wh at 27 %, ess1 24192 Wh at 24 %, both 20-90 %
		final var windows = List.of(//
				new ControllerEvcsPriceImpl.StorageWindow(8755, 27, 20, 90),
				new ControllerEvcsPriceImpl.StorageWindow(24192, 24, 20, 90));
		// usable 23063 Wh, stored 8755 * 0.07 + 24192 * 0.04 = 613 + 968 = 1581 Wh
		// -> net SoC 7 %; target 80 % = 18450 Wh -> 16870 Wh missing
		assertEquals(7, ControllerEvcsPriceImpl.calculateNetSoc(windows));
		assertEquals(16870, ControllerEvcsPriceImpl.calculateEnergyToTarget(windows, 80));
		// above the target: nothing missing; SoC above the window counts as full
		assertEquals(0, ControllerEvcsPriceImpl.calculateEnergyToTarget(
				List.of(new ControllerEvcsPriceImpl.StorageWindow(10000, 95, 20, 90)), 80));
		assertEquals(100, ControllerEvcsPriceImpl.calculateNetSoc(
				List.of(new ControllerEvcsPriceImpl.StorageWindow(10000, 95, 20, 90))));
		// below the window counts as empty: full target missing
		assertEquals(5600, ControllerEvcsPriceImpl.calculateEnergyToTarget(
				List.of(new ControllerEvcsPriceImpl.StorageWindow(10000, 5, 20, 90)), 80));
		assertNull(ControllerEvcsPriceImpl.calculateEnergyToTarget(List.of(), 80));
	}

	@Test
	public void storagePriorityIgnoresStorageDischargeTest() throws Exception {
		// Priority STORAGE: the car charges 5405 W, grid 54 W, storage discharges
		// 4740 W, PV only 2 kW. The storage discharge is not excess power: 5405 -
		// 54 - 4740 - 200 = 411 W -> below minimum -> no excess charging; blended
		// price 411 W at 7 ct + 3729 W at 44.3 ct = 40.6 ct -> no charging
		new ControllerTest(new ControllerEvcsPriceImpl(createDummyClock())) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(3680) //
						.setDefaultChargeMinPower(0) //
						.setPriority(Priority.STORAGE) //
						.setEnergySessionLimit(0) //
						.setPriceLimit(30) //
						.setPriceLimitFullPower(20) //
						.setPriceChargePower(11040) //
						.setExcessChargeHystersis(0) //
						.setExcessChargePauseHysteresis(0) //
						.build()) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 443.) //
						.input(ESS_SOC, 60) //
						.input(ESS_DISCHARGE_POWER, 4740) //
						.input(ESS_MAX_DISCHARGE_POWER, 10000) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 54) //
						.input("evcs0", ACTIVE_POWER, 5405) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.next(new TestCase("real feed-in of 5 kW counts as excess") //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, -5000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4800)) //
				.deactivate();
	}

	@Test
	public void startConfirmationTest() throws Exception {
		// A single cycle with apparent excess must not start charging; after the
		// confirmation time of 10 s with continuous excess it does
		final var clock = createDummyClock();
		new ControllerTest(new ControllerEvcsPriceImpl(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(3680) //
						.setDefaultChargeMinPower(0) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.setPriceLimit(0) //
						.setExcessChargeHystersis(0) //
						.setExcessChargePauseHysteresis(0) //
						.setStartConfirmationTime(10) //
						.build()) //
				.next(new TestCase("apparent excess for one cycle") //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase("excess gone again: no start") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase("excess again, counter restarts") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase("still excess after 11 s: start") //
						.timeleap(clock, 11, SECONDS) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, false)) //
				.deactivate();
	}

	@Test
	public void storageAboveTargetChargesWithoutPvTest() throws Exception {
		// Evening, no PV: storage 20 kWh at 95 % (net 95 % > target 80 %) -> the
		// surplus above the target may go to the car at 8 ct, grid 50 ct.
		// After the storage dropped to the target: priced like grid -> stop.
		final var clock = createDummyClock();
		prepareStoragePriceTest(clock, true, 0, 2000, 96) //
				.next(new TestCase("storage above target, no PV") //
						.input(GRID_BUY_PRICE, 500.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 95) //
						.input(ESS_MAX_DISCHARGE_POWER, 5000) //
						.input(ESS_DISCHARGE_POWER, 2000) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_ENERGY_TO_TARGET, 0) //
						.output(STORAGE_TARGET_REACHABLE, true) //
						.output(STORAGE_PRICE, 80.) //
						.output(BLENDED_PRICE, 80.) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(PRICE_CHARGING, true)) //
				.next(new TestCase("storage at target: no PV, storage like grid -> stop") //
						.timeleap(clock, 5, MINUTES) //
						.input(ESS_SOC, 79) //
						.input(ESS_DISCHARGE_POWER, 6000) //
						.input("evcs0", ACTIVE_POWER, 4000) //
						.output(STORAGE_TARGET_REACHABLE, false) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}
}
