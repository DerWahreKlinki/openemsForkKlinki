package io.openems.edge.controller.chp.costoptimization;

import io.openems.common.types.OptionsEnum;

public enum StartCriterion implements OptionsEnum {
	PRICE_THRESHOLD(0, "Start based on Time-of-Use price (or fallback price) vs. configured threshold"), //
	GRID_THRESHOLD_ONLY(1, "Ignore price entirely; start purely once minGridPower/temperature gates allow it"); //

	private final int value;
	private final String name;

	private StartCriterion(int value, String name) {
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
		return PRICE_THRESHOLD;
	}
}
