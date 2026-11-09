import { ComponentJsonApiRequest } from "src/app/shared/jsonrpc/request/componentJsonApiRequest";
import { Edge, Websocket } from "src/app/shared/shared";
import { GetPriceForecast } from "../jsonrpc/get-price-forecast";

/**
 * View model of the price outlook of a TimeOfUseTariff.Forecast (fork). Prices are kept in [Currency/MWh];
 * the 'priceCt' pipe shows them in ct/kWh.
 */
export namespace PriceForecast {

    export const FACTORY_ID = "TimeOfUseTariff.Forecast";

    /** Rating thresholds [ct/kWh]. */
    export interface Thresholds { level2: number; level3: number; level4: number; level5: number }

    export interface Quarter { time: Date; price: number; published: boolean }

    export interface Window { from: Date; to: Date; price: number }

    export interface Periods { night: number | null; morning: number | null; noon: number | null; evening: number | null }

    export interface Day {
        date: Date;
        quarters: number;
        published: number;
        /** true if at least one quarter of the day is a forecast */
        forecast: boolean;
        min: number;
        minAt: Date;
        max: number;
        average: number;
        periods: Periods;
        cheapestWindow: Window | null;
    }

    export interface Outlook {
        thresholds: Thresholds;
        windowHours: number;
        lastUpdate: Date | null;
        quarters: Quarter[];
        days: Day[];
    }

    /** Rating of a price: 1 (cheap) to 5 (expensive). */
    export function rate(price: number | null | undefined, thresholds: Thresholds): number | null {
        if (price === null || price === undefined || !Number.isFinite(price)) {
            return null;
        }
        const ct = price / 10;
        if (ct < thresholds.level2) { return 1; }
        if (ct < thresholds.level3) { return 2; }
        if (ct < thresholds.level4) { return 3; }
        if (ct < thresholds.level5) { return 4; }
        return 5;
    }

    export function parse(result: GetPriceForecast.Result): Outlook {
        return {
            thresholds: result.thresholds,
            windowHours: result.windowHours,
            lastUpdate: result.lastUpdate ? new Date(result.lastUpdate) : null,
            quarters: result.quarters.map(q => ({ time: new Date(q.time), price: q.price, published: q.published })),
            days: result.days.map(d => ({
                date: new Date(d.date + "T00:00:00"),
                quarters: d.quarters,
                published: d.published,
                forecast: d.published < d.quarters,
                min: d.min,
                minAt: new Date(d.minAt),
                max: d.max,
                average: d.average,
                periods: d.periods,
                cheapestWindow: d.cheapestWindow
                    ? { from: new Date(d.cheapestWindow.from), to: new Date(d.cheapestWindow.to), price: d.cheapestWindow.price }
                    : null,
            })),
        };
    }

    /** Fetches the outlook from the Edge. */
    export function fetch(edge: Edge, websocket: Websocket, componentId: string): Promise<Outlook> {
        return edge.sendRequest(websocket, new ComponentJsonApiRequest({
            componentId: componentId,
            payload: new GetPriceForecast.Request(),
        })).then(response => parse((response as any).result as GetPriceForecast.Result));
    }

    /** The current quarter-hour of the outlook, or null. */
    export function current(outlook: Outlook | null, now: Date = new Date()): Quarter | null {
        if (!outlook) {
            return null;
        }
        const t = now.getTime();
        return outlook.quarters.find(q => q.time.getTime() <= t && t < q.time.getTime() + 15 * 60_000) ?? null;
    }

    /** The day of the outlook that holds 'date', or null. */
    export function dayOf(outlook: Outlook | null, date: Date = new Date()): Day | null {
        if (!outlook) {
            return null;
        }
        return outlook.days.find(d => d.date.getFullYear() === date.getFullYear()
            && d.date.getMonth() === date.getMonth() && d.date.getDate() === date.getDate()) ?? null;
    }

    /** Days after 'date'. */
    export function daysAfter(outlook: Outlook | null, date: Date = new Date()): Day[] {
        if (!outlook) {
            return [];
        }
        const start = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
        return outlook.days.filter(d => d.date.getTime() > start);
    }

    /**
     * Quarters at now + step, now + 2 step, ... for the preview row; stops where the outlook ends.
     *
     * @param stepHours hours between the previews
     * @param count maximum number of previews
     */
    export function upcoming(outlook: Outlook | null, stepHours: number, count: number, now: Date = new Date()): Quarter[] {
        if (!outlook) {
            return [];
        }
        const result: Quarter[] = [];
        for (let i = 1; i <= count; i++) {
            const at = new Date(now.getTime() + i * stepHours * 3_600_000);
            const q = current(outlook, at);
            if (!q) {
                break;
            }
            result.push({ time: at, price: q.price, published: q.published });
        }
        return result;
    }
}
