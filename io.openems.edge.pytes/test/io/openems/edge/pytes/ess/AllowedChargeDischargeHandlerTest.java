package io.openems.edge.pytes.ess;

import static io.openems.edge.pytes.ess.AllowedChargeDischargeHandler.chargeLimitWhileCurtailed;
import static io.openems.edge.pytes.ess.AllowedChargeDischargeHandler.minWithInverterLimit;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AllowedChargeDischargeHandlerTest {

	@Test
	public void inverterLimitOnlyLowersTheLimit() {
		assertEquals(40, minWithInverterLimit(40, 50_000)); // BMS/config 40 A, inverter 50 A
		assertEquals(40, minWithInverterLimit(50, 40_000)); // inverter 40 A
		assertEquals(48, minWithInverterLimit(50, 48_300)); // 48.3 A rounded down
	}

	@Test
	public void placeholdersAndMissingValuesAreIgnored() {
		assertEquals(40, minWithInverterLimit(40, null)); // not read yet
		assertEquals(40, minWithInverterLimit(40, 0)); // not set
		assertEquals(40, minWithInverterLimit(40, 999_000)); // 999.0 A placeholder (regs 43012/43013)
		assertEquals(40, minWithInverterLimit(40, 150_001)); // above the datasheet range
	}

	@Test
	public void curtailedPvServesTheHouseBeforeTheBattery() {
		// measured 2026-09-24: PV curtailed to 2629 W, house 1257 W, charge limit
		// -2100 W -> the battery may only take what is left, otherwise the house
		// is served from the grid
		assertEquals(-1372, chargeLimitWhileCurtailed(-2100, 2629, 1257));
		// less PV than the house needs: no charging at all
		assertEquals(0, chargeLimitWhileCurtailed(-2100, 900, 1257));
		// the BMS/config limit still wins when it is the smaller one
		assertEquals(-500, chargeLimitWhileCurtailed(-500, 4000, 1000));
		// without measurements nothing is changed
		assertEquals(-2100, chargeLimitWhileCurtailed(-2100, 2629, null));
	}
}
