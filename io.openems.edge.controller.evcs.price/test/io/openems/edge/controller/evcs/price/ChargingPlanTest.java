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
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.openems.edge.controller.evcs.price.ChargingPlan.Car;
import io.openems.edge.controller.evcs.price.ChargingPlan.Input;
import io.openems.edge.controller.evcs.price.ChargingPlan.Quarter;
import io.openems.edge.controller.evcs.price.ChargingPlan.Reason;
import io.openems.edge.controller.evcs.price.ChargingPlan.Rules;
import io.openems.edge.controller.evcs.price.ChargingPlan.Storage;
import io.openems.edge.controller.evcs.price.ChargingPlan.StorageMode;
import io.openems.edge.controller.evcs.price.ChargingPlan.Target;
import io.openems.edge.controller.evcs.price.ChargingPlan.TargetStatus;

public class ChargingPlanTest {

	private static final ZonedDateTime START = ZonedDateTime.of(2026, 10, 15, 7, 0, 0, 0, ZoneId.of("Europe/Berlin"));

	/** Usable capacity of both storages within 20..90 %. */
	private static final int USABLE = 22962;
	private static final int TARGET = (int) Math.round(USABLE * 0.85);

	private static Rules rules(Priority priority) {
		return new Rules(true, 30, 17, 11040, priority, 4140, 11040, 7, 8, true, TARGET, 200, 16170, 0, false);
	}

	private static Rules rulesWithHysteresis(double hysteresis, boolean priceDrivenNow) {
		return new Rules(true, 30, 17, 11040, Priority.CAR, 4140, 11040, 7, 8, true, TARGET, 200, 16170, hysteresis,
				priceDrivenNow);
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

	private static List<Input> nightPrices(double... prices) {
		final var result = new ArrayList<Input>();
		for (var i = 0; i < prices.length; i++) {
			result.add(new Input(START.plusMinutes(15L * i), prices[i], 0, 1000));
		}
		return result;
	}

	@Test
	public void priceHysteresisKeepsCharging() {
		// prices around the limit of 30 ct: once charging by price, a quarter-hour up
		// to 1 ct above the limit goes on with the minimum power; a start needs a
		// price below the limit
		final var inputs = nightPrices(29, 30.5, 32, 30.5, 29, 30.9, 31.1);
		final var without = ChargingPlan.calculate(rulesWithHysteresis(0, false), storage(10000), new Car(true, null),
				inputs).quarters();
		assertEquals(List.of(Reason.PRICE, Reason.TOO_EXPENSIVE, Reason.TOO_EXPENSIVE, Reason.TOO_EXPENSIVE,
				Reason.PRICE, Reason.TOO_EXPENSIVE, Reason.TOO_EXPENSIVE),
				without.stream().map(Quarter::reason).toList());
		final var with = ChargingPlan.calculate(rulesWithHysteresis(1, false), storage(10000), new Car(true, null),
				inputs).quarters();
		assertEquals(List.of(Reason.PRICE, Reason.PRICE, Reason.TOO_EXPENSIVE, Reason.TOO_EXPENSIVE, Reason.PRICE,
				Reason.PRICE, Reason.TOO_EXPENSIVE), with.stream().map(Quarter::reason).toList());
		// within the hysteresis: the minimum power only
		assertEquals(4140, with.get(1).power());
		assertTrue(with.get(0).power() > 4140);
		// charging by price right now: the first quarter-hour continues within the
		// hysteresis
		final var now = ChargingPlan.calculate(rulesWithHysteresis(1, true), storage(10000), new Car(true, null),
				nightPrices(30.5, 32)).quarters();
		assertEquals(List.of(Reason.PRICE, Reason.TOO_EXPENSIVE), now.stream().map(Quarter::reason).toList());
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
		final var rules = new Rules(true, 30, 17, 11040, Priority.STORAGE, 4140, 11040, 7, 8, true, TARGET, 200, 9000,
				0, false);
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

	/*
	 * A night from 22:00 to 06:00: 1 kW house, no PV, the storage may not
	 * discharge, prices per hour 40, 35, 25, 25, 30, 45, 50, 50 ct.
	 */
	private static List<Input> targetNight() {
		final var start = ZonedDateTime.of(2026, 10, 19, 22, 0, 0, 0, ZoneId.of("Europe/Berlin"));
		final double[] prices = { 40, 35, 25, 25, 30, 45, 50, 50 };
		final var result = new ArrayList<Input>();
		for (var i = 0; i < 8 * 4; i++) {
			result.add(new Input(start.plusMinutes(15L * i), prices[i / 4], 0, 1000, StorageMode.NO_DISCHARGE, 0));
		}
		return result;
	}

	@Test
	public void targetPicksCheapestQuarters() {
		// 10 kWh until 06:00 at 11.04 kW (2.76 kWh per quarter-hour): the first
		// three quarter-hours at 25 ct (8.28 kWh), the rest of 1.72 kWh in the
		// fourth: its base of 1.035 kWh and 0.685 kWh on top
		final var inputs = targetNight();
		final var deadline = inputs.get(0).time().plusHours(8);
		final var r = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(true, 10000),
				new Target(10000, deadline), inputs);
		assertEquals(TargetStatus.PLANNED, r.targetStatus());
		assertEquals(10000, r.energyWh());
		assertEquals(r.energyWh(), r.fromGridWh());
		assertEquals(0, r.fromStorageWh());
		for (var q : r.quarters()) {
			final var hour = q.time().getHour();
			final var minute = q.time().getMinute();
			if (hour == 0 && minute < 45) {
				assertEquals(11040, q.power(), "full power at " + q.time());
				assertEquals(Reason.TARGET_CHARGE, q.reason());
				assertEquals(q.price(), q.blendedPrice(), 0.01);
			} else if (hour == 0 && minute == 45) {
				assertEquals(6880, q.power(), "rest at " + q.time());
				assertEquals(Reason.TARGET_CHARGE, q.reason());
			} else if (hour == 22 || hour == 23) {
				assertEquals(0, q.power(), "no charging at " + q.time());
				assertEquals(Reason.TOO_EXPENSIVE, q.reason());
			} else {
				// after the target is covered
				assertEquals(0, q.power(), "no charging at " + q.time());
				assertEquals(Reason.LIMIT, q.reason());
			}
		}
		// 10 kWh at 25 ct
		assertEquals(10000 * 25, r.costCt() * 1000, 1);
	}

	@Test
	public void targetTakesSurplusBeforeGrid() {
		// 3 kWh until 15:00 on a PV day with 45 ct in the morning and 33 ct at noon:
		// the surplus at the PV price is the cheapest, taken with what it offers
		// instead of full power from grid
		final var inputs = day(2500);
		final var deadline = START.plusHours(8);
		final var r = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(10000), new Car(true, 3000),
				new Target(3000, deadline), inputs);
		assertEquals(TargetStatus.PLANNED, r.targetStatus());
		assertTrue(r.energyWh() >= 3000 && r.energyWh() <= 3000 + 4140 / 4, "energy " + r.energyWh());
		assertEquals(0, r.fromGridWh());
		assertEquals(r.energyWh(), r.fromPvWh());
		for (var q : r.quarters()) {
			if (q.power() > 0) {
				assertEquals(Reason.TARGET_CHARGE, q.reason());
				assertTrue(q.power() < 11040, "surplus only at " + q.time() + ": " + q.power());
				assertEquals(q.power(), q.fromPv());
				assertTrue(q.time().getHour() >= 11 && q.time().getHour() < 15, "noon at " + q.time());
			}
		}
	}

	@Test
	public void targetLocksStorageBelowEveningTarget() {
		// Two hours at night in balancing, 1 kW house, 2 kWh target. Storage below its
		// evening target: locked for the car, it keeps serving the house and the car
		// takes grid power
		final var inputs = night(30., StorageMode.BALANCING, 0);
		final var deadline = START.plusHours(2);
		final var locked = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(true, 2000),
				new Target(2000, deadline), inputs);
		assertEquals(TargetStatus.PLANNED, locked.targetStatus());
		assertTrue(locked.energyWh() >= 2000, "energy " + locked.energyWh());
		assertEquals(0, locked.fromStorageWh());
		assertEquals(locked.energyWh(), locked.fromGridWh());
		for (var q : locked.quarters()) {
			if (q.power() > 0) {
				assertTrue(q.storageLocked(), "locked at " + q.time());
				assertEquals(30., q.blendedPrice(), 1e-6);
			}
		}
		// above the evening target the storage discharges for the car
		final var free = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(TARGET + 3000),
				new Car(true, 2000), new Target(2000, deadline), inputs);
		// 8.5 kW discharge minus 1 kW house: the storage delivers most of the 8 kW
		assertTrue(free.fromStorageWh() >= 1500, "storage " + free.fromStorageWh());
		assertTrue(free.fromGridWh() < 500, "grid " + free.fromGridWh());
		assertTrue(free.quarters().stream().noneMatch(Quarter::storageLocked));
	}

	@Test
	public void targetPrefersPlannedQuarters() {
		// equal prices all night: without a previous plan the earliest quarter-hours
		// are taken, with a previous plan its quarter-hours
		final var inputs = night(30., StorageMode.NO_DISCHARGE, 0);
		final var deadline = START.plusHours(2);
		final var fresh = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(true, 4000),
				new Target(4000, deadline), inputs);
		assertTrue(fresh.quarters().get(0).power() > 0, "earliest quarter-hour first");
		final var previous = Set.of(START.plusMinutes(60), START.plusMinutes(75));
		final var again = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(true, 4000),
				new Target(4000, deadline), inputs, previous);
		assertEquals(0, again.quarters().get(0).power());
		assertTrue(again.quarters().get(4).power() > 0, "previously planned quarter-hour first");
		assertTrue(again.energyWh() >= 4000, "energy " + again.energyWh());
	}

	@Test
	public void targetUsesCheapNightWithoutForecast() {
		// the forecast ends after two hours, the prices go on: the cheap night
		// quarter-hours without forecast are usable at the grid price
		final var inputs = new ArrayList<Input>();
		for (var i = 0; i < 16; i++) {
			final var time = START.plusMinutes(15L * i);
			final var price = i < 8 ? 47. : 35.;
			inputs.add(i < 8 ? new Input(time, price, 0, 1000, StorageMode.NO_DISCHARGE, 0)
					: new Input(time, price, null, null, StorageMode.NO_DISCHARGE, 0));
		}
		final var r = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(true, 5000),
				new Target(5000, START.plusHours(4)), inputs);
		assertEquals(TargetStatus.PLANNED, r.targetStatus());
		assertTrue(r.energyWh() >= 5000, "energy " + r.energyWh());
		for (var q : r.quarters()) {
			if (q.power() > 0) {
				assertTrue(q.time().isAfter(START.plusMinutes(105)), "cheap night at " + q.time());
				assertEquals(Reason.TARGET_CHARGE, q.reason());
				assertEquals(35., q.blendedPrice(), 1e-6);
			}
		}
	}

	@Test
	public void resultUntilKeepsTheTotals() {
		final var inputs = new ArrayList<Input>();
		for (var i = 0; i < 8; i++) {
			inputs.add(new Input(START.plusMinutes(15L * i), 20., 0, 1000, StorageMode.NO_DISCHARGE, 0));
		}
		final var r = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(true, 4000),
				new Target(4000, START.plusMinutes(8 * 15)), inputs);
		assertEquals(8, r.quarters().size());
		final var cut = r.until(START.plusMinutes(4 * 15));
		assertEquals(4, cut.quarters().size());
		assertTrue(cut.quarters().get(3).time().isBefore(START.plusMinutes(4 * 15)));
		assertEquals(r.energyWh(), cut.energyWh());
		assertEquals(r.targetStatus(), cut.targetStatus());
		// nothing to cut: the same plan
		assertEquals(r, r.until(START.plusMinutes(8 * 15)));
	}

	@Test
	public void targetEndsWithTheLastWholeQuarterBeforeTheTargetTime() {
		// the target time is 5 minutes into a quarter-hour: that quarter-hour charges
		// only 5 minutes before it, so it does not count; the one before it does
		final var inputs = new ArrayList<Input>();
		for (var i = 0; i < 8; i++) {
			// the last quarter-hour is the cheapest, the one before the second cheapest
			final var price = i == 7 ? 20. : i == 6 ? 30. : 40.;
			inputs.add(new Input(START.plusMinutes(15L * i), price, 0, 1000, StorageMode.NO_DISCHARGE, 0));
		}
		final var r = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(true, 2000),
				new Target(2000, START.plusMinutes(7 * 15 + 5)), inputs);
		assertEquals(TargetStatus.PLANNED, r.targetStatus());
		// the cut quarter-hour is shown as after the target time, not planned
		assertEquals(8, r.quarters().size());
		assertEquals(Reason.AFTER_TARGET, r.quarters().get(7).reason());
		for (var q : r.quarters()) {
			if (q.power() > 0) {
				assertEquals(START.plusMinutes(6 * 15), q.time());
			}
		}
	}

	@Test
	public void targetUsesTheStorageSurplusOnlyOnce() {
		// PV day, 1 kW house, storage 2 kWh above its evening target: the car may
		// take about these 2 kWh at the storage price, not 2 kWh in every
		// quarter-hour. What the car takes at noon is missing in the afternoon
		final var inputs = day(1000);
		final var deadline = START.plusHours(16);
		final var r = ChargingPlan.calculateTarget(rules(Priority.STORAGE), storage(TARGET + 2000),
				new Car(true, 12000), new Target(12000, deadline), inputs);
		assertEquals(TargetStatus.PLANNED, r.targetStatus());
		assertTrue(r.energyWh() >= 12000, "energy " + r.energyWh());
		assertTrue(r.fromStorageWh() <= 2000 + 2760, "storage " + r.fromStorageWh());
		// the storage ends the PV day at its target or above
		assertTrue(r.storageAtPvEndWh() >= TARGET - 500, "at PV end " + r.storageAtPvEndWh());
	}

	@Test
	public void applyTargetFollowsTheStoredPlan() {
		// a stored plan charges in the third quarter-hour, whatever the prices say
		final var inputs = night(30., StorageMode.NO_DISCHARGE, 0);
		final var deadline = START.plusHours(2);
		final var fixed = Map.of(START.plusMinutes(30), 11040);
		final var r = ChargingPlan.applyTarget(rules(Priority.CAR), storage(5000), new Car(true, 2000),
				new Target(2000, deadline), inputs, fixed);
		assertEquals(TargetStatus.PLANNED, r.targetStatus());
		assertEquals(0, r.quarters().get(0).power());
		assertEquals(0, r.quarters().get(1).power());
		assertTrue(r.quarters().get(2).power() >= 4140, "charges in the planned quarter-hour");
		assertEquals(Reason.TARGET_CHARGE, r.quarters().get(2).reason());
		assertEquals(0, r.quarters().get(3).power());
		// not enough in the plan for the missing energy
		final var unreachable = ChargingPlan.applyTarget(rules(Priority.CAR), storage(5000), new Car(true, 9000),
				new Target(9000, deadline), inputs, fixed);
		assertEquals(TargetStatus.UNREACHABLE, unreachable.targetStatus());
		// reached
		final var reached = ChargingPlan.applyTarget(rules(Priority.CAR), storage(5000), new Car(true, 0),
				new Target(0, deadline), inputs, fixed);
		assertEquals(TargetStatus.REACHED, reached.targetStatus());
	}

	@Test
	public void targetWithoutPricesUntilTheTargetTime() {
		// prices end at 06:00, target 07:00: no charging, nothing is planned blind
		final var inputs = targetNight();
		final var deadline = inputs.get(0).time().plusHours(9);
		final var r = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(true, 10000),
				new Target(10000, deadline), inputs);
		assertEquals(TargetStatus.PRICES_MISSING, r.targetStatus());
		assertEquals(0, r.energyWh());
		assertEquals(32, r.quarters().size());
		assertTrue(r.quarters().stream().allMatch(q -> q.power() == 0));
	}

	@Test
	public void targetUnreachableTakesEverything() {
		// 100 kWh in 8 hours at 11.04 kW: every quarter-hour at full power
		final var inputs = targetNight();
		final var deadline = inputs.get(0).time().plusHours(8);
		final var r = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(true, 100000),
				new Target(100000, deadline), inputs);
		assertEquals(TargetStatus.UNREACHABLE, r.targetStatus());
		assertEquals(8 * 11040, r.energyWh());
		assertTrue(r.quarters().stream().allMatch(q -> q.power() == 11040 && q.reason() == Reason.TARGET_CHARGE));
	}

	@Test
	public void targetReached() {
		final var inputs = targetNight();
		final var deadline = inputs.get(0).time().plusHours(8);
		final var r = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(true, 0),
				new Target(0, deadline), inputs);
		assertEquals(TargetStatus.REACHED, r.targetStatus());
		assertEquals(0, r.energyWh());
		assertTrue(r.quarters().stream().allMatch(q -> q.reason() == Reason.LIMIT));
	}

	@Test
	public void targetWithoutCar() {
		final var inputs = targetNight();
		final var deadline = inputs.get(0).time().plusHours(8);
		final var r = ChargingPlan.calculateTarget(rules(Priority.CAR), storage(5000), new Car(false, 10000),
				new Target(10000, deadline), inputs);
		assertEquals(TargetStatus.PLANNED, r.targetStatus());
		assertEquals(0, r.energyWh());
		assertTrue(r.quarters().stream().allMatch(q -> q.reason() == Reason.NOT_PLUGGED));
	}
}
