package io.openems.edge.ess.energyprice;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;

/**
 * A component with a price channel, stands in for the Time-of-Use-Tariff
 * controller.
 */
public class DummyPriceSource extends AbstractDummyOpenemsComponent<DummyPriceSource> implements OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		QUARTERLY_PRICES(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR)), //
		;

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	public DummyPriceSource(String id) {
		super(id, //
				OpenemsComponent.ChannelId.values(), //
				ChannelId.values() //
		);
	}

	@Override
	protected DummyPriceSource self() {
		return this;
	}
}
