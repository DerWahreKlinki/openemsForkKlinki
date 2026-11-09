package io.openems.edge.timeofusetariff.forecast.jsonrpc;

import java.util.UUID;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.JsonrpcResponseSuccess;

/**
 * The price outlook of a TimeOfUseTariff.Forecast, see
 * {@link io.openems.edge.timeofusetariff.forecast.PriceForecast#toJson}.
 *
 * <pre>
 * {
 *   "jsonrpc": "2.0",
 *   "id": "UUID",
 *   "result": {
 *     "thresholds": { "level2": number, "level3": number, "level4": number, "level5": number }, // [ct/kWh]
 *     "windowHours": number,
 *     "lastUpdate": string | null,
 *     "quarters": [{ "time": string, "price": number, "published": boolean }], // [Currency/MWh]
 *     "days": [{
 *       "date": string, "quarters": number, "published": number,
 *       "min": number, "minAt": string, "max": number, "average": number,
 *       "periods": { "night": number | null, "morning": number | null, "noon": number | null, "evening": number | null },
 *       "cheapestWindow": { "from": string, "to": string, "price": number } | null
 *     }]
 *   }
 * }
 * </pre>
 */
public class GetPriceForecastResponse extends JsonrpcResponseSuccess {

	public static final String METHOD = "getPriceForecast";

	private final JsonObject result;

	public GetPriceForecastResponse(UUID id, JsonObject result) {
		super(id);
		this.result = result;
	}

	@Override
	public JsonObject getResult() {
		return this.result;
	}
}
