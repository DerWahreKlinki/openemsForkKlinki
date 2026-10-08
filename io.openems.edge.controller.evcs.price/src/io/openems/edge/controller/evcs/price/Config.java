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

	@AttributeDefinition(name = "Start confirmation time [s]", description = "Charging is only started after the charge power has been above zero for this time (Seconds). Avoids starts on a single wrong measurement, e.g. right after a restart when grid and storage values lag behind; '0' starts immediately.")
	int startConfirmationTime() default 10;

	@AttributeDefinition(name = "Stop confirmation time [s]", description = "Charging is only paused after the charge power has been zero for this time (Seconds), in addition to the 'Minimum charging time'. A short cloud must not end a charge process that would then need the start confirmation again. '0' pauses at once.")
	int stopConfirmationTime() default 0;

	@AttributeDefinition(name = "Price-based charging", description = "In excess power mode: charge from grid depending on the grid buy price (the two price limits below), and fill up the minimum hardware power from grid while the blended price of surplus, storage and grid power is below the 'Price limit'. Switched off, grid power is never taken because of its price: the car charges only from surplus power that would otherwise be fed into the grid and, if included, from the storage reserve; the price limits have no effect.")
	boolean enablePriceCharging() default true;

	@AttributeDefinition(name = "Price limit [Cent/kWh]", description = "In excess power mode with price-based charging: charge from grid if the grid buy price is below this limit. Starts with the minimum hardware power at this limit and increases linearly down to the 'Price limit for full power'. A limit of 0 charges only at negative prices.")
	double priceLimit() default 30;

	@AttributeDefinition(name = "Price limit for full power [Cent/kWh]", description = "In excess power mode with price-based charging: charge from grid with full power if the grid buy price is at or below this limit; includes negative prices.")
	double priceLimitFullPower() default 20;

	@AttributeDefinition(name = "Full charge power by price [W]", description = "Charge power in Watt that is applied while the grid buy price is at or below the 'Price limit for full power'.")
	int priceChargePower() default 11040;

	@AttributeDefinition(name = "Value of surplus power [Cent/kWh]", description = "Opportunity cost of surplus power that would otherwise be fed into the grid, e.g. the feed-in tariff. In excess power mode with price-based charging: if the surplus is below the minimum hardware power, the missing power is taken from grid as long as the blended price of surplus and grid power is below the 'Price limit'.")
	double pvPrice() default 7;

	@AttributeDefinition(name = "Include storage with foresight", description = "In excess power mode: the energy of the storage is projected until the end of PV production (production minus consumption, minus what the car takes from the surplus, quarter-hour by quarter-hour). If it ends above the evening target, the car may charge that surplus from the storage already now, even without PV surplus; storage power is then priced like PV power (plus loss surcharge) in the blended price. Otherwise storage power is priced like grid power. The storage price is always calculated and available as channel.")
	boolean useStorageSurplus() default false;

	@AttributeDefinition(name = "Storage target in the evening [% net]", description = "State of charge the storage should have at the end of PV production, in percent of the usable capacity (within the SoC windows of the ChargeDischargeLimiters, if present). Power from the storage is priced like PV power only while the projected state at the end of PV production - with the car drawing what it draws now - stays above this target.")
	int storageTargetSocNet() default 80;

	@AttributeDefinition(name = "Storage loss surcharge [Cent/kWh]", description = "Added to the 'Value of PV power' for power from the storage that will be refilled by PV surplus; covers the conversion losses.")
	double storageLossSurcharge() default 1;

	String webconsole_configurationFactory_nameHint() default "Controller Electric Vehicle Charging Station with Price Limit [{id}]";

}