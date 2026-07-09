package io.openems.edge.controller.chp.costoptimization;

import io.openems.common.test.AbstractComponentConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id = "ctrlChpCostOptimization0";
		private String alias = "";
		private boolean enabled = true;
		private Mode mode = Mode.AUTOMATIC;
		private boolean debugMode = true;
		private int priceThreshold = 100;
		private int fallbackPrice = 0;
		private int maxActivePower = 10000;
		private int startHyteresis = 3600;
		private int runHyteresis = 3600;
		private int preparationHyteresis = 3600;
		private int minBufferTankTemperature = 60;
		private int thresholdBufferTankTemperature = 70;
		private int maxBufferTankTemperature = 75;
		private int reducePowerThresholdTemperature = 5;
		private int minGridPower = 40000;
		private String meterId = "meter0";
		private String chpId = "chp0";

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

		public Builder setMode(Mode mode) {
			this.mode = mode;
			return this;
		}

		public Builder setDebugMode(boolean debugMode) {
			this.debugMode = debugMode;
			return this;
		}

		public Builder setPriceThreshold(int priceThreshold) {
			this.priceThreshold = priceThreshold;
			return this;
		}

		public Builder setFallbackPrice(int fallbackPrice) {
			this.fallbackPrice = fallbackPrice;
			return this;
		}

		public Builder setMaxActivePower(int maxActivePower) {
			this.maxActivePower = maxActivePower;
			return this;
		}

		public Builder setStartHyteresis(int startHyteresis) {
			this.startHyteresis = startHyteresis;
			return this;
		}

		public Builder setRunHyteresis(int runHyteresis) {
			this.runHyteresis = runHyteresis;
			return this;
		}

		public Builder setPreparationHyteresis(int preparationHyteresis) {
			this.preparationHyteresis = preparationHyteresis;
			return this;
		}

		public Builder setMinBufferTankTemperature(int minBufferTankTemperature) {
			this.minBufferTankTemperature = minBufferTankTemperature;
			return this;
		}

		public Builder setThresholdBufferTankTemperature(int thresholdBufferTankTemperature) {
			this.thresholdBufferTankTemperature = thresholdBufferTankTemperature;
			return this;
		}

		public Builder setMaxBufferTankTemperature(int maxBufferTankTemperature) {
			this.maxBufferTankTemperature = maxBufferTankTemperature;
			return this;
		}

		public Builder setReducePowerThresholdTemperature(int reducePowerThresholdTemperature) {
			this.reducePowerThresholdTemperature = reducePowerThresholdTemperature;
			return this;
		}

		public Builder setMinGridPower(int minGridPower) {
			this.minGridPower = minGridPower;
			return this;
		}

		public Builder setMeterId(String meterId) {
			this.meterId = meterId;
			return this;
		}

		public Builder setChpId(String chpId) {
			this.chpId = chpId;
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
	public int priceThreshold() {
		return this.builder.priceThreshold;
	}

	@Override
	public int fallbackPrice() {
		return this.builder.fallbackPrice;
	}

	@Override
	public String meter_id() {
		return this.builder.meterId;
	}

	@Override
	public String chp_id() {
		return this.builder.chpId;
	}

	@Override
	public int maxActivePower() {
		return this.builder.maxActivePower;
	}

	@Override
	public int startHyteresis() {
		return this.builder.startHyteresis;
	}

	@Override
	public int runHyteresis() {
		return this.builder.runHyteresis;
	}

	@Override
	public Mode mode() {
		return this.builder.mode;
	}

	@Override
	public int preparationHyteresis() {
		return this.builder.preparationHyteresis;
	}

	@Override
	public int minBufferTankTemperature() {
		return this.builder.minBufferTankTemperature;
	}

	@Override
	public int maxBufferTankTemperature() {
		return this.builder.maxBufferTankTemperature;
	}

	@Override
	public int minGridPower() {
		return this.builder.minGridPower;
	}

	@Override
	public int thresholdBufferTankTemperature() {
		return this.builder.thresholdBufferTankTemperature;
	}

	@Override
	public int reducePowerThresholdTemperature() {
		return this.builder.reducePowerThresholdTemperature;
	}

	@Override
	public String webconsole_configurationFactory_nameHint() {
		return "Controller CHP cost optimization [" + this.id() + "]";
	}

}
