package io.openems.edge.controller.evcs.price;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.edge.evcs.api.ChargeMode;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private boolean enabled = true;
		private boolean debugMode = false;
		private String evcsId = "evcs0";
		private boolean enabledCharging = true;
		private ChargeMode chargeMode = ChargeMode.FORCE_CHARGE;
		private int forceChargeMinPower = 7560;
		private int defaultChargeMinPower = 0;
		private Priority priority = Priority.CAR;
		private int energySessionLimit = 0;
		private int excessChargeHystersis = 120;
		private int excessChargePauseHysteresis = 30;
		private int startConfirmationTime = 0;
		private double priceLimit = 0;
		private double priceLimitFullPower = 0;
		private int priceChargePower = 11040;
		private boolean useStorageSurplus = false;
		private int storageTargetSocNet = 80;
		private double storageLossSurcharge = 1;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
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

		public Builder setEvcsId(String evcsId) {
			this.evcsId = evcsId;
			return this;
		}

		public Builder setEnableCharging(boolean enableCharging) {
			this.enabledCharging = enableCharging;
			return this;
		}

		public Builder setChargeMode(ChargeMode chargeMode) {
			this.chargeMode = chargeMode;
			return this;
		}

		public Builder setForceChargeMinPower(int forceChargeMinPower) {
			this.forceChargeMinPower = forceChargeMinPower;
			return this;
		}

		public Builder setDefaultChargeMinPower(int defaultChargeMinPower) {
			this.defaultChargeMinPower = defaultChargeMinPower;
			return this;
		}

		public Builder setPriority(Priority priority) {
			this.priority = priority;
			return this;
		}

		public Builder setEnergySessionLimit(int energySessionLimit) {
			this.energySessionLimit = energySessionLimit;
			return this;
		}

		public Builder setExcessChargeHystersis(int excessChargeHystersis) {
			this.excessChargeHystersis = excessChargeHystersis;
			return this;
		}

		public Builder setExcessChargePauseHysteresis(int excessChargePauseHysteresis) {
			this.excessChargePauseHysteresis = excessChargePauseHysteresis;
			return this;
		}

		public Builder setStartConfirmationTime(int startConfirmationTime) {
			this.startConfirmationTime = startConfirmationTime;
			return this;
		}

		public Builder setPriceLimit(double priceLimit) {
			this.priceLimit = priceLimit;
			return this;
		}

		public Builder setPriceLimitFullPower(double priceLimitFullPower) {
			this.priceLimitFullPower = priceLimitFullPower;
			return this;
		}

		public Builder setPriceChargePower(int priceChargePower) {
			this.priceChargePower = priceChargePower;
			return this;
		}

		public Builder setUseStorageSurplus(boolean useStorageSurplus) {
			this.useStorageSurplus = useStorageSurplus;
			return this;
		}

		public Builder setStorageTargetSocNet(int storageTargetSocNet) {
			this.storageTargetSocNet = storageTargetSocNet;
			return this;
		}

		public Builder setStorageLossSurcharge(double storageLossSurcharge) {
			this.storageLossSurcharge = storageLossSurcharge;
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
	public String evcs_id() {
		return this.builder.evcsId;
	}

	@Override
	public boolean enabledCharging() {
		return this.builder.enabledCharging;
	}

	@Override
	public ChargeMode chargeMode() {
		return this.builder.chargeMode;
	}

	@Override
	public int forceChargeMinPower() {
		return this.builder.forceChargeMinPower;
	}

	@Override
	public int defaultChargeMinPower() {
		return this.builder.defaultChargeMinPower;
	}

	@Override
	public Priority priority() {
		return this.builder.priority;
	}

	@Override
	public int energySessionLimit() {
		return this.builder.energySessionLimit;
	}

	@Override
	public boolean debugMode() {
		return this.builder.debugMode;
	}

	@Override
	public int excessChargeHystersis() {
		return this.builder.excessChargeHystersis;
	}

	@Override
	public int excessChargePauseHysteresis() {
		return this.builder.excessChargePauseHysteresis;
	}

	@Override
	public int startConfirmationTime() {
		return this.builder.startConfirmationTime;
	}

	@Override
	public double priceLimit() {
		return this.builder.priceLimit;
	}

	@Override
	public double priceLimitFullPower() {
		return this.builder.priceLimitFullPower;
	}

	@Override
	public double pvPrice() {
		return 7;
	}

	@Override
	public int priceChargePower() {
		return this.builder.priceChargePower;
	}

	@Override
	public boolean useStorageSurplus() {
		return this.builder.useStorageSurplus;
	}

	@Override
	public int storageTargetSocNet() {
		return this.builder.storageTargetSocNet;
	}

	@Override
	public double storageLossSurcharge() {
		return this.builder.storageLossSurcharge;
	}
}