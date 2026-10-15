package io.openems.edge.simulator.datasource.csv.file;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;

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

import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.simulator.CsvUtils;
import io.openems.edge.simulator.DataContainer;
import io.openems.edge.simulator.datasource.api.AbstractDatasource;
import io.openems.edge.simulator.datasource.api.SimulatorDatasource;

/**
 * Provides the values of a CSV file. With 'alignToClock' the record is selected
 * by the time of day: record 0 is midnight, every record lasts 'timeDelta'
 * seconds. This matches the Simulator Predictor, which maps the first record to
 * midnight as well, so that the simulated day and its prediction agree whenever
 * the Edge is started.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Simulator.Datasource.CSV.File", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_WRITE //
})
public class SimulatorDatasourceCsvFileImpl extends AbstractDatasource
		implements SimulatorDatasourceCsvFile, SimulatorDatasource, OpenemsComponent, EventHandler {

	@Reference
	private ComponentManager componentManager;

	private Config config;

	public SimulatorDatasourceCsvFileImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				SimulatorDatasourceCsvFile.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws NumberFormatException, IOException {
		this.config = config;
		super.activate(context, config.id(), config.alias(), config.enabled(), config.timeDelta());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		if (!this.config.alignToClock()) {
			super.handleEvent(event);
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_WRITE:
			final var now = LocalDateTime.now(this.componentManager.getClock());
			final var timeDelta = Math.max(this.config.timeDelta(), 1);
			this.setRecordIndex(now.toLocalTime().toSecondOfDay() / timeDelta);
			break;
		}
	}

	@Override
	protected ComponentManager getComponentManager() {
		return this.componentManager;
	}

	@Override
	protected DataContainer getData() throws NumberFormatException, IOException {
		return CsvUtils.readCsvFile(new File(this.config.filename()), this.config.format(), this.config.factor());
	}
}
