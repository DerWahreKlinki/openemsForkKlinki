package io.openems.edge.pvinverter.sma;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.common.utils.ConfigUtils;
import io.openems.edge.pvinverter.sunspec.Phase;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id = null;
		private boolean readOnly;
		private boolean debugMode;
		private String modbusId = null;
		private int modbusUnitId;
		private Phase phase;
		private int modbusBaseAddress;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setReadOnly(boolean readOnly) {
			this.readOnly = readOnly;
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

		public Builder modbusBaseAddress(int modbusBaseAddress) {
			this.modbusBaseAddress = modbusBaseAddress;
			return this;
		}

		public Builder setPhase(Phase phase) {
			this.phase = phase;
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
	public boolean readOnly() {
		return this.builder.readOnly;
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
	public int modbusBaseAddress() {
		return this.builder.modbusBaseAddress;
	}

	@Override
	public String Modbus_target() {
		return ConfigUtils.generateReferenceTargetFilter(this.id(), this.modbus_id());
	}

	@Override
	public int modbusUnitId() {
		return this.builder.modbusUnitId;
	}

	@Override
	public Phase phase() {
		return this.builder.phase;
	}

}