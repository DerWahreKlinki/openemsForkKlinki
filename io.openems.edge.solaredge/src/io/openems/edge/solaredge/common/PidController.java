package io.openems.edge.solaredge.common;

public class PidController {
	private double kp; // Proportional Gain
	private double ki; // Integral Gain
	private double kd; // Derivative Gain
	private double deltaMax; // Maximum step size for PID output

	private int setpoint; // Desired target value
	private double integral; // Integral term
	private double previousError; // Last error recorded
	private double lastUpdateTime; // Time of last update
	private double previousOutput;
	
	private double deadband;

	public PidController(double kp, double ki, double kd, double deltaMax,  double deadband) {
		this.kp = kp;
		this.ki = ki;
		this.kd = kd;
		this.deltaMax = deltaMax;
		
		this.deadband = deadband;		
		this.setpoint = 0;
		this.integral = 0;
		this.previousError = 0;
		this.lastUpdateTime = System.currentTimeMillis() / 1000.0;
		this.previousOutput = 0;
	}

	public void setSetpoint(int setpoint) {
		this.setpoint = setpoint;
	}

	public int update(int measuredValue, double currentTime) {
		double deltaTime = currentTime - lastUpdateTime;
		lastUpdateTime = currentTime;

		double error = setpoint - measuredValue;
		if (Math.abs(error) < deadband) {
			error = 0;
		}
		// Proportional Term
		double proportional = kp * error;

		// Integral Term
		integral += error * deltaTime;
		double integralTerm = ki * integral;

		// Derivative Term
		double derivative = (error - previousError) / deltaTime;
		double derivativeTerm = kd * derivative;

		previousError = error;

		// Combine all terms
		double output = proportional + integralTerm + derivativeTerm;

		// Limit changes to deltaMax
		double outputChange = output - previousOutput;
		output = previousOutput + Math.max(-deltaMax, Math.min(deltaMax, outputChange));
		this.previousOutput = output;

		return (int) output;
	}
}

