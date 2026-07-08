package io.openems.edge.controller.ess.chargedischargelimiter.enums;

import io.openems.common.types.OptionsEnum;

public enum BalancingDecision implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	NO(0, "No balancing needed"), // SoC in range between min and max
	YES(1, "Balancing necessary and allowed"), //
	YES_DEFERRED(2, "Balancing necessary but NOT allowed"), //
;


	private final int value;
	private final String name;

	private BalancingDecision(int value, String name) {
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