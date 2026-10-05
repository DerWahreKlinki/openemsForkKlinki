package io.openems.edge.controller.ess.chargedischargelimiter;

/**
 * What the ActivePower set-point of a DC-coupled hybrid ESS means.
 */
public enum SetPointSemantics {
	/**
	 * Detect automatically: a hybrid ESS whose configuration property
	 * 'setPointMode' is DC_SETPOINT (e.g. SolarEdge) takes battery power,
	 * everything else takes AC power.
	 */
	AUTO,
	/**
	 * The set-point is AC power including PV production (AC = battery + PV).
	 */
	AC_INCLUDING_PV,
	/**
	 * The set-point is battery power only.
	 */
	BATTERY_ONLY;
}
