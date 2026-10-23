package io.openems.edge.controller.ess.chargedischargelimiter.cluster;

import static io.openems.common.channel.PersistencePriority.HIGH;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.StringReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ControllerEssChargeDischargeLimiterCluster extends Controller, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * The state of the cluster, inherited from the limiters of its members.
		 */
		STATE_MACHINE(Doc.of(State.values()) //
				.persistencePriority(HIGH) //
				.text("State of the cluster, inherited from the limiters of its members")),
		/**
		 * The member and its state that set the state of the cluster; empty in normal
		 * operation.
		 */
		TRIGGER(Doc.of(OpenemsType.STRING) //
				.persistencePriority(HIGH) //
				.text("The member and its state that set the state of the cluster")),
		/**
		 * Number of members with a limiter.
		 */
		MEMBERS(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(HIGH) //
				.text("Members with a limiter")),
		/**
		 * Lower end of the SoC window of the cluster: the sum over the members.
		 */
		MIN_ENERGY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Lower end of the SoC window of the cluster")),
		/**
		 * Upper end of the SoC window of the cluster: the sum over the members.
		 */
		MAX_ENERGY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Upper end of the SoC window of the cluster")),
		/**
		 * Energy in the cluster: the sum over the members.
		 */
		CURRENT_ENERGY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.persistencePriority(HIGH) //
				.text("Energy in the cluster")),
		/**
		 * The SoC of the cluster within its window: 0 % at the summed lower end,
		 * 100 % at the summed upper end.
		 */
		NET_SOC(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.persistencePriority(HIGH) //
				.text("SoC of the cluster within its window")),
		/**
		 * Charge power the members allow right now, as a positive value.
		 */
		CHARGE_POWER_ALLOWED(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(HIGH) //
				.text("Charge power allowed right now")),
		/**
		 * Discharge power the members allow right now.
		 */
		DISCHARGE_POWER_ALLOWED(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(HIGH) //
				.text("Discharge power allowed right now"));

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
	 * Gets the Channel for {@link ChannelId#STATE_MACHINE}.
	 *
	 * @return the Channel
	 */
	public default Channel<State> getStateMachineChannel() {
		return this.channel(ChannelId.STATE_MACHINE);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#STATE_MACHINE}.
	 *
	 * @param value the next value
	 */
	public default void _setStateMachine(State value) {
		this.getStateMachineChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#TRIGGER}.
	 *
	 * @return the Channel
	 */
	public default StringReadChannel getTriggerChannel() {
		return this.channel(ChannelId.TRIGGER);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#TRIGGER}.
	 *
	 * @param value the next value
	 */
	public default void _setTrigger(String value) {
		this.getTriggerChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#NET_SOC}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getNetSocChannel() {
		return this.channel(ChannelId.NET_SOC);
	}

	/**
	 * Gets the value of {@link ChannelId#NET_SOC} in [%].
	 *
	 * @return the value
	 */
	public default Value<Integer> getNetSoc() {
		return this.getNetSocChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#NET_SOC}.
	 *
	 * @param value the next value
	 */
	public default void _setNetSoc(Integer value) {
		this.getNetSocChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#MEMBERS}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMembersChannel() {
		return this.channel(ChannelId.MEMBERS);
	}

	/**
	 * Gets the value of {@link ChannelId#MEMBERS}.
	 *
	 * @return the value
	 */
	public default Value<Integer> getMembers() {
		return this.getMembersChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#MEMBERS}.
	 *
	 * @param value the next value
	 */
	public default void _setMembers(Integer value) {
		this.getMembersChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#MIN_ENERGY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMinEnergyChannel() {
		return this.channel(ChannelId.MIN_ENERGY);
	}

	/**
	 * Gets the value of {@link ChannelId#MIN_ENERGY} in [Wh].
	 *
	 * @return the value
	 */
	public default Value<Integer> getMinEnergy() {
		return this.getMinEnergyChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#MIN_ENERGY}.
	 *
	 * @param value the next value
	 */
	public default void _setMinEnergy(Integer value) {
		this.getMinEnergyChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_ENERGY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxEnergyChannel() {
		return this.channel(ChannelId.MAX_ENERGY);
	}

	/**
	 * Gets the value of {@link ChannelId#MAX_ENERGY} in [Wh].
	 *
	 * @return the value
	 */
	public default Value<Integer> getMaxEnergy() {
		return this.getMaxEnergyChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#MAX_ENERGY}.
	 *
	 * @param value the next value
	 */
	public default void _setMaxEnergy(Integer value) {
		this.getMaxEnergyChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CURRENT_ENERGY}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getCurrentEnergyChannel() {
		return this.channel(ChannelId.CURRENT_ENERGY);
	}

	/**
	 * Gets the value of {@link ChannelId#CURRENT_ENERGY} in [Wh].
	 *
	 * @return the value
	 */
	public default Value<Integer> getCurrentEnergy() {
		return this.getCurrentEnergyChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CURRENT_ENERGY}.
	 *
	 * @param value the next value
	 */
	public default void _setCurrentEnergy(Integer value) {
		this.getCurrentEnergyChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CHARGE_POWER_ALLOWED}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getChargePowerAllowedChannel() {
		return this.channel(ChannelId.CHARGE_POWER_ALLOWED);
	}

	/**
	 * Gets the value of {@link ChannelId#CHARGE_POWER_ALLOWED} in [W].
	 *
	 * @return the value
	 */
	public default Value<Integer> getChargePowerAllowed() {
		return this.getChargePowerAllowedChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CHARGE_POWER_ALLOWED}.
	 *
	 * @param value the next value
	 */
	public default void _setChargePowerAllowed(Integer value) {
		this.getChargePowerAllowedChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#DISCHARGE_POWER_ALLOWED}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getDischargePowerAllowedChannel() {
		return this.channel(ChannelId.DISCHARGE_POWER_ALLOWED);
	}

	/**
	 * Gets the value of {@link ChannelId#DISCHARGE_POWER_ALLOWED} in [W].
	 *
	 * @return the value
	 */
	public default Value<Integer> getDischargePowerAllowed() {
		return this.getDischargePowerAllowedChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#DISCHARGE_POWER_ALLOWED}.
	 *
	 * @param value the next value
	 */
	public default void _setDischargePowerAllowed(Integer value) {
		this.getDischargePowerAllowedChannel().setNextValue(value);
	}
}
