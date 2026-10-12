package io.openems.edge.controller.evcs.price;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;
import static io.openems.common.utils.JsonUtils.buildJsonObject;
import static java.lang.Math.max;
import static java.lang.Math.min;

import java.util.function.Supplier;

import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.energy.api.handler.EnergyScheduleHandler;
import io.openems.edge.energy.api.simulation.periods.PeriodData.Price;

/**
 * Tells the energy optimizer what the car is expected to draw, so that the
 * Time-of-Use-Tariff controller plans the storage with the car in mind (e.g.
 * delays the discharge for an expensive evening while the car charges from grid
 * at a cheap price). The optimizer only receives this forecast; the decision
 * when and how much the car charges stays with the controller. Without an
 * energy optimizer the handler is never asked and nothing changes.
 */
public class EnergyScheduler {

	/**
	 * The settings and the current state the forecast is built from.
	 *
	 * @param enablePriceCharging charge from grid depending on the price
	 * @param priceLimit          the upper price limit in [Cent/kWh]
	 * @param priceLimitFullPower the lower price limit in [Cent/kWh]
	 * @param priceChargePower    the full charge power by price in [W]
	 * @param priority            the priority of charging
	 * @param minPower            the minimum hardware power in [W]
	 * @param maxPower            the maximum hardware power in [W]
	 * @param pluggedIn           a car is plugged in
	 * @param storageReserveWh    the energy the car may take from the storage
	 *                            (reserve above the evening target) in [Wh]
	 */
	public record Config(boolean enablePriceCharging, double priceLimit, double priceLimitFullPower,
			int priceChargePower, Priority priority, int minPower, int maxPower, boolean pluggedIn,
			int storageReserveWh) {

		/**
		 * Returns a {@link JsonSerializer} for a {@link Config}.
		 *
		 * @return the created {@link JsonSerializer}
		 */
		public static JsonSerializer<Config> serializer() {
			return jsonObjectSerializer(Config.class, json -> {
				return new Config(//
						json.getBoolean("enablePriceCharging"), //
						json.getDouble("priceLimit"), //
						json.getDouble("priceLimitFullPower"), //
						json.getInt("priceChargePower"), //
						json.getEnum("priority", Priority.class), //
						json.getInt("minPower"), //
						json.getInt("maxPower"), //
						json.getBoolean("pluggedIn"), //
						json.getInt("storageReserveWh"));
			}, obj -> {
				return buildJsonObject() //
						.addProperty("enablePriceCharging", obj.enablePriceCharging()) //
						.addProperty("priceLimit", obj.priceLimit()) //
						.addProperty("priceLimitFullPower", obj.priceLimitFullPower()) //
						.addProperty("priceChargePower", obj.priceChargePower()) //
						.addProperty("priority", obj.priority()) //
						.addProperty("minPower", obj.minPower()) //
						.addProperty("maxPower", obj.maxPower()) //
						.addProperty("pluggedIn", obj.pluggedIn()) //
						.addProperty("storageReserveWh", obj.storageReserveWh()) //
						.build();
			});
		}
	}

	/**
	 * Per simulation run: the storage reserve the car has not used yet.
	 */
	public static class ScheduleContext {
		private int remainingReserveWh;

		public ScheduleContext(int storageReserveWh) {
			this.remainingReserveWh = max(storageReserveWh, 0);
		}

		/**
		 * Takes energy from the storage reserve.
		 *
		 * @param energy the energy the car would like to take in [Wh]
		 * @return the energy actually left in the reserve for it in [Wh]
		 */
		public int takeFromReserve(int energy) {
			var taken = min(max(energy, 0), this.remainingReserveWh);
			this.remainingReserveWh -= taken;
			return taken;
		}
	}

	/**
	 * Builds the {@link EnergyScheduleHandler}.
	 *
	 * @param parent         the parent {@link OpenemsComponent}
	 * @param configSupplier supplier for {@link Config}; null while the controller
	 *                       is disabled
	 * @return a {@link EnergyScheduleHandler}
	 */
	public static EnergyScheduleHandler.WithOnlyOneMode buildEnergyScheduleHandler(OpenemsComponent parent,
			Supplier<Config> configSupplier) {
		return EnergyScheduleHandler.WithOnlyOneMode.<Config, ScheduleContext>create(parent) //
				.setSerializer(Config.serializer(), configSupplier) //
				.setOptimizationContext(goc -> configSupplier.get()) //
				.setScheduleContext(() -> {
					var config = configSupplier.get();
					return new ScheduleContext(config == null ? 0 : config.storageReserveWh());
				}) //
				.setSimulator((id, period, gsc, coc, csc, ef, fitness) -> {
					if (coc == null || !coc.pluggedIn()) {
						return;
					}
					final var gridBuyPrice = period.data().gridBuyPrice().map(Price::actual).orElse(null);
					final var surplusPower = period.duration().convertEnergyToPower(ef.getSurplus());
					var power = calculateExpectedChargePower(coc, gridBuyPrice, surplusPower);
					var energy = period.duration().convertPowerToEnergy(power);
					if (energy <= 0 && coc.storageReserveWh() > 0 && csc != null) {
						// Storage reserve: the car takes the minimum power from the storage until
						// the reserve is used up
						energy = csc.takeFromReserve(period.duration().convertPowerToEnergy(coc.minPower()));
					}
					if (energy > 0) {
						ef.addManagedConsumption(id, energy);
					}
				}) //
				.build();
	}

	/**
	 * Calculates the charge power the controller is expected to apply in a
	 * period, from the price and the surplus; the storage reserve is handled
	 * separately.
	 *
	 * @param config       the {@link Config}
	 * @param gridBuyPrice the grid buy price in [Currency/MWh]; possibly null
	 * @param surplusPower production minus consumption in [W]
	 * @return the charge power in [W]
	 */
	protected static int calculateExpectedChargePower(Config config, Double gridBuyPrice, int surplusPower) {
		var power = 0;
		if (config.enablePriceCharging()) {
			power = ControllerEvcsPriceImpl.calculateChargePowerFromPrice(gridBuyPrice, config.priceLimit(),
					config.priceLimitFullPower(), config.minPower(), config.priceChargePower());
		}
		// With priority CAR the car takes the surplus before the storage; with
		// priority STORAGE the storage is filled first and the optimizer decides
		// about the rest
		if (config.priority() == Priority.CAR && surplusPower >= config.minPower()) {
			power = max(power, surplusPower);
		}
		return min(power, config.maxPower());
	}
}
