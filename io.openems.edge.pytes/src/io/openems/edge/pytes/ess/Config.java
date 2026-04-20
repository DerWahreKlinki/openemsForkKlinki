package io.openems.edge.pytes.ess;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.pytes.enums.RemoteDispatchRealtimeControlSwitch;

@ObjectClassDefinition(//
		name = "Pytes Hybrid Inverter", //
		description = "Pytes Hybrid Inverter")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ess0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "StandBy", description = "Set the device to standby")
	boolean standBy() default false;

	@AttributeDefinition(name = "ESS SetPoint", description = "SetPoint")
	RemoteDispatchRealtimeControlSwitch essSetpoint() default RemoteDispatchRealtimeControlSwitch.BATTERY_CONTROL;

	@AttributeDefinition(name = "Max. Apparent Power", description = "Inverter´s apparent power limit")
	int maxApparentPower() default 10000;

	@AttributeDefinition(name = "Min SoC [5-100%]", description = "Minimum SoC. This value is written to hardware")
	int minSoc() default 10;

	@AttributeDefinition(name = "Enable Backup Port", description = "Activate Backup Port")
	boolean enableBackupPort() default true;

	String webconsole_configurationFactory_nameHint() default "io.openems.edge.pytes [{id}]";

	@AttributeDefinition(name = "Debug Mode", description = "Activates the debug mode")
	boolean debugMode() default false;

	@AttributeDefinition(name = "Extended Debug mode", description = "Enables extended Debug mode")
	boolean extendedDebugMode() default false;	
	
	@AttributeDefinition(name = "Automatic Mode", description = "automatic mode - no export to grid")
	boolean automaticMode() default false;
	
	@AttributeDefinition(name = "ReadOnly Mode", description = "read only mode")
	boolean readOnlyMode() default false;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device. ")
	int modbusUnitId() default 1;

}