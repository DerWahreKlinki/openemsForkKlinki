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
        /** Does not charge. */
        NONE = "NONE",
    }

    export type Settings = {
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
    };

    export type Slot = {
        timestamp: Date;
        /** Grid buy price in [Cent/kWh] */
        price: number;
        /** Predicted production in [W] */
        production: number;
        /** Predicted surplus (production minus consumption) in [W]; never negative */
        surplus: number;
        /** Expected charge power in [W]; 0 if the controller would not charge */
        chargePower: number;
        /** Share of PV power in percent; of the minimum power if the controller would not charge */
        pvShare: number;
        /** Effective price of a kWh in the car in [Cent/kWh] */
        effectivePrice: number;
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
     * @returns The settings
     */
    export function getSettings(
        controller: EdgeConfig.Component,
        minPower: number | null,
        maxPower: number | null,
    ): Settings {
        const properties = controller.properties;
        return {
            priceLimit: toNumber(properties["priceLimit"], 0),
            priceLimitFullPower: toNumber(properties["priceLimitFullPower"], 0),
            priceChargePower: toNumber(properties["priceChargePower"], 0),
            pvPrice: toNumber(properties["pvPrice"], 0),
            minPower: minPower != null && minPower > 0 ? minPower : DEFAULT_MIN_POWER,
            maxPower: maxPower != null && maxPower > 0 ? maxPower : null,
        };
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
    export function calculateBlendedPrice(price: number, surplus: number, power: number, pvPrice: number): number {
        const pvPower = Math.min(Math.max(surplus, 0), power);
        return (pvPower * pvPrice + (power - pvPower) * price) / power;
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
        for (const entry of schedule) {
            const timestamp = new Date(entry.timestamp);
            if (
                entry.price == null ||
                Number.isNaN(timestamp.getTime()) ||
                timestamp.getTime() + quarter <= now.getTime()
            ) {
                continue;
            }
            const price = entry.price / 10; // [Currency/MWh] to [Cent/kWh]
            const production = Math.max(entry.production ?? 0, 0);
            const surplus = Math.max(production - Math.max(entry.consumption ?? 0, 0), 0);

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
                surplus > 0 &&
                settings.priceLimit > 0 &&
                calculateBlendedPrice(price, surplus, settings.minPower, settings.pvPrice) < settings.priceLimit
            ) {
                chargePower = settings.minPower;
            }

            let zone: Zone;
            if (chargePower <= 0) {
                zone = Zone.NONE;
            } else if (chargePower >= getFullPower(settings)) {
                zone = Zone.FULL;
            } else {
                zone = Zone.REDUCED;
            }

            // Without charging: what would it cost at minimum power
            const referencePower = chargePower > 0 ? chargePower : settings.minPower;
            const pvPower = Math.min(surplus, referencePower);
            const effectivePrice = calculateBlendedPrice(price, surplus, referencePower, settings.pvPrice);

            result.push({
                timestamp: timestamp,
                price: price,
                production: production,
                surplus: surplus,
                chargePower: chargePower,
                pvShare: Math.round((pvPower / referencePower) * 100),
                effectivePrice: Math.round(effectivePrice * 100) / 100,
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
