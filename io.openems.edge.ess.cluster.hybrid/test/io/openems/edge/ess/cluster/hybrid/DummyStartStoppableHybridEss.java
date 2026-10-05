package io.openems.edge.ess.cluster.hybrid;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.test.AbstractDummyManagedSymmetricEss;
import io.openems.edge.ess.test.DummyHybridEss;

/**
 * A simulated {@link HybridEss} that - other than {@link DummyHybridEss} -
 * provides the Channels of {@link StartStoppable}, which are required by the
 * Cluster.
 */
public class DummyStartStoppableHybridEss extends AbstractDummyManagedSymmetricEss<DummyStartStoppableHybridEss>
		implements HybridEss, ManagedSymmetricEss, SymmetricEss, StartStoppable, OpenemsComponent {

	private Integer surplusPower = null;

	public DummyStartStoppableHybridEss(String id) {
		super(id, //
				OpenemsComponent.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				HybridEss.ChannelId.values(), //
				StartStoppable.ChannelId.values());
	}

	@Override
	protected final DummyStartStoppableHybridEss self() {
		return this;
	}

	/**
	 * Set the surplus power.
	 *
	 * @param value the value
	 * @return myself
	 */
	public final DummyStartStoppableHybridEss withSurplusPower(Integer value) {
		this.surplusPower = value;
		return this;
	}

	@Override
	public final Integer getSurplusPower() {
		return this.surplusPower;
	}
}
