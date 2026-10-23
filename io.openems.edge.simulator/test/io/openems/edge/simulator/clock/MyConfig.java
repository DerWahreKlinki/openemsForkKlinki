package io.openems.edge.simulator.clock;

import io.openems.common.test.AbstractComponentConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private double factor = 1.0;
		private String start = "";

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setFactor(double factor) {
			this.factor = factor;
			return this;
		}

		public Builder setStart(String start) {
			this.start = start;
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
	public double factor() {
		return this.builder.factor;
	}

	@Override
	public String start() {
		return this.builder.start;
	}
}
