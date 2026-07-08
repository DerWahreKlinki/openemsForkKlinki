package io.openems.edge.chp.ecpower.control;

import static io.openems.common.test.TestUtils.createDummyClock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;

public class XrgiControlImplTest {

	/**
	 * Stepless control (regulationSteps=0): the requested percentage is applied
	 * directly, without quantization into discrete units.
	 */
	@Test
	public void testApplyPower_steplessPercentCalculation() throws Exception {
		final var clock = createDummyClock();
		var control = new XrgiControlImpl();

		new ComponentTest(control) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setModbusId("modbus0") //
						.setMaxActivePower(10000) //
						.setRegulationSteps(0) //
						.setHysteresis(120) //
						.build());

		control.applyPower(5000);

		assertEquals(5000, control.geActivePowerTarget().get());
		assertEquals(0, control.getActiveRegulationStep().get());
		assertEquals(50, control.getSetPowerPercentChannel().getNextWriteValue().get());
	}

	/**
	 * A target power above maxActivePower must be clamped to 100%, not allowed to
	 * exceed the hardware's rated output.
	 */
	@Test
	public void testApplyPower_clampsAbove100Percent() throws Exception {
		final var clock = createDummyClock();
		var control = new XrgiControlImpl();

		new ComponentTest(control) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setModbusId("modbus0") //
						.setMaxActivePower(10000) //
						.setRegulationSteps(0) //
						.setHysteresis(120) //
						.build());

		control.applyPower(20000); // double the max

		assertEquals(100, control.getSetPowerPercentChannel().getNextWriteValue().get());
	}

	/**
	 * applyPower(null) is the documented way to turn the CHP off and must be
	 * treated as 0, not throw or leave stale values.
	 */
	@Test
	public void testApplyPower_nullIsTreatedAsZero() throws Exception {
		final var clock = createDummyClock();
		var control = new XrgiControlImpl();

		new ComponentTest(control) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setModbusId("modbus0") //
						.setMaxActivePower(10000) //
						.setRegulationSteps(0) //
						.setHysteresis(120) //
						.build());

		control.applyPower((Integer) null);

		assertEquals(0, control.geActivePowerTarget().get());
		assertEquals(0, control.getSetPowerPercentChannel().getNextWriteValue().get());
	}

	/**
	 * Regression guard for the division-by-zero protection: if maxActivePower is
	 * misconfigured as 0, applyPower() must return early and must not write any
	 * channel (it must not silently divide by zero either).
	 */
	@Test
	public void testApplyPower_maxActivePowerZero_doesNothing() throws Exception {
		final var clock = createDummyClock();
		var control = new XrgiControlImpl();

		new ComponentTest(control) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setModbusId("modbus0") //
						.setMaxActivePower(0) //
						.setRegulationSteps(0) //
						.setHysteresis(120) //
						.build());

		control.applyPower(5000);

		assertNull(control.geActivePowerTarget().get());
		assertTrue(control.getSetPowerPercentChannel().getNextWriteValue().isEmpty());
	}

	/**
	 * With 2 regulation steps, a small target (1%) must be quantized up to
	 * running one full unit (50%), and crossing the half-way point must switch to
	 * both units at full load (100%) - matching the documented behavior in
	 * XrgiControlImpl.applyPower().
	 */
	@Test
	public void testApplyPower_regulationStepsQuantization() throws Exception {
		final var clock = createDummyClock();
		var control = new XrgiControlImpl();

		new ComponentTest(control) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setModbusId("modbus0") //
						.setMaxActivePower(10000) //
						.setRegulationSteps(2) //
						.setHysteresis(0) // isolate quantization from hysteresis timing
						.build());

		control.applyPower(100); // 1% of max -> rounds up to 1 unit
		assertEquals(1, control.getActiveRegulationStep().get());
		assertEquals(50, control.getSetPowerPercentChannel().getNextWriteValue().get());

		control.applyPower(5100); // 51% of max -> rounds up to 2 units
		assertEquals(2, control.getActiveRegulationStep().get());
		assertEquals(100, control.getSetPowerPercentChannel().getNextWriteValue().get());
	}

	/**
	 * A step change requested before the configured hysteresis has elapsed must
	 * be blocked (stays at the previous step, AWAITING_HYSTERESIS=true); once the
	 * hysteresis has elapsed, the same request must succeed.
	 */
	@Test
	public void testApplyPower_hysteresisBlocksRapidStepChange() throws Exception {
		final var clock = createDummyClock();
		var control = new XrgiControlImpl();

		new ComponentTest(control) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setModbusId("modbus0") //
						.setMaxActivePower(10000) //
						.setRegulationSteps(2) //
						.setHysteresis(120) //
						.build());

		control.applyPower(100); // 1% -> first-ever transition to step 1, always allowed
		assertEquals(1, control.getActiveRegulationStep().get());
		assertFalse(control.getAwaitingHysteresis().get());

		control.applyPower(5100); // 51% -> would require step 2, but no time has passed
		assertEquals(1, control.getActiveRegulationStep().get(), "step change must be blocked by hysteresis");
		assertTrue(control.getAwaitingHysteresis().get());
		assertEquals(50, control.getSetPowerPercentChannel().getNextWriteValue().get(),
				"percent must reflect the still-active step 1, not the blocked step 2");

		clock.leap(121, ChronoUnit.SECONDS);
		control.applyPower(5100); // same request again, now after the hysteresis window
		assertEquals(2, control.getActiveRegulationStep().get());
		assertFalse(control.getAwaitingHysteresis().get());
		assertEquals(100, control.getSetPowerPercentChannel().getNextWriteValue().get());
	}

	@Test
	public void testApplyPreparation() throws Exception {
		final var clock = createDummyClock();
		var control = new XrgiControlImpl();

		new ComponentTest(control) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setModbusId("modbus0") //
						.setMaxActivePower(10000) //
						.setRegulationSteps(2) //
						.setHysteresis(120) //
						.build());

		control.applyPreparation(true);
		assertEquals(1, control.getChpPreparationChannel().getNextWriteValue().get());

		control.applyPreparation(false);
		assertEquals(0, control.getChpPreparationChannel().getNextWriteValue().get());
	}

}
