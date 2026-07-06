package io.openems.edge.solaredge.hybrid.ess;

import static io.openems.common.test.TestUtils.createDummyClock;
import static io.openems.edge.ess.api.HybridEss.ChannelId.DC_DISCHARGE_POWER;
import static io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER;
import static io.openems.edge.ess.api.ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER;
import static io.openems.edge.ess.api.SymmetricEss.ChannelId.ACTIVE_POWER;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.AC_CHARGE_POLICY;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.AC_POWER;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.AC_POWER_SCALE;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.CHARGE_POWER;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.CHARGE_POWER_WANTED;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.CONTROL_MODE;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.DC_POWER;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.DC_POWER_SCALE;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.MAX_CHARGE_CONTINUES_POWER;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.MAX_DISCHARGE_CONTINUES_POWER;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.SET_AC_CHARGE_POLICY;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.SET_CONTROL_MODE;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.SET_MAX_CHARGE_POWER;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.SET_MAX_DISCHARGE_POWER;
import static io.openems.edge.solaredge.hybrid.ess.SolarEdgeHybridEss.ChannelId.SET_REMOTE_CONTROL_COMMAND_MODE;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.test.TimeLeapClock;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.solaredge.enums.AcChargePolicy;
import io.openems.edge.solaredge.enums.ChargeDischargeMode;
import io.openems.edge.solaredge.enums.ControlMode;
import io.openems.edge.solaredge.enums.SetPointMode;

public class SolarEdgeHybridEssImplTest {

	private static final String ESS_ID = "solarEdgeHybridEss0";
	private static final String MODBUS_ID = "modbus0";
	private static final String METER_ID = "meter0";

	private static final Instant TEST_START = Instant.ofEpochMilli(
			1546300800000L /* Tuesday, 1. January 2019 00:00:00 UTC */);

	private static class MyComponentTest extends ComponentTest {

		private Integer chargePowerTarget = null;

		public MyComponentTest(OpenemsComponent sut) throws OpenemsException {
			super(sut);
		}

		public MyComponentTest withChargePowerTarget(Integer chargePowerTarget) {
			this.chargePowerTarget = chargePowerTarget;
			return this;
		}

		@Override
		protected void handleEvent(String topic) throws Exception {
			if (topic.equals(EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS)) {
				if (this.chargePowerTarget != null) {
					((SolarEdgeHybridEssImpl) this.getSut()).applyChargePower(this.chargePowerTarget);
				}
			}
			super.handleEvent(topic);
		}
	}

	private static TimeLeapClock createClock() {
		return new TimeLeapClock(TEST_START, ZoneId.of("UTC"));
	}

	private static MyComponentTest createComponentTest(SolarEdgeHybridEssImpl sut, TimeLeapClock clock)
			throws Exception {
		return createComponentTest(sut, clock, SetPointMode.DC_SETPOINT, false, 5_000, 5_000);
	}

	private static MyComponentTest createComponentTest(
			SolarEdgeHybridEssImpl sut,
			TimeLeapClock clock,
			SetPointMode setPointMode,
			boolean readOnlyMode,
			int chargePowerLimit,
			int dischargePowerLimit) throws Exception {

		final var test = new MyComponentTest(sut);

		test.addReference("componentManager", new DummyComponentManager(clock));
		test.addReference("setModbus", new DummyModbusBridge(MODBUS_ID));

		test.activate(MyConfig.create() //
				.setId(ESS_ID) //
				.setMeterId(METER_ID) //
				.setModbusId(MODBUS_ID) //
				.setSetPointMode(setPointMode) //
				.setReadOnlyMode(readOnlyMode) //
				.setChargePowerLimit(chargePowerLimit) //
				.setDischargePowerLimit(dischargePowerLimit) //
				.setFeedToGridPowerLimit(10_000) //
				.setMaxPvProductionPowerLimit(20_000) //
				.build());

		return test;
	}

	@Test
	public void testApplyChargePowerChargeMode() throws Exception {
		final var clock = createClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock) //
				.withChargePowerTarget(-2_000) //
				.next(new TestCase("Charge battery with 2000 W") //
						.input(CONTROL_MODE, ControlMode.SE_CTRL_MODE_REMOTE) //
						.input(AC_CHARGE_POLICY, AcChargePolicy.SE_CHARGE_DISCHARGE_MODE_ALWAYS) //
						.input(MAX_CHARGE_CONTINUES_POWER, 5_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 5_000) //
						.output(SET_CONTROL_MODE, ControlMode.SE_CTRL_MODE_REMOTE) //
						.output(SET_AC_CHARGE_POLICY, AcChargePolicy.SE_CHARGE_DISCHARGE_MODE_ALWAYS) //
						.output(CHARGE_POWER_WANTED, -2_000) //
						.output(CHARGE_POWER, -2_000) //
						.output(SET_REMOTE_CONTROL_COMMAND_MODE, ChargeDischargeMode.SE_CHARGE_POLICY_PV_AC) //
						.output(SET_MAX_CHARGE_POWER, 2_000) //
						.output(SET_MAX_DISCHARGE_POWER, 0)) //
				.deactivate();
	}

	@Test
	public void testApplyChargePowerDischargeMode() throws Exception {
		final var clock = createClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock) //
				.withChargePowerTarget(2_500) //
				.next(new TestCase("Discharge battery with 2500 W") //
						.input(CONTROL_MODE, ControlMode.SE_CTRL_MODE_REMOTE) //
						.input(AC_CHARGE_POLICY, AcChargePolicy.SE_CHARGE_DISCHARGE_MODE_ALWAYS) //
						.input(MAX_CHARGE_CONTINUES_POWER, 5_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 5_000) //
						.output(SET_CONTROL_MODE, ControlMode.SE_CTRL_MODE_REMOTE) //
						.output(SET_AC_CHARGE_POLICY, AcChargePolicy.SE_CHARGE_DISCHARGE_MODE_ALWAYS) //
						.output(CHARGE_POWER_WANTED, 2_500) //
						.output(CHARGE_POWER, 2_500) //
						.output(SET_REMOTE_CONTROL_COMMAND_MODE, ChargeDischargeMode.SE_CHARGE_POLICY_MAX_EXPORT) //
						.output(SET_MAX_CHARGE_POWER, 0) //
						.output(SET_MAX_DISCHARGE_POWER, 2_500)) //
				.deactivate();
	}

	@Test
	public void testApplyChargePowerIdleMode() throws Exception {
		final var clock = createClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock) //
				.withChargePowerTarget(0) //
				.next(new TestCase("Idle battery") //
						.input(CONTROL_MODE, ControlMode.SE_CTRL_MODE_REMOTE) //
						.input(AC_CHARGE_POLICY, AcChargePolicy.SE_CHARGE_DISCHARGE_MODE_ALWAYS) //
						.input(MAX_CHARGE_CONTINUES_POWER, 5_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 5_000) //
						.output(SET_CONTROL_MODE, ControlMode.SE_CTRL_MODE_REMOTE) //
						.output(SET_AC_CHARGE_POLICY, AcChargePolicy.SE_CHARGE_DISCHARGE_MODE_ALWAYS) //
						.output(CHARGE_POWER_WANTED, 0) //
						.output(CHARGE_POWER, 0) //
						.output(SET_REMOTE_CONTROL_COMMAND_MODE, ChargeDischargeMode.SE_CHARGE_POLICY_PV_AC) //
						.output(SET_MAX_CHARGE_POWER, 0) //
						.output(SET_MAX_DISCHARGE_POWER, 0)) //
				.deactivate();
	}

	@Test
	public void testApplyChargePowerClampsChargeToConfigLimit() throws Exception {
		final var clock = createClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock, SetPointMode.DC_SETPOINT, false, 5_000, 5_000) //
				.withChargePowerTarget(-8_000) //
				.next(new TestCase("Requested charge exceeds configured charge limit") //
						.input(CONTROL_MODE, ControlMode.SE_CTRL_MODE_REMOTE) //
						.input(AC_CHARGE_POLICY, AcChargePolicy.SE_CHARGE_DISCHARGE_MODE_ALWAYS) //
						.input(MAX_CHARGE_CONTINUES_POWER, 8_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 5_000) //
						.output(CHARGE_POWER_WANTED, -8_000) //
						.output(CHARGE_POWER, -8_000) //
						.output(SET_REMOTE_CONTROL_COMMAND_MODE, ChargeDischargeMode.SE_CHARGE_POLICY_PV_AC) //
						.output(SET_MAX_CHARGE_POWER, 5_000) //
						.output(SET_MAX_DISCHARGE_POWER, 0)) //
				.deactivate();
	}

	@Test
	public void testApplyChargePowerClampsDischargeToConfigLimit() throws Exception {
		final var clock = createClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock, SetPointMode.DC_SETPOINT, false, 5_000, 5_000) //
				.withChargePowerTarget(8_000) //
				.next(new TestCase("Requested discharge exceeds configured discharge limit") //
						.input(CONTROL_MODE, ControlMode.SE_CTRL_MODE_REMOTE) //
						.input(AC_CHARGE_POLICY, AcChargePolicy.SE_CHARGE_DISCHARGE_MODE_ALWAYS) //
						.input(MAX_CHARGE_CONTINUES_POWER, 5_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 8_000) //
						.output(CHARGE_POWER_WANTED, 8_000) //
						.output(CHARGE_POWER, 8_000) //
						.output(SET_REMOTE_CONTROL_COMMAND_MODE, ChargeDischargeMode.SE_CHARGE_POLICY_MAX_EXPORT) //
						.output(SET_MAX_CHARGE_POWER, 0) //
						.output(SET_MAX_DISCHARGE_POWER, 5_000)) //
				.deactivate();
	}

	@Test
	public void testApplyChargePowerClampsChargeToHardwareLimitIfLowerThanConfig() throws Exception {
		final var clock = createClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock, SetPointMode.DC_SETPOINT, false, 5_000, 5_000) //
				.withChargePowerTarget(-4_000) //
				.next(new TestCase("Requested charge exceeds hardware charge limit") //
						.input(CONTROL_MODE, ControlMode.SE_CTRL_MODE_REMOTE) //
						.input(AC_CHARGE_POLICY, AcChargePolicy.SE_CHARGE_DISCHARGE_MODE_ALWAYS) //
						.input(MAX_CHARGE_CONTINUES_POWER, 3_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 5_000) //
						.output(SET_REMOTE_CONTROL_COMMAND_MODE, ChargeDischargeMode.SE_CHARGE_POLICY_PV_AC) //
						.output(SET_MAX_CHARGE_POWER, 3_000) //
						.output(SET_MAX_DISCHARGE_POWER, 0)) //
				.deactivate();
	}

	@Test
	public void testApplyChargePowerClampsDischargeToHardwareLimitIfLowerThanConfig() throws Exception {
		final var clock = createClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock, SetPointMode.DC_SETPOINT, false, 5_000, 5_000) //
				.withChargePowerTarget(4_000) //
				.next(new TestCase("Requested discharge exceeds hardware discharge limit") //
						.input(CONTROL_MODE, ControlMode.SE_CTRL_MODE_REMOTE) //
						.input(AC_CHARGE_POLICY, AcChargePolicy.SE_CHARGE_DISCHARGE_MODE_ALWAYS) //
						.input(MAX_CHARGE_CONTINUES_POWER, 5_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 3_000) //
						.output(SET_REMOTE_CONTROL_COMMAND_MODE, ChargeDischargeMode.SE_CHARGE_POLICY_MAX_EXPORT) //
						.output(SET_MAX_CHARGE_POWER, 0) //
						.output(SET_MAX_DISCHARGE_POWER, 3_000)) //
				.deactivate();
	}

	@Test
	public void testSetLimitsInDcSetpointMode() throws Exception {
		final var clock = createDummyClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock, SetPointMode.DC_SETPOINT, false, 5_000, 5_000) //
				.next(new TestCase("DC setpoint limits only battery charge/discharge") //
						.input(MAX_CHARGE_CONTINUES_POWER, 7_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 6_000) //
						.output(ALLOWED_CHARGE_POWER, -5_000) //
						.output(ALLOWED_DISCHARGE_POWER, 5_000)) //
				.deactivate();
	}

	@Test
	public void testSetLimitsUsesHardwareLimitIfLowerThanConfig() throws Exception {
		final var clock = createDummyClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock, SetPointMode.DC_SETPOINT, false, 5_000, 5_000) //
				.next(new TestCase("Hardware limits are lower than configured limits") //
						.input(MAX_CHARGE_CONTINUES_POWER, 3_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 2_500) //
						.output(ALLOWED_CHARGE_POWER, -3_000) //
						.output(ALLOWED_DISCHARGE_POWER, 2_500)) //
				.deactivate();
	}

	@Test
	public void testSetLimitsInAcSetpointModeAddsPvProductionToAllowedDischargePower() throws Exception {
		final var clock = createDummyClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock, SetPointMode.AC_SETPOINT, false, 5_000, 5_000) //
				.next(new TestCase("AC setpoint allows battery discharge plus PV production") //
						.input(AC_POWER, 6_000) //
						.input(AC_POWER_SCALE, 0) //
						.input(DC_POWER, 1_000) //
						.input(DC_POWER_SCALE, 0) //
						.input(DC_DISCHARGE_POWER, 1_000) //
						.input(MAX_CHARGE_CONTINUES_POWER, 4_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 3_000) //
						.output(ACTIVE_POWER, 6_000) //
						.output(ALLOWED_CHARGE_POWER, -4_000) //
						.output(ALLOWED_DISCHARGE_POWER, 8_000)) //
				.deactivate();
	}

	@Test
	public void testSetMyActivePowerUsesAcPower() throws Exception {
		final var clock = createDummyClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock) //
				.next(new TestCase("AC power is mapped to ActivePower") //
						.input(AC_POWER, 3_333) //
						.input(AC_POWER_SCALE, 0) //
						.input(DC_POWER, 0) //
						.input(DC_POWER_SCALE, 0) //
						.input(MAX_CHARGE_CONTINUES_POWER, 5_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 5_000) //
						.output(ACTIVE_POWER, 3_333)) //
				.deactivate();
	}

	@Test
	public void testSetMyActivePowerUsesNegativeDcPowerIfAcPowerIsZero() throws Exception {
		final var clock = createDummyClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock) //
				.next(new TestCase("Negative DC power is used as ActivePower if AC power is zero") //
						.input(AC_POWER, 0) //
						.input(AC_POWER_SCALE, 0) //
						.input(DC_POWER, -1_500) //
						.input(DC_POWER_SCALE, 0) //
						.input(MAX_CHARGE_CONTINUES_POWER, 5_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 5_000) //
						.output(ACTIVE_POWER, -1_500)) //
				.deactivate();
	}

	@Test
	public void testReadOnlyModeSwitchesToAutomaticMode() throws Exception {
		final var clock = createClock();
		final var sut = new SolarEdgeHybridEssImpl();

		createComponentTest(sut, clock, SetPointMode.DC_SETPOINT, true, 5_000, 5_000) //
				.withChargePowerTarget(2_000) //
				.next(new TestCase("Read-only mode must not apply remote-control power commands") //
						.input(MAX_CHARGE_CONTINUES_POWER, 5_000) //
						.input(MAX_DISCHARGE_CONTINUES_POWER, 5_000) //
						.output(SET_CONTROL_MODE, ControlMode.SE_CTRL_MODE_MAX_SELF_CONSUMPTION) //
						.output(CHARGE_POWER_WANTED, null) //
						.output(CHARGE_POWER, null) //
						.output(SET_MAX_CHARGE_POWER, null) //
						.output(SET_MAX_DISCHARGE_POWER, null)) //
				.deactivate();
	}
}