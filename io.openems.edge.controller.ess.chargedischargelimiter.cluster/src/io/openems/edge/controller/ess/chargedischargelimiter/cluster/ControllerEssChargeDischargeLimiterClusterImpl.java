package io.openems.edge.controller.ess.chargedischargelimiter.cluster;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.ArrayList;
import java.util.List;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.modbusslave.ModbusType;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.controller.ess.chargedischargelimiter.ControllerEssChargeDischargeLimiter;
import io.openems.edge.controller.ess.chargedischargelimiter.EnergyScheduler;
import io.openems.edge.energy.api.EnergySchedulable;
import io.openems.edge.energy.api.handler.EnergyScheduleHandler;
import io.openems.edge.energy.api.handler.RescheduleMode;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.MetaEss;
import io.openems.edge.ess.api.SymmetricEss;

/**
 * Reports the SoC window and the allowed power of an ESS cluster to the energy
 * optimizer: the sum over the Charge/Discharge Limiters of the members. The
 * limiters of the members keep controlling their ESS. A member without a
 * limiter counts with its whole capacity and the power its ESS allows.
 *
 * <p>
 * A member with a pending calibration charges first: the power solver of
 * OpenEMS distributes the charge power of the cluster by SoC and knows nothing
 * about calibrations, so this controller gives the other members a charge limit
 * (see {@link #updateChargePriority()}). Their limiters apply it and report it
 * as their allowed charge power, so the optimizer plans with it as well.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.ChargeDischargeLimiter.Cluster", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssChargeDischargeLimiterClusterImpl extends AbstractOpenemsComponent
		implements ControllerEssChargeDischargeLimiterCluster, Controller, OpenemsComponent, EnergySchedulable,
		ModbusSlave {

	private final Logger log = LoggerFactory.getLogger(ControllerEssChargeDischargeLimiterClusterImpl.class);

	@Reference
	private ComponentManager componentManager;

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	private MetaEss ess;

	@Reference
	private Sum sum;

	// The limit melts away by this much per cycle without feed-in, so that the
	// priority member regains the charge power of the cluster; the equilibrium
	// is a feed-in of this size while the other members take the rest
	protected static final int MEMBER_CHARGE_LIMIT_DECAY_W = 50;
	// The limit grows with the feed-in only while the priority member falls short
	// of the charge power the power solver asks of it (its battery tapers near
	// full, a limit of the device): then the feed-in is power it cannot take. Fed
	// in while it charges as asked, another controller (e.g. the time-of-use
	// controller limiting the cluster) holds the cluster back, and a growing limit
	// would hand the share to the other members instead. Short by less than this
	// share of the asked power counts as charging as asked
	protected static final double PRIORITY_SHORTFALL_SHARE = 0.1;
	// The limit grows with the feed-in also while the power solver asks of the
	// priority member all the charge power its limiter allows, less this
	// tolerance: the member is at its ceiling (the device, the SoC window) and the
	// feed-in is power it cannot take either (2026-11-10: Victron at its 5 kW,
	// 200 W fed in while the SolarEdge battery was held at 0). The ceiling this
	// cluster limiter sets itself (what the member takes plus the headroom) does
	// not count: at that one the member still ramps up
	protected static final int PRIORITY_CEILING_TOLERANCE_W = 100;
	// The limit grows by at most this much per cycle: a feed-in of one or two
	// cycles (readings of the devices lag behind each other) stays small, and the
	// other members ramp up instead of jumping in while the cluster is still
	// adjusting (2026-11-06: 2.6 kW drawn from the grid for a moment)
	protected static final int MEMBER_CHARGE_LIMIT_RAMP_W = 500;
	// A calibrating member is limited to what it charges right now plus this
	// headroom: the power solver knows nothing of a battery that tapers near full
	// and would assign it a share it cannot take, which is fed into the grid
	// (2026-11-06: 1.8 kW for the whole hold). With the headroom the member can
	// still ramp up, by this much per cycle. Its limiter raises the limit to its
	// own hold or force charge, so the two never conflict
	protected static final int CALIBRATING_HEADROOM_W = 500;

	private Config config;
	// The charge limit of the other members while a member charges first; null
	// while no member does
	private Integer memberChargeLimit = null;
	private EnergyScheduleHandler energyScheduleHandler;
	// The last summary of the members, for the energy optimizer; null while unknown
	private volatile Summary summary = Summary.NONE;
	// The last complete report: it stands in while a member restarts and its
	// channels are empty for a moment
	private volatile EnergyScheduler.Config lastReport = null;
	// The optimizer was told that the first report is there: right after the
	// activation the members have no values yet and the optimizer plans without
	// the window until its next run
	private boolean firstReportAnnounced = false;

	public ControllerEssChargeDischargeLimiterClusterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEssChargeDischargeLimiterCluster.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "ess", config.ess_id())) {
			return;
		}
		this.energyScheduleHandler = EnergyScheduler.buildEnergyScheduleHandler(this, this::report);
	}

	@Modified
	private void modified(ComponentContext context, Config config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "ess", config.ess_id())) {
			return;
		}
		if (this.energyScheduleHandler != null) {
			this.energyScheduleHandler.triggerReschedule("ControllerEssChargeDischargeLimiterClusterImpl::modified()",
					RescheduleMode.DO_NOT_UPDATE_CURRENT_PERIOD);
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.updateChargePriority();
		final var summary = this.summarize();
		this.summary = summary;
		this._setStateMachine(summary.state());
		this._setTrigger(summary.trigger());
		this._setMembers(summary.members());
		final var report = summary.report();
		this._setMinEnergy(report == null ? null : report.minEnergy());
		this._setMaxEnergy(report == null ? null : report.maxEnergy());
		this._setNetCapacity(report == null ? null : report.maxEnergy() - report.minEnergy());
		this._setCurrentEnergy(summary.currentEnergy());
		this._setChargePowerAllowed(report == null ? null : report.maxChargePower());
		this._setDischargePowerAllowed(report == null ? null : report.maxDischargePower());
		this._setNetSoc(netSoc(report, summary.currentEnergy()));
		if (report != null && !this.firstReportAnnounced && this.config.reportToEnergyScheduler()
				&& this.energyScheduleHandler != null) {
			this.firstReportAnnounced = true;
			this.energyScheduleHandler.triggerReschedule("ControllerEssChargeDischargeLimiterClusterImpl::first report",
					RescheduleMode.OPTIMIZE_CURRENT_PERIOD);
		}
		if (this.config.debugMode()) {
			this.logInfo(this.log, this.debugLog());
		}
	}

	/**
	 * The SoC within the window of the cluster: 0 % at the lower end, 100 % at
	 * the upper end.
	 *
	 * @param report        the window
	 * @param currentEnergy the energy in the cluster in [Wh]
	 * @return the SoC in [%]; null if unknown
	 */
	protected static Integer netSoc(EnergyScheduler.Config report, Integer currentEnergy) {
		if (report == null || currentEnergy == null) {
			return null;
		}
		final var window = report.maxEnergy() - report.minEnergy();
		if (window <= 0) {
			return null;
		}
		final var soc = (int) Math.round(100. * (currentEnergy - report.minEnergy()) / window);
		return Math.max(0, Math.min(100, soc));
	}

	@Override
	public EnergyScheduleHandler getEnergyScheduleHandler() {
		return this.energyScheduleHandler;
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(OpenemsComponent.getModbusSlaveNatureTable(accessMode), ModbusSlaveNatureTable
				.of(ControllerEssChargeDischargeLimiterCluster.class, accessMode, 100) //
				.channel(0, ControllerEssChargeDischargeLimiterCluster.ChannelId.STATE_MACHINE, ModbusType.UINT16)
				.channel(1, ControllerEssChargeDischargeLimiterCluster.ChannelId.MEMBERS, ModbusType.UINT16)
				.channel(2, ControllerEssChargeDischargeLimiterCluster.ChannelId.NET_SOC, ModbusType.UINT16)
				.channel(3, ControllerEssChargeDischargeLimiterCluster.ChannelId.MIN_ENERGY, ModbusType.UINT32)
				.channel(5, ControllerEssChargeDischargeLimiterCluster.ChannelId.MAX_ENERGY, ModbusType.UINT32)
				.channel(7, ControllerEssChargeDischargeLimiterCluster.ChannelId.CURRENT_ENERGY, ModbusType.UINT32)
				.channel(9, ControllerEssChargeDischargeLimiterCluster.ChannelId.CHARGE_POWER_ALLOWED,
						ModbusType.UINT16)
				.channel(10, ControllerEssChargeDischargeLimiterCluster.ChannelId.DISCHARGE_POWER_ALLOWED,
						ModbusType.UINT16)
				.channel(11, ControllerEssChargeDischargeLimiterCluster.ChannelId.NET_CAPACITY, ModbusType.UINT32)
				.build());
	}

	@Override
	public String debugLog() {
		final var summary = this.summary;
		final var limit = this.memberChargeLimit;
		return summary.state().getName() + (summary.trigger().isEmpty() ? "" : " (" + summary.trigger() + ")")
				+ (summary.report() == null ? ""
						: "|" + summary.report().minEnergy() + "-" + summary.report().maxEnergy() + " Wh|"
								+ summary.report().maxChargePower() + "/" + summary.report().maxDischargePower() + " W")
				+ (limit == null ? "" : "|" + this.getPriorityMember().orElse("?") + " first, others <= " + limit + " W");
	}

	/**
	 * A member with a pending calibration charges first. The other members get a
	 * charge limit that starts at 0 and grows with the power fed into the grid,
	 * so they take only what the calibrating member leaves over, before it is fed
	 * in. Without feed-in the limit melts away again and the calibrating member
	 * regains the whole charge power of the cluster. Nothing is forced: the limit
	 * only redistributes the charge power the cluster gets anyway, so no energy
	 * comes from the grid for the calibration unless its limiter allows the force
	 * charge. A member without a limiter cannot be limited.
	 */
	protected void updateChargePriority() {
		final var ess = this.ess;
		if (ess == null) {
			return;
		}
		final var limiters = this.memberLimiters();
		final var priority = limiters.stream() //
				.filter(ControllerEssChargeDischargeLimiterClusterImpl::chargesFirst) //
				.findFirst().orElse(null);
		if (priority == null) {
			this.memberChargeLimit = null;
		} else {
			final var sum = this.sum;
			final var cannotTakeMore = this.fallsShortOfAskedPower(priority) || this.isAskedAllItMay(priority);
			final var feedIn = sum == null || !cannotTakeMore ? 0
					: Math.min(Math.max(0, -sum.getGridActivePower().orElse(0)), MEMBER_CHARGE_LIMIT_RAMP_W);
			final var before = this.memberChargeLimit == null ? 0 : this.memberChargeLimit;
			var limit = Math.max(0, before + feedIn - MEMBER_CHARGE_LIMIT_DECAY_W);
			// no higher than the other members could charge at all
			final var othersMax = this.allowedChargePowerOfOthers(limiters, priority);
			if (othersMax != null) {
				limit = Math.min(limit, othersMax);
			}
			this.memberChargeLimit = limit;
		}
		for (var limiter : limiters) {
			if (isCalibrating(limiter)) {
				// no more than it takes: the rest goes to the others, not to the grid
				final var charging = this.batteryChargePowerOf(limiter);
				limiter.setClusterChargeLimit(charging == null ? null : charging + CALIBRATING_HEADROOM_W);
			} else {
				limiter.setClusterChargeLimit(priority == null ? null : this.memberChargeLimit);
			}
		}
		this._setPriorityMember(priority == null ? "" : priority.getEssId());
		this._setMemberChargeLimit(this.memberChargeLimit);
	}

	/**
	 * Whether the member of this limiter calibrates: waiting, force-charging or
	 * holding at the balancing SoC.
	 *
	 * @param limiter the limiter of a member
	 * @return true if calibrating
	 */
	protected static boolean isCalibrating(ControllerEssChargeDischargeLimiter limiter) {
		return switch (memberStateOf(limiter)) {
		case BALANCING_WANTED, FORCE_CHARGE_ACTIVE, BALANCING_ACTIVE -> true;
		default -> false;
		};
	}

	/**
	 * The battery charge power of the member of this limiter, as a positive value;
	 * 0 while it discharges.
	 *
	 * @param limiter the limiter of a member
	 * @return the charge power in [W]; null if unknown
	 */
	private Integer batteryChargePowerOf(ControllerEssChargeDischargeLimiter limiter) {
		try {
			final OpenemsComponent member = this.componentManager.getComponent(limiter.getEssId());
			final Integer power = batteryPowerOf(member);
			return power == null ? null : Math.max(-power, 0);
		} catch (OpenemsNamedException e) {
			this.logDebug(this.log, "Cluster member [" + limiter.getEssId() + "] not found: " + e.getMessage());
			return null;
		}
	}

	/**
	 * The battery power of an ESS, the same quantity as its set-point: battery
	 * power for a hybrid ESS that takes battery power as set-point (SolarEdge in
	 * DC_SETPOINT mode), AC power otherwise; negative = charge.
	 *
	 * @param ess the ESS
	 * @return the power in [W]; null if unknown
	 */
	private static Integer batteryPowerOf(OpenemsComponent ess) {
		if (ess instanceof HybridEss hybrid && isSetPointBatteryPower(ess)) {
			return hybrid.getDcDischargePower().get();
		}
		return ess instanceof SymmetricEss symmetric ? symmetric.getActivePower().get() : null;
	}

	/**
	 * The limiters of the members, in the order of the members of the cluster.
	 *
	 * @return the limiters
	 */
	private List<ControllerEssChargeDischargeLimiter> memberLimiters() {
		final var limiters = this.componentManager.getEnabledComponentsOfType(ControllerEssChargeDischargeLimiter.class);
		final var result = new ArrayList<ControllerEssChargeDischargeLimiter>();
		for (var memberId : this.ess.getEssIds()) {
			limiters.stream() //
					.filter(l -> memberId.equals(l.getEssId())) //
					.findFirst() //
					.ifPresent(result::add);
		}
		return result;
	}

	/**
	 * Does the member of this limiter charge first? While its calibration is
	 * pending and it can still take charge power: waiting for the price with PV
	 * from the cluster, or force-charging.
	 *
	 * @param limiter the limiter of a member
	 * @return true if the member charges first
	 */
	protected static boolean chargesFirst(ControllerEssChargeDischargeLimiter limiter) {
		return switch (memberStateOf(limiter)) {
		case BALANCING_WANTED, FORCE_CHARGE_ACTIVE -> limiter.getChargePowerAllowed().orElse(0) > 0;
		default -> false;
		};
	}

	/**
	 * Does the member of this limiter charge with clearly less than the power
	 * solver asked of it? Only then the feed-in is power it cannot take.
	 *
	 * @param limiter the limiter of the priority member
	 * @return true if it falls short; false if it charges as asked or if unknown
	 */
	private boolean fallsShortOfAskedPower(ControllerEssChargeDischargeLimiter limiter) {
		try {
			final OpenemsComponent member = this.componentManager.getComponent(limiter.getEssId());
			if (!(member instanceof ManagedSymmetricEss managed)) {
				return false;
			}
			// the set-point of the solver; negative = charge
			final Integer asked = managed.getDebugSetActivePower().get();
			if (asked == null || -asked <= 0) {
				return false;
			}
			final Integer actual = batteryPowerOf(member);
			return actual != null && -actual < -asked * (1 - PRIORITY_SHORTFALL_SHARE);
		} catch (OpenemsNamedException e) {
			this.logDebug(this.log, "Cluster member [" + limiter.getEssId() + "] not found: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Does the power solver ask of the member of this limiter all the charge power
	 * its limiter allows? Then it is at its ceiling, and the feed-in is power it
	 * cannot take. Not while the ceiling is the one of this cluster limiter (what
	 * the member takes plus the headroom): there it still ramps up.
	 *
	 * @param limiter the limiter of the priority member
	 * @return true if asked all it may; false otherwise or if unknown
	 */
	private boolean isAskedAllItMay(ControllerEssChargeDischargeLimiter limiter) {
		final var allowed = limiter.getChargePowerAllowed().orElse(0);
		if (allowed <= 0) {
			return false;
		}
		final Integer ownCeiling = limiter.getClusterChargeLimit().get();
		if (ownCeiling != null && allowed >= ownCeiling) {
			return false;
		}
		try {
			final OpenemsComponent member = this.componentManager.getComponent(limiter.getEssId());
			if (!(member instanceof ManagedSymmetricEss managed)) {
				return false;
			}
			// the set-point of the solver; negative = charge
			final Integer asked = managed.getDebugSetActivePower().get();
			return asked != null && -asked >= allowed - PRIORITY_CEILING_TOLERANCE_W;
		} catch (OpenemsNamedException e) {
			this.logDebug(this.log, "Cluster member [" + limiter.getEssId() + "] not found: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Does the ESS take battery power (instead of AC power) as set-point? Read from
	 * the configuration property 'setPointMode' of the ESS via its generic property
	 * channel, as the limiter of the member does.
	 *
	 * @param ess the ESS
	 * @return true if the set-point is battery power
	 */
	private static boolean isSetPointBatteryPower(OpenemsComponent ess) {
		try {
			final var value = ess.channel("_PropertySetPointMode").value().get();
			return value != null && "DC_SETPOINT".equals(value.toString());
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * The charge power the ESS of the other members allow in total.
	 *
	 * @param limiters the limiters of the members
	 * @param priority the limiter of the member that charges first
	 * @return the sum in [W]; null if unknown for all of them
	 */
	private Integer allowedChargePowerOfOthers(List<ControllerEssChargeDischargeLimiter> limiters,
			ControllerEssChargeDischargeLimiter priority) {
		Integer total = null;
		for (var limiter : limiters) {
			if (limiter == priority) {
				continue;
			}
			try {
				final OpenemsComponent member = this.componentManager.getComponent(limiter.getEssId());
				if (member instanceof ManagedSymmetricEss managed) {
					final var allowed = managed.getAllowedChargePower().get();
					if (allowed != null) {
						total = (total == null ? 0 : total) + Math.abs(allowed);
					}
				}
			} catch (OpenemsNamedException e) {
				this.logDebug(this.log, "Cluster member [" + limiter.getEssId() + "] not found: " + e.getMessage());
			}
		}
		return total;
	}

	/**
	 * What the energy optimizer gets: the summary of the members, taken fresh,
	 * because the optimizer may ask before the first cycle; null while this
	 * controller does not report.
	 *
	 * @return the config of the energy schedule handler; null if none
	 */
	protected EnergyScheduler.Config report() {
		final var config = this.config;
		if (config == null || !config.enabled() || !config.reportToEnergyScheduler()) {
			return null;
		}
		return this.summarize().report();
	}

	/**
	 * The summary of the members.
	 *
	 * @param report        the report to the optimizer; null if unknown
	 * @param currentEnergy the energy in the cluster in [Wh]; null if unknown
	 * @param state         the state of the cluster
	 * @param trigger       the member and its state that set the state; empty if
	 *                      none
	 * @param members       the number of members with a limiter
	 */
	protected record Summary(EnergyScheduler.Config report, Integer currentEnergy, State state, String trigger,
			int members) {
		static final Summary NONE = new Summary(null, null, State.UNDEFINED, "", 0);
	}

	/**
	 * Sums the limiters of the members: the SoC windows in energy, the energy and
	 * the power they allow right now. The state is the most severe one among the
	 * members: error before balancing before limiting.
	 *
	 * @return the summary
	 */
	protected Summary summarize() {
		final var ess = this.ess;
		if (ess == null) {
			return Summary.NONE;
		}
		final var limiters = this.componentManager.getEnabledComponentsOfType(ControllerEssChargeDischargeLimiter.class);
		var minEnergy = 0;
		var maxEnergy = 0;
		var currentEnergy = 0;
		var chargePower = 0;
		var dischargePower = 0;
		var members = 0;
		var known = 0;
		var complete = true;
		var state = State.NORMAL;
		var trigger = "";
		for (var memberId : ess.getEssIds()) {
			final var limiter = limiters.stream() //
					.filter(l -> memberId.equals(l.getEssId())) //
					.findFirst().orElse(null);
			if (limiter != null) {
				members++;
				final var memberState = memberStateOf(limiter);
				final var severity = severityOf(memberState);
				if (severity.ordinal() > state.ordinal()) {
					state = severity;
					// the enum name, the UI translates it
					trigger = memberId + ": " + memberState.name();
				}
				final var min = limiter.getMinEnergy().get();
				final var max = limiter.getMaxEnergy().get();
				final var current = limiter.getCurrentEnergy().get();
				if (min == null || max == null) {
					complete = false;
					continue;
				}
				known++;
				minEnergy += min;
				maxEnergy += max;
				currentEnergy += current == null ? 0 : current;
				if (severity != State.MEMBER_ERROR) {
					chargePower += Math.max(limiter.getChargePowerAllowed().orElse(0), 0);
					dischargePower += Math.max(limiter.getDischargePowerAllowed().orElse(0), 0);
				}
				continue;
			}
			// no limiter: the whole ESS counts
			try {
				final SymmetricEss member = this.componentManager.getComponent(memberId);
				final var capacity = member.getCapacity().get();
				if (capacity == null) {
					complete = false;
					continue;
				}
				known++;
				maxEnergy += capacity;
				final var soc = member.getSoc().get();
				currentEnergy += soc == null ? 0 : (int) Math.round(capacity * soc / 100.);
				if (member instanceof ManagedSymmetricEss managed) {
					chargePower += Math.abs(managed.getAllowedChargePower().orElse(0));
					dischargePower += Math.max(managed.getAllowedDischargePower().orElse(0), 0);
				}
			} catch (OpenemsNamedException e) {
				this.logDebug(this.log, "Cluster member [" + memberId + "] not found: " + e.getMessage());
				complete = false;
			}
		}
		if (members == 0) {
			return new Summary(null, known > 0 ? currentEnergy : null, State.NO_MEMBERS, "", 0);
		}
		final EnergyScheduler.Config report;
		if (complete) {
			report = new EnergyScheduler.Config(minEnergy, maxEnergy, chargePower, dischargePower);
			this.lastReport = report;
		} else {
			// a member restarts: its channels are empty for a moment, the last report
			// stands in
			report = this.lastReport;
		}
		return new Summary(report, known > 0 ? currentEnergy : null, state, trigger, members);
	}

	private static io.openems.edge.controller.ess.chargedischargelimiter.enums.State memberStateOf(
			ControllerEssChargeDischargeLimiter limiter) {
		final io.openems.edge.controller.ess.chargedischargelimiter.enums.State state = limiter
				.getStateMachineChannel().value().asEnum();
		return state == null ? io.openems.edge.controller.ess.chargedischargelimiter.enums.State.UNDEFINED : state;
	}

	/**
	 * The state of the cluster a member in this state causes.
	 *
	 * @param memberState the state of the limiter of a member
	 * @return the state of the cluster
	 */
	protected static State severityOf(io.openems.edge.controller.ess.chargedischargelimiter.enums.State memberState) {
		return switch (memberState) {
		case ERROR, UNDEFINED -> State.MEMBER_ERROR;
		case BALANCING_ACTIVE, BALANCING_WANTED, FORCE_CHARGE_ACTIVE -> State.MEMBER_BALANCING;
		case BELOW_MIN_SOC, ABOVE_MAX_SOC, MIN_SOC_REACHED, MAX_SOC_REACHED, APPROACHING_MIN_SOC, APPROACHING_MAX_SOC //
			-> State.MEMBER_LIMITED;
		case NORMAL -> State.NORMAL;
		};
	}
}
