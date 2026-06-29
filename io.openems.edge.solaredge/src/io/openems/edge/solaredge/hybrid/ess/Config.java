package io.openems.edge.solaredge.hybrid.ess;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import io.openems.edge.solaredge.enums.SetPointMode;

@ObjectClassDefinition(//
		name = "SolarEdge Hybrid Inverter", //
		description = "SolarEdge Hybrid Inverter System - ESS")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ess0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Debug Mode", description = "Activates the debug mode")
	boolean debugMode() default false;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device. ")
	int modbusUnitId() default 14;
	
	@AttributeDefinition(name = "SetPoint", description = "Control at AC (includes PV) or DC setpoint")
	SetPointMode setPointMode() default SetPointMode.DC_SETPOINT;

	@AttributeDefinition(name = "Read-Only mode", description = "Enables Read-Only mode")
	boolean readOnlyMode() default true;

	@AttributeDefinition(name = "Charge Power Limit", description = "Limits the maximum charge power")
	int chargePowerLimit() default 5000;

	@AttributeDefinition(name = "Discharge Power Limit", description = "Limits the maximum discharge power")
	int dischargePowerLimit() default 5000;

	@AttributeDefinition(name = "Feed-to-grid Power Limit", description = "Limits PV-production if limit exceeds ")
	int feedToGridPowerLimit() default 10000;

	@AttributeDefinition(name = "PV Power Limit", description = "Limits PV total production power. Overrides feed-to-grid-limit if lower")
	int maxPvProductionPowerLimit() default 20000;	
	
	@AttributeDefinition(name = "Meter-ID", description = "ID of meter for sell-to-grid-limit")
	String meter_id() default "meter0";

	String webconsole_configurationFactory_nameHint() default "SolarEdge Hybrid Inverter System [{id}]";
}
