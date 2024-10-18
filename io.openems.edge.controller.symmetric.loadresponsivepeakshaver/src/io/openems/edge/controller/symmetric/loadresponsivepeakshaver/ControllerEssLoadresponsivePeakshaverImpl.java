package io.openems.edge.controller.symmetric.loadresponsivepeakshaver;

import java.time.Duration;
import java.time.Instant;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Symmetric.LoadreponsivePeakshaving", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssLoadresponsivePeakshaverImpl extends AbstractOpenemsComponent
		implements ControllerEssLoadresponsivePeakshaver, Controller, OpenemsComponent {

	public static final double DEFAULT_MAX_ADJUSTMENT_RATE = 0.2;

	private final Logger log = LoggerFactory.getLogger(ControllerEssLoadresponsivePeakshaverImpl.class);

	@Reference
	private ComponentManager componentManager;

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private ManagedSymmetricEss ess;

	private Config config;

	private State state = State.UNDEFINED;
	private static final int HYSTERESIS = 5; // seconds
	private Instant lastStateChangeTime = Instant.MIN;
	private Instant peakshavingStopTime = Instant.MIN;
	private Instant peakshavingStartTime = Instant.MIN;


	public ControllerEssLoadresponsivePeakshaverImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEssLoadresponsivePeakshaver.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		ManagedSymmetricEss ess = this.componentManager.getComponent(this.config.ess_id());
		ElectricityMeter meter = this.componentManager.getComponent(this.config.meter_id());

		/*
		 * Check that we are On-Grid (and warn on undefined Grid-Mode)
		 */
		var gridMode = ess.getGridMode();
		if (gridMode.isUndefined()) {
			this.logWarn(this.log, "Grid-Mode is [UNDEFINED]");
		}
		switch (gridMode) {
		case ON_GRID:
		case UNDEFINED:
			break;
		case OFF_GRID:
			return;
		}

		// Calculate 'real' grid-power (without current ESS charge/discharge)
		var gridPower = meter.getActivePower().getOrError() /* current buy-from/sell-to grid */
				+ ess.getActivePower().getOrError() /* current charge/discharge Ess */;

		int calculatedPower;

		this.log.info("Number of Peakshaving controllers found: ");

		/*
		 * this.logDebug(this.log, "\nCurrent State " + this.state.getName() + "\n" +
		 * "Current SoC " + this.ess.getSoc().get() + "% \n" + "Current ActivePower " +
		 * this.ess.getActivePower().get() + "W \n" +
		 * "Energy charged since last balancing " + this.getChargedEnergy().get() +
		 * "Wh \n");
		 */
		switch (this.state) {
		case UNDEFINED:
			// Stub: check something

			this.changeState(State.STANDBY);

			break;
		case STANDBY:
			// Stub: check something
			if (gridPower >= this.config.peakShavingThresholdPower()) {
				// Peakshaving starts above threshold
				// Remember: In peak shaving mode the battery can be charged
				this.changeState(State.PEAKSHAVING_ACTIVE);
			}
			break;
		case PEAKSHAVING_ACTIVE:
			if (gridPower > this.config.peakShavingThresholdPower()) {
				this.peakshavingStartTime = Instant.now(this.componentManager.getClock()); // Start timer
			}


			if (gridPower >= this.config.peakShavingPower()) {
				/*
				 * Peak-Shaving
				 */
				calculatedPower = gridPower -= this.config.peakShavingPower();

			} else if (gridPower <= this.config.rechargePower()) {
				/*
				 * Recharge
				 */
				calculatedPower = gridPower -= this.config.rechargePower();

			} else {
				/*
				 * Do nothing
				 */
				calculatedPower = 0;

			}
			// Set result
			ess.setActivePowerEqualsWithPid(calculatedPower);
			ess.setReactivePowerEquals(0);
			
			// Only leave if grid power is below threshold an hysteresis has passed
			if (this.peakShavingHysteresisActive() == false) {
				// Peakshaving starts above threshold
				// Remember: In peak shaving mode the battery can be charged
				this.changeState(State.STANDBY);
			}
			break;
		default:
			// ToDo
			break;

		}

	}

	private boolean peakShavingHysteresisActive() {
		long peakShavingDuration = Duration.between(this.peakshavingStartTime, Instant.now(this.componentManager.getClock()))
				.getSeconds();
		if (peakShavingDuration > this.config.hysteresisTime()) {
			return true;
		} else {
			return false;
		}

	}

	/**
	 * Changes the state if hysteresis time passed, to avoid too quick changes.
	 *
	 * @param nextState the target state
	 * @return whether the state was changed
	 */
	private boolean changeState(State nextState) {
		if (this.state == nextState) {

			return false;
		}
		if (Duration.between(//
				this.lastStateChangeTime, //
				Instant.now(this.componentManager.getClock()) //
		).toSeconds() >= HYSTERESIS) {
			this.state = nextState;
			this.lastStateChangeTime = Instant.now(this.componentManager.getClock());

			return true;
		} else {

			return false;
		}
	}
}
