package io.openems.edge.ess.energyprice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class InventoryTest {

	private static final double DELTA = 0.01;

	@Test
	public void chargeFromPvCountsAtFeedInTariff() {
		final var sut = new Inventory();
		sut.charge(1000, 0, 300., 70, 1.0);
		assertEquals(1000, sut.energyWh(), DELTA);
		assertEquals(1000, sut.pvWh(), DELTA);
		assertEquals(0, sut.gridWh(), DELTA);
		assertEquals(0.07, sut.cost(), DELTA);
		assertEquals(70, sut.storedPrice(), DELTA);
		assertEquals(70, sut.price(1.0), DELTA);
	}

	@Test
	public void lossesRaiseThePriceOfTheDeliveredEnergy() {
		final var sut = new Inventory();
		// 1000 Wh bought at 300, 81 % round trip: 900 Wh arrive, 810 Wh come out
		sut.charge(1000, 1000, 300., 70, 0.81);
		assertEquals(900, sut.energyWh(), DELTA);
		assertEquals(900, sut.gridWh(), DELTA);
		assertEquals(0.3, sut.cost(), DELTA);
		assertEquals(333.33, sut.storedPrice(), DELTA);
		assertEquals(300 / 0.81, sut.price(0.81), DELTA);
	}

	@Test
	public void mixesTheSourcesAndDischargingKeepsThePrice() {
		final var sut = new Inventory();
		sut.charge(1000, 1000, 300., 70, 1.0);
		sut.charge(1000, 0, 300., 70, 1.0);
		assertEquals(185, sut.price(1.0), DELTA);
		sut.discharge(500, 1.0);
		assertEquals(1500, sut.energyWh(), DELTA);
		assertEquals(750, sut.gridWh(), DELTA);
		assertEquals(750, sut.pvWh(), DELTA);
		assertEquals(185, sut.price(1.0), DELTA);
		// the grid part was taken partly from the grid, partly from PV: the price
		// is the mix of what is there
		sut.charge(500, 500, 100., 70, 1.0);
		assertEquals(2000, sut.energyWh(), DELTA);
		assertEquals((1500 * 185 + 500 * 100) / 2000., sut.price(1.0), DELTA);
	}

	@Test
	public void calibrationKeepsThePrice() {
		final var sut = new Inventory();
		sut.charge(2000, 2000, 185., 70, 1.0);
		sut.calibrate(1000);
		assertEquals(1000, sut.energyWh(), DELTA);
		assertEquals(0.185, sut.cost(), DELTA);
		assertEquals(185, sut.price(1.0), DELTA);
		sut.calibrate(3000);
		assertEquals(3000, sut.energyWh(), DELTA);
		assertEquals(185, sut.price(1.0), DELTA);
	}

	@Test
	public void unknownContentIsNotPartOfThePrice() {
		final var sut = new Inventory();
		sut.calibrate(5000);
		assertNull(sut.price(1.0));
		assertEquals(5000, sut.unpricedWh(), DELTA);
		sut.charge(1000, 1000, 200., 70, 1.0);
		assertEquals(6000, sut.energyWh(), DELTA);
		assertEquals(5000, sut.unpricedWh(), DELTA);
		assertEquals(200, sut.price(1.0), DELTA);
		// discharging takes from both parts alike
		sut.discharge(3000, 1.0);
		assertEquals(3000, sut.energyWh(), DELTA);
		assertEquals(2500, sut.unpricedWh(), DELTA);
		assertEquals(500, sut.pricedWh(), DELTA);
		assertEquals(200, sut.price(1.0), DELTA);
		// charged without price: unknown as well
		sut.charge(1000, 0, null, 70, 1.0);
		assertEquals(4000, sut.energyWh(), DELTA);
		assertEquals(3500, sut.unpricedWh(), DELTA);
		assertEquals(200, sut.price(1.0), DELTA);
	}

	@Test
	public void dischargingAnEmptyInventoryDoesNothing() {
		final var sut = new Inventory();
		sut.discharge(1000, 1.0);
		assertEquals(0, sut.energyWh(), DELTA);
		assertNull(sut.price(1.0));
		sut.charge(100, 100, 300., 70, 1.0);
		sut.discharge(1000, 1.0);
		assertEquals(0, sut.energyWh(), DELTA);
		assertNull(sut.price(1.0));
	}

	@Test
	public void efficiencyIsCorrectedByTheChangeOfTheContent() {
		// live 26.09.-25.10.2026, SolarEdge: 152 kWh in, 133 kWh out, SoC 78 -> 43 %
		// of 8294 Wh
		assertEquals(0.857, Inventory.calculateEfficiency(152272, 132960, 78, 43, 8294), 0.001);
		// equal SoC: plain ratio
		assertEquals(0.8, Inventory.calculateEfficiency(1000, 800, 50, 50, 10000), DELTA);
		// the content rose by 500 Wh: only 500 Wh were consumed
		assertEquals(0.8, Inventory.calculateEfficiency(1000, 400, 50, 55, 10000), DELTA);
		// nonsense gives no value or is clamped
		assertNull(Inventory.calculateEfficiency(0, 400, 50, 50, 10000));
		assertNull(Inventory.calculateEfficiency(1000, 400, 50, 60, 10000));
		assertNull(Inventory.calculateEfficiency(1000, 400, null, 50, 10000));
		assertEquals(1.0, Inventory.calculateEfficiency(1000, 1200, 50, 50, 10000), DELTA);
		assertEquals(0.5, Inventory.calculateEfficiency(1000, 100, 50, 50, 10000), DELTA);
	}
}
