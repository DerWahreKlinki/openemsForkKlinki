package io.openems.edge.timeofusetariff.forecast;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.common.utils.ConfigUtils;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private String timeOfUseTariffId = "timeOfUseTariff0";
		private String url = "https://epexpredictor.batzill.com/prices_short";
		private String region = "DE";
		private double surchargeCt = 17.4;
		private int taxPercent = 19;
		private int horizonHours = 144;
		private int windowHours = 4;
		private int ratingLevel2 = 40;
		private int ratingLevel3 = 45;
		private int ratingLevel4 = 50;
		private int ratingLevel5 = 55;
		private boolean debugMode = false;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setTimeOfUseTariffId(String timeOfUseTariffId) {
			this.timeOfUseTariffId = timeOfUseTariffId;
			return this;
		}

		public Builder setUrl(String url) {
			this.url = url;
			return this;
		}

		public Builder setRegion(String region) {
			this.region = region;
			return this;
		}

		public Builder setSurchargeCt(double surchargeCt) {
			this.surchargeCt = surchargeCt;
			return this;
		}

		public Builder setTaxPercent(int taxPercent) {
			this.taxPercent = taxPercent;
			return this;
		}

		public Builder setHorizonHours(int horizonHours) {
			this.horizonHours = horizonHours;
			return this;
		}

		public Builder setWindowHours(int windowHours) {
			this.windowHours = windowHours;
			return this;
		}

		public Builder setRatingLevels(int level2, int level3, int level4, int level5) {
			this.ratingLevel2 = level2;
			this.ratingLevel3 = level3;
			this.ratingLevel4 = level4;
			this.ratingLevel5 = level5;
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
	public String timeOfUseTariff_id() {
		return this.builder.timeOfUseTariffId;
	}

	@Override
	public String url() {
		return this.builder.url;
	}

	@Override
	public String region() {
		return this.builder.region;
	}

	@Override
	public double surchargeCt() {
		return this.builder.surchargeCt;
	}

	@Override
	public int taxPercent() {
		return this.builder.taxPercent;
	}

	@Override
	public int horizonHours() {
		return this.builder.horizonHours;
	}

	@Override
	public int windowHours() {
		return this.builder.windowHours;
	}

	@Override
	public int ratingLevel2() {
		return this.builder.ratingLevel2;
	}

	@Override
	public int ratingLevel3() {
		return this.builder.ratingLevel3;
	}

	@Override
	public int ratingLevel4() {
		return this.builder.ratingLevel4;
	}

	@Override
	public int ratingLevel5() {
		return this.builder.ratingLevel5;
	}

	@Override
	public boolean debugMode() {
		return this.builder.debugMode;
	}

	@Override
	public String timeOfUseTariff_target() {
		return ConfigUtils.generateReferenceTargetFilter(this.id(), this.timeOfUseTariff_id());
	}
}
