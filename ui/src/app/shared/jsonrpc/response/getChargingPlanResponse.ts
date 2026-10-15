import { JsonrpcResponseSuccess } from "src/app/shared/jsonrpc/base";

/**
 * Wraps a JSON-RPC Response for a GetChargingPlanRequest of a Controller.Evcs.Price.
 *
 * ```json
 * {
 *   "jsonrpc": "2.0",
 *   "id": UUID,
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
 * ```
 */
export class GetChargingPlanResponse extends JsonrpcResponseSuccess {
    public constructor(
        public override readonly id: string,
        public override readonly result: {
            start: string | null;
            energyWh: number;
            fromPvWh: number;
            fromStorageWh: number;
            fromGridWh: number;
            storageAtPvEndWh: number | null;
            targetWh: number;
            usableWh: number;
            targetReached: boolean;
            quarters: GetChargingPlanResponse.Quarter[];
        },
    ) {
        super(id, result);
    }
}

export namespace GetChargingPlanResponse {
    export type Quarter = {
        timestamp: string;
        price: number | null;
        production: number | null;
        consumption: number | null;
        mode: string;
        power: number;
        fromPv: number;
        fromStorage: number;
        storageBudget: number;
        fromGrid: number;
        blendedPrice: number | null;
        reason: string;
        storageWh: number;
    };
}
