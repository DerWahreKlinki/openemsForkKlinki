package io.openems.edge.pytes.ess;

import static io.openems.edge.common.channel.ChannelUtils.setValue;

import org.slf4j.Logger;

import io.openems.edge.battery.api.Battery;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.common.component.ClockProvider;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.generic.common.AbstractAllowedChargeDischargeHandler;
import io.openems.edge.pytes.battery.PytesBattery;
import io.openems.edge.pytes.dccharger.PytesDcCharger;
import io.openems.edge.pytes.enums.RemoteDispatchRealtimeControlSwitch;

public class AllowedChargeDischargeHandler extends AbstractAllowedChargeDischargeHandler<PytesJs3Impl> {

	private final PytesBattery battery;
	private final PytesDcCharger dcCharger;
	private final Logger log;

	public AllowedChargeDischargeHandler(PytesJs3Impl parent, PytesBattery battery, PytesDcCharger dcCharger, RemoteDispatchRealtimeControlSwitch essSetpoint) {
		super(parent);
		this.battery = battery;
		this.dcCharger = dcCharger;
		this.log = this.parent.getLogger();
	}

	@Override
	public void accept(ClockProvider clockProvider, Battery battery, SymmetricBatteryInverter inverter) {

		if (battery == null) {

			this._setAllowedChargePower(0);
			parent._setAllowedDischargePower(0);
			return;
		}
		this.accept(clockProvider);
	}

	/**
	 * Calculates AllowedChargePower and AllowedDischargePower and sets the
	 * Channels.
	 *
	 * <p>
	 * Semantics (both derived from the BMS current limits):
	 * <ul>
	 * <li>AllowedChargePower is the DC-side battery limit (negative or 0). The
	 * AC side can not go below limit + PV; that part is expressed via
	 * {@code getSurplusPower()}.</li>
	 * <li>AllowedDischargePower is AC-side: battery limit + PV, capped by
	 * MaxApparentPower. {@code ApplyPowerHandler} subtracts PV again for the
	 * battery set-point.</li>
	 * </ul>
	 *
	 * @param clockProvider a {@link ClockProvider}
	 */
	public void accept(ClockProvider clockProvider) {

		if (this.battery == null) {
		    this._setAllowedChargePower(0);
		    parent._setAllowedDischargePower(0);
		    return;

		}

		Integer batteryMaxChargeCurrent = this.battery.getBmsChargeCurrentLimit().get(); // mA
		Integer batteryMaxDischargeCurrent = this.battery.getBmsDischargeCurrentLimit().get(); // mA

		Integer batteryVoltage = this.battery.getBatteryVoltage().get(); // mV. NOT the battery nature
		
		Integer maxApparentPower = parent.getMaxApparentPower().get();

		if (batteryMaxChargeCurrent == null ||  batteryMaxDischargeCurrent == null || batteryVoltage == null || maxApparentPower == null) {
			this.parent.debugLog("[AllowChargeDischarge Handler] values not available yet, setting 0 W");

			this._setAllowedChargePower(0);
			this.parent._setAllowedDischargePower(0);
			return;
		}

		// mA -> A, rounded towards the safe side (never above the BMS limit)
		batteryMaxChargeCurrent = (int) Math.floor(batteryMaxChargeCurrent / 1000.0);
		batteryMaxDischargeCurrent = (int) Math.floor(batteryMaxDischargeCurrent / 1000.0);



		Integer configuredMaxChargeCurrent = this.battery.getConfiguredMaxChargeCurrent(); // A
		Integer configuredMaxDischargeCurrent = this.battery.getConfiguredMaxDischargeCurrent();

		int maxChargeCurrent = (int)    Math.min(configuredMaxChargeCurrent,batteryMaxChargeCurrent);
		int maxDischargeCurrent = (int)    Math.min(configuredMaxDischargeCurrent,batteryMaxDischargeCurrent);

		int allowedChargePower = (int) Math.min(0, Math.ceil(Math.round((maxChargeCurrent * batteryVoltage * -1) / 1000.0))); // Voltage is mV
		int allowedDischargePower = (int) Math.max(0, Math.floor(Math.round((maxDischargeCurrent * batteryVoltage) / 1000.0)));

		this.parent.debugLog("[AllowChargeDischarge Handler] max. ChargeCurrent  " + maxChargeCurrent
		+ "A maxDischargeCurrent: " + maxDischargeCurrent
		+ "A Voltage:"  + batteryVoltage
		+ "V Allowed Charge Power " + allowedChargePower
		+ "W/Allowed Discharge Power " + allowedDischargePower

		 );

		// PV production straight from the charger (ActivePower - DcDischargePower
		// is the same value one cycle later)
		int pvProduction = this.dcCharger != null ? Math.max(0, this.dcCharger.getActualPower().orElse(0)) : 0;

		// Report what actually arrives on the AC side: the inverter delivers
		// BIAS_W less battery power than commanded and conversion losses sit in
		// between (see ApplyPowerHandler). Without this the solver asks for e.g.
		// 2000 W although only ~1700 W are achievable at a 2112 W BMS limit.
		// Charging is left as-is (the bias works in favour there).
		this.parent.setBatteryDischargeLimit(allowedDischargePower); // raw BMS limit for the set-point clamp
		if (allowedDischargePower > 0) {
			allowedDischargePower = Math.max(0, allowedDischargePower - ApplyPowerHandler.BIAS_W
					- ApplyPowerHandler.expectedLosses(allowedDischargePower, pvProduction));
		}
		// Apply AllowedChargePower and AllowedDischargePower
		this._setAllowedChargePower((int) allowedChargePower); // 0 or negative
		this.parent._setAllowedDischargePower((int) Math.min(maxApparentPower, allowedDischargePower + pvProduction)); // positive
	}

	
	// 2026 03 26 Helper to set allowed charge power via new method
	private void _setAllowedChargePower(int allowedChargePower) {
		setValue(this.parent, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER,
				allowedChargePower);
	}	
	
}
