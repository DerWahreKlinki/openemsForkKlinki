package io.openems.edge.meter.janitza.umg96rme;

import static io.openems.edge.meter.api.MeterType.GRID;

import org.junit.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyConfigurationAdmin;

public class MeterJanitzaUmg96rmeImplTest {

	@Test
	public void test() throws Exception {
		new ComponentTest(new MeterJanitzaUmg96rmeImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("meter0") //
						.setModbusId("modbus0") //
						.setType(GRID) //
						.build()) //
		;
	}
}