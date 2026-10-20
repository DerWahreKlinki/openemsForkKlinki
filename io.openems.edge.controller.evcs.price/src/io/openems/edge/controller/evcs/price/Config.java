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

	@AttributeDefinition(name = "Energy limit in this session in [Wh]", description = "Charge only up to this energy in this session; 0 = no limit.")
	int energySessionLimit() default 0;

	@AttributeDefinition(name = "Start hysteresis [s]", description = "Start only after charging has been allowed for this time without a gap; also the minimum pause.")
	int startConfirmationTime() default 30;

	@AttributeDefinition(name = "Stop hysteresis [s]", description = "Stop only after charging has been denied for this time without a gap; also the minimum charging time.")
	int stopConfirmationTime() default 120;

	@AttributeDefinition(name = "Price-based charging", description = "Charge from grid below the price limits; off = surplus and storage reserve only, never grid.")
	boolean enablePriceCharging() default true;

	@AttributeDefinition(name = "Price limit [Cent/kWh]", description = "Charge from grid below this price: minimum power here, full power at the lower limit; 0 = negative prices only.")
	double priceLimit() default 30;

	@AttributeDefinition(name = "Price limit for full power [Cent/kWh]", description = "At or below this price: full charge power from grid.")
	double priceLimitFullPower() default 20;

	@AttributeDefinition(name = "Full charge power by price [W]", description = "Charge power at or below the 'Price limit for full power'.")
	int priceChargePower() default 11040;

	@AttributeDefinition(name = "Value of surplus power [Cent/kWh]", description = "Value of surplus power that would go to the grid, e.g. the feed-in tariff; counts in the blended price.")
	double pvPrice() default 7;

	@AttributeDefinition(name = "Include storage with foresight", description = "The car may charge what the storage will have above its evening target at the end of PV production.")
	boolean useStorageSurplus() default false;

	@AttributeDefinition(name = "Storage target in the evening [% net]", description = "Target state of charge at the end of PV production, in percent of the usable capacity.")
	int storageTargetSocNet() default 80;

	@AttributeDefinition(name = "Storage loss surcharge [Cent/kWh]", description = "Added to the value of surplus power for storage power; covers the conversion losses.")
	double storageLossSurcharge() default 1;

	@AttributeDefinition(name = "Storage charge efficiency [%]", description = "Share of the PV surplus that arrives in the storage in the projection until the evening; covers the conversion losses.")
	int storageChargeEfficiency() default 92;

	@AttributeDefinition(name = "Time-of-Use-Tariff controller ID", description = "Its state tells whether the storage may discharge: while it delays the discharge or charges from grid, storage power counts like grid power. Empty = not checked.")
	String timeOfUseTariff_id() default "";

	@AttributeDefinition(name = "Target charging", description = "Charge the target energy until the target time in the cheapest quarter-hours, regardless of the price limits; switches itself off at the target time.")
	boolean targetCharging() default false;

	@AttributeDefinition(name = "Target energy [Wh]", description = "Energy to charge from switching target charging on until the target time.")
	int targetEnergy() default 10000;

	@AttributeDefinition(name = "Target time [min]", description = "Time of day as minutes after midnight, e.g. 420 = 07:00; the next occurrence counts.")
	int targetTime() default 420;

	@AttributeDefinition(name = "Ess-ID for the storage lock", description = "Storage (e.g. the cluster) that target charging locks for the car while it is below its evening target: its discharge is capped to the house load, the car charges from grid. Empty = no lock.")
	String ess_id() default "essCluster";

	@AttributeDefinition(name = "Session start energy [Wh]", description = "Counter of the wallbox when the car was plugged in; the session limit counts from there. Set by the controller itself, -1 = no car plugged in.")
	int sessionStartEnergy() default -1;

	@AttributeDefinition(name = "Target start energy [Wh]", description = "Session energy of the wallbox when target charging was switched on; the target counts from there. Set by the controller itself, -1 = not yet set.")
	int targetStartEnergy() default -1;

	String webconsole_configurationFactory_nameHint() default "Controller Electric Vehicle Charging Station with Price Limit [{id}]";

}