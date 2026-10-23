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
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.CAR_TAKES_NOTHING;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.HYSTERESIS_REMAINING;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.PAUSE_TODAY;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.PLAN_ENERGY;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.PLAN_REASON;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_DISCHARGE_BLOCKED;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_POWER_FOR_CAR;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_PRICE;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_ENERGY_TO_TARGET;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_SURPLUS_ENERGY;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_TARGET_REACHABLE;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.SESSION_ENERGY;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_DISCHARGE_LIMIT;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_LOCKED;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.TARGET_PLAN_QUARTERS;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.TARGET_REMAINING_ENERGY;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.TARGET_STATUS;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.PRICE_CHARGING;
import static io.openems.edge.controller.evcs.price.Priority.CAR;
import static io.openems.edge.controller.evcs.price.Priority.STORAGE;
import static io.openems.edge.evcs.api.ChargeMode.EXCESS_POWER;
import static io.openems.edge.evcs.api.ChargeMode.FORCE_CHARGE;
import static io.openems.edge.evcs.api.Evcs.ChannelId.ENERGY_SESSION;
import static io.openems.edge.evcs.api.Evcs.ChannelId.MAXIMUM_HARDWARE_POWER;
import static io.openems.edge.evcs.api.Evcs.ChannelId.MAXIMUM_POWER;
import static io.openems.edge.evcs.api.Evcs.ChannelId.MINIMUM_HARDWARE_POWER;
import static io.openems.edge.evcs.api.Evcs.ChannelId.STATUS;
import static io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_LESS_OR_EQUALS;
import static io.openems.edge.evcs.api.ManagedEvcs.ChannelId.CHARGE_STATE;
import static io.openems.edge.evcs.api.ManagedEvcs.ChannelId.IS_CLUSTERED;
import static io.openems.edge.evcs.api.ManagedEvcs.ChannelId.SET_CHARGE_POWER_LIMIT;
import static io.openems.edge.evcs.api.ManagedEvcs.ChannelId.SET_ENERGY_LIMIT;
import static io.openems.edge.evcs.api.ManagedEvcs.ChannelId.SET_CHARGE_POWER_REQUEST;
import static io.openems.edge.meter.api.ElectricityMeter.ChannelId.ACTIVE_POWER;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.openems.edge.ess.test.DummyManagedSymmetricEss;
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
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
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
	public void noHysteresisWithoutCarTest() throws Exception {
		// Without a car there is nothing to protect: the limit follows the excess at
		// once and no hysteresis is shown. After plugging in the charge process starts
		// with the start hysteresis.
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
				.next(new TestCase() //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.NOT_READY_FOR_CHARGING) //
						.input(GRID_ACTIVE_POWER, -6_000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6_000) //
						.output(AWAITING_HYSTERESIS, false) //
						.output(HYSTERESIS_REMAINING, null)) //
				.next(new TestCase() //
						.input(GRID_ACTIVE_POWER, 1000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase() //
						.input(GRID_ACTIVE_POWER, -6_000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6_000) //
						.output(AWAITING_HYSTERESIS, false)) //
				// the car is plugged in: start hysteresis from the beginning
				.next(new TestCase() //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase() //
						.timeleap(clock, 31, SECONDS) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6_000) //
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
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
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
		return prepareStoragePriceTest(clock, new ControllerEvcsPriceImpl(clock), useStorageSurplus,
				enablePriceCharging, priority, timeOfUseTariff, productionW, consumptionW, quarters);
	}

	private static ControllerTest prepareStoragePriceTest(TimeLeapClock clock, ControllerEvcsPriceImpl controller,
			boolean useStorageSurplus, boolean enablePriceCharging, Priority priority,
			OpenemsComponent timeOfUseTariff, int productionW, int consumptionW, int quarters) throws Exception {
		return prepareStoragePriceTest(clock, controller, useStorageSurplus, enablePriceCharging, priority,
				timeOfUseTariff, productionW, consumptionW, quarters, 0);
	}

	private static ControllerTest prepareStoragePriceTest(TimeLeapClock clock, ControllerEvcsPriceImpl controller,
			boolean useStorageSurplus, boolean enablePriceCharging, Priority priority,
			OpenemsComponent timeOfUseTariff, int productionW, int consumptionW, int quarters,
			int stopConfirmationTime) throws Exception {
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
		final var test = new ControllerTest(controller) //
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
						.setStopConfirmationTime(stopConfirmationTime) //
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
	public void planWithoutStorageBudgetTest() throws Exception {
		// Priority CAR, production 6 kW, consumption 2 kW: the 4 kW surplus is below
		// the minimum power, the car fills up from the storage. The storage (20 kWh at
		// 50 %, target 80 %) misses its target, so the plan withdraws the storage
		// budget: the 140 W storage share costs the grid price, the blend of 4 kW PV
		// at 7 ct stays far below the limit, the car charges all day -> target not
		// reachable. Live: 2130 W PV at 7 ct, the measured 1903 W storage at 43.1 ct
		// -> below the 30 ct limit -> charging
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
						.output(STORAGE_TARGET_REACHABLE, false) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(PRICE_CHARGING, true)) //
				.next(new TestCase() //
						// less PV: 1000 W PV, 3033 W storage at 43.1 ct -> above 30 ct
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
	public void planWithoutProductionForecastTest() throws Exception {
		// The weather forecast fails to load right at the start of the quarter-hour
		// (live: Open-Meteo answers 503 at every quarter-hour): no plan at all instead
		// of a plan of unknown quarter-hours, the storage projection stays empty. The
		// next cycle finds the forecast and the plan appears within the same
		// quarter-hour.
		final var clock = createDummyClock();
		final var now = Instant.now(clock);
		final var cm = new DummyComponentManager(clock);
		final var sum = new DummySum();
		final var production = new Integer[96];
		final var consumption = new Integer[96];
		final var prices = new Double[96];
		for (var i = 0; i < 96; i++) {
			production[i] = 6000;
			consumption[i] = 2000;
			prices[i] = 431.;
		}
		final var productionPredictor = new DummyPredictor("predictor0", cm, Prediction.EMPTY_PREDICTION,
				SUM_PRODUCTION);
		final var predictorManager = new DummyPredictorManager(productionPredictor, //
				new DummyPredictor("predictor1", cm, Prediction.from(sum, SUM_CONSUMPTION, now, consumption),
						SUM_CONSUMPTION));
		final var controller = new ControllerEvcsPriceImpl(clock);
		new ControllerTest(controller) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", predictorManager) //
				.addReference("timeOfUseTariff", DummyTimeOfUseTariffProvider.fromQuarterlyPrices(clock, prices)) //
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
						.setEnablePriceCharging(true) //
						.setPriceLimit(30) //
						.setPriceLimitFullPower(20) //
						.setPriceChargePower(11040) //
						.setUseStorageSurplus(true) //
						.setStorageTargetSocNet(80) //
						.setStorageLossSurcharge(1) //
						.setTimeOfUseTariffId("") //
						.build()) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(PLAN_ENERGY, null) //
						.output(PLAN_REASON, null) //
						.output(STORAGE_SURPLUS_ENERGY, null)) //
				.next(new TestCase() //
						// one cycle later the forecast is there: plan within the same quarter-hour
						.timeleap(clock, 1, SECONDS) //
						.onBeforeProcessImage(() -> productionPredictor
								.setPrediction(Prediction.from(sum, SUM_PRODUCTION, now, production))) //
						.input(GRID_BUY_PRICE, 431.) //
						.input("evcs0", ACTIVE_POWER, 4033) //
						.onAfterControllersCallbacks(() -> {
							final var energy = controller.channel(PLAN_ENERGY).getNextValue().asOptional();
							assertTrue(energy.isPresent() && (Integer) energy.get() > 0, "plan expected");
						}) //
						.output(PLAN_REASON, "FILL_UP")) //
				.deactivate();
	}

	@Test
	public void displayPlanWithoutCarTest() throws Exception {
		// No car: the plan for the controller has no charging (NOT_PLUGGED), the plan
		// for the display shows what charging would cost as if a car were plugged in
		// now: 4 kW surplus below the minimum power -> fill-up from the storage at a
		// blend below the limit. Setup like planWithoutStorageBudgetTest.
		final var clock = createDummyClock();
		final var controller = new ControllerEvcsPriceImpl(clock);
		prepareStoragePriceTest(clock, controller, true, true, CAR, null, 6000, 2000, 96) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.NOT_READY_FOR_CHARGING) //
						.input(GRID_ACTIVE_POWER, -4000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(PLAN_ENERGY, 0) //
						.output(PLAN_REASON, "NOT_PLUGGED") //
						.onAfterControllersCallbacks(() -> {
							final var display = controller.getDisplayPlan();
							assertFalse(display.quarters().isEmpty(), "display plan expected");
							final var q = display.quarters().get(0);
							assertEquals(4140, q.power());
							assertEquals(ChargingPlan.Reason.FILL_UP, q.reason());
							assertTrue(q.blendedPrice() != null && q.blendedPrice() < 30, "blend below the limit");
							assertTrue(display.energyWh() > 0);
						})) //
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
	public void storageDipKeepsChargingByPlanTest() throws Exception {
		// Charging by price from the storage the plan releases (8 ct, grid 43.1 ct):
		// a house load that takes the storage share for a moment is a cloud, not a
		// price -> the stop hysteresis holds. The optimizer taking the storage away
		// is a decision of the plan -> stop at once
		final var clock = createDummyClock();
		final var tou = new DummyTimeOfUseTariff("ctrlToU0");
		prepareStoragePriceTest(clock, new ControllerEvcsPriceImpl(clock), true, true, STORAGE, tou, 12000, 2000,
				96, 600) //
				.next(new TestCase("balancing while charging") //
						.input("ctrlToU0", DummyTimeOfUseTariff.ChannelId.STATE_MACHINE,
								DummyTimeOfUseTariff.State.BALANCING) //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 5140) //
						.input(CONSUMPTION_ACTIVE_POWER, 5140) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ACTIVE_POWER, 4140) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_PRICE, 80.) //
						.output(STORAGE_POWER_FOR_CAR, 4140) //
						.output(BLENDED_PRICE, 80.0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(AWAITING_HYSTERESIS, false)) //
				// 5 kW house load: the storage delivers its maximum 5140 W, 140 W are
				// left for the car, blended 41.9 ct -> no fill-up, but the plan still has
				// 4140 W storage for the car -> stop hysteresis, charging goes on
				.next(new TestCase("house load takes the storage") //
						.timeleap(clock, 1, MINUTES) //
						.input(ESS_DISCHARGE_POWER, 5140) //
						.input(CONSUMPTION_ACTIVE_POWER, 9140) //
						.input(GRID_ACTIVE_POWER, 4000) //
						.output(STORAGE_POWER_FOR_CAR, 140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase("house load still there") //
						.timeleap(clock, 5, MINUTES) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase("house load gone") //
						.timeleap(clock, 1, MINUTES) //
						.input(CONSUMPTION_ACTIVE_POWER, 5140) //
						.input(GRID_ACTIVE_POWER, 0) //
						.output(STORAGE_POWER_FOR_CAR, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(AWAITING_HYSTERESIS, false)) //
				// the optimizer delays the discharge: the plan has no storage for the
				// car any more -> charging by price ends at once
				.next(new TestCase("delayed discharge") //
						.timeleap(clock, 1, MINUTES) //
						.input("ctrlToU0", DummyTimeOfUseTariff.ChannelId.STATE_MACHINE,
								DummyTimeOfUseTariff.State.DELAY_DISCHARGE) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, 4140) //
						.output(STORAGE_DISCHARGE_BLOCKED, true) //
						.output(STORAGE_POWER_FOR_CAR, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, false)) //
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
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
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
	public void priceDrivenSwitchesAtOnceTest() throws Exception {
		// Charging the price alone carries starts and stops at once, the time
		// hysteresis of 600 s does not apply: the price changes with the quarter-hour.
		// Once charging by price it goes on up to 1 ct above the limit. Surplus
		// charging keeps the time hysteresis
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
						.setEnablePriceCharging(true) //
						.setPriceLimit(30) //
						.setPriceLimitFullPower(17) //
						.setPriceChargePower(11040) //
						.setPriceHysteresis(1) //
						.setStartConfirmationTime(600) //
						.setStopConfirmationTime(600) //
						.build()) //
				.next(new TestCase("29 ct, no surplus: start at once") //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input(GRID_BUY_PRICE, 290.) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4671) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase("30.5 ct: within the price hysteresis, minimum power") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_BUY_PRICE, 305.) //
						.input(GRID_ACTIVE_POWER, 5600) //
						.input("evcs0", ACTIVE_POWER, 4600) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase("31.5 ct: stop at once") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_BUY_PRICE, 315.) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase("30.5 ct: not charging, no hysteresis: stays off") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_BUY_PRICE, 305.) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase("25 ct: start at once") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_BUY_PRICE, 250.) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6794) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase("40 ct but surplus: charging goes on by sun") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_BUY_PRICE, 400.) //
						.input(GRID_ACTIVE_POWER, -2000) //
						.input("evcs0", ACTIVE_POWER, 4000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase("surplus gone: the stop hysteresis applies") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_ACTIVE_POWER, 5000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase("after 600 s without surplus: stop") //
						.timeleap(clock, 600, SECONDS) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase("sun back: the start hysteresis applies") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, true)) //
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
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //
				.next(new TestCase("excess gone: stop hysteresis running") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_ACTIVE_POWER, 2000) //
						.input("evcs0", ACTIVE_POWER, 4000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, true)) //
				.next(new TestCase("pause written: stop at once, no stop hysteresis") //
						.timeleap(clock, 2, SECONDS) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input(PAUSE_TODAY, true) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, false)) //
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
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
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
	public void nextDeadlineTest() {
		final var now = ZonedDateTime.of(2026, 10, 19, 22, 0, 0, 0, ZoneId.of("Europe/Berlin"));
		assertEquals(now.plusHours(9), ControllerEvcsPriceImpl.nextDeadline(now, 7 * 60));
		assertEquals(now.plusMinutes(30), ControllerEvcsPriceImpl.nextDeadline(now, 22 * 60 + 30));
		assertEquals(now.plusDays(1), ControllerEvcsPriceImpl.nextDeadline(now, 22 * 60));
	}

	@Test
	public void targetChargingTest() throws Exception {
		// Target charging: 5 kWh until two hours from now, 50 ct in the first hour
		// and 20 ct in the second -> the plan waits for the second hour and charges
		// with full power there at once, without a start hysteresis
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 120) % (24 * 60);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = i >= 4 && i < 8 ? 200. : 500.;
		}
		final var controller = new ControllerEvcsPriceImpl(clock);
		prepareTargetTest(clock, controller, prices, 5000, targetTime) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 500.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "PLANNED") //
						.output(PLAN_ENERGY, 5000) //
						.output(PLAN_REASON, "TOO_EXPENSIVE") //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase() //
						.timeleap(clock, 60, MINUTES) //
						.input(GRID_BUY_PRICE, 200.) //
						.output(TARGET_STATUS, "PLANNED") //
						.output(PLAN_REASON, "TARGET_CHARGE") //
						.output(AWAITING_HYSTERESIS, false) //
						.output(PRICE_CHARGING, true) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.deactivate();
	}

	@Test
	public void targetStartEnergyTest() throws Exception {
		// The target counts from switching on: the session energy at that moment is
		// stored in the configuration, a new session (counter reset) counts from zero
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 120) % (24 * 60);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 10000, targetTime, -1, cm) //
				.next(new TestCase("start not yet stored: the whole target is missing") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 22902) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "PLANNED") //
						.output(TARGET_REMAINING_ENERGY, 10000)) //
				.deactivate();
		assertEquals(22902, (int) (Integer) cm.getConfiguration("ctrlEvcs0").getProperties().get("targetStartEnergy"));

		final var cm2 = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 10000, targetTime, 22902, cm2) //
				.next(new TestCase("3 kWh charged since switching on") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 25902) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_REMAINING_ENERGY, 7000)) //
				.next(new TestCase("new session: the counter restarted at zero") //
						.input("evcs0", ENERGY_SESSION, 1000) //
						.output(TARGET_REMAINING_ENERGY, 9000)) //
				.next(new TestCase("target reached") //
						.input("evcs0", ENERGY_SESSION, 10000) //
						.output(TARGET_REMAINING_ENERGY, 0) //
						.output(TARGET_STATUS, "REACHED")) //
				.deactivate();
		// the target is reached: the switch goes off by itself
		assertEquals(false, cm2.getConfiguration("ctrlEvcs0").getProperties().get("targetCharging"));
	}

	@Test
	public void sessionLimitFromPlugInTest() throws Exception {
		// The wallbox counter keeps the last session until the next charging: the
		// controller stores the counter at the plug-in and sends counter + limit
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
						.setForceChargeMinPower(4140) //
						.setDefaultChargeMinPower(0) //
						.setPriority(CAR) //
						.setEnergySessionLimit(5000) //
						.setSessionStartEnergy(-1) //
						.build()) //
				.next(new TestCase("plugged in with an old counter: start is stored, limit sent as is") //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 22902) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(SESSION_ENERGY, 0) //
						.output("evcs0", SET_ENERGY_LIMIT, 5000)) //
				.deactivate();
		assertEquals(22902, (int) (Integer) cm.getConfiguration("ctrlEvcs0").getProperties().get("sessionStartEnergy"));

		cm = new DummyConfigurationAdmin();
		new ControllerTest(new ControllerEvcsPriceImpl()) //
				.addReference("cm", cm) //
				.addReference("sum", new DummySum()) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(4140) //
						.setDefaultChargeMinPower(0) //
						.setPriority(CAR) //
						.setEnergySessionLimit(5000) //
						.setSessionStartEnergy(22902) //
						.build()) //
				.next(new TestCase("3 kWh since the plug-in: the limit counts from the start") //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ENERGY_SESSION, 25902) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(SESSION_ENERGY, 3000) //
						.output("evcs0", SET_ENERGY_LIMIT, 27902)) //
				.next(new TestCase("the wallbox restarted its counter: the session counts from zero") //
						.input("evcs0", ENERGY_SESSION, 1000) //
						.output(SESSION_ENERGY, 1000) //
						.output("evcs0", SET_ENERGY_LIMIT, 5000)) //
				.next(new TestCase("unplugged: no session") //
						.input("evcs0", STATUS, Status.NOT_READY_FOR_CHARGING) //
						.output(SESSION_ENERGY, null)) //
				.deactivate();
		assertEquals(-1, (int) (Integer) cm.getConfiguration("ctrlEvcs0").getProperties().get("sessionStartEnergy"));
	}

	@Test
	public void targetPlanIsStoredAndFollowedTest() throws Exception {
		// The first plan goes to the configuration. A stored plan is followed even
		// if a fresh plan would wait for the cheaper second hour
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 120) % (24 * 60);
		final var deadline = ControllerEvcsPriceImpl.nextDeadline(now, targetTime);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = i >= 4 && i < 8 ? 200. : 500.;
		}
		var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 5000, targetTime, 0, cm) //
				.next(new TestCase("first plan: made and stored") //
						.input(GRID_BUY_PRICE, 500.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 0) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "PLANNED") //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.deactivate();
		final var stored = ControllerEvcsPriceImpl.StoredTargetPlan
				.decode((String) cm.getConfiguration("ctrlEvcs0").getProperties().get("targetPlan"));
		assertNotNull(stored);
		assertEquals(deadline.toEpochSecond(), stored.deadline());
		assertEquals(5000, stored.targetEnergy());
		assertTrue(stored.quarters() > 0);
		assertEquals(0, stored.powers()[0], "the expensive first quarter-hour is not planned");

		// a stored plan that charges right now, although the prices say wait
		final var quarter = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15);
		final var plan = new ControllerEvcsPriceImpl.StoredTargetPlan(now.toEpochSecond(), deadline.toEpochSecond(),
				5000, quarter.toEpochSecond(), new int[] { 11040, 8960, 0, 0, 0, 0, 0, 0 });
		cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 5000, targetTime, 0, cm, plan.encode()) //
				.next(new TestCase("the stored plan is followed") //
						.input(GRID_BUY_PRICE, 500.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 0) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "PLANNED") //
						.output(TARGET_PLAN_QUARTERS, 2) //
						.output(PLAN_REASON, "TARGET_CHARGE") //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.next(new TestCase("the car is unplugged: target charging switches off") //
						.input("evcs0", STATUS, Status.NOT_READY_FOR_CHARGING)) //
				.deactivate();
		assertEquals(plan.encode(), cm.getConfiguration("ctrlEvcs0").getProperties().get("targetPlan"),
				"the plan is not made again");
		assertEquals(false, cm.getConfiguration("ctrlEvcs0").getProperties().get("targetCharging"));
	}

	@Test
	public void targetPlanMadeAgainOnDeviationTest() throws Exception {
		// The stored plan charged in the past hour, but the car took nothing: the
		// missing energy deviates from the plan, so the rest is planned again
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 120) % (24 * 60);
		final var deadline = ControllerEvcsPriceImpl.nextDeadline(now, targetTime);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var quarter = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15);
		final var old = new ControllerEvcsPriceImpl.StoredTargetPlan(now.minusHours(1).toEpochSecond(),
				deadline.toEpochSecond(), 5000, quarter.minusHours(1).toEpochSecond(),
				new int[] { 11040, 8960, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		final var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 5000, targetTime, 0, cm, old.encode()) //
				.next(new TestCase("nothing charged: planned again") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 0) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "PLANNED") //
						.output(PLAN_ENERGY, 5000)) //
				.deactivate();
		final var made = ControllerEvcsPriceImpl.StoredTargetPlan
				.decode((String) cm.getConfiguration("ctrlEvcs0").getProperties().get("targetPlan"));
		assertNotNull(made);
		assertTrue(made.plannedAt() > old.plannedAt(), "a new plan");
		assertTrue(made.start() >= quarter.toEpochSecond(), "from now on");
		assertTrue(made.quarters() > 0);
	}

	@Test
	public void targetPlanMadeAgainWhenItNoLongerCoversTheRestTest() throws Exception {
		// The car charged a little less than the plan expected, within the tolerance,
		// but the rest of the plan no longer covers the missing energy: planned again
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 180) % (24 * 60);
		final var deadline = ControllerEvcsPriceImpl.nextDeadline(now, targetTime);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var quarter = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15);
		// two quarter-hours charged at minimum power, one more planned for 1035 Wh
		final var old = new ControllerEvcsPriceImpl.StoredTargetPlan(now.minusHours(1).toEpochSecond(),
				deadline.toEpochSecond(), 3000, quarter.minusMinutes(30).toEpochSecond(),
				new int[] { 4140, 4140, 4140, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		final var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 3000, targetTime, 0, cm, old.encode()) //
				.next(new TestCase("1800 of 2070 Wh charged: 1200 Wh missing, the plan covers 1035 Wh") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 1800) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "PLANNED") //
						.output(TARGET_REMAINING_ENERGY, 1200)) //
				.deactivate();
		final var made = ControllerEvcsPriceImpl.StoredTargetPlan
				.decode((String) cm.getConfiguration("ctrlEvcs0").getProperties().get("targetPlan"));
		assertNotNull(made);
		assertTrue(made.plannedAt() > old.plannedAt(), "a new plan");
		assertTrue(made.remainingFrom(quarter) >= 1200, "the new plan covers the rest");
	}

	@Test
	public void targetPlanCoveringTheRestIsNotMadeAgainTest() throws Exception {
		// The car charged a little less than the plan expected, but the rest of the
		// plan still covers the missing energy: the plan stays
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 180) % (24 * 60);
		final var deadline = ControllerEvcsPriceImpl.nextDeadline(now, targetTime);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var quarter = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15);
		final var old = new ControllerEvcsPriceImpl.StoredTargetPlan(now.minusHours(1).toEpochSecond(),
				deadline.toEpochSecond(), 3000, quarter.minusMinutes(30).toEpochSecond(),
				new int[] { 4140, 4140, 4140, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		final var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 3000, targetTime, 0, cm, old.encode()) //
				.next(new TestCase("2000 of 2070 Wh charged: 1000 Wh missing, the plan covers 1035 Wh") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 2000) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "PLANNED") //
						.output(TARGET_REMAINING_ENERGY, 1000)) //
				.deactivate();
		assertEquals(old.encode(), cm.getConfiguration("ctrlEvcs0").getProperties().get("targetPlan"));
	}

	@Test
	public void targetPlanMadeAgainWithTheCarPowerTest() throws Exception {
		// The plan gives the car 11 kW, the car takes 4 kW for two minutes: the rest
		// is planned again with the car power, the plan remembers it
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 240) % (24 * 60);
		final var deadline = ControllerEvcsPriceImpl.nextDeadline(now, targetTime);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var quarter = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15);
		final var old = new ControllerEvcsPriceImpl.StoredTargetPlan(now.minusMinutes(20).toEpochSecond(),
				deadline.toEpochSecond(), 9000, quarter.toEpochSecond(),
				new int[] { 11040, 11040, 11040, 2880, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		final var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 9000, targetTime, 0, cm, old.encode()) //
				.next(new TestCase("the car takes 4 kW of 11 kW") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ENERGY_SESSION, 500) //
						.input(GRID_ACTIVE_POWER, 5000) //
						.input("evcs0", ACTIVE_POWER, 4050) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "PLANNED")) //
				.next(new TestCase("still after one minute: the plan stays") //
						.timeleap(clock, 1, MINUTES) //
						.output(TARGET_STATUS, "PLANNED")) //
				.next(new TestCase("after two minutes: planned again with 4 kW") //
						.timeleap(clock, 70, SECONDS) //
						.output(TARGET_STATUS, "PLANNED")) //
				.deactivate();
		final var made = ControllerEvcsPriceImpl.StoredTargetPlan
				.decode((String) cm.getConfiguration("ctrlEvcs0").getProperties().get("targetPlan"));
		assertNotNull(made);
		assertTrue(made.plannedAt() > old.plannedAt(), "a new plan");
		assertEquals(4140, made.carPower());
		// the current quarter-hour goes on as planned, the rest with the car power
		assertEquals(11040, made.powers()[0]);
		for (var i = 1; i < made.powers().length; i++) {
			assertTrue(made.powers()[i] <= 4140, "no quarter-hour above the car power: " + made.powers()[i]);
		}
		assertTrue(made.remainingFrom(quarter) >= 8500 - 4140 / 4, "the new plan covers the rest");
	}

	@Test
	public void priceCarPowerMeasuredTest() throws Exception {
		// Price charging offers 11 kW, the car takes 4 kW for two minutes: the plan
		// is made with the car power at once and reaches the optimizer; takes the car
		// 9 kW later, the hardware counts again
		final var clock = createDummyClock();
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var controller = new ControllerEvcsPriceImpl(clock);
		preparePricePlanTest(clock, controller, prices) //
				.next(new TestCase("the car takes 4 kW of the offered 11 kW") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ENERGY_SESSION, 500) //
						.input(GRID_ACTIVE_POWER, 5000) //
						.input("evcs0", ACTIVE_POWER, 4050) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.next(new TestCase("after one minute: the plan still has the hardware") //
						.timeleap(clock, 1, MINUTES) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) //
						.onAfterControllersCallbacks(() -> {
							final var plan = controller.getDisplayPlan();
							assertTrue(plan.quarters().stream().anyMatch(q -> q.power() > 4140), "hardware power");
						})) //
				.next(new TestCase("after two minutes since the offer: the plan is made with 4 kW, the offer stays") //
						.timeleap(clock, 130, SECONDS) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) //
						.onAfterControllersCallbacks(() -> {
							final var plan = controller.getDisplayPlan();
							assertTrue(plan.energyWh() > 0, "a plan");
							assertTrue(plan.quarters().stream().allMatch(q -> q.power() <= 4140), "car power");
							final var esh = controller.energySchedulerConfig();
							assertNotNull(esh);
							assertTrue(esh.charging(), "the plan counts for the optimizer");
							assertTrue(esh.planWh().stream().allMatch(wh -> wh <= 4140 / 4), "car power");
						})) //
				.next(new TestCase("the car takes 9 kW") //
						.input("evcs0", ACTIVE_POWER, 9000)) //
				.next(new TestCase("after two minutes: the hardware counts again") //
						.timeleap(clock, 140, SECONDS) //
						.onAfterControllersCallbacks(() -> {
							final var plan = controller.getDisplayPlan();
							assertTrue(plan.quarters().stream().anyMatch(q -> q.power() > 4140), "hardware power");
						})) //
				.deactivate();
	}

	@Test
	public void carTakesNothingTest() throws Exception {
		// Price charging offers 11 kW to a plugged-in car that takes nothing: the plan
		// counts for the optimizer for 45 minutes, then not; the offer stays. Power
		// again: the plan counts again
		final var clock = createDummyClock();
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var controller = new ControllerEvcsPriceImpl(clock);
		preparePricePlanTest(clock, controller, prices) //
				.next(new TestCase("plugged in, nothing taken") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 500) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) //
						.output(CAR_TAKES_NOTHING, false)) //
				.next(new TestCase("the plan counts as soon as the car is plugged in") //
						.timeleap(clock, 1, SECONDS) //
						.onAfterControllersCallbacks(() -> {
							final var esh = controller.energySchedulerConfig();
							assertNotNull(esh);
							assertTrue(esh.charging(), "the plan counts for the optimizer");
						})) //
				.next(new TestCase("after 44 minutes: still") //
						.timeleap(clock, 44, MINUTES) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) //
						.output(CAR_TAKES_NOTHING, false) //
						.onAfterControllersCallbacks(() -> {
							assertTrue(controller.energySchedulerConfig().charging());
						})) //
				.next(new TestCase("after 46 minutes: the car takes nothing, the offer stays") //
						.timeleap(clock, 2, MINUTES) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) //
						.output(CAR_TAKES_NOTHING, true) //
						.onAfterControllersCallbacks(() -> {
							assertFalse(controller.energySchedulerConfig().charging(), "no consumption for the optimizer");
						})) //
				.next(new TestCase("the car takes power again") //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ACTIVE_POWER, 5000) //
						.output(CAR_TAKES_NOTHING, false) //
						.onAfterControllersCallbacks(() -> {
							assertTrue(controller.energySchedulerConfig().charging(), "the plan counts again");
						})) //
				.deactivate();
	}

	@Test
	public void carTakesNothingCountPausesWithoutOfferTest() throws Exception {
		// The 45 minutes count the time offered in total: 30 minutes offered, 30
		// minutes nothing offered (too expensive), then 16 minutes offered again
		final var clock = createDummyClock();
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var controller = new ControllerEvcsPriceImpl(clock);
		preparePricePlanTest(clock, controller, prices) //
				.next(new TestCase("plugged in, nothing taken") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 500) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.next(new TestCase("offered") //
						.timeleap(clock, 1, SECONDS)) //
				.next(new TestCase("after 30 minutes offered: too expensive, nothing offered") //
						.timeleap(clock, 30, MINUTES) //
						.input(GRID_BUY_PRICE, 900.) //
						.output(CAR_TAKES_NOTHING, false)) //
				.next(new TestCase("nothing offered") //
						.timeleap(clock, 1, SECONDS) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase("30 minutes without offer: the count pauses") //
						.timeleap(clock, 30, MINUTES) //
						.output(CAR_TAKES_NOTHING, false)) //
				.next(new TestCase("cheap again: offered") //
						.timeleap(clock, 1, SECONDS) //
						.input(GRID_BUY_PRICE, 200.) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.next(new TestCase("offered") //
						.timeleap(clock, 1, SECONDS)) //
				.next(new TestCase("14 more minutes offered: 44 in total") //
						.timeleap(clock, 14, MINUTES) //
						.output(CAR_TAKES_NOTHING, false)) //
				.next(new TestCase("2 more minutes offered: 46 in total, the car takes nothing") //
						.timeleap(clock, 2, MINUTES) //
						.output(CAR_TAKES_NOTHING, true) //
						.onAfterControllersCallbacks(() -> {
							assertFalse(controller.energySchedulerConfig().charging(), "no consumption for the optimizer");
						})) //
				.deactivate();
	}

	@Test
	public void targetPlanKeptWhileTheCarTakesNothingTest() throws Exception {
		// Target charging: the car takes nothing for 46 minutes: target charging and
		// its plan stay, only the optimizer gets nothing; power again: it counts again
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 240) % (24 * 60);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var controller = new ControllerEvcsPriceImpl(clock);
		final var cm = new DummyConfigurationAdmin();
		// more than fits until the target time: every quarter-hour charges
		prepareTargetTest(clock, controller, prices, 60000, targetTime, 0, cm) //
				.next(new TestCase("plugged in, nothing taken") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 500) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "UNREACHABLE")) //
				.next(new TestCase("the plan starts with the next quarter-hour") //
						.timeleap(clock, 15, MINUTES) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.next(new TestCase("the plan counts") //
						.timeleap(clock, 1, SECONDS) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) //
						.onAfterControllersCallbacks(() -> {
							assertTrue(controller.energySchedulerConfig().charging());
						})) //
				.next(new TestCase("after 46 minutes: the car takes nothing, target charging stays") //
						.timeleap(clock, 46, MINUTES) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040) //
						.output(CAR_TAKES_NOTHING, true) //
						.output(TARGET_STATUS, "UNREACHABLE") //
						.onAfterControllersCallbacks(() -> {
							assertFalse(controller.energySchedulerConfig().charging(), "no consumption for the optimizer");
						})) //
				.next(new TestCase("the car takes power again") //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ACTIVE_POWER, 9000) //
						.output(CAR_TAKES_NOTHING, false) //
						.onAfterControllersCallbacks(() -> {
							assertTrue(controller.energySchedulerConfig().charging(), "the plan counts again");
						})) //
				.deactivate();
		assertEquals(true, cm.getConfiguration("ctrlEvcs0").getProperties().get("targetCharging"));
	}

	@Test
	public void targetPlanWithTheCarPowerIsNotMadeAgainInTheSameQuarterTest() throws Exception {
		// A plan made with the car power keeps the current quarter-hour at the old
		// 11 kW; the car still takes 4 kW: measured against the car power, no new plan
		final var clock = createDummyClock();
		clock.leap(3, MINUTES);
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 240) % (24 * 60);
		final var deadline = ControllerEvcsPriceImpl.nextDeadline(now, targetTime);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var quarter = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15);
		final var made = new ControllerEvcsPriceImpl.StoredTargetPlan(now.minusMinutes(1).toEpochSecond(),
				deadline.toEpochSecond(), 9000, quarter.toEpochSecond(),
				new int[] { 11040, 4140, 4140, 4140, 4140, 4140, 4140, 4140, 0, 0, 0, 0, 0, 0, 0, 0 }, 4140);
		final var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 9000, targetTime, 0, cm, made.encode()) //
				.next(new TestCase("the car takes 4 kW of the kept 11 kW") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ENERGY_SESSION, 200) //
						.input(GRID_ACTIVE_POWER, 5000) //
						.input("evcs0", ACTIVE_POWER, 4050) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.next(new TestCase("still after three minutes: the plan stays") //
						.timeleap(clock, 3, MINUTES) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.deactivate();
		assertEquals(made.encode(), cm.getConfiguration("ctrlEvcs0").getProperties().get("targetPlan"));
	}

	@Test
	public void targetPlanInTheLastQuarterIsNotMadeAgainTest() throws Exception {
		// The target time is the end of the current quarter-hour: the car takes less
		// than planned, but there is no quarter-hour left to plan; the plan goes on
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 15) % (24 * 60);
		final var deadline = ControllerEvcsPriceImpl.nextDeadline(now, targetTime);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var quarter = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15);
		final var made = new ControllerEvcsPriceImpl.StoredTargetPlan(now.minusMinutes(20).toEpochSecond(),
				deadline.toEpochSecond(), 3000, quarter.minusMinutes(15).toEpochSecond(),
				new int[] { 11040, 11040 }, 0);
		final var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 3000, targetTime, 0, cm, made.encode()) //
				.next(new TestCase("the car takes 4 kW of 11 kW in the last quarter-hour") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ENERGY_SESSION, 0) //
						.input(GRID_ACTIVE_POWER, 5000) //
						.input("evcs0", ACTIVE_POWER, 4050) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.next(new TestCase("still after three minutes: the plan goes on") //
						.timeleap(clock, 3, MINUTES) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.deactivate();
		assertEquals(made.encode(), cm.getConfiguration("ctrlEvcs0").getProperties().get("targetPlan"));
	}

	@Test
	public void targetTimePassedWhileOffSwitchesOffTest() throws Exception {
		// The stored plan's target time passed while the controller was off: it
		// switches target charging off instead of planning for the next day
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 24 * 60 - 60) % (24 * 60); // an hour ago
		final var passed = now.minusHours(1);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var old = new ControllerEvcsPriceImpl.StoredTargetPlan(now.minusHours(3).toEpochSecond(),
				passed.toEpochSecond(), 5000, now.minusHours(3).toEpochSecond(),
				new int[] { 0, 11040, 11040, 0, 0, 0, 0, 0 }, 0);
		final var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 5000, targetTime, 0, cm, old.encode()) //
				.next(new TestCase("target time passed: off") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 2000) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.deactivate();
		assertEquals(false, cm.getConfiguration("ctrlEvcs0").getProperties().get("targetCharging"));
	}

	@Test
	public void targetPlanInTheLastQuarterIsNotMadeAgainOnDeviationTest() throws Exception {
		// The last quarter-hour before the target time begins and the car is far
		// behind the plan: there is no quarter-hour left to plan, the plan goes on
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 15) % (24 * 60);
		final var deadline = ControllerEvcsPriceImpl.nextDeadline(now, targetTime);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var quarter = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15);
		final var made = new ControllerEvcsPriceImpl.StoredTargetPlan(now.minusMinutes(20).toEpochSecond(),
				deadline.toEpochSecond(), 5000, quarter.minusMinutes(15).toEpochSecond(),
				new int[] { 11040, 4140 }, 4140);
		final var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 5000, targetTime, 0, cm, made.encode()) //
				.next(new TestCase("1 of 2.76 kWh charged: the plan goes on with 4140 W") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ENERGY_SESSION, 1000) //
						.input(GRID_ACTIVE_POWER, 5000) //
						.input("evcs0", ACTIVE_POWER, 4000) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140)) //
				.next(new TestCase("a second later still") //
						.timeleap(clock, 1, SECONDS) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140)) //
				.deactivate();
		assertEquals(made.encode(), cm.getConfiguration("ctrlEvcs0").getProperties().get("targetPlan"));
	}

	@Test
	public void targetPlanMadeMidQuarterIsNotMadeAgainTest() throws Exception {
		// A plan made 10 minutes into the quarter-hour holds only a third of the
		// first quarter-hour; followed in the same quarter-hour it is not made again
		final var clock = createDummyClock();
		clock.leap(10, MINUTES);
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 120) % (24 * 60);
		final var deadline = ControllerEvcsPriceImpl.nextDeadline(now, targetTime);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		final var quarter = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15);
		// 11040 W for the remaining 5 minutes = 920 Wh, then 4 x 1020 Wh
		final var plan = new ControllerEvcsPriceImpl.StoredTargetPlan(now.toEpochSecond(), deadline.toEpochSecond(),
				5000, quarter.toEpochSecond(), new int[] { 11040, 4080, 4080, 4080, 4080, 0, 0, 0 });
		assertEquals(920 + 4080, plan.remainingFrom(quarter));
		final var cm = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 5000, targetTime, 0, cm, plan.encode()) //
				.next(new TestCase("followed, not made again") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 0) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_PLAN_QUARTERS, 5) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 11040)) //
				.deactivate();
		assertEquals(plan.encode(), cm.getConfiguration("ctrlEvcs0").getProperties().get("targetPlan"));
	}

	@Test
	public void targetStorageLockTest() throws Exception {
		// Storage at 50 % of 20 kWh, evening target 85 %: target charging locks it
		// for the car, the discharge is capped to the house load without the car.
		// At 95 % the lock is released
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 120) % (24 * 60);
		final var prices = new Double[96];
		for (var i = 0; i < prices.length; i++) {
			prices[i] = 200.;
		}
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 5000, targetTime) //
				.next(new TestCase("locked: cap = house 2500 W - car 0 W") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(CONSUMPTION_ACTIVE_POWER, 2500) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(STORAGE_LOCKED, true) //
						.output(STORAGE_DISCHARGE_LIMIT, 2500) //
						.output("ess0", SET_ACTIVE_POWER_LESS_OR_EQUALS, 2500)) //
				.next(new TestCase("car charges 11 kW: the cap stays the house load") //
						.input(CONSUMPTION_ACTIVE_POWER, 13540) //
						.input("evcs0", ACTIVE_POWER, 11040) //
						.output(STORAGE_DISCHARGE_LIMIT, 2500) //
						.output("ess0", SET_ACTIVE_POWER_LESS_OR_EQUALS, 2500)) //
				.next(new TestCase("storage above the target: released") //
						.input(ESS_SOC, 95) //
						.output(STORAGE_LOCKED, false) //
						.output(STORAGE_DISCHARGE_LIMIT, null) //
						.output("ess0", SET_ACTIVE_POWER_LESS_OR_EQUALS, null)) //
				.deactivate();
	}

	@Test
	public void targetChargingWithoutPricesTest() throws Exception {
		// Prices for one hour only, target in two hours: nothing is planned blind
		final var clock = createDummyClock();
		final var now = ZonedDateTime.now(clock);
		final var targetTime = (now.getHour() * 60 + now.getMinute() + 120) % (24 * 60);
		final var prices = new Double[] { 200., 200., 200., 200. };
		final var controller = new ControllerEvcsPriceImpl(clock);
		prepareTargetTest(clock, controller, prices, 5000, targetTime) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "PRICES_MISSING") //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.deactivate();
	}

	private static ControllerTest prepareTargetTest(TimeLeapClock clock, ControllerEvcsPriceImpl controller,
			Double[] prices, int targetEnergy, int targetTime) throws Exception {
		return prepareTargetTest(clock, controller, prices, targetEnergy, targetTime, -1, new DummyConfigurationAdmin());
	}

	private static ControllerTest prepareTargetTest(TimeLeapClock clock, ControllerEvcsPriceImpl controller,
			Double[] prices, int targetEnergy, int targetTime, int targetStartEnergy, DummyConfigurationAdmin cm)
			throws Exception {
		return prepareTargetTest(clock, controller, prices, targetEnergy, targetTime, targetStartEnergy, cm, "");
	}

	private static ControllerTest prepareTargetTest(TimeLeapClock clock, ControllerEvcsPriceImpl controller,
			Double[] prices, int targetEnergy, int targetTime, int targetStartEnergy, DummyConfigurationAdmin cm,
			String targetPlan) throws Exception {
		return prepareModeTest(clock, controller, prices, true, targetEnergy, targetTime, targetStartEnergy, cm,
				targetPlan);
	}

	private static ControllerTest preparePricePlanTest(TimeLeapClock clock, ControllerEvcsPriceImpl controller,
			Double[] prices) throws Exception {
		return prepareModeTest(clock, controller, prices, false, 0, 0, -1, new DummyConfigurationAdmin(), "");
	}

	private static ControllerTest prepareModeTest(TimeLeapClock clock, ControllerEvcsPriceImpl controller,
			Double[] prices, boolean targetCharging, int targetEnergy, int targetTime, int targetStartEnergy,
			DummyConfigurationAdmin cm, String targetPlan) throws Exception {
		final var now = Instant.now(clock);
		final var componentManager = new DummyComponentManager(clock);
		final var sum = new DummySum();
		final var production = new Integer[prices.length];
		final var consumption = new Integer[prices.length];
		for (var i = 0; i < prices.length; i++) {
			production[i] = 0;
			consumption[i] = 1000;
		}
		final var predictorManager = new DummyPredictorManager(
				new DummyPredictor("predictor0", componentManager,
						Prediction.from(sum, SUM_PRODUCTION, now, production), SUM_PRODUCTION),
				new DummyPredictor("predictor1", componentManager,
						Prediction.from(sum, SUM_CONSUMPTION, now, consumption), SUM_CONSUMPTION));
		return new ControllerTest(controller) //
				.addReference("cm", cm) //
				.addReference("componentManager", componentManager) //
				.addReference("sum", sum) //
				.addReference("predictorManager", predictorManager) //
				.addReference("timeOfUseTariff", DummyTimeOfUseTariffProvider.fromQuarterlyPrices(clock, prices)) //
				.addReference("evcs", DummyManagedEvcs.ofDisabled("evcs0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0")) //
				.activate(MyConfig.create() //
						.setId("ctrlEvcs0") //
						.setEvcsId("evcs0") //
						.setEssId("ess0") //
						.setEnableCharging(true) //
						.setChargeMode(EXCESS_POWER) //
						.setForceChargeMinPower(3680) //
						.setDefaultChargeMinPower(0) //
						.setPriority(CAR) //
						.setEnergySessionLimit(0) //
						.setEnablePriceCharging(true) //
						.setPriceLimit(30) //
						.setPriceLimitFullPower(20) //
						.setPriceChargePower(11040) //
						.setUseStorageSurplus(false) //
						.setTargetCharging(targetCharging) //
						.setTargetEnergy(targetEnergy) //
						.setTargetTime(targetTime) //
						.setTargetStartEnergy(targetStartEnergy) //
						.setTargetPlan(targetPlan) //
						.build());
	}

	@Test
	public void isPluggedInTest() {
		// without plug state: from the status
		assertTrue(ControllerEvcsPriceImpl.isPluggedIn(Status.READY_FOR_CHARGING, null));
		assertFalse(ControllerEvcsPriceImpl.isPluggedIn(Status.NOT_READY_FOR_CHARGING, null));
		// Keba with a car plugged in while no power was offered
		assertTrue(ControllerEvcsPriceImpl.isPluggedIn(Status.NOT_READY_FOR_CHARGING, 7));
		assertTrue(ControllerEvcsPriceImpl.isPluggedIn(Status.NOT_READY_FOR_CHARGING, 5));
		// cable only on the wallbox
		assertFalse(ControllerEvcsPriceImpl.isPluggedIn(Status.NOT_READY_FOR_CHARGING, 3));
		assertFalse(ControllerEvcsPriceImpl.isPluggedIn(Status.READY_FOR_CHARGING, 0));
	}
}
