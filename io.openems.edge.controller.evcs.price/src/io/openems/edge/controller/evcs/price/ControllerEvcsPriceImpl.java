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
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

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
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.evcs.api.ChargeMode;
import io.openems.edge.evcs.api.ChargeState;
import io.openems.edge.evcs.api.ManagedEvcs;
import io.openems.edge.evcs.api.Status;
import io.openems.edge.predictor.api.manager.PredictorManager;
import io.openems.edge.predictor.api.prediction.Prediction;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Evcs.Price", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@GenerateTargetsFromReferences("evcs")
public class ControllerEvcsPriceImpl extends AbstractOpenemsComponent
		implements Controller, ControllerEvcsPrice, OpenemsComponent, ModbusSlave {

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

	// True while the storage is already above its evening target: that surplus
	// may go to the car even without PV surplus
	private boolean storageAboveTarget = false;

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private Sum sum;

	@Reference
	private ComponentManager componentManager;

	// Optional: without predictions the storage is priced like grid power
	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile PredictorManager predictorManager;

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

		this.config = config;

		this.evcs._setChargeMode(config.chargeMode());
		this.evcs._setMaximumPower(null);
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

		if (this.evcs.isReadOnly()) {
			this.setEvcsIsReadOnlyChannel(true);
			this.setChargePowerLimit(null);
			return;
		}
		this.setEvcsIsReadOnlyChannel(false);

		final var isClustered = this.evcs.getIsClustered().orElse(false);

		/*
		 * Stop early if charging is disabled
		 */
		if (!this.config.enabledCharging()) {
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

			// Blended price of filling up to the minimum hardware power from PV surplus,
			// storage (at its replacement cost) and grid
			final var storagePrice = this.updateStoragePrice(gridBuyPrice);
			final var storagePower = this.sum.getEssMaxDischargePower().orElse(0);
			final var blendedPrice = calculateBlendedPrice(gridBuyPrice, excessPower, storagePower,
					minimumHardwarePower, this.config.pvPrice(), storagePrice);
			this.setBlendedPrice(blendedPrice == null ? null : blendedPrice * 10);

			// Not enough excess power for the minimum hardware power: take the missing
			// power from the storage reserve and, with price-based charging, from grid
			if (nextChargePower == 0) {
				// With storage pricing the missing power may also come from the storage
				// alone, as long as the storage is above its evening target right now
				final var storageAvailable = this.config.useStorageSurplus() && this.storageAboveTarget
						&& storagePower > 0;
				final var available = excessPower > 0 || storageAvailable;
				final boolean fillUp;
				if (priceChargingEnabled) {
					fillUp = this.config.useStorageSurplus() //
							? available && blendedPrice != null && blendedPrice < this.config.priceLimit()
							: isBlendedPriceBelowLimit(gridBuyPrice, excessPower, minimumHardwarePower,
									this.config.pvPrice(), this.config.priceLimit());
				} else {
					// Without price-based charging no grid power at all: excess power and the
					// storage reserve have to cover the minimum power by themselves
					fillUp = Math.max(excessPower, 0) + (storageAvailable ? storagePower : 0) >= minimumHardwarePower;
				}
				if (fillUp) {
					nextChargePower = minimumHardwarePower;
					priceCharging = priceChargingEnabled;
				}
			}
		} else {
			this.setStoragePrice(null);
			this.setBlendedPrice(null);
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
	 * Updates the storage price channels and returns the replacement cost of
	 * power from the storage in [Cent/kWh].
	 *
	 * <p>
	 * The energy of the storage is projected until the end of PV production
	 * today: the current energy plus production minus consumption, minus what the
	 * car takes from the surplus, quarter-hour by quarter-hour, within the usable
	 * capacity. If the projection
	 * ends above the evening target, the storage will have that surplus anyway and
	 * power taken from it now is replaced by PV: it costs the PV price plus the
	 * loss surcharge, and the car may take the surplus even without PV. Otherwise
	 * the energy is missing in the evening and has to be bought from grid: it
	 * costs the grid buy price. Without predictions the grid buy price is used.
	 *
	 * @param gridBuyPrice the grid buy price in [Currency/MWh]; possibly null
	 * @return the storage price in [Cent/kWh]; null if no grid buy price is known
	 */
	private Double updateStoragePrice(Double gridBuyPrice) {
		// With priority CAR the car takes PV surplus before the storage: conservative
		// assumption that it keeps drawing what it draws now until the end of PV
		// production, at least the minimum power while it is plugged in. With
		// priority STORAGE the storage is filled first and the car only gets what the
		// storage cannot take, so the car does not reduce the projection.
		final var carPower = this.config.priority() == Priority.STORAGE //
				? 0
				: calculateAssumedCarPower(this.evcs.getActivePower().orElse(0), this.evcs.getStatus(),
						this.evcs.getMinimumHardwarePower().orElse(0));

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

		Integer storageSurplus = null;
		final var predictorManager = this.predictorManager;
		if (predictorManager != null && energy != null) {
			try {
				final var production = predictorManager
						.getPrediction(new ChannelAddress("_sum", "ProductionActivePower"));
				final var consumption = predictorManager
						.getPrediction(new ChannelAddress("_sum", "ConsumptionActivePower"));
				final var expected = calculateExpectedStorageEnergy(production, consumption, carPower,
						energy.storedWh(), energy.usableWh(), ZonedDateTime.now(this.clock));
				if (expected != null) {
					storageSurplus = (int) Math
							.round(expected - energy.usableWh() * this.config.storageTargetSocNet() / 100.);
				}
			} catch (RuntimeException e) {
				this.logDebug(this.log, "Unable to read predictions: " + e.getMessage());
			}
		}

		final var targetReachable = isStorageTargetReachable(storageSurplus);
		// The car may take energy from the storage only while a surplus is left; every
		// kWh it takes lowers the SoC and with it the surplus, so this ends by itself
		this.storageAboveTarget = storageSurplus != null && storageSurplus > 0;
		final var storagePrice = calculateStoragePrice(gridBuyPrice, this.config.pvPrice(),
				this.config.storageLossSurcharge(), targetReachable);

		this.setStorageSurplusEnergy(storageSurplus);
		this.setStorageEnergyToTarget(energyToTarget);
		this.setStorageNetSoc(netSoc);
		this.setStorageTargetReachable(targetReachable);
		this.setStoragePrice(storagePrice == null ? null : storagePrice * 10);
		return storagePrice;
	}

	/**
	 * The power the car is assumed to draw until the end of PV production.
	 *
	 * @param activePower  the current charge power in [W]
	 * @param status       the EVCS {@link Status}
	 * @param minimumPower the minimum hardware power in [W]
	 * @return the assumed power in [W]: the current power, at least the minimum
	 *         power while a car is plugged in, 0 otherwise
	 */
	protected static int calculateAssumedCarPower(int activePower, Status status, int minimumPower) {
		final var pluggedIn = switch (status) {
		case READY_FOR_CHARGING, CHARGING, CHARGING_REJECTED, ENERGY_LIMIT_REACHED -> true;
		case UNDEFINED, STARTING, NOT_READY_FOR_CHARGING, ERROR -> false;
		};
		return pluggedIn ? Math.max(activePower, minimumPower) : Math.max(activePower, 0);
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
	 * Projects the energy of the storage from now until the end of PV production:
	 * quarter-hour by quarter-hour, production minus consumption is added,
	 * negative balances included, and the energy is kept within the usable
	 * capacity. The car takes from a positive balance up to its assumed power; it
	 * charges from PV surplus only, so it never makes the balance negative.
	 *
	 * <p>
	 * "Evening" is the end of the running block of quarter-hours with predicted
	 * production. Without production in the current quarter-hour - after the end
	 * of PV production until sunrise - the evening has passed and the storage
	 * keeps what it has; the PV of the next day does not count, otherwise the car
	 * could empty the storage at night and the house would buy from grid until
	 * the next day's PV refills it.
	 *
	 * @param production  the production prediction in [W] per quarter-hour
	 * @param consumption the consumption prediction in [W] per quarter-hour
	 * @param carPower    the power the car is assumed to draw from PV surplus in [W]
	 * @param storedWh    the energy stored now within the SoC windows in [Wh]
	 * @param usableWh    the usable capacity within the SoC windows in [Wh]
	 * @param now         the current time
	 * @return the expected energy at the end of PV production in [Wh]; null if
	 *         there is no prediction
	 */
	protected static Integer calculateExpectedStorageEnergy(Prediction production, Prediction consumption,
			int carPower, double storedWh, double usableWh, ZonedDateTime now) {
		if (production == null || consumption == null || production.isEmpty() || consumption.isEmpty()) {
			return null;
		}
		// include the current quarter-hour
		final var start = now.toInstant().minusSeconds(15 * 60);
		final var quarters = new TreeMap<>(production.toMap());
		var energy = storedWh;
		for (var entry : quarters.tailMap(start, true).entrySet()) {
			final var prod = entry.getValue();
			if (prod == null || prod <= 0) {
				// end of the running PV block; at night already the current quarter-hour
				break;
			}
			final var cons = consumption.getAt(entry.getKey());
			if (cons == null) {
				continue;
			}
			final var balance = prod - cons;
			energy += (balance > 0 ? Math.max(balance - carPower, 0) : balance) / 4.;
			// the storage can neither hold more than its usable capacity nor less than nothing
			energy = Math.min(Math.max(energy, 0), usableWh);
		}
		return (int) Math.round(energy);
	}

	/**
	 * Decides if the storage is expected to reach its evening target.
	 *
	 * @param storageSurplusWh the expected energy above the target at the end of PV
	 *                         production in [Wh], negative if missing; possibly
	 *                         null
	 * @return true if nothing is missing; false if unknown
	 */
	protected static boolean isStorageTargetReachable(Integer storageSurplusWh) {
		return storageSurplusWh != null && storageSurplusWh >= 0;
	}

	/**
	 * Calculates the replacement cost of power from the storage.
	 *
	 * @param gridBuyPrice  the grid buy price in [Currency/MWh]; possibly null
	 * @param pvPrice       the value of PV power in [Cent/kWh]
	 * @param lossSurcharge the loss surcharge in [Cent/kWh]
	 * @param targetReachable true if the storage will reach its evening target by
	 *                        PV surplus anyway
	 * @return the storage price in [Cent/kWh]; null if no grid buy price is known
	 */
	protected static Double calculateStoragePrice(Double gridBuyPrice, double pvPrice, double lossSurcharge,
			boolean targetReachable) {
		if (gridBuyPrice == null) {
			return null;
		}
		// 1 Cent/kWh = 10 Currency/MWh
		return targetReachable ? pvPrice + lossSurcharge : gridBuyPrice / 10;
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
	 * @param storagePower the power the storage is able to discharge in [W]
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
		if (this.lastChargePower <= 0 && nextChargePower > 0) {
			if (this.startWantedSince == null) {
				this.startWantedSince = now;
			}
			var hysteresis = Duration.ofSeconds(this.config.startConfirmationTime());
			if (this.startWantedSince.plus(hysteresis).isAfter(now)) {
				showWarning = true;
				targetChargePower = this.lastChargePower;
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
			if (this.stopWantedSince.plus(hysteresis).isAfter(now)) {
				showWarning = true;
				targetChargePower = this.lastChargePower;
			} else {
				targetChargePower = 0;
			}
		} else {
			this.stopWantedSince = null;
		}

		// Apply results
		this.lastChargePower = targetChargePower;
		this.channel(ControllerEvcsPrice.ChannelId.AWAITING_HYSTERESIS).setNextValue(showWarning);

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
