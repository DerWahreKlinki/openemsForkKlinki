package io.openems.edge.controller.evcs.price;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;
import static io.openems.common.utils.JsonUtils.buildJsonObject;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;

import com.google.gson.JsonArray;

import io.openems.common.jsonrpc.serialization.JsonElementPath;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.energy.api.handler.EnergyScheduleHandler;

/**
 * Tells the energy optimizer what the car takes.
 *
 * <p>
 * The car is a consumer for the optimizer only while it actually charges: the
 * charging plan of the controller gives the energy per quarter-hour from the
 * start of the plan on. Before the start and after the end of charging the car
 * is not part of the optimization, because nobody knows whether and when it
 * charges.
 */
public class EnergyScheduler {

	/** Seconds per quarter-hour. */
	private static final int QUARTER_SECONDS = 15 * 60;

	/**
	 * The optimization context: what the optimizer knows about the car.
	 *
	 * @param charging  true while the car charges
	 * @param planStart the start of the plan as epoch seconds; the first quarter-hour
	 *                  of planWh
	 * @param planWh    the planned energy per quarter-hour in [Wh]
	 */
	public record Config(boolean charging, long planStart, List<Integer> planWh) {

		/**
		 * Gets the planned energy for a period.
		 *
		 * @param from the start of the period
		 * @param to   the end of the period, exclusive
		 * @return the energy in [Wh]; 0 outside of the plan
		 */
		public int energyBetween(ZonedDateTime from, ZonedDateTime to) {
			if (this.planWh == null || this.planWh.isEmpty()) {
				return 0;
			}
			var result = 0;
			for (var t = from.toEpochSecond(); t < to.toEpochSecond(); t += QUARTER_SECONDS) {
				final var index = Math.floorDiv(t - this.planStart, QUARTER_SECONDS);
				if (index >= 0 && index < this.planWh.size()) {
					final var wh = this.planWh.get((int) index);
					result += wh == null ? 0 : wh;
				}
			}
			return result;
		}

		/**
		 * Returns a {@link JsonSerializer} for {@link Config}.
		 *
		 * @return the serializer
		 */
		public static JsonSerializer<Config> serializer() {
			return jsonObjectSerializer(Config.class, json -> {
				return new Config(//
						json.getBoolean("charging"), //
						json.getLong("planStart"), //
						json.getList("planWh", JsonElementPath::getAsInt));
			}, obj -> {
				final var planWh = new JsonArray();
				for (var wh : obj.planWh()) {
					planWh.add(wh);
				}
				return buildJsonObject() //
						.addProperty("charging", obj.charging()) //
						.addProperty("planStart", obj.planStart()) //
						.add("planWh", planWh) //
						.build();
			});
		}
	}

	/**
	 * Builds the {@link EnergyScheduleHandler}.
	 *
	 * @param parent         the parent {@link OpenemsComponent}
	 * @param configSupplier supplier for {@link Config}; null while the controller
	 *                       is not active
	 * @return the handler
	 */
	public static EnergyScheduleHandler.WithOnlyOneMode buildEnergyScheduleHandler(OpenemsComponent parent,
			Supplier<Config> configSupplier) {
		return EnergyScheduleHandler.WithOnlyOneMode.<Config, Void>create(parent) //
				.setSerializer(Config.serializer(), configSupplier) //
				.setOptimizationContext(goc -> configSupplier.get()) //
				.setSimulator((id, period, gsc, coc, csc, ef, fitness) -> {
					if (coc == null || !coc.charging()) {
						return;
					}
					final var energy = coc.energyBetween(period.time(), period.time().plus(period.duration().duration));
					if (energy > 0) {
						ef.addManagedConsumption(id, energy);
					}
				}) //
				.build();
	}
}
