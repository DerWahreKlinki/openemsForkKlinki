import { TranslateService } from "@ngx-translate/core";
import { ComponentJsonApiRequest } from "src/app/shared/jsonrpc/request/componentJsonApiRequest";
import { GetScheduleRequest } from "src/app/shared/jsonrpc/request/getScheduleRequest";
import { GetScheduleResponse } from "src/app/shared/jsonrpc/response/getScheduleResponse";
import { Edge, EdgeConfig, Websocket } from "src/app/shared/shared";

/**
 * With Energy Scheduler V2 the GridOptimizedCharge controller does not run its
 * own state machine any more; its logic is part of the schedule of the
 * Time-of-Use-Tariff controller (states DELAY_CHARGE, LIMIT_CHARGE and
 * AVOID_GRID_SELL_LIMIT). This reads that schedule and summarizes the part
 * that belongs to the GridOptimizedCharge controller.
 */
export namespace GridOptimizedChargePlan {

    const ENERGY_SCHEDULER_ID = "_energy";
    const ENERGY_SCHEDULER_V2 = "V2_ENERGY_SCHEDULABLE";
    const TIME_OF_USE_TARIFF_FACTORY_ID = "Controller.Ess.Time-Of-Use-Tariff";

    /** Time-of-Use-Tariff states that implement the GridOptimizedCharge logic */
    export enum State {
        DELAY_CHARGE = 6,
        LIMIT_CHARGE = 7,
        AVOID_GRID_SELL_LIMIT = 8,
    }

    export type Block = {
        state: State;
        from: Date;
        /** End of the block (exclusive) */
        until: Date;
        /** Average charge power of the storage in [W], positive */
        chargePower: number | null;
    };

    export type Plan = {
        /** Block that is active now; null if the storage is not limited now */
        current: Block | null;
        /** Next block after the current one (or the first one), if any */
        next: Block | null;
    };

    /**
     * Is the GridOptimizedCharge logic part of the Time-of-Use-Tariff schedule?
     * That is the case with Energy Scheduler V2 and an enabled Time-of-Use-Tariff
     * controller; the latter disables the controller's own state machine. Without
     * a Time-of-Use-Tariff controller the controller runs on its own, also with V2.
     *
     * @param config The EdgeConfig
     * @returns true if the schedule has to be shown instead of the controller's states
     */
    export function isEnergySchedulerV2(config: EdgeConfig | null): boolean {
        const energy = config?.components?.[ENERGY_SCHEDULER_ID];
        return energy?.properties?.["version"] === ENERGY_SCHEDULER_V2 && getTimeOfUseTariffController(config) != null;
    }

    function getTimeOfUseTariffController(config: EdgeConfig | null): EdgeConfig.Component | null {
        return (
            config?.getComponentsByFactory(TIME_OF_USE_TARIFF_FACTORY_ID).find((component) => component.isEnabled) ??
            null
        );
    }

    /**
     * Builds the plan from the schedule entries: consecutive quarter-hours with
     * the same GridOptimizedCharge state form a block.
     *
     * @param schedule The schedule entries (quarter-hourly)
     * @param now The current time
     * @returns the plan
     */
    export function calculate(
        schedule: { timestamp: string; state: number; ess: number | null }[],
        now: Date,
    ): Plan {
        const quarter = 15 * 60 * 1000;
        const blocks: Block[] = [];
        const entries = [...schedule]
            .map((e) => ({ time: new Date(e.timestamp), state: e.state, ess: e.ess }))
            .filter((e) => !Number.isNaN(e.time.getTime()) && e.time.getTime() + quarter > now.getTime())
            .sort((a, b) => a.time.getTime() - b.time.getTime());

        for (const entry of entries) {
            if (!(entry.state in State)) {
                continue;
            }
            const last = blocks[blocks.length - 1];
            const charge = entry.ess == null ? null : Math.max(-entry.ess, 0);
            if (last != null && last.state === entry.state && last.until.getTime() === entry.time.getTime()) {
                last.until = new Date(entry.time.getTime() + quarter);
                if (charge != null) {
                    const n = Math.round((last.until.getTime() - last.from.getTime()) / quarter);
                    last.chargePower = ((last.chargePower ?? 0) * (n - 1) + charge) / n;
                }
            } else {
                blocks.push({
                    state: entry.state,
                    from: entry.time,
                    until: new Date(entry.time.getTime() + quarter),
                    chargePower: charge,
                });
            }
        }

        const current = blocks.find((b) => b.from.getTime() <= now.getTime() && now.getTime() < b.until.getTime()) ?? null;
        const next = blocks.find((b) => b.from.getTime() > now.getTime() && b !== current) ?? null;
        return { current, next };
    }

    /**
     * Loads the schedule of the Time-of-Use-Tariff controller and builds the plan.
     *
     * @param edge The Edge
     * @param websocket The Websocket
     * @param config The EdgeConfig
     * @returns the plan; null if there is no schedule
     */
    export async function load(edge: Edge, websocket: Websocket, config: EdgeConfig): Promise<Plan | null> {
        const timeOfUseTariff = getTimeOfUseTariffController(config);
        if (timeOfUseTariff == null) {
            return null;
        }
        const response = (await edge.sendRequest(
            websocket,
            new ComponentJsonApiRequest({
                componentId: timeOfUseTariff.id,
                payload: new GetScheduleRequest(),
            }),
        )) as GetScheduleResponse;
        const schedule = response?.result?.schedule;
        if (schedule == null) {
            return null;
        }
        return calculate(schedule, new Date());
    }

    /**
     * Describes the plan in one line, e.g. "Charging limited to 3.4 kW until 15:30".
     *
     * @param plan The plan
     * @param translate The TranslateService
     * @returns the text
     */
    export function describe(plan: Plan | null, translate: TranslateService): string {
        const prefix = "EDGE.INDEX.WIDGETS.GRID_OPTIMIZED_CHARGE.ENERGY_SCHEDULER.";
        if (plan == null) {
            return translate.instant(prefix + "NO_SCHEDULE");
        }
        if (plan.current != null) {
            return describeBlock(plan.current, translate);
        }
        if (plan.next != null) {
            return translate.instant(prefix + "NEXT", {
                text: describeBlock(plan.next, translate),
                time: formatTime(plan.next.from),
            });
        }
        return translate.instant(prefix + "NO_LIMIT");
    }

    function describeBlock(block: Block, translate: TranslateService): string {
        const prefix = "EDGE.INDEX.WIDGETS.GRID_OPTIMIZED_CHARGE.ENERGY_SCHEDULER.";
        const params = {
            until: formatTime(block.until),
            power: block.chargePower == null ? "-" : (block.chargePower / 1000).toFixed(1) + " kW",
        };
        switch (block.state) {
            case State.LIMIT_CHARGE:
                return translate.instant(prefix + "LIMIT_CHARGE", params);
            case State.DELAY_CHARGE:
                return translate.instant(prefix + "DELAY_CHARGE", params);
            case State.AVOID_GRID_SELL_LIMIT:
                return translate.instant(prefix + "AVOID_GRID_SELL_LIMIT", params);
        }
    }

    function formatTime(date: Date): string {
        return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    }
}
