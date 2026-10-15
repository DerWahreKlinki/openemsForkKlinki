package io.openems.edge.simulator.timeofusetariff;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Simulator Time-Of-Use Tariff", //
		description = "Provides a Time-Of-Use price curve from a Simulator DataSource. "
				+ "The records of one day are averaged per quarter-hour, start at midnight and repeat for the horizon.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "timeOfUseTariff0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Datasource-ID", description = "ID of Simulator Datasource.")
	String datasource_id() default "datasource0";

	@AttributeDefinition(name = "Key", description = "Column of the Datasource with the grid buy price in [Currency/MWh].")
	String key() default "price";

	@AttributeDefinition(name = "Horizon", description = "Number of days the prices are provided for, starting today.")
	int horizonDays() default 2;

	String webconsole_configurationFactory_nameHint() default "Simulator Time-Of-Use Tariff [{id}]";
}
