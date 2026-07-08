package io.openems.edge.generator.test;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;
import io.openems.edge.common.test.TestUtils;
import io.openems.edge.generator.api.ManagedSymmetricGenerator;
import io.openems.edge.generator.api.SymmetricGenerator;

/**
 * Provides a simple, simulated {@link ManagedSymmetricGenerator} component
 * that can be used together with the OpenEMS Component test framework.
 */
public class DummyManagedSymmetricGenerator extends AbstractDummyOpenemsComponent<DummyManagedSymmetricGenerator>
		implements ManagedSymmetricGenerator, SymmetricGenerator, OpenemsComponent {

	private Integer lastAppliedPower = null;
	private Boolean lastAppliedPreparation = null;
	private boolean applyPowerWasCalledWithNull = false;

	public DummyManagedSymmetricGenerator(String id) {
		super(id, //
				OpenemsComponent.ChannelId.values(), //
				SymmetricGenerator.ChannelId.values(), //
				ManagedSymmetricGenerator.ChannelId.values() //
		);
	}

	@Override
	protected DummyManagedSymmetricGenerator self() {
		return this;
	}

	public DummyManagedSymmetricGenerator withGeneratorActivePower(Integer value) {
		TestUtils.withValue(this, SymmetricGenerator.ChannelId.GENERATOR_ACTIVE_POWER, value);
		return this;
	}

	public DummyManagedSymmetricGenerator withAverageBufferTankTemperature(Integer value) {
		TestUtils.withValue(this, SymmetricGenerator.ChannelId.BUFFER_TANK_TEMPERATURE, value);
		return this;
	}

	public DummyManagedSymmetricGenerator withReadyForOperation(boolean value) {
		TestUtils.withValue(this, ManagedSymmetricGenerator.ChannelId.READY_FOR_OPERATION, value);
		return this;
	}

	@Override
	public void applyPower(int calculateChpPowerTarget) {
		this.lastAppliedPower = calculateChpPowerTarget;
		this.applyPowerWasCalledWithNull = false;
	}

	@Override
	public void applyPower(Integer activePowerTarget) {
		this.lastAppliedPower = activePowerTarget;
		this.applyPowerWasCalledWithNull = activePowerTarget == null;
	}

	@Override
	public void applyPreparation(Boolean activate) {
		this.lastAppliedPreparation = activate;
	}

	/**
	 * Gets the last power value passed to {@link #applyPower}.
	 *
	 * @return the last applied power, or null if applyPower(null) was last called
	 */
	public Integer getLastAppliedPower() {
		return this.lastAppliedPower;
	}

	/**
	 * True if the most recent {@link #applyPower} call passed null (i.e. "turn
	 * off").
	 *
	 * @return true if applyPower(null) was the last call
	 */
	public boolean wasLastApplyPowerNull() {
		return this.applyPowerWasCalledWithNull;
	}

	/**
	 * Gets the last value passed to {@link #applyPreparation}.
	 *
	 * @return the last applied preparation flag
	 */
	public Boolean getLastAppliedPreparation() {
		return this.lastAppliedPreparation;
	}
}
