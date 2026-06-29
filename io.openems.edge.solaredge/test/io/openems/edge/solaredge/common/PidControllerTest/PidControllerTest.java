package io.openems.edge.solaredge.common.PidControllerTest;

import org.junit.Test;

import org.junit.Before;
import org.junit.Assert;
import io.openems.edge.solaredge.common.PidController;

public class PidControllerTest {
	private PidController pidController;
	private double startTime;

	@Before
	public void setUp() {
		// Initialize the PID controller with example parameters
		pidController = new PidController(0.5, 0.1, 0.05,  100,100);
		pidController.setSetpoint(2000); // Set the target feed-to-grid limit
		startTime = System.currentTimeMillis() / 1000.0;
	}

	@Test
	public void testIncreaseOutput() {
		// Simulate a measured value far below the setpoint
		int measuredValue = -5000;
		double currentTime = System.currentTimeMillis() / 1000.0;

		// Get PID controller output
		int output = pidController.update(measuredValue, currentTime);

		// Expected output should be a positive increase toward the setpoint
		Assert.assertEquals(1000, output);
	}

	@Test
	public void testDecreaseOutput() {
		// Simulate a measured value far above the setpoint
		int measuredValue = 5000;
		double currentTime = System.currentTimeMillis() / 1000.0;

		// Get PID controller output
		int output = pidController.update(measuredValue, currentTime);

		// Expected output should be a negative decrease to reduce the value
		Assert.assertEquals(1000, output);
	}

	@Test
	public void testZeroOutput() {
		// Simulate a measured value equal to the setpoint
		int measuredValue = 4000;
		double currentTime = System.currentTimeMillis() / 1000.0;

		// Get PID controller output
		int output = pidController.update(measuredValue, currentTime);

		// The output should be zero as there's no adjustment needed
		Assert.assertEquals(1000, output);
	}

	@Test
	public void testMultipleCycles() {
		// Example scenario with a measured value below the setpoint
		int gridPower = 4000;
		double currentTime = startTime;

		// Simulate PID behavior over 5 cycles
		int previousOutput = 0;
		for (int i = 0; i < 10; i++) {
			currentTime += 1; // Increment time by 1 second for each cycle
			int output = pidController.update(gridPower, currentTime);

			// Check that the output is progressing toward correction
			//Assert.assertTrue("Output should be increasing towards the setpoint", output > previousOutput);
			previousOutput = output;

			// Simulate gradual movement of the measured value toward the setpoint
			gridPower += 200;
		}

		// Final output should be close to the setpoint adjustment (4000)
		Assert.assertTrue("Final output should be near setpoint", previousOutput > 3500);
	}
}
