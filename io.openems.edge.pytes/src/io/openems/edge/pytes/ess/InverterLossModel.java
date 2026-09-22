package io.openems.edge.pytes.ess;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Self-calibrating model of the inverter's conversion losses and of its
 * battery set-point bias.
 *
 * <p>
 * The losses between battery and AC side grow with the total throughput
 * (battery + PV) and are modelled as a line {@code losses = base + factor *
 * throughput}. In battery control the inverter additionally shifts the
 * battery power towards charging by a {@code bias} against the command; the
 * shift differs between charging and discharging (measured 2026-09-22: ~90 W
 * discharging, ~220 W charging), so it is kept per direction. Both are needed
 * for the feed-forward of the battery set-point, for the AC-side allowed
 * discharge power and for the derived {@code DcDischargePower}. The start
 * values were measured on one Pytes JS3 (15 kVA) on 2026-09-16; other models
 * of the family have other loss curves, so the parameters are learned at
 * runtime from steady-state measurements:
 *
 * <pre>
 * losses = PV + batteryPower(BMS) - ActivePower     (any mode)
 * bias   = commandedBatteryPower - batteryPower(BMS) (battery control, not idle)
 * </pre>
 *
 * <p>
 * A cycle counts as steady when the set-point, AC and PV power stayed within
 * {@link #STEADY_BAND_W} for {@link #STEADY_CYCLES}
 * cycles, so that the BMS lag (~6-11 s), cloud edges and set-point steps are
 * excluded; the caller additionally masks the warm-up after start and
 * implausible BMS values. The loss line is a regularised least-squares fit
 * over the samples (exponentially forgotten, see {@link #FORGET}) with a
 * prior on the start values: while the operating points do not spread enough
 * to identify base and factor, the prior keeps the line near the defaults,
 * with enough spread both parameters follow the data. A constant offset
 * between the AC, PV and BMS measurements ends up in the base, which may
 * therefore be slightly negative. All parameters are clamped to sane ranges.
 * Pure computation, nothing is persisted.
 */
class InverterLossModel {

	/** Start values, measured 2026-09-16 (see ApplyPowerHandler). */
	static final int DEFAULT_LOSS_BASE_W = 30;
	static final double DEFAULT_LOSS_FACTOR = 0.03;
	static final int DEFAULT_BIAS_W = 190;

	static final int STEADY_CYCLES = 15;
	static final int STEADY_BAND_W = 150;
	/** Samples the bias needs before it is used. */
	static final int MIN_SAMPLES = 20;
	/** Weight of a new sample in the bias means (~50 steady cycles). */
	static final double ALPHA = 0.02;
	/** Forgetting factor of the loss regression per sample (~500 samples memory). */
	static final double FORGET = 0.998;
	/** Weight of the prior on the start values, in equivalent samples. */
	static final double PRIOR_WEIGHT = 20;
	/** Throughput at which the prior on the factor is as strong as on the base. */
	static final double PRIOR_THROUGHPUT_W = 1500;
	/** Commands below this are idle, no bias sample. */
	static final int MIN_BIAS_COMMAND_W = 300;

	static final int LOSS_BASE_MIN_W = -100;
	static final int LOSS_BASE_MAX_W = 200;
	static final double LOSS_FACTOR_MIN = 0.0;
	static final double LOSS_FACTOR_MAX = 0.10;
	static final int BIAS_MIN_W = -200;
	static final int BIAS_MAX_W = 400;

	// weighted sums of the loss regression: n, sum T, sum L, sum T*T, sum T*L
	private double n = 0;
	private double sT = 0;
	private double sL = 0;
	private double sTT = 0;
	private double sTL = 0;
	private int samples = 0;
	/** Running bias means per direction: [0] discharging, [1] charging. */
	private final double[] biasMean = new double[2];
	private final int[] biasSamples = new int[2];

	private final Deque<Integer> acValues = new ArrayDeque<>();
	private final Deque<Integer> pvValues = new ArrayDeque<>();
	private final Deque<Integer> commandValues = new ArrayDeque<>();

	private int lossBaseW = DEFAULT_LOSS_BASE_W;
	private double lossFactor = DEFAULT_LOSS_FACTOR;
	private final int[] biasW = { DEFAULT_BIAS_W, DEFAULT_BIAS_W };

	/**
	 * Feeds one cycle of measurements.
	 *
	 * @param pvPower        PV production in W
	 * @param acPower        inverter AC output in W (positive = export)
	 * @param batteryPower   battery power from the BMS in W (positive = discharge)
	 * @param commandW       the battery power commanded to the inverter in W
	 *                       (positive = discharge), or null when not in battery
	 *                       control
	 * @param measurementsOk false while the measurements must not be used (warm-up
	 *                       after start, implausible BMS values)
	 */
	void update(int pvPower, int acPower, int batteryPower, Integer commandW, boolean measurementsOk) {
		push(this.acValues, acPower);
		push(this.pvValues, pvPower);
		push(this.commandValues, commandW == null ? Integer.MIN_VALUE : commandW);
		if (!measurementsOk || !this.isSteady()) {
			return;
		}
		int throughput = Math.abs(batteryPower) + Math.max(0, pvPower);
		int losses = pvPower + batteryPower - acPower;
		this.n = this.n * FORGET + 1;
		this.sT = this.sT * FORGET + throughput;
		this.sL = this.sL * FORGET + losses;
		this.sTT = this.sTT * FORGET + (double) throughput * throughput;
		this.sTL = this.sTL * FORGET + (double) throughput * losses;
		this.samples++;
		this.fitLosses();

		if (commandW != null && Math.abs(commandW) >= MIN_BIAS_COMMAND_W) {
			int d = commandW > 0 ? 0 : 1;
			int bias = commandW - batteryPower;
			if (this.biasSamples[d] == 0) {
				this.biasMean[d] = bias;
			} else {
				this.biasMean[d] += ALPHA * (bias - this.biasMean[d]);
			}
			this.biasSamples[d]++;
			if (this.biasSamples[d] >= MIN_SAMPLES) {
				this.biasW[d] = clamp((int) Math.round(this.biasMean[d]), BIAS_MIN_W, BIAS_MAX_W);
			}
		}
	}

	/**
	 * Minimises sum(L - a - b*T)^2 + pa*(a - a0)^2 + pb*(b - b0)^2 over the
	 * (forgotten) samples with the start values as prior.
	 */
	private void fitLosses() {
		double pa = PRIOR_WEIGHT;
		double pb = PRIOR_WEIGHT * PRIOR_THROUGHPUT_W * PRIOR_THROUGHPUT_W;
		double a11 = this.n + pa;
		double a12 = this.sT;
		double a22 = this.sTT + pb;
		double r1 = this.sL + pa * DEFAULT_LOSS_BASE_W;
		double r2 = this.sTL + pb * DEFAULT_LOSS_FACTOR;
		double det = a11 * a22 - a12 * a12;
		if (det <= 0) {
			return;
		}
		double base = (r1 * a22 - a12 * r2) / det;
		double factor = (a11 * r2 - a12 * r1) / det;
		this.lossFactor = clamp(factor, LOSS_FACTOR_MIN, LOSS_FACTOR_MAX);
		this.lossBaseW = clamp((int) Math.round(base), LOSS_BASE_MIN_W, LOSS_BASE_MAX_W);
	}

	private boolean isSteady() {
		if (this.acValues.size() < STEADY_CYCLES) {
			return false;
		}
		// the command jitters by a few tens of watts with the trim and the
		// controllers' targets; only a real step (or a mode change) counts
		return spread(this.acValues) <= STEADY_BAND_W && spread(this.pvValues) <= STEADY_BAND_W
				&& spread(this.commandValues) <= STEADY_BAND_W;
	}

	/**
	 * Expected conversion losses between battery and AC side.
	 *
	 * @param batteryPower battery power in W (sign irrelevant)
	 * @param pvPower      PV power in W
	 * @return losses in W
	 */
	int losses(int batteryPower, int pvPower) {
		return this.lossBaseW + (int) Math.round(this.lossFactor * (Math.abs(batteryPower) + Math.max(0, pvPower)));
	}

	/**
	 * The shift towards charging the inverter applies to the commanded battery
	 * power (battery control), for the given direction.
	 *
	 * @param discharging true for a discharge command, false for charging
	 * @return the bias in W
	 */
	int bias(boolean discharging) {
		return this.biasW[discharging ? 0 : 1];
	}

	/**
	 * The battery's AC-side contribution derived from the inverter output: AC =
	 * PV + battery - losses, so battery = AC - PV + losses. Without the loss term
	 * the derived value shows the conversion losses as charging (~130 W at 3 kW PV
	 * with an idle battery, seen live 2026-09-21) although the BMS reads ~30 W.
	 *
	 * @param acPower the inverter AC output in W, null if unknown
	 * @param pvPower the PV production in W
	 * @return the battery power in W (positive = discharge), or null
	 */
	Integer deriveDcDischargePower(Integer acPower, int pvPower) {
		if (acPower == null) {
			return null;
		}
		int raw = acPower - pvPower;
		return raw + this.losses(raw, pvPower);
	}

	int getLossBaseW() {
		return this.lossBaseW;
	}

	double getLossFactor() {
		return this.lossFactor;
	}

	int getSamples() {
		return this.samples;
	}

	int getBiasSamples() {
		return this.biasSamples[0] + this.biasSamples[1];
	}

	private static void push(Deque<Integer> values, int value) {
		values.addLast(value);
		while (values.size() > STEADY_CYCLES) {
			values.removeFirst();
		}
	}

	private static long spread(Deque<Integer> values) {
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (int v : values) {
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		return max - min;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
