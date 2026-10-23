package io.openems.edge.controller.ess.chargedischargelimiter.cluster;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

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
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.MetaEss;
import io.openems.edge.ess.api.SymmetricEss;

/**
 * Reports the SoC window and the allowed power of an ESS cluster to the energy
 * optimizer: the sum over the Charge/Discharge Limiters of the members. The
 * limiters of the members keep controlling their ESS, this controller controls
 * nothing. A member without a limiter counts with its whole capacity and the
 * power its ESS allows.
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

	private Config config;
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
		final var summary = this.summarize();
		this.summary = summary;
		this._setStateMachine(summary.state());
		this._setTrigger(summary.trigger());
		this._setMembers(summary.members());
		final var report = summary.report();
		this._setMinEnergy(report == null ? null : report.minEnergy());
		this._setMaxEnergy(report == null ? null : report.maxEnergy());
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
				.build());
	}

	@Override
	public String debugLog() {
		final var summary = this.summary;
		return summary.state().getName() + (summary.trigger().isEmpty() ? "" : " (" + summary.trigger() + ")")
				+ (summary.report() == null ? ""
						: "|" + summary.report().minEnergy() + "-" + summary.report().maxEnergy() + " Wh|"
								+ summary.report().maxChargePower() + "/" + summary.report().maxDischargePower() + " W");
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
					trigger = memberId + ": " + memberState.getName();
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
