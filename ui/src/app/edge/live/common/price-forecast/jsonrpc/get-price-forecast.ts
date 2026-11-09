import { JsonrpcRequest } from "src/app/shared/jsonrpc/base";

/**
 * JSON-RPC 'getPriceForecast' of a TimeOfUseTariff.Forecast (fork): the price outlook for the next days.
 * Prices are in [Currency/MWh], like the Time-of-Use tariff channels.
 */
export namespace GetPriceForecast {

    export const METHOD: string = "getPriceForecast";

    export class Request extends JsonrpcRequest {
        public constructor() {
            super(GetPriceForecast.METHOD, {});
        }
    }

    /** The raw result as sent by the Edge. */
    export interface Result {
        thresholds: { level2: number; level3: number; level4: number; level5: number }; // [ct/kWh]
        windowHours: number;
        lastUpdate: string | null;
        quarters: { time: string; price: number; published: boolean }[];
        days: {
            date: string;
            quarters: number;
            published: number;
            min: number;
            minAt: string;
            max: number;
            average: number;
            periods: { night: number | null; morning: number | null; noon: number | null; evening: number | null };
            cheapestWindow: { from: string; to: string; price: number } | null;
        }[];
    }
}
