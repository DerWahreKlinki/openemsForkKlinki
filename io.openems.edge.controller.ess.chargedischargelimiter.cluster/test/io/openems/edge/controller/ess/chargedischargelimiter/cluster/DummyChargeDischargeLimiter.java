package io.openems.edge.controller.ess.chargedischargelimiter.cluster;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter;

/**
 * A limiter of a member with channels only.
 */
public class DummyChargeDischargeLimiter extends AbstractDummyOpenemsComponent<DummyChargeDischargeLimiter>
		implements ControllerEssChargeDischargeLimiter {

	private final String essId;

	public DummyChargeDischargeLimiter(String id, String essId) {
		super(id, //
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEssChargeDischargeLimiter.ChannelId.values() //
		);
		this.essId = essId;
	}

	@Override
	protected DummyChargeDischargeLimiter self() {
		return this;
	}

	@Override
	public String getEssId() {
		return this.essId;
	}

	@Override
	public void run() {
	}
}
