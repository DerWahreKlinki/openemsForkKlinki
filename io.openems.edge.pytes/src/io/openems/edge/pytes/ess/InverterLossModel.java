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
 * throughput}. In battery control the inverter additionally delivers
 * {@code bias} less battery discharge power than commanded. Both are needed
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
 * implausible BMS values. Loss samples are averaged per throughput bin (below
 * and above {@link #BIN_SPLIT_W}); with two valid bins the line runs through
 * both bin means, with one valid bin only the base is adapted. All parameters
 * are clamped to sane ranges. Pure computation, nothing is persisted.
 */
class InverterLossModel {

	/** Start values, measured 2026-09-16 (see ApplyPowerHandler). */
	static final int DEFAULT_LOSS_BASE_W = 30;
	static final double DEFAULT_LOSS_FACTOR = 0.03;
	static final int DEFAULT_BIAS_W = 190;

	static final int STEADY_CYCLES = 15;
	static final int STEADY_BAND_W = 150;
	/** Throughput that separates the two loss bins. */
	static final int BIN_SPLIT_W = 1500;
	/** Samples a bin needs before it is used. */
	static final int MIN_SAMPLES = 20;
	/** Weight of a new sample in the running means (~50 steady cycles). */
	static final double ALPHA = 0.02;
	/** Commands below this are idle, no bias sample. */
	static final int MIN_BIAS_COMMAND_W = 300;

	static final int LOSS_BASE_MIN_W = 0;
	static final int LOSS_BASE_MAX_W = 200;
	static final double LOSS_FACTOR_MIN = 0.0;
	static final double LOSS_FACTOR_MAX = 0.10;
	static final int BIAS_MIN_W = 0;
	static final int BIAS_MAX_W = 400;

	/** Running mean of throughput and losses of one bin. */
	private static final class Bin {
		private double throughput = 0;
		private double losses = 0;
		private int samples = 0;

		private void add(int t, int l) {
			if (this.samples == 0) {
				this.throughput = t;
				this.losses = l;
			} else {
				this.throughput += ALPHA * (t - this.throughput);
				this.losses += ALPHA * (l - this.losses);
			}
			this.samples++;
		}

		private boolean valid() {
			return this.samples >= MIN_SAMPLES;
		}
	}

	private final Bin low = new Bin();
	private final Bin high = new Bin();
	private double biasMean = 0;
	private int biasSamples = 0;

	private final Deque<Integer> acValues = new ArrayDeque<>();
	private final Deque<Integer> pvValues = new ArrayDeque<>();
	private final Deque<Integer> commandValues = new ArrayDeque<>();

	private int lossBaseW = DEFAULT_LOSS_BASE_W;
	private double lossFactor = DEFAULT_LOSS_FACTOR;
	private int biasW = DEFAULT_BIAS_W;

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
		(throughput < BIN_SPLIT_W ? this.low : this.high).add(throughput, losses);
		this.fitLosses();

		if (commandW != null && Math.abs(commandW) >= MIN_BIAS_COMMAND_W) {
			int bias = commandW - batteryPower;
			if (this.biasSamples == 0) {
				this.biasMean = bias;
			} else {
				this.biasMean += ALPHA * (bias - this.biasMean);
			}
			this.biasSamples++;
			if (this.biasSamples >= MIN_SAMPLES) {
				this.biasW = clamp((int) Math.round(this.biasMean), BIAS_MIN_W, BIAS_MAX_W);
			}
		}
	}

	private void fitLosses() {
		if (this.low.valid() && this.high.valid() && this.high.throughput - this.low.throughput > 500) {
			double factor = (this.high.losses - this.low.losses) / (this.high.throughput - this.low.throughput);
			this.lossFactor = clamp(factor, LOSS_FACTOR_MIN, LOSS_FACTOR_MAX);
			this.lossBaseW = clamp((int) Math.round(this.low.losses - this.lossFactor * this.low.throughput),
					LOSS_BASE_MIN_W, LOSS_BASE_MAX_W);
		} else if (this.low.valid() || this.high.valid()) {
			// one operating point only: keep the slope, move the line through it
			Bin bin = this.low.valid() ? this.low : this.high;
			this.lossBaseW = clamp((int) Math.round(bin.losses - this.lossFactor * bin.throughput), LOSS_BASE_MIN_W,
					LOSS_BASE_MAX_W);
		}
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
	 * The battery power the inverter delivers less than commanded (battery
	 * control, discharge direction).
	 *
	 * @return the bias in W
	 */
	int bias() {
		return this.biasW;
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
		return this.low.samples + this.high.samples;
	}

	int getBiasSamples() {
		return this.biasSamples;
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
