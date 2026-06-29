package io.openems.edge.solaredge.common;


import java.util.Map;
import java.util.Optional;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.sunspec.AbstractOpenemsSunSpecComponent;
import io.openems.edge.bridge.modbus.sunspec.SunSpecModel;
import io.openems.edge.bridge.modbus.sunspec.SunSpecPoint;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.SymmetricEss;
//

public abstract class AbstractSunSpecEss extends AbstractOpenemsSunSpecComponent
		// TODO Implement HybridEss, ManagedSymmetricEss, AsymmetricEss
		implements SunSpecEss, SymmetricEss, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(AbstractSunSpecEss.class);

	public AbstractSunSpecEss(Map<SunSpecModel, Priority> activeModels,
			io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds,
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) throws OpenemsException {
		super(activeModels, firstInitialChannelIds, furtherInitialChannelIds);
		
	}

	/**
     * Make sure to call this method from the inheriting OSGi Component.
     */
    @Override
    protected void activate(ComponentContext context, String id, String alias, boolean enabled, int unitId,
            int readFromCommonBlockNo) throws OpenemsException {
        super.activate(context, id, alias, enabled, unitId, readFromCommonBlockNo);
    }


	/**
	 * Make sure to call this method from the inheriting OSGi Component.
	 */
	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public String debugLog() {
		return new StringBuilder() //
				.append("SoC:").append(this.getSoc().asString()) //
				.append("|ESS ActivePower:").append(this.getActivePower().asString()) //
				.toString();
	}

	@Override
	protected void onSunSpecInitializationCompleted() {
		this.logInfo(this.log, "SunSpec initialization finished. " + this.channels().size() + " Channels available.");
	}

	@Override
	protected <T extends Channel<?>> Optional<T> getSunSpecChannel(SunSpecPoint point) {
		return super.getSunSpecChannel(point);
	}

	@Override
	protected <T extends Channel<?>> T getSunSpecChannelOrError(SunSpecPoint point) throws OpenemsException {
		return super.getSunSpecChannelOrError(point);
	}
}
