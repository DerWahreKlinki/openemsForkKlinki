package io.openems.edge.ess.energyprice;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;

import io.openems.common.timedata.Resolution;
import io.openems.common.types.ChannelAddress;
import io.openems.common.types.OpenemsType;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.MetaEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * Keeps the price of the energy stored in one ESS, see {@link Inventory}.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Ess.EnergyPrice", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				EventConstants.EVENT_TOPIC + "=" + TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
		})
public class EssEnergyPriceImpl extends AbstractOpenemsComponent
		implements EssEnergyPrice, OpenemsComponent, EventHandler, TimedataProvider {

	// A longer gap between two cycles is a clock jump or a pause: the power of the
	// current cycle does not stand for it
	private static final Duration MAX_CYCLE = Duration.ofMinutes(15);
	private static final int HISTORY_MINUTES = 15;
	private static final double CENT_PER_KWH_TO_CURRENCY_PER_MWH = 10;

	private final Logger log = LoggerFactory.getLogger(EssEnergyPriceImpl.class);
	// The Timedata queries run off the cycle thread
	private final Executor executor;

	@Reference
	private ComponentManager componentManager;

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private Sum sum;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timedata = null;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	private SymmetricEss ess;

	private Config config;
	private Inventory inventory = new Inventory();
	private double efficiency = 0.9;
	private Double lastPrice = null;
	private Instant lastCycle = null;
	private double missingPriceWh = 0;
	private CalculateEnergyFromPower chargeGridEnergy;
	private CalculateEnergyFromPower chargePvEnergy;

	// The content is rebuilt from the history once after the activation; until
	// then the inventory is not led
	private boolean historyDone = false;
	private boolean historyRunning = false;
	private volatile Inventory historyResult = null;
	// The efficiency is measured once a day
	private LocalDate efficiencyDay = null;
	private boolean efficiencyRunning = false;
	private volatile Double efficiencyResult = null;

	public EssEnergyPriceImpl() {
		this(ForkJoinPool.commonPool());
	}

	protected EssEnergyPriceImpl(Executor executor) {
		super(//
				OpenemsComponent.ChannelId.values(), //
				EssEnergyPrice.ChannelId.values() //
		);
		this.executor = executor;
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.applyConfig(config);
		this.historyDone = config.historyDays() <= 0;
		this.chargeGridEnergy = new CalculateEnergyFromPower(this, EssEnergyPrice.ChannelId.CHARGE_GRID_ENERGY,
				this.componentManager.getClock());
		this.chargePvEnergy = new CalculateEnergyFromPower(this, EssEnergyPrice.ChannelId.CHARGE_PV_ENERGY,
				this.componentManager.getClock());
		OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "ess", config.ess_id());
	}

	@Modified
	private void modified(ComponentContext context, Config config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.applyConfig(config);
		OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "ess", config.ess_id());
	}

	private void applyConfig(Config config) {
		this.config = config;
		this.efficiency = clampEfficiency(config.efficiency() / 100.);
		// measure again with the new period
		this.efficiencyDay = null;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled() || !TOPIC_CYCLE_AFTER_PROCESS_IMAGE.equals(event.getTopic())) {
			return;
		}
		final var now = Instant.now(this.componentManager.getClock());
		this.startQueries(now);
		this.applyQueryResults();

		final var batteryPower = batteryPower(this.ess);
		final var split = this.splitCharge(batteryPower);
		this._setChargeGridPower(split.grid());
		this._setChargePvPower(split.pv());
		this.chargeGridEnergy.update(split.grid());
		this.chargePvEnergy.update(split.pv());

		final var price = this.readPrice();
		final var feedInTariff = this.feedInTariff();
		final var hours = this.cycleHours(now);
		if (this.historyDone && hours > 0 && batteryPower != null) {
			if (split.charge() > 0) {
				final var chargedWh = split.charge() * hours;
				this.inventory.charge(chargedWh, split.grid() * hours, price, feedInTariff, this.efficiency);
				if (price == null) {
					this.missingPriceWh += chargedWh;
				}
			} else if (batteryPower > 0) {
				this.inventory.discharge(batteryPower * hours, this.efficiency);
			}
		}
		if (this.historyDone) {
			final var content = this.measuredContent();
			if (content != null) {
				this.inventory.calibrate(content);
			}
		}

		this._setPrice(this.historyDone ? this.inventory.price(this.efficiency) : null);
		this._setStoredEnergy(this.historyDone ? (int) Math.round(this.inventory.energyWh()) : null);
		this._setStoredGridEnergy(this.historyDone ? (int) Math.round(this.inventory.gridWh()) : null);
		this._setStoredPvEnergy(this.historyDone ? (int) Math.round(this.inventory.pvWh()) : null);
		this._setUnpricedEnergy(this.historyDone ? (int) Math.round(this.inventory.unpricedWh()) : null);
		this._setStoredCost(this.historyDone ? this.inventory.cost() : null);
		this._setMissingPriceEnergy(Math.round(this.missingPriceWh));
		this._setEfficiency(this.efficiency * 100);
		this._setPriceMissing(this.lastPrice == null);

		if (this.config.debugMode()) {
			this.logInfo(this.log, this.debugLog());
		}
	}

	/**
	 * The battery power of an ESS: the DC battery power of a hybrid ESS (its AC
	 * power contains the DC-coupled PV), otherwise the AC power. Negative =
	 * charging.
	 *
	 * @param ess the ESS
	 * @return the power [W]; null if unknown
	 */
	private static Integer batteryPower(SymmetricEss ess) {
		if (ess instanceof HybridEss hybrid) {
			return hybrid.getDcDischargePower().get();
		}
		return ess.getActivePower().get();
	}

	private static ChannelAddress batteryPowerChannel(SymmetricEss ess) {
		return new ChannelAddress(ess.id(), ess instanceof HybridEss //
				? HybridEss.ChannelId.DC_DISCHARGE_POWER.id()
				: SymmetricEss.ChannelId.ACTIVE_POWER.id());
	}

	private record Split(int charge, int grid, int pv) {
		private static final Split NONE = new Split(0, 0, 0);
	}

	/**
	 * Splits the charge power of the ESS into the part bought from the grid and
	 * the part from PV: the grid purchase of the moment counts as charging first,
	 * shared between all ESS that charge in proportion to their charge power.
	 *
	 * @param batteryPower the battery power of the ESS; negative = charging
	 * @return the {@link Split}
	 */
	private Split splitCharge(Integer batteryPower) {
		if (batteryPower == null || batteryPower >= 0) {
			return Split.NONE;
		}
		final var charge = -batteryPower;
		var totalCharge = 0L;
		final Set<String> seen = new HashSet<>();
		for (var other : this.componentManager.getEnabledComponentsOfType(SymmetricEss.class)) {
			if (other instanceof MetaEss || !seen.add(other.id())) {
				continue;
			}
			final var otherPower = batteryPower(other);
			if (otherPower != null && otherPower < 0) {
				totalCharge += -otherPower;
			}
		}
		return split(charge, Math.max(0, this.sum.getGridActivePower().orElse(0)), totalCharge);
	}

	private static Split split(int charge, int gridBuy, long totalCharge) {
		final var total = Math.max(totalCharge, charge);
		final var grid = (int) Math.min(charge, (long) gridBuy * charge / total);
		return new Split(charge, grid, charge - grid);
	}

	/**
	 * Reads the grid buy price. While the channel has no value the last known
	 * price stands in.
	 *
	 * @return the price [Currency/MWh]; null while no price is known
	 */
	private Double readPrice() {
		final var price = this.readChannel(this.config.priceChannel());
		if (price != null) {
			this.lastPrice = price;
		}
		return this.lastPrice;
	}

	/**
	 * The configured feed-in tariff.
	 *
	 * @return the tariff [Currency/MWh]
	 */
	private double feedInTariff() {
		return this.config.feedInTariff() * CENT_PER_KWH_TO_CURRENCY_PER_MWH;
	}

	private Double readChannel(String address) {
		try {
			final Channel<?> channel = this.componentManager.getChannel(ChannelAddress.fromString(address));
			final var value = channel.value().get();
			if (value == null) {
				return null;
			}
			return TypeUtils.getAsType(OpenemsType.DOUBLE, value);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * The time since the last cycle, for which the power of this cycle counts.
	 *
	 * @param now the current time
	 * @return the hours; 0 on the first cycle and after a clock jump
	 */
	private double cycleHours(Instant now) {
		final var last = this.lastCycle;
		this.lastCycle = now;
		if (last == null) {
			return 0;
		}
		final var duration = Duration.between(last, now);
		if (duration.isNegative() || duration.compareTo(MAX_CYCLE) > 0) {
			return 0;
		}
		return duration.toMillis() / 3_600_000.;
	}

	private Double measuredContent() {
		final var soc = this.ess.getSoc().get();
		final var capacity = this.ess.getCapacity().get();
		if (soc == null || capacity == null) {
			return null;
		}
		return soc / 100. * capacity;
	}

	private static double clampEfficiency(double efficiency) {
		return Math.max(0.5, Math.min(1.0, efficiency));
	}

	/*
	 * Timedata: the history on start and the efficiency once a day
	 */

	private void startQueries(Instant now) {
		final var timedata = this.timedata;
		final var capacity = this.ess.getCapacity().get();
		// Both queries turn SoC into energy and need the capacity for that; a Modbus device reports it only
		// after its first successful read. The history needs Timedata as well, the efficiency measurement
		// only if configured. No timeout: the queries wait for their data and the state channels show it.
		final var timedataNeeded = !this.historyDone || this.config.efficiencyDays() > 0;
		this._setCapacityMissing(capacity == null);
		this._setTimedataMissing(timedataNeeded && timedata == null);
		if (capacity == null) {
			return;
		}
		if (!this.historyDone && !this.historyRunning && timedata != null) {
			this.historyRunning = true;
			final var days = this.config.historyDays();
			final var feedInTariff = this.feedInTariff();
			CompletableFuture.runAsync(() -> {
				try {
					this.historyResult = this.replayHistory(timedata, now, days, feedInTariff, capacity);
				} catch (Exception e) {
					this.logWarn(this.log, "Rebuilding the content from the history failed: " + e.getMessage());
					this.historyResult = new Inventory();
				}
			}, this.executor);
		}
		if (this.config.efficiencyDays() > 0 && timedata != null && !this.efficiencyRunning) {
			final var today = LocalDate.ofInstant(now, this.componentManager.getClock().getZone());
			if (!today.equals(this.efficiencyDay)) {
				this.efficiencyDay = today;
				this.efficiencyRunning = true;
				final var days = this.config.efficiencyDays();
				CompletableFuture.runAsync(() -> {
					try {
						this.efficiencyResult = this.measureEfficiency(timedata, now, days, capacity);
					} catch (Exception e) {
						this.logWarn(this.log, "Measuring the efficiency failed: " + e.getMessage());
					} finally {
						this.efficiencyRunning = false;
					}
				}, this.executor);
			}
		}
	}

	private void applyQueryResults() {
		final var history = this.historyResult;
		if (history != null) {
			this.historyResult = null;
			this.historyRunning = false;
			this.historyDone = true;
			this.inventory = history;
			final var price = history.storedPrice();
			this.logInfo(this.log, String.format("Content from history: %.0f Wh, priced %.0f Wh, price %s",
					history.energyWh(), history.pricedWh(), price == null ? "unknown" : String.format("%.1f", price)));
		}
		final var efficiency = this.efficiencyResult;
		if (efficiency != null) {
			this.efficiencyResult = null;
			this.efficiency = clampEfficiency(efficiency);
		}
	}

	/**
	 * Rebuilds the content from the history: the battery power of all ESS, the
	 * grid power, the price and the SoC in quarter-hours, replayed like cycles.
	 *
	 * @param timedata     the {@link Timedata}
	 * @param now          the current time
	 * @param days         the days of history
	 * @param feedInTariff the feed-in tariff [Currency/MWh]
	 * @param capacity     the capacity [Wh]; null if unknown
	 * @return the {@link Inventory}
	 * @throws Exception on error
	 */
	private Inventory replayHistory(Timedata timedata, Instant now, int days, double feedInTariff, Integer capacity)
			throws Exception {
		final var zone = this.componentManager.getClock().getZone();
		final var to = ZonedDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.HOURS);
		final var from = to.minusDays(days);
		final var own = batteryPowerChannel(this.ess);
		final Map<ChannelAddress, Boolean> essChannels = new HashMap<>();
		for (var ess : this.componentManager.getEnabledComponentsOfType(SymmetricEss.class)) {
			if (!(ess instanceof MetaEss)) {
				essChannels.put(batteryPowerChannel(ess), ess.id().equals(this.ess.id()));
			}
		}
		essChannels.put(own, true);
		final var grid = new ChannelAddress(Sum.SINGLETON_COMPONENT_ID, Sum.ChannelId.GRID_ACTIVE_POWER.id());
		final var price = ChannelAddress.fromString(this.config.priceChannel());
		final var soc = new ChannelAddress(this.ess.id(), SymmetricEss.ChannelId.SOC.id());
		final Set<ChannelAddress> channels = new HashSet<>(essChannels.keySet());
		channels.add(grid);
		channels.add(price);
		channels.add(soc);

		final var data = timedata.queryHistoricData(null, from, to, channels,
				new Resolution(HISTORY_MINUTES, ChronoUnit.MINUTES));
		final var inventory = new Inventory();
		final var hours = HISTORY_MINUTES / 60.;
		Double lastPrice = null;
		for (var row : data.values()) {
			final var batteryPower = asDouble(row.get(own));
			final var rowPrice = asDouble(row.get(price));
			if (rowPrice != null) {
				lastPrice = rowPrice;
			}
			if (batteryPower != null) {
				if (batteryPower < 0) {
					final var charge = (int) Math.round(-batteryPower);
					var totalCharge = 0L;
					for (var entry : essChannels.entrySet()) {
						final var power = asDouble(row.get(entry.getKey()));
						if (power != null && power < 0) {
							totalCharge += Math.round(-power);
						}
					}
					final var gridBuy = asDouble(row.get(grid));
					final var split = split(charge, (int) Math.round(Math.max(0, gridBuy == null ? 0 : gridBuy)),
							totalCharge);
					inventory.charge(charge * hours, split.grid() * hours, lastPrice, feedInTariff, this.efficiency);
				} else if (batteryPower > 0) {
					inventory.discharge(batteryPower * hours, this.efficiency);
				}
			}
			final var rowSoc = asDouble(row.get(soc));
			if (rowSoc != null && capacity != null) {
				inventory.calibrate(rowSoc / 100. * capacity);
			}
		}
		return inventory;
	}

	/**
	 * Measures the round-trip efficiency from the energy counters of the ESS over
	 * the period, corrected by the SoC at its start and end.
	 *
	 * @param timedata the {@link Timedata}
	 * @param now      the current time
	 * @param days     the period in days
	 * @param capacity the capacity [Wh]; null if unknown
	 * @return the efficiency; null if the period gives no usable value
	 * @throws Exception on error
	 */
	private Double measureEfficiency(Timedata timedata, Instant now, int days, Integer capacity) throws Exception {
		final var zone = this.componentManager.getClock().getZone();
		final var to = ZonedDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.HOURS);
		final var from = to.minusDays(days);
		final var hybrid = this.ess instanceof HybridEss;
		final var charged = new ChannelAddress(this.ess.id(), hybrid //
				? HybridEss.ChannelId.DC_CHARGE_ENERGY.id()
				: SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY.id());
		final var discharged = new ChannelAddress(this.ess.id(), hybrid //
				? HybridEss.ChannelId.DC_DISCHARGE_ENERGY.id()
				: SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY.id());
		final var soc = new ChannelAddress(this.ess.id(), SymmetricEss.ChannelId.SOC.id());
		// Sum of the days instead of last minus first over the whole period: the
		// energy counters restart when a component is rebuilt, a day with a negative
		// or missing value is left out
		final var energy = timedata.queryHistoricEnergyPerPeriod(null, from, to, Set.of(charged, discharged),
				new Resolution(1, ChronoUnit.DAYS));
		final var chargedWh = sumOfDays(energy, charged);
		final var dischargedWh = sumOfDays(energy, discharged);
		final var resolution = new Resolution(HISTORY_MINUTES, ChronoUnit.MINUTES);
		final var socFrom = firstValue(timedata.queryHistoricData(null, from, from.plusHours(1), Set.of(soc), resolution)
				.values().stream().map(r -> asDouble(r.get(soc))).toList());
		final var socTo = firstValue(timedata.queryHistoricData(null, to.minusHours(1), to, Set.of(soc), resolution)
				.values().stream().map(r -> asDouble(r.get(soc))).toList().reversed());
		if (chargedWh == null || dischargedWh == null || socFrom == null || socTo == null) {
			this.logInfo(this.log, String.format("Efficiency not measurable over %d days: missing %s%s%s%s", days,
					chargedWh == null ? "charge energy " : "", dischargedWh == null ? "discharge energy " : "",
					socFrom == null ? "SoC at start " : "", socTo == null ? "SoC at end" : ""));
			return null;
		}
		final var efficiency = Inventory.calculateEfficiency(chargedWh, dischargedWh, (int) Math.round(socFrom),
				(int) Math.round(socTo), capacity);
		this.logInfo(this.log, String.format("Efficiency over %d days: charged %.0f Wh, discharged %.0f Wh, "
				+ "SoC %.0f -> %.0f %% = %s", days, chargedWh, dischargedWh, socFrom, socTo,
				efficiency == null ? "unknown" : String.format("%.1f %%", efficiency * 100)));
		return efficiency;
	}

	/**
	 * Sums the daily energies of a channel; days without a usable value are left
	 * out.
	 *
	 * @param energyPerDay the result of the per-period query
	 * @param channel      the channel
	 * @return the sum [Wh]; null without any usable day
	 */
	private static Double sumOfDays(Map<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> energyPerDay,
			ChannelAddress channel) {
		Double sum = null;
		for (var row : energyPerDay.values()) {
			final var value = asDouble(row.get(channel));
			if (value == null || value < 0) {
				continue;
			}
			sum = (sum == null ? 0 : sum) + value;
		}
		return sum;
	}

	private static Double firstValue(List<Double> values) {
		for (var value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private static Double asDouble(JsonElement json) {
		if (json == null) {
			return null;
		}
		return JsonUtils.getAsOptionalDouble(json).orElse(null);
	}

	@Override
	public String debugLog() {
		final var price = this.getPrice().get();
		final IntegerReadChannel grid = this.channel(EssEnergyPrice.ChannelId.STORED_GRID_ENERGY);
		final IntegerReadChannel pv = this.channel(EssEnergyPrice.ChannelId.STORED_PV_ENERGY);
		final IntegerReadChannel unpriced = this.channel(EssEnergyPrice.ChannelId.UNPRICED_ENERGY);
		final IntegerReadChannel chargeGrid = this.channel(EssEnergyPrice.ChannelId.CHARGE_GRID_POWER);
		final IntegerReadChannel chargePv = this.channel(EssEnergyPrice.ChannelId.CHARGE_PV_POWER);
		return String.format("Price:%s|Stored:%s Wh (Grid:%s|PV:%s|?:%s)|Charge Grid:%d|PV:%d W|Eff:%.0f %%", //
				price == null ? "-" : String.format("%.1f", price), //
				this.getStoredEnergy().asStringWithoutUnit(), //
				grid.value().asStringWithoutUnit(), //
				pv.value().asStringWithoutUnit(), //
				unpriced.value().asStringWithoutUnit(), //
				chargeGrid.value().orElse(0), //
				chargePv.value().orElse(0), //
				this.efficiency * 100);
	}
}
