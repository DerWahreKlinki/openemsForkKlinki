package io.openems.edge.controller.evcs.price;

import static io.openems.common.test.TestUtils.createDummyClock;
import static io.openems.edge.common.sum.Sum.ChannelId.CONSUMPTION_ACTIVE_POWER;
import static io.openems.edge.common.sum.Sum.ChannelId.ESS_CAPACITY;
import static io.openems.edge.common.sum.Sum.ChannelId.ESS_DISCHARGE_POWER;
import static io.openems.edge.common.sum.Sum.ChannelId.ESS_MAX_APPARENT_POWER;
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
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_BEHIND_PLAN;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_BUDGET_WITHHELD;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_DISCHARGE_BLOCKED;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_CHARGE_ROOM;
import static io.openems.edge.controller.evcs.price.ControllerEvcsPrice.ChannelId.STORAGE_DISPLACED_POWER;
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
import static io.openems.edge.meter.api.ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY;
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
			BALANCING(1, "Balancing"), DELAY_DISCHARGE(0, "Delay discharge"), CHARGE_GRID(3, "Charge from grid"),
			LIMIT_CHARGE(7, "Limit charge");

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
	public void storagePriorityLeavesTheStorageItsRoomTest() throws Exception {
		// Priority STORAGE without storage share: the car charges 4404 W, grid 0, the
		// storage charges 1600 W and could take 9000 W. The measured surplus (4204 W)
		// is what the car draws itself; the storage could charge it, so it is not
		// the car's: no surplus, the storage share has no budget and costs the grid
		// price -> stop. Without room the car keeps the measured surplus as before
		prepareStoragePriceTest(createDummyClock(), false, true, STORAGE, 12000, 2000, 96) //
				.next(new TestCase("the storage charges at its limit: the surplus is the car's") //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_MAX_APPARENT_POWER, 9000) //
						.input(ESS_DISCHARGE_POWER, -9000) //
						.input(CONSUMPTION_ACTIVE_POWER, 6404) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ACTIVE_POWER, 4404) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_CHARGE_ROOM, 0) //
						.output(STORAGE_DISPLACED_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4204) //
						.output(PRICE_CHARGING, false)) //
				.next(new TestCase("the storage could charge 7400 W more: the car's draw is no surplus") //
						.input(ESS_DISCHARGE_POWER, -1600) //
						.output(STORAGE_CHARGE_ROOM, 7400) //
						.output(STORAGE_DISPLACED_POWER, 4204) //
						.output(STORAGE_POWER_FOR_CAR, 0) //
						.output(BLENDED_PRICE, 431.0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(PRICE_CHARGING, false)) //
				.deactivate();
	}

	@Test
	public void displacedSurplusCountsAsStorageShareTest() throws Exception {
		// Like storagePriorityFollowsMeasuredSurplusTest, but the storage could take
		// 9000 W: all of the car's draw is surplus the storage could charge. The plan
		// gives the car a storage budget (the storage reaches its target anyway), so
		// the displaced surplus costs the storage price and the car fills up to the
		// minimum power instead of following its own draw
		prepareStoragePriceTest(createDummyClock(), true, true, STORAGE, 12000, 2000, 96) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_MAX_APPARENT_POWER, 9000) //
						.input(ESS_DISCHARGE_POWER, -1600) //
						.input(CONSUMPTION_ACTIVE_POWER, 6404) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ACTIVE_POWER, 4404) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_TARGET_REACHABLE, true) //
						.output(STORAGE_PRICE, 80.) //
						.output(STORAGE_CHARGE_ROOM, 7400) //
						.output(STORAGE_DISPLACED_POWER, 4204) //
						.output(STORAGE_POWER_FOR_CAR, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(PRICE_CHARGING, true)) //
				.deactivate();
	}

	@Test
	public void storageRoomFollowsTheTimeOfUseTariffStateTest() throws Exception {
		// What the storage would do with the surplus depends on the Time-of-Use-Tariff
		// controller: while it limits the charging the storage takes nothing, the
		// measured surplus stays the car's; in BALANCING and while the discharge is
		// delayed the storage charges from surplus and keeps its room
		final var clock = createDummyClock();
		final var tou = new DummyTimeOfUseTariff("ctrlToU0");
		prepareStoragePriceTest(clock, false, true, STORAGE, tou, 12000, 2000, 96) //
				.next(new TestCase("limited charging: the storage takes no surplus") //
						.input("ctrlToU0", DummyTimeOfUseTariff.ChannelId.STATE_MACHINE,
								DummyTimeOfUseTariff.State.LIMIT_CHARGE) //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_MAX_APPARENT_POWER, 9000) //
						.input(ESS_DISCHARGE_POWER, -1600) //
						.input(CONSUMPTION_ACTIVE_POWER, 6404) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ACTIVE_POWER, 4404) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_CHARGE_ROOM, 0) //
						.output(STORAGE_DISPLACED_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4204)) //
				.next(new TestCase("balancing: the storage would charge the surplus") //
						.input("ctrlToU0", DummyTimeOfUseTariff.ChannelId.STATE_MACHINE,
								DummyTimeOfUseTariff.State.BALANCING) //
						.output(STORAGE_CHARGE_ROOM, 7400) //
						.output(STORAGE_DISPLACED_POWER, 4204) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.next(new TestCase("delayed discharge: the storage still charges from surplus") //
						.input("ctrlToU0", DummyTimeOfUseTariff.ChannelId.STATE_MACHINE,
								DummyTimeOfUseTariff.State.DELAY_DISCHARGE) //
						.output(STORAGE_CHARGE_ROOM, 7400) //
						.output(STORAGE_DISPLACED_POWER, 4204) //
						.output(STORAGE_DISCHARGE_BLOCKED, true) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.deactivate();
	}

	@Test
	public void measuredChargingIsReportedToTheOptimizerTest() throws Exception {
		// The car charges 4404 W on measured surplus. The optimizer gets the plan,
		// and the running and the next quarter-hour carry at least the measured
		// power (1101 Wh per quarter-hour)
		final var clock = createDummyClock();
		final var controller = new ControllerEvcsPriceImpl(clock);
		prepareStoragePriceTest(clock, controller, true, true, STORAGE, null, 12000, 2000, 96) //
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
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4204) //
						.onAfterControllersCallbacks(() -> {
							final var config = controller.energySchedulerConfig();
							assertNotNull(config);
							assertTrue(config.charging());
							assertEquals(Instant.now(clock).getEpochSecond(), config.planStart());
							assertTrue(config.planWh().size() >= 2);
							assertTrue(config.planWh().get(0) >= 1101, "running quarter-hour " + config.planWh().get(0));
							assertTrue(config.planWh().get(1) >= 1101, "next quarter-hour " + config.planWh().get(1));
						})) //
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
				// the storage charged on meanwhile (53 %), it is on the path of the plan
				.next(new TestCase("house load gone") //
						.timeleap(clock, 1, MINUTES) //
						.input(CONSUMPTION_ACTIVE_POWER, 5140) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input(ESS_SOC, 53) //
						.output(STORAGE_BUDGET_WITHHELD, false) //
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
						// the storage charged as the plan expected: no deficit carried
						.input(ESS_SOC, 55) //
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
	public void storageBudgetWithheldBehindPlanTest() throws Exception {
		// Priority STORAGE, forecast 12 kW production, 2 kW consumption, storage 30
		// kWh at 50 %: the plan lets the car fill up with 4140 W from the storage
		// budget and expects the storage to charge 5860 W, 1465 Wh per quarter-hour.
		// Live the storage stays at 50 % (clouds the forecast did not see): once it
		// lies more than 2 % of the capacity (600 Wh) behind that path, the budget
		// is withheld, the car gets the surplus only and stops through the stop
		// hysteresis. The next quarter-hour plans anew from the measured storage,
		// but its path starts where the old plan expected the storage: the deficit
		// carries over, the hysteresis runs on and the stop goes through
		final var clock = createDummyClock();
		prepareStoragePriceTest(clock, new ControllerEvcsPriceImpl(clock), true, true, STORAGE, null, 12000, 2000,
				96, 600) //
				.next(new TestCase("on the path at the start of the quarter-hour") //
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
						.output(PLAN_REASON, "FILL_UP") //
						.output(STORAGE_BEHIND_PLAN, 0) //
						.output(STORAGE_BUDGET_WITHHELD, false) //
						.output(STORAGE_PRICE, 80.) //
						.output(STORAGE_POWER_FOR_CAR, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase("5 minutes later: 488 Wh behind, within the tolerance") //
						.timeleap(clock, 5, MINUTES) //
						.output(STORAGE_BEHIND_PLAN, 488) //
						.output(STORAGE_BUDGET_WITHHELD, false) //
						.output(STORAGE_PRICE, 80.) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(AWAITING_HYSTERESIS, false)) //
				// 977 Wh behind: budget withheld, the storage counts like grid, the
				// blended price is the grid price -> stop wanted, the stop hysteresis
				// runs (the balance took the 5 minutes since the last cycle)
				.next(new TestCase("10 minutes later: 977 Wh behind, budget withheld") //
						.timeleap(clock, 5, MINUTES) //
						.output(STORAGE_BEHIND_PLAN, 977) //
						.output(STORAGE_BUDGET_WITHHELD, true) //
						.output(STORAGE_PRICE, 431.) //
						.output(STORAGE_POWER_FOR_CAR, 4140) //
						.output(BLENDED_PRICE, 431.0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(AWAITING_HYSTERESIS, true) //
						.output(HYSTERESIS_REMAINING, 300)) //
				.next(new TestCase("14 minutes later: still behind, hysteresis runs on") //
						.timeleap(clock, 4, MINUTES) //
						.output(STORAGE_BEHIND_PLAN, 1367) //
						.output(STORAGE_BUDGET_WITHHELD, true) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(AWAITING_HYSTERESIS, true) //
						.output(HYSTERESIS_REMAINING, 60)) //
				// the next quarter-hour: a new plan from the measured 50 %, its path
				// starts at the 1465 Wh the old plan expected by now -> still behind, the
				// budget stays withheld, the hysteresis runs out -> stop
				.next(new TestCase("next quarter-hour: new plan carries the deficit -> stop") //
						.timeleap(clock, 1, MINUTES) //
						.output(STORAGE_BEHIND_PLAN, 1465) //
						.output(STORAGE_BUDGET_WITHHELD, true) //
						.output(STORAGE_PRICE, 431.) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, false)) //
				// the clouds stay: the new plan expects 1465 Wh more at the end of the
				// quarter-hour, the storage does not move -> the deficit stays
				.next(new TestCase("5 minutes into the next quarter-hour: the deficit stays") //
						.timeleap(clock, 5, MINUTES) //
						.output(STORAGE_BEHIND_PLAN, 1465) //
						.output(STORAGE_BUDGET_WITHHELD, true) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				// the sun is back: the storage charges to 54 %, 1200 Wh of the carried
				// deficit are made up, 265 Wh remain -> within the tolerance, released
				.next(new TestCase("10 minutes into the next quarter-hour at 54 %: released") //
						.timeleap(clock, 5, MINUTES) //
						.input(ESS_SOC, 54) //
						.output(STORAGE_BEHIND_PLAN, 265) //
						.output(STORAGE_BUDGET_WITHHELD, false) //
						.output(STORAGE_PRICE, 80.)) //
				.deactivate();
	}

	@Test
	public void storageAheadOfPlanKeepsBudgetTest() throws Exception {
		// The storage charges faster than the plan expected (more sun): never behind,
		// the budget stays
		final var clock = createDummyClock();
		prepareStoragePriceTest(clock, new ControllerEvcsPriceImpl(clock), true, true, STORAGE, null, 12000, 2000,
				96, 600) //
				.next(new TestCase() //
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
						.output(STORAGE_BEHIND_PLAN, 0) //
						.output(STORAGE_BUDGET_WITHHELD, false)) //
				.next(new TestCase("10 minutes later at 55 %: ahead of the path") //
						.timeleap(clock, 10, MINUTES) //
						.input(ESS_SOC, 55) //
						.output(STORAGE_BEHIND_PLAN, 0) //
						.output(STORAGE_BUDGET_WITHHELD, false) //
						.output(STORAGE_PRICE, 80.) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140) //
						.output(AWAITING_HYSTERESIS, false)) //
				// a SoC step down to 53 % right at the quarter-hour: the old plan expected
				// 54.9 %, the 565 Wh carry into the path of the new plan, within the
				// tolerance
				.next(new TestCase("next quarter-hour at 53 %") //
						.timeleap(clock, 5, MINUTES) //
						.input(ESS_SOC, 53) //
						.output(STORAGE_BEHIND_PLAN, 565) //
						.output(STORAGE_BUDGET_WITHHELD, false)) //
				// the usable capacity changes (the SoC windows arrive after a restart):
				// 53 % of 20 kWh is 5300 Wh less than before, but the plan is made again
				// from it instead of showing the storage behind
				.next(new TestCase("capacity changes mid quarter-hour") //
						.timeleap(clock, 5, MINUTES) //
						.input(ESS_CAPACITY, 20000) //
						.output(STORAGE_BEHIND_PLAN, 0) //
						.output(STORAGE_BUDGET_WITHHELD, false)) //
				.deactivate();
	}

	@Test
	public void storageBudgetWithheldBeforeTheStartTest() throws Exception {
		// Before the start the storage share of the plan would carry the start (8
		// ct); with the storage behind the path the budget is withheld and the car
		// does not start, the surplus alone (0) does not reach the minimum power
		final var clock = createDummyClock();
		prepareStoragePriceTest(clock, new ControllerEvcsPriceImpl(clock), true, true, STORAGE, null, 12000, 2000,
				96, 600) //
				.next(new TestCase("plan made at 50 %") //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(CONSUMPTION_ACTIVE_POWER, 1000) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(STORAGE_BUDGET_WITHHELD, false) //
						.output(STORAGE_POWER_FOR_CAR, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 4140)) //
				.next(new TestCase("10 minutes later the storage is still at 50 %: no start") //
						.timeleap(clock, 10, MINUTES) //
						.output(STORAGE_BEHIND_PLAN, 977) //
						.output(STORAGE_BUDGET_WITHHELD, true) //
						.output(STORAGE_POWER_FOR_CAR, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0)) //
				.deactivate();
	}

	@Test
	public void storagePathWithoutPlanTest() throws Exception {
		// Without predictions there is no plan and no path
		final var clock = createDummyClock();
		prepareStoragePriceTest(clock, new ControllerEvcsPriceImpl(clock), true, true, STORAGE, null, 12000, 2000,
				0, 600) //
				.next(new TestCase() //
						.input(GRID_BUY_PRICE, 431.) //
						.input(ESS_CAPACITY, 30000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input(GRID_ACTIVE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ACTIVE_POWER, 4140) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(PLAN_REASON, null) //
						.output(STORAGE_BEHIND_PLAN, null) //
						.output(STORAGE_BUDGET_WITHHELD, null)) //
				.deactivate();
	}

	@Test
	public void calculateExpectedStoredWhTest() {
		final var zone = ZoneId.of("Europe/Berlin");
		final var t0 = ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, zone);
		final var plan = new ChargingPlan.Result(List.of(storedQuarterOf(t0, 1600), storedQuarterOf(t0.plusMinutes(15), 2000)),
				t0, 2070, 0, 2070, 0, null, true);
		// plan made at the start of the quarter-hour from 1000 Wh: linear to 1600 Wh
		assertEquals(1000., ControllerEvcsPriceImpl.calculateExpectedStoredWh(plan, 1000, t0, t0), 0.01);
		assertEquals(1300., ControllerEvcsPriceImpl.calculateExpectedStoredWh(plan, 1000, t0, t0.plusSeconds(450)),
				0.01);
		// the second quarter-hour starts at the end of the first one
		assertEquals(1600., ControllerEvcsPriceImpl.calculateExpectedStoredWh(plan, 1000, t0, t0.plusMinutes(15)),
				0.01);
		assertEquals(1800.,
				ControllerEvcsPriceImpl.calculateExpectedStoredWh(plan, 1000, t0, t0.plusMinutes(22).plusSeconds(30)),
				0.01);
		// outside the plan
		assertNull(ControllerEvcsPriceImpl.calculateExpectedStoredWh(plan, 1000, t0, t0.minusSeconds(1)));
		assertNull(ControllerEvcsPriceImpl.calculateExpectedStoredWh(plan, 1000, t0, t0.plusMinutes(30)));
		// plan made 5 minutes into the quarter-hour: the path starts there
		assertEquals(1000.,
				ControllerEvcsPriceImpl.calculateExpectedStoredWh(plan, 1000, t0.plusMinutes(5), t0.plusMinutes(5)),
				0.01);
		assertEquals(1300.,
				ControllerEvcsPriceImpl.calculateExpectedStoredWh(plan, 1000, t0.plusMinutes(5), t0.plusMinutes(10)),
				0.01);
		// a time before the calculation (clock set back) stays at the start value
		assertEquals(1000.,
				ControllerEvcsPriceImpl.calculateExpectedStoredWh(plan, 1000, t0.plusMinutes(5), t0.plusMinutes(2)),
				0.01);
		// without a calculation time the path starts at the quarter-hour
		assertEquals(1300., ControllerEvcsPriceImpl.calculateExpectedStoredWh(plan, 1000, null, t0.plusSeconds(450)),
				0.01);
		// no plan
		assertNull(ControllerEvcsPriceImpl.calculateExpectedStoredWh(ChargingPlan.Result.EMPTY, 1000, t0, t0));
	}

	private static ChargingPlan.Quarter storedQuarterOf(ZonedDateTime time, double storageWh) {
		return new ChargingPlan.Quarter(time, 43.1, 12000, 2000, ChargingPlan.StorageMode.BALANCING, 4140, 0, 4140,
				4140, 0, 8., ChargingPlan.Reason.FILL_UP, storageWh, false);
	}

	@Test
	public void calculateStorageDeficitWhTest() {
		final var zone = ZoneId.of("Europe/Berlin");
		final var t0 = ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, zone);
		final var plan = new ChargingPlan.Result(List.of(storedQuarterOf(t0, 1600)), t0, 2070, 0, 2070, 0, null,
				true);
		// plan made from 1000 Wh at the quarter-hour, 1300 Wh expected half-way
		assertEquals(300.,
				ControllerEvcsPriceImpl.calculateStorageDeficitWh(plan, 1000, t0, t0.plusSeconds(450), 1000), 0.01);
		// on or ahead of the path
		assertEquals(0., ControllerEvcsPriceImpl.calculateStorageDeficitWh(plan, 1000, t0, t0.plusSeconds(450), 1300),
				0.01);
		assertEquals(0., ControllerEvcsPriceImpl.calculateStorageDeficitWh(plan, 1000, t0, t0.plusSeconds(450), 1500),
				0.01);
		// at the end of the quarter-hour the old plan is over: no deficit
		assertEquals(0., ControllerEvcsPriceImpl.calculateStorageDeficitWh(plan, 1000, t0, t0.plusMinutes(15), 1000),
				0.01);
		// no plan
		assertEquals(0., ControllerEvcsPriceImpl.calculateStorageDeficitWh(ChargingPlan.Result.EMPTY, 1000, t0, t0,
				1000), 0.01);
	}

	@Test
	public void isStorageBehindPlanTest() {
		// tolerance 2 % of 30 kWh = 600 Wh
		assertFalse(ControllerEvcsPriceImpl.isStorageBehindPlan(15600, 15000, 30000));
		assertTrue(ControllerEvcsPriceImpl.isStorageBehindPlan(15601, 15000, 30000));
		// ahead of the path
		assertFalse(ControllerEvcsPriceImpl.isStorageBehindPlan(15000, 16000, 30000));
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
	public void isSurplusChargingStateTest() {
		assertTrue(ControllerEvcsPriceImpl.isSurplusChargingState("BALANCING"));
		assertTrue(ControllerEvcsPriceImpl.isSurplusChargingState("DELAY_DISCHARGE"));
		assertTrue(ControllerEvcsPriceImpl.isSurplusChargingState("CHARGE_GRID"));
		assertTrue(ControllerEvcsPriceImpl.isSurplusChargingState("PEAK_SHAVING"));
		assertFalse(ControllerEvcsPriceImpl.isSurplusChargingState("DELAY_CHARGE"));
		assertFalse(ControllerEvcsPriceImpl.isSurplusChargingState("LIMIT_CHARGE"));
		assertFalse(ControllerEvcsPriceImpl.isSurplusChargingState("DISCHARGE_GRID"));
		assertFalse(ControllerEvcsPriceImpl.isSurplusChargingState("DISCHARGE_CONSUMPTION"));
		assertFalse(ControllerEvcsPriceImpl.isSurplusChargingState(null));
	}

	@Test
	public void calculateStorageChargeRoomTest() {
		// allowed 9000 W, charging 1600 W -> 7400 W room
		assertEquals(7400, ControllerEvcsPriceImpl.calculateStorageChargeRoom(9000, -1600));
		// discharging: the whole allowed power is room
		assertEquals(9000, ControllerEvcsPriceImpl.calculateStorageChargeRoom(9000, 2000));
		// charging more than allowed (e.g. the limit just dropped): no room, never negative
		assertEquals(0, ControllerEvcsPriceImpl.calculateStorageChargeRoom(4000, -5000));
		assertEquals(0, ControllerEvcsPriceImpl.calculateStorageChargeRoom(0, 0));
	}

	@Test
	public void planEnergyForOptimizerTest() {
		final var now = ZonedDateTime.of(2026, 10, 28, 10, 30, 0, 0, ZoneId.of("Europe/Berlin"));
		final var quarter = (ChargingPlan.Quarter) null;
		// no plan, no charging: nothing
		assertTrue(ControllerEvcsPriceImpl.planEnergyForOptimizer(ChargingPlan.Result.EMPTY, now, 0).wh().isEmpty());
		assertEquals(0, ControllerEvcsPriceImpl.planEnergyForOptimizer(ChargingPlan.Result.EMPTY, now, 0).start());
		// no plan, the car charges 5000 W: the running and the next quarter-hour
		var r = ControllerEvcsPriceImpl.planEnergyForOptimizer(ChargingPlan.Result.EMPTY, now, 5000);
		assertEquals(now.toEpochSecond(), r.start());
		assertEquals(List.of(1250, 1250), r.wh());
		// a plan of three quarter-hours from now: 4140 W, 0 W, 4140 W; the car charges
		// 5000 W -> the measured power where it is higher, the plan beyond
		final var plan = new ChargingPlan.Result(List.of(//
				quarterOf(now, 4140), quarterOf(now.plusMinutes(15), 0), quarterOf(now.plusMinutes(30), 4140)), now,
				2070, 0, 0, 2070, null, true);
		r = ControllerEvcsPriceImpl.planEnergyForOptimizer(plan, now, 5000);
		assertEquals(now.toEpochSecond(), r.start());
		assertEquals(List.of(1250, 1250, 1035), r.wh());
		// the car charges less than planned: the plan stays
		assertEquals(List.of(1035, 750, 1035), ControllerEvcsPriceImpl.planEnergyForOptimizer(plan, now, 3000).wh());
		// not charging: the plan alone
		assertEquals(List.of(1035, 0, 1035), ControllerEvcsPriceImpl.planEnergyForOptimizer(plan, now, 0).wh());
		// the plan is from the previous quarter-hour (not recalculated yet): the
		// measured power lands on the running quarter-hour
		r = ControllerEvcsPriceImpl.planEnergyForOptimizer(plan, now.plusMinutes(15), 5000);
		assertEquals(now.toEpochSecond(), r.start());
		assertEquals(List.of(1035, 1250, 1250), r.wh());
		assertNull(quarter);
	}

	private static ChargingPlan.Quarter quarterOf(ZonedDateTime time, int power) {
		return new ChargingPlan.Quarter(time, 43.1, 0, 1000, ChargingPlan.StorageMode.BALANCING, power, 0, 0, 0,
				power, null, power > 0 ? ChargingPlan.Reason.PRICE : ChargingPlan.Reason.TOO_EXPENSIVE, 15000, false);
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
	public void stopConfirmationBalanceTest() throws Exception {
		// Stop confirmation 600 s as a balance: 4 minutes of cloud, 1 minute of sun,
		// 4 of cloud, 1 of sun ... Under the old gap-free rule every minute of sun
		// restarted the timer and the car never stopped (28.10.2026, 11:25-13:10);
		// now the balance reaches 600 s after 4 + 4 + 4 minutes of cloud minus 2 of
		// sun and the car stops
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
						.setStopConfirmationTime(600) //
						.build()) //
				.next(new TestCase("sun: start") //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //
				.next(new TestCase("cloud for 4 minutes: balance 240 s") //
						.timeleap(clock, 4, MINUTES) //
						.input(GRID_ACTIVE_POWER, 2000) //
						.input("evcs0", ACTIVE_POWER, 4000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, true) //
						.output(HYSTERESIS_REMAINING, 360)) //
				.next(new TestCase("sun for a minute: balance 180 s, no warning") //
						.timeleap(clock, 1, MINUTES) //
						.input(GRID_ACTIVE_POWER, -2000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, false) //
						.output(HYSTERESIS_REMAINING, null)) //
				.next(new TestCase("cloud for 4 minutes: balance 420 s") //
						.timeleap(clock, 4, MINUTES) //
						.input(GRID_ACTIVE_POWER, 2000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, true) //
						.output(HYSTERESIS_REMAINING, 180)) //
				.next(new TestCase("sun for a minute: balance 360 s") //
						.timeleap(clock, 1, MINUTES) //
						.input(GRID_ACTIVE_POWER, -2000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase("cloud for 4 minutes: balance 600 s -> stop") //
						.timeleap(clock, 4, MINUTES) //
						.input(GRID_ACTIVE_POWER, 2000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 0) //
						.output(AWAITING_HYSTERESIS, false)) //
				.next(new TestCase("a long sunny spell after a cloud forgets the cloud") //
						.timeleap(clock, 1, MINUTES) //
						.input(GRID_ACTIVE_POWER, -6000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //
				.next(new TestCase("cloud 5 minutes") //
						.timeleap(clock, 5, MINUTES) //
						.input(GRID_ACTIVE_POWER, 2000) //
						.input("evcs0", ACTIVE_POWER, 4000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(HYSTERESIS_REMAINING, 300)) //
				.next(new TestCase("sun 6 minutes: balance back at zero") //
						.timeleap(clock, 6, MINUTES) //
						.input(GRID_ACTIVE_POWER, -2000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000)) //
				.next(new TestCase("cloud 5 minutes again: still 300 s to go") //
						.timeleap(clock, 5, MINUTES) //
						.input(GRID_ACTIVE_POWER, 2000) //
						.output("evcs0", SET_CHARGE_POWER_LIMIT, 6000) //
						.output(HYSTERESIS_REMAINING, 300)) //
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
		// The target counts from switching on: the total counter of the wallbox at
		// that moment is stored in the configuration. The session counter of the
		// wallbox restarts e.g. at midnight; the target must not count twice then
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
						.input("evcs0", ENERGY_SESSION, 5008) //
						.input("evcs0", ACTIVE_CONSUMPTION_ENERGY, 4041000L) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_STATUS, "PLANNED") //
						.output(TARGET_REMAINING_ENERGY, 10000)) //
				.deactivate();
		assertEquals(4041000, (int) (Integer) cm.getConfiguration("ctrlEvcs0").getProperties().get("targetStartEnergy"));

		final var cm2 = new DummyConfigurationAdmin();
		prepareTargetTest(clock, new ControllerEvcsPriceImpl(clock), prices, 10000, targetTime, 4041000, cm2) //
				.next(new TestCase("3 kWh charged since switching on") //
						.input(GRID_BUY_PRICE, 200.) //
						.input(ESS_CAPACITY, 20000) //
						.input(ESS_SOC, 50) //
						.input(ESS_DISCHARGE_POWER, 0) //
						.input("evcs0", IS_CLUSTERED, false) //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 8008) //
						.input("evcs0", ACTIVE_CONSUMPTION_ENERGY, 4044000L) //
						.input(GRID_ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 0) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.output(TARGET_REMAINING_ENERGY, 7000)) //
				.next(new TestCase("midnight: the session counter of the wallbox restarts, the total goes on") //
						.input("evcs0", ENERGY_SESSION, 0) //
						.output(TARGET_REMAINING_ENERGY, 7000)) //
				.next(new TestCase("session counter above the old start again: still counted once (night of 03./04.11.2026)") //
						.input("evcs0", ENERGY_SESSION, 5315) //
						.input("evcs0", ACTIVE_CONSUMPTION_ENERGY, 4049315L) //
						.output(TARGET_REMAINING_ENERGY, 1685)) //
				.next(new TestCase("a cycle without total counter: the last value counts") //
						.input("evcs0", ACTIVE_CONSUMPTION_ENERGY, null) //
						.output(TARGET_REMAINING_ENERGY, 1685)) //
				.next(new TestCase("target reached") //
						.input("evcs0", ACTIVE_CONSUMPTION_ENERGY, 4051000L) //
						.output(TARGET_REMAINING_ENERGY, 0) //
						.output(TARGET_STATUS, "REACHED")) //
				.deactivate();
		// the target is reached: the switch goes off by itself
		assertEquals(false, cm2.getConfiguration("ctrlEvcs0").getProperties().get("targetCharging"));
	}

	@Test
	public void sessionLimitFromPlugInTest() throws Exception {
		// The session counts from the total counter of the wallbox at the plug-in. The
		// wallbox compares its limit with its own session counter, which keeps the
		// last session until the next charging and restarts e.g. at midnight: the
		// controller sends that counter plus what is left of the limit
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
				.next(new TestCase("plugged in with an old counter: start is stored, the limit follows in the next cycle") //
						.input("evcs0", STATUS, Status.READY_FOR_CHARGING) //
						.input("evcs0", ENERGY_SESSION, 22902) //
						.input("evcs0", ACTIVE_CONSUMPTION_ENERGY, 4041000L) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(SESSION_ENERGY, 0) //
						.output("evcs0", SET_ENERGY_LIMIT, 5000)) //
				.deactivate();
		assertEquals(4041000, (int) (Integer) cm.getConfiguration("ctrlEvcs0").getProperties().get("sessionStartEnergy"));

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
						.setSessionStartEnergy(4041000) //
						.build()) //
				.next(new TestCase("3 kWh since the plug-in: the limit counts from the start") //
						.input("evcs0", STATUS, Status.CHARGING) //
						.input("evcs0", ENERGY_SESSION, 25902) //
						.input("evcs0", ACTIVE_CONSUMPTION_ENERGY, 4044000L) //
						.input("evcs0", MAXIMUM_HARDWARE_POWER, 11040) //
						.input("evcs0", MINIMUM_HARDWARE_POWER, 4140) //
						.output(SESSION_ENERGY, 3000) //
						.output("evcs0", SET_ENERGY_LIMIT, 27902)) //
				.next(new TestCase("the wallbox restarted its session counter: the session goes on") //
						.input("evcs0", ENERGY_SESSION, 1000) //
						.input("evcs0", ACTIVE_CONSUMPTION_ENERGY, 4045000L) //
						.output(SESSION_ENERGY, 4000) //
						.output("evcs0", SET_ENERGY_LIMIT, 2000)) //
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
						.input("evcs0", ACTIVE_CONSUMPTION_ENERGY, 1800L) //
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
						.input("evcs0", ACTIVE_CONSUMPTION_ENERGY, 2000L) //
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
		// charging is off as well, as with the "Off" button of the UI
		assertEquals(false, cm.getConfiguration("ctrlEvcs0").getProperties().get("enabledCharging"));
	}

	@Test
	public void targetEndSwitchesChargingOffTest() throws Exception {
		// Target charging was switched on over a force charge (the target charging
		// view of the UI leaves the mode underneath as it was). When the target time
		// passes, the controller must not fall back to the force charge: 2026-11-11
		// it charged 12 kWh from the grid at full power from 05:35 on, at a price far
		// above the limit. The switch-off switches charging off altogether; the mode
		// underneath stays as it was, the user chooses the next one
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
		prepareModeTest(clock, new ControllerEvcsPriceImpl(clock), prices, FORCE_CHARGE, true, 5000, targetTime, 0,
				cm, old.encode()) //
				.next(new TestCase("target time passed: off, and not the force charge underneath") //
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
		final var properties = cm.getConfiguration("ctrlEvcs0").getProperties();
		assertEquals(false, properties.get("targetCharging"));
		assertEquals(false, properties.get("enabledCharging"));
		assertEquals("FORCE_CHARGE", String.valueOf(properties.get("chargeMode")));
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
		return prepareModeTest(clock, controller, prices, EXCESS_POWER, targetCharging, targetEnergy, targetTime,
				targetStartEnergy, cm, targetPlan);
	}

	private static ControllerTest prepareModeTest(TimeLeapClock clock, ControllerEvcsPriceImpl controller,
			Double[] prices, ChargeMode chargeMode, boolean targetCharging, int targetEnergy, int targetTime,
			int targetStartEnergy, DummyConfigurationAdmin cm, String targetPlan) throws Exception {
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
						.setChargeMode(chargeMode) //
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
