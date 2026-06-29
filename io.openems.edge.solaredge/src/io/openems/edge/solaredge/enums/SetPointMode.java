package io.openems.edge.solaredge.enums;

import io.openems.common.types.OptionsEnum;

public  enum SetPointMode implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	AC_SETPOINT(0, "AC Setpoint. Includes PV power"), //
	DC_SETPOINT(1, "DC Setpoint. Control battery directly"); //

	private final int value;
	private final String name;

	private SetPointMode(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}
}
