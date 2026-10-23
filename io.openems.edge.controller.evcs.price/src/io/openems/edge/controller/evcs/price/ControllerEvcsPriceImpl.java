package io.openems.edge.controller.evcs.price;

import static io.openems.common.utils.FunctionUtils.doNothing;
import static java.lang.Math.max;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.osgi.service.cm.Configuration;
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

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.ChannelAddress;
import io.openems.common.types.OptionsEnum;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.common.utils.DateUtils;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.controller.evcs.price.jsonrpc.GetChargingPlanRequest;
import io.openems.edge.controller.evcs.price.jsonrpc.GetChargingPlanResponse;
import io.openems.edge.common.meta.Meta;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.energy.api.simulation.periods.PeriodDuration;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.energy.api.EnergySchedulable;
import io.openems.edge.energy.api.handler.EnergyScheduleHandler;
import io.openems.edge.energy.api.handler.EshWithDifferentModes;
import io.openems.edge.energy.api.handler.RescheduleMode;
import io.openems.edge.evcs.api.ChargeMode;
import io.openems.edge.evcs.api.ChargeState;
import io.openems.edge.evcs.api.ManagedEvcs;
import io.openems.edge.evcs.api.Status;
import io.openems.edge.predictor.api.manager.PredictorManager;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Evcs.Price", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@GenerateTargetsFromReferences("evcs")
public class ControllerEvcsPriceImpl extends AbstractOpenemsComponent
		implements Controller, ControllerEvcsPrice, OpenemsComponent, ModbusSlave, EnergySchedulable, ComponentJsonApi {

	private static final int CHARGE_POWER_BUFFER = 200;
	private static final double DEFAULT_UPPER_TARGET_DIFFERENCE_PERCENT = 0.10; // 10%

	private final Logger log = LoggerFactory.getLogger(ControllerEvcsPriceImpl.class);
	private final ChargingLowerThanTargetHandler chargingLowerThanTargetHandler;
	// The clock of the tests; null in production, where the clock of the
	// component manager counts (see clock())
	private final Clock testClock;

	// Last charge power, used for the hysteresis
	private int lastChargePower = 0;
	// The running charging is carried by the price alone (it would go on without
	// any surplus): the price hysteresis applies, the time hysteresis does not
	private boolean priceDrivenCharging = false;

	// Time since when a charge power above zero (below or equal zero) is calculated
	// continuously while not charging (charging); used for the start (stop)
	// hysteresis
	private Instant startWantedSince = null;
	private Instant stopWantedSince = null;

	// The Time-of-Use-Tariff controller blocks the storage discharge right now:
	// it delays the discharge or charges from grid
	private boolean storageDischargeBlocked = false;

	// The charging plan: recalculated every quarter-hour, after plugging in or out
	// and after a configuration change
	private ChargingPlan.Result plan = ChargingPlan.Result.EMPTY;
	// The plan for the display: without a car the plan as if a car were plugged in
	// now, so the UI shows what charging would cost per quarter-hour
	private ChargingPlan.Result displayPlan = ChargingPlan.Result.EMPTY;
	private boolean displayPlanHypothetical = false;
	private ZonedDateTime planQuarter = null;
	private boolean planDirty = true;
	// Quarter-hour in which the missing prediction was logged, once per quarter-hour
	private ZonedDateTime planWarnedQuarter = null;
	// Storage power the plan allows for the car at the storage price in the
	// running quarter-hour; 0 while the storage is blocked
	private int planStoragePower = 0;
	// Evening target and usable capacity the plan was calculated with, for the UI
	private int planTargetWh = 0;
	// Stored energy within the usable windows in [Wh]; null if unknown
	private Double planStoredWh = null;
	// Target charging locks the storage for the car (with hysteresis)
	private boolean storageLocked = false;
	private double planUsableWh = 0;
	// The plan of target charging as stored in the configuration; null without
	private StoredTargetPlan storedTargetPlan = null;
	// A new target plan is requested, with the reason
	private boolean targetReplan = false;
	private String targetReplanReason = null;
	// True while the car charges; a change is reported to the energy optimizer
	private boolean lastCharging = false;
	/** Configuration updates sent while the new configuration is still pending. */
	private final Map<String, Object> configUpdatesSent = new HashMap<>();

	// Forecast of the car for the energy optimizer (Time-of-Use-Tariff plans the
	// storage with it); unused without an optimizer
	private EnergyScheduleHandler energyScheduleHandler = null;
	// null until the wallbox reported a status
	private Boolean lastPluggedIn = null;

	// "Do not charge today": end of the pause; null if not paused
	private Instant pausedUntil = null;
	// Target charging: the next occurrence of the target time; null while off
	private ZonedDateTime targetDeadline = null;
	// Target charging: the plan (its time of making) the optimizer was told about
	// last; 0 if none. A new plan is announced once this instance follows it
	private long announcedTargetPlanAt = 0;
	// Target charging: since when the car takes less than the plan gives it; null
	// while it takes what it gets
	private Instant carPowerLowSince = null;
	// Target charging: the car power the next plan is made with; 0 for the hardware
	private int targetCarPower = 0;
	// Price charging: the car power measured in this session, for the plan; 0 for
	// the hardware. Target charging keeps its own in the stored plan
	private int priceCarPower = 0;
	// How long the car took nothing although at least the minimum power was
	// offered; the time without offer pauses it, power or a new session resets it
	private Duration carIdleTime = Duration.ZERO;
	// The cycle the idle time was counted last; null while nothing is offered
	private Instant carIdleLast = null;
	// The car takes nothing for a long time, e.g. it is full: the plan goes on,
	// but it does not count for the energy optimizer
	private boolean carTakesNothing = false;
	// The next plan is announced to the energy optimizer, with the reason; null
	// if not
	private String planAnnounce = null;
	// The car takes this much less than planned or offered -> plan again
	private static final int CAR_POWER_TOLERANCE = 1000; // W
	private static final Duration CAR_POWER_CONFIRMATION = Duration.ofMinutes(2);
	// Plug state of the EVCS from which a car is plugged in, e.g. Keba: 5 and 7
	private static final int PLUGGED_IN_EV = 5;
	// Below this the car takes nothing
	private static final int CAR_IDLE_POWER = 500; // W
	// A car that takes nothing this long although power is offered counts as
	// taking nothing (e.g. it is full); a pausing car starts again within it
	private static final Duration CAR_IDLE_CONFIRMATION = Duration.ofMinutes(45);
	// the planning period: the quarter-hour of the optimizer
	private static final Duration QUARTER = PeriodDuration.QUARTER.duration;
	/** The plan is shown for this long from the current quarter-hour on, in every mode. */
	private static final Duration DISPLAY_HORIZON = Duration.ofHours(24);

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private Sum sum;

	@Reference
	private ComponentManager componentManager;

	// Optional: without predictions there is no charging plan and the storage is
	// priced like grid power
	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile PredictorManager predictorManager;

	// Optional: the price curve for the charging plan
	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile TimeOfUseTariff timeOfUseTariff;

	// Optional: the grid connection limit
	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Meta meta;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.evcs_id})(enabled=true))")
	private ManagedEvcs evcs;

	private Config config;

	public ControllerEvcsPriceImpl() {
		this((Clock) null);
	}

	protected ControllerEvcsPriceImpl(Clock clock) {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEvcsPrice.ChannelId.values() //
		);
		this.testClock = clock;
		this.chargingLowerThanTargetHandler = new ChargingLowerThanTargetHandler(this::clock);
	}

	/**
	 * The clock: the one of the component manager, like the Time-of-Use-Tariff
	 * and the energy optimizer use it, so that a simulated clock moves all of
	 * them together; the tests pass their own.
	 *
	 * @return the clock
	 */
	private Clock clock() {
		if (this.testClock != null) {
			return this.testClock;
		}
		final var componentManager = this.componentManager;
		return componentManager != null ? componentManager.getClock() : Clock.systemDefaultZone();
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		super.activate(context, config.id(), config.alias(), config.enabled());

		if (config.forceChargeMinPower() < 0) {
			throw new OpenemsException("Force-Charge Min-Power [" + config.forceChargeMinPower() + "] must be >= 0");
		}

		if (config.priceLimit() < 0) {
			throw new OpenemsException("Price limit [" + config.priceLimit() + "] must be >= 0");
		}

		if (config.priceChargePower() < 0) {
			throw new OpenemsException("Full charge power by price [" + config.priceChargePower() + "] must be >= 0");
		}

		if (config.defaultChargeMinPower() < 0) {
			throw new OpenemsException(
					"Default-Charge Min-Power [" + config.defaultChargeMinPower() + "] must be >= 0");
		}

		if (config.storageChargeEfficiency() < 50 || config.storageChargeEfficiency() > 100) {
			throw new OpenemsException("Storage charge efficiency [" + config.storageChargeEfficiency()
					+ "] must be between 50 and 100 %");
		}

		if (config.targetEnergy() < 0 || config.targetTime() < 0 || config.targetTime() >= 24 * 60) {
			throw new OpenemsException("Target energy [" + config.targetEnergy() + "] must be >= 0 and target time ["
					+ config.targetTime() + "] between 0 and 1439 minutes after midnight");
		}

		this.config = config;
		this.configUpdatesSent.clear();
		// Target charging ends at the next occurrence of the target time
		this.targetDeadline = config.targetCharging() //
				? nextDeadline(ZonedDateTime.now(this.clock()), config.targetTime())
				: null;
		this.storedTargetPlan = StoredTargetPlan.decode(config.targetPlan());
		if (this.targetDeadline != null && this.storedTargetPlan != null
				&& this.storedTargetPlan.deadline() <= ZonedDateTime.now(this.clock()).toEpochSecond()) {
			// the target time of the stored plan passed while the controller was off:
			// target charging ends instead of planning for the next day
			this.targetDeadline = Instant.ofEpochSecond(this.storedTargetPlan.deadline())
					.atZone(this.targetDeadline.getZone());
		}

		this.evcs._setChargeMode(config.chargeMode());
		this.evcs._setMaximumPower(null);

		this.energyScheduleHandler = EnergyScheduler.buildEnergyScheduleHandler(this,
				this::energySchedulerConfig);
	}

	/**
	 * The forecast of the car for the energy optimizer: the energy of the charging
	 * plan per quarter-hour, as soon as a car is plugged in. A car that takes
	 * nothing for a long time (e.g. a full one) does not count.
	 *
	 * @return the config; null while the controller does not charge
	 */
	protected EnergyScheduler.Config energySchedulerConfig() {
		if (!this.config.enabled() || !this.config.enabledCharging()
				|| (this.config.chargeMode() != ChargeMode.EXCESS_POWER && !this.config.targetCharging())) {
			return null;
		}
		final var plan = this.plan;
		final var pluggedIn = Boolean.TRUE.equals(this.lastPluggedIn);
		return new EnergyScheduler.Config(//
				!this.carTakesNothing && (this.lastCharging || (pluggedIn && plan.energyWh() > 0)), //
				plan.quarters().isEmpty() ? 0 : plan.quarters().get(0).time().toEpochSecond(), //
				plan.quarters().stream().map(q -> q.power() / 4).toList());
	}

	@Override
	public EnergyScheduleHandler getEnergyScheduleHandler() {
		return this.energyScheduleHandler;
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GetChargingPlanRequest.METHOD,
				call -> GetChargingPlanResponse.from(call.getRequest().getId(), this.plan, this.displayPlan.quarters(),
						this.displayPlanHypothetical, this.planTargetWh, this.planUsableWh));
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	/**
	 * If the EVCS is clustered the method will set the charge power request.
	 * Otherwise it will set directly the charge power limit.
	 */
	@Override
	public void run() throws OpenemsNamedException {
		// Mirror EVCS values and price for the Modbus/TCP API
		this.setEvcsActivePower(this.evcs.getActivePower().get());
		this.setEvcsStatus(this.evcs.getStatus());
		this.setGridBuyPrice(this.sum.getGridBuyPrice().get());

		// A car plugged in or out changes the forecast for the energy optimizer
		final var evcsStatus = this.evcs.getStatus();
		final var plug = this.evcsPlug();
		final var pluggedIn = isPluggedIn(evcsStatus, plug);
		if (plug == null && (evcsStatus == Status.UNDEFINED || evcsStatus == Status.STARTING)) {
			// not known yet, e.g. right after a restart
		} else if (this.lastPluggedIn == null) {
			this.lastPluggedIn = pluggedIn;
		} else if (pluggedIn != this.lastPluggedIn) {
			this.lastPluggedIn = pluggedIn;
			this.planDirty = true;
			// a new session, maybe another car: measure its power again
			this.priceCarPower = 0;
			this.carPowerLowSince = null;
			this.resetCarIdle();
			this.carTakesNothing = false;
			if (pluggedIn) {
				this.chargingLowerThanTargetHandler.reset();
				this.evcs._setMaximumPower(null);
				this.requestTargetReplan("car plugged in");
			} else if ((plug != null || evcsStatus == Status.NOT_READY_FOR_CHARGING) && this.config.targetCharging()) {
				// the car is gone: target charging ends, its plan is void
				this.logInfo(this.log, "Car unplugged: target charging switches off");
				this.configUpdateOnce("targetCharging", false);
			}
			if (this.energyScheduleHandler != null) {
				this.energyScheduleHandler.triggerReschedule("ControllerEvcsPriceImpl::car plugged in/out",
						RescheduleMode.DO_NOT_UPDATE_CURRENT_PERIOD);
			}
		}
		this.updateSessionStart(pluggedIn);

		if (this.evcs.isReadOnly()) {
			this.setEvcsIsReadOnlyChannel(true);
			this.setChargePowerLimit(null);
			return;
		}
		this.setEvcsIsReadOnlyChannel(false);

		final var isClustered = this.evcs.getIsClustered().orElse(false);

		/*
		 * Stop early if charging is disabled or paused for today
		 */
		this.updatePause();
		if (!this.config.enabledCharging() || this.isPaused()) {
			// A new charge process after the pause starts with the start hysteresis
			this.resetHysteresis();
			this.setPriceCharging(false);
			this.setChargePowerLimit(0);
			this.evcs.setChargePowerLimit(0);
			if (isClustered) {
				this.evcs.setChargePowerRequest(0);
				this.resetMinMaxChannels();
			}
			return;
		}

		this.adaptConfigToHardwareLimits();

		this.evcs.setEnergyLimit(this.sessionLimitForEvcs());

		/*
		 * Sets a fixed request of 0 if the Charger is not ready
		 */
		if (isClustered) {

			var status = this.evcs.getStatus();
			switch (status) {
			case ERROR, STARTING, UNDEFINED, ENERGY_LIMIT_REACHED -> {
				this.setPriceCharging(false);
				this.setChargePowerLimit(0);
				this.evcs.setChargePowerRequest(0);
				this.resetMinMaxChannels();
				return;
			}
			case CHARGING_REJECTED, READY_FOR_CHARGING //
				-> this.evcs._setMaximumPower(null);
			case NOT_READY_FOR_CHARGING, CHARGING //
				-> doNothing();
			}
		}

		// Read parameters from Config
		final var chargeMode = this.config.chargeMode();
		final var priority = this.config.priority();
		final var forceChargePower = this.config.forceChargeMinPower() * this.evcs.getPhasesAsInt();
		final var defaultChargeMinPower = this.config.defaultChargeMinPower();

		/*
		 * Calculates the next charging power depending on the charge mode and priority
		 */
		var nextChargePower = chargeMode == null //
				? 0 //
				: switch (chargeMode) {
				case EXCESS_POWER -> //
					switch (priority) {
					case CAR -> calculateChargePowerFromExcessPower(this.sum, this.evcs);
					case STORAGE -> {
						// SoC > 97 % or always, when there is no ESS is available
						if (this.sum.getEssSoc().orElse(100) > 97) {
							yield calculateChargePowerFromExcessPower(this.sum, this.evcs);
						} else {
							yield calculateExcessPowerAfterEss(this.sum, this.evcs);
						}
					}
					};
				case FORCE_CHARGE -> forceChargePower;
				};

		var nextMinPower = chargeMode == null //
				? 0 //
				: switch (chargeMode) {
				case EXCESS_POWER -> defaultChargeMinPower;
				case FORCE_CHARGE -> 0;
				};
		this.evcs._setMinimumPower(nextMinPower);

		final var excessPower = nextChargePower;
		nextChargePower = max(nextChargePower, nextMinPower);

		// Charging under minimum hardware power isn't possible
		var minimumHardwarePower = this.evcs.getMinimumHardwarePower().orElse(0);
		if (nextChargePower < minimumHardwarePower) {
			nextChargePower = 0;
		}

		// Target charging ends at the target time or once the target is reached: the
		// switch goes off by itself
		final var targetMode = this.isTargetMode();
		if (targetMode && !ZonedDateTime.now(this.clock()).isBefore(this.targetDeadline)) {
			this.logInfo(this.log, "Target time reached: target charging switches off");
			this.configUpdateOnce("targetCharging", false);
		} else if (targetMode && this.config.targetStartEnergy() >= 0 && this.targetRemainingWh() <= 0) {
			this.logInfo(this.log, "Target reached: target charging switches off");
			this.configUpdateOnce("targetCharging", false);
		}
		this.updateTargetStartEnergy(targetMode);
		if (!this.config.targetCharging() && this.storedTargetPlan != null) {
			// target charging is off: the plan is void
			this.configUpdateOnce("targetPlan", StoredTargetPlan.NONE);
		}

		// Charge from grid depending on the price; the higher of excess power and
		// price dependent power wins. Target charging follows its plan instead
		var priceCharging = false;
		// the decision would be the same without any surplus: the price alone carries
		// it, so it changes with the quarter-hour and not with a cloud
		var priceDriven = false;
		if (chargeMode == ChargeMode.EXCESS_POWER || targetMode) {
			final var gridBuyPrice = this.sum.getGridBuyPrice().get();
			final var priceChargingEnabled = this.config.enablePriceCharging() && !targetMode;
			// once charging by price the price may exceed the limit by the hysteresis
			final var onOffLimit = this.config.priceLimit()
					+ (this.priceDrivenCharging ? this.config.priceHysteresis() : 0);
			if (priceChargingEnabled) {
				var priceChargePower = calculateChargePowerFromPrice(gridBuyPrice, this.config.priceLimit(),
						this.config.priceLimitFullPower(), minimumHardwarePower, this.config.priceChargePower(),
						this.config.priceHysteresis(), this.priceDrivenCharging);
				priceDriven = priceChargePower > 0;
				if (priceChargePower > nextChargePower) {
					nextChargePower = priceChargePower;
					priceCharging = true;
				}
			}

			// Blended price of the power the car draws now - or of the minimum hardware
			// power before the start - from PV surplus, storage and grid. The storage
			// share is measured while the car charges: what the storage discharges after
			// the house; before the start it is the share the charging plan contains for
			// this quarter-hour. Storage power is valued at the storage price as far as
			// the plan allows it, the rest like grid power. A blocked storage delivers
			// nothing
			final var storagePrice = this.updateStoragePrice(gridBuyPrice);
			this.applyStorageLock(targetMode);
			final var carPower = Math.max(this.evcs.getActivePower().orElse(0), 0);
			// Surplus is what goes to grid, plus what the car draws right now (the
			// measured excess power); the storage share is what the storage discharges
			// after the house. Nothing is estimated here
			final var storagePower = calculateStoragePowerForCar(carPower, this.sum.getEssDischargePower().orElse(0),
					this.sum.getConsumptionActivePower().orElse(0), this.sum.getProductionActivePower().orElse(0),
					this.storageDischargeBlocked, this.planStoragePower, minimumHardwarePower);
			final var pvPower = excessPower;
			this.setStoragePowerForCar(storagePower);
			final var plannedStoragePower = Math.min(storagePower, this.planStoragePower);
			final var blendedPrice = calculateBlendedPrice(gridBuyPrice, pvPower, plannedStoragePower,
					carPower > 0 ? carPower : minimumHardwarePower, this.config.pvPrice(), storagePrice);
			this.setBlendedPrice(blendedPrice == null ? null : blendedPrice * 10);

			// Not enough excess power for the minimum hardware power: take the missing
			// power from the storage as far as the plan allows it and, with price-based
			// charging, from grid
			if (targetMode) {
				// Target charging: the plan chose the cheapest quarter-hours until the
				// target time, the price limits and the surplus do not count
				final var quarter = this.plan.getAt(ZonedDateTime.now(this.clock()));
				nextChargePower = quarter == null ? 0 : quarter.power();
				// everything beyond the surplus is taken because of the plan, not the sun
				priceCharging = quarter != null && quarter.power() > 0 && quarter.fromPv() < quarter.power();
			} else if (nextChargePower == 0) {
				final var storageAvailable = this.config.useStorageSurplus() && plannedStoragePower > 0;
				final var available = pvPower > 0 || storageAvailable;
				final boolean fillUp;
				if (priceChargingEnabled) {
					fillUp = this.config.useStorageSurplus() //
							? available && blendedPrice != null && blendedPrice < onOffLimit
							: isBlendedPriceBelowLimit(gridBuyPrice, pvPower, minimumHardwarePower,
									this.config.pvPrice(), onOffLimit);
					if (this.config.useStorageSurplus() && this.planStoragePower > 0) {
						// storage and grid alone below the limit, e.g. storage the optimizer
						// releases at night: charging by price, the surplus is not needed.
						// The storage share of the plan counts, not the measured one: a house
						// load that takes the storage for a moment is a cloud, not a price
						final var withoutPv = calculateBlendedPrice(gridBuyPrice, 0, this.planStoragePower,
								minimumHardwarePower, this.config.pvPrice(), storagePrice);
						priceDriven = priceDriven || withoutPv != null && withoutPv < onOffLimit;
					}
				} else {
					// Without price-based charging no grid power at all: PV and the planned
					// storage power have to cover the minimum power by themselves
					fillUp = Math.max(pvPower, 0)
							+ (storageAvailable ? plannedStoragePower : 0) >= minimumHardwarePower;
				}
				if (fillUp) {
					nextChargePower = minimumHardwarePower;
					priceCharging = priceChargingEnabled;
				}
			}

			// The grid connection limit is shared with the house and the storage
			nextChargePower = this.applyGridLimit(nextChargePower, carPower, minimumHardwarePower);
		} else {
			this.setStoragePrice(null);
			this.setBlendedPrice(null);
			this.setStoragePowerForCar(null);
			this.setStorageDischargeBlocked(null);
		}
		this.setPriceCharging(priceCharging);

		/**
		 * Calculates the maximum Power of the Car.
		 */
		if (nextChargePower != 0) {

			int activePower = this.evcs.getActivePower().orElse(0);

			/**
			 * Check the difference of the current charge power and the previous charging
			 * target
			 */
			if (this.chargingLowerThanTargetHandler.isLower(this.evcs)) {

				var maximumPower = this.chargingLowerThanTargetHandler.getMaximumChargePower();
				if (maximumPower != null) {
					this.evcs._setMaximumPower(maximumPower + CHARGE_POWER_BUFFER);
					this.logDebug(this.log,
							"Maximum Charge Power of the EV reduced to" + maximumPower + " W plus buffer");
				}
			} else {
				int currMax = this.evcs.getMaximumPower().orElse(0);

				/**
				 * If the power would increases again above the current maximum power, it resets
				 * the maximum Power.
				 */
				if (activePower > currMax * (1 + DEFAULT_UPPER_TARGET_DIFFERENCE_PERCENT)) {
					this.evcs._setMaximumPower(null);
				}
			}
		}

		if (targetMode) {
			// Target charging follows its plan at once: the quarter-hours are chosen
			// ahead, a start hysteresis would only cut into them
			this.resetHysteresis();
		} else if (chargeMode == ChargeMode.EXCESS_POWER) {
			if (pluggedIn) {
				// Apply hysteresis
				nextChargePower = this.applyHysteresis(nextChargePower, priceDriven);
			} else {
				// Without a car there is nothing to protect: no hysteresis, the limit
				// follows the price at once; the charge process after plugging in starts
				// with the start hysteresis
				this.resetHysteresis();
			}
		}
		this.priceDrivenCharging = !targetMode && nextChargePower > 0 && priceDriven;

		// The car becomes a consumer for the energy optimizer with the start of
		// charging and disappears with its end
		final var charging = nextChargePower > 0 && this.evcs.getActivePower().orElse(0) > 0;
		if (charging != this.lastCharging) {
			this.lastCharging = charging;
			if (this.energyScheduleHandler != null) {
				this.energyScheduleHandler.triggerReschedule("ControllerEvcsPriceImpl::charging started/stopped",
						RescheduleMode.DO_NOT_UPDATE_CURRENT_PERIOD);
			}
		}

		if (isClustered) {
			this.evcs.setChargePowerRequest(nextChargePower);
		} else {
			this.evcs.setChargePowerLimit(nextChargePower);
		}
		this.setChargePowerLimit(nextChargePower);
		this.logDebug(this.log, "Next charge power: " + nextChargePower + " W");
	}

	/**
	 * Resetting the minimum and maximum power channels.
	 */
	private void resetMinMaxChannels() {
		this.evcs._setMinimumPower(0);
		this.evcs._setMaximumPower(null);
	}

	/**
	 * Adapt the charge limits to the given hardware limits of the EVCS.
	 */
	private void adaptConfigToHardwareLimits() {

		var maxHardwareOpt = this.evcs.getMaximumHardwarePower().asOptional();
		if (maxHardwareOpt.isPresent()) {
			int maxHW = maxHardwareOpt.get();
			if (maxHW != 0) {
				maxHW = (int) Math.ceil(maxHW / 100.0) * 100;
				if (this.config.defaultChargeMinPower() > maxHW) {
					this.configUpdate("defaultChargeMinPower", maxHW);
				}
			}
		}

	}

	/**
	 * Calculates the next charging power, depending on the current PV production
	 * and house consumption.
	 *
	 * @param sum  the {@link Sum} component
	 * @param evcs Electric Vehicle Charging Station
	 * @return the available excess power for charging
	 * @throws OpenemsNamedException on error
	 */
	private static int calculateChargePowerFromExcessPower(Sum sum, ManagedEvcs evcs) throws OpenemsNamedException {
		int buyFromGrid = sum.getGridActivePower().orElse(0);
		int essDischarge = sum.getEssDischargePower().orElse(0);
		int evcsCharge = evcs.getActivePower().orElse(0);

		return evcsCharge - buyFromGrid - essDischarge;
	}

	/**
	 * Updates the storage and plan channels and returns the replacement cost of
	 * power from the storage in [Cent/kWh].
	 *
	 * <p>
	 * The charging plan (see {@link ChargingPlan}) simulates storage and car
	 * together over the price horizon and keeps the storage at its evening target.
	 * The storage power the plan contains for the running quarter-hour is
	 * refilled by PV before the evening and costs the PV price plus the loss
	 * surcharge; any other storage power costs the grid buy price. Without prices
	 * or predictions there is no plan and the grid buy price is used.
	 *
	 * @param gridBuyPrice the grid buy price in [Currency/MWh]; possibly null
	 * @return the storage price in [Cent/kWh]; null if no grid buy price is known
	 */
	private Double updateStoragePrice(Double gridBuyPrice) {
		// Usable capacity within the SoC windows of the ChargeDischargeLimiters; falls
		// back to the total capacity of _sum if there is no limiter
		var windows = this.readStorageWindows();
		if (windows.isEmpty()) {
			final var capacity = this.sum.getEssCapacity().get();
			final var soc = this.sum.getEssSoc().get();
			if (capacity != null && soc != null) {
				windows = List.of(new StorageWindow(capacity, soc, 0, 100));
			}
		}
		final var energy = calculateStorageEnergy(windows);
		final var netSoc = calculateNetSoc(windows);
		final var energyToTarget = calculateEnergyToTarget(windows, this.config.storageTargetSocNet());
		this.storageDischargeBlocked = this.readStorageDischargeBlocked();

		// The charging plan, once per quarter-hour
		this.checkCarPower();
		this.checkCarIdle();
		this.setCarTakesNothing(this.carTakesNothing);
		this.updateChargingPlan(windows, energy);
		final var plan = this.plan;
		final var quarter = plan.getAt(ZonedDateTime.now(this.clock()));
		final var targetWh = energy == null ? 0. : energy.usableWh() * this.config.storageTargetSocNet() / 100.;
		this.planTargetWh = (int) Math.round(targetWh);
		this.planUsableWh = energy == null ? 0 : energy.usableWh();
		this.planStoredWh = energy == null ? null : energy.storedWh();
		final Integer storageSurplus = plan.storageAtPvEndWh() == null //
				? null
				: (int) Math.round(plan.storageAtPvEndWh() - targetWh);
		final var targetReachable = !plan.quarters().isEmpty() && plan.targetReached();

		// Storage power the plan allows for the car in this quarter-hour: PV refills
		// it before the evening, so it costs the PV price plus the loss surcharge.
		// Everything else from the storage costs the grid buy price
		this.planStoragePower = quarter == null || this.storageDischargeBlocked //
				? 0
				: quarter.storageBudget();
		final Double storagePrice;
		if (gridBuyPrice == null) {
			storagePrice = null;
		} else if (this.config.useStorageSurplus() && this.planStoragePower > 0) {
			storagePrice = this.config.pvPrice() + this.config.storageLossSurcharge();
		} else {
			// 1 Cent/kWh = 10 Currency/MWh
			storagePrice = gridBuyPrice / 10;
		}

		this.setStorageSurplusEnergy(storageSurplus);
		this.setStorageDischargeBlocked(this.storageDischargeBlocked);
		this.setStorageEnergyToTarget(energyToTarget);
		this.setStorageNetSoc(netSoc);
		this.setStorageTargetReachable(targetReachable);
		this.setStoragePrice(storagePrice == null ? null : storagePrice * 10);
		this.setPlanStart(plan.start() == null ? null : plan.start().toEpochSecond());
		this.setPlanEnergy(plan.quarters().isEmpty() ? null : plan.energyWh());
		this.setPlanEnergyFromStorage(plan.quarters().isEmpty() ? null : plan.fromStorageWh());
		this.setPlanPower(quarter == null ? null : quarter.power());
		this.setPlanReason(quarter == null ? null : quarter.reason().name());
		this.setPlanEnergyFromGrid(plan.quarters().isEmpty() ? null : plan.fromGridWh());
		this.setPlanCost(plan.quarters().isEmpty() ? null : plan.costCt());
		final var targetMode = this.isTargetMode();
		final var remaining = targetMode ? this.targetRemainingWh() : 0;
		// reached follows the live session energy, the rest comes from the plan;
		// without a plan the prices or the forecast are missing
		this.setTargetStatus(!targetMode ? ChargingPlan.TargetStatus.NONE.name()
				: remaining == 0 ? ChargingPlan.TargetStatus.REACHED.name()
						: plan.quarters().isEmpty() ? ChargingPlan.TargetStatus.PRICES_MISSING.name()
								: plan.targetStatus().name());
		this.setTargetDeadline(targetMode ? this.targetDeadline.toEpochSecond() : null);
		this.setTargetRemainingEnergy(targetMode ? remaining : null);
		final var stored = this.storedTargetPlan;
		this.setTargetPlanTime(targetMode && stored != null ? stored.plannedAt() : null);
		this.setTargetPlanQuarters(targetMode && stored != null ? stored.quarters() : null);
		return storagePrice;
	}

	/**
	 * Recalculates the charging plan once per quarter-hour, after plugging in or
	 * out and after a configuration change.
	 *
	 * @param windows the storage windows
	 * @param energy  the storage energy; possibly null
	 */
	private void updateChargingPlan(List<StorageWindow> windows, StorageEnergy energy) {
		final var now = ZonedDateTime.now(this.clock());
		final var quarter = DateUtils.roundDownToQuarter(now);
		if (!this.planDirty && quarter.equals(this.planQuarter)) {
			return;
		}
		this.planDirty = false;
		this.planQuarter = quarter;
		this.checkTargetPlanDeviation(quarter);
		try {
			final var plans = this.calculateChargingPlan(quarter, windows, energy);
			this.plan = plans.plan();
			// the same time axis in every mode: 24 hours from the current quarter-hour
			this.displayPlan = plans.display().until(quarter.plus(DISPLAY_HORIZON));
			this.displayPlanHypothetical = plans.hypothetical();
			this.announceTargetPlan();
			this.announcePlan();
		} catch (RuntimeException e) {
			this.logWarn(this.log, "Unable to calculate the charging plan: " + e.getMessage());
			this.plan = ChargingPlan.Result.EMPTY;
			this.displayPlan = ChargingPlan.Result.EMPTY;
			this.displayPlanHypothetical = false;
		}
		if (this.plan.quarters().isEmpty()) {
			// right after a restart prices and predictions arrive a little later than
			// the first cycle: try again next cycle instead of waiting a quarter-hour
			this.planDirty = true;
			this.logDebug(this.log, "No charging plan: prices or predictions missing");
		} else {
			this.logDebug(this.log, "Charging plan: start " + this.plan.start() + ", " + this.plan.energyWh()
					+ " Wh, from storage " + this.plan.fromStorageWh() + " Wh, target reached "
					+ this.plan.targetReached());
		}
	}

	/**
	 * The plans of a quarter-hour.
	 *
	 * @param plan         the plan for the car as it is: for the controller and the
	 *                     energy optimizer
	 * @param display      the plan for the display: the same, or without a car the
	 *                     plan as if a car were plugged in now
	 * @param hypothetical true if the display plan assumes a car
	 */
	protected record Plans(ChargingPlan.Result plan, ChargingPlan.Result display, boolean hypothetical) {
		static final Plans EMPTY = new Plans(ChargingPlan.Result.EMPTY, ChargingPlan.Result.EMPTY, false);
	}

	/**
	 * Calculates the charging plan for the price horizon from the prices of the
	 * Time-of-Use-Tariff, the predictions of production and unmanaged consumption,
	 * the schedule of the Time-of-Use-Tariff controller, the storage and the
	 * car.
	 *
	 * @param quarter the current quarter-hour
	 * @param windows the storage windows
	 * @param energy  the storage energy; possibly null
	 * @return the plans; empty if prices or predictions are missing
	 */
	private Plans calculateChargingPlan(ZonedDateTime quarter, List<StorageWindow> windows, StorageEnergy energy) {
		final var tariff = this.timeOfUseTariff;
		final var predictorManager = this.predictorManager;
		if (tariff == null || predictorManager == null || energy == null) {
			return Plans.EMPTY;
		}
		final var prices = tariff.getPrices();
		if (prices.isEmpty()) {
			return Plans.EMPTY;
		}
		final var production = predictorManager.getPrediction(new ChannelAddress("_sum", "ProductionActivePower"));
		var consumption = predictorManager.getPrediction(new ChannelAddress("_sum", "UnmanagedConsumptionActivePower"));
		if (consumption.isEmpty()) {
			consumption = predictorManager.getPrediction(new ChannelAddress("_sum", "ConsumptionActivePower"));
		}
		if (production.isEmpty() || consumption.isEmpty()) {
			// e.g. the weather forecast fails to load right at the start of the
			// quarter-hour: no plan instead of a plan of unknown quarter-hours; the
			// caller tries again next cycle
			if (!quarter.equals(this.planWarnedQuarter)) {
				this.planWarnedQuarter = quarter;
				this.logWarn(this.log, "No charging plan: prediction of "
						+ (production.isEmpty() ? "production" : "consumption") + " is missing, trying again");
			}
			return Plans.EMPTY;
		}
		final var modes = this.readStorageModes();
		final var gridChargePower = this.readStorageGridChargePower();
		final var inputs = new ArrayList<ChargingPlan.Input>();
		for (var entry : prices.toMapWithAllTimes().tailMap(quarter.toInstant(), true).entrySet()) {
			final var time = entry.getKey();
			final var price = entry.getValue();
			inputs.add(new ChargingPlan.Input(time.atZone(quarter.getZone()), price == null ? null : price / 10,
					production.getAt(time), consumption.getAt(time),
					ChargingPlan.StorageMode.fromState(modes.get(time)), gridChargePower));
		}
		final var limits = this.readStorageLimits();
		final var storage = new ChargingPlan.Storage(energy.storedWh(), energy.usableWh(), limits[0], limits[1],
				this.config.storageChargeEfficiency() / 100.);
		final var meta = this.meta;
		// The car may take less than the hardware allows: the controller lowers the
		// maximum power while the car draws less than it is offered
		final var minPower = this.evcs.getMinimumHardwarePower().orElse(0);
		final var maxHardwarePower = this.evcs.getMaximumHardwarePower().orElse(0);
		final var hardwareMaxPower = Math.max(this.evcs.getMaximumPower()
				.orElse(maxHardwarePower > 0 ? maxHardwarePower : this.config.priceChargePower()), minPower);
		final var targetMode = this.isTargetMode();
		// Target charging is limited by the configured full charge power. It plans
		// with the hardware, not with the lowered maximum: a car that takes less is
		// measured while it charges and the rest is planned again with its power
		// (see checkCarPower)
		final var fullPower = Math.max(maxHardwarePower > 0 ? maxHardwarePower : this.config.priceChargePower(),
				minPower);
		final var maxPower = targetMode
				? Math.max(Math.min(fullPower, this.config.priceChargePower()), minPower)
				: this.priceCarPower > 0 //
						? Math.min(hardwareMaxPower, Math.max(this.priceCarPower, minPower))
						: hardwareMaxPower;
		final var rules = new ChargingPlan.Rules(this.config.enablePriceCharging(), this.config.priceLimit(),
				this.config.priceLimitFullPower(), this.config.priceChargePower(), this.config.priority(), minPower,
				maxPower, this.config.pvPrice(), this.config.pvPrice() + this.config.storageLossSurcharge(),
				this.config.useStorageSurplus(),
				(int) Math.round(energy.usableWh() * this.config.storageTargetSocNet() / 100.), CHARGE_POWER_BUFFER,
				meta == null ? 0 : meta.getGridBuyLimit().hard().power(), this.config.priceHysteresis(),
				this.priceDrivenCharging);
		final var pluggedIn = isPluggedIn(this.evcs.getStatus(), this.evcsPlug()) && !this.isPaused();
		final var sessionLimit = this.config.energySessionLimit();
		final Integer remainingWh = sessionLimit > 0 //
				? Math.max(sessionLimit - this.evcs.getEnergySession().orElse(0), 0)
				: null;
		if (targetMode) {
			return this.calculateTargetPlan(quarter, rules, storage, inputs, pluggedIn);
		}
		final var plan = ChargingPlan.calculate(rules, storage, new ChargingPlan.Car(pluggedIn, remainingWh), inputs);
		if (pluggedIn) {
			return new Plans(plan, plan, false);
		}
		// Without a car: what charging would cost, as if a car were plugged in now
		final var display = ChargingPlan.calculate(rules, storage, new ChargingPlan.Car(true, remainingWh), inputs);
		return new Plans(plan, display, true);
	}

	/**
	 * The plan of target charging. A plan that was made and announced to the
	 * energy optimizer stays: the controller follows it until the target time.
	 * It plans again only without a plan, when the target changed, when the car
	 * was plugged in or when the charged energy deviates from the plan.
	 *
	 * @param quarter   the current quarter-hour
	 * @param rules     the rules
	 * @param storage   the storage now
	 * @param inputs    the forecast per quarter-hour
	 * @param pluggedIn true if a car is plugged in
	 * @return the plans
	 */
	private Plans calculateTargetPlan(ZonedDateTime quarter, ChargingPlan.Rules rules, ChargingPlan.Storage storage,
			List<ChargingPlan.Input> inputs, boolean pluggedIn) {
		final var remaining = this.targetRemainingWh();
		final var deadline = this.targetDeadline;
		final var target = new ChargingPlan.Target(remaining, deadline);
		final var stored = this.storedTargetPlan;
		final var zone = quarter.getZone();
		String reason;
		if (stored == null) {
			reason = "no plan yet";
		} else if (stored.deadline() != deadline.toEpochSecond() || stored.targetEnergy() != this.config.targetEnergy()) {
			reason = "target changed";
		} else if (this.targetReplan) {
			reason = this.targetReplanReason;
		} else {
			return this.followTargetPlan(rules, storage, stored, target, inputs, pluggedIn);
		}
		// Whole quarter-hours only, like the optimizer: the plan starts with the next
		// quarter-hour. The current one goes on as the old plan had it; the energy it
		// still charges comes off the rest
		final var now = ZonedDateTime.now(this.clock());
		final var next = quarter.plus(QUARTER);
		final var currentPower = stored == null ? 0 : stored.powerByTime(zone).getOrDefault(quarter, 0);
		final var currentTakes = this.targetCarPower > 0 ? Math.min(currentPower, this.targetCarPower) : currentPower;
		final var restOfQuarter = Math.max(Duration.between(now, next).toSeconds(), 0);
		final var restWh = (int) Math.round(currentTakes * restOfQuarter / 3600.);
		final var ahead = inputs.stream().filter(in -> !in.time().isBefore(next)).toList();
		if (stored != null && !next.isBefore(deadline)) {
			// no quarter-hour left before the target time: the stored plan goes on
			this.targetReplan = false;
			this.targetReplanReason = null;
			this.targetCarPower = 0;
			this.planDirty = false;
			return this.followTargetPlan(rules, storage, stored, target, inputs, pluggedIn);
		}
		final var planTarget = new ChargingPlan.Target(Math.max(remaining - restWh, 0), deadline);
		final var preferred = stored == null ? Set.<ZonedDateTime>of() : stored.powerByTime(zone).keySet();
		// a measured car power limits the plan, not the quarter-hour that goes on
		final var planRules = this.targetCarPower > 0 ? rules.withMaxPower(this.targetCarPower) : rules;
		final var plan = ChargingPlan.calculateTarget(planRules, storage,
				new ChargingPlan.Car(pluggedIn, planTarget.remainingWh()), planTarget, ahead, preferred);
		final var status = plan.targetStatus();
		if (pluggedIn && (status == ChargingPlan.TargetStatus.PLANNED
				|| status == ChargingPlan.TargetStatus.UNREACHABLE)) {
			// announce and keep: the plan goes to the configuration
			final var made = StoredTargetPlan.of(now, deadline, this.config.targetEnergy(), quarter, currentPower, plan,
					this.targetCarPower);
			this.logInfo(this.log, "Target plan made (" + reason + "): " + made.quarters() + " quarter-hours, "
					+ plan.energyWh() + " Wh from " + next.toLocalTime() + ", " + status
					+ (this.targetCarPower > 0 ? ", car power " + this.targetCarPower + " W" : ""));
			this.targetReplan = false;
			this.targetReplanReason = null;
			this.targetCarPower = 0;
			this.configUpdateOnce("targetPlan", made.encode());
			// this instance follows it from now on, whether or not the configuration
			// change starts a new one
			this.storedTargetPlan = made;
			return this.followTargetPlan(rules, storage, made, target, inputs, pluggedIn);
		}
		if (pluggedIn) {
			return new Plans(plan, plan, false);
		}
		final var display = ChargingPlan.calculateTarget(planRules, storage,
				new ChargingPlan.Car(true, planTarget.remainingWh()), planTarget, ahead, preferred);
		return new Plans(plan, display, true);
	}

	/**
	 * Follows a stored target plan: the car charges the planned power per
	 * quarter-hour and nothing else.
	 *
	 * @param rules     the rules
	 * @param storage   the storage
	 * @param stored    the stored plan
	 * @param target    the target
	 * @param inputs    the inputs from the current quarter-hour on
	 * @param pluggedIn whether a car is plugged in
	 * @return the plans
	 */
	private Plans followTargetPlan(ChargingPlan.Rules rules, ChargingPlan.Storage storage, StoredTargetPlan stored,
			ChargingPlan.Target target, List<ChargingPlan.Input> inputs, boolean pluggedIn) {
		final var fixed = stored.powerByTime(target.deadline().getZone());
		final var remaining = target.remainingWh();
		final var plan = ChargingPlan.applyTarget(rules, storage, new ChargingPlan.Car(pluggedIn, remaining), target,
				inputs, fixed);
		if (pluggedIn) {
			return new Plans(plan, plan, false);
		}
		final var display = ChargingPlan.applyTarget(rules, storage, new ChargingPlan.Car(true, remaining), target,
				inputs, fixed);
		return new Plans(plan, display, true);
	}

	/**
	 * Requests a new plan of target charging.
	 *
	 * @param reason the reason, for the log
	 */
	private void requestTargetReplan(String reason) {
		if (!this.isTargetMode() || this.storedTargetPlan == null) {
			return;
		}
		if (!this.targetReplan) {
			this.logInfo(this.log, "Target plan is made again: " + reason);
		}
		this.targetReplan = true;
		this.targetReplanReason = reason;
		this.targetCarPower = 0;
		this.planDirty = true;
	}

	/**
	 * Target charging: the car may take less than the plan gives it, e.g. with a
	 * single-phase charger. Once it does so by more than 1 kW for two minutes while
	 * charging, the rest is planned again with the power it takes; the plan is
	 * followed with that power until it is made again for another reason, then
	 * the hardware counts again. 0 W is no car limit: the car has not started. A
	 * plan made for this reason in the current quarter-hour is made again only
	 * when the car takes 2 kW less than it.
	 */
	private void checkCarPower() {
		if (!this.isTargetMode()) {
			this.checkPriceCarPower();
			return;
		}
		final var stored = this.storedTargetPlan;
		if (stored == null || this.targetReplan) {
			this.carPowerLowSince = null;
			return;
		}
		final var now = ZonedDateTime.now(this.clock());
		final var quarter = DateUtils.roundDownToQuarter(now);
		if (quarter.plus(QUARTER).toEpochSecond() >= stored.deadline()) {
			// no quarter-hour left before the target time: nothing to plan again
			this.carPowerLowSince = null;
			return;
		}
		final var planned = stored.powerByTime(now.getZone()).getOrDefault(quarter, 0);
		final var actual = this.evcs.getActivePower().orElse(0);
		final var minPower = this.evcs.getMinimumHardwarePower().orElse(0);
		// a plan made with a car power in this quarter-hour keeps the current one at
		// its old power: the car is measured against that car power, and it takes a
		// further 2 kW less to plan again
		final var madeHere = stored.carPower() > 0 && stored.plannedAt() >= quarter.toEpochSecond();
		final var expected = madeHere ? Math.min(planned, stored.carPower()) : planned;
		final var tolerance = (madeHere ? 2 : 1) * CAR_POWER_TOLERANCE;
		if (planned <= minPower || actual < 500 || expected - actual <= tolerance) {
			this.carPowerLowSince = null;
			return;
		}
		if (this.carPowerLowSince == null) {
			this.carPowerLowSince = now.toInstant();
			return;
		}
		if (Duration.between(this.carPowerLowSince, now.toInstant()).compareTo(CAR_POWER_CONFIRMATION) < 0) {
			return;
		}
		this.carPowerLowSince = null;
		this.requestTargetReplan("the car takes " + actual + " W of the planned " + planned + " W");
		this.targetCarPower = Math.max(actual, minPower);
		this.planDirty = true;
	}

	/**
	 * Price charging: the car may take less than it is offered, e.g. with a
	 * single-phase charger. Once it does so by more than 1 kW for two minutes
	 * while charging, the plan is made with the power it takes for the rest of
	 * the session, at once, and the optimizer is told. Takes the car 1 kW more
	 * than measured for two minutes, the hardware counts again.
	 */
	private void checkPriceCarPower() {
		if (this.config.chargeMode() != ChargeMode.EXCESS_POWER || !Boolean.TRUE.equals(this.lastPluggedIn)) {
			this.carPowerLowSince = null;
			return;
		}
		final var now = Instant.now(this.clock());
		final var offered = this.offeredPower();
		final var actual = this.evcs.getActivePower().orElse(0);
		final var minPower = this.evcs.getMinimumHardwarePower().orElse(0);
		final var measured = this.priceCarPower;
		final var more = measured > 0 && actual > measured + CAR_POWER_TOLERANCE;
		final var less = offered > minPower && actual >= CAR_IDLE_POWER && offered - actual > CAR_POWER_TOLERANCE
				&& (measured == 0 || measured - actual > CAR_POWER_TOLERANCE);
		if (!more && !less) {
			this.carPowerLowSince = null;
			return;
		}
		if (this.carPowerLowSince == null) {
			this.carPowerLowSince = now;
			return;
		}
		if (Duration.between(this.carPowerLowSince, now).compareTo(CAR_POWER_CONFIRMATION) < 0) {
			return;
		}
		this.carPowerLowSince = null;
		if (more) {
			this.priceCarPower = 0;
			this.logInfo(this.log, "The car takes " + actual + " W, more than the measured " + measured
					+ " W: the plan is made with the hardware again");
		} else {
			this.priceCarPower = Math.max(actual, minPower);
			this.logInfo(this.log, "The car takes " + actual + " W of the offered " + offered
					+ " W: the plan is made with its power");
		}
		this.planAnnounce = "car power";
		this.planDirty = true;
	}

	/**
	 * The car may take nothing although power is offered: it pauses, or it is
	 * full; the wallbox cannot tell. After 45 minutes without power while at least
	 * the minimum power is offered, in total, the car counts as taking nothing; the
	 * time without offer only pauses the count, as a plan of single cheap
	 * quarter-hours may never offer 45 minutes at a stretch. The plan goes
	 * on and the wallbox offers as before, so that a pausing car starts by itself,
	 * but the plan does not count for the energy optimizer. Power again ends it,
	 * with a new measurement of the car.
	 */
	private void checkCarIdle() {
		final var now = Instant.now(this.clock());
		final var actual = this.evcs.getActivePower().orElse(0);
		if (!Boolean.TRUE.equals(this.lastPluggedIn) || !this.config.enabledCharging() || this.isPaused()) {
			this.resetCarIdle();
			this.carTakesNothing = false;
			return;
		}
		if (actual >= CAR_IDLE_POWER) {
			this.resetCarIdle();
			if (this.carTakesNothing) {
				this.carTakesNothing = false;
				this.priceCarPower = 0;
				this.logInfo(this.log, "The car takes " + actual + " W again: the plan counts for the optimizer again");
				this.planAnnounce = "car takes power again";
				this.planDirty = true;
			}
			return;
		}
		final var offered = this.offeredPower();
		final var minPower = this.evcs.getMinimumHardwarePower().orElse(0);
		if (offered <= 0 || offered < minPower) {
			// nothing offered: no evidence either way, the count pauses
			this.carIdleLast = null;
			return;
		}
		if (this.carIdleLast != null) {
			this.carIdleTime = this.carIdleTime.plus(Duration.between(this.carIdleLast, now));
		}
		this.carIdleLast = now;
		if (!this.carTakesNothing && this.carIdleTime.compareTo(CAR_IDLE_CONFIRMATION) >= 0) {
			this.carTakesNothing = true;
			this.logInfo(this.log, "The car takes nothing for " + CAR_IDLE_CONFIRMATION.toMinutes() + " minutes although "
					+ offered + " W are offered: the plan does not count for the optimizer");
			this.planAnnounce = "car takes nothing";
			this.planDirty = true;
		}
	}

	private void resetCarIdle() {
		this.carIdleTime = Duration.ZERO;
		this.carIdleLast = null;
	}

	/**
	 * The power offered to the car in the last cycle.
	 *
	 * @return the power in [W]; 0 if none
	 */
	private int offeredPower() {
		final Channel<Integer> channel = this.channel(ControllerEvcsPrice.ChannelId.CHARGE_POWER_LIMIT);
		return channel.value().orElse(0);
	}

	/**
	 * Tells the optimizer about a plan that changed between two quarter-hours
	 * (the car power was measured, the car takes nothing or takes power again).
	 */
	private void announcePlan() {
		final var reason = this.planAnnounce;
		if (reason == null) {
			return;
		}
		this.planAnnounce = null;
		if (this.energyScheduleHandler != null) {
			this.energyScheduleHandler.triggerReschedule("ControllerEvcsPriceImpl::" + reason,
					RescheduleMode.DO_NOT_UPDATE_CURRENT_PERIOD);
		}
	}

	/**
	 * Tells the optimizer about a new target plan, so that the Time-of-Use-Tariff
	 * plans the storage with it at once instead of at its next quarter-hour. A
	 * stored plan reaches the optimizer through the configuration: this instance
	 * announces it with its first plan that follows it.
	 */
	private void announceTargetPlan() {
		final var stored = this.storedTargetPlan;
		final var plannedAt = this.isTargetMode() && stored != null ? stored.plannedAt() : 0;
		if (plannedAt == this.announcedTargetPlanAt) {
			return;
		}
		this.announcedTargetPlanAt = plannedAt;
		if (this.energyScheduleHandler != null) {
			this.energyScheduleHandler.triggerReschedule("ControllerEvcsPriceImpl::target plan",
					RescheduleMode.DO_NOT_UPDATE_CURRENT_PERIOD);
		}
	}

	/**
	 * Once per quarter-hour: the stored plan expects a certain energy to be
	 * missing from now on. If the real missing energy deviates by more than one
	 * quarter-hour of minimum power, e.g. because the car took less than planned,
	 * the rest is planned again.
	 *
	 * @param quarter the current quarter-hour
	 */
	private void checkTargetPlanDeviation(ZonedDateTime quarter) {
		final var stored = this.storedTargetPlan;
		if (!this.isTargetMode() || stored == null || this.targetReplan) {
			return;
		}
		if (stored.plannedAt() >= quarter.toEpochSecond()) {
			// made in this quarter-hour: nothing can have deviated yet
			return;
		}
		if (quarter.plus(QUARTER).toEpochSecond() >= stored.deadline()) {
			// no quarter-hour left before the target time: nothing to plan again
			return;
		}
		final var expected = stored.remainingFrom(quarter);
		final var actual = this.targetRemainingWh();
		final var tolerance = Math.max(this.evcs.getMinimumHardwarePower().orElse(4140) / 4, 500);
		if (Math.abs(actual - expected) > tolerance) {
			this.requestTargetReplan("missing " + actual + " Wh, the plan expected " + expected + " Wh");
		} else if (actual > expected && stored.hasFreeQuarterFrom(quarter)) {
			// a little less than planned was charged, e.g. the car took less power or
			// started late: the rest of the plan no longer covers the missing energy
			// and there are free quarter-hours before the target time
			this.requestTargetReplan("the plan covers " + expected + " Wh of the missing " + actual + " Wh");
		}
	}

	/**
	 * The plan of target charging as stored in the configuration: the planned
	 * power per quarter-hour from its start on.
	 *
	 * @param plannedAt    when the plan was made, epoch seconds
	 * @param deadline     the target time, epoch seconds
	 * @param targetEnergy the target energy in [Wh]
	 * @param start        the first quarter-hour, epoch seconds
	 * @param powers       the power per quarter-hour in [W]
	 */
	protected record StoredTargetPlan(long plannedAt, long deadline, int targetEnergy, long start, int[] powers,
			int carPower) {

		/** The configuration value without a plan (an empty string is a default). */
		public static final String NONE = "-";

		/**
		 * A plan made with the hardware power.
		 *
		 * @param plannedAt    when it was made, epoch seconds
		 * @param deadline     the target time, epoch seconds
		 * @param targetEnergy the target energy in [Wh]
		 * @param start        the first quarter-hour, epoch seconds
		 * @param powers       the power per quarter-hour in [W]
		 */
		public StoredTargetPlan(long plannedAt, long deadline, int targetEnergy, long start, int[] powers) {
			this(plannedAt, deadline, targetEnergy, start, powers, 0);
		}

		/**
		 * Builds the stored plan from a plan.
		 *
		 * @param plannedAt    now
		 * @param deadline     the target time
		 * @param targetEnergy the target energy in [Wh]
		 * @param plan         the plan
		 * @return the stored plan
		 */
		public static StoredTargetPlan of(ZonedDateTime plannedAt, ZonedDateTime deadline, int targetEnergy,
				ChargingPlan.Result plan) {
			return of(plannedAt, deadline, targetEnergy, plan, 0);
		}

		/**
		 * Builds the stored plan from a plan made with a measured car power.
		 *
		 * @param plannedAt    now
		 * @param deadline     the target time
		 * @param targetEnergy the target energy in [Wh]
		 * @param plan         the plan
		 * @param carPower     the car power the plan was made with in [W]; 0 for the
		 *                     hardware
		 * @return the stored plan
		 */
		public static StoredTargetPlan of(ZonedDateTime plannedAt, ZonedDateTime deadline, int targetEnergy,
				ChargingPlan.Result plan, int carPower) {
			final var quarters = plan.quarters();
			final var powers = new int[quarters.size()];
			for (var i = 0; i < powers.length; i++) {
				powers[i] = quarters.get(i).power();
			}
			return new StoredTargetPlan(plannedAt.toEpochSecond(), deadline.toEpochSecond(), targetEnergy,
					quarters.isEmpty() ? 0 : quarters.get(0).time().toEpochSecond(), powers, carPower);
		}

		/**
		 * Builds the stored plan from a plan that starts with the next quarter-hour,
		 * with the current quarter-hour in front of it.
		 *
		 * @param plannedAt    now
		 * @param deadline     the target time
		 * @param targetEnergy the target energy in [Wh]
		 * @param quarter      the current quarter-hour
		 * @param currentPower the power of the current quarter-hour in [W]
		 * @param plan         the plan from the next quarter-hour on
		 * @param carPower     the car power the plan was made with in [W]; 0 for the
		 *                     hardware
		 * @return the stored plan
		 */
		public static StoredTargetPlan of(ZonedDateTime plannedAt, ZonedDateTime deadline, int targetEnergy,
				ZonedDateTime quarter, int currentPower, ChargingPlan.Result plan, int carPower) {
			final var quarters = plan.quarters();
			final var powers = new int[quarters.size() + 1];
			powers[0] = currentPower;
			for (var i = 0; i < quarters.size(); i++) {
				powers[i + 1] = quarters.get(i).power();
			}
			return new StoredTargetPlan(plannedAt.toEpochSecond(), deadline.toEpochSecond(), targetEnergy,
					quarter.toEpochSecond(), powers, carPower);
		}

		/**
		 * Parses the configuration value.
		 *
		 * @param value the value
		 * @return the stored plan; null if none
		 */
		public static StoredTargetPlan decode(String value) {
			if (value == null || value.isBlank() || value.equals(NONE)) {
				return null;
			}
			try {
				final var parts = value.split("\\|");
				final var powers = parts.length < 5 || parts[4].isBlank() ? new int[0]
						: java.util.Arrays.stream(parts[4].split(",")).mapToInt(Integer::parseInt).toArray();
				return new StoredTargetPlan(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
						Integer.parseInt(parts[2]), Long.parseLong(parts[3]), powers,
						parts.length > 5 ? Integer.parseInt(parts[5]) : 0);
			} catch (RuntimeException e) {
				return null;
			}
		}

		/**
		 * The configuration value.
		 *
		 * @return the value
		 */
		public String encode() {
			final var b = new StringBuilder();
			b.append(this.plannedAt).append('|').append(this.deadline).append('|').append(this.targetEnergy).append('|')
					.append(this.start).append('|');
			for (var i = 0; i < this.powers.length; i++) {
				if (i > 0) {
					b.append(',');
				}
				b.append(this.powers[i]);
			}
			b.append('|').append(this.carPower);
			return b.toString();
		}

		/**
		 * The planned power per quarter-hour start.
		 *
		 * @param zone the time zone
		 * @return the powers in [W], only quarter-hours with power
		 */
		public Map<ZonedDateTime, Integer> powerByTime(ZoneId zone) {
			final var result = new HashMap<ZonedDateTime, Integer>();
			final var first = Instant.ofEpochSecond(this.start).atZone(zone);
			for (var i = 0; i < this.powers.length; i++) {
				if (this.powers[i] > 0) {
					result.put(first.plus(QUARTER.multipliedBy(i)), this.powers[i]);
				}
			}
			return result;
		}

		/**
		 * The energy the plan still charges from a quarter-hour on.
		 *
		 * @param quarter the quarter-hour
		 * @return the energy in [Wh]
		 */
		public int remainingFrom(ZonedDateTime quarter) {
			var wh = 0.;
			final var from = quarter.toEpochSecond();
			for (var i = 0; i < this.powers.length; i++) {
				if (this.start + QUARTER.toSeconds() * i >= from) {
					// the plan was made within its first quarter-hour: only the rest of it
					// charges
					final var fraction = i == 0
							? Math.max(Math.min(1 - (this.plannedAt - this.start) / (double) QUARTER.toSeconds(), 1), 0)
							: 1;
					wh += this.powers[i] * fraction / 4;
				}
			}
			return (int) Math.round(wh);
		}

		/**
		 * Whether the plan has a quarter-hour without charging before the target time,
		 * from a quarter-hour on.
		 *
		 * @param quarter the quarter-hour
		 * @return true if there is one
		 */
		public boolean hasFreeQuarterFrom(ZonedDateTime quarter) {
			final var from = quarter.toEpochSecond();
			for (var i = 0; i < this.powers.length; i++) {
				final var time = this.start + QUARTER.toSeconds() * i;
				if (time >= from && time < this.deadline && this.powers[i] <= 0) {
					return true;
				}
			}
			// the plan ends before the target time
			return this.start + QUARTER.toSeconds() * this.powers.length < this.deadline;
		}

		/**
		 * The number of quarter-hours with charging.
		 *
		 * @return the number
		 */
		public int quarters() {
			var n = 0;
			for (var p : this.powers) {
				if (p > 0) {
					n++;
				}
			}
			return n;
		}
	}

	/**
	 * Gets the plan for the display (for tests).
	 *
	 * @return the display plan
	 */
	protected ChargingPlan.Result getDisplayPlan() {
		return this.displayPlan;
	}

	/**
	 * Reads the schedule of the configured Time-of-Use-Tariff controller: what it
	 * plans to do with the storage per quarter-hour.
	 *
	 * @return the state names by time; empty if no controller is configured or it
	 *         has no schedule
	 */
	private Map<Instant, String> readStorageModes() {
		final var result = new HashMap<Instant, String>();
		final var id = this.config.timeOfUseTariff_id();
		if (id == null || id.isBlank()) {
			return result;
		}
		try {
			if (this.componentManager.getComponent(id) instanceof EnergySchedulable schedulable
					&& schedulable.getEnergyScheduleHandler() instanceof EshWithDifferentModes<?, ?, ?> esh) {
				for (var entry : esh.getSchedule().entrySet()) {
					result.put(entry.getKey().toInstant(), String.valueOf(entry.getValue().mode()));
				}
			}
		} catch (OpenemsNamedException | RuntimeException e) {
			this.logDebug(this.log, "Unable to read the Time-of-Use-Tariff schedule: " + e.getMessage());
		}
		return result;
	}

	/**
	 * Reads the power the configured Time-of-Use-Tariff controller may charge the
	 * storage from grid with.
	 *
	 * @return the power in [W]; 0 if unknown
	 */
	private int readStorageGridChargePower() {
		final var id = this.config.timeOfUseTariff_id();
		if (id == null || id.isBlank()) {
			return 0;
		}
		try {
			final Object value = this.readChannelValue(this.componentManager.getComponent(id),
					"_PropertyMaxChargePowerFromGrid");
			return value instanceof Number n ? Math.max(n.intValue(), 0)
					: value instanceof String s ? Math.max(Integer.parseInt(s.trim()), 0) : 0;
		} catch (OpenemsNamedException | RuntimeException e) {
			return 0;
		}
	}

	/**
	 * Reads the charge and discharge power of the storages behind the
	 * ChargeDischargeLimiters; falls back to the maximum apparent power of _sum.
	 *
	 * @return charge power and discharge power in [W]
	 */
	private int[] readStorageLimits() {
		var charge = 0;
		var discharge = 0;
		var found = false;
		try {
			for (var component : this.componentManager.getEnabledComponents()) {
				if (!LIMITER_FACTORY_PID.equals(component.serviceFactoryPid())) {
					continue;
				}
				final var essId = this.<String>readChannelValue(component, "_PropertyEssId");
				if (essId == null) {
					continue;
				}
				final var ess = this.componentManager.getComponent(essId);
				final Integer allowedCharge = this.readChannelValue(ess, "AllowedChargePower");
				final Integer allowedDischarge = this.readChannelValue(ess, "AllowedDischargePower");
				final Integer maxApparent = this.readChannelValue(ess, "MaxApparentPower");
				charge += Math.abs(allowedCharge != null ? allowedCharge : maxApparent != null ? maxApparent : 0);
				discharge += Math.abs(allowedDischarge != null ? allowedDischarge
						: maxApparent != null ? maxApparent : 0);
				found = true;
			}
		} catch (OpenemsNamedException | RuntimeException e) {
			this.logDebug(this.log, "Unable to read storage limits: " + e.getMessage());
		}
		if (!found) {
			final var maxApparent = this.sum.getEssMaxApparentPower().orElse(0);
			charge = discharge = maxApparent > 0 ? maxApparent : Integer.MAX_VALUE / 4;
		}
		return new int[] { charge, discharge };
	}

	/**
	 * Limits the charge power to the grid connection limit, which is shared with
	 * the house and the storage: what the others draw from grid right now stays
	 * reserved for them.
	 *
	 * @param nextChargePower      the wanted charge power in [W]
	 * @param carPower             the measured charge power of the car in [W]
	 * @param minimumHardwarePower the minimum hardware power in [W]
	 * @return the charge power in [W]; 0 if the limit leaves less than the minimum
	 */
	private int applyGridLimit(int nextChargePower, int carPower, int minimumHardwarePower) {
		final var meta = this.meta;
		if (nextChargePower <= 0 || meta == null) {
			return nextChargePower;
		}
		final var limit = meta.getGridBuyLimit().hard().power();
		if (limit <= 0) {
			return nextChargePower;
		}
		final var others = Math.max(this.sum.getGridActivePower().orElse(0) - carPower, 0);
		final var room = limit - others;
		if (nextChargePower <= room) {
			return nextChargePower;
		}
		this.logDebug(this.log, "Grid limit " + limit + " W: " + others + " W for the others, " + room
				+ " W left for the car");
		return room >= minimumHardwarePower ? room : 0;
	}

	/**
	 * Reads the state of the configured Time-of-Use-Tariff controller.
	 *
	 * @return true while it blocks the storage discharge; false if unknown or if
	 *         no controller is configured
	 */
	private boolean readStorageDischargeBlocked() {
		final var id = this.config.timeOfUseTariff_id();
		if (id == null || id.isBlank()) {
			return false;
		}
		try {
			final var value = this.componentManager.getComponent(id).channel("StateMachine").value();
			if (!value.isDefined()) {
				return false;
			}
			final OptionsEnum state = value.asEnum();
			return isDischargeBlockingState(state instanceof Enum<?> e ? e.name() : null);
		} catch (OpenemsNamedException | RuntimeException e) {
			this.logDebug(this.log, "Unable to read the Time-of-Use-Tariff state: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Decides from the state of the Time-of-Use-Tariff controller whether the
	 * storage discharge is blocked: while the discharge is delayed or the storage
	 * charges from grid, the storage delivers nothing to the car. The other states
	 * limit the charging only or discharge anyway.
	 *
	 * @param state the name of the {@code StateMachine} value; possibly null
	 * @return true if the discharge is blocked
	 */
	protected static boolean isDischargeBlockingState(String state) {
		if (state == null) {
			return false;
		}
		return switch (state) {
		case "DELAY_DISCHARGE", "CHARGE_GRID", "PEAK_SHAVING" -> true;
		default -> false;
		};
	}

	/**
	 * Calculates the power the storage delivers to the car.
	 *
	 * <p>
	 * While the car charges, the storage discharge is measured: the house is
	 * served first (consumption without the car, minus production), the rest up to
	 * the car power goes to the car. Before the start nothing can be measured: the
	 * storage is assumed to deliver what the charging plan contains for this
	 * quarter-hour, up to the minimum charge power. A blocked storage delivers
	 * nothing.
	 *
	 * @param carPower          the measured charge power of the car in [W]
	 * @param essDischargePower the measured storage discharge in [W]; negative
	 *                          while the storage charges
	 * @param consumptionPower  the total consumption including the car in [W]
	 * @param productionPower   the production in [W]
	 * @param blocked           the storage discharge is blocked
	 * @param planStoragePower  the storage power of the charging plan for this
	 *                          quarter-hour in [W]
	 * @param minPower          the minimum hardware power in [W]
	 * @return the power for the car in [W]
	 */
	protected static int calculateStoragePowerForCar(int carPower, int essDischargePower, int consumptionPower,
			int productionPower, boolean blocked, int planStoragePower, int minPower) {
		if (blocked) {
			return 0;
		}
		if (carPower <= 0) {
			return Math.min(Math.max(planStoragePower, 0), Math.max(minPower, 0));
		}
		final var houseDeficit = Math.max(consumptionPower - carPower - productionPower, 0);
		return Math.min(Math.max(essDischargePower - houseDeficit, 0), carPower);
	}

	/**
	 * Reads the "do not charge today" switch and keeps the pause until midnight.
	 */
	private void updatePause() {
		final var now = ZonedDateTime.now(this.clock());
		final var before = this.isPaused();
		final BooleanWriteChannel channel = this.channel(ControllerEvcsPrice.ChannelId.PAUSE_TODAY);
		final var written = channel.getNextWriteValueAndReset();
		if (written.isPresent()) {
			this.pausedUntil = written.get() //
					? now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()).toInstant()
					: null;
		}
		if (this.pausedUntil != null && !now.toInstant().isBefore(this.pausedUntil)) {
			// midnight has passed: the controller decides on its own again
			this.pausedUntil = null;
		}
		final var paused = this.isPaused();
		channel.setNextValue(paused);
		this.channel(ControllerEvcsPrice.ChannelId.PAUSED_UNTIL)
				.setNextValue(paused ? this.pausedUntil.getEpochSecond() : null);
		if (paused != before && this.energyScheduleHandler != null) {
			this.energyScheduleHandler.triggerReschedule("ControllerEvcsPriceImpl::pause " + paused,
					RescheduleMode.DO_NOT_UPDATE_CURRENT_PERIOD);
		}
	}

	private boolean isPaused() {
		return this.pausedUntil != null;
	}

	/**
	 * Target charging is on: the switch is set and the target time is known.
	 *
	 * @return true while target charging is on
	 */
	private boolean isTargetMode() {
		return this.config.targetCharging() && this.targetDeadline != null;
	}

	/**
	 * Energy that still misses for the target. The target counts from switching
	 * target charging on, i.e. from the session energy stored at that moment.
	 *
	 * @return the energy in [Wh]; 0 if reached
	 */
	private int targetRemainingWh() {
		return Math.max(this.config.targetEnergy() - this.targetChargedWh(), 0);
	}

	/**
	 * Energy charged since target charging was switched on.
	 *
	 * @return the energy in [Wh]
	 */
	private int targetChargedWh() {
		final var start = this.config.targetStartEnergy();
		if (start < 0) {
			return 0; // start not yet stored
		}
		final var session = this.evcs.getEnergySession().orElse(0);
		// a new session (re-plug) resets the wallbox counter: count from zero then
		return session >= start ? session - start : session;
	}

	/**
	 * Stores the session energy when target charging is switched on and clears it
	 * when it is off, so that the target always counts from switching on.
	 *
	 * @param targetMode true while target charging is on
	 */
	private void updateTargetStartEnergy(boolean targetMode) {
		if (targetMode) {
			if (this.config.targetStartEnergy() < 0) {
				final var session = this.evcs.getEnergySession();
				if (session.isDefined()) {
					this.logInfo(this.log, "Target charging starts at session energy " + session.get() + " Wh");
					this.configUpdateOnce("targetStartEnergy", session.get());
				}
			}
		} else if (!this.config.targetCharging() && this.config.targetStartEnergy() >= 0) {
			this.configUpdateOnce("targetStartEnergy", -1);
		}
	}

	/**
	 * The session counts from plugging the car in: the wallbox counter restarts
	 * only with the next charging, so its value at the plug-in is stored in the
	 * configuration. A counter below the stored value means the wallbox started a
	 * new session, then the session counts from zero.
	 *
	 * @param pluggedIn true if a car is plugged in
	 */
	private void updateSessionStart(boolean pluggedIn) {
		final var start = this.config.sessionStartEnergy();
		if (!pluggedIn) {
			if (start >= 0) {
				this.configUpdateOnce("sessionStartEnergy", -1);
			}
			this.setSessionEnergy(null);
			return;
		}
		final var counter = this.evcs.getEnergySession();
		if (!counter.isDefined()) {
			this.setSessionEnergy(null);
			return;
		}
		if (start < 0) {
			this.logInfo(this.log, "Car plugged in at wallbox counter " + counter.get() + " Wh");
			this.configUpdateOnce("sessionStartEnergy", counter.get());
		} else if (counter.get() < start) {
			this.logInfo(this.log, "Wallbox counter restarted: the session counts from zero");
			this.configUpdateOnce("sessionStartEnergy", 0);
		}
		this.setSessionEnergy(this.sessionChargedWh());
	}

	/**
	 * Energy charged since the car was plugged in.
	 *
	 * @return the energy in [Wh]
	 */
	private int sessionChargedWh() {
		final var start = this.config.sessionStartEnergy();
		final var counter = this.evcs.getEnergySession().orElse(0);
		if (start < 0) {
			return 0; // start not yet stored
		}
		return counter >= start ? counter - start : counter;
	}

	/**
	 * The energy limit for the wallbox component. It compares the limit with its
	 * own counter, so the counter value at the plug-in is added: the limit then
	 * counts from the plug-in. Target charging has its own energy target and
	 * overrides the session limit.
	 *
	 * @return the limit in [Wh]; 0 = unlimited
	 */
	private int sessionLimitForEvcs() {
		if (this.isTargetMode()) {
			return 0;
		}
		final var limit = this.config.energySessionLimit();
		if (limit <= 0) {
			return 0;
		}
		final var start = this.config.sessionStartEnergy();
		final var counter = this.evcs.getEnergySession().orElse(0);
		return start > 0 && counter >= start ? start + limit : limit;
	}

	/**
	 * Target charging locks the storage for the car while it is below its evening
	 * target: its discharge is capped to the house load, so the balancing keeps
	 * serving the house and the car charges from grid. The cap is a constraint for
	 * the power solver; the balancing controller runs later and fits into it.
	 *
	 * @param targetMode true while target charging is on
	 * @throws OpenemsNamedException on error
	 */
	private void applyStorageLock(boolean targetMode) throws OpenemsNamedException {
		final var ess = this.readStorageForLock();
		final var stored = this.planStoredWh;
		final var target = this.planTargetWh;
		var lock = false;
		if (targetMode && ess != null && stored != null && target > 0) {
			// hysteresis: locks below the target, releases 2 % of the usable capacity above
			final var release = target + this.planUsableWh * 0.02;
			lock = this.storageLocked ? stored < release : stored < target;
		}
		if (lock != this.storageLocked) {
			this.logInfo(this.log, lock ? "Storage locked for the car: it serves the house only"
					: "Storage released for the car");
		}
		this.storageLocked = lock;
		Integer cap = null;
		if (lock) {
			final var carPower = Math.max(this.evcs.getActivePower().orElse(0), 0);
			cap = Math.max(this.sum.getConsumptionActivePower().orElse(0) - carPower, 0);
			ess.setActivePowerLessOrEquals(cap);
		}
		this.setStorageLocked(lock);
		this.setStorageDischargeLimit(cap);
	}

	/**
	 * The storage that target charging locks for the car, looked up by its
	 * configured id. Looked up at runtime, so the default id works for
	 * configurations that do not contain the property yet.
	 *
	 * @return the storage; null if none is configured or found
	 */
	private ManagedSymmetricEss readStorageForLock() {
		final var id = this.config.ess_id();
		if (id == null || id.isBlank()) {
			return null;
		}
		try {
			if (this.componentManager.getComponent(id) instanceof ManagedSymmetricEss ess) {
				return ess;
			}
		} catch (OpenemsNamedException | RuntimeException e) {
			this.logDebug(this.log, "Storage for the lock [" + id + "] not available: " + e.getMessage());
		}
		return null;
	}

	/**
	 * Like {@link #configUpdate(String, Object)}, but only once until the new
	 * configuration has been activated; the component is reactivated on every
	 * configuration change.
	 *
	 * @param targetProperty Property that should be changed
	 * @param requiredValue  Value that should be set
	 */
	private void configUpdateOnce(String targetProperty, Object requiredValue) {
		if (Objects.equals(this.configUpdatesSent.get(targetProperty), requiredValue)) {
			return;
		}
		this.configUpdatesSent.put(targetProperty, requiredValue);
		this.configUpdate(targetProperty, requiredValue);
	}

	/**
	 * The next occurrence of a time of day.
	 *
	 * @param now          the current time
	 * @param minutesOfDay the time of day as minutes after midnight
	 * @return today at that time if it is still ahead, else tomorrow
	 */
	protected static ZonedDateTime nextDeadline(ZonedDateTime now, int minutesOfDay) {
		var result = now.toLocalDate().atStartOfDay(now.getZone()).plusMinutes(minutesOfDay);
		if (!result.isAfter(now)) {
			result = result.plusDays(1);
		}
		return result;
	}

	/**
	 * The plug state of the EVCS, if it has one: e.g. a Keba reports
	 * NOT_READY_FOR_CHARGING without a car and as well with a car that was plugged
	 * in while no power was offered; only its plug state tells them apart.
	 *
	 * @return the plug state; null if the EVCS has none or it is not known yet
	 */
	private Integer evcsPlug() {
		final Channel<?> channel = this.evcs.channelOrNull("Plug");
		if (channel == null) {
			return null;
		}
		final var value = channel.value().get();
		if (value instanceof Integer plug && plug >= 0) {
			return plug;
		}
		return null;
	}

	/**
	 * A car is plugged in: from the plug state of the EVCS if known, otherwise
	 * from its status.
	 *
	 * @param status the EVCS {@link Status}
	 * @param plug   the plug state of the EVCS, from 5 on a car is plugged in;
	 *               null if unknown
	 * @return true if a car is plugged in
	 */
	protected static boolean isPluggedIn(Status status, Integer plug) {
		if (plug != null) {
			return plug >= PLUGGED_IN_EV;
		}
		return switch (status) {
		case READY_FOR_CHARGING, CHARGING, CHARGING_REJECTED, ENERGY_LIMIT_REACHED -> true;
		case UNDEFINED, STARTING, NOT_READY_FOR_CHARGING, ERROR -> false;
		};
	}

	/**
	 * Capacity and SoC of one storage with the SoC window it is operated in.
	 *
	 * @param capacity the total capacity in [Wh]
	 * @param soc      the current SoC in [%]
	 * @param minSoc   the lower border of the SoC window in [%]
	 * @param maxSoc   the upper border of the SoC window in [%]
	 */
	protected static record StorageWindow(int capacity, int soc, int minSoc, int maxSoc) {
	}

	/**
	 * Energy of all storages within their SoC windows.
	 *
	 * @param storedWh the stored energy above the lower borders in [Wh]
	 * @param usableWh the usable capacity between the borders in [Wh]
	 */
	protected static record StorageEnergy(double storedWh, double usableWh) {
	}

	/**
	 * Calculates the stored and the usable energy of all storages within their SoC
	 * windows.
	 *
	 * @param windows the {@link StorageWindow}s
	 * @return the {@link StorageEnergy}; null if there is no window
	 */
	protected static StorageEnergy calculateStorageEnergy(List<StorageWindow> windows) {
		if (windows == null || windows.isEmpty()) {
			return null;
		}
		var stored = 0.;
		var usable = 0.;
		for (var w : windows) {
			final var soc = Math.min(Math.max(w.soc(), w.minSoc()), w.maxSoc());
			stored += w.capacity() * (soc - w.minSoc()) / 100.;
			usable += w.capacity() * (w.maxSoc() - w.minSoc()) / 100.;
		}
		return new StorageEnergy(stored, usable);
	}

	private static final String LIMITER_FACTORY_PID = "Controller.Ess.ChargeDischargeLimiter";

	/**
	 * Reads the SoC windows of all enabled ChargeDischargeLimiters and the capacity
	 * and SoC of their ESS. Uses only generic channels, so there is no dependency to
	 * the limiter bundle.
	 *
	 * @return the {@link StorageWindow}s; empty if there is no limiter
	 */
	private List<StorageWindow> readStorageWindows() {
		final var result = new ArrayList<StorageWindow>();
		try {
			for (var component : this.componentManager.getEnabledComponents()) {
				if (!LIMITER_FACTORY_PID.equals(component.serviceFactoryPid())) {
					continue;
				}
				final var essId = this.<String>readChannelValue(component, "_PropertyEssId");
				final var minSoc = this.<Integer>readChannelValue(component, "_PropertyMinSoc");
				final var maxSoc = this.<Integer>readChannelValue(component, "_PropertyMaxSoc");
				if (essId == null || minSoc == null || maxSoc == null || maxSoc <= minSoc) {
					continue;
				}
				final var ess = this.componentManager.getComponent(essId);
				final var capacity = this.<Integer>readChannelValue(ess, "Capacity");
				final var soc = this.<Integer>readChannelValue(ess, "Soc");
				if (capacity == null || soc == null) {
					continue;
				}
				result.add(new StorageWindow(capacity, soc, minSoc, maxSoc));
			}
		} catch (OpenemsNamedException | RuntimeException e) {
			this.logDebug(this.log, "Unable to read storage windows: " + e.getMessage());
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private <T> T readChannelValue(OpenemsComponent component, String channelId) {
		try {
			final Channel<?> channel = component.channel(channelId);
			return (T) channel.value().get();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Calculates the energy the storage needs to reach the target within the SoC
	 * windows.
	 *
	 * @param windows      the {@link StorageWindow}s
	 * @param targetSocNet the target in [%] of the usable capacity
	 * @return the missing energy in [Wh], 0 if the target is reached; null if there
	 *         is no window
	 */
	protected static Integer calculateEnergyToTarget(List<StorageWindow> windows, int targetSocNet) {
		final var energy = calculateStorageEnergy(windows);
		if (energy == null) {
			return null;
		}
		return (int) Math.round(Math.max(energy.usableWh() * targetSocNet / 100. - energy.storedWh(), 0));
	}

	/**
	 * Calculates the net state of charge of all storages within their SoC windows.
	 *
	 * @param windows the {@link StorageWindow}s
	 * @return the net SoC in [%]; null if there is no window
	 */
	protected static Integer calculateNetSoc(List<StorageWindow> windows) {
		final var energy = calculateStorageEnergy(windows);
		if (energy == null || energy.usableWh() <= 0) {
			return null;
		}
		return (int) Math.round(energy.storedWh() / energy.usableWh() * 100);
	}

	/**
	 * Calculates the charge power that is allowed by the grid buy price.
	 *
	 * <ul>
	 * <li>price at or above 'priceLimit': 0 W
	 * <li>between the limits: linear from 'minPower' (at 'priceLimit') to
	 * 'fullPower' (at 'priceLimitFullPower')
	 * <li>price at or below 'priceLimitFullPower': 'fullPower'
	 * </ul>
	 *
	 * @param gridBuyPrice        the grid buy price in [Currency/MWh]; possibly
	 *                            null
	 * @param priceLimit          the upper limit in [Cent/kWh]; 0 allows
	 *                            negative prices only
	 * @param priceLimitFullPower the lower limit in [Cent/kWh]
	 * @param minPower            the minimum hardware power in [W]
	 * @param fullPower           the full charge power by price in [W]
	 * @return the charge power in [W]; 0 if charging is not allowed by price
	 */
	protected static int calculateChargePowerFromPrice(Double gridBuyPrice, double priceLimit,
			double priceLimitFullPower, int minPower, int fullPower) {
		if (gridBuyPrice == null || fullPower <= 0 || fullPower < minPower) {
			return 0;
		}
		// 1 Cent/kWh = 10 Currency/MWh
		final var price = gridBuyPrice / 10;
		if (price >= priceLimit) {
			return 0;
		}
		if (price <= priceLimitFullPower) {
			return fullPower;
		}
		final var factor = (priceLimit - price) / (priceLimit - priceLimitFullPower);
		return (int) Math.round(minPower + (fullPower - minPower) * factor);
	}

	/**
	 * Calculates the charge power that is allowed by the grid buy price, with the
	 * price hysteresis: while charging by price, a price above 'priceLimit' but
	 * below 'priceLimit' plus 'hysteresis' still allows the minimum power.
	 *
	 * @param gridBuyPrice        the grid buy price in [Currency/MWh]; possibly
	 *                            null
	 * @param priceLimit          the upper limit in [Cent/kWh]
	 * @param priceLimitFullPower the lower limit in [Cent/kWh]
	 * @param minPower            the minimum hardware power in [W]
	 * @param fullPower           the full charge power by price in [W]
	 * @param hysteresis          the price hysteresis in [Cent/kWh]
	 * @param charging            true while charging by price
	 * @return the charge power in [W]; 0 if charging is not allowed by price
	 */
	protected static int calculateChargePowerFromPrice(Double gridBuyPrice, double priceLimit,
			double priceLimitFullPower, int minPower, int fullPower, double hysteresis, boolean charging) {
		final var power = calculateChargePowerFromPrice(gridBuyPrice, priceLimit, priceLimitFullPower, minPower,
				fullPower);
		if (power > 0 || !charging || hysteresis <= 0 || gridBuyPrice == null || fullPower <= 0
				|| fullPower < minPower) {
			return power;
		}
		return gridBuyPrice / 10 < priceLimit + hysteresis ? minPower : 0;
	}

	/**
	 * Calculates the blended price of charging with the given power, partly from
	 * excess power and partly from grid.
	 *
	 * @param gridBuyPrice the grid buy price in [Currency/MWh]; possibly null
	 * @param excessPower  the available excess power in [W]
	 * @param power        the charge power in [W]
	 * @param pvPrice      the value of PV power in [Cent/kWh]
	 * @return the blended price in [Cent/kWh]; null if it cannot be calculated
	 */
	protected static Double calculateBlendedPrice(Double gridBuyPrice, int excessPower, int power, double pvPrice) {
		if (gridBuyPrice == null || power <= 0) {
			return null;
		}
		// 1 Cent/kWh = 10 Currency/MWh
		final var price = gridBuyPrice / 10;
		final var pvPower = Math.min(Math.max(excessPower, 0), power);
		return (pvPower * pvPrice + (power - pvPower) * price) / power;
	}

	/**
	 * Calculates the blended price of charging with the given power, partly from
	 * excess power, partly from the storage and partly from grid.
	 *
	 * @param gridBuyPrice the grid buy price in [Currency/MWh]; possibly null
	 * @param excessPower  the available excess power in [W]
	 * @param storagePower the power the storage delivers to the car in [W]
	 * @param power        the charge power in [W]
	 * @param pvPrice      the value of PV power in [Cent/kWh]
	 * @param storagePrice the replacement cost of storage power in [Cent/kWh];
	 *                     null prices the storage like grid
	 * @return the blended price in [Cent/kWh]; null if no price is available
	 */
	protected static Double calculateBlendedPrice(Double gridBuyPrice, int excessPower, int storagePower, int power,
			double pvPrice, Double storagePrice) {
		if (gridBuyPrice == null || power <= 0) {
			return null;
		}
		// 1 Cent/kWh = 10 Currency/MWh
		final var price = gridBuyPrice / 10;
		final var pvPower = Math.min(Math.max(excessPower, 0), power);
		final var essPower = Math.min(Math.max(storagePower, 0), power - pvPower);
		final var gridPower = power - pvPower - essPower;
		final var essPrice = storagePrice == null ? price : storagePrice;
		return (pvPower * pvPrice + essPower * essPrice + gridPower * price) / power;
	}

	/**
	 * Checks if charging with the minimum hardware power is allowed by the blended
	 * price of excess power and grid power.
	 *
	 * @param gridBuyPrice the grid buy price in [Currency/MWh]; possibly null
	 * @param excessPower  the available excess power in [W]
	 * @param minPower     the minimum hardware power in [W]
	 * @param pvPrice      the value of PV power in [Cent/kWh]
	 * @param priceLimit   the upper limit in [Cent/kWh]
	 * @return true if the blended price is below the limit
	 */
	protected static boolean isBlendedPriceBelowLimit(Double gridBuyPrice, int excessPower, int minPower,
			double pvPrice, double priceLimit) {
		if (excessPower <= 0) {
			return false;
		}
		final var blendedPrice = calculateBlendedPrice(gridBuyPrice, excessPower, minPower, pvPrice);
		return blendedPrice != null && blendedPrice < priceLimit;
	}

	/**
	 * Calculate result depending on the current evcs power and grid power.
	 *
	 * @param sum  the {@link Sum} component
	 * @param evcs the {@link ManagedEvcs}
	 * @return the excess power
	 */
	private static int calculateExcessPowerAfterEss(Sum sum, ManagedEvcs evcs) {
		int buyFromGrid = sum.getGridActivePower().orElse(0);
		int evcsCharge = evcs.getActivePower().orElse(0);
		// Power the storage is discharging is not excess power: without this the
		// storage feeds the car and the car's own consumption looks like excess
		// (grid stays at zero). Charging of the storage is not subtracted: the
		// storage has priority, so that power is not available for the car.
		int essDischarge = Math.max(sum.getEssDischargePower().orElse(0), 0);

		var result = evcsCharge - buyFromGrid - essDischarge;

		// Add a buffer in Watt to have lower priority than the ess
		result -= 200;

		return result > 0 ? result : 0;
	}

	/**
	 * Forgets the running charge process: the next one starts with the start
	 * hysteresis.
	 */
	private void resetHysteresis() {
		this.lastChargePower = 0;
		this.priceDrivenCharging = false;
		this.startWantedSince = null;
		this.stopWantedSince = null;
		this.setHysteresisRemaining(null);
		this.channel(ControllerEvcsPrice.ChannelId.AWAITING_HYSTERESIS).setNextValue(false);
	}

	/**
	 * Applies the hysteresis to avoid too quick changes between a charge process
	 * and a pause. Only a change the surplus causes is confirmed over time: a
	 * start the price carries alone happens at once, and charging the price
	 * carried alone stops at once when the price no longer does (and the surplus
	 * does not take over); the price changes with the quarter-hour, waiting adds
	 * nothing but charging into the expensive quarter-hour.
	 *
	 * @param nextChargePower the next charge power limit
	 * @param priceDriven     the price alone, with the storage share of the
	 *                        plan, carries charging now
	 * @return next charge power or the last power if hysteresis is active
	 */
	private int applyHysteresis(int nextChargePower, boolean priceDriven) {
		int targetChargePower = nextChargePower;
		boolean showWarning = false;
		var now = Instant.now(this.clock());

		// Wait at least the EVCS-specific response time, required to increase and
		// decrease the charging power
		if (awaitLastChanges(this.evcs.getChargeState().asEnum())) {
			// Still waiting for increasing, decreasing the power or undefined
			return this.lastChargePower;
		}
		// TODO: Show info, test and check if bellow logic still needed or need to be
		// different (Change only when we would change for xSeconds)

		// Start charging: the charge power has to stay above zero for the start
		// hysteresis; a short burst of sun or a single wrong measurement must not
		// start the car (every start is a contactor cycle)
		Integer remaining = null;
		if (this.lastChargePower <= 0 && nextChargePower > 0 && !priceDriven) {
			if (this.startWantedSince == null) {
				this.startWantedSince = now;
			}
			var hysteresis = Duration.ofSeconds(this.config.startConfirmationTime());
			final var until = this.startWantedSince.plus(hysteresis);
			if (until.isAfter(now)) {
				showWarning = true;
				targetChargePower = this.lastChargePower;
				remaining = (int) Duration.between(now, until).getSeconds();
			}
		} else {
			this.startWantedSince = null;
		}

		// Pause charging by limiting to zero: the charge power has to stay at zero
		// for the stop hysteresis; a short cloud must not end a charge process that
		// would then need the start hysteresis again. Charging by price ends at once
		// when the price or the plan no longer carries it, not when a house load
		// takes the storage share for a moment
		if (this.lastChargePower > 0 && nextChargePower <= 0 && (!this.priceDrivenCharging || priceDriven)) {
			if (this.stopWantedSince == null) {
				this.stopWantedSince = now;
			}
			var hysteresis = Duration.ofSeconds(this.config.stopConfirmationTime());
			final var until = this.stopWantedSince.plus(hysteresis);
			if (until.isAfter(now)) {
				showWarning = true;
				targetChargePower = this.lastChargePower;
				remaining = (int) Duration.between(now, until).getSeconds();
			} else {
				targetChargePower = 0;
			}
		} else {
			this.stopWantedSince = null;
		}

		// Apply results
		this.lastChargePower = targetChargePower;
		this.channel(ControllerEvcsPrice.ChannelId.AWAITING_HYSTERESIS).setNextValue(showWarning);
		this.setHysteresisRemaining(remaining);

		return targetChargePower;
	}

	/**
	 * Check if the evcs should wait for last changes.
	 * 
	 * <p>
	 * Since the charging stations and each car have their own response time until
	 * they charge at the set power, the controller waits until everything runs
	 * normally.
	 * 
	 * @param chargeState current evcs charge state
	 * @return The cvcs should await or not
	 */
	private static boolean awaitLastChanges(ChargeState chargeState) {
		if (chargeState.equals(ChargeState.INCREASING) || chargeState.equals(ChargeState.DECREASING)) {
			// Still waiting for increasing, decreasing the power
			return true;
		}
		return false;
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				Controller.getModbusSlaveNatureTable(accessMode), //
				ControllerEvcsPrice.getModbusSlaveNatureTable(accessMode));
	}

	/**
	 * Updating the configuration property to given value.
	 *
	 * @param targetProperty Property that should be changed
	 * @param requiredValue  Value that should be set
	 */
	public void configUpdate(String targetProperty, Object requiredValue) {

		Configuration c;
		try {
			var pid = this.servicePid();
			if (pid.isEmpty()) {
				this.logInfo(this.log, "PID of " + this.id() + " is Empty");
				return;
			}
			c = this.cm.getConfiguration(pid, "?");
			var properties = c.getProperties();
			properties.put(targetProperty, requiredValue);
			c.update(properties);
		} catch (IOException | SecurityException e) {
			this.logError(this.log, "ERROR: " + e.getMessage());
		}
	}

	@Override
	public void logInfo(Logger log, String message) {
		super.logInfo(log, message);
	}

	@Override
	protected void logWarn(Logger log, String message) {
		super.logWarn(log, message);
	}

	@Override
	protected void logDebug(Logger log, String message) {
		if (this.config.debugMode()) {
			this.logInfo(this.log, message);
		}
	}
}
