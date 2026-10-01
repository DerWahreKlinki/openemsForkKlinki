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
 * throughput}. In battery control the inverter does not follow the commanded
 * battery power exactly either; its response is a line as well,
 * {@code battery = offset + gain * command}. Both are needed for the
 * feed-forward of the battery set-point, for the AC-side allowed discharge
 * power and for the derived {@code DcDischargePower}. The start values were
 * measured on one Pytes JS3 (15 kVA); other models of the family behave
 * differently, so the parameters are learned at runtime from steady-state
 * measurements:
 *
 * <pre>
 * losses  = PV + batteryPower(BMS) - ActivePower            (any mode)
 * battery = offset + gain * commandedBatteryPower           (battery control)
 * </pre>
 *
 * <p>
 * The response was a constant {@code bias = command - battery} per direction
 * before, which is the average of something that is not constant: measured
 * over 1316 steady cycles on 2026-09-30/10-01, {@code command - battery} ran
 * from +273 W at a command of 331 W to -199 W at 1612 W. An average therefore
 * sits between the ends and is wrong at both: the feed-forward asked for
 * 200 W of discharge to reach 149 W of charging, and the trim needed minutes
 * to chew through the error. The regression over the same data gives
 * {@code battery = -78 W + 1.09 * command}, with a knee below ~500 W where
 * the inverter only follows with a gain of about 0.4.
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
	/** Start values of the response, measured 2026-09-30/10-01 over 1316 cycles. */
	static final int DEFAULT_RESPONSE_OFFSET_W = -78;
	static final double DEFAULT_RESPONSE_GAIN = 1.09;

	static final int STEADY_CYCLES = 15;
	static final int STEADY_BAND_W = 150;
	/** Samples a fit needs before it is used instead of the start values. */
	static final int MIN_SAMPLES = 20;
	/** Forgetting factor of the regressions per sample (~500 samples memory). */
	static final double FORGET = 0.998;
	/** Weight of the prior on the start values, in equivalent samples. */
	static final double PRIOR_WEIGHT = 20;
	/** Throughput at which the prior on the factor is as strong as on the base. */
	static final double PRIOR_THROUGHPUT_W = 1500;
	/** Command at which the prior on the gain is as strong as on the offset. */
	static final double PRIOR_COMMAND_W = 800;

	static final int LOSS_BASE_MIN_W = -100;
	static final int LOSS_BASE_MAX_W = 200;
	static final double LOSS_FACTOR_MIN = 0.0;
	static final double LOSS_FACTOR_MAX = 0.10;
	static final int RESPONSE_OFFSET_MIN_W = -400;
	static final int RESPONSE_OFFSET_MAX_W = 200;
	static final double RESPONSE_GAIN_MIN = 0.3;
	static final double RESPONSE_GAIN_MAX = 2.0;

	// weighted sums of the loss regression: n, sum T, sum L, sum T*T, sum T*L
	private double n = 0;
	private double sT = 0;
	private double sL = 0;
	private double sTT = 0;
	private double sTL = 0;
	private int samples = 0;
	// weighted sums of the response regression: n, sum C, sum B, sum C*C, sum C*B
	private double rn = 0;
	private double sC = 0;
	private double sB = 0;
	private double sCC = 0;
	private double sCB = 0;
	private int responseSamples = 0;

	private final Deque<Integer> acValues = new ArrayDeque<>();
	private final Deque<Integer> pvValues = new ArrayDeque<>();
	private final Deque<Integer> commandValues = new ArrayDeque<>();

	private int lossBaseW = DEFAULT_LOSS_BASE_W;
	private double lossFactor = DEFAULT_LOSS_FACTOR;
	private int responseOffsetW = DEFAULT_RESPONSE_OFFSET_W;
	private double responseGain = DEFAULT_RESPONSE_GAIN;

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

		// Response of the inverter to the command. Samples at a command of 0 are
		// kept: they pin the offset, which is where most of the error sat.
		if (commandW != null) {
			this.rn = this.rn * FORGET + 1;
			this.sC = this.sC * FORGET + commandW;
			this.sB = this.sB * FORGET + batteryPower;
			this.sCC = this.sCC * FORGET + (double) commandW * commandW;
			this.sCB = this.sCB * FORGET + (double) commandW * batteryPower;
			this.responseSamples++;
			this.fitResponse();
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
	 * Minimises sum(B - o - g*C)^2 + po*(o - o0)^2 + pg*(g - g0)^2 over the
	 * (forgotten) samples with the start values as prior, so that the fit stays
	 * near the measured defaults while the commands do not spread enough.
	 */
	private void fitResponse() {
		double po = PRIOR_WEIGHT;
		double pg = PRIOR_WEIGHT * PRIOR_COMMAND_W * PRIOR_COMMAND_W;
		double a11 = this.rn + po;
		double a12 = this.sC;
		double a22 = this.sCC + pg;
		double r1 = this.sB + po * DEFAULT_RESPONSE_OFFSET_W;
		double r2 = this.sCB + pg * DEFAULT_RESPONSE_GAIN;
		double det = a11 * a22 - a12 * a12;
		if (det <= 0 || this.responseSamples < MIN_SAMPLES) {
			return;
		}
		double offset = (r1 * a22 - a12 * r2) / det;
		double gain = (a11 * r2 - a12 * r1) / det;
		this.responseGain = clamp(gain, RESPONSE_GAIN_MIN, RESPONSE_GAIN_MAX);
		this.responseOffsetW = clamp((int) Math.round(offset), RESPONSE_OFFSET_MIN_W, RESPONSE_OFFSET_MAX_W);
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
	 * The command that makes the inverter deliver the wanted battery power
	 * (battery control), i.e. the inverse of {@code battery = offset + gain *
	 * command}.
	 *
	 * @param batteryPower the wanted battery power in W (positive = discharge)
	 * @return the command in W (positive = discharge)
	 */
	int commandFor(int batteryPower) {
		return (int) Math.round((batteryPower - this.responseOffsetW) / this.responseGain);
	}

	/**
	 * What {@link #commandFor} adds on top of the wanted battery power, i.e. the
	 * feed-forward without the losses.
	 *
	 * @param batteryPower the wanted battery power in W (positive = discharge)
	 * @return the correction in W
	 */
	int responseCorrection(int batteryPower) {
		return this.commandFor(batteryPower) - batteryPower;
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

	int getResponseOffsetW() {
		return this.responseOffsetW;
	}

	double getResponseGain() {
		return this.responseGain;
	}

	int getResponseSamples() {
		return this.responseSamples;
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
