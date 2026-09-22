package io.openems.edge.pytes.ess;

import static io.openems.edge.pytes.ess.InverterLossModel.BIAS_MIN_W;
import static io.openems.edge.pytes.ess.InverterLossModel.DEFAULT_BIAS_W;
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
		assertEquals(DEFAULT_BIAS_W, this.sut.bias(true));
		assertEquals(DEFAULT_BIAS_W, this.sut.bias(false));
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
	public void learnsTheBiasInBatteryControlOnly() {
		this.steady(0, 800, 0, STEADY_CYCLES + MIN_SAMPLES); // 800 W discharge commanded
		assertEquals(PLANT_BIAS, this.sut.bias(true));
		assertEquals(DEFAULT_BIAS_W, this.sut.bias(false)); // charging not learned yet
		assertTrue(this.sut.getBiasSamples() >= MIN_SAMPLES);
		// charging is learned separately (the plant shifts by the same bias here)
		this.steady(3000, -1000, 0, STEADY_CYCLES + MIN_SAMPLES);
		assertEquals(PLANT_BIAS, this.sut.bias(false));
		assertEquals(PLANT_BIAS, this.sut.bias(true));

		// AC output control (no command) and idle commands add no bias samples
		var other = new InverterLossModel();
		steadyOn(other, 2000, null, 0, STEADY_CYCLES + MIN_SAMPLES);
		steadyOn(other, 2000, 100, 0, STEADY_CYCLES + MIN_SAMPLES);
		assertEquals(DEFAULT_BIAS_W, other.bias(true));
		assertEquals(0, other.getBiasSamples());
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
		// inverter delivering far more than commanded: bias clamped at -200 W
		for (int i = 0; i < STEADY_CYCLES + MIN_SAMPLES; i++) {
			this.sut.update(0, 900, 1500, 800, true);
		}
		assertEquals(BIAS_MIN_W, this.sut.bias(true));
	}
}
