package io.openems.edge.controller.ess.chargedischargelimiter;

import io.openems.common.test.AbstractComponentConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id = "chargeDischargeLimiter0";
		private String alias = "";
		private boolean enabled = true;
		private String essId;
		private int minSoc = 15;
		private int maxSoc = 85;
		private int energyBetweenBalancingCycles = 100;
		private int maxPrice = 0;
		private int forceChargePower = 500;
		private int balancingHysteresis = 3600;
		private boolean debugMode = true;
		private String essTarget = "(enabled=true)";

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

		public Builder setEssId(String essId) {
			this.essId = essId;
			return this;
		}

		public Builder setMinSoc(int minSoc) {
			this.minSoc = minSoc;
			return this;
		}

		public Builder setMaxSoc(int maxSoc) {
			this.maxSoc = maxSoc;
			return this;
		}

		public Builder setEnergyBetweenBalancingCycles(int energyBetweenBalancingCycles) {
			this.energyBetweenBalancingCycles = energyBetweenBalancingCycles;
			return this;
		}

		public Builder setMaxPrice(int maxPrice) {
			this.maxPrice = maxPrice;
			return this;
		}

		public Builder setForceChargePower(int forceChargePower) {
			this.forceChargePower = forceChargePower;
			return this;
		}

		public Builder setBalancingHysteresis(int balancingHysteresis) {
			this.balancingHysteresis = balancingHysteresis;
			return this;
		}

		public Builder setDebugMode(boolean debugMode) {
			this.debugMode = debugMode;
			return this;
		}

		public Builder setEssTarget(String essTarget) {
			this.essTarget = essTarget;
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
	public String ess_id() {
		return this.builder.essId;
	}

	@Override
	public int minSoc() {
		return this.builder.minSoc;
	}

	@Override
	public int maxSoc() {
		return this.builder.maxSoc;
	}

	@Override
	public int energyBetweenBalancingCycles() {
		return this.builder.energyBetweenBalancingCycles;
	}

	@Override
	public int maxPrice() {
		return this.builder.maxPrice;
	}

	@Override
	public int forceChargePower() {
		return this.builder.forceChargePower;
	}

	@Override
	public int balancingHysteresis() {
		return this.builder.balancingHysteresis;
	}

	@Override
	public boolean debugMode() {
		return this.builder.debugMode;
	}

	@Override
	public String ess_target() {
		return this.builder.essTarget;
	}

	@Override
	public String webconsole_configurationFactory_nameHint() {
		return "Controller ESS Charge/Discharge limiter [" + this.id() + "]";
	}
}