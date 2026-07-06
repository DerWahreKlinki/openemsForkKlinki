package io.openems.edge.solaredge.hybrid.ess;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.edge.common.type.Phase.SingleOrAllPhase;
import io.openems.edge.solaredge.enums.SetPointMode;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {

		private String id = "ess0";
		private String alias = "";
		private boolean enabled = true;

		private boolean debugMode = false;

		private String modbusId = "modbus0";
		private int modbusUnitId = 14;

		private SetPointMode setPointMode = SetPointMode.DC_SETPOINT;
		private boolean readOnlyMode = true;

		private int chargePowerLimit = 5000;
		private int dischargePowerLimit = 5000;
		private int feedToGridPowerLimit = 10000;
		private int maxPvProductionPowerLimit = 20000;

		private String meterId = "meter0";

		/*
		 * Compatibility fields for older/copied tests.
		 * They are not part of Config.
		 */
		private boolean hybrid;
		private SingleOrAllPhase phase;
		private String coreTarget;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setAlias(String alias) {
			this.alias = alias;
			return this;
		}

		public Builder setEnabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		public Builder setDebugMode(boolean debugMode) {
			this.debugMode = debugMode;
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

		public Builder setSetPointMode(SetPointMode setPointMode) {
			this.setPointMode = setPointMode;
			return this;
		}

		public Builder setReadOnlyMode(boolean readOnlyMode) {
			this.readOnlyMode = readOnlyMode;
			return this;
		}

		/**
		 * Compatibility alias. Prefer {@link #setReadOnlyMode(boolean)}.
		 */
		public Builder setReadOnly(boolean readOnly) {
			this.readOnlyMode = readOnly;
			return this;
		}

		public Builder setChargePowerLimit(int chargePowerLimit) {
			this.chargePowerLimit = chargePowerLimit;
			return this;
		}

		public Builder setDischargePowerLimit(int dischargePowerLimit) {
			this.dischargePowerLimit = dischargePowerLimit;
			return this;
		}

		public Builder setFeedToGridPowerLimit(int feedToGridPowerLimit) {
			this.feedToGridPowerLimit = feedToGridPowerLimit;
			return this;
		}

		public Builder setMaxPvProductionPowerLimit(int maxPvProductionPowerLimit) {
			this.maxPvProductionPowerLimit = maxPvProductionPowerLimit;
			return this;
		}

		public Builder setMeterId(String meterId) {
			this.meterId = meterId;
			return this;
		}

		/**
		 * Compatibility only. Not part of Config.
		 */
		public Builder setHybrid(boolean hybrid) {
			this.hybrid = hybrid;
			return this;
		}

		/**
		 * Compatibility only. Not part of Config.
		 */
		public Builder setPhase(SingleOrAllPhase phase) {
			this.phase = phase;
			return this;
		}

		/**
		 * Compatibility only. Not part of Config.
		 */
		public Builder setCoreTarget(String coreTarget) {
			this.coreTarget = coreTarget;
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
	public String id() {
		return this.builder.id;
	}

	@Override
	public String alias() {
		return this.builder.alias;
	}

	@Override
	public boolean enabled() {
		return this.builder.enabled;
	}

	@Override
	public boolean debugMode() {
		return this.builder.debugMode;
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
	public SetPointMode setPointMode() {
		return this.builder.setPointMode;
	}

	@Override
	public boolean readOnlyMode() {
		return this.builder.readOnlyMode;
	}

	@Override
	public int chargePowerLimit() {
		return this.builder.chargePowerLimit;
	}

	@Override
	public int dischargePowerLimit() {
		return this.builder.dischargePowerLimit;
	}

	@Override
	public int feedToGridPowerLimit() {
		return this.builder.feedToGridPowerLimit;
	}

	@Override
	public int maxPvProductionPowerLimit() {
		return this.builder.maxPvProductionPowerLimit;
	}

	@Override
	public String meter_id() {
		return this.builder.meterId;
	}

	@Override
	public String webconsole_configurationFactory_nameHint() {
		return "SolarEdge Hybrid Inverter System [" + this.builder.id + "]";
	}
}