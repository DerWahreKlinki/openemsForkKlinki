package io.openems.edge.controller.evcs.price.jsonrpc;

import static io.openems.common.utils.JsonUtils.buildJsonObject;

import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.JsonrpcResponseSuccess;
import io.openems.edge.controller.evcs.price.ChargingPlan;

/**
 * The charging plan of a Controller.Evcs.Price.
 *
 * <pre>
 * {
 *   "jsonrpc": "2.0",
 *   "id": "UUID",
 *   "result": {
 *     "start": string | null,
 *     "energyWh": number,
 *     "fromPvWh": number,
 *     "fromStorageWh": number,
 *     "fromGridWh": number,
 *     "storageAtPvEndWh": number | null,
 *     "targetWh": number,
 *     "usableWh": number,
 *     "targetReached": boolean,
 *     "quarters": [{
 *       "timestamp": string,
 *       "price": number | null,        // [Currency/MWh]
 *       "production": number | null,   // [W]
 *       "consumption": number | null,  // [W]
 *       "mode": string,
 *       "power": number,               // [W]
 *       "fromPv": number,              // [W]
 *       "fromStorage": number,         // [W]
 *       "storageBudget": number,       // [W]
 *       "fromGrid": number,            // [W]
 *       "blendedPrice": number | null, // [Currency/MWh]
 *       "reason": string,
 *       "storageWh": number            // [Wh] within the usable window
 *     }]
 *   }
 * }
 * </pre>
 */
public class GetChargingPlanResponse extends JsonrpcResponseSuccess {

	private final JsonObject result;

	public GetChargingPlanResponse(UUID id, JsonObject result) {
		super(id);
		this.result = result;
	}

	@Override
	public JsonObject getResult() {
		return this.result;
	}

	/**
	 * Builds the response from a plan.
	 *
	 * @param requestId the request id
	 * @param plan      the plan
	 * @param targetWh  the evening target in [Wh]
	 * @param usableWh  the usable capacity in [Wh]
	 * @return the response
	 */
	public static GetChargingPlanResponse from(UUID requestId, ChargingPlan.Result plan, int targetWh,
			double usableWh) {
		final var quarters = new JsonArray();
		for (var q : plan.quarters()) {
			quarters.add(buildJsonObject() //
					.addProperty("timestamp", q.time().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)) //
					.add("price", q.price() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(q.price() * 10)) //
					.add("production", q.production() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(q.production())) //
					.add("consumption", q.consumption() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(q.consumption())) //
					.addProperty("mode", q.mode().name()) //
					.addProperty("power", q.power()) //
					.addProperty("fromPv", q.fromPv()) //
					.addProperty("fromStorage", q.fromStorage()) //
					.addProperty("storageBudget", q.storageBudget()) //
					.addProperty("fromGrid", q.fromGrid()) //
					.add("blendedPrice", q.blendedPrice() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(q.blendedPrice() * 10)) //
					.addProperty("reason", q.reason().name()) //
					.addProperty("storageWh", Math.round(q.storageWh())) //
					.build());
		}
		final var result = buildJsonObject() //
				.add("start", plan.start() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(plan.start().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))) //
				.addProperty("energyWh", plan.energyWh()) //
				.addProperty("fromPvWh", plan.fromPvWh()) //
				.addProperty("fromStorageWh", plan.fromStorageWh()) //
				.addProperty("fromGridWh", plan.fromGridWh()) //
				.add("storageAtPvEndWh", plan.storageAtPvEndWh() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(Math.round(plan.storageAtPvEndWh()))) //
				.addProperty("targetWh", targetWh) //
				.addProperty("usableWh", Math.round(usableWh)) //
				.addProperty("targetReached", plan.targetReached()) //
				.add("quarters", quarters) //
				.build();
		return new GetChargingPlanResponse(requestId, result);
	}
}
