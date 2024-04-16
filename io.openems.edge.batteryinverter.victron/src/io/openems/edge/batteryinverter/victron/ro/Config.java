package io.openems.edge.batteryinverter.victron.ro;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.common.startstop.StartStopConfig;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.victron.enums.DeviceType;

@ObjectClassDefinition(//
	name = "Battery-Inverter Victron RO", //
	description = "Implements the Victron battery inverter (read only).")
public @interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "batteryInverter0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
    String alias() default "Victron Multiplus 2 Inverter";

    
	@AttributeDefinition(name = "Phase", description = "true, if three Inverters are configured for master-slave symmetric mode")
	Phase phase() default Phase.L1;

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "Start/stop behaviour?", description = "Should this Component be forced to start or stop?")
    StartStopConfig startStop() default StartStopConfig.AUTO;

    @AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
    String modbus_id() default "modbus0";

    @AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device. Defaults to '100' for Victron Multiplus 2.")
    int modbusUnitId() default 100;
    
    @AttributeDefinition(name = "Inverter Type", description = "Device type of Victron Multiplus 2")
    DeviceType DeviceType() default DeviceType.UNDEFINED;

    @AttributeDefinition(name = "Threshold for DC PV Feed-In in [W]", description = "DC PV Generation below this threshold will not be fed into grid.")
    int dcFeedInThreshold() default 100;
    
	@AttributeDefinition(name = "Max Charge/Discharge Power", description = "max. Charge Power")
	int maxChargePower() default 2000;    
    
    @AttributeDefinition(name = "Debug", description = "Enable debug mode?")
    boolean debugMode() default false;
    
	@AttributeDefinition(name = "Read-Only mode", description = "Enables Read-Only mode")
	boolean readOnlyMode() default false;    
    
    @AttributeDefinition(name = "Victron ESS-ID", description = "ESS-ID to which the batteryinverter is connected to")
    String ess_id() default "ess0";
    
	@AttributeDefinition(name = "Victron ESS target filter", description = "This is auto-generated by 'Victron ESS '.")
	String Ess_target() default "(enabled=true)";


    @AttributeDefinition(name = "Modbus target filter", description = "This is auto-generated by 'Modbus-ID'.")
    String Modbus_target() default "(enabled=true)";

    String webconsole_configurationFactory_nameHint() default "Battery-Inverter Victron RO [{id}]";
}
