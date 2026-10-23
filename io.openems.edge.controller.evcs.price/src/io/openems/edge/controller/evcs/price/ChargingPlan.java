package io.openems.edge.controller.evcs.price;

import static java.lang.Math.max;
import static java.lang.Math.min;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Predicted charging curve of the car for the price horizon.
 *
 * <p>
 * The plan simulates storage and car together, quarter-hour by quarter-hour,
 * with the rules the controller applies live: the storage takes the surplus up
 * to its limits and the car gets what the storage cannot take; below the price
 * limit the car charges from grid along the price ramp; otherwise it fills up to
 * its minimum power as long as the blended price of PV, storage and grid power
 * stays below the price limit. Storage power for the car costs the storage
 * price while PV can refill it before the end of the running PV block, and it
 * is only allowed as long as the storage still reaches its evening target at the
 * end of that block. Where it does not, the storage price is withdrawn from the
 * charging quarter-hours with the smallest PV share first until the target
 * holds: such a quarter-hour keeps no storage budget, its storage share costs
 * the grid price, and it still charges if the blended price stays below the
 * limit, like the controller does live. Surplus the storage cannot take is
 * always the car's, whether the target is reached or not. Outside of PV blocks
 * the storage is reserved for the house.
 *
 * <p>
 * The schedule of the Time-of-Use-Tariff controller is respected per
 * quarter-hour: while it delays the discharge the storage delivers nothing to
 * the car; while it charges from grid the car may charge from grid as well, but
 * the grid connection limit is shared with the house and the storage.
 *
 * <p>
 * All powers are in [W], energies in [Wh] and prices in [ct/kWh]. A
 * quarter-hour of power P stores P/4 Wh.
 */
public final class ChargingPlan {

	/** Quarter-hours per hour. */
	private static final int QUARTERS_PER_HOUR = 4;

	/** What the Time-of-Use-Tariff controller does with the storage. */
	public enum StorageMode {
		/** Self-consumption: charges from surplus, discharges for the house. */
		BALANCING,
		/** Charges from surplus, does not discharge. */
		NO_DISCHARGE,
		/** Charges from grid, does not discharge. */
		CHARGE_GRID;

		/**
		 * Maps the state of the Time-of-Use-Tariff controller.
		 *
		 * @param state the state name, e.g. "DELAY_DISCHARGE"; null for unknown
		 * @return the storage mode
		 */
		public static StorageMode fromState(String state) {
			if (state == null) {
				return BALANCING;
			}
			return switch (state) {
			case "CHARGE_GRID" -> CHARGE_GRID;
			case "DELAY_DISCHARGE", "PEAK_SHAVING", "DELAY_CHARGE", "LIMIT_CHARGE" -> NO_DISCHARGE;
			default -> BALANCING;
			};
		}
	}

	/**
	 * The rules of the controller, from its configuration.
	 *
	 * @param enablePriceCharging charge from grid below the price limit
	 * @param priceLimit          the price limit in [ct/kWh]
	 * @param priceLimitFullPower the price for full power in [ct/kWh]
	 * @param priceChargePower    the full power of price charging in [W]
	 * @param priority            STORAGE or CAR
	 * @param minPower            the minimum charge power in [W]
	 * @param maxPower            the maximum charge power in [W]
	 * @param pvPrice             the value of PV power in [ct/kWh]
	 * @param storagePrice        the replacement cost of storage power that PV
	 *                            refills in [ct/kWh]
	 * @param useStorageSurplus   storage power for the car is valued at the
	 *                            storage price; otherwise at the grid price
	 * @param targetWh            the evening target of the storage in [Wh] net
	 * @param bufferPower         the buffer below the feed-in in [W]
	 * @param gridMaxBuyPower     the grid buy limit in [W]; 0 for none
	 */
	public record Rules(boolean enablePriceCharging, double priceLimit, double priceLimitFullPower,
			int priceChargePower, Priority priority, int minPower, int maxPower, double pvPrice, double storagePrice,
			boolean useStorageSurplus, int targetWh, int bufferPower, int gridMaxBuyPower) {

		/**
		 * The rules with a lower maximum power, e.g. the measured power of the car.
		 *
		 * @param maxPower the maximum power in [W]
		 * @return the rules
		 */
		public Rules withMaxPower(int maxPower) {
			return new Rules(this.enablePriceCharging, this.priceLimit, this.priceLimitFullPower, this.priceChargePower,
					this.priority, this.minPower, Math.max(Math.min(this.maxPower, maxPower), this.minPower),
					this.pvPrice, this.storagePrice, this.useStorageSurplus, this.targetWh, this.bufferPower,
					this.gridMaxBuyPower);
		}
	}

	/**
	 * The storage, summed over all storages within their usable windows.
	 *
	 * @param storedWh          the stored energy within the windows in [Wh]
	 * @param usableWh          the usable capacity of the windows in [Wh]
	 * @param maxChargePower    the charge power in [W]
	 * @param maxDischargePower the discharge power in [W]
	 * @param efficiency        the charge efficiency, 0..1
	 */
	public record Storage(double storedWh, double usableWh, int maxChargePower, int maxDischargePower,
			double efficiency) {
	}

	/**
	 * The car.
	 *
	 * @param pluggedIn   true if a car is plugged in
	 * @param remainingWh the energy the car may still take in [Wh]; null if
	 *                    unlimited
	 */
	public record Car(boolean pluggedIn, Integer remainingWh) {
	}

	/**
	 * The forecast of one quarter-hour.
	 *
	 * @param time                   the start of the quarter-hour
	 * @param price                  the grid buy price in [ct/kWh]; null if
	 *                               unknown
	 * @param production             the predicted production in [W]; null if
	 *                               unknown
	 * @param consumption            the predicted consumption of the house without
	 *                               the car in [W]; null if unknown
	 * @param mode                   what the Time-of-Use-Tariff controller does
	 *                               with the storage; null for BALANCING
	 * @param storageGridChargePower the power the storage takes from grid in mode
	 *                               CHARGE_GRID in [W]
	 */
	public record Input(ZonedDateTime time, Double price, Integer production, Integer consumption, StorageMode mode,
			int storageGridChargePower) {

		public Input(ZonedDateTime time, Double price, Integer production, Integer consumption) {
			this(time, price, production, consumption, StorageMode.BALANCING, 0);
		}

		/**
		 * Gets the mode; BALANCING if null.
		 *
		 * @return the mode
		 */
		public StorageMode modeOrDefault() {
			return this.mode == null ? StorageMode.BALANCING : this.mode;
		}
	}

	/** Why the car charges or does not charge in a quarter-hour. */
	public enum Reason {
		/** No car plugged in. */
		NOT_PLUGGED,
		/** Forecast or price unknown. */
		UNKNOWN,
		/** Charges from the surplus the storage cannot take. */
		SURPLUS,
		/** Charges from grid below the price limit. */
		PRICE,
		/** Fills up to the minimum power at a blended price below the limit. */
		FILL_UP,
		/** Blended price above the limit. */
		TOO_EXPENSIVE,
		/** No storage budget, the storage would miss its evening target: too expensive at the grid price. */
		TARGET,
		/** The energy limit of the session is reached. */
		LIMIT,
		/** The grid connection limit leaves no room. */
		GRID_LIMIT,
		/** Target charging: one of the cheapest quarter-hours until the target time. */
		TARGET_CHARGE,
		/** Target charging: after the target time, the plan does not reach there. */
		AFTER_TARGET
	}

	/** State of target charging in a plan. */
	public enum TargetStatus {
		/** Not a target plan. */
		NONE,
		/** The cheapest quarter-hours until the target time cover the target. */
		PLANNED,
		/** Every usable quarter-hour is taken and the target is still missed. */
		UNREACHABLE,
		/** Prices or forecast do not reach the target time: no charging. */
		PRICES_MISSING,
		/** The session already holds the target energy. */
		REACHED
	}

	/**
	 * Target charging: charge an energy until a time of day in the cheapest
	 * quarter-hours.
	 *
	 * @param remainingWh the energy still missing in [Wh]
	 * @param deadline    the target time
	 */
	public record Target(int remainingWh, ZonedDateTime deadline) {
	}

	/**
	 * One quarter-hour of the plan.
	 *
	 * @param time         the start of the quarter-hour
	 * @param price        the grid buy price in [ct/kWh]
	 * @param production   the predicted production in [W]
	 * @param consumption  the predicted consumption of the house in [W]
	 * @param mode         what the Time-of-Use-Tariff controller does with the
	 *                     storage
	 * @param power        the planned charge power in [W]
	 * @param fromPv       the share that would otherwise be fed in, in [W]
	 * @param fromStorage   the share the storage charges less or discharges more,
	 *                      in [W]
	 * @param storageBudget the part of fromStorage that costs the storage price:
	 *                      PV refills it before the evening, in [W]
	 * @param fromGrid      the share bought from grid, in [W]
	 * @param blendedPrice  the blended price of the planned power, or of the
	 *                      minimum power if not charging, in [ct/kWh]; null if
	 *                      unknown
	 * @param reason        why the car charges or does not charge
	 * @param storageWh     the stored energy at the end of the quarter-hour in [Wh]
	 */
	public record Quarter(ZonedDateTime time, Double price, Integer production, Integer consumption, StorageMode mode,
			int power, int fromPv, int fromStorage, int storageBudget, int fromGrid, Double blendedPrice,
			Reason reason, double storageWh, boolean storageLocked) {
	}

	/**
	 * The plan.
	 *
	 * @param quarters         the quarter-hours
	 * @param start            the first quarter-hour with charging; null if none
	 * @param energyWh         the planned energy in [Wh]
	 * @param fromPvWh         the planned energy from PV in [Wh]
	 * @param fromStorageWh    the planned energy from the storage in [Wh]
	 * @param fromGridWh       the planned energy from grid in [Wh]
	 * @param storageAtPvEndWh the stored energy at the end of the running or next
	 *                         PV block in [Wh]; null if there is no PV block
	 * @param targetReached    true if the storage reaches its target at the end of
	 *                         every PV block
	 * @param targetStatus     the state of target charging; NONE for a plan by
	 *                         price
	 */
	public record Result(List<Quarter> quarters, ZonedDateTime start, int energyWh, int fromPvWh, int fromStorageWh,
			int fromGridWh, Double storageAtPvEndWh, boolean targetReached, TargetStatus targetStatus) {

		/** An empty plan. */
		public static final Result EMPTY = new Result(List.of(), null, 0, 0, 0, 0, null, true);

		public Result(List<Quarter> quarters, ZonedDateTime start, int energyWh, int fromPvWh, int fromStorageWh,
				int fromGridWh, Double storageAtPvEndWh, boolean targetReached) {
			this(quarters, start, energyWh, fromPvWh, fromStorageWh, fromGridWh, storageAtPvEndWh, targetReached,
					TargetStatus.NONE);
		}

		/**
		 * The same plan with a state of target charging.
		 *
		 * @param status the state
		 * @return the plan
		 */
		public Result withTargetStatus(TargetStatus status) {
			return new Result(this.quarters, this.start, this.energyWh, this.fromPvWh, this.fromStorageWh,
					this.fromGridWh, this.storageAtPvEndWh, this.targetReached, status);
		}

		/**
		 * The same plan with only the quarter-hours before the time, for the display;
		 * the totals stay.
		 *
		 * @param end the time
		 * @return the plan
		 */
		public Result until(ZonedDateTime end) {
			final var shown = this.quarters.stream().filter(q -> q.time().isBefore(end)).toList();
			if (shown.size() == this.quarters.size()) {
				return this;
			}
			return new Result(shown, this.start, this.energyWh, this.fromPvWh, this.fromStorageWh, this.fromGridWh,
					this.storageAtPvEndWh, this.targetReached, this.targetStatus);
		}

		/**
		 * Gets the cost of the planned energy: the blended price of every charging
		 * quarter-hour times its energy.
		 *
		 * @return the cost in [ct]
		 */
		public double costCt() {
			var result = 0.;
			for (var q : this.quarters) {
				if (q.power() > 0 && q.blendedPrice() != null) {
					result += q.power() / (double) QUARTERS_PER_HOUR / 1000. * q.blendedPrice();
				}
			}
			return result;
		}

		/**
		 * Gets the quarter-hour that contains the time.
		 *
		 * @param time the time
		 * @return the quarter-hour; null if the plan does not cover it
		 */
		public Quarter getAt(ZonedDateTime time) {
			for (var q : this.quarters) {
				if (!time.isBefore(q.time()) && time.isBefore(q.time().plusMinutes(15))) {
					return q;
				}
			}
			return null;
		}
	}

	private ChargingPlan() {
	}

	/**
	 * Calculates the plan.
	 *
	 * @param rules   the rules
	 * @param storage the storage now
	 * @param car     the car now
	 * @param inputs  the forecast per quarter-hour, in ascending order
	 * @return the plan
	 */
	public static Result calculate(Rules rules, Storage storage, Car car, List<Input> inputs) {
		if (inputs.isEmpty()) {
			return Result.EMPTY;
		}
		// The plan covers the running (or next) PV block and the night after it; the
		// PV of the day after is nobody's business today, the plan is recalculated
		// every quarter-hour anyway
		var blocks = findPvBlocks(inputs);
		if (blocks.size() > 1) {
			inputs = inputs.subList(0, blocks.get(1)[0]);
			blocks = blocks.subList(0, 1);
		}
		final var dropped = new HashSet<Integer>();
		Result result;
		while (true) {
			result = simulate(rules, storage, car, inputs, blocks, dropped);
			final var candidate = findQuarterToDrop(result, rules, blocks, dropped);
			if (candidate == null) {
				break;
			}
			dropped.add(candidate);
		}
		return result;
	}

	/** Production below this share of the predicted peak does not count as PV. */
	protected static final double PV_BLOCK_THRESHOLD = 0.03;

	/*
	 * Finds the PV blocks: runs of quarter-hours with production of at least 3 % of
	 * the predicted peak (a forecast may show a few Watt long after sunset), as
	 * pairs of first and last index.
	 */
	private static List<int[]> findPvBlocks(List<Input> inputs) {
		final var result = new ArrayList<int[]>();
		var peak = 0;
		for (var in : inputs) {
			if (in.production() != null) {
				peak = max(peak, in.production());
			}
		}
		final var threshold = max((int) Math.ceil(peak * PV_BLOCK_THRESHOLD), 1);
		int start = -1;
		for (var i = 0; i < inputs.size(); i++) {
			final var prod = inputs.get(i).production();
			final var pv = prod != null && prod >= threshold;
			if (pv && start < 0) {
				start = i;
			} else if (!pv && start >= 0) {
				result.add(new int[] { start, i - 1 });
				start = -1;
			}
		}
		if (start >= 0) {
			result.add(new int[] { start, inputs.size() - 1 });
		}
		return result;
	}

	private static boolean isInPvBlock(List<int[]> blocks, int index) {
		for (var b : blocks) {
			if (index >= b[0] && index <= b[1]) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Target charging locks the storage for the car while it is below its evening
	 * target.
	 *
	 * @param rules  the {@link Rules}
	 * @param stored the stored energy in [Wh]
	 * @return true if the storage may not discharge for the car
	 */
	public static boolean isStorageLocked(Rules rules, double stored) {
		return rules.targetWh() > 0 && stored < rules.targetWh();
	}

	/**
	 * The house and the storage of a quarter-hour without the car.
	 *
	 * @param surplus           production minus consumption in [W]
	 * @param roomPower         the power the storage can still take in [W]
	 * @param gridCharge        the planned charging from grid in [W]
	 * @param mayDischarge      true if the storage may discharge
	 * @param charge            the storage charging in [W]
	 * @param dischargeForHouse the storage discharging for the house in [W]
	 * @param feedIn            the feed-in in [W]
	 * @param buyWithout        the grid buy in [W]
	 * @param excess            the surplus the car may take in [W], by priority
	 */
	private record House(int surplus, double roomPower, int gridCharge, boolean mayDischarge, int charge,
			int dischargeForHouse, int feedIn, int buyWithout, int excess) {

		static House of(Rules rules, Storage storage, Input in, double stored) {
			final var mode = in.modeOrDefault();
			final var surplus = in.production() - in.consumption();
			final var roomPower = max(storage.usableWh() - stored, 0) * QUARTERS_PER_HOUR / storage.efficiency();
			final var gridCharge = mode == StorageMode.CHARGE_GRID ? max(in.storageGridChargePower(), 0) : 0;
			final var mayDischarge = mode == StorageMode.BALANCING;
			final var charge = (int) min(max(surplus, 0) + gridCharge, min(storage.maxChargePower(), roomPower));
			final var dischargeForHouse = mayDischarge
					? (int) min(max(-surplus, 0), min(storage.maxDischargePower(), stored * QUARTERS_PER_HOUR))
					: 0;
			final var feedIn = max(surplus, 0) - max(charge - gridCharge, 0);
			final var buyWithout = max(-surplus, 0) - dischargeForHouse + min(gridCharge, charge);
			final var excess = switch (rules.priority()) {
			case STORAGE -> max(feedIn - rules.bufferPower(), 0);
			case CAR -> max(surplus, 0);
			};
			return new House(surplus, roomPower, gridCharge, mayDischarge, charge, dischargeForHouse, feedIn,
					buyWithout, excess);
		}
	}

	/** Bonus for quarter-hours of the previous plan when planning again [ct/kWh]. */
	public static final double PLAN_PREFERENCE = 2.;

	/**
	 * Calculates the plan for target charging: the cheapest quarter-hours until
	 * the target time that cover the missing energy, regardless of the price
	 * limits.
	 *
	 * <p>
	 * Every quarter-hour offers its power in slices with their own price: the
	 * surplus the car may take at the PV price, storage power at the storage price
	 * while the storage is above its evening target (else at the grid price) and
	 * grid power at the grid price, up to the maximum power. The first minimum
	 * power of a quarter-hour is one slice at its blended price, because the car
	 * cannot charge below it; the rest is divisible. The cheapest slices are taken
	 * until the missing energy is covered, among equals the earlier ones. Without
	 * prices or forecast until the target time nothing is planned: charging blind
	 * is not what target charging is for.
	 *
	 * @param rules   the rules; maxPower is the upper limit of target charging
	 * @param storage the storage now
	 * @param car     the car now; remainingWh is the missing energy
	 * @param target  the target
	 * @param inputs  the forecast per quarter-hour, in ascending order
	 * @return the plan
	 */
	public static Result calculateTarget(Rules rules, Storage storage, Car car, Target target, List<Input> inputs) {
		return calculateTarget(rules, storage, car, target, inputs, Set.of());
	}

	/**
	 * Like {@link #calculateTarget(Rules, Storage, Car, Target, List)}, preferring
	 * the quarter-hours of a previous plan: a change needs a noticeable advantage,
	 * so a plan that was announced to the energy optimizer stays as it is.
	 *
	 * @param rules     the rules
	 * @param storage   the storage now
	 * @param car       the car now
	 * @param target    the target
	 * @param inputs    the forecast per quarter-hour
	 * @param preferred the start times of the previously planned quarter-hours
	 * @return the plan
	 */
	public static Result calculateTarget(Rules rules, Storage storage, Car car, Target target, List<Input> inputs,
			Set<ZonedDateTime> preferred) {
		final var horizon = targetHorizon(target, inputs);
		if (horizon.isEmpty()) {
			return Result.EMPTY.withTargetStatus(TargetStatus.PRICES_MISSING);
		}
		final var blocks = findPvBlocks(horizon);
		// the quarter-hours after the target time are shown, not planned
		final var display = displayHorizon(horizon, inputs);
		final var displayBlocks = findPvBlocks(display);
		final var noPower = new HashMap<Integer, Integer>();
		// the prices must reach the target time; the quarter-hour cut by it is not in
		// the horizon, so look at the inputs
		final var reachesDeadline = inputs.stream().anyMatch(in -> in.price() != null
				&& in.time().isBefore(target.deadline()) && !in.time().plusMinutes(15).isBefore(target.deadline()));
		final var pricesKnown = horizon.stream().allMatch(in -> in.price() != null) && reachesDeadline;
		if (!pricesKnown) {
			return simulate(rules, storage, car, display, displayBlocks, Set.of(), noPower, afterHorizon(horizon, display))
					.withTargetStatus(TargetStatus.PRICES_MISSING);
		}
		if (target.remainingWh() <= 0) {
			return simulate(rules, storage, new Car(car.pluggedIn(), 0), display, displayBlocks, Set.of(), noPower, afterHorizon(horizon, display))
					.withTargetStatus(TargetStatus.REACHED);
		}
		// The slices: the storage runs without the car, the car takes what every
		// quarter-hour offers. Storage power counts at the storage price only while
		// the storage stays above its evening target. What the car takes at one
		// quarter-hour is missing at every later one, so the surplus above the
		// target is a budget the car uses up from the cheapest quarter-hours on;
		// beyond it storage power costs the grid price, because the house buys from
		// grid instead
		record Slice(int index, boolean base, double wh, double pvWh, double storageWh, double gridWh) {
		}

		final var n = horizon.size();
		final var gridPrice = new double[n];
		final var surplusWh = new double[n]; // above the evening target, without the car
		final var bases = new ArrayList<Slice>();
		final var rests = new ArrayList<List<Slice>>();
		for (var i = 0; i < n; i++) {
			rests.add(new ArrayList<>());
		}
		var stored = min(max(storage.storedWh(), 0), storage.usableWh());
		for (var i = 0; i < n; i++) {
			final var in = horizon.get(i);
			final var h = House.of(rules, storage, in, stored);
			final var next = min(max(stored + h.charge() / (double) QUARTERS_PER_HOUR * storage.efficiency()
					- h.dischargeForHouse() / (double) QUARTERS_PER_HOUR, 0), storage.usableWh());
			if (in.production() == null || in.consumption() == null || in.price() == null) {
				stored = next;
				continue;
			}
			gridPrice[i] = in.price();
			surplusWh[i] = rules.useStorageSurplus() ? max(stored - rules.targetWh(), 0) : 0;
			final var storageUsable = isInPvBlock(blocks, i) && in.modeOrDefault() == StorageMode.BALANCING;
			final var reductionAvailable = storageUsable ? max(h.charge() - h.gridCharge(), 0) : 0;
			// below its evening target the storage is locked for the car: it only serves
			// the house, the car takes grid power instead
			final var dischargeAvailable = storageUsable && !isStorageLocked(rules, stored)
					? (int) max(min(storage.maxDischargePower(), stored * QUARTERS_PER_HOUR) - h.dischargeForHouse(), 0)
					: 0;
			final var gridAvailable = rules.gridMaxBuyPower() > 0 ? max(rules.gridMaxBuyPower() - h.buyWithout(), 0)
					: Integer.MAX_VALUE;
			final int[] powers = { h.excess(), reductionAvailable + dischargeAvailable, gridAvailable };
			// the first minimum power as one slice: PV, storage, grid in that order
			var missing = rules.minPower();
			final var base = new double[3];
			for (var k = 0; k < 3; k++) {
				final var share = min(powers[k], missing);
				base[k] = share;
				powers[k] -= share;
				missing -= share;
			}
			if (missing > 0) {
				// not even the minimum power: unusable
				stored = next;
				continue;
			}
			final var toWh = 1. / QUARTERS_PER_HOUR;
			bases.add(new Slice(i, true, rules.minPower() * toWh, base[0] * toWh, base[1] * toWh, base[2] * toWh));
			var room = max(rules.maxPower() - rules.minPower(), 0);
			for (var k = 0; k < 3 && room > 0; k++) {
				final var share = min(powers[k], room);
				if (share > 0) {
					final var wh = share * toWh;
					rests.get(i).add(new Slice(i, false, wh, k == 0 ? wh : 0, k == 1 ? wh : 0, k == 2 ? wh : 0));
					room -= share;
				}
			}
			stored = next;
		}
		// Greedy by price. The price of a slice with storage power depends on what the
		// car took from the storage before: the budget at a quarter-hour is the
		// smallest surplus of this and every later quarter-hour of its PV block after
		// the draws up to there, so that the draws already planned later stay
		// covered and the storage still holds its target at the end of PV
		final var drawn = new double[n];
		final var bonus = new double[n];
		final var blockEnd = new int[n];
		for (var i = 0; i < n; i++) {
			bonus[i] = preferred.contains(horizon.get(i).time()) ? PLAN_PREFERENCE : 0;
			blockEnd[i] = i;
			for (var bl : blocks) {
				if (i >= bl[0] && i <= bl[1]) {
					blockEnd[i] = bl[1];
				}
			}
		}
		final java.util.function.IntToDoubleFunction budget = i -> {
			var avail = Double.MAX_VALUE;
			var used = 0.;
			for (var j = 0; j <= blockEnd[i]; j++) {
				used += drawn[j];
				if (j >= i) {
					avail = min(avail, surplusWh[j] - used);
				}
			}
			return max(avail, 0);
		};
		final java.util.function.ToDoubleFunction<Slice> priceOf = sl -> {
			final var cheap = min(sl.storageWh(), budget.applyAsDouble(sl.index()));
			return (sl.pvWh() * rules.pvPrice() + cheap * rules.storagePrice()
					+ (sl.storageWh() - cheap + sl.gridWh()) * gridPrice[sl.index()]) / sl.wh();
		};
		record Entry(double key, Slice slice) {
		}

		final var queue = new java.util.PriorityQueue<Entry>(Comparator.comparingDouble(Entry::key) //
				.thenComparingInt(e -> e.slice().index()) //
				.thenComparing(e -> !e.slice().base()));
		for (var sl : bases) {
			queue.add(new Entry(priceOf.applyAsDouble(sl) - bonus[sl.index()], sl));
		}
		final var takenWh = new double[n];
		var need = (double) target.remainingWh();
		while (need > 0 && !queue.isEmpty()) {
			final var e = queue.poll();
			final var sl = e.slice();
			final var key = priceOf.applyAsDouble(sl) - bonus[sl.index()];
			if (key > e.key() + 1e-9) {
				// the storage budget shrank since: back with the real price
				queue.add(new Entry(key, sl));
				continue;
			}
			// the base is indivisible, the rest is taken as far as needed
			final var wh = sl.base() ? sl.wh() : min(sl.wh(), need);
			final var cheap = min(sl.storageWh() * wh / sl.wh(), budget.applyAsDouble(sl.index()));
			drawn[sl.index()] += cheap;
			takenWh[sl.index()] += wh;
			need -= wh;
			if (sl.base()) {
				for (var rest : rests.get(sl.index())) {
					queue.add(new Entry(priceOf.applyAsDouble(rest) - bonus[rest.index()], rest));
				}
			}
		}
		final var fixedPower = new HashMap<Integer, Integer>();
		for (var i = 0; i < takenWh.length; i++) {
			if (takenWh[i] > 0) {
				fixedPower.put(i, (int) Math.round(takenWh[i] * QUARTERS_PER_HOUR));
			}
		}
		final var status = need > 0.5 ? TargetStatus.UNREACHABLE : TargetStatus.PLANNED;
		return simulate(rules, storage, car, display, displayBlocks, Set.of(), fixedPower, afterHorizon(horizon, display))
				.withTargetStatus(status);
	}

	/**
	 * The quarter-hours until the target time. The forecast usually ends before
	 * the prices do: a quarter-hour with a price but without forecast counts as
	 * night without PV and without house load, i.e. the car pays the grid price.
	 *
	 * @param target the target
	 * @param inputs the forecast per quarter-hour
	 * @return the quarter-hours before the target time
	 */
	/**
	 * The horizon of target charging with the quarter-hours after it, so that the
	 * plan shows the prices as far as they are known, like the Time-of-Use-Tariff.
	 *
	 * @param horizon the horizon, a prefix of the inputs
	 * @param inputs  the inputs
	 * @return the horizon and the rest of the inputs
	 */
	private static List<Input> displayHorizon(List<Input> horizon, List<Input> inputs) {
		final var result = new ArrayList<Input>(horizon);
		result.addAll(inputs.subList(Math.min(horizon.size(), inputs.size()), inputs.size()));
		return result;
	}

	/**
	 * The first quarter-hour after the horizon of target charging; null if the
	 * inputs end with the horizon.
	 *
	 * @param horizon the horizon
	 * @param display the horizon with the rest of the inputs
	 * @return the time; null if none
	 */
	private static ZonedDateTime afterHorizon(List<Input> horizon, List<Input> display) {
		return display.size() > horizon.size() ? display.get(horizon.size()).time() : null;
	}

	private static List<Input> targetHorizon(Target target, List<Input> inputs) {
		final var horizon = new ArrayList<Input>();
		final var first = inputs.isEmpty() ? null : inputs.get(0).time();
		for (var in : inputs) {
			if (!in.time().isBefore(target.deadline())) {
				continue;
			}
			// a quarter-hour that ends after the target time charges only partly
			// before it: it does not count, except the current one
			if (!in.time().equals(first) && in.time().plusMinutes(15).isAfter(target.deadline())) {
				continue;
			}
			if (in.price() != null && (in.production() == null || in.consumption() == null)) {
				horizon.add(new Input(in.time(), in.price(), in.production() == null ? 0 : in.production(),
						in.consumption() == null ? 0 : in.consumption(), in.mode(), in.storageGridChargePower()));
			} else {
				horizon.add(in);
			}
		}
		return horizon;
	}

	/**
	 * Applies a plan that was made before: the car charges the given power per
	 * quarter-hour, the rest is simulated with the current forecast. The energy
	 * optimizer got this plan, so the controller follows it instead of planning
	 * again every quarter-hour.
	 *
	 * @param rules       the rules
	 * @param storage     the storage now
	 * @param car         the car now; remainingWh is the missing energy
	 * @param target      the target
	 * @param inputs      the forecast per quarter-hour
	 * @param powerByTime the planned power per quarter-hour start in [W]
	 * @return the plan
	 */
	public static Result applyTarget(Rules rules, Storage storage, Car car, Target target, List<Input> inputs,
			Map<ZonedDateTime, Integer> powerByTime) {
		final var horizon = targetHorizon(target, inputs);
		if (horizon.isEmpty()) {
			return Result.EMPTY.withTargetStatus(TargetStatus.PRICES_MISSING);
		}
		final var blocks = findPvBlocks(horizon);
		// the quarter-hours after the target time are shown, not planned
		final var display = displayHorizon(horizon, inputs);
		final var displayBlocks = findPvBlocks(display);
		if (target.remainingWh() <= 0) {
			return simulate(rules, storage, new Car(car.pluggedIn(), 0), display, displayBlocks, Set.of(), new HashMap<>(), afterHorizon(horizon, display)).withTargetStatus(TargetStatus.REACHED);
		}
		final var fixedPower = new HashMap<Integer, Integer>();
		var plannedWh = 0.;
		for (var i = 0; i < horizon.size(); i++) {
			final var power = powerByTime.get(horizon.get(i).time());
			if (power != null && power > 0) {
				fixedPower.put(i, power);
				plannedWh += power / (double) QUARTERS_PER_HOUR;
			}
		}
		final var status = plannedWh + 0.5 < target.remainingWh() ? TargetStatus.UNREACHABLE : TargetStatus.PLANNED;
		return simulate(rules, storage, car, display, displayBlocks, Set.of(), fixedPower, afterHorizon(horizon, display))
				.withTargetStatus(status);
	}

	private static Result simulate(Rules rules, Storage storage, Car car, List<Input> inputs, List<int[]> blocks,
			Set<Integer> dropped) {
		return simulate(rules, storage, car, inputs, blocks, dropped, null, null);
	}

	/*
	 * Simulates storage and car. With fixedPower (target charging) the car charges
	 * the given power per quarter-hour index and nothing else.
	 */
	private static Result simulate(Rules rules, Storage storage, Car car, List<Input> inputs, List<int[]> blocks,
			Set<Integer> dropped, Map<Integer, Integer> fixedPower, ZonedDateTime fixedUntil) {
		final var quarters = new ArrayList<Quarter>(inputs.size());
		var stored = min(max(storage.storedWh(), 0), storage.usableWh());
		var remaining = car.remainingWh();
		ZonedDateTime start = null;
		var energy = 0.;
		var fromPv = 0.;
		var fromStorage = 0.;
		var fromGrid = 0.;
		for (var i = 0; i < inputs.size(); i++) {
			final var in = inputs.get(i);
			final var prod = in.production();
			final var cons = in.consumption();
			final var price = in.price();
			final var mode = in.modeOrDefault();
			int power = 0;
			int pvShare = 0;
			int storageShare = 0;
			int storageBudget = 0;
			int gridShare = 0;
			Double blended = null;
			Reason reason;
			var storageLockedNow = false;
			if (prod == null || cons == null || price == null) {
				reason = Reason.UNKNOWN;
			} else {
				final var inPvBlock = isInPvBlock(blocks, i);
				// without storage budget the storage share costs the grid price
				final var budget = !dropped.contains(i);
				// the storage without the car
				final var h = House.of(rules, storage, in, stored);
				final var surplus = h.surplus();
				final var roomPower = h.roomPower();
				final var gridCharge = h.gridCharge();
				final var mayDischarge = h.mayDischarge();
				final var charge = h.charge();
				final var dischargeForHouse = h.dischargeForHouse();
				final var buyWithout = h.buyWithout();
				final var excess = h.excess();
				final var pricePower = rules.enablePriceCharging()
						? ControllerEvcsPriceImpl.calculateChargePowerFromPrice(price * 10, rules.priceLimit(),
								rules.priceLimitFullPower(), rules.minPower(), rules.priceChargePower())
						: 0;
				var targetCharging = false;
				if (!car.pluggedIn()) {
					reason = Reason.NOT_PLUGGED;
				} else if (fixedPower != null && fixedUntil != null && !in.time().isBefore(fixedUntil)) {
					// after the horizon of target charging: shown, not planned
					reason = Reason.AFTER_TARGET;
				} else if (remaining != null && remaining <= 0) {
					reason = Reason.LIMIT;
				} else if (fixedPower != null) {
					// target charging: the plan decided, the price limits do not count
					final var fixed = fixedPower.getOrDefault(i, 0);
					if (fixed >= rules.minPower()) {
						power = min(fixed, rules.maxPower());
						reason = Reason.TARGET_CHARGE;
						targetCharging = true;
					} else {
						reason = Reason.TOO_EXPENSIVE;
					}
				} else if (excess >= rules.minPower()) {
					power = min(max(excess, pricePower), rules.maxPower());
					reason = Reason.SURPLUS;
				} else if (pricePower > 0) {
					power = min(pricePower, rules.maxPower());
					reason = Reason.PRICE;
				} else {
					// fill up to the minimum power: PV first, then the storage, the rest from
					// grid. Storage power costs the storage price while PV refills it before
					// the evening: the storage charging the car displaces always, a discharge
					// only while the storage is above its target. A discharge below the target
					// makes the house buy from grid instead: it costs the grid price
					final var missing = rules.minPower() - excess;
					final var storageUsable = inPvBlock && mode == StorageMode.BALANCING;
					final var dischargeAvailable = storageUsable
							? (int) max(min(storage.maxDischargePower(), stored * QUARTERS_PER_HOUR)
									- dischargeForHouse, 0)
							: 0;
					final var reductionShare = min(missing, storageUsable ? charge - gridCharge : 0);
					final var dischargeShare = min(missing - reductionShare, dischargeAvailable);
					final var priced = rules.useStorageSurplus() && budget;
					final var reductionPrice = priced ? rules.storagePrice() : price;
					final var dischargePrice = priced && stored >= rules.targetWh() ? rules.storagePrice() : price;
					final var essShare = reductionShare + dischargeShare;
					blended = (excess * rules.pvPrice() + reductionShare * reductionPrice
							+ dischargeShare * dischargePrice + (missing - essShare) * price) / rules.minPower();
					if ((excess > 0 || essShare > 0) && blended < rules.priceLimit()) {
						power = rules.minPower();
						reason = Reason.FILL_UP;
					} else {
						reason = budget ? Reason.TOO_EXPENSIVE : Reason.TARGET;
					}
				}
				if (power > 0 && remaining != null) {
					// target charging keeps the minimum power for the last quarter-hour: the
					// car cannot charge below it and the target is a minimum, not a limit
					final var cap = targetCharging
							? max((long) remaining * QUARTERS_PER_HOUR, rules.minPower())
							: (long) remaining * QUARTERS_PER_HOUR;
					power = (int) min(power, cap);
					if (power < rules.minPower()) {
						power = 0;
						reason = Reason.LIMIT;
					}
				}
				// the storage with the car; the grid connection limit is shared with the house
				// and the storage, so the car is reduced by what exceeds it. Target charging
				// locks the storage for the car while it is below its evening target: it
				// discharges for the house only
				final var locked = targetCharging && isStorageLocked(rules, stored);
				storageLockedNow = locked;
				var net = surplus - power;
				var chargeWith = (int) min(max(net, 0) + gridCharge, min(storage.maxChargePower(), roomPower));
				var dischargeWith = mayDischarge
						? (int) min(max(-net, 0), min(storage.maxDischargePower(), stored * QUARTERS_PER_HOUR))
						: 0;
				if (locked) {
					dischargeWith = min(dischargeWith, dischargeForHouse);
				}
				var buyWith = max(-net, 0) - dischargeWith + min(gridCharge, chargeWith);
				if (power > 0 && rules.gridMaxBuyPower() > 0 && buyWith > rules.gridMaxBuyPower()) {
					power -= buyWith - rules.gridMaxBuyPower();
					if (power < rules.minPower()) {
						power = 0;
						reason = Reason.GRID_LIMIT;
					}
					net = surplus - power;
					chargeWith = (int) min(max(net, 0) + gridCharge, min(storage.maxChargePower(), roomPower));
					dischargeWith = mayDischarge
							? (int) min(max(-net, 0), min(storage.maxDischargePower(), stored * QUARTERS_PER_HOUR))
							: 0;
					if (locked) {
						dischargeWith = min(dischargeWith, dischargeForHouse);
					}
					buyWith = max(-net, 0) - dischargeWith + min(gridCharge, chargeWith);
				}
				if (power > 0) {
					gridShare = min(max(buyWith - buyWithout, 0), power);
					// with priority STORAGE the storage charging the car displaces counts as
					// storage power; with priority CAR the surplus is the car's by definition and
					// only an additional discharge counts
					storageShare = switch (rules.priority()) {
					case STORAGE -> max((charge - dischargeForHouse) - (chargeWith - dischargeWith), 0);
					case CAR -> max(dischargeWith - dischargeForHouse, 0);
					};
					storageShare = min(storageShare, power - gridShare);
					pvShare = power - storageShare - gridShare;
					// the storage charging the car displaces is refilled by PV; an additional
					// discharge only counts at the storage price while the storage is above its
					// target, below the target the house buys from grid instead
					final var reduction = min(storageShare, max(charge - chargeWith, 0));
					final var extraDischarge = storageShare - reduction;
					final var storageUsable = rules.useStorageSurplus() && budget && inPvBlock
							&& mode == StorageMode.BALANCING;
					final var reductionPrice = storageUsable ? rules.storagePrice() : price;
					final var dischargePrice = storageUsable && stored >= rules.targetWh() ? rules.storagePrice() : price;
					storageBudget = (storageUsable ? reduction : 0)
							+ (storageUsable && stored >= rules.targetWh() ? extraDischarge : 0);
					blended = (pvShare * rules.pvPrice() + reduction * reductionPrice + extraDischarge * dischargePrice
							+ gridShare * price) / power;
					if (start == null) {
						start = in.time();
					}
					energy += power / (double) QUARTERS_PER_HOUR;
					fromPv += pvShare / (double) QUARTERS_PER_HOUR;
					fromStorage += storageShare / (double) QUARTERS_PER_HOUR;
					fromGrid += gridShare / (double) QUARTERS_PER_HOUR;
					if (remaining != null) {
						remaining -= (int) Math.round(power / (double) QUARTERS_PER_HOUR);
					}
				}
				stored += chargeWith / (double) QUARTERS_PER_HOUR * storage.efficiency()
						- dischargeWith / (double) QUARTERS_PER_HOUR;
				stored = min(max(stored, 0), storage.usableWh());
			}
			quarters.add(new Quarter(in.time(), price, prod, cons, mode, power, pvShare, storageShare, storageBudget,
					gridShare, blended, reason, stored, storageLockedNow));
		}
		Double storageAtPvEnd = null;
		var targetReached = true;
		for (var b : blocks) {
			final var atEnd = quarters.get(b[1]).storageWh();
			if (storageAtPvEnd == null) {
				storageAtPvEnd = atEnd;
			}
			if (atEnd + 0.5 < rules.targetWh()) {
				targetReached = false;
			}
		}
		return new Result(quarters, start, (int) Math.round(energy), (int) Math.round(fromPv),
				(int) Math.round(fromStorage), (int) Math.round(fromGrid), storageAtPvEnd, targetReached);
	}

	/*
	 * Finds the quarter-hour to withdraw the storage budget from: in the first PV
	 * block that misses the target, the charging quarter-hour with storage budget
	 * and the smallest PV share.
	 */
	private static Integer findQuarterToDrop(Result result, Rules rules, List<int[]> blocks, Set<Integer> dropped) {
		for (var b : blocks) {
			if (result.quarters().get(b[1]).storageWh() + 0.5 >= rules.targetWh()) {
				continue;
			}
			Integer best = null;
			var bestRatio = Double.MAX_VALUE;
			for (var i = b[0]; i <= b[1]; i++) {
				final var q = result.quarters().get(i);
				if (q.power() <= 0 || q.storageBudget() <= 0 || dropped.contains(i)) {
					continue;
				}
				// the smallest PV share first; among equals the latest, so that the car
				// takes what it may take as early as possible
				final var ratio = q.fromPv() / (double) q.power();
				if (ratio <= bestRatio) {
					best = i;
					bestRatio = ratio;
				}
			}
			if (best != null) {
				return best;
			}
			// nothing left to drop in this block: the target is missed without the car
		}
		return null;
	}
}
