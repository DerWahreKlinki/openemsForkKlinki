package io.openems.edge.ess.cluster.hybrid;

import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.ess.api.AsymmetricEss;
import io.openems.edge.ess.api.ManagedAsymmetricEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.MetaEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Power;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Ess.Cluster.Hybrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
@GenerateTargetsFromReferences("Ess")
public class EssClusterHybridImpl extends AbstractOpenemsComponent implements EssClusterHybrid, ManagedAsymmetricEss,
		AsymmetricEss, ManagedSymmetricEss, SymmetricEss, MetaEss, OpenemsComponent, ModbusSlave, EventHandler,
		StartStoppable {

	private final Logger log = LoggerFactory.getLogger(EssClusterHybridImpl.class);
	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);
	private final ChannelManager channelManager = new ChannelManager(this);
	private final List<SymmetricEss> esss = new CopyOnWriteArrayList<>();

	// Dynamic and optional on purpose: _power binds all ManagedSymmetricEss
	// (including this cluster) while it is activated. A static reference here
	// closes the cycle Cluster -> Power -> Cluster at start-up, which Felix reports
	// as "ServiceFactory.getService() resulted in a cycle"; the cluster then stays
	// unknown to _power until it is restarted (observed live on 2026-10-03).
	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Power power;

	@Reference
	protected ComponentManager componentManager;

	@Reference(name = "Ess", policy = DYNAMIC, policyOption = GREEDY, cardinality = MULTIPLE, //
			target = "(&(id=${config.ess_ids})(enabled=true)(!(service.factoryPid=Ess.Cluster))(!(service.factoryPid=Ess.Cluster.Hybrid)))")
	protected synchronized void addEss(ManagedSymmetricEss ess) {
		this.esss.add(ess);
		this.channelManager.deactivate();
		this.channelManager.activate(this.esss);
	}

	protected synchronized void removeEss(ManagedSymmetricEss ess) {
		this.esss.remove(ess);
		this.channelManager.deactivate();
		this.channelManager.activate(this.esss);
	}

	private Config config;

	public EssClusterHybridImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				AsymmetricEss.ChannelId.values(), //
				ManagedAsymmetricEss.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				EssClusterHybrid.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		this.config = config;
		this.activate(context, config.id(), config.alias(), config.enabled());
		this.channelManager.activate(this.esss);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		this.channelManager.deactivate();
		super.deactivate();
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsException {
		throw new OpenemsException("EssClusterHybridImpl.applyPower() should never be called.");
	}

	@Override
	public void applyPower(int activePowerL1, int reactivePowerL1, int activePowerL2, int reactivePowerL2,
			int activePowerL3, int reactivePowerL3) throws OpenemsException {
		throw new OpenemsException("EssClusterHybridImpl.applyPower() should never be called.");
	}

	@Override
	public int getPowerPrecision() {
		return calculateMinPowerPrecision(this.esss);
	}

	/**
	 * Calculates the minimum PowerPrecision of all ESS in the Cluster.
	 * 
	 * <p>
	 * If there are no ESS or if no ESS has a PowerPrecision, the default value of 1
	 * is returned.
	 * 
	 * @param esss a List of ESS
	 * @return minimum PowerPrecision
	 */
	protected static int calculateMinPowerPrecision(List<SymmetricEss> esss) {
		return Math.max(1, esss.stream() //
				.filter(ManagedSymmetricEss.class::isInstance) //
				.map(ManagedSymmetricEss.class::cast) //
				.mapToInt(ManagedSymmetricEss::getPowerPrecision) //
				.min() //
				.orElse(1));
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				SymmetricEss.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode), //
				AsymmetricEss.getModbusSlaveNatureTable(accessMode), //
				ManagedAsymmetricEss.getModbusSlaveNatureTable(accessMode), //
				ModbusSlaveNatureTable.of(EssClusterHybridImpl.class, accessMode, 300) //
						.build());
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> this.handleStartStop();
		}
	}

	/**
	 * Starts/Stops all ESS in the Cluster as required by Config or call to
	 * setStartStop().
	 */
	private void handleStartStop() {
		var target = this.getStartStopTarget();
		if (target == this.getStartStop()) {
			return;
		}

		this.esss.stream() //
				.filter(StartStoppable.class::isInstance) //
				.map(StartStoppable.class::cast) //
				.forEach(ess -> {
					try {
						ess.setStartStop(target);
					} catch (OpenemsNamedException e) {
						this.logError(this.log, e.getMessage());
					}
				});
	}

	@Override
	public synchronized String[] getEssIds() {
		return this.config.ess_ids();
	}

	@Override
	public void setStartStop(StartStop value) {
		this.startStopTarget.set(value);
	}

	private StartStop getStartStopTarget() {
		return switch (this.config.startStop()) {
		case AUTO -> this.startStopTarget.get();
		case START -> StartStop.START;
		case STOP -> StartStop.STOP;
		};
	}
}
