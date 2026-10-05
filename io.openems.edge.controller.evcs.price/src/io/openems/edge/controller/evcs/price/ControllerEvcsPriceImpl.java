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

	// Time of last charge power change, used for the hysteresis
	private Instant lastInitialCharge = Instant.MIN;

	// Time of last charge pause, used for the hysteresis
	private Instant lastChargePause = Instant.MIN;

	// Last charge power, used for the hysteresis
	private int lastChargePower = 0;

	// Time since when a charge power above zero is calculated continuously while
	// not charging; used for the start confirmation
	private Instant startWantedSince = null;

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
			var priceChargePower = calculateChargePowerFromPrice(gridBuyPrice, this.config.priceLimit(),
					this.config.priceLimitFullPower(), minimumHardwarePower, this.config.priceChargePower());
			if (priceChargePower > nextChargePower) {
				nextChargePower = priceChargePower;
				priceCharging = true;
			}

			// Blended price of filling up to the minimum hardware power from PV surplus,
			// storage (at its replacement cost) and grid
			final var storagePrice = this.updateStoragePrice(gridBuyPrice);
			final var storagePower = this.sum.getEssMaxDischargePower().orElse(0);
			final var blendedPrice = calculateBlendedPrice(gridBuyPrice, excessPower, storagePower,
					minimumHardwarePower, this.config.pvPrice(), storagePrice);
			this.setBlendedPrice(blendedPrice == null ? null : blendedPrice * 10);

			// Not enough excess power for the minimum hardware power: take the missing
			// power from storage/grid if the blended price is below the limit
			if (nextChargePower == 0) {
				final var belowLimit = this.config.useStorageSurplus() //
						? excessPower > 0 && this.config.priceLimit() > 0 && blendedPrice != null
								&& blendedPrice < this.config.priceLimit()
						: isBlendedPriceBelowLimit(gridBuyPrice, excessPower, minimumHardwarePower,
								this.config.pvPrice(), this.config.priceLimit());
				if (belowLimit) {
					nextChargePower = minimumHardwarePower;
					priceCharging = true;
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
	 * If the expected PV surplus of the day exceeds the free storage capacity plus
	 * the configured reserve, the storage will be full anyway and power taken from
	 * it now is replaced by PV surplus: it costs the PV price plus the loss
	 * surcharge. Otherwise the energy is missing later and has to be bought from
	 * grid: it costs the grid buy price. Without predictions the grid buy price is
	 * used.
	 *
	 * @param gridBuyPrice the grid buy price in [Currency/MWh]; possibly null
	 * @return the storage price in [Cent/kWh]; null if no grid buy price is known
	 */
	private Double updateStoragePrice(Double gridBuyPrice) {
		// Conservative assumption: the car keeps drawing what it draws now until the
		// end of PV production; at least the minimum power while it is plugged in
		final var carPower = calculateAssumedCarPower(this.evcs.getActivePower().orElse(0), this.evcs.getStatus(),
				this.evcs.getMinimumHardwarePower().orElse(0));

		Integer expectedSurplus = null;
		final var predictorManager = this.predictorManager;
		if (predictorManager != null) {
			try {
				final var production = predictorManager
						.getPrediction(new ChannelAddress("_sum", "ProductionActivePower"));
				final var consumption = predictorManager
						.getPrediction(new ChannelAddress("_sum", "ConsumptionActivePower"));
				expectedSurplus = calculateExpectedSurplusEnergy(production, consumption, carPower,
						ZonedDateTime.now(this.clock));
			} catch (RuntimeException e) {
				this.logDebug(this.log, "Unable to read predictions: " + e.getMessage());
			}
		}

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
		final var netSoc = calculateNetSoc(windows);
		final var energyToTarget = calculateEnergyToTarget(windows, this.config.storageTargetSocNet());

		final var targetReachable = isStorageTargetReachable(expectedSurplus, energyToTarget);
		final var storagePrice = calculateStoragePrice(gridBuyPrice, this.config.pvPrice(),
				this.config.storageLossSurcharge(), targetReachable);

		this.setExpectedSurplusEnergy(expectedSurplus);
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
		return (int) Math.round(Math.max(usable * targetSocNet / 100. - stored, 0));
	}

	/**
	 * Calculates the net state of charge of all storages within their SoC windows.
	 *
	 * @param windows the {@link StorageWindow}s
	 * @return the net SoC in [%]; null if there is no window
	 */
	protected static Integer calculateNetSoc(List<StorageWindow> windows) {
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
		return usable <= 0 ? null : (int) Math.round(stored / usable * 100);
	}

	/**
	 * Calculates the expected PV surplus energy for the storage from now until the
	 * end of PV production today: the sum of the positive differences of production
	 * minus consumption minus the assumed car power per quarter-hour. "Evening" is
	 * the last quarter-hour of today with predicted production.
	 *
	 * @param production  the production prediction in [W] per quarter-hour
	 * @param consumption the consumption prediction in [W] per quarter-hour
	 * @param carPower    the power the car is assumed to draw in [W]
	 * @param now         the current time
	 * @return the surplus energy in [Wh]; null if there is no prediction
	 */
	protected static Integer calculateExpectedSurplusEnergy(Prediction production, Prediction consumption,
			int carPower, ZonedDateTime now) {
		if (production == null || consumption == null || production.isEmpty() || consumption.isEmpty()) {
			return null;
		}
		final var endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()).toInstant();
		// include the current quarter-hour
		final var start = now.toInstant().minusSeconds(15 * 60);
		var surplusWh = 0.;
		var count = 0;
		for (var entry : production.toMap().entrySet()) {
			final var time = entry.getKey();
			if (time.isBefore(start) || !time.isBefore(endOfDay)) {
				continue;
			}
			final var prod = entry.getValue();
			final var cons = consumption.getAt(time);
			if (prod == null || cons == null) {
				continue;
			}
			count++;
			if (prod <= 0) {
				// no production: nothing for the storage (and after sunset this stays so)
				continue;
			}
			surplusWh += Math.max(prod - cons - carPower, 0) / 4.;
		}
		return count == 0 ? null : (int) Math.round(surplusWh);
	}

	/**
	 * Decides if the storage is expected to reach its evening target by PV surplus.
	 *
	 * @param expectedSurplusWh the expected PV surplus energy for the storage in
	 *                          [Wh]; possibly null
	 * @param energyToTargetWh  the energy missing to the target in [Wh]; possibly
	 *                          null
	 * @return true if the surplus covers the missing energy; false if unknown
	 */
	protected static boolean isStorageTargetReachable(Integer expectedSurplusWh, Integer energyToTargetWh) {
		return expectedSurplusWh != null && energyToTargetWh != null && expectedSurplusWh >= energyToTargetWh;
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
	 * @param priceLimit          the upper limit in [Cent/kWh]; '0' deactivates
	 *                            charging by price
	 * @param priceLimitFullPower the lower limit in [Cent/kWh]
	 * @param minPower            the minimum hardware power in [W]
	 * @param fullPower           the full charge power by price in [W]
	 * @return the charge power in [W]; 0 if charging is not allowed by price
	 */
	protected static int calculateChargePowerFromPrice(Double gridBuyPrice, double priceLimit,
			double priceLimitFullPower, int minPower, int fullPower) {
		if (gridBuyPrice == null || priceLimit <= 0 || fullPower <= 0 || fullPower < minPower) {
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
	 * @param priceLimit   the upper limit in [Cent/kWh]; '0' deactivates charging
	 *                     by price
	 * @return true if the blended price is below the limit
	 */
	protected static boolean isBlendedPriceBelowLimit(Double gridBuyPrice, int excessPower, int minPower,
			double pvPrice, double priceLimit) {
		if (priceLimit <= 0 || excessPower <= 0) {
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

		// New charge power limit
		if (this.lastChargePower <= 0 && nextChargePower > 0) {
			var hysteresis = Duration.ofSeconds(this.config.excessChargePauseHysteresis());
			if (this.startWantedSince == null) {
				this.startWantedSince = now;
			}
			var confirmation = Duration.ofSeconds(this.config.startConfirmationTime());
			if (!this.lastChargePause.plus(hysteresis).isBefore(now)) {
				// Wait for hysteresis
				showWarning = true;
				targetChargePower = this.lastChargePower;
			} else if (this.startWantedSince.plus(confirmation).isAfter(now)) {
				// Wait for the start confirmation: the charge power has to stay above
				// zero for a while, a single wrong measurement must not start charging
				showWarning = true;
				targetChargePower = this.lastChargePower;
			} else {
				// Start charing
				this.lastInitialCharge = now;
			}
		} else {
			this.startWantedSince = null;
		}

		// Pause charging by limiting to zero
		if (this.lastChargePower > 0 && nextChargePower <= 0) {
			var hysteresis = Duration.ofSeconds(this.config.excessChargeHystersis());
			if (this.lastInitialCharge.plus(hysteresis).isBefore(now)) {

				// Pause charing
				targetChargePower = 0;
				this.lastChargePause = now;
			} else {
				// Wait for hysteresis
				showWarning = true;
				targetChargePower = this.lastChargePower;
			}
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
