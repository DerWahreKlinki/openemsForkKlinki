package io.openems.edge.simulator.timeofusetariff;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.common.types.ChannelAddress;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.simulator.datasource.api.SimulatorDatasource;
import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;

/**
 * Provides a Time-Of-Use price curve from a Simulator DataSource: the records of
 * the price column are averaged per quarter-hour (according to the Time-Delta
 * of the DataSource), mapped to today from midnight on and repeated for the
 * configured number of days.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Simulator.TimeOfUseTariff", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@GenerateTargetsFromReferences("datasource")
public class SimulatorTimeOfUseTariffImpl extends AbstractOpenemsComponent
		implements SimulatorTimeOfUseTariff, TimeOfUseTariff, OpenemsComponent {

	private static final int SECONDS_PER_QUARTER = 15 * 60;
	private static final int QUARTERS_PER_DAY = 24 * 4;

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.datasource_id})(enabled=true))")
	private SimulatorDatasource datasource;

	private Config config;

	public SimulatorTimeOfUseTariffImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				SimulatorTimeOfUseTariff.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public TimeOfUsePrices getPrices() {
		final List<Double> values = this.datasource.getValues(OpenemsType.DOUBLE,
				new ChannelAddress(this.id(), this.config.key()));
		final var quarters = averagePerQuarter(values, this.datasource.getTimeDelta());
		if (quarters.isEmpty()) {
			return TimeOfUsePrices.EMPTY_PRICES;
		}
		final var midnight = ZonedDateTime.now(this.componentManager.getClock()).truncatedTo(ChronoUnit.DAYS);
		final var days = Math.max(this.config.horizonDays(), 1);
		final var result = new Double[QUARTERS_PER_DAY * days];
		for (var i = 0; i < result.length; i++) {
			result[i] = quarters.get(i % quarters.size());
		}
		return TimeOfUsePrices.from(midnight.toInstant(), result);
	}

	/**
	 * Averages the records per quarter-hour.
	 *
	 * @param values    the records of the price column; possibly with null values
	 * @param timeDelta the seconds per record; -1 or 0 count as one record per
	 *                  quarter
	 * @return one value per quarter-hour; null if a quarter has no value
	 */
	protected static List<Double> averagePerQuarter(List<Double> values, int timeDelta) {
		final var perQuarter = timeDelta > 0 ? Math.max(SECONDS_PER_QUARTER / timeDelta, 1) : 1;
		final var result = new ArrayList<Double>();
		for (var i = 0; i < values.size(); i += perQuarter) {
			var sum = 0.;
			var count = 0;
			for (var j = i; j < Math.min(i + perQuarter, values.size()); j++) {
				final var value = values.get(j);
				if (value != null) {
					sum += value;
					count++;
				}
			}
			result.add(count > 0 ? sum / count : null);
		}
		return result;
	}

	@Override
	public String debugLog() {
		final var prices = this.getPrices();
		final var now = ZonedDateTime.now(this.componentManager.getClock());
		final var price = prices.getAt(now);
		return price == null ? "no price" : String.format("%.1f", price);
	}
}
