package io.openems.edge.ess.energyprice;

import io.openems.common.test.AbstractComponentConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private String essId = "ess0";
		private String priceChannel = "ctrlToU1/QuarterlyPrices";
		private double feedInTariff = 7;
		private int efficiencyDays = 0;
		private int efficiency = 100;
		private int historyDays = 0;
		private boolean debugMode = false;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setEssId(String essId) {
			this.essId = essId;
			return this;
		}

		public Builder setPriceChannel(String priceChannel) {
			this.priceChannel = priceChannel;
			return this;
		}

		public Builder setFeedInTariff(double feedInTariff) {
			this.feedInTariff = feedInTariff;
			return this;
		}

		public Builder setEfficiencyDays(int efficiencyDays) {
			this.efficiencyDays = efficiencyDays;
			return this;
		}

		public Builder setEfficiency(int efficiency) {
			this.efficiency = efficiency;
			return this;
		}

		public Builder setHistoryDays(int historyDays) {
			this.historyDays = historyDays;
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
	public String ess_id() {
		return this.builder.essId;
	}

	@Override
	public String priceChannel() {
		return this.builder.priceChannel;
	}

	@Override
	public double feedInTariff() {
		return this.builder.feedInTariff;
	}

	@Override
	public int efficiencyDays() {
		return this.builder.efficiencyDays;
	}

	@Override
	public int efficiency() {
		return this.builder.efficiency;
	}

	@Override
	public int historyDays() {
		return this.builder.historyDays;
	}

	@Override
	public boolean debugMode() {
		return this.builder.debugMode;
	}

	@Override
	public String ess_target() {
		return "(&(enabled=true)(!(service.pid=" + this.id() + "))(|(id=" + this.ess_id() + ")))";
	}
}
