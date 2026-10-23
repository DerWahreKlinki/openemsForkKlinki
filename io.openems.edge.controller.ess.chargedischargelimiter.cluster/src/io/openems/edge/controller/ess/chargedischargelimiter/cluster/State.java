package io.openems.edge.controller.ess.chargedischargelimiter.cluster;

import io.openems.common.types.OptionsEnum;

/**
 * The state of the cluster, inherited from the limiters of its members.
 */
public enum State implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	/** Every member with a limiter is in normal operation. */
	NORMAL(0, "Normal"), //
	/** A member limits: a SoC limit is reached or near. */
	MEMBER_LIMITED(1, "A member limits"), //
	/** A member balances or force-charges. */
	MEMBER_BALANCING(2, "A member balances"), //
	/** A member reports an error or has no state. */
	MEMBER_ERROR(3, "A member reports an error"), //
	/** No member has a limiter: nothing to report. */
	NO_MEMBERS(4, "No member with a limiter");

	private final int value;
	private final String name;

	private State(int value, String name) {
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
