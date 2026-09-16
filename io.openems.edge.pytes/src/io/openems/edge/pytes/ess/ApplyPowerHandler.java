package io.openems.edge.pytes.ess;

import org.slf4j.Logger;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.pytes.battery.PytesBattery;
import io.openems.edge.pytes.dccharger.PytesDcCharger;
import io.openems.edge.pytes.enums.EnableDisable;
import io.openems.edge.pytes.enums.RemoteDispatchRealtimeControlSwitch;
import io.openems.edge.pytes.enums.RemoteDispatchSystemLimitSwitch;
import io.openems.edge.pytes.enums.WorkState;

public class ApplyPowerHandler {

	// === Dependencies ===
	private final PytesJs3Impl ess;
	private final PytesBattery battery;
	private final PytesDcCharger dcCharger;
	private final Logger log;

	// === Feed-forward ===
	// Measured 2026-09-16 on the live system: the inverter applies a constant
	// bias of ~190-220 W towards charging to the commanded battery power (500 W
	// discharge commanded -> 300 W delivered; 200 W charge commanded -> 418 W
	// delivered), and conversion losses between battery and AC side that grow
	// with the total inverter throughput (battery + PV): ~50 W @ 0.8 kW,
	// ~160 W @ 4.2 kW, ~210 W @ 6.5 kW. Both are compensated up-front so a new
	// set-point is right within the inverter's own dead time (~10 s). The bias
	// does not apply at 0 W. The model is kept slightly conservative; the trim
	// covers the rest. Shared with AllowedChargeDischargeHandler so the limits
	// reported to the solver are what actually arrives on the AC side.
	static final int BIAS_W = 190;
	static final int LOSS_BASE_W = 30;
	static final double LOSS_FACTOR = 0.03; // of |battery| + PV
	private static final int MIN_TARGET_W = 50; // below this the inverter is treated as idle
	private static final int FAILSAFE_MINUTES = 5; // reg 44101: inverter falls back to self-use after this

	// === Setpoint trim ===
	// A slow integral correction on the AC-side battery contribution
	// (ActivePower - PV, which is what Sum, UI and all OpenEMS controllers use)
	// removes what the feed-forward model does not cover. The residual differs
	// by direction, so charge and discharge keep their own trim and a sign
	// change needs no re-settling. All time constants are in milliseconds and
	// scaled with the configured cycle time.
	private static final double TRIM_GAIN_PER_S = 0.04; // -> ~25 s time constant
	private static final int TRIM_LIMIT = 300; // W, anti-windup
	private static final int TRIM_WARMUP_MS = 30_000; // BMS values are unreliable right after start
	private static final int TRIM_FREEZE_MS = 12_000; // no integration while the inverter follows a step
	private static final int TRIM_FREEZE_STEP_W = 100; // step size that triggers the freeze
	private double trimDischarge = 0;
	private double trimCharge = 0;
	private long elapsedMs = 0;
	private long freezeUntilMs = 0;
	private Integer lastAcBatteryTarget = null;

	/**
	 * Expected conversion losses between battery and AC side.
	 *
	 * @param batteryPower battery power in W (sign irrelevant)
	 * @param pvPower      PV power in W
	 * @return losses in W
	 */
	static int expectedLosses(int batteryPower, int pvPower) {
		return LOSS_BASE_W + (int) Math.round(LOSS_FACTOR * (Math.abs(batteryPower) + Math.max(0, pvPower)));
	}

	public ApplyPowerHandler(PytesJs3Impl ess, PytesBattery battery, PytesDcCharger dcCharger) {
		this.ess = ess;
		this.battery = battery;
		this.dcCharger = dcCharger;
		this.log = ess.getLogger();
	}

	/**
	 * Applies the given power setpoint to the ESS via remote dispatch.
	 *
	 * @param activePowerTarget         the target active power in W
	 * @param reactivePower             the target reactive power in var
	 * @param configuredMaxApparentPower the configured maximum apparent power in VA
	 * @param essSetpoint               the remote dispatch control mode to apply
	 * @throws OpenemsNamedException on error
	 */
	public void apply(int activePowerTarget, int reactivePower, int configuredMaxApparentPower, RemoteDispatchRealtimeControlSwitch essSetpoint)
			throws OpenemsNamedException {

		// --- Guards ---
		if (!this.ess.isManaged()) {
			this.log.debug("[ApplyPower] ReadOnly Mode enabled. Skip ApplyPower");
			return;
		}
		
		if (this.ess.getWorkState() != WorkState.NORMAL) {
			this.log.debug("ESS not in normal mode. Skipping ApplyPower");
			return;
		}
		Integer maxAllowedChargePower = this.ess.getAllowedChargePower().get();
		Integer maxAllowedDischargePower = this.ess.getAllowedDischargePower().get(); // includes PV

		Integer maxApparentPower = this.ess.getMaxApparentPower().get();

		if (maxApparentPower == null) {
			this.log.debug("[ApplyPower] maxApparentPower is null. Skipping ApplyPower");
			return;
		}

		if (maxAllowedChargePower == null) {
			this.log.debug("[ApplyPower] maxAllowedChargePower is null. Skipping ApplyPower");
			return;
		}

		if (maxAllowedDischargePower == null) {
			this.log.debug("[ApplyPower] maxAllowedDischargePower is null. Skipping ApplyPower");
			return;
		}

		Integer batteryPower = this.battery.getDcDischargePower().get();

		if (batteryPower == null) {
			this.log.debug("[ApplyPower] batteryPower is null. Skipping ApplyPower");
			return;
		}

		Integer essActivePower = this.ess.getActivePower().get();

		if (essActivePower == null) {
			this.log.debug("[ApplyPower] essActivePower is null. Skipping ApplyPower");
			return;
		}

		Integer essDcDischargePower = this.ess.getDcDischargePower().get();

		if (essDcDischargePower == null) {
			this.log.debug("[ApplyPower] essDcDischargePower is null. Skipping ApplyPower");
			return;
		}


		// guards for AC
		maxApparentPower = Math.min(maxApparentPower, configuredMaxApparentPower);
		if (activePowerTarget > 0) { // discharging
			activePowerTarget = Math.min(activePowerTarget, maxApparentPower);
		} else {
			activePowerTarget = Math.max(activePowerTarget, -maxApparentPower);
		}

		int pvPower = this.dcCharger != null ? this.dcCharger.getActualPower().orElse(0) : 0; // Maybe no pv connected
		int maxAllowedBatteryDischargePower = 0;
		int sign = 1;
		int batteryPowerTarget = 0;

		if (essSetpoint == RemoteDispatchRealtimeControlSwitch.BATTERY_CONTROL) {
			// guards for DC: clamp on the raw BMS limit, not on the (reduced) AC-side
			// value reported to the solver
			maxAllowedBatteryDischargePower = Math.max(0, this.ess.getBatteryDischargeLimit());
			batteryPowerTarget = activePowerTarget - pvPower;
			sign = -1; // negative setpoint at batteryControl setpoint
		} else {
			// grid point / AC port control: the value is an AC power
			batteryPowerTarget = activePowerTarget;
			maxAllowedBatteryDischargePower = Math.max(0, maxAllowedDischargePower);
		}



		// AC-side battery target (positive = discharge) - this is what has to show
		// up as ActivePower - PV.
		int acBatteryTarget = batteryPowerTarget;
		boolean idle = Math.abs(acBatteryTarget) < MIN_TARGET_W;

		// Feed-forward: bias and losses always act in discharge direction.
		final int feedForward = idle ? 0 : BIAS_W + expectedLosses(acBatteryTarget, pvPower);

		// Time base, scaled with the configured cycle time
		int cycleTimeMs = this.ess.getCycleTime();
		this.elapsedMs += cycleTimeMs;
		if (this.lastAcBatteryTarget != null
				&& Math.abs(acBatteryTarget - this.lastAcBatteryTarget) > TRIM_FREEZE_STEP_W) {
			this.freezeUntilMs = this.elapsedMs + TRIM_FREEZE_MS;
		}
		this.lastAcBatteryTarget = acBatteryTarget;

		// Integral trim on the AC-side battery contribution. Only integrate when
		// the set-point is not sitting on a BMS limit (anti-windup: with the
		// current trim applied), not idle, not right after a step (the inverter
		// needs its dead time first) and the measurements are plausible (BMS
		// values are garbage right after start).
		int acBatteryPower = essActivePower - pvPower;
		int plausibleLimit = Math.max(maxAllowedDischargePower, -maxAllowedChargePower) + 500;
		boolean plausible = Math.abs(batteryPower) <= plausibleLimit && Math.abs(acBatteryPower) <= plausibleLimit;
		boolean discharging = acBatteryTarget > 0;
		double currentTrim = discharging ? this.trimDischarge : this.trimCharge;
		int untrimmedSetPoint = acBatteryTarget + feedForward + (int) Math.round(currentTrim);
		boolean limited = untrimmedSetPoint > maxAllowedBatteryDischargePower
				|| untrimmedSetPoint < maxAllowedChargePower;
		boolean settled = this.elapsedMs > TRIM_WARMUP_MS && this.elapsedMs >= this.freezeUntilMs;
		if (!idle && !limited && settled && plausible) {
			double delta = TRIM_GAIN_PER_S * (cycleTimeMs / 1000.0) * (acBatteryTarget - acBatteryPower);
			if (discharging) {
				this.trimDischarge = Math.max(-TRIM_LIMIT, Math.min(TRIM_LIMIT, this.trimDischarge + delta));
			} else {
				this.trimCharge = Math.max(-TRIM_LIMIT, Math.min(TRIM_LIMIT, this.trimCharge + delta));
			}
		}
		double trim = idle ? 0 : discharging ? this.trimDischarge : this.trimCharge;

		// Battery set-point = AC-side target + feed-forward + trim, clamped to the
		// BMS limits.
		batteryPowerTarget = acBatteryTarget + feedForward + (int) Math.round(trim);
		if (batteryPowerTarget > 0) { // discharge
			batteryPowerTarget = Math.min(batteryPowerTarget, maxAllowedBatteryDischargePower);
		} else { // charge
			batteryPowerTarget = Math.max(batteryPowerTarget, maxAllowedChargePower); // already negative
		}

		batteryPowerTarget = (int) Math.round(batteryPowerTarget / 10.0); // Applied value has to be divided by 10

		this.writeExternalControlFlags();
		// Reg 44106 (1 = 10 W): with 44105 = 2 (battery control) a negative value is
		// battery discharge, positive is charge; with 44105 = 3/4 negative is import,
		// positive is export.
		batteryPowerTarget = batteryPowerTarget * sign;
		this.ess.setRemoteDispatchRealtimeControlSwitch(essSetpoint); 
		this.ess.setRemoteDispatchRealtimeControlPower(batteryPowerTarget);
		
		this.ess.debugLog(""
				+ "\n[ApplyPower] TargetPower: " + activePowerTarget
				+ "\n[ApplyPower] EssPower: " + essActivePower
				+ "\n[ApplyPower] Allowed Charge/Discharge Power: " + maxAllowedChargePower + "/" +  maxAllowedBatteryDischargePower
				+ "\n[ApplyPower] ESS DC DischargePower: " + essDcDischargePower
				+ "\n[ApplyPower] Battery hardware SetPoint: " + batteryPowerTarget
				+ "\n[ApplyPower] FeedForward: " + feedForward + " W, Trim: " + Math.round(trim) + " W (AC-PV "
				+ acBatteryPower + " W, BMS " + batteryPower + " W, trimD " + Math.round(this.trimDischarge)
				+ " trimC " + Math.round(this.trimCharge) + ")"
				+ "\n[ApplyPower]   PV Power " + pvPower);		


	}

	// ========================= Helper =========================

	/**
	 * Writes the remote dispatch settings that select external (EMS) control.
	 *
	 * @throws OpenemsNamedException on write error
	 */
	private void writeExternalControlFlags() throws OpenemsNamedException {
		// The remote dispatch block 44100-44108 is re-written every cycle: the
		// inverter does not persist it and the failsafe expects periodic writes.
		// All nine registers get a value so the bridge sends ONE FC16 frame;
		// registers without a value (44103/44104/44108) would split it into two.
		// 44105/44106 are set by apply() in the same cycle.
		this.ess.setRemoteDispatchSwitch(EnableDisable.ENABLE); // 44100
		this.ess.setRemoteDispatchFailsafeSetting(FAILSAFE_MINUTES); // 44101
		this.ess.setRemoteDispatchSystemLimitSwitch(RemoteDispatchSystemLimitSwitch.DISABLE); // 44102
		this.ess.setRemoteDispatchSystemImportLimit(0); // 44103, unused while the limit switch is disabled
		this.ess.setRemoteDispatchSystemExportLimit(0); // 44104
		// 44108: PV on, DO off, grid charge allowed, no off-grid standby. Same value
		// the inverter reports by default. ToDo: make grid charge configurable.
		this.ess.setRemoteDispatchRealtimeControlFunctionSwitch(false, false, true, false);
	}

}
