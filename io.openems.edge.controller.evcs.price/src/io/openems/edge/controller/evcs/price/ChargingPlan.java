package io.openems.edge.controller.evcs.price;

import static java.lang.Math.max;
import static java.lang.Math.min;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * end of that block. Where it does not, the quarter-hours with the smallest PV
 * share are dropped first until the target holds. Outside of PV blocks the
 * storage is reserved for the house.
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
		/** Dropped: the storage would miss its evening target. */
		TARGET,
		/** The energy limit of the session is reached. */
		LIMIT,
		/** The grid connection limit leaves no room. */
		GRID_LIMIT
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
			Reason reason, double storageWh) {
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
	 */
	public record Result(List<Quarter> quarters, ZonedDateTime start, int energyWh, int fromPvWh, int fromStorageWh,
			int fromGridWh, Double storageAtPvEndWh, boolean targetReached) {

		/** An empty plan. */
		public static final Result EMPTY = new Result(List.of(), null, 0, 0, 0, 0, null, true);

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
		final var blocks = findPvBlocks(inputs);
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

	/*
	 * Finds the PV blocks: runs of quarter-hours with production, as pairs of
	 * first and last index.
	 */
	private static List<int[]> findPvBlocks(List<Input> inputs) {
		final var result = new ArrayList<int[]>();
		int start = -1;
		for (var i = 0; i < inputs.size(); i++) {
			final var prod = inputs.get(i).production();
			final var pv = prod != null && prod > 0;
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

	private static Result simulate(Rules rules, Storage storage, Car car, List<Input> inputs, List<int[]> blocks,
			Set<Integer> dropped) {
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
			if (prod == null || cons == null || price == null) {
				reason = Reason.UNKNOWN;
			} else {
				final var inPvBlock = isInPvBlock(blocks, i);
				final var surplus = prod - cons;
				final var roomPower = max(storage.usableWh() - stored, 0) * QUARTERS_PER_HOUR / storage.efficiency();
				final var gridCharge = mode == StorageMode.CHARGE_GRID ? max(in.storageGridChargePower(), 0) : 0;
				final var mayDischarge = mode == StorageMode.BALANCING;
				// the storage without the car
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
				final var pricePower = rules.enablePriceCharging()
						? ControllerEvcsPriceImpl.calculateChargePowerFromPrice(price * 10, rules.priceLimit(),
								rules.priceLimitFullPower(), rules.minPower(), rules.priceChargePower())
						: 0;
				if (!car.pluggedIn()) {
					reason = Reason.NOT_PLUGGED;
				} else if (remaining != null && remaining <= 0) {
					reason = Reason.LIMIT;
				} else if (dropped.contains(i)) {
					reason = Reason.TARGET;
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
					final var reductionPrice = rules.useStorageSurplus() ? rules.storagePrice() : price;
					final var dischargePrice = rules.useStorageSurplus() && stored >= rules.targetWh()
							? rules.storagePrice()
							: price;
					final var essShare = reductionShare + dischargeShare;
					blended = (excess * rules.pvPrice() + reductionShare * reductionPrice
							+ dischargeShare * dischargePrice + (missing - essShare) * price) / rules.minPower();
					if ((excess > 0 || essShare > 0) && blended < rules.priceLimit()) {
						power = rules.minPower();
						reason = Reason.FILL_UP;
					} else {
						reason = Reason.TOO_EXPENSIVE;
					}
				}
				if (power > 0 && remaining != null) {
					power = (int) min(power, (long) remaining * QUARTERS_PER_HOUR);
					if (power < rules.minPower()) {
						power = 0;
						reason = Reason.LIMIT;
					}
				}
				// the storage with the car; the grid connection limit is shared with the house
				// and the storage, so the car is reduced by what exceeds it
				var net = surplus - power;
				var chargeWith = (int) min(max(net, 0) + gridCharge, min(storage.maxChargePower(), roomPower));
				var dischargeWith = mayDischarge
						? (int) min(max(-net, 0), min(storage.maxDischargePower(), stored * QUARTERS_PER_HOUR))
						: 0;
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
					final var storageUsable = rules.useStorageSurplus() && inPvBlock && mode == StorageMode.BALANCING;
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
						remaining -= power / QUARTERS_PER_HOUR;
					}
				}
				stored += chargeWith / (double) QUARTERS_PER_HOUR * storage.efficiency()
						- dischargeWith / (double) QUARTERS_PER_HOUR;
				stored = min(max(stored, 0), storage.usableWh());
			}
			quarters.add(new Quarter(in.time(), price, prod, cons, mode, power, pvShare, storageShare, storageBudget,
					gridShare, blended, reason, stored));
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
	 * Finds the quarter-hour to drop: in the first PV block that misses the target,
	 * the charging quarter-hour with storage share and the smallest PV share.
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
				if (q.power() <= 0 || q.fromStorage() <= 0 || dropped.contains(i)) {
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
