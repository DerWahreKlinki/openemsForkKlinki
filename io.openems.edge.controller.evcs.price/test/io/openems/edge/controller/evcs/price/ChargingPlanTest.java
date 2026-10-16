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
	public void targetMissedWithoutCarWithdrawsAllStorageBudgets() {
		// house 6 kW: the storage cannot reach its target even without the car, so no
		// quarter-hour keeps a storage budget; the storage share costs the grid price
		// and the car still fills up where PV and grid power blend below the limit
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(2000), new Car(true, null), day(6000));
		assertFalse(r.targetReached());
		assertTrue(r.quarters().stream().allMatch(q -> q.storageBudget() == 0));
		for (var q : r.quarters()) {
			if (q.power() > 0) {
				assertEquals(Reason.FILL_UP, q.reason(), "at " + q.time());
				assertTrue(q.blendedPrice() < 30, "blended " + q.blendedPrice() + " at " + q.time());
				// the storage share is priced like grid power
				final var expected = (q.fromPv() * 7. + (q.fromStorage() + q.fromGrid()) * q.price()) / q.power();
				assertEquals(expected, q.blendedPrice(), 0.01, "at " + q.time());
			}
		}
	}

	@Test
	public void surplusOfTheFullStorageIsTheCarsWhenTheTargetIsMissed() {
		// full storage, PV bell, house 1 kW until 16:00 and 7 kW after: the storage
		// misses its target at PV end whatever the car does, but the surplus it cannot
		// take at noon goes to the car anyway
		final var inputs = new ArrayList<Input>();
		for (var i = 0; i < 17 * 4; i++) {
			final var time = START.plusMinutes(15L * i);
			final var hour = time.getHour() + time.getMinute() / 60.;
			final var pv = hour < 9 || hour >= 18 ? 0 : (int) Math.round(8000 * Math.sin(Math.PI * (hour - 9) / 9));
			inputs.add(new Input(time, 45., pv, hour < 16 ? 1000 : 7000));
		}
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(USABLE), new Car(true, null), inputs);
		assertFalse(r.targetReached());
		assertTrue(r.storageAtPvEndWh() < TARGET, "at PV end " + r.storageAtPvEndWh());
		for (var hour = 12; hour < 16; hour++) {
			final var q = r.getAt(START.withHour(hour));
			assertEquals(Reason.SURPLUS, q.reason(), "at " + q.time());
			assertTrue(q.power() >= 4140, "power " + q.power() + " at " + q.time());
			assertEquals(0, q.fromStorage(), "at " + q.time());
			assertEquals(0, q.storageBudget(), "at " + q.time());
		}
		// no storage budget anywhere, the storage price is withdrawn
		assertTrue(r.quarters().stream().allMatch(q -> q.storageBudget() == 0));
		// the evening: the house takes everything, too expensive
		assertEquals(Reason.TOO_EXPENSIVE, r.getAt(START.withHour(19)).reason());
	}

	@Test
	public void carPriorityTakesSurplusFirst() {
		final var r = ChargingPlan.calculate(rules(Priority.CAR), storage(10847), new Car(true, null), day(2500));
		assertNotNull(r.start());
		// the car takes the surplus as soon as it covers the minimum power (12:00), the
		// storage only gets the rest and misses its target. Before 12:00 the car fills
		// up from the storage without storage budget: the storage share costs the grid
		// price, from 10:45 on the blend of PV at 7 ct and grid at 45 ct is below 30 ct
		assertEquals(10, r.start().getHour(), "start " + r.start());
		assertEquals(45, r.start().getMinute(), "start " + r.start());
		assertFalse(r.targetReached());
		assertTrue(r.energyWh() > 15000, "energy " + r.energyWh());
		assertTrue(r.quarters().stream().allMatch(q -> q.storageBudget() == 0));
		for (var q : r.quarters()) {
			if (q.time().getHour() < 12 && q.power() > 0) {
				assertEquals(Reason.FILL_UP, q.reason(), "at " + q.time());
				assertTrue(q.blendedPrice() < 30, "blended " + q.blendedPrice() + " at " + q.time());
			}
		}
		assertEquals(Reason.SURPLUS, r.getAt(START.withHour(12)).reason());
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
	public void horizonEndsBeforeTheNextPvBlock() {
		// two days: the plan stops where the PV of the second day starts
		final var inputs = new ArrayList<Input>();
		inputs.addAll(day(2500));
		for (var i = 0; i < 7 * 4; i++) {
			inputs.add(new Input(START.plusDays(1).minusHours(7).plusMinutes(15L * i), 45., 0, 1000));
		}
		inputs.addAll(day(2500).stream()
				.map(in -> new Input(in.time().plusDays(1), in.price(), in.production(), in.consumption())).toList());
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(10847), new Car(true, null), inputs);
		// 24 hours plus the second day from 07:00 until 09:00 (the first PV at 09:15)
		assertEquals(24 * 4 + 9, r.quarters().size());
		assertTrue(r.quarters().get(r.quarters().size() - 1).time().isBefore(START.plusDays(1).withHour(9).plusMinutes(15)));
		assertTrue(r.targetReached());
	}

	@Test
	public void pvBlockEndsBelowThreePercentOfThePeak() {
		// Live 15.10.2026: the weather forecast shows a few Watt until 20:15; the PV
		// block must end at 18:30 (398 W, above 3 % of 8500 W = 255 W), not at 20:15,
		// otherwise the evening target is checked after two hours of house load
		final var inputs = new ArrayList<Input>();
		final var pv = new int[] { 8500, 6000, 2350, 1177, 726, 398, 185, 67, 18, 6, 2, 1, 1, 0, 0, 0 };
		for (var i = 0; i < pv.length; i++) {
			inputs.add(new Input(START.plusMinutes(15L * i), 45., pv[i], 3500));
		}
		final var r = ChargingPlan.calculate(rules(Priority.STORAGE), storage(22000), new Car(true, null), inputs);
		// the storage at the end of the PV block is the one after the 398 W quarter-hour
		assertEquals(r.quarters().get(5).storageWh(), r.storageAtPvEndWh(), 0.5);
		// no storage share for the car in the twilight
		for (var i = 6; i < pv.length; i++) {
			assertEquals(0, r.quarters().get(i).fromStorage(), "quarter " + i);
		}
	}

	@Test
	public void storageModeFromState() {
		assertEquals(StorageMode.BALANCING, StorageMode.fromState(null));
		assertEquals(StorageMode.BALANCING, StorageMode.fromState("BALANCING"));
		assertEquals(StorageMode.NO_DISCHARGE, StorageMode.fromState("DELAY_DISCHARGE"));
		assertEquals(StorageMode.CHARGE_GRID, StorageMode.fromState("CHARGE_GRID"));
	}
}
