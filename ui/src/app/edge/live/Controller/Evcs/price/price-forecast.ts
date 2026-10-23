import { ChartConstants } from "src/app/shared/components/chart/chart.constants";
import { ComponentJsonApiRequest } from "src/app/shared/jsonrpc/request/componentJsonApiRequest";
import { GetChargingPlanRequest } from "src/app/shared/jsonrpc/request/getChargingPlanRequest";
import { GetScheduleRequest } from "src/app/shared/jsonrpc/request/getScheduleRequest";
import { GetChargingPlanResponse } from "src/app/shared/jsonrpc/response/getChargingPlanResponse";
import { GetScheduleResponse } from "src/app/shared/jsonrpc/response/getScheduleResponse";
import { Edge, EdgeConfig, Websocket } from "src/app/shared/shared";

/**
 * Forecast for a 'Controller.Evcs.Price': what would the controller do in the upcoming quarters of an hour and what
 * would a kWh in the car effectively cost. Based on the schedule of the Time-of-Use-Tariff controller.
 */
export namespace EvcsPriceForecast {
    export const FACTORY_ID = "Controller.Evcs.Price";
    const TIME_OF_USE_TARIFF_FACTORY_ID = "Controller.Ess.Time-Of-Use-Tariff";
    const DEFAULT_MIN_POWER = 4140; // [W]; 6 A on three phases
    const DEFAULT_WINDOW_SLOTS = 12; // 3 hours

    export enum Zone {
        /** Charges with full power. */
        FULL = "FULL",
        /** Charges with reduced power. */
        REDUCED = "REDUCED",
        /** Does not charge: too expensive (blended price above the limit). */
        NONE = "NONE",
        /** Does not charge: the storage needs the surplus for its evening target, everything counts at the grid price, too expensive. */
        GRID_PRICE = "GRID_PRICE",
        /** Does not charge: session limit, grid limit, forecast unknown; target charging: quarter-hour not chosen. */
        UNPLANNABLE = "UNPLANNABLE",
        /** Target charging: after the target time, not planned. */
        AFTER_TARGET = "AFTER_TARGET",
    }

    /** The colour of every zone, shared by the chart, the band and the explanation in the modal. */
    export const ZONE_COLORS: Record<Zone, string> = {
        [Zone.FULL]: ChartConstants.Colors.GREEN,
        [Zone.REDUCED]: ChartConstants.Colors.ORANGE,
        [Zone.NONE]: ChartConstants.Colors.RED,
        [Zone.GRID_PRICE]: "rgb(205, 205, 205)",
        [Zone.UNPLANNABLE]: "rgb(120, 120, 120)",
        [Zone.AFTER_TARGET]: "rgb(77, 106, 130)",
    };

    export type Settings = {
        /** Priority of the controller: CAR takes surplus before the storage, STORAGE only what would go to grid */
        priority: "CAR" | "STORAGE";
        /** Price storage power for the blended price (useStorageSurplus of the controller) */
        useStorageSurplus: boolean;
        /**
         * Current replacement cost of storage power in [Cent/kWh] (channel StoragePrice of the controller); used
         * when the storage price cannot be calculated per quarter-hour; null prices storage power like grid power
         */
        storagePrice: number | null;
        /**
         * True while the controller has released the storage reserve right now (channel StorageTargetReachable);
         * the start of the hysteresis for the running quarter-hour; null if unknown
         */
        storageReleased: boolean | null;
        /** Evening target of the storage in [%] of the usable capacity */
        storageTargetSocNet: number;
        /** Loss surcharge on the PV price for storage power in [Cent/kWh] */
        storageLossSurcharge: number;
        /** Share of a PV surplus that arrives in the storage in the projection in [%] (conversion losses) */
        storageChargeEfficiency: number;
        /** Total storage capacity in [Wh]; null if unknown */
        storageCapacity: number | null;
        /** Lower border of the usable SoC window in [%] (ChargeDischargeLimiter) */
        storageMinSoc: number;
        /** Upper border of the usable SoC window in [%] (ChargeDischargeLimiter) */
        storageMaxSoc: number;
        /** Charge from grid depending on the price; off: surplus and storage reserve only, never grid */
        enablePriceCharging: boolean;
        /** Upper price limit in [Cent/kWh]; 0 allows negative prices only */
        priceLimit: number;
        /** Lower price limit in [Cent/kWh] */
        priceLimitFullPower: number;
        /** Full charge power by price in [W] */
        priceChargePower: number;
        /** Value of surplus power that would otherwise be fed into the grid in [Cent/kWh] */
        pvPrice: number;
        /** Minimum hardware power in [W] */
        minPower: number;
        /** Maximum hardware power in [W]; null if unknown */
        maxPower: number | null;
    };

    export type ScheduleEntry = {
        timestamp: string;
        /** Grid buy price in [Currency/MWh] */
        price: number | null;
        /** Predicted production in [W] */
        production: number | null;
        /** Predicted consumption in [W] */
        consumption: number | null;
        /** Planned storage power in [W]; positive discharge, negative charge; null if unknown */
        ess?: number | null;
        /** Planned state of charge of the storage in [%]; null if unknown */
        soc?: number | null;
    };

    export type Slot = {
        timestamp: Date;
        /** Grid buy price in [Cent/kWh] */
        price: number;
        /** Predicted production in [W] */
        production: number;
        /** Surplus available for the car in [W] according to the priority; never negative */
        surplus: number;
        /** Planned storage charging in [W]; never negative */
        storageCharge: number;
        /** Expected charge power in [W]; 0 if the controller would not charge */
        chargePower: number;
        /** Share of PV power in percent; of the minimum power if the controller would not charge */
        pvShare: number;
        /** Effective price of a kWh in the car in [Cent/kWh] */
        effectivePrice: number;
        /** Replacement cost of storage power in this quarter-hour in [Cent/kWh]; null = like grid */
        storagePrice: number | null;
        /** Storage is (planned to be) above its evening target in this quarter-hour */
        storageAboveTarget: boolean;
        /** Projected energy above the evening target at the end of PV production in [Wh]; null if unknown */
        storageSurplus: number | null;
        /** Planned state of charge of the storage in [%]; null if unknown */
        soc: number | null;
        zone: Zone;
        /** The values of this quarter-hour are measured (current state of the controller), not predicted */
        live?: boolean;
    };

    /** Current state of the controller, used for the running quarter-hour instead of the forecast */
    export type Live = {
        /** Charge power currently set by the controller in [W]; null if unknown */
        chargePower: number | null;
        /** Blended price currently reported by the controller in [Cent/kWh]; null if unknown */
        blendedPrice: number | null;
        /** Current production in [W]; null if unknown */
        production: number | null;
        /** True while the controller wants to start but waits for the start hysteresis */
        awaitingHysteresis?: boolean | null;
    };

    /**
     * Buffer in [W] the controller subtracts from the measured excess power before it counts as
     * available, so that the storage keeps priority (CHARGE_POWER_BUFFER of the controller).
     */
    export const CHARGE_POWER_BUFFER = 200;

    export type Window = {
        from: Date;
        to: Date;
        /** Average effective price in [Cent/kWh] */
        averagePrice: number;
    };

    /**
     * Gets the settings from the properties of a 'Controller.Evcs.Price'.
     *
     * @param controller The controller
     * @param minPower The minimum hardware power of the EVCS in [W]
     * @param maxPower The maximum hardware power of the EVCS in [W]
     * @param storagePrice The current replacement cost of storage power in [Cent/kWh]; null if unknown
     * @param storageCapacity The total storage capacity in [Wh]; null if unknown
     * @param config The EdgeConfig for the SoC window of the limiters; null for 0..100 %
     * @param storageReleased True if the controller has released the storage reserve right now; null if unknown
     * @returns The settings
     */
    export function getSettings(
        controller: EdgeConfig.Component,
        minPower: number | null,
        maxPower: number | null,
        storagePrice: number | null = null,
        storageCapacity: number | null = null,
        config: EdgeConfig | null = null,
        storageReleased: boolean | null = null,
    ): Settings {
        const properties = controller.properties;
        const window = getStorageWindow(config);
        return {
            priority: properties["priority"] === "STORAGE" ? "STORAGE" : "CAR",
            useStorageSurplus: properties["useStorageSurplus"] === true || properties["useStorageSurplus"] === "true",
            storagePrice: storagePrice,
            storageReleased: storageReleased,
            storageTargetSocNet: toNumber(properties["storageTargetSocNet"], 80),
            storageLossSurcharge: toNumber(properties["storageLossSurcharge"], 1),
            storageChargeEfficiency: toNumber(properties["storageChargeEfficiency"], 92),
            storageCapacity: storageCapacity,
            storageMinSoc: window.min,
            storageMaxSoc: window.max,
            enablePriceCharging:
                properties["enablePriceCharging"] !== false && properties["enablePriceCharging"] !== "false",
            priceLimit: toNumber(properties["priceLimit"], 0),
            priceLimitFullPower: toNumber(properties["priceLimitFullPower"], 0),
            priceChargePower: toNumber(properties["priceChargePower"], 0),
            pvPrice: toNumber(properties["pvPrice"], 0),
            minPower: minPower != null && minPower > 0 ? minPower : DEFAULT_MIN_POWER,
            maxPower: maxPower != null && maxPower > 0 ? maxPower : null,
        };
    }

    /**
     * Reads the usable SoC window from the enabled ChargeDischargeLimiter controllers (the widest common window);
     * 0..100 % without limiter.
     *
     * @param config The EdgeConfig
     * @returns The window in [%]
     */
    export function getStorageWindow(config: EdgeConfig | null): { min: number; max: number } {
        const limiters = config?.getComponentsByFactory("Controller.Ess.ChargeDischargeLimiter")?.filter((c) => c.isEnabled) ?? [];
        if (limiters.length === 0) {
            return { min: 0, max: 100 };
        }
        const mins = limiters.map((c) => toNumber(c.properties["minSoc"], 0));
        const maxs = limiters.map((c) => toNumber(c.properties["maxSoc"], 100));
        return { min: Math.min(...mins), max: Math.max(...maxs) };
    }

    /**
     * Net state of charge within the usable window.
     *
     * @param soc The state of charge in [%]
     * @param minSoc The lower border in [%]
     * @param maxSoc The upper border in [%]
     * @returns the net SoC in [%]
     */
    export function toNetSoc(soc: number, minSoc: number, maxSoc: number): number {
        if (maxSoc <= minSoc) {
            return soc;
        }
        return Math.min(Math.max(((soc - minSoc) / (maxSoc - minSoc)) * 100, 0), 100);
    }

    /**
     * Calculates the charge power that is allowed by the grid buy price. Mirrors
     * 'ControllerEvcsPriceImpl.calculateChargePowerFromPrice()'.
     *
     * @param price The grid buy price in [Cent/kWh]
     * @param settings The settings
     * @returns The charge power in [W]; 0 if charging is not allowed by price
     */
    export function calculateChargePowerFromPrice(price: number, settings: Settings): number {
        const fullPower = settings.priceChargePower;
        if (!settings.enablePriceCharging || fullPower <= 0 || fullPower < settings.minPower) {
            return 0;
        }
        if (price >= settings.priceLimit) {
            return 0;
        }
        if (price <= settings.priceLimitFullPower) {
            return fullPower;
        }
        const factor = (settings.priceLimit - price) / (settings.priceLimit - settings.priceLimitFullPower);
        return Math.round(settings.minPower + (fullPower - settings.minPower) * factor);
    }

    /**
     * Calculates the blended price of charging with the given power, partly from surplus power and partly from grid.
     * Mirrors 'ControllerEvcsPriceImpl.calculateBlendedPrice()'.
     *
     * @param price The grid buy price in [Cent/kWh]
     * @param surplus The available surplus power in [W]
     * @param power The charge power in [W]
     * @param pvPrice The value of PV power in [Cent/kWh]
     * @returns The blended price in [Cent/kWh]
     */
    export function calculateBlendedPrice(
        price: number,
        surplus: number,
        power: number,
        pvPrice: number,
        storagePrice: number | null = null,
    ): number {
        const pvPower = Math.min(Math.max(surplus, 0), power);
        // The missing power comes from the storage (at its replacement cost) if it is priced, else from grid
        const restPrice = storagePrice ?? price;
        return (pvPower * pvPrice + (power - pvPower) * restPrice) / power;
    }

    /**
     * Calculates the surplus that is available for the car, mirroring the priority of the controller: with
     * priority CAR the car may take what the storage would charge; with priority STORAGE only the power that
     * would be fed into the grid after the planned storage charging counts.
     *
     * @param production The predicted production in [W]
     * @param consumption The predicted consumption in [W]
     * @param storageCharge The planned storage charging in [W]; never negative
     * @param priority The priority of the controller
     * @returns The surplus in [W]; never negative
     */
    export function calculateSurplus(
        production: number,
        consumption: number,
        storageCharge: number,
        priority: "CAR" | "STORAGE",
    ): number {
        const pvSurplus = Math.max(Math.max(production, 0) - Math.max(consumption, 0), 0);
        if (priority === "STORAGE") {
            return Math.max(pvSurplus - Math.max(storageCharge, 0) - CHARGE_POWER_BUFFER, 0);
        }
        return pvSurplus;
    }

    /**
     * Projects the energy of the storage from the first entry until the last quarter-hour with PV production
     * of that day and returns what is left above the evening target, mirroring the controller: per
     * quarter-hour production minus consumption is added, negative balances included, within the usable
     * capacity. With priority CAR the car takes from a positive balance up to its minimum power (it charges
     * from PV surplus only); with priority STORAGE the storage is filled first, so the car does not count.
     *
     * @param entries The schedule entries from the quarter-hour on, in chronological order
     * @param storedWh The energy stored at the first entry within the SoC window in [Wh]
     * @param usableWh The usable capacity within the SoC window in [Wh]
     * @param settings The settings
     * @returns The energy above the target in [Wh]; negative if missing
     */
    export function calculateStorageSurplus(
        entries: { entry: ScheduleEntry; timestamp: Date }[],
        storedWh: number,
        usableWh: number,
        settings: Settings,
    ): number {
        // "Evening" is the end of the running block of quarter-hours with production; at night (no production
        // in the first quarter-hour) the evening has passed and the next day's PV does not count
        let lastProduction = -1;
        while (lastProduction + 1 < entries.length && (entries[lastProduction + 1].entry.production ?? 0) > 0) {
            lastProduction++;
        }
        const carPower = settings.priority === "STORAGE" ? 0 : settings.minPower;
        // of a positive balance only the charge efficiency arrives in the storage (conversion losses)
        const efficiency = settings.storageChargeEfficiency / 100;
        let energy = storedWh;
        for (let k = 0; k <= lastProduction; k++) {
            const e = entries[k].entry;
            const balance = Math.max(e.production ?? 0, 0) - Math.max(e.consumption ?? 0, 0);
            energy += (balance > 0 ? Math.max(balance - carPower, 0) * efficiency : balance) / 4;
            energy = Math.min(Math.max(energy, 0), usableWh);
        }
        return Math.round(energy - (settings.storageTargetSocNet / 100) * usableWh);
    }

    /**
     * Decides if the storage reserve is released for the car, mirroring the hysteresis of the controller:
     * released from one hour of minimum power above the evening target on, and until the surplus is used up.
     *
     * @param storageSurplus The energy above the evening target in [Wh]; negative if missing
     * @param released True if the reserve was released in the quarter-hour before
     * @param settings The settings
     * @returns True if the reserve is released
     */
    export function isStorageReleased(storageSurplus: number, released: boolean, settings: Settings): boolean {
        return released ? storageSurplus > 0 : storageSurplus >= Math.max(settings.minPower, 1);
    }

    /**
     * Replaces the forecast of the running quarter-hour by the current state of the controller, so that
     * the chart never contradicts what the controller is doing right now.
     *
     * @param slots The slots of the forecast
     * @param live The current state of the controller; null leaves the forecast untouched
     * @param settings The settings
     * @param now The current time
     * @returns A new array with the running quarter-hour replaced; the input if there is none
     */
    export function applyLive(slots: Slot[], live: Live | null, settings: Settings, now: Date): Slot[] {
        if (live == null || slots.length === 0) {
            return slots;
        }
        const quarter = 15 * 60 * 1000;
        const index = slots.findIndex(
            (slot) => slot.timestamp.getTime() <= now.getTime() && now.getTime() < slot.timestamp.getTime() + quarter,
        );
        if (index < 0) {
            return slots;
        }
        const slot = slots[index];
        // While the start hysteresis runs the limit is still 0, but the controller wants to charge:
        // show the wanted power, at least the minimum power
        let chargePower = live.chargePower ?? slot.chargePower;
        if (live.awaitingHysteresis && chargePower <= 0) {
            chargePower = Math.max(slot.chargePower, settings.minPower);
        }
        const effectivePrice = live.blendedPrice ?? slot.effectivePrice;
        const zone = getZone(chargePower, effectivePrice, settings);
        const result = [...slots];
        result[index] = {
            ...slot,
            production: live.production ?? slot.production,
            chargePower: chargePower,
            effectivePrice: Math.round(effectivePrice * 100) / 100,
            zone: zone,
            live: true,
        };
        return result;
    }

    /**
     * Gets the zone of a quarter-hour: the price the bar shows decides between "too expensive" and "nothing
     * available" - the latter only if the price would be fine but there is nothing to charge with.
     *
     * @param chargePower The expected charge power in [W]; 0 if the controller would not charge
     * @param effectivePrice The effective price in [Cent/kWh]
     * @param settings The settings
     * @returns The zone
     */
    function getZone(chargePower: number, effectivePrice: number, settings: Settings): Zone {
        if (chargePower <= 0) {
            // without price-based charging the price is never the reason
            return settings.enablePriceCharging && effectivePrice >= settings.priceLimit ? Zone.NONE : Zone.UNPLANNABLE;
        }
        if (chargePower >= getFullPower(settings)) {
            return Zone.FULL;
        }
        return Zone.REDUCED;
    }

    /**
     * Gets the power that counts as 'full power'.
     *
     * @param settings The settings
     * @returns The power in [W]
     */
    function getFullPower(settings: Settings): number {
        const powers = [settings.priceChargePower, settings.maxPower].filter(
            (power): power is number => power != null && power > 0,
        );
        return powers.length > 0 ? Math.min(...powers) : settings.minPower;
    }

    /**
     * Calculates the forecast for the upcoming entries of a schedule.
     *
     * @param schedule The schedule of the Time-of-Use-Tariff controller
     * @param settings The settings
     * @param now The current time; entries that ended before are ignored
     * @returns The slots
     */
    export function calculate(schedule: ScheduleEntry[], settings: Settings, now: Date): Slot[] {
        const quarter = 15 * 60 * 1000;
        const result: Slot[] = [];
        const entries = schedule
            .map((entry) => ({ entry, timestamp: new Date(entry.timestamp) }))
            .filter(
                ({ entry, timestamp }) =>
                    entry.price != null &&
                    !Number.isNaN(timestamp.getTime()) &&
                    timestamp.getTime() + quarter > now.getTime(),
            )
            .sort((a, b) => a.timestamp.getTime() - b.timestamp.getTime());
        const usable =
            settings.storageCapacity != null
                ? (settings.storageCapacity * (settings.storageMaxSoc - settings.storageMinSoc)) / 100
                : null;

        // The release of the storage reserve has a hysteresis; the running quarter-hour starts with the
        // state of the controller, the quarter-hours after with the state of the one before
        let released = settings.storageReleased ?? false;

        for (let i = 0; i < entries.length; i++) {
            const { entry, timestamp } = entries[i];
            const price = entry.price! / 10; // [Currency/MWh] to [Cent/kWh]
            const production = Math.max(entry.production ?? 0, 0);
            const storageCharge = Math.max(-(entry.ess ?? 0), 0);
            const surplus = calculateSurplus(production, entry.consumption ?? 0, storageCharge, settings.priority);

            // Storage price of this quarter-hour, mirroring the controller: the energy of the
            // storage is projected until the last quarter-hour with PV of the day; PV price plus
            // loss surcharge while the reserve above the evening target is released, otherwise grid price
            let storageSurplus: number | null = null;
            let storagePrice: number | null = settings.useStorageSurplus ? settings.storagePrice : null;
            if (settings.useStorageSurplus && entry.soc != null && usable != null) {
                const netSoc = toNetSoc(entry.soc, settings.storageMinSoc, settings.storageMaxSoc);
                storageSurplus = calculateStorageSurplus(entries.slice(i), (netSoc / 100) * usable, usable, settings);
                released = isStorageReleased(storageSurplus, released, settings);
                // storage power counts like PV only while the reserve is released
                storagePrice = released ? settings.pvPrice + settings.storageLossSurcharge : price;
            }
            // A storage reserve is available while it is released; without a projection, while the
            // controller itself prices the storage below grid
            const storageAboveTarget =
                storageSurplus != null ? released : storagePrice != null && storagePrice < price;
            // While the storage is above its target the car may charge from it without PV
            const available = surplus > 0 || (settings.useStorageSurplus && storageAboveTarget);

            // Charging below the minimum hardware power is not possible
            let surplusPower = surplus >= settings.minPower ? surplus : 0;
            if (settings.maxPower != null) {
                surplusPower = Math.min(surplusPower, settings.maxPower);
            }
            const pricePower = calculateChargePowerFromPrice(price, settings);
            let chargePower = Math.max(surplusPower, pricePower);

            // Not enough surplus for the minimum hardware power: the missing power is taken from the storage
            // reserve and, with price-based charging, from grid if the blended price is below the limit
            if (chargePower <= 0 && available) {
                if (settings.enablePriceCharging) {
                    if (
                        calculateBlendedPrice(price, surplus, settings.minPower, settings.pvPrice, storagePrice) <
                        settings.priceLimit
                    ) {
                        chargePower = settings.minPower;
                    }
                } else if (storageAboveTarget && settings.useStorageSurplus) {
                    // without price-based charging the storage reserve has to cover the missing power
                    chargePower = settings.minPower;
                }
            }

            // Without charging: what would it cost at minimum power
            const referencePower = chargePower > 0 ? chargePower : settings.minPower;
            const pvPower = Math.min(surplus, referencePower);
            const effectivePrice = calculateBlendedPrice(
                price,
                surplus,
                referencePower,
                settings.pvPrice,
                storagePrice,
            );

            const zone = getZone(chargePower, effectivePrice, settings);

            result.push({
                timestamp: timestamp,
                price: price,
                production: production,
                surplus: surplus,
                storageCharge: storageCharge,
                chargePower: chargePower,
                pvShare: Math.round((pvPower / referencePower) * 100),
                effectivePrice: Math.round(effectivePrice * 100) / 100,
                storagePrice: storagePrice,
                storageAboveTarget: storageAboveTarget,
                storageSurplus: storageSurplus,
                soc: entry.soc ?? null,
                zone: zone,
            });
        }
        return result;
    }

    /**
     * Finds the cheapest contiguous window by effective price.
     *
     * @param slots The slots
     * @param size The number of slots in the window
     * @returns The window, null if there are not enough slots
     */
    export function getCheapestWindow(slots: Slot[], size: number = DEFAULT_WINDOW_SLOTS): Window | null {
        if (size <= 0 || slots.length < size) {
            return null;
        }
        const quarter = 15 * 60 * 1000;
        let best: Window | null = null;
        for (let start = 0; start + size <= slots.length; start++) {
            const window = slots.slice(start, start + size);
            // Skip windows with gaps
            if (window[size - 1].timestamp.getTime() - window[0].timestamp.getTime() !== (size - 1) * quarter) {
                continue;
            }
            const average = window.reduce((sum, slot) => sum + slot.effectivePrice, 0) / size;
            if (best == null || average < best.averagePrice) {
                best = {
                    from: window[0].timestamp,
                    to: new Date(window[size - 1].timestamp.getTime() + quarter),
                    averagePrice: Math.round(average * 10) / 10,
                };
            }
        }
        return best;
    }

    /**
     * Gets the enabled Time-of-Use-Tariff controller.
     *
     * @param config The EdgeConfig
     * @returns The controller, null if there is none
     */
    export function getTimeOfUseTariffController(config: EdgeConfig): EdgeConfig.Component | null {
        return (
            config.getComponentsByFactory(TIME_OF_USE_TARIFF_FACTORY_ID).find((component) => component.isEnabled) ??
            null
        );
    }

    /**
     * Loads the forecast: the charging plan of the controller itself, if it provides one (Edge from 15.10.2026 on);
     * otherwise the schedule of the Time-of-Use-Tariff controller, from which the behaviour of the controller is
     * estimated with the rules of its former storage reserve.
     *
     * @param edge The Edge
     * @param websocket The Websocket
     * @param config The EdgeConfig
     * @param settings The settings
     * @param componentId The ID of the controller; null skips the charging plan
     * @returns The slots; empty if there is neither a charging plan nor a schedule
     */
    export async function load(
        edge: Edge,
        websocket: Websocket,
        config: EdgeConfig,
        settings: Settings,
        componentId: string | null = null,
    ): Promise<Slot[]> {
        if (componentId != null) {
            try {
                const plan = (await edge.sendRequest(
                    websocket,
                    new ComponentJsonApiRequest({
                        componentId: componentId,
                        payload: new GetChargingPlanRequest(),
                    }),
                )) as GetChargingPlanResponse;
                const quarters = plan?.result?.quarters ?? [];
                if (quarters.length > 0) {
                    return fromPlan(quarters, plan.result.usableWh, settings, new Date());
                }
            } catch (e) {
                // older Edge without charging plan: fall through to the estimate
                console.debug("EvcsPriceForecast: no charging plan, using the estimate", e);
            }
        }
        const timeOfUseTariff = getTimeOfUseTariffController(config);
        if (timeOfUseTariff == null) {
            return [];
        }
        const response = (await edge.sendRequest(
            websocket,
            new ComponentJsonApiRequest({
                componentId: timeOfUseTariff.id,
                payload: new GetScheduleRequest(),
            }),
        )) as GetScheduleResponse;
        return calculate(response?.result?.schedule ?? [], settings, new Date());
    }

    /**
     * Converts the charging plan of the controller into slots: the plan already contains the decision of the
     * controller per quarter-hour, nothing is estimated here.
     *
     * @param quarters The quarter-hours of the plan
     * @param usableWh The usable capacity of the storage in [Wh]; 0 if unknown
     * @param settings The settings
     * @param now The current time; quarter-hours that ended before are ignored
     * @returns The slots
     */
    export function fromPlan(
        quarters: GetChargingPlanResponse.Quarter[],
        usableWh: number,
        settings: Settings,
        now: Date,
    ): Slot[] {
        const quarter = 15 * 60 * 1000;
        const result: Slot[] = [];
        for (const q of quarters) {
            const timestamp = new Date(q.timestamp);
            if (q.price == null || Number.isNaN(timestamp.getTime()) || timestamp.getTime() + quarter <= now.getTime()) {
                continue;
            }
            const price = q.price / 10; // [Currency/MWh] to [Cent/kWh]
            const referencePower = q.power > 0 ? q.power : settings.minPower;
            const effectivePrice = q.blendedPrice != null ? q.blendedPrice / 10 : price;
            const storageAboveTarget = q.storageBudget > 0;
            // TOO_EXPENSIVE: blended price above the limit; TARGET: the storage needs the surplus for the
            // evening, everything counts at the grid price; the rest (limit, grid limit, unknown, not chosen
            // by target charging) is not plannable
            const zone =
                q.power <= 0
                    ? q.reason === "TOO_EXPENSIVE"
                        ? Zone.NONE
                        : q.reason === "TARGET"
                          ? Zone.GRID_PRICE
                          : q.reason === "AFTER_TARGET"
                            ? Zone.AFTER_TARGET
                            : Zone.UNPLANNABLE
                    : getZone(q.power, effectivePrice, settings);
            result.push({
                timestamp: timestamp,
                price: price,
                production: Math.max(q.production ?? 0, 0),
                surplus: q.fromPv,
                storageCharge: 0,
                chargePower: q.power,
                pvShare: referencePower > 0 ? Math.round((Math.min(q.fromPv, referencePower) / referencePower) * 100) : 0,
                effectivePrice: effectivePrice,
                storagePrice: storageAboveTarget ? settings.pvPrice + settings.storageLossSurcharge : price,
                storageAboveTarget: storageAboveTarget,
                storageSurplus: null,
                soc: usableWh > 0 ? Math.round((q.storageWh / usableWh) * 100) : null,
                zone: zone,
            });
        }
        return result;
    }

    function toNumber(value: unknown, fallback: number): number {
        if (value == null || value === "") {
            return fallback;
        }
        const result = Number(value);
        return Number.isNaN(result) ? fallback : result;
    }
}
