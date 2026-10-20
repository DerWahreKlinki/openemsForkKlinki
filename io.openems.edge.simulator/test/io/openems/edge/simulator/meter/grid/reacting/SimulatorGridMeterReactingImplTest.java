package io.openems.edge.simulator.meter.grid.reacting;

import static io.openems.edge.meter.api.ElectricityMeter.ChannelId.ACTIVE_POWER;

import org.junit.jupiter.api.Test;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.meter.test.DummyElectricityMeter;

public class SimulatorGridMeterReactingImplTest {

	@Test
	public void test() throws OpenemsException, Exception {
		new ComponentTest(new SimulatorGridMeterReactingImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.activate(MyConfig.create() //
						.setId("meter0") //
						.build()) //
				.next(new TestCase()) //
				.deactivate();
	}

	@Test
	public void managedConsumptionCounts() throws Exception {
		// A simulated managed consumer (e.g. Simulator.Evcs) is part of the grid
		// balance like a not-metered consumer: 1 kW house + 11 kW car - 8 kW PV
		final var house = new DummyElectricityMeter("meter1").withMeterType(MeterType.CONSUMPTION_NOT_METERED);
		final var car = new DummyElectricityMeter("evcs0").withMeterType(MeterType.MANAGED_CONSUMPTION_METERED);
		final var pv = new DummyElectricityMeter("pv0").withMeterType(MeterType.PRODUCTION);
		new ComponentTest(new SimulatorGridMeterReactingImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("addMeter", house) //
				.addReference("addMeter", car) //
				.addReference("addMeter", pv) //
				.activate(MyConfig.create() //
						.setId("meter0") //
						.build()) //
				.next(new TestCase() //
						.input("meter1", ACTIVE_POWER, 1000) //
						.input("evcs0", ACTIVE_POWER, 11000) //
						.input("pv0", ACTIVE_POWER, 8000) //
						.output("meter0", ACTIVE_POWER, 4000)) //
				.deactivate();
	}
}
