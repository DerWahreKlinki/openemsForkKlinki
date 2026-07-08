package io.openems.edge.controller.ess.chargedischargelimiter.enums;

import io.openems.common.types.OptionsEnum;

public enum BalancingDeferralReason implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	NONE(0, "Balancing is not deferred"), //
	PEAKSHAVING(1, "Balancing deferred due to active peakshaving"), //
	PRICE_LIMIT(2, "Balancing deferred due to exceeded price limit"), //
	;

	private final int value;
	private final String name;

	private BalancingDeferralReason(int value, String name) {
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
