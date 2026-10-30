package io.openems.edge.ess.energyprice;

/**
 * The content of an ESS with its volume-weighted average purchase price.
 *
 * <p>
 * Charging adds energy at the price of the moment, discharging removes energy
 * at the average price, the calibration to the measured content scales the
 * inventory. Only charging changes the price. Losses are taken symmetrically:
 * the square root of the round-trip efficiency on each way, so the price of a
 * delivered kWh is the purchase price divided by the round-trip efficiency.
 *
 * <p>
 * Energy is in [Wh], prices in [Currency/MWh], cost in [Currency]. Content with
 * unknown price (the content at start, energy charged without a price) is kept
 * apart: it is not part of the price, but shrinks with the rest.
 */
public class Inventory {

	private static final double MIN_EFFICIENCY = 0.5;
	private static final double MAX_EFFICIENCY = 1.0;
	// Below this content the price is not meaningful
	private static final double MIN_PRICED_WH = 1.0;

	private double energyWh = 0;
	private double pricedWh = 0;
	private double gridWh = 0;
	private double pvWh = 0;
	private double cost = 0;

	/**
	 * Adds charged energy.
	 *
	 * @param chargedWh    the energy charged into the ESS [Wh]
	 * @param gridWh       the part of it bought from the grid [Wh]
	 * @param price        the grid buy price [Currency/MWh]; null = unknown, the
	 *                     energy counts without price
	 * @param feedInTariff the value of the PV part [Currency/MWh]
	 * @param efficiency   the round-trip efficiency (0.5 .. 1)
	 */
	public void charge(double chargedWh, double gridWh, Double price, double feedInTariff, double efficiency) {
		if (chargedWh <= 0) {
			return;
		}
		final var factor = Math.sqrt(efficiency);
		final var stored = chargedWh * factor;
		this.energyWh += stored;
		if (price == null) {
			return;
		}
		final var grid = Math.max(0, Math.min(gridWh, chargedWh));
		final var pv = chargedWh - grid;
		this.cost += (grid * price + pv * feedInTariff) / 1_000_000.;
		this.pricedWh += stored;
		this.gridWh += grid * factor;
		this.pvWh += pv * factor;
	}

	/**
	 * Removes delivered energy at the average price.
	 *
	 * @param deliveredWh the energy delivered by the ESS [Wh]
	 * @param efficiency  the round-trip efficiency (0.5 .. 1)
	 */
	public void discharge(double deliveredWh, double efficiency) {
		if (deliveredWh <= 0 || this.energyWh <= 0) {
			return;
		}
		final var removed = Math.min(this.energyWh, deliveredWh / Math.sqrt(efficiency));
		this.scale((this.energyWh - removed) / this.energyWh);
	}

	/**
	 * Calibrates the content to the measured content of the ESS. The price stays:
	 * the difference is drift of the measurement, not a purchase.
	 *
	 * @param measuredWh the measured content [Wh], e.g. SoC times capacity
	 */
	public void calibrate(double measuredWh) {
		final var measured = Math.max(0, measuredWh);
		if (this.energyWh <= 0) {
			this.energyWh = measured;
			return;
		}
		this.scale(measured / this.energyWh);
	}

	private void scale(double factor) {
		this.energyWh *= factor;
		this.pricedWh *= factor;
		this.gridWh *= factor;
		this.pvWh *= factor;
		this.cost *= factor;
	}

	/**
	 * Gets the average purchase price of a stored kWh.
	 *
	 * @return the price [Currency/MWh]; null while nothing priced is stored
	 */
	public Double storedPrice() {
		if (this.pricedWh < MIN_PRICED_WH) {
			return null;
		}
		return this.cost * 1_000_000. / this.pricedWh;
	}

	/**
	 * Gets the price of a delivered kWh: the purchase price plus the losses on the
	 * way out.
	 *
	 * @param efficiency the round-trip efficiency (0.5 .. 1)
	 * @return the price [Currency/MWh]; null while nothing priced is stored
	 */
	public Double price(double efficiency) {
		final var storedPrice = this.storedPrice();
		if (storedPrice == null) {
			return null;
		}
		return storedPrice / Math.sqrt(efficiency);
	}

	/**
	 * Gets the content.
	 *
	 * @return the energy [Wh]
	 */
	public double energyWh() {
		return this.energyWh;
	}

	/**
	 * Gets the part of the content with a known price.
	 *
	 * @return the energy [Wh]
	 */
	public double pricedWh() {
		return this.pricedWh;
	}

	/**
	 * Gets the part of the content without a known price.
	 *
	 * @return the energy [Wh]
	 */
	public double unpricedWh() {
		return Math.max(0, this.energyWh - this.pricedWh);
	}

	/**
	 * Gets the part of the content bought from the grid.
	 *
	 * @return the energy [Wh]
	 */
	public double gridWh() {
		return this.gridWh;
	}

	/**
	 * Gets the part of the content charged from PV.
	 *
	 * @return the energy [Wh]
	 */
	public double pvWh() {
		return this.pvWh;
	}

	/**
	 * Gets the purchase cost of the priced content.
	 *
	 * @return the cost [Currency]
	 */
	public double cost() {
		return this.cost;
	}

	/**
	 * Calculates the round-trip efficiency from the energy counters of a period,
	 * corrected by the change of the content: discharged / (charged - change of
	 * content).
	 *
	 * @param chargedWh    the energy charged in the period [Wh]
	 * @param dischargedWh the energy discharged in the period [Wh]
	 * @param socFrom      the SoC at the start of the period [%]
	 * @param socTo        the SoC at the end of the period [%]
	 * @param capacityWh   the capacity [Wh]
	 * @return the efficiency (0.5 .. 1); null if the period gives no usable value
	 */
	public static Double calculateEfficiency(double chargedWh, double dischargedWh, Integer socFrom, Integer socTo,
			Integer capacityWh) {
		if (chargedWh <= 0 || dischargedWh < 0 || socFrom == null || socTo == null || capacityWh == null
				|| capacityWh <= 0) {
			return null;
		}
		final var contentChange = (socTo - socFrom) / 100. * capacityWh;
		final var consumed = chargedWh - contentChange;
		if (consumed <= 0) {
			return null;
		}
		return Math.max(MIN_EFFICIENCY, Math.min(MAX_EFFICIENCY, dischargedWh / consumed));
	}
}
