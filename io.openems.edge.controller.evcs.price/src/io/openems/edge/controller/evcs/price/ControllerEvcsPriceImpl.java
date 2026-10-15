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
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
	private final Clock clock;

	// Last charge power, used for the hysteresis
	private int lastChargePower = 0;

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
	private ZonedDateTime planQuarter = null;
	private boolean planDirty = true;
	// Storage power the plan allows for the car at the storage price in the
	// running quarter-hour; 0 while the storage is blocked
	private int planStoragePower = 0;
	// Evening target and usable capacity the plan was calculated with, for the UI
	private int planTargetWh = 0;
	private double planUsableWh = 0;
	// True while the car charges; a change is reported to the energy optimizer
	private boolean lastCharging = false;

	// Forecast of the car for the energy optimizer (Time-of-Use-Tariff plans the
	// storage with it); unused without an optimizer
	private EnergyScheduleHandler energyScheduleHandler = null;
	private boolean lastPluggedIn = false;

	// "Do not charge today": end of the pause; null if not paused
	private Instant pausedUntil = null;

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
		this(Clock.systemDefaultZone());
	}

	protected ControllerEvcsPriceImpl(Clock clock) {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEvcsPrice.ChannelId.values() //
		);
		this.clock = clock;
		this.chargingLowerThanTargetHandler = new ChargingLowerThanTargetHandler(clock);
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

		this.config = config;

		this.evcs._setChargeMode(config.chargeMode());
		this.evcs._setMaximumPower(null);

		this.energyScheduleHandler = EnergyScheduler.buildEnergyScheduleHandler(this, () -> {
			if (!this.config.enabled() || !this.config.enabledCharging() || this.config.chargeMode() != ChargeMode.EXCESS_POWER) {
				return null;
			}
			// The car is a consumer for the optimizer only while it charges: then with
			// the energy of the charging plan per quarter-hour
			final var plan = this.plan;
			return new EnergyScheduler.Config(//
					this.lastCharging, //
					plan.quarters().isEmpty() ? 0 : plan.quarters().get(0).time().toEpochSecond(), //
					plan.quarters().stream().map(q -> q.power() / 4).toList());
		});
	}

	@Override
	public EnergyScheduleHandler getEnergyScheduleHandler() {
		return this.energyScheduleHandler;
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GetChargingPlanRequest.METHOD, call -> GetChargingPlanResponse
				.from(call.getRequest().getId(), this.plan, this.planTargetWh, this.planUsableWh));
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
		final var pluggedIn = isPluggedIn(this.evcs.getStatus());
		if (pluggedIn != this.lastPluggedIn) {
			this.lastPluggedIn = pluggedIn;
			this.planDirty = true;
			if (this.energyScheduleHandler != null) {
				this.energyScheduleHandler.triggerReschedule("ControllerEvcsPriceImpl::car plugged in/out",
						RescheduleMode.DO_NOT_UPDATE_CURRENT_PERIOD);
			}
		}

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
			this.lastChargePower = 0;
			this.startWantedSince = null;
			this.stopWantedSince = null;
			this.setHysteresisRemaining(null);
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

		this.evcs.setEnergyLimit(this.config.energySessionLimit());

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

		// Charge from grid depending on the price; the higher of excess power and
		// price dependent power wins
		var priceCharging = false;
		if (chargeMode == ChargeMode.EXCESS_POWER) {
			final var gridBuyPrice = this.sum.getGridBuyPrice().get();
			final var priceChargingEnabled = this.config.enablePriceCharging();
			if (priceChargingEnabled) {
				var priceChargePower = calculateChargePowerFromPrice(gridBuyPrice, this.config.priceLimit(),
						this.config.priceLimitFullPower(), minimumHardwarePower, this.config.priceChargePower());
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
			if (nextChargePower == 0) {
				final var storageAvailable = this.config.useStorageSurplus() && plannedStoragePower > 0;
				final var available = pvPower > 0 || storageAvailable;
				final boolean fillUp;
				if (priceChargingEnabled) {
					fillUp = this.config.useStorageSurplus() //
							? available && blendedPrice != null && blendedPrice < this.config.priceLimit()
							: isBlendedPriceBelowLimit(gridBuyPrice, pvPower, minimumHardwarePower,
									this.config.pvPrice(), this.config.priceLimit());
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

		if (chargeMode == ChargeMode.EXCESS_POWER) {
			// Apply hysteresis
			nextChargePower = this.applyHysteresis(nextChargePower);
		}

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
		this.updateChargingPlan(windows, energy);
		final var plan = this.plan;
		final var quarter = plan.getAt(ZonedDateTime.now(this.clock));
		final var targetWh = energy == null ? 0. : energy.usableWh() * this.config.storageTargetSocNet() / 100.;
		this.planTargetWh = (int) Math.round(targetWh);
		this.planUsableWh = energy == null ? 0 : energy.usableWh();
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
		final var now = ZonedDateTime.now(this.clock);
		final var quarter = now.truncatedTo(ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15);
		if (!this.planDirty && quarter.equals(this.planQuarter)) {
			return;
		}
		this.planDirty = false;
		this.planQuarter = quarter;
		try {
			this.plan = this.calculateChargingPlan(quarter, windows, energy);
		} catch (RuntimeException e) {
			this.logWarn(this.log, "Unable to calculate the charging plan: " + e.getMessage());
			this.plan = ChargingPlan.Result.EMPTY;
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
	 * Calculates the charging plan for the price horizon from the prices of the
	 * Time-of-Use-Tariff, the predictions of production and unmanaged consumption,
	 * the schedule of the Time-of-Use-Tariff controller, the storage and the
	 * car.
	 *
	 * @param quarter the current quarter-hour
	 * @param windows the storage windows
	 * @param energy  the storage energy; possibly null
	 * @return the plan; empty if prices or predictions are missing
	 */
	private ChargingPlan.Result calculateChargingPlan(ZonedDateTime quarter, List<StorageWindow> windows,
			StorageEnergy energy) {
		final var tariff = this.timeOfUseTariff;
		final var predictorManager = this.predictorManager;
		if (tariff == null || predictorManager == null || energy == null) {
			return ChargingPlan.Result.EMPTY;
		}
		final var prices = tariff.getPrices();
		if (prices.isEmpty()) {
			return ChargingPlan.Result.EMPTY;
		}
		final var production = predictorManager.getPrediction(new ChannelAddress("_sum", "ProductionActivePower"));
		var consumption = predictorManager.getPrediction(new ChannelAddress("_sum", "UnmanagedConsumptionActivePower"));
		if (consumption.isEmpty()) {
			consumption = predictorManager.getPrediction(new ChannelAddress("_sum", "ConsumptionActivePower"));
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
		final var maxPower = Math.max(this.evcs.getMaximumPower()
				.orElse(maxHardwarePower > 0 ? maxHardwarePower : this.config.priceChargePower()), minPower);
		final var rules = new ChargingPlan.Rules(this.config.enablePriceCharging(), this.config.priceLimit(),
				this.config.priceLimitFullPower(), this.config.priceChargePower(), this.config.priority(), minPower,
				maxPower, this.config.pvPrice(), this.config.pvPrice() + this.config.storageLossSurcharge(),
				this.config.useStorageSurplus(),
				(int) Math.round(energy.usableWh() * this.config.storageTargetSocNet() / 100.), CHARGE_POWER_BUFFER,
				meta == null ? 0 : meta.getGridBuyLimit().hard().power());
		final var pluggedIn = isPluggedIn(this.evcs.getStatus()) && !this.isPaused();
		final var sessionLimit = this.config.energySessionLimit();
		final Integer remainingWh = sessionLimit > 0 //
				? Math.max(sessionLimit - this.evcs.getEnergySession().orElse(0), 0)
				: null;
		return ChargingPlan.calculate(rules, storage, new ChargingPlan.Car(pluggedIn, remainingWh), inputs);
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
		final var now = ZonedDateTime.now(this.clock);
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
	 * A car is plugged in.
	 *
	 * @param status the EVCS {@link Status}
	 * @return true if a car is plugged in
	 */
	protected static boolean isPluggedIn(Status status) {
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
	 * Applies the hysteresis to avoid too quick changes between a charge process
	 * and a pause.
	 *
	 * @param nextChargePower the next charge power limit
	 * @return next charge power or the last power if hysteresis is active
	 */
	private int applyHysteresis(int nextChargePower) {
		int targetChargePower = nextChargePower;
		boolean showWarning = false;
		var now = Instant.now(this.clock);

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
		if (this.lastChargePower <= 0 && nextChargePower > 0) {
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
		// would then need the start hysteresis again
		if (this.lastChargePower > 0 && nextChargePower <= 0) {
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
			var target = properties.get(targetProperty);
			var existingTarget = target.toString();
			if (!existingTarget.isEmpty()) {
				properties.put(targetProperty, requiredValue);
				c.update(properties);
			}
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
