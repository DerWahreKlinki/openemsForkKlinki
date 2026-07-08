package io.openems.edge.thermometer.esera.onewire;

import org.junit.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.thermometer.api.Thermometer;
import io.openems.edge.thermometer.esera.onewire.enums.OwdStatus;

import static io.openems.edge.thermometer.esera.onewire.EseraOneWireThermometer.ChannelId.OWD_READ_FAILED;
import static io.openems.edge.thermometer.esera.onewire.EseraOneWireThermometer.ChannelId.OWD_STATUS;
import static io.openems.edge.thermometer.esera.onewire.EseraOneWireThermometer.ChannelId.TEMPERATURE_OWD_DEBUG;

public class MyModbusDeviceTest {

	@Test
	public void test() throws Exception {
		new ComponentTest(new EseraOneWireThermometerImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("component0") //
						.setModbusId("modbus0") //
						.build()) //
				.next(new TestCase()) //
				.deactivate();
	}

	/**
	 * Regression test for validateAndSetTemperatures(): the method runs on
	 * TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, i.e. before nextValue is promoted to
	 * value. It must read the freshly Modbus-read temperature via getNextValue()
	 * - like it already correctly does for OWD_STATUS - instead of the
	 * still-stale .value() from the previous cycle.
	 */
	@Test
	public void testTemperatureReflectsFreshlyReadValue_notStaleValue() throws Exception {
		new ComponentTest(new EseraOneWireThermometerImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("component0") //
						.setModbusId("modbus0") //
						.build()) //
				.next(new TestCase("Bootstrap: first Modbus read arrives") //
						.input(OWD_STATUS, OwdStatus.NORMAL) //
						.input(TEMPERATURE_OWD_DEBUG, 100)) //
				.next(new TestCase("Temperature must already reflect the freshly read 100, not stale null") //
						.input(OWD_STATUS, OwdStatus.NORMAL) //
						.input(TEMPERATURE_OWD_DEBUG, 200) //
						.output(Thermometer.ChannelId.TEMPERATURE, 100) //
						.output(OWD_READ_FAILED, false)) //
				.next(new TestCase("Next cycle must reflect the next freshly read value (200), not 100 again") //
						.output(Thermometer.ChannelId.TEMPERATURE, 200) //
						.output(OWD_READ_FAILED, false)) //
				.deactivate();
	}

	/**
	 * When the sensor status is not NORMAL, OWD_READ_FAILED must be set and the
	 * TEMPERATURE channel must not be touched with a possibly garbage reading.
	 */
	@Test
	public void testErrorStatusSetsReadFailedAndSkipsTemperature() throws Exception {
		new ComponentTest(new EseraOneWireThermometerImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("component0") //
						.setModbusId("modbus0") //
						.build()) //
				.next(new TestCase("Bootstrap: good reading") //
						.input(OWD_STATUS, OwdStatus.NORMAL) //
						.input(TEMPERATURE_OWD_DEBUG, 100)) //
				.next(new TestCase("Temperature reflects the good reading") //
						.input(OWD_STATUS, OwdStatus.NORMAL) //
						.input(TEMPERATURE_OWD_DEBUG, 100) //
						.output(Thermometer.ChannelId.TEMPERATURE, 100) //
						.output(OWD_READ_FAILED, false)) //
				.next(new TestCase("Sensor error is reported and temperature is left untouched") //
						.input(OWD_STATUS, OwdStatus.ERROR) //
						.output(OWD_READ_FAILED, true) //
						.output(Thermometer.ChannelId.TEMPERATURE, 100)) //
				.deactivate();
	}

}
