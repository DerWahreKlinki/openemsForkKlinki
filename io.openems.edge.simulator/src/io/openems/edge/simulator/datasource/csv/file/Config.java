package io.openems.edge.simulator.datasource.csv.file;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.simulator.CsvFormat;

@ObjectClassDefinition(//
		name = "Simulator DataSource: CSV File", //
		description = "Provides the values of a CSV file. The record is selected by the time of day, "
				+ "so that the values match the real clock and the predictions of the Simulator Predictor.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "datasource0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Filename", description = "Path of the CSV file with a title line of Channel-Addresses "
			+ "(e.g. 'charger0/ActivePower') and one record per Time-Delta, starting at midnight.")
	String filename();

	@AttributeDefinition(name = "Factor", description = "Each value in the csv-file is multiplied by this factor.")
	float factor() default 1;

	@AttributeDefinition(name = "Time-Delta", description = "Time-Delta between two records in seconds; 300 for a day of 288 records.")
	int timeDelta() default 300;

	@AttributeDefinition(name = "Align to clock", description = "Select the record by the time of day. "
			+ "Otherwise the records are played from the start, one per Time-Delta.")
	boolean alignToClock() default true;

	@AttributeDefinition(name = "CSV Format", description = "The format of the CSV file")
	CsvFormat format() default CsvFormat.GERMAN_EXCEL;

	String webconsole_configurationFactory_nameHint() default "Simulator DataSource: CSV File [{id}]";
}
