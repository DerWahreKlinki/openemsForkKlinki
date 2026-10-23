package io.openems.edge.controller.ess.chargedischargelimiter.cluster;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;
import io.openems.edge.ess.api.MetaEss;
import io.openems.edge.ess.api.SymmetricEss;

/**
 * An ESS cluster with members.
 */
public class DummyEssCluster extends AbstractDummyOpenemsComponent<DummyEssCluster> implements MetaEss, SymmetricEss {

	private final String[] essIds;

	public DummyEssCluster(String id, String... essIds) {
		super(id, //
				OpenemsComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values() //
		);
		this.essIds = essIds;
	}

	@Override
	protected DummyEssCluster self() {
		return this;
	}

	@Override
	public String[] getEssIds() {
		return this.essIds;
	}
}
