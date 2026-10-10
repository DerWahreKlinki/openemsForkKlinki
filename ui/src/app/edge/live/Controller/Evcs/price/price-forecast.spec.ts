import { EvcsPriceForecast } from "./price-forecast";

describe("EvcsPriceForecast", () => {
    const SETTINGS: EvcsPriceForecast.Settings = {
        priority: "CAR",
        useStorageSurplus: false,
        storagePrice: null,
        storageTargetSocNet: 80,
        storageLossSurcharge: 1,
        storageCapacity: null,
        storageMinSoc: 20,
        storageMaxSoc: 90,
        enablePriceCharging: true,
        priceLimit: 30,
        priceLimitFullPower: 20,
        priceChargePower: 11040,
        pvPrice: 7,
        minPower: 4140,
        maxPower: 11040,
    };
    const NOW = new Date("2026-10-02T10:00:00Z");

    function entry(
        time: string,
        price: number | null,
        production: number,
        consumption: number,
        ess: number | null = null,
    ): EvcsPriceForecast.ScheduleEntry {
        return {
            timestamp: "2026-10-02T" + time + ":00Z",
            price: price,
            production: production,
            consumption: consumption,
            ess: ess,
        };
    }

    function calculateSingle(price: number, production: number, consumption: number): EvcsPriceForecast.Slot {
        return EvcsPriceForecast.calculate([entry("10:00", price, production, consumption)], SETTINGS, NOW)[0];
    }

    it("#calculateChargePowerFromPrice mirrors the controller", () => {
        expect(EvcsPriceForecast.calculateChargePowerFromPrice(40.17, SETTINGS)).toBe(0);
        expect(EvcsPriceForecast.calculateChargePowerFromPrice(30, SETTINGS)).toBe(0);
        expect(EvcsPriceForecast.calculateChargePowerFromPrice(29, SETTINGS)).toBe(4830);
        expect(EvcsPriceForecast.calculateChargePowerFromPrice(25, SETTINGS)).toBe(7590);
        expect(EvcsPriceForecast.calculateChargePowerFromPrice(20, SETTINGS)).toBe(11040);
        expect(EvcsPriceForecast.calculateChargePowerFromPrice(-12, SETTINGS)).toBe(11040);
        expect(EvcsPriceForecast.calculateChargePowerFromPrice(10, { ...SETTINGS, priceLimit: 0 })).toBe(0);
        expect(EvcsPriceForecast.calculateChargePowerFromPrice(10, { ...SETTINGS, priceChargePower: 3000 })).toBe(0);
    });

    it("#calculate charges with surplus power only", () => {
        const slot = calculateSingle(375, 8924, 692);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slot.chargePower).toBe(8232);
        expect(slot.pvShare).toBe(100);
        expect(slot.effectivePrice).toBe(7);
    });

    it("#calculate fills up to the minimum power if the blended price is below the limit", () => {
        const slot = calculateSingle(426, 5540, 2176);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slot.chargePower).toBe(4140);
        expect(slot.pvShare).toBe(81);
        expect(slot.effectivePrice).toBe(13.67);
    });

    it("#calculate does not charge at high price without enough surplus", () => {
        const slot = calculateSingle(469, 3656, 2472);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.NONE);
        expect(slot.chargePower).toBe(0);
        // 1184 W PV and 2956 W grid at minimum power
        expect(slot.pvShare).toBe(29);
        expect(slot.effectivePrice).toBe(35.49);
    });

    it("#calculate charges reduced between the price limits", () => {
        const slot = calculateSingle(250, 3000, 0);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slot.chargePower).toBe(7590);
        expect(slot.pvShare).toBe(40);
        expect(slot.effectivePrice).toBe(17.89);
    });

    it("#calculate charges with full power below the lower price limit", () => {
        const slot = calculateSingle(150, 0, 500);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.FULL);
        expect(slot.chargePower).toBe(11040);
        expect(slot.pvShare).toBe(0);
        expect(slot.effectivePrice).toBe(15);
    });

    it("#calculate limits surplus power to the maximum hardware power", () => {
        const slot = calculateSingle(400, 20000, 0);
        expect(slot.chargePower).toBe(11040);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.FULL);
    });

    it("#calculate ignores past entries and entries without price", () => {
        const slots = EvcsPriceForecast.calculate(
            [
                entry("09:30", 300, 0, 0), // past
                entry("09:45", 300, 0, 0), // just ended
                entry("10:00", 300, 0, 0),
                entry("10:15", null, 0, 0), // no price
            ],
            SETTINGS,
            NOW,
        );
        expect(slots.length).toBe(1);
        expect(slots[0].timestamp).toEqual(NOW);
    });

    it("#getCheapestWindow finds the cheapest contiguous window", () => {
        const slots = EvcsPriceForecast.calculate(
            [
                entry("10:00", 400, 0, 0),
                entry("10:15", 350, 0, 0),
                entry("10:30", 150, 0, 0),
                entry("10:45", 170, 0, 0),
                entry("11:00", 500, 0, 0),
            ],
            SETTINGS,
            NOW,
        );
        const window = EvcsPriceForecast.getCheapestWindow(slots, 2);
        expect(window).toEqual({
            from: new Date("2026-10-02T10:30:00Z"),
            to: new Date("2026-10-02T11:00:00Z"),
            averagePrice: 16,
        });
        expect(EvcsPriceForecast.getCheapestWindow(slots, 6)).toBeNull();
    });
    it("#calculateSurplus follows the priority", () => {
        // PV 5 kW, house 1 kW, storage planned to charge 3 kW
        expect(EvcsPriceForecast.calculateSurplus(5000, 1000, 3000, "CAR")).toBe(4000);
        // 200 W buffer of the controller: 5000 - 1000 - 3000 - 200
        expect(EvcsPriceForecast.calculateSurplus(5000, 1000, 3000, "STORAGE")).toBe(800);
        // storage takes everything
        expect(EvcsPriceForecast.calculateSurplus(5000, 1000, 6000, "STORAGE")).toBe(0);
        // storage discharging (positive ess -> charge 0)
        expect(EvcsPriceForecast.calculateSurplus(500, 2000, 0, "STORAGE")).toBe(0);
    });

    it("#calculate with priority STORAGE: no charging while the storage takes the surplus", () => {
        // 06.10.2026 10:00: PV 5 kW, house 1 kW, storage charges 4 kW (balancing), 42 ct; the controller
        // prices the storage like grid (no reserve) -> the bar shows 42 ct: too expensive
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, priority: "STORAGE", useStorageSurplus: true, storagePrice: 42 };
        const slot = EvcsPriceForecast.calculate([entry("10:00", 420, 5000, 1000, -4000)], settings, NOW)[0];
        expect(slot.surplus).toBe(0);
        expect(slot.storageCharge).toBe(4000);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.NONE);
        expect(slot.chargePower).toBe(0);
        expect(slot.effectivePrice).toBe(42);
    });

    it("#calculate with priority STORAGE: charges from the storage reserve the controller reports", () => {
        // same, but the controller prices the storage at 8 ct (reserve above the evening target) -> the car
        // may take the minimum power from the storage
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, priority: "STORAGE", useStorageSurplus: true, storagePrice: 8 };
        const slot = EvcsPriceForecast.calculate([entry("10:00", 420, 5000, 1000, -4000)], settings, NOW)[0];
        expect(slot.storageAboveTarget).toBe(true);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slot.chargePower).toBe(4140);
        expect(slot.effectivePrice).toBe(8);
    });

    it("#calculate with priority STORAGE: charges from the grid-bound rest when the storage is limited", () => {
        // storage limited to 3 kW: 1 kW would go to grid, 800 W after the 200 W buffer
        // -> blended price with storage at 8 ct: (800*7 + 3340*8)/4140
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, priority: "STORAGE", useStorageSurplus: true, storagePrice: 8 };
        const slot = EvcsPriceForecast.calculate([entry("10:00", 420, 5000, 1000, -3000)], settings, NOW)[0];
        expect(slot.surplus).toBe(800);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slot.chargePower).toBe(4140);
        expect(slot.effectivePrice).toBe(7.81);
    });

    it("#calculate with priority STORAGE: storage priced like grid when the evening target is not reachable", () => {
        // same as above, but storagePrice = grid price 42 ct -> (800*7 + 3340*42)/4140 = 35.2 ct -> locked
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, priority: "STORAGE", useStorageSurplus: true, storagePrice: 42 };
        const slot = EvcsPriceForecast.calculate([entry("10:00", 420, 5000, 1000, -3000)], settings, NOW)[0];
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.NONE);
        expect(slot.effectivePrice).toBe(35.24);
    });
    it("#calculate per quarter-hour: storage price from the planned SoC and the remaining PV surplus", () => {
        // 20 kWh storage, window 20-90 % -> usable 14 kWh, target 80 % = 11.2 kWh net
        const settings: EvcsPriceForecast.Settings = {
            ...SETTINGS, priority: "STORAGE", useStorageSurplus: true, storagePrice: 8, storageCapacity: 20000,
        };
        const e = (time: string, price: number, prod: number, cons: number, ess: number, soc: number) =>
            ({ ...entry(time, price, prod, cons, ess), soc: soc });
        const slots = EvcsPriceForecast.calculate([
            // 10:00: PV 6 kW, house 1 kW, storage charges 3 kW, SoC 50 % (net 43 %): 5.2 kWh missing;
            // remaining surplus after car (4140): (6000-1000-4140)/4 + (8000-1000-4140)/4 = 215 + 715 -> not enough
            e("10:00", 420, 6000, 1000, -3000, 50),
            // 10:15: PV 8 kW, storage charges 3 kW, SoC 88 % (net 97 %) -> above target: storage 8 ct
            e("10:15", 420, 8000, 1000, -3000, 88),
            // 20:00: no PV, SoC 85 % (net 93 %) -> above target: car may charge from storage, 8 ct, reduced
            e("20:00", 500, 0, 1500, 1500, 85),
            // 21:00: no PV, SoC 70 % (net 71 %) -> below target: storage like grid, 50 ct -> too expensive
            e("21:00", 500, 0, 1500, 1500, 70),
        ], settings, NOW);
        // priority STORAGE: the car does not count. 6000 Wh stored + 1250 + 1750 = 9000 Wh, target 11200 Wh
        expect(slots[0].storageSurplus).toBe(-2200);
        expect(slots[0].storagePrice).toBe(42);
        expect(slots[0].storageAboveTarget).toBe(false);
        // 1800 W PV (after the 200 W buffer) at 7 ct + 2340 W storage at 42 ct = 26.78 ct -> below the 30 ct limit
        expect(slots[0].zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slots[0].effectivePrice).toBe(26.78);
        // 13600 Wh + 1750 -> capped at 14000 Wh -> 2800 Wh above the target
        expect(slots[1].storageSurplus).toBe(2800);
        expect(slots[1].storagePrice).toBe(8);
        expect(slots[1].storageAboveTarget).toBe(true);
        expect(slots[1].zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slots[2].storageSurplus).toBe(1800);
        expect(slots[2].storageAboveTarget).toBe(true);
        expect(slots[2].zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slots[2].effectivePrice).toBe(8);
        expect(slots[3].storageSurplus).toBe(-1200);
        expect(slots[3].storageAboveTarget).toBe(false);
        expect(slots[3].zone).toBe(EvcsPriceForecast.Zone.NONE);
        expect(slots[3].storagePrice).toBe(50);
    });

    it("#calculate without price-based charging uses surplus and storage reserve only", () => {
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, enablePriceCharging: false };
        // negative price, 4000 W surplus below the minimum power -> no charging, and never "too expensive"
        let slot = EvcsPriceForecast.calculate([entry("10:00", -10, 5000, 1000)], settings, NOW)[0];
        expect(slot.chargePower).toBe(0);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.NO_SURPLUS);
        // 5000 W surplus covers the minimum power
        slot = EvcsPriceForecast.calculate([entry("10:00", 500, 6000, 1000)], settings, NOW)[0];
        expect(slot.chargePower).toBe(5000);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        // storage reserve reported by the controller covers the minimum power
        slot = EvcsPriceForecast.calculate([entry("10:00", 500, 0, 1000)],
            { ...settings, useStorageSurplus: true, storagePrice: 8 }, NOW)[0];
        expect(slot.chargePower).toBe(4140);
        expect(slot.effectivePrice).toBe(8);
    });

    it("#calculateChargePowerFromPrice with limit 0 charges at negative prices only", () => {
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, priceLimit: 0, priceLimitFullPower: 0 };
        expect(EvcsPriceForecast.calculateChargePowerFromPrice(0, settings)).toBe(0);
        expect(EvcsPriceForecast.calculateChargePowerFromPrice(-1, settings)).toBe(11040);
    });

    it("#calculateStorageSurplus ignores the next day's PV at night", () => {
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, storageTargetSocNet: 85 };
        const e = (iso: string, production: number) => ({
            entry: { timestamp: iso, price: 300, production: production, consumption: 500 },
            timestamp: new Date(iso),
        });
        // 00:00 at night, PV from 08:00 tomorrow: storage 70 % of 14 kWh, target 85 % -> 2100 Wh missing
        const night = [e("2026-10-11T00:00:00", 0), e("2026-10-11T00:15:00", 0), e("2026-10-11T08:00:00", 9000)];
        expect(EvcsPriceForecast.calculateStorageSurplus(night, 9800, 14000, settings)).toBe(-2100);
        // during the day the running PV block counts: priority CAR, 9000 - 500 - 4140 per quarter-hour
        const day = [e("2026-10-11T10:00:00", 9000), e("2026-10-11T10:15:00", 9000), e("2026-10-11T20:00:00", 0)];
        expect(EvcsPriceForecast.calculateStorageSurplus(day, 9800, 14000, settings)).toBe(-2100 + 2180);
    });

    it("#applyLive replaces the running quarter-hour by the state of the controller", () => {
        // 07.10.2026 10:15: forecast PV 5.5 kW -> reduced charging; measured: 4.4 kW, controller not charging,
        // blended price 8 ct (below the limit) -> nothing available right now
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, priority: "STORAGE", useStorageSurplus: true, storagePrice: 8 };
        const forecast = EvcsPriceForecast.calculate([
            entry("10:00", 435, 5500, 2300, -2000),
            entry("10:15", 416, 5800, 1100, -2900),
        ], settings, new Date("2026-10-02T10:05:00Z"));
        expect(forecast[0].zone).toBe(EvcsPriceForecast.Zone.REDUCED);

        const live = EvcsPriceForecast.applyLive(forecast, { chargePower: 0, blendedPrice: 8, production: 4400 },
            settings, new Date("2026-10-02T10:05:00Z"));
        expect(live[0].live).toBe(true);
        expect(live[0].zone).toBe(EvcsPriceForecast.Zone.NO_SURPLUS);
        expect(live[0].chargePower).toBe(0);
        expect(live[0].effectivePrice).toBe(8);
        expect(live[0].production).toBe(4400);
        // the forecast of the next quarter-hour and the input are untouched
        expect(live[1]).toBe(forecast[1]);
        expect(forecast[0].live).toBeUndefined();

        // charging at 4.1 kW -> reduced; too expensive and not charging -> locked
        expect(EvcsPriceForecast.applyLive(forecast, { chargePower: 4140, blendedPrice: 7.8, production: 6000 },
            settings, new Date("2026-10-02T10:05:00Z"))[0].zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(EvcsPriceForecast.applyLive(forecast, { chargePower: 0, blendedPrice: 35, production: 4400 },
            settings, new Date("2026-10-02T10:05:00Z"))[0].zone).toBe(EvcsPriceForecast.Zone.NONE);
        // no running quarter-hour (now is before the first slot) or no live data -> unchanged
        expect(EvcsPriceForecast.applyLive(forecast, { chargePower: 0, blendedPrice: 8, production: 0 },
            settings, new Date("2026-10-02T09:00:00Z"))).toBe(forecast);
        expect(EvcsPriceForecast.applyLive(forecast, null, settings, new Date("2026-10-02T10:05:00Z"))).toBe(forecast);
    });
});
