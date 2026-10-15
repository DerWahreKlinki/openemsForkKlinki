import { JsonrpcRequest } from "src/app/shared/jsonrpc/base";

/**
 * Gets the charging plan of a Controller.Evcs.Price; sent as payload of a ComponentJsonApiRequest.
 *
 * ```json
 * {
 *   "jsonrpc": "2.0",
 *   "id": "UUID",
 *   "method": "getChargingPlan",
 *   "params": {}
 * }
 * ```
 */
export class GetChargingPlanRequest extends JsonrpcRequest {
    private static readonly METHOD: string = "getChargingPlan";

    public constructor() {
        super(GetChargingPlanRequest.METHOD, {});
    }
}
