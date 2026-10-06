import { ComponentJsonApiRequest } from "src/app/shared/jsonrpc/request/componentJsonApiRequest";
import { GetScheduleRequest } from "src/app/shared/jsonrpc/request/getScheduleRequest";
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
        /** Does not charge: nothing available (no PV surplus, storage not above its evening target). */
        NO_SURPLUS = "NO_SURPLUS",
    }

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
        /** Evening target of the storage in [%] of the usable capacity */
        storageTargetSocNet: number;
        /** Loss surcharge on the PV price for storage power in [Cent/kWh] */
        storageLossSurcharge: number;
        /** Total storage capacity in [Wh]; null if unknown */
        storageCapacity: number | null;
        /** Lower border of the usable SoC window in [%] (ChargeDischargeLimiter) */
        storageMinSoc: number;
        /** Upper border of the usable SoC window in [%] (ChargeDischargeLimiter) */
        storageMaxSoc: number;
        /** Upper price limit in [Cent/kWh]; 0 deactivates charging by price */
        priceLimit: number;
        /** Lower price limit in [Cent/kWh] */
        priceLimitFullPower: number;
        /** Full charge power by price in [W] */
        priceChargePower: number;
        /** Value of surplus PV power in [Cent/kWh] */
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
        zone: Zone;
    };

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
     * @returns The settings
     */
    export function getSettings(
        controller: EdgeConfig.Component,
        minPower: number | null,
        maxPower: number | null,
        storagePrice: number | null = null,
        storageCapacity: number | null = null,
        config: EdgeConfig | null = null,
    ): Settings {
        const properties = controller.properties;
        const window = getStorageWindow(config);
        return {
            priority: properties["priority"] === "STORAGE" ? "STORAGE" : "CAR",
            useStorageSurplus: properties["useStorageSurplus"] === true || properties["useStorageSurplus"] === "true",
            storagePrice: storagePrice,
            storageTargetSocNet: toNumber(properties["storageTargetSocNet"], 80),
            storageLossSurcharge: toNumber(properties["storageLossSurcharge"], 1),
            storageCapacity: storageCapacity,
            storageMinSoc: window.min,
            storageMaxSoc: window.max,
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
        if (settings.priceLimit <= 0 || fullPower <= 0 || fullPower < settings.minPower) {
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
            return Math.max(pvSurplus - Math.max(storageCharge, 0), 0);
        }
        return pvSurplus;
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

        for (let i = 0; i < entries.length; i++) {
            const { entry, timestamp } = entries[i];
            const price = entry.price! / 10; // [Currency/MWh] to [Cent/kWh]
            const production = Math.max(entry.production ?? 0, 0);
            const storageCharge = Math.max(-(entry.ess ?? 0), 0);
            const surplus = calculateSurplus(production, entry.consumption ?? 0, storageCharge, settings.priority);

            // Storage price of this quarter-hour, mirroring the controller: PV price plus
            // loss surcharge if the storage is above its evening target or the remaining
            // PV surplus of the day (after the car) still reaches it; otherwise grid price
            let storageAboveTarget = false;
            let storagePrice: number | null = settings.useStorageSurplus ? settings.storagePrice : null;
            if (settings.useStorageSurplus && entry.soc != null && usable != null) {
                const netSoc = toNetSoc(entry.soc, settings.storageMinSoc, settings.storageMaxSoc);
                const energyToTarget = Math.max(((settings.storageTargetSocNet - netSoc) / 100) * usable, 0);
                storageAboveTarget = energyToTarget <= 0;
                let surplusToEndOfDay = 0;
                for (let k = i; k < entries.length; k++) {
                    const e = entries[k];
                    if (e.timestamp.toDateString() !== timestamp.toDateString()) {
                        break;
                    }
                    const prod = Math.max(e.entry.production ?? 0, 0);
                    if (prod <= 0) {
                        continue;
                    }
                    surplusToEndOfDay += Math.max(prod - Math.max(e.entry.consumption ?? 0, 0) - settings.minPower, 0) / 4;
                }
                storagePrice =
                    surplusToEndOfDay >= energyToTarget ? settings.pvPrice + settings.storageLossSurcharge : price;
            }
            // While the storage is above its target the car may charge from it without PV
            const available = surplus > 0 || (settings.useStorageSurplus && storageAboveTarget);

            // Charging below the minimum hardware power is not possible
            let surplusPower = surplus >= settings.minPower ? surplus : 0;
            if (settings.maxPower != null) {
                surplusPower = Math.min(surplusPower, settings.maxPower);
            }
            const pricePower = calculateChargePowerFromPrice(price, settings);
            let chargePower = Math.max(surplusPower, pricePower);

            // Not enough surplus for the minimum hardware power: the missing power is taken from grid if the
            // blended price is below the limit
            if (
                chargePower <= 0 &&
                available &&
                settings.priceLimit > 0 &&
                calculateBlendedPrice(price, surplus, settings.minPower, settings.pvPrice, storagePrice) <
                    settings.priceLimit
            ) {
                chargePower = settings.minPower;
            }

            let zone: Zone;
            if (chargePower <= 0) {
                zone = available ? Zone.NONE : Zone.NO_SURPLUS;
            } else if (chargePower >= getFullPower(settings)) {
                zone = Zone.FULL;
            } else {
                zone = Zone.REDUCED;
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
     * Loads the schedule of the Time-of-Use-Tariff controller and calculates the forecast.
     *
     * @param edge The Edge
     * @param websocket The Websocket
     * @param config The EdgeConfig
     * @param settings The settings
     * @returns The slots; empty if there is no Time-of-Use-Tariff controller or no schedule
     */
    export async function load(
        edge: Edge,
        websocket: Websocket,
        config: EdgeConfig,
        settings: Settings,
    ): Promise<Slot[]> {
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

    function toNumber(value: unknown, fallback: number): number {
        if (value == null || value === "") {
            return fallback;
        }
        const result = Number(value);
        return Number.isNaN(result) ? fallback : result;
    }
}
