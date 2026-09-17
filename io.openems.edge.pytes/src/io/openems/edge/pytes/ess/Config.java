package io.openems.edge.pytes.ess;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.pytes.enums.EnableDisable;
import io.openems.edge.pytes.enums.RemoteDispatchRealtimeControlSwitch;
import io.openems.edge.pytes.enums.WorkMode;

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

	@AttributeDefinition(name = "WorkMode", description = "Work Mode. ExternalMode -> device is controlled by OpenEMS")
	WorkMode workMode() default WorkMode.EXTERNAL;

	@AttributeDefinition(name = "ESS SetPoint", description = "How the OpenEMS AC set-point is applied: BATTERY_CONTROL = EMS computes and sets the battery power (with bias/loss compensation); AC_OUTPUT_CONTROL = inverter regulates its own AC output to the set-point. Note: with AC_OUTPUT_CONTROL the OpenEMS-side battery current limits are not enforced by the inverter.")
	RemoteDispatchRealtimeControlSwitch essSetpoint() default RemoteDispatchRealtimeControlSwitch.BATTERY_CONTROL;

	@AttributeDefinition(name = "Max. Apparent Power", description = "Inverter´s apparent power limit")
	int maxApparentPower() default 10000;

	@AttributeDefinition(name = "Min SoC [5-100%]", description = "NOT applied: the SoC limits configured in the inverter are authoritative. Kept for a possible opt-in (see PytesJs3Impl.setDefaultValues()).")
	int minSoc() default 10;

	@AttributeDefinition(name = "Enable Backup Port", description = "NOT applied: the backup port setting configured in the inverter is authoritative. Kept for a possible opt-in (see PytesJs3Impl.setDefaultValues()).")
	boolean enableBackupPort() default true;

	@AttributeDefinition(name = "Failsafe timeout [min]", description = "Minutes without an EMS write after which the inverter falls back to its own self-use mode (reg 44101, 1-1440). Keep it short if loads on the backup port depend on the EMS.")
	int failsafeMinutes() default 5;

	@AttributeDefinition(name = "Feed-in limitation", description = "Write the grid feed-in hard limit from Core.Meta (gridFeedInLimitationType / maximumGridFeedInLimit) into the inverter (reg 44102/44104) as hardware backstop. The inverter then limits the export at its grid meter and curtails PV if the battery cannot absorb the surplus. The dynamic part is done by controllers (e.g. GridOptimizedCharge).")
	EnableDisable feedPowerEnable() default EnableDisable.DISABLE;

	String webconsole_configurationFactory_nameHint() default "io.openems.edge.pytes [{id}]";

	@AttributeDefinition(name = "Debug Mode", description = "Activates the debug mode")
	boolean debugMode() default false;

	@AttributeDefinition(name = "Extended Debug mode", description = "Enables extended Debug mode")
	boolean extendedDebugMode() default false;

	@AttributeDefinition(name = "TEST: AC output limit [%]", description = "Writes reg 43052 (limited power, % of rated power, 0-110) once on activation; -1 = do not write. Test step for a dynamic feed-in limitation: the inverter should curtail PV instead of overcharging the battery while still following the EMS set-point.")
	int acOutputLimitPercent() default -1;

	@AttributeDefinition(name = "ReadOnly Mode", description = "read only mode")
	boolean readOnlyMode() default false;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device. ")
	int modbusUnitId() default 1;

}