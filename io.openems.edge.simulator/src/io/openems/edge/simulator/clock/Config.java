package io.openems.edge.simulator.clock;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Simulator Clock", //
		description = "Provides the clock of the Edge: a clock that runs faster than real time, optionally from a "
				+ "given start. Every component that takes its clock from the Component-Manager follows it. "
				+ "For simulations only.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "clock0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Factor", description = "Simulated seconds per real second; 1 = real time.")
	double factor() default 1.0;

	@AttributeDefinition(name = "Start", description = "Simulated time at activation as ISO date-time, e.g. "
			+ "2026-10-22T20:00:00+02:00; empty = the real time.")
	String start() default "";

	String webconsole_configurationFactory_nameHint() default "Simulator Clock [{id}]";
}
