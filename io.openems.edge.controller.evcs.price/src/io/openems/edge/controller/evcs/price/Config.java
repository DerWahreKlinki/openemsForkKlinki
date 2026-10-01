package io.openems.edge.controller.evcs.price;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.evcs.api.ChargeMode;

@ObjectClassDefinition(//
		name = "Controller Electric Vehicle Charging Station with Price Limit", //
		description = "Limits the maximum charging power of an electric vehicle charging station. Charges with surplus power or from grid if the grid buy price is below a limit.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlEvcs0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Debug Mode", description = "Activates the debug mode")
	boolean debugMode() default false;

	@AttributeDefinition(name = "Evcs-ID", description = "ID of Evcs device (Has to be managed).", required = true)
	String evcs_id() default "evcs0";

	@AttributeDefinition(name = "Enabled charging", description = "Activates or deactivates the Charging.")
	boolean enabledCharging() default true;

	@AttributeDefinition(name = "Charge-Mode", description = "Set the charge-mode.")
	ChargeMode chargeMode() default ChargeMode.FORCE_CHARGE;

	@AttributeDefinition(name = "Force-charge minimum power [W] per Phase", description = "Set the minimum power for the force charge mode in Watt per Phase.")
	int forceChargeMinPower() default 7360;

	@AttributeDefinition(name = "Default-charge minimum power [W]", description = "Set the minimum power for the default charge mode in Watt.")
	int defaultChargeMinPower() default 0;

	@AttributeDefinition(name = "Priority of charging", description = "Decide which Component should be preferred.")
	Priority priority() default Priority.CAR;

	@AttributeDefinition(name = "Energy limit in this session in [Wh]", description = "Set the Energylimit in this Session in Wh. The charging station will only charge till this limit; '0' is no limit.")
	int energySessionLimit() default 0;

	@AttributeDefinition(name = "Minimum charging time while charging with excess power", description = "Minimum time (Seconds) is applied to avoid continuous switching between charging and not charging")
	int excessChargeHystersis() default 120;

	@AttributeDefinition(name = "Minimum pause time while charging with excess power", description = "Minimum time (Seconds) is applied to avoid continuous switching between charging and not charging")
	int excessChargePauseHysteresis() default 30;

	@AttributeDefinition(name = "Price limit [Cent/kWh]", description = "In excess power mode: charge from grid if the grid buy price is below this limit. Starts with the minimum hardware power at this limit and increases linearly down to the 'Price limit for full power'; '0' deactivates charging by price.")
	double priceLimit() default 30;

	@AttributeDefinition(name = "Price limit for full power [Cent/kWh]", description = "In excess power mode: charge from grid with full power if the grid buy price is at or below this limit; includes negative prices.")
	double priceLimitFullPower() default 20;

	@AttributeDefinition(name = "Full charge power by price [W]", description = "Charge power in Watt that is applied while the grid buy price is at or below the 'Price limit for full power'.")
	int priceChargePower() default 11040;

	String webconsole_configurationFactory_nameHint() default "Controller Electric Vehicle Charging Station with Price Limit [{id}]";

}