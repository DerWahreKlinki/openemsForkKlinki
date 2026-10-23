package io.openems.edge.controller.evcs.price;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

import io.openems.edge.evcs.api.ManagedEvcs;

public class ChargingLowerThanTargetHandler {

	/**
	 * The set charge limit is compared with the actual charge power. If for '3'
	 * times the evcs is not using all of the reserved charge power, the maxPower is
	 * reduced to the actually used charge power so that there is power left to be
	 * distributed to other EVCS.
	 */
	private static final int MAXIMUM_OUT_OF_RANGE_TRIES = 3;
	private int outOfRangeCounter = 0;
	private static final double CHARGING_TARGET_MAX_DIFFERENCE_PERCENT = 0.15; // 10%
	private static final int CHECK_CHARGING_TARGET_DIFFERENCE_TIME = 45; // sec

	private final Supplier<Clock> clock;

	// null until the first check
	private Instant lastChargingCheck = null;
	private Integer maximumChargePower = null; // W

	public ChargingLowerThanTargetHandler(Supplier<Clock> clock) {
		this.clock = clock;
	}

	/**
	 * Check if the difference between the requested charging target and the real
	 * charging power is higher than the CHARGING_TARGET_MAX_DIFFERENCE for at least
	 * MAXIMUM_OUT_OF_RANGE_TRIES.
	 *
	 * @param evcs EVCS
	 * @return true if the difference is too high
	 */
	protected boolean isLower(ManagedEvcs evcs) {
		final var now = Instant.now(this.clock.get());
		if (this.lastChargingCheck == null) {
			this.lastChargingCheck = now;
			return false;
		}
		if (this.lastChargingCheck.plusSeconds(CHECK_CHARGING_TARGET_DIFFERENCE_TIME).isBefore(now)) {
			if (this.isChargingLowerThanTarget(evcs)) {

				this.outOfRangeCounter++;
				if (this.outOfRangeCounter >= MAXIMUM_OUT_OF_RANGE_TRIES) {
					return true;
				}
			} else {
				this.outOfRangeCounter = 0;
			}
			this.lastChargingCheck = now;
		}
		return false;
	}

	/**
	 * Check if the difference between the requested charging target and the real
	 * charging power is higher than the CHARGING_TARGET_MAX_DIFFERENCE. If it
	 * returns true, it is setting the maximumPower.
	 *
	 * @param evcs EVCS
	 * @return true if the difference is too high
	 */
	protected boolean isChargingLowerThanTarget(ManagedEvcs evcs) {
		int chargePower = evcs.getActivePower().orElse(0);
		final var target = evcs.getSetChargePowerLimit().asOptional()
				.or(() -> evcs.getMaximumHardwarePower().asOptional());
		if (target.isEmpty()) {
			// neither a limit nor the hardware known: nothing to compare
			this.maximumChargePower = null;
			return false;
		}
		int chargePowerTarget = target.get();

		if (chargePowerTarget - chargePower > chargePowerTarget * CHARGING_TARGET_MAX_DIFFERENCE_PERCENT) {
			this.maximumChargePower = this.calculateMaximumPower(chargePower);
			this.lastChargingCheck = Instant.now(this.clock.get());
			return true;
		}
		this.maximumChargePower = null;
		return false;
	}

	/**
	 * Returns the calculated maximum charge power depending on the current charge
	 * power and the current maximum charge power.
	 *
	 * @param currentChargePower current charge power
	 * @return the current charge power or one of the past charge power values
	 */
	private Integer calculateMaximumPower(int currentChargePower) {
		if (this.maximumChargePower == null) {
			return currentChargePower;
		}
		return currentChargePower > this.maximumChargePower ? currentChargePower : this.maximumChargePower;
	}

	/**
	 * Maximum charge power of the EV depending on the last
	 * {@link #isChargingLowerThanTarget(ManagedEvcs)} try's.
	 *
	 * @return The maximum charge power of the EV.
	 */
	/**
	 * Forgets the measured maximum: a new session, maybe another car.
	 */
	protected void reset() {
		this.outOfRangeCounter = 0;
		this.maximumChargePower = null;
	}

	protected Integer getMaximumChargePower() {
		return this.maximumChargePower;
	}
}
