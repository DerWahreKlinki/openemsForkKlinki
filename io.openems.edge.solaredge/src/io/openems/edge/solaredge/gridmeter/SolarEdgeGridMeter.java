package io.openems.edge.solaredge.gridmeter;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Interface for SolarEdge Grid Meter. This interface defines various channels
 * and methods to interact with a SolarEdge Grid Meter.
 */
public interface SolarEdgeGridMeter extends OpenemsComponent {

	/**
	 * Enumeration of Channel IDs for SolarEdge Grid Meter.
	 */
	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/**
		 * Grid Power. Negative while feed-to-grid.
		 * 
		 * <ul>
		 * <li>Interface: SolarEdgeGridMeter
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).persistencePriority(PersistencePriority.HIGH)),

		// Power channels for each phase
		/**
		 * Grid Power L1. Negative while feed-to-grid.
		 * 
		 * <ul>
		 * <li>Interface: SolarEdgeGridMeter
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		POWER_L1(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).persistencePriority(PersistencePriority.HIGH)),

		/**
		 * Grid Power L2. Negative while feed-to-grid.
		 * 
		 * <ul>
		 * <li>Interface: SolarEdgeGridMeter
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		POWER_L2(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).persistencePriority(PersistencePriority.HIGH)),

		/**
		 * Grid Power L3. Negative while feed-to-grid.
		 * 
		 * <ul>
		 * <li>Interface: SolarEdgeGridMeter
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		POWER_L3(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).persistencePriority(PersistencePriority.HIGH)),

		/**
		 * Power Scale. Used to calculate power values.
		 * <ul>
		 * <li>Interface: SolarEdgeGridMeter
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		POWER_SCALE(Doc.of(OpenemsType.INTEGER).persistencePriority(PersistencePriority.HIGH));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the Channel for {@link ChannelId#POWER}.
	 *
	 * @return the Channel
	 */
	default IntegerReadChannel getPowerChannel() {
		return this.channel(ChannelId.POWER);
	}

	/**
	 * See {@link ChannelId#POWER}
	 *
	 * @return the Channel {@link Value}
	 */
	default Value<Integer> getPower() {
		return this.getPowerChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#POWER_L1}.
	 *
	 * @return the Channel
	 */
	default IntegerReadChannel getPowerL1Channel() {
		return this.channel(ChannelId.POWER_L1);
	}

	/**
	 * See {@link ChannelId#POWER_L1}
	 *
	 * @return the Channel {@link Value}
	 */
	default Value<Integer> getPowerL1() {
		return this.getPowerL1Channel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#POWER_L2}.
	 *
	 * @return the Channel
	 */
	default IntegerReadChannel getPowerL2Channel() {
		return this.channel(ChannelId.POWER_L2);
	}

	/**
	 * See {@link ChannelId#POWER_L2}
	 *
	 * @return the Channel {@link Value}
	 */
	default Value<Integer> getPowerL2() {
		return this.getPowerL2Channel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#POWER_L3}.
	 *
	 * @return the Channel
	 */
	default IntegerReadChannel getPowerL3Channel() {
		return this.channel(ChannelId.POWER_L3);
	}

	/**
	 * See {@link ChannelId#POWER_L3}
	 *
	 * @return the Channel {@link Value}
	 */
	default Value<Integer> getPowerL3() {
		return this.getPowerL3Channel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#POWER_SCALE}.
	 *
	 * @return the Channel
	 */
	default IntegerReadChannel getPowerScaleChannel() {
		return this.channel(ChannelId.POWER_SCALE);
	}

	/**
	 * See {@link ChannelId#POWER_SCALE}
	 *
	 * @return the Channel {@link Value}
	 */
	default Value<Integer> getPowerScale() {
		return this.getPowerScaleChannel().value();
	}

}
