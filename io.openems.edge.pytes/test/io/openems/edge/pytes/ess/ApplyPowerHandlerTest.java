package io.openems.edge.pytes.ess;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.pytes.enums.RemoteDispatchRealtimeControlSwitch;
import io.openems.edge.pytes.enums.RemoteDispatchSystemLimitSwitch;
import io.openems.edge.pytes.enums.WorkState;

/**
 * Cycle-by-cycle tests of {@link ApplyPowerHandler} against fakes. The expected
 * register values follow the constants in the handler: response offset -78 W at
 * a gain of 1.09, losses
 * 30 W + 3 % of (|battery| + PV), register 44106 in 10 W steps, negative =
 * discharge in battery control, positive = export in AC output control.
 */
public class ApplyPowerHandlerTest {

	private static final int MAX_APPARENT_POWER = 10_000;

	private DummyApplyPowerEss ess;
	private DummyPytesDcCharger charger;
	private InverterLossModel model;
	private ApplyPowerHandler handler;

	@Before
	public void setup() {
		this.ess = new DummyApplyPowerEss("ess0") //
				.withMaxApparentPower(MAX_APPARENT_POWER) //
				.withAllowedChargePower(-2100) //
				.withAllowedDischargePower(2300) //
				.withBatteryLimits(-2100, 2100) //
				.withActivePower(0) //
				.withDcDischargePower(0) //
				.withBatteryDcDischargePower(0) //
				.withBackupLoadPower(0);
		this.charger = new DummyPytesDcCharger("dccharger0") //
				.withActualPower(0);
		this.model = new InverterLossModel();
		this.handler = new ApplyPowerHandler(this.ess, this.charger, this.model);
	}

	private Optional<?> written(PytesJs3.ChannelId channelId) {
		WriteChannel<?> channel = this.ess.channel(channelId);
		return channel.getNextWriteValueAndReset();
	}

	// Runs one cycle in battery control and returns the value written to reg 44106
	private int applyBatteryControl(int acTarget) throws Exception {
		this.handler.apply(acTarget, 0, MAX_APPARENT_POWER, RemoteDispatchRealtimeControlSwitch.BATTERY_CONTROL);
		return (Integer) this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_POWER).orElseThrow();
	}

	// Runs one cycle in AC output control and returns the value written to reg 44106
	private int applyAcOutputControl(int acTarget) throws Exception {
		this.handler.apply(acTarget, 0, MAX_APPARENT_POWER, RemoteDispatchRealtimeControlSwitch.AC_OUTPUT_CONTROL);
		return (Integer) this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_POWER).orElseThrow();
	}

	@Test
	public void lossModelDoesNotLearnWhileTheInverterLimitsItself() throws Exception {
		// Inverter in its own export cap: commanded battery power is not what the
		// battery does, those cycles must not reach the model (live 2026-09-22:
		// the discharge bias ran into its clamp)
		var model = new InverterLossModel();
		var handler = new ApplyPowerHandler(this.ess, this.charger, model);
		this.ess.withInverterLimited(true).withActivePower(4370).withBatteryDcDischargePower(-1513);
		this.charger.withActualPower(6115);
		for (int i = 0; i < 200; i++) {
			handler.apply(5880, 0, MAX_APPARENT_POWER, RemoteDispatchRealtimeControlSwitch.BATTERY_CONTROL);
			this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_POWER);
		}
		assertEquals(0, model.getSamples());
		assertEquals(InverterLossModel.DEFAULT_RESPONSE_OFFSET_W, model.getResponseOffsetW());

		// same cycles without the limitation: the model learns
		this.ess.withInverterLimited(false).withActivePower(1170).withBatteryDcDischargePower(1100);
		this.charger.withActualPower(100);
		for (int i = 0; i < 200; i++) {
			handler.apply(1170, 0, MAX_APPARENT_POWER, RemoteDispatchRealtimeControlSwitch.BATTERY_CONTROL);
			this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_POWER);
		}
		assertTrue(model.getSamples() > 0);
	}

	@Test
	public void lossModelKeepsLearningWhileOurOwnClampBinds() throws Exception {
		// Live 2026-09-25: with the charge clamp binding all day (PV surplus above
		// the battery limit) the model stopped learning completely and the bias
		// stayed at its start value - exactly where it decides how far the charge
		// current overshoots. The inverter follows the clamped command, so those
		// cycles are valid samples.
		var model = new InverterLossModel();
		var handler = new ApplyPowerHandler(this.ess, this.charger, model);
		// PV 5 kW, 2 kW wanted at the AC side -> 3 kW charge wanted, clamped to
		// the charge limit. The plant follows the clamped command and charges
		// 250 W more than commanded, so that is the offset to be learned.
		this.charger.withActualPower(5000);
		this.ess.withActivePower(2630).withBatteryDcDischargePower(-2160);
		for (int i = 0; i < 300; i++) {
			handler.apply(2000, 0, MAX_APPARENT_POWER, RemoteDispatchRealtimeControlSwitch.BATTERY_CONTROL);
			int register = (Integer) this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_POWER)
					.orElseThrow();
			int setPoint = -register * 10;
			this.ess.withBatteryDcDischargePower(setPoint - 250) //
					.withActivePower(5000 + setPoint - 460);
		}
		assertTrue(model.getSamples() > 0);
		assertTrue("response should have been learned", model.getResponseSamples() > 0);
	}

	@Test
	public void batteryControlAppliesFeedForward() throws Exception {
		// 500 W AC with 200 W PV -> 300 W from the battery. The response needs
		// (300 + 78) / 1.09 = 347 W of command, i.e. 47 W on top, plus losses
		// 30 + 3 % * (300 + 200) = 45 -> 392 W -> register -39
		this.charger.withActualPower(200);
		this.ess.withActivePower(500);
		this.ess.withBatteryDcDischargePower(300);

		assertEquals(-39, this.applyBatteryControl(500));
		assertEquals(RemoteDispatchRealtimeControlSwitch.BATTERY_CONTROL.getValue(),
				this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_SWITCH).orElseThrow());
		assertEquals(5, this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_FAILSAFE_SETTING).orElseThrow());
		assertEquals(RemoteDispatchSystemLimitSwitch.DISABLE.getValue(),
				this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH).orElseThrow());
	}

	@Test
	public void batteryControlClampsToBmsLimits() throws Exception {
		this.charger.withActualPower(200);
		// far above the discharge limit -> 2100 W -> -210. The inverter delivers
		// less than commanded, so the battery stays below the limit.
		assertEquals(-210, this.applyBatteryControl(5000));
		// far below the charge limit: the command is the one that produces the
		// limit, i.e. commandFor(-2100) = (-2100 + 78) / 1.09 = -1855 -> +185,
		// and the battery then really lands on -2100
		int register = this.applyBatteryControl(-5000);
		assertEquals(185, register);
		double battery = InverterLossModel.DEFAULT_RESPONSE_OFFSET_W
				+ InverterLossModel.DEFAULT_RESPONSE_GAIN * (-register * 10);
		assertEquals(-2100, battery, 15);
	}

	@Test
	public void trimUnwindsWhileTheSetPointSitsOnALimit() throws Exception {
		// Live 2026-09-28: 1220 W wanted, the trim had grown to 253 W, the
		// resulting set-point hit the 1645 W discharge limit - and the anti-windup
		// froze the trim there, so the inverter kept delivering 210 W too much
		// into the grid for hours. A correction that moves the set-point back into
		// range has to be integrated.
		this.ess.withBatteryLimits(-2100, 1100).withActivePower(1430).withBatteryDcDischargePower(1430);
		int first = 0;
		int last = 0;
		for (int i = 0; i < 120; i++) {
			int register = this.applyBatteryControl(1220);
			if (i == 40) {
				first = register;
			}
			last = register;
		}
		// clamped at the start (1100 W -> -110), then walking back out of the limit
		assertEquals(-110, first);
		assertTrue("set-point should leave the limit, was " + last, last > -110);
	}

	@Test
	public void feedForwardFadesOutTowardsZero() throws Exception {
		// With PV the small target is chased. 520 W AC against 500 W PV is a battery
		// target of 20 W, i.e. 40 % of 50 W: 40 % of (response correction 70 +
		// losses 30 + 3 % of 520) = 46 W on top -> 66 W -> register -7
		this.charger.withActualPower(500);
		assertEquals(-7, this.applyBatteryControl(520));
		// at a battery target of 0 nothing is added: there the inverter has no bias
		assertEquals(0, this.applyBatteryControl(500));
		// above MIN_TARGET_W the full feed-forward applies again: 100 + correction
		// 63 + losses (30 + 3 % of 600) = 211 W -> register -21
		assertEquals(-21, this.applyBatteryControl(600));
	}

	@Test
	public void deadBandKeepsTheInverterStillAtNight() throws Exception {
		// No PV and a battery target of a few tens of watts: commanding it would
		// drive the inverter against its bias and the battery would cycle around
		// zero all night (measured 29./30.09.2026: 519 Wh out, 484 Wh in at 22 W
		// of house load). The set-point is 0 instead.
		assertEquals(0, this.applyBatteryControl(22));
		assertEquals(0, this.applyBatteryControl(-30));
		// a real request is still followed, however small the house load is
		assertEquals(-67, this.applyBatteryControl(600));
		// and with PV the band is off again, see feedForwardFadesOutTowardsZero
		this.charger.withActualPower(500);
		assertTrue(this.applyBatteryControl(520) != 0);
	}

	@Test
	public void acOutputControlSubtractsBackupLoad() throws Exception {
		// 1.2 kW EV on the backup port: the grid-side port only has to deliver
		// the difference
		this.charger.withActualPower(200);
		this.ess.withBackupLoadPower(1190);
		this.ess.withActivePower(1200);

		assertEquals(1, this.applyAcOutputControl(1200));
		assertEquals(RemoteDispatchRealtimeControlSwitch.AC_OUTPUT_CONTROL.getValue(),
				this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_SWITCH).orElseThrow());
	}

	@Test
	public void acOutputControlExportsPvAboveChargeLimit() throws Exception {
		// PV 5 kW, battery may take 2.1 kW: the inverter must export at least
		// 2.9 kW regardless of the 800 W the controller asks for. The surplus
		// search is off (as while reg 43052 limits) - this tests the clamp only.
		this.charger.withActualPower(5000);
		this.ess.withAllowedChargePower(0).withPvLimitActive(true);
		assertEquals(290, this.applyAcOutputControl(800));
	}

	@Test
	public void acOutputControlSurplusProbeRaisesTheLowerBound() throws Exception {
		// Battery full (charge limit 0), PV curtailed to the 500 W the controller
		// asks for: the probe lifts the lower bound one step above the measured PV
		this.ess.withBatteryLimits(0, 2100).withAllowedChargePower(0).withActivePower(500).withGridPower(-100)
				.withGridFeedInLimit(5000);
		this.charger.withActualPower(500);
		for (int i = 0; i < PvSurplusProbe.AVERAGE_CYCLES - 1; i++) {
			assertEquals(50, this.applyAcOutputControl(500)); // steady window not full yet
		}
		assertEquals(80, this.applyAcOutputControl(500)); // 500 + STEP_W
		assertEquals(800, (int) this.ess.channel(PytesJs3.ChannelId.SURPLUS_FLOOR).getNextValue().get());

		// no search in battery control
		this.handler.apply(500, 0, MAX_APPARENT_POWER, RemoteDispatchRealtimeControlSwitch.BATTERY_CONTROL);
		assertEquals(0, (int) this.ess.channel(PytesJs3.ChannelId.SURPLUS_FLOOR).getNextValue().get());
	}

	@Test
	public void acOutputControlClampsToAllowedRange() throws Exception {
		this.charger.withActualPower(200);
		this.ess.withPvLimitActive(true); // clamp only, no surplus search
		assertEquals(230, this.applyAcOutputControl(5000)); // AllowedDischargePower 2300
		assertEquals(-190, this.applyAcOutputControl(-5000)); // PV 200 + charge limit -2100
	}

	@Test
	public void trimStartsAfterWarmUpAndIntegratesSlowly() throws Exception {
		// battery control, no PV: target 500 W, the inverter only delivers 300 W
		this.ess.withActivePower(300);
		this.ess.withBatteryDcDischargePower(300);

		// 500 + response correction 30 + losses (30 + 3 % * 500) = 575 W
		int expectedWithoutTrim = -58;
		for (int cycle = 1; cycle <= 30; cycle++) {
			assertEquals("cycle " + cycle, expectedWithoutTrim, this.applyBatteryControl(500));
		}
		// from cycle 31 on: +0.04 * 200 W = 8 W per cycle -> after 15 cycles +120 W
		for (int cycle = 31; cycle <= 45; cycle++) {
			this.applyBatteryControl(500);
		}
		// 575 + 128 = 703 W; the model meanwhile learns from the (synthetic) steady
		// state and moves the feed-forward by a few watts
		assertEquals(-69, this.applyBatteryControl(500), 2);
	}

	@Test
	public void trimFreezesAfterSetPointStep() throws Exception {
		this.ess.withActivePower(300);
		this.ess.withBatteryDcDischargePower(300);
		for (int cycle = 1; cycle <= 40; cycle++) {
			this.applyBatteryControl(500);
		}
		int before = this.applyBatteryControl(500);
		// a step of 1000 W freezes the trim for 12 s, so the register only changes
		// by what the step itself asks for: target, response correction and losses,
		// all of which the model knows
		this.ess.withActivePower(300); // inverter has not followed yet
		int afterStep = this.applyBatteryControl(1500);
		int stepW = 1500 + this.model.responseCorrection(1500) + this.model.losses(1500, 0)
				- (500 + this.model.responseCorrection(500) + this.model.losses(500, 0));
		assertEquals(before - Math.round(stepW / 10.0f), afterStep, 1);
		for (int cycle = 1; cycle <= 10; cycle++) {
			assertEquals("frozen cycle " + cycle, afterStep, this.applyBatteryControl(1500));
		}
	}

	@Test
	public void writesGridFeedInLimit() throws Exception {
		this.ess.withGridFeedInLimit(1000);
		this.applyBatteryControl(500);
		assertEquals(RemoteDispatchSystemLimitSwitch.EXPORT_LIMIT_ENABLE.getValue(),
				this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH).orElseThrow());
		assertEquals(1000, this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_SYSTEM_EXPORT_LIMIT).orElseThrow());

		this.ess.withGridFeedInLimit(null);
		this.applyBatteryControl(500);
		assertEquals(RemoteDispatchSystemLimitSwitch.DISABLE.getValue(),
				this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_SYSTEM_LIMIT_SWITCH).orElseThrow());
		assertEquals(0, this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_SYSTEM_EXPORT_LIMIT).orElseThrow());
	}

	@Test
	public void doesNotWriteInErrorState() throws Exception {
		this.ess.withWorkState(WorkState.ERROR);
		this.handler.apply(500, 0, MAX_APPARENT_POWER, RemoteDispatchRealtimeControlSwitch.BATTERY_CONTROL);
		assertFalse(this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_REALTIME_CONTROL_POWER).isPresent());
		assertFalse(this.written(PytesJs3.ChannelId.SET_REMOTE_DISPATCH_SWITCH).isPresent());
	}

	@Test
	public void keepsWritingInWarningState() throws Exception {
		// a warning (derating, fan, ...) is informational - the inverter runs on
		this.ess.withWorkState(WorkState.WARNING);
		this.charger.withActualPower(500);
		assertEquals(-7, this.applyBatteryControl(520));
	}
}
