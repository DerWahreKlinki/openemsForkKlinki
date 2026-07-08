package io.openems.edge.chp.ecpower.control;

import io.openems.common.test.AbstractComponentConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private String modbusId = null;
		private int modbusUnitId;
		private int maxActivePower = 40000;
		private int regulationSteps = 2;
		private int hysteresis = 120;
		private boolean debugMode = true;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setModbusId(String modbusId) {
			this.modbusId = modbusId;
			return this;
		}

		public Builder setModbusUnitId(int modbusUnitId) {
			this.modbusUnitId = modbusUnitId;
			return this;
		}

		public Builder setMaxActivePower(int maxActivePower) {
			this.maxActivePower = maxActivePower;
			return this;
		}

		public Builder setRegulationSteps(int regulationSteps) {
			this.regulationSteps = regulationSteps;
			return this;
		}

		public Builder setHysteresis(int hysteresis) {
			this.hysteresis = hysteresis;
			return this;
		}

		public Builder setDebugMode(boolean debugMode) {
			this.debugMode = debugMode;
			return this;
		}

		public MyConfig build() {
			return new MyConfig(this);
		}
	}

	/**
	 * Create a Config builder.
	 *
	 * @return a {@link Builder}
	 */
	public static Builder create() {
		return new Builder();
	}

	private final Builder builder;

	private MyConfig(Builder builder) {
		super(Config.class, builder.id);
		this.builder = builder;
	}

	@Override
	public String modbus_id() {
		return this.builder.modbusId;
	}

	@Override
	public int modbusUnitId() {
		return this.builder.modbusUnitId;
	}

	@Override
	public int maxActivePower() {
		return this.builder.maxActivePower;
	}

	@Override
	public int regulationSteps() {
		return this.builder.regulationSteps;
	}

	@Override
	public int hysteresis() {
		return this.builder.hysteresis;
	}

	@Override
	public boolean debugMode() {
		return this.builder.debugMode;
	}

	@Override
	public String Modbus_target() {
		return "(enabled=true)";
	}

}
