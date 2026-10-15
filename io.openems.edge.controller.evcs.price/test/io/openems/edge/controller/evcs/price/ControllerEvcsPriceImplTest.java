package io.openems.edge.controller.evcs.price;

import static io.openems.common.test.TestUtils.createDummyClock;
import static io.openems.edge.common.sum.Sum.ChannelId.CONSUMPTION_ACTIVE_POWER;
import static io.openems.edge.common.sum.Sum.ChannelId.ESS_CAPACITY;
import static io.openems.edge.common.sum.Sum.ChannelId.ESS_DISCHARGE_POWER;
import static io.openems.edge.common.sum.Sum.ChannelId.ESS_SOC;
import static io.openems.edge.common.sum.Sum.ChannelId.GRID_ACTIVE_POWER;
import static io.openems.edge.common.sum.Sum.ChannelId.GRID_BUY_PRICE;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.AWAITING_HYSTERESIS;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.BLENDED_PRICE;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.PAUSE_TODAY;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_DISCHARGE_BLOCKED;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_POWER_FOR_CAR;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_PRICE;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_ENERGY_TO_TARGET;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_SURPLUS_ENERGY;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_TARGET_REACHABLE;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.PRICE_CHARGING;
import static io.openems.edge.controller.evcs.price.Priority.CAR;
import static io.openems.edge.controller.evcs.price.Priority.STORAGE;
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
import static java.time.temporal.ChronoUnit.HOURS;
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
import io.openems.common.types.OptionsEnum;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;
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
import io.openems.edge.timeofusetariff.test.DummyTimeOfUseTariffProvider;
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
		// Start hysteresis 30 s, stop hysteresis 120 s
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
						.setStartConfirmationTime(30) //
						.setStopConfirmationTime(120) //
						.build()) //

				// Excess power: start only after the start hysteresis
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -6_000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase() //
						.timeleap(clock, 31, SECONDS) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6_000) //
						.output(AWAITING_HYSTERESIS, false)) //

				// Changes of the charge power while charging are applied at once
				.next(new TestCase() //
						.input(GRID_ACTIVE_POWER, -200) //
						.input("evcs0", ACTIVE_POWER, 5800) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6_000)) //
				.next(new TestCase() //
						.input(GRID_ACTIVE_POWER, 500) //
						.input("evcs0", ACTIVE_POWER, 5800) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 5300)) //

				// No excess power any more: stop only after the stop hysteresis
				.next(new TestCase() //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 5000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 5_300) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase() //
						.timeleap(clock, 6, MINUTES) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, false)) //

				// Excess power again: start hysteresis from the beginning
				.next(new TestCase() //
						.input(GRID_ACTIVE_POWER, -5000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase() //
						.timeleap(clock, 1, MINUTES) //
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
		return preparePriceTest(chargeMode, priceLimit, true);
	}

	private static ControllerTest preparePriceTest(ChargeMode chargeMode, double priceLimit,
			boolean enablePriceCharging) throws Exception {
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
						.setEnablePriceCharging(enablePriceCharging) //
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
	public void priceLimitZeroChargesAtNegativePriceTest() throws Exception {
		// A limit of 0 is a limit, not a switch: charging at negative prices only
		preparePriceTest(EXCESS_POWER, 0) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, -10.) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 300) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) //
						.output(PRICE_CHARGING, true)) //
				.deactivate();
	}

	@Test
	public void priceChargingDisabledTest() throws Exception {
		// Without price-based charging the price does not matter at all: only
		// excess power that covers the minimum hardware power is used
		preparePriceTest(EXCESS_POWER, 30, false) //
				.next(new TestCase("negative price, no excess power") //
						.input(GRID_BUY_PRICE, -10.) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, 300) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.next(new TestCase("excess power below the minimum: no fill-up from grid") //
						.input(GRID_BUY_PRICE, 150.) //
						.input(GRID_ACTIVE_POWER, -3000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.next(new TestCase("excess power covers the minimum") //
						.input(GRID_ACTIVE_POWER, -5000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 5000) //
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
		// limit 0: negative prices only
		assertEquals(0, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(100., 0, 20, 4140, 11040));
		assertEquals(0, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(0., 0, 20, 4140, 11040));
		assertEquals(11040, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(-10., 0, 20, 4140, 11040));
		// unknown price, full power below minimum hardware power
		assertEquals(0, ControllerEvcsPriceImpl.calculateChargePowerFromPrice(null, 30, 20, 4140, 11040));
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
		return prepareStoragePriceTest(clock, useStorageSurplus, true, productionW, consumptionW, quarters);
	}

	private static ControllerTest prepareStoragePriceTest(TimeLeapClock clock, boolean useStorageSurplus,
			boolean enablePriceCharging, int productionW, int consumptionW, int quarters) throws Exception {
		return prepareStoragePriceTest(clock, useStorageSurplus, enablePriceCharging, CAR, productionW, consumptionW,
				quarters);
	}

	private static ControllerTest prepareStoragePriceTest(TimeLeapClock clock, boolean useStorageSurplus,
			boolean enablePriceCharging, Priority priority, int productionW, int consumptionW, int quarters)
			throws Exception {
		return prepareStoragePriceTest(clock, useStorageSurplus, enablePriceCharging, priority, null, productionW,
				consumptionW, quarters);
	}

	private static ControllerTest prepareStoragePriceTest(TimeLeapClock clock, boolean useStorageSurplus,
			boolean enablePriceCharging, Priority priority, OpenemsComponent timeOfUseTariff, int productionW,
			int consumptionW, int quarters) throws Exception {
		final var now = Instant.now(clock);
		final var cm = new DummyComponentManager(clock);
		if (timeOfUseTariff != null) {
			cm.addComponent(timeOfUseTariff);
		}
		final var sum = new DummySum();
		final var production = new Integer[quarters];
		final var consumption = new Integer[quarters];
		final var prices = new Double[quarters];
		for (var i = 0; i < quarters; i++) {
			production[i] = productionW;
			consumption[i] = consumptionW;
			prices[i] = 431.;
		}
		final var predictorManager = new DummyPredictorManager(
				new DummyPredictor("predictor0", cm, Prediction.from(sum, SUM_PRODUCTION, now, production),
						SUM_PRODUCTION),
				new DummyPredictor("predictor1", cm, Prediction.from(sum, SUM_CONSUMPTION, now, consumption),
						SUM_CONSUMPTION));
		final var test = new ControllerTest(new ControllerEvcsPriceImpl(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", predictorManager) //
				.addReference("timeOfUseTariff", DummyTimeOfUseTariffProvider.fromQuarterlyPrices(clock, prices)) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0"));
		if (timeOfUseTariff != null) {
			test.addComponent(timeOfUseTariff);
		}
		return test.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(3680) //
						.setDefaultChargeMinPower(0) //
						.setPriority(priority) //
						.setEnergySessionLimit(0) //
						.setEnablePriceCharging(enablePriceCharging) //
						.setPriceLimit(30) //
						.setPriceLimitFullPower(20) //
						.setPriceChargePower(11040) //
						.setUseStorageSurplus(useStorageSurplus) //
						.setStorageTargetSocNet(80) //
						.setStorageLossSurcharge(1) //
						.setTimeOfUseTariffId(timeOfUseTariff == null ? "" : timeOfUseTariff.id()) //
						.build());
	}

	/**
	 * Stands in for the Time-of-Use-Tariff controller: only its StateMachine
	 * channel is read, by name.
	 */
	private static class DummyTimeOfUseTariff extends AbstractDummyOpenemsComponent<DummyTimeOfUseTariff> {

		public enum State implements OptionsEnum {
			BALANCING(1, "Balancing"), DELAY_DISCHARGE(0, "Delay discharge"), CHARGE_GRID(3, "Charge from grid");

			private final int value;
			private final String name;

			private State(int value, String name) {
				this.value = value;
				this.name = name;
			}

			@Override
			public int getValue() {
				return this.value;
			}

			@Override
			public String getName() {
				return this.name;
			}

			@Override
			public OptionsEnum getUndefined() {
				return BALANCING;
			}
		}

		public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
			STATE_MACHINE(Doc.of(State.values()));

			private final Doc doc;

			private ChannelId(Doc doc) {
				this.doc = doc;
			}

			@Override
			public Doc doc() {
				return this.doc;
			}
		}

		public DummyTimeOfUseTariff(String id) {
			super(id, OpenemsComponent.ChannelId.values(), ChannelId.values());
		}

		@Override
		protected DummyTimeOfUseTariff self() {
			return this;
		}
	}

	@Test
	public void carPriorityPlanTest() throws Exception {
		// Dummy clock starts at midnight: 96 quarters, production 12 kW, consumption
		// 2 kW, priority CAR: the plan gives the whole 10 kW surplus to the car, the
		// storage (30 kWh at 50 %, target 80 %) gets nothing and misses its target by
		// 9 kWh -> storage like grid (43.1 ct): the car draws 4033 W, 2130 W PV at
		// 7 ct and the measured 1903 W storage at 43.1 ct -> 24.0 ct -> charging
		prepareStoragePriceTest(createDummyClock(), true, 12000, 2000, 96) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 1903) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_SURPLUS_ENERGY, -9000) //
						.output(STORAGE_ENERGY_TO_TARGET, 9000) //
						.output(STORAGE_TARGET_REACHABLE, false) //
						.output(STORAGE_PRICE, 431.) //
						.output(STORAGE_POWER_FOR_CAR, 1903) //
						.output(STORAGE_DISCHARGE_BLOCKED, false) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(PRICE_CHARGING, true)) //
				.deactivate();
	}

	@Test
	public void planKeepsTheTargetTest() throws Exception {
		// Production 6 kW, consumption 2 kW: the 4 kW surplus is below the minimum
		// power, the car would fill up from the storage. The plan drops those
		// quarter-hours until the storage (20 kWh at 50 %, target 80 %) reaches its
		// target -> target reachable. Live: 2130 W PV at 7 ct, the measured 1903 W
		// storage mostly at 43.1 ct -> below the 30 ct limit -> charging
		final var clock = createDummyClock();
		prepareStoragePriceTest(clock, true, 6000, 2000, 96) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 1903) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_ENERGY_TO_TARGET, 6000) //
						.output(STORAGE_TARGET_REACHABLE, true) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(PRICE_CHARGING, true)) //
				.next(new TestCase() //
						// less PV: 1000 W PV, 3033 W storage mostly at 43.1 ct -> above 30 ct
						.timeleap(clock, 5, MINUTES) //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_DISCHARGE_POWER, 3033) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.output(STORAGE_TARGET_REACHABLE, true) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void storagePriorityPlanTest() throws Exception {
		// Priority STORAGE: the storage is filled first, the car fills up from the
		// storage charging only as far as the storage still reaches its target in
		// the evening; the plan contains that share for the running quarter-hour ->
		// storage like PV (8 ct), the storage ends at its target
		prepareStoragePriceTest(createDummyClock(), true, true, STORAGE, 6000, 2000, 96) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_TARGET_REACHABLE, true) //
						.output(STORAGE_PRICE, 80.)) //
				.deactivate();
	}


	@Test
	public void storagePriorityFollowsMeasuredSurplusTest() throws Exception {
		// Priority STORAGE, the car charges 4404 W, grid 0, the storage charges: surplus
		// is what goes to grid plus what the car draws, minus the buffer -> 4204 W.
		// The car follows the measured surplus; what the storage charges less because
		// of it is not estimated live, the plan protects the evening target
		prepareStoragePriceTest(createDummyClock(), true, true, STORAGE, 12000, 2000, 96) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, -7596) //
						.input(CONSUMPTION_ACTIVE_POWER, 6404) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ACTIVE_POWER, 4404) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_TARGET_REACHABLE, true) //
						.output(STORAGE_PRICE, 80.) //
						.output(STORAGE_POWER_FOR_CAR, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4204) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}


	@Test
	public void storageSurplusDisabledTest() throws Exception {
		// Disabled: the storage is priced like grid: 1000 W PV at 7 ct + 3140 W at
		// 43.1 ct = 34.4 ct -> no charging (priority CAR: the plan gives the surplus
		// to the car, the storage misses its target)
		prepareStoragePriceTest(createDummyClock(), false, 12000, 2000, 96) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 3033) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_TARGET_REACHABLE, false) //
						.output(STORAGE_PRICE, 431.) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void storageBlockedByTimeOfUseTariffTest() throws Exception {
		// Priority STORAGE, 12 kW production, 2 kW consumption: the plan lets the car
		// fill up from the storage charging while the storage still reaches its
		// target. The Time-of-Use-Tariff controller decides whether the storage
		// delivers at all.
		final var clock = createDummyClock();
		final var tou = new DummyTimeOfUseTariff("ctrlToU0");
		prepareStoragePriceTest(clock, true, true, STORAGE, tou, 12000, 2000, 96) //
				// Charging, but the discharge is delayed: the car draws 4140 W from grid.
				// Storage share 0, blended price = grid price 43.1 ct -> stop
				.next(new TestCase("delayed discharge while charging") //
						.input("ctrlToU0", DummyTimeOfUseTariff.ChannelId.STATE_MACHINE,
								DummyTimeOfUseTariff.State.DELAY_DISCHARGE) //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(CONSUMPTION_ACTIVE_POWER, 5140) //
						.input(GRID_ACTIVE_POWER, 4140) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ACTIVE_POWER, 4140) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_TARGET_REACHABLE, true) //
						.output(STORAGE_PRICE, 431.) //
						.output(STORAGE_DISCHARGE_BLOCKED, true) //
						.output(STORAGE_POWER_FOR_CAR, 0) //
						.output(BLENDED_PRICE, 431.0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				// Balancing: the storage discharges 5140 W, 1000 W for the house, 4140 W
				// for the car -> 8 ct -> charging with the minimum power
				.next(new TestCase("balancing while charging") //
						.timeleap(clock, 5, MINUTES) //
						.input("ctrlToU0", DummyTimeOfUseTariff.ChannelId.STATE_MACHINE,
								DummyTimeOfUseTariff.State.BALANCING) //
						.input(ESS_DISCHARGE_POWER, 5140) //
						.input(GRID_ACTIVE_POWER, 0) //
						.output(STORAGE_DISCHARGE_BLOCKED, false) //
						.output(STORAGE_PRICE, 80.) //
						.output(STORAGE_POWER_FOR_CAR, 4140) //
						.output(BLENDED_PRICE, 80.0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140)) //
				// Charging from grid: the storage charges, the car draws from grid -> stop
				.next(new TestCase("charge from grid while charging") //
						.timeleap(clock, 5, MINUTES) //
						.input("ctrlToU0", DummyTimeOfUseTariff.ChannelId.STATE_MACHINE,
								DummyTimeOfUseTariff.State.CHARGE_GRID) //
						.input(ESS_DISCHARGE_POWER, -3000) //
						.input(GRID_ACTIVE_POWER, 7140) //
						.output(STORAGE_DISCHARGE_BLOCKED, true) //
						.output(STORAGE_POWER_FOR_CAR, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				// Before the start nothing is measured: with balancing the plan's storage
				// share covers the minimum power -> start
				.next(new TestCase("balancing before the start") //
						.timeleap(clock, 5, MINUTES) //
						.input("ctrlToU0", DummyTimeOfUseTariff.ChannelId.STATE_MACHINE,
								DummyTimeOfUseTariff.State.BALANCING) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(CONSUMPTION_ACTIVE_POWER, 1000) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output(STORAGE_DISCHARGE_BLOCKED, false) //
						.output(STORAGE_POWER_FOR_CAR, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140)) //
				// Delayed discharge before the start: no start
				.next(new TestCase("delayed discharge before the start") //
						.timeleap(clock, 5, MINUTES) //
						.input("ctrlToU0", DummyTimeOfUseTariff.ChannelId.STATE_MACHINE,
								DummyTimeOfUseTariff.State.DELAY_DISCHARGE) //
						.output(STORAGE_DISCHARGE_BLOCKED, true) //
						.output(STORAGE_POWER_FOR_CAR, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.deactivate();
	}

	@Test
	public void calculateStoragePowerForCarTest() {
		// blocked: nothing, whatever is measured
		assertEquals(0, ControllerEvcsPriceImpl.calculateStoragePowerForCar(4140, 5140, 5140, 0, true, 4140, 4140));
		// charging: the house (1000 W) is served first, the rest goes to the car
		assertEquals(4140,
				ControllerEvcsPriceImpl.calculateStoragePowerForCar(4140, 5140, 5140, 0, false, 4140, 4140));
		assertEquals(1000,
				ControllerEvcsPriceImpl.calculateStoragePowerForCar(4140, 2000, 5140, 0, false, 4140, 4140));
		// PV covers the house: the whole discharge goes to the car, released or not
		assertEquals(1903,
				ControllerEvcsPriceImpl.calculateStoragePowerForCar(4033, 1903, 4033, 2130, false, 0, 4140));
		// the storage charges: nothing for the car
		assertEquals(0, ControllerEvcsPriceImpl.calculateStoragePowerForCar(4140, -3000, 5140, 0, false, 4140, 4140));
		// not more than the car draws
		assertEquals(4033,
				ControllerEvcsPriceImpl.calculateStoragePowerForCar(4033, 9000, 5033, 0, false, 4140, 4140));
		// before the start: what the plan contains, up to the minimum power
		assertEquals(4140, ControllerEvcsPriceImpl.calculateStoragePowerForCar(0, 0, 1000, 0, false, 5000, 4140));
		assertEquals(2000, ControllerEvcsPriceImpl.calculateStoragePowerForCar(0, 0, 1000, 0, false, 2000, 4140));
		assertEquals(0, ControllerEvcsPriceImpl.calculateStoragePowerForCar(0, 0, 1000, 0, false, 0, 4140));
	}

	@Test
	public void isDischargeBlockingStateTest() {
		assertTrue(ControllerEvcsPriceImpl.isDischargeBlockingState("DELAY_DISCHARGE"));
		assertTrue(ControllerEvcsPriceImpl.isDischargeBlockingState("CHARGE_GRID"));
		assertTrue(ControllerEvcsPriceImpl.isDischargeBlockingState("PEAK_SHAVING"));
		assertFalse(ControllerEvcsPriceImpl.isDischargeBlockingState("BALANCING"));
		assertFalse(ControllerEvcsPriceImpl.isDischargeBlockingState("DELAY_CHARGE"));
		assertFalse(ControllerEvcsPriceImpl.isDischargeBlockingState("LIMIT_CHARGE"));
		assertFalse(ControllerEvcsPriceImpl.isDischargeBlockingState("DISCHARGE_CONSUMPTION"));
		assertFalse(ControllerEvcsPriceImpl.isDischargeBlockingState("DISCHARGE_GRID"));
		assertFalse(ControllerEvcsPriceImpl.isDischargeBlockingState("AVOID_GRID_SELL_LIMIT"));
		assertFalse(ControllerEvcsPriceImpl.isDischargeBlockingState(null));
	}



	@Test
	public void calculateBlendedPriceWithStorageTest() {
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
						.build()) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 443.) //
						.input(ESS_SOC, 60) //
						.input(ESS_DISCHARGE_POWER, 4740) //
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
	public void stopConfirmationTest() throws Exception {
		// Once charging, a single cycle without excess must not pause; after the
		// confirmation time of 10 s without excess it does
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
						.setStartConfirmationTime(0) //
						.setStopConfirmationTime(10) //
						.build()) //
				.next(new TestCase("excess: start at once") //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //
				.next(new TestCase("excess gone for one cycle: keep charging") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_ACTIVE_POWER, 2000) //
						.input("evcs0", ACTIVE_POWER, 4000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase("excess back: counter restarts") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_ACTIVE_POWER, -2000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase("excess gone again") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_ACTIVE_POWER, 2000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase("still no excess after 11 s: pause") //
						.timeleap(clock, 11, SECONDS) //
						.input(GRID_ACTIVE_POWER, 2000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, false)) //
				.deactivate();
	}

	@Test
	public void pauseTodayTest() throws Exception {
		// "Do not charge today": written as true, charging stops at once and stays
		// off until midnight; from then on the controller decides on its own again
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
						.setStartConfirmationTime(0) //
						.setStopConfirmationTime(120) //
						.build()) //
				.next(new TestCase("excess: charging") //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //
				.next(new TestCase("pause written: stop at once, no stop hysteresis") //
						.timeleap(clock, 2, SECONDS) //
						.input(PAUSE_TODAY, true) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase("still paused with excess") //
						.timeleap(clock, 10, HOURS) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase("after midnight: charging again") //
						.timeleap(clock, 14, HOURS) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //
				.next(new TestCase("pause and end it by hand") //
						.timeleap(clock, 2, SECONDS) //
						.input(PAUSE_TODAY, true) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase() //
						.timeleap(clock, 2, SECONDS) //
						.input(PAUSE_TODAY, false) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //
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




}
