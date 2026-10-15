package io.openems.edge.controller.evcs.price.jsonrpc;

import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.base.JsonrpcRequest;

/**
 * Gets the charging plan of a Controller.Evcs.Price.
 *
 * <pre>
 * {
 *   "jsonrpc": "2.0",
 *   "id": "UUID",
 *   "method": "getChargingPlan",
 *   "params": {}
 * }
 * </pre>
 */
public class GetChargingPlanRequest extends JsonrpcRequest {

	public static final String METHOD = "getChargingPlan";

	/**
	 * Create {@link GetChargingPlanRequest} from a template {@link JsonrpcRequest}.
	 *
	 * @param r the template {@link JsonrpcRequest}
	 * @return the {@link GetChargingPlanRequest}
	 * @throws OpenemsException on parse error
	 */
	public static GetChargingPlanRequest from(JsonrpcRequest r) throws OpenemsException {
		return new GetChargingPlanRequest(r);
	}

	public GetChargingPlanRequest() {
		super(METHOD);
	}

	private GetChargingPlanRequest(JsonrpcRequest request) {
		super(request, METHOD);
	}

	@Override
	public JsonObject getParams() {
		return new JsonObject();
	}
}
