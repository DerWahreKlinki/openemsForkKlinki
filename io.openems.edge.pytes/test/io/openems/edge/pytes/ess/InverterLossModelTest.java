package io.openems.edge.pytes.ess;

import static io.openems.edge.pytes.ess.InverterLossModel.DEFAULT_RESPONSE_GAIN;
import static io.openems.edge.pytes.ess.InverterLossModel.DEFAULT_RESPONSE_OFFSET_W;
import static io.openems.edge.pytes.ess.InverterLossModel.DEFAULT_LOSS_BASE_W;
import static io.openems.edge.pytes.ess.InverterLossModel.DEFAULT_LOSS_FACTOR;
import static io.openems.edge.pytes.ess.InverterLossModel.MIN_SAMPLES;
import static io.openems.edge.pytes.ess.InverterLossModel.STEADY_CYCLES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InverterLossModelTest {

	/** Synthetic inverter: losses 50 W + 5 %, bias 250 W. */
	private static final int PLANT_BASE = 50;
	private static final double PLANT_FACTOR = 0.05;
	private static final int PLANT_BIAS = 250;

	private final InverterLossModel sut = new InverterLossModel();

	// Runs steady cycles at one operating point. In battery control the battery
	// delivers command - bias, the AC output is PV + battery - losses.
	private void steady(int pv, Integer command, int batteryPower, int cycles) {
		steadyOn(this.sut, pv, command, batteryPower, cycles);
	}

	private static void steadyOn(InverterLossModel model, int pv, Integer command, int batteryPower, int cycles) {
		int battery = command != null ? command - PLANT_BIAS : batteryPower;
		int losses = PLANT_BASE + (int) Math.round(PLANT_FACTOR * (Math.abs(battery) + pv));
		int ac = pv + battery - losses;
		for (int i = 0; i < cycles; i++) {
			model.update(pv, ac, battery, command, true);
		}
	}

	@Test
	public void startsWithTheMeasuredDefaults() {
		assertEquals(DEFAULT_LOSS_BASE_W, this.sut.getLossBaseW());
		assertEquals(DEFAULT_LOSS_FACTOR, this.sut.getLossFactor(), 1e-9);
		assertEquals(DEFAULT_RESPONSE_OFFSET_W, this.sut.getResponseOffsetW());
		assertEquals(DEFAULT_RESPONSE_GAIN, this.sut.getResponseGain(), 1e-9);
		// to hold the battery at 0 the inverter needs a small discharge command
		assertEquals(72, this.sut.commandFor(0));
		assertEquals(30 + 90, this.sut.losses(0, 3000));
	}

	@Test
	public void derivedDcPowerCompensatesTheConversionLosses() {
		// idle battery, 3300 W PV: the inverter outputs PV minus ~130 W losses;
		// without the loss term the derivation would show 130 W of charging
		assertEquals(Integer.valueOf(4), this.sut.deriveDcDischargePower(3171, 3300));
		// charging 2 kW from 5 kW PV: AC = 5000 - 2000 - (30 + 3 % * 7000) = 2760;
		// the losses are estimated from AC - PV, so the result is a few watts off
		assertEquals(Integer.valueOf(-1993), this.sut.deriveDcDischargePower(2760, 5000));
		assertNull(this.sut.deriveDcDischargePower(null, 1000));
	}

	@Test
	public void learnsBaseAndFactorFromTwoOperatingPoints() {
		// one operating point only (idle battery, 800 W PV): the line moves
		// through it, the prior keeps the slope near the default
		this.steady(800, null, 0, STEADY_CYCLES + 200);
		assertEquals(PLANT_BASE + 40, this.sut.losses(0, 800), 5);
		assertEquals(DEFAULT_LOSS_FACTOR, this.sut.getLossFactor(), 0.01);

		// second operating point far away (5 kW PV, battery charging 1 kW):
		// base and factor follow the data
		this.steady(5000, null, -1000, STEADY_CYCLES + 400);
		assertEquals(PLANT_FACTOR, this.sut.getLossFactor(), 0.003);
		assertEquals(PLANT_BASE, this.sut.getLossBaseW(), 8);
		assertEquals(PLANT_BASE + 150, this.sut.losses(0, 3000), 10);
	}

	@Test
	public void singleHighOperatingPointLowersTheFactor() {
		// live 2026-09-22: ~0 W losses at 3.7 kW throughput; with the two-bin
		// logic the default 3 % stayed and the derived DC power was 130 W off
		var lossless = new InverterLossModel();
		for (int i = 0; i < STEADY_CYCLES + 400; i++) {
			lossless.update(2964, 2210, -754, null, true);
		}
		assertEquals(0, lossless.losses(-754, 2964), 15);
		assertEquals(Integer.valueOf(-754), lossless.deriveDcDischargePower(2210, 2964), 20);
	}

	@Test
	public void learnsTheResponseFromTwoCommandLevels() {
		// The synthetic plant answers command - PLANT_BIAS, which is an offset of
		// -PLANT_BIAS at a gain of 1. Two levels are needed to separate both.
		this.steady(0, 800, 0, STEADY_CYCLES + 300);
		this.steady(0, 1600, 0, STEADY_CYCLES + 300);
		// the prior on the start values keeps some pull, so the parameters land
		// near but not exactly on the plant - what matters is the inverse map
		assertEquals(-PLANT_BIAS, this.sut.getResponseOffsetW(), 70);
		assertEquals(1.0, this.sut.getResponseGain(), 0.1);
		// to get 550 W out of this plant, ask for 550 + bias
		assertEquals(550 + PLANT_BIAS, this.sut.commandFor(550), 80);
		assertEquals(PLANT_BIAS, this.sut.responseCorrection(550), 80);
		assertTrue(this.sut.getResponseSamples() >= MIN_SAMPLES);

		// AC output control gives no command, so nothing is learned there
		var other = new InverterLossModel();
		steadyOn(other, 2000, null, 0, STEADY_CYCLES + MIN_SAMPLES);
		assertEquals(DEFAULT_RESPONSE_OFFSET_W, other.getResponseOffsetW());
		assertEquals(0, other.getResponseSamples());
	}

	@Test
	public void ignoresTransientsAndMaskedMeasurements() {
		// AC output swings by 500 W every cycle: never steady
		for (int i = 0; i < 100; i++) {
			this.sut.update(3000, 2500 + (i % 2) * 500, 0, null, true);
		}
		assertEquals(0, this.sut.getSamples());

		// steady but masked (warm-up): no samples either
		for (int i = 0; i < 100; i++) {
			this.sut.update(3000, 2800, 0, null, false);
		}
		assertEquals(0, this.sut.getSamples());

		// a set-point step resets the steady window
		this.steady(0, 500, 0, STEADY_CYCLES - 1);
		this.steady(0, 1000, 0, STEADY_CYCLES - 1);
		assertEquals(0, this.sut.getSamples());
	}

	@Test
	public void clampsImplausibleValues() {
		// "losses" of 2 kW at 3 kW throughput: base capped at 200 W, factor at 10 %
		for (int i = 0; i < STEADY_CYCLES + MIN_SAMPLES; i++) {
			this.sut.update(1000, -1000, 0, null, true);
		}
		for (int i = 0; i < STEADY_CYCLES + MIN_SAMPLES; i++) {
			this.sut.update(5000, 2000, 0, null, true);
		}
		assertTrue(this.sut.getLossBaseW() <= InverterLossModel.LOSS_BASE_MAX_W);
		assertTrue(this.sut.getLossFactor() <= InverterLossModel.LOSS_FACTOR_MAX);
		// inverter delivering far more than commanded: the gain is capped
		var wild = new InverterLossModel();
		for (int i = 0; i < STEADY_CYCLES + 200; i++) {
			wild.update(0, 900, 8000, 800, true);
		}
		assertTrue(wild.getResponseGain() <= InverterLossModel.RESPONSE_GAIN_MAX);
		assertTrue(wild.getResponseOffsetW() >= InverterLossModel.RESPONSE_OFFSET_MIN_W);
	}
}
