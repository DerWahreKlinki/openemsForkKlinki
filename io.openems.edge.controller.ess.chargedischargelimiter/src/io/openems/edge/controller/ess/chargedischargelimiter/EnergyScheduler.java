package io.openems.edge.controller.ess.chargedischargelimiter;

import static io.openems.common.jsonrpc.serialization.JsonSerializerUtil.jsonObjectSerializer;
import static io.openems.common.utils.JsonUtils.buildJsonObject;
import static java.lang.Math.max;
import static java.lang.Math.min;

import java.util.function.Supplier;

import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.energy.api.handler.EnergyScheduleHandler;

/**
 * Tells the energy optimizer the SoC window of the ESS in energy and the power
 * it allows right now. The window is given in [Wh] of the ESS the optimizer
 * plans with: for a limiter on an ESS cluster the sum over the members. A
 * negative power means unknown: no limit.
 */
public class EnergyScheduler {

	/**
	 * Builds the {@link EnergyScheduleHandler}.
	 *
	 * <p>
	 * This is public so that it can be used by the EnergyScheduler integration
	 * test.
	 *
	 * @param parent         the parent {@link OpenemsComponent}
	 * @param configSupplier supplier for the {@link Config}
	 * @return a {@link EnergyScheduleHandler}
	 */
	public static EnergyScheduleHandler.WithOnlyOneMode buildEnergyScheduleHandler(OpenemsComponent parent,
			Supplier<Config> configSupplier) {
		return EnergyScheduleHandler.WithOnlyOneMode.<Config, Void>create(parent)
				.setSerializer(Config.serializer(), configSupplier)
				.setOptimizationContext(gsc -> configSupplier.get())
				.setSimulator((id, period, gsc, coc, csc, ef, fitness) -> {
					if (coc == null) {
						return;
					}
					var currentEnergy = gsc.ess.getInitialEnergy();
					// the window
					var allowedDischarge = max(0, currentEnergy - coc.minEnergy());
					var allowedCharge = max(0, coc.maxEnergy() - currentEnergy);
					// the power allowed right now, as energy of this period
					if (coc.maxDischargePower() >= 0) {
						allowedDischarge = min(allowedDischarge,
								period.duration().convertPowerToEnergy(coc.maxDischargePower()));
					}
					if (coc.maxChargePower() >= 0) {
						allowedCharge = min(allowedCharge, period.duration().convertPowerToEnergy(coc.maxChargePower()));
					}
					ef.setEssMaxDischarge(allowedDischarge);
					ef.setEssMaxCharge(allowedCharge);
				})
				.build();
	}

	/**
	 * The report to the optimizer.
	 *
	 * @param minEnergy        lower end of the SoC window in [Wh]
	 * @param maxEnergy        upper end of the SoC window in [Wh]
	 * @param maxChargePower   charge power allowed right now in [W]; negative for
	 *                         unknown
	 * @param maxDischargePower discharge power allowed right now in [W]; negative
	 *                         for unknown
	 */
	public static record Config(int minEnergy, int maxEnergy, int maxChargePower, int maxDischargePower) {

		/**
		 * Returns a {@link JsonSerializer} for a {@link Config}.
		 *
		 * @return the created {@link JsonSerializer}
		 */
		public static JsonSerializer<Config> serializer() {
			return jsonObjectSerializer(Config.class, json -> {
				return new Config(json.getInt("minEnergy"), json.getInt("maxEnergy"),
						json.getOptionalInt("maxChargePower").orElse(-1),
						json.getOptionalInt("maxDischargePower").orElse(-1));
			}, obj -> {
				return buildJsonObject() //
						.addProperty("minEnergy", obj.minEnergy) //
						.addProperty("maxEnergy", obj.maxEnergy) //
						.addProperty("maxChargePower", obj.maxChargePower) //
						.addProperty("maxDischargePower", obj.maxDischargePower) //
						.build();
			});
		}
	}
}
