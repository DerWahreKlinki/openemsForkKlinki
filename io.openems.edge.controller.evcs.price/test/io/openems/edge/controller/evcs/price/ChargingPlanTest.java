package io.openems.edge.controller.evcs.price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.edge.controller.evcs.price.ChargingPlan.Car;
import io.openems.edge.controller.evcs.price.ChargingPlan.Input;
import io.openems.edge.controller.evcs.price.ChargingPlan.Reason;
import io.openems.edge.controller.evcs.price.ChargingPlan.Rules;
import io.openems.edge.controller.evcs.price.ChargingPlan.Storage;
import io.openems.edge.controller.evcs.price.ChargingPlan.StorageMode;

public class ChargingPlanTest {

	private static final ZonedDateTime START = ZonedDateTime.of(2026, 10, 15, 7, 0, 0, 0, ZoneId.of("Europe/Berlin"));

	/** Usable capacity of both storages within 20..90 %. */
	private static final int USABLE = 22962;
	private static final int TARGET = (int) Math.round(USABLE * 0.85);

	private static Rules rules(Priority priority) {
		return new Rules(true, 30, 17, 11040, priority, 4140, 11040, 7, 8, true, TARGET, 200, 16170);
	}

	private static Storage storage(double storedWh) {
		return new Storage(storedWh, USABLE, 8000, 8500, 0.92);
	}

	/*
	 * A day from 07:00: PV bell up to 8 kW between 09:00 and 18:00, prices 45 ct in
	 * the morning and evening, 33 ct from 12:00 to 15:00.
	 */
	private static List<Input> day(int house) {
		final var result = new ArrayList<Input>();
		for (var i = 0; i < 17 * 4; i++) {
			final var time = START.plusMinutes(15L * i);
			final var hour = time.getHour() + time.getMinute() / 60.;
			final var pv = hour < 9 || hour >= 18 ? 0 : (int) Math.round(8000 * Math.sin(Math.PI * (hour - 9) / 9));
			final var price = hour >= 12 && hour < 15 ? 33. : 45.;
			result.add(new Input(time, price, pv, house));
		}
		return result;
	}

	private static List<Input> night(double price, StorageMode mode, int gridCharge) {
		final var result = new ArrayList<Input>();
		for (var i = 0; i < 8; i++) {
			result.add(new Input(START.plusMinutes(15L * i), price, 0, 1000, mode, gridCharge));
		}
		return result;
	}

	@Test
	public void emptyAndUnplugged() {
		assertEquals(0, ChargingPlan.calculate(rules(Priority.STORAGE), storage(10000), new Car(true, null), List.of())
				.energyWh());
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(10000), new Car(false, null),
				day(2500));
		assertEquals(0, r.energyWh());
		assertNull(r.start());
		assertTrue(r.quarters().stream().allMatch(q -> q.reason() == Reason.NOT_PLUGGED));
	}

	@Test
	public void storagePriorityDayHoldsTarget() {
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(10847), new Car(true, null),
				day(2500));
		assertNotNull(r.start());
		// no charging before the storage can no longer take the surplus
		// the car takes the margin above the target as early as possible: from 10:45
		// on the storage charging it displaces covers enough of the minimum power, the
		// storage catches up later. Before that a discharge below the target would
		// make the house buy from grid at 45 ct: too expensive
		assertEquals(10, r.start().getHour(), "start " + r.start());
		assertEquals(45, r.start().getMinute(), "start " + r.start());
		assertTrue(r.energyWh() > 5000, "energy " + r.energyWh());
		assertEquals(r.energyWh(), r.fromPvWh() + r.fromStorageWh() + r.fromGridWh(), 2);
		// the storage reaches its evening target: at most 15 % of the capacity for the car
		assertTrue(r.targetReached());
		assertTrue(r.storageAtPvEndWh() + 1 >= TARGET, "at PV end " + r.storageAtPvEndWh());
		// the storage share may exceed the margin above the target: charging the car
		// displaces while the storage is not yet full is caught up later
		assertTrue(r.fromStorageWh() <= r.energyWh(), "from storage " + r.fromStorageWh());
		// nothing from grid at 33 ct and above
		assertEquals(0, r.fromGridWh(), () -> "from grid: " + r.quarters().stream().filter(q -> q.fromGrid() > 0)
				.map(q -> q.time().toLocalTime() + " " + q.reason() + " " + q.power() + "/" + q.fromPv() + "/"
						+ q.fromStorage() + "/" + q.fromGrid() + " st=" + Math.round(q.storageWh()))
				.toList());
		// storage power for the car only within the PV block
		for (var q : r.quarters()) {
			if (q.fromStorage() > 0) {
				assertTrue(q.production() > 0, "storage share at " + q.time());
			}
			if (q.power() > 0) {
				assertTrue(q.blendedPrice() < 30, "blended " + q.blendedPrice() + " at " + q.time());
			}
		}
		// evening: too expensive
		final var evening = r.getAt(START.withHour(19));
		assertEquals(0, evening.power());
		assertEquals(Reason.TOO_EXPENSIVE, evening.reason());
	}

	@Test
	public void targetMissedWithoutCarDropsAllStorageShares() {
		// house 6 kW: the storage cannot reach its target even without the car
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(2000), new Car(true, null), day(6000));
		assertFalse(r.targetReached());
		assertEquals(0, r.fromStorageWh());
		assertTrue(r.quarters().stream().noneMatch(q -> q.fromStorage() > 0));
	}

	@Test
	public void carPriorityTakesSurplusFirst() {
		final var r = ChargingPlan.calculate(rules(Priority.CAR), storage(10847), new Car(true, null), day(2500));
		assertNotNull(r.start());
		// the car takes the surplus as soon as it covers the minimum power (12:00), the
		// storage only gets the rest; fill-ups from the storage before that are dropped
		// because the storage would miss its target
		assertEquals(12, r.start().getHour(), "start " + r.start());
		assertTrue(r.energyWh() > 15000, "energy " + r.energyWh());
		assertTrue(r.quarters().stream().filter(q -> q.reason() == Reason.SURPLUS).allMatch(q -> q.fromStorage() == 0));
	}

	@Test
	public void nightPriceCharging() {
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(10000), new Car(true, null),
				night(25, StorageMode.NO_DISCHARGE, 0));
		final var expected = ControllerEvcsPriceImpl.calculateChargePowerFromPrice(250., 30, 17, 4140, 11040);
		for (var q : r.quarters()) {
			assertEquals(Reason.PRICE, q.reason());
			assertEquals(expected, q.power());
			assertEquals(expected, q.fromGrid());
			assertEquals(0, q.fromStorage());
		}
		assertEquals(expected * 2, r.energyWh());
		// the storage is untouched while the discharge is delayed
		assertEquals(10000, r.quarters().get(7).storageWh(), 1);
	}

	@Test
	public void nightBalancingFeedsCarFromStorage() {
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(10000), new Car(true, null),
				night(25, StorageMode.BALANCING, 0));
		final var q = r.quarters().get(0);
		assertEquals(Reason.PRICE, q.reason());
		assertTrue(q.fromStorage() > 0);
		assertTrue(q.storageWh() < 10000);
		// outside of a PV block the storage counts at the grid price
		assertEquals(25, q.blendedPrice(), 0.01);
	}

	@Test
	public void nightDelayDischargeAboveLimit() {
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(10000), new Car(true, null),
				night(40, StorageMode.NO_DISCHARGE, 0));
		assertEquals(0, r.energyWh());
		assertEquals(Reason.TOO_EXPENSIVE, r.quarters().get(0).reason());
		assertEquals(40, r.quarters().get(0).blendedPrice(), 0.01);
	}

	@Test
	public void chargeGridSharesTheGridLimit() {
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(10000), new Car(true, null),
				night(20, StorageMode.CHARGE_GRID, 6000));
		final var ramp = ControllerEvcsPriceImpl.calculateChargePowerFromPrice(200., 30, 17, 4140, 11040);
		final var q = r.quarters().get(0);
		assertEquals(Reason.PRICE, q.reason());
		// house 1000 + storage 6000 + car <= 16170
		assertEquals(16170 - 1000 - 6000, q.power());
		assertTrue(q.power() < ramp);
		assertEquals(q.power(), q.fromGrid());
		// the storage charges from grid
		assertTrue(q.storageWh() > 10000);
	}

	@Test
	public void gridLimitLeavesNoRoom() {
		final var rules = new Rules(true, 30, 17, 11040, Priority.STORAGE, 4140, 11040, 7, 8, true, TARGET, 200, 9000);
		final var r = ChargingPlan.calculate(rules, storage(10000), new Car(true, null),
				night(20, StorageMode.CHARGE_GRID, 6000));
		assertEquals(0, r.energyWh());
		assertEquals(Reason.GRID_LIMIT, r.quarters().get(0).reason());
	}

	@Test
	public void sessionLimitStopsThePlan() {
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(10000), new Car(true, 2000),
				night(25, StorageMode.NO_DISCHARGE, 0));
		// one quarter-hour at 6794 W; the rest of 301 Wh is below the minimum power
		assertEquals(1699, r.energyWh(), 1);
		assertEquals(Reason.PRICE, r.quarters().get(0).reason());
		assertEquals(Reason.LIMIT, r.quarters().get(1).reason());
	}

	@Test
	public void unknownForecast() {
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(10000), new Car(true, null),
				List.of(new Input(START, null, 0, 1000)));
		assertEquals(Reason.UNKNOWN, r.quarters().get(0).reason());
	}

	@Test
	public void storageModeFromState() {
		assertEquals(StorageMode.BALANCING, StorageMode.fromState(null));
		assertEquals(StorageMode.BALANCING, StorageMode.fromState("BALANCING"));
		assertEquals(StorageMode.NO_DISCHARGE, StorageMode.fromState("DELAY_DISCHARGE"));
		assertEquals(StorageMode.CHARGE_GRID, StorageMode.fromState("CHARGE_GRID"));
	}
}
