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
        expect(EvcsPriceForecast.calculateSurplus(5000, 1000, 3000, "STORAGE")).toBe(1000);
        // storage takes everything
        expect(EvcsPriceForecast.calculateSurplus(5000, 1000, 6000, "STORAGE")).toBe(0);
        // storage discharging (positive ess -> charge 0)
        expect(EvcsPriceForecast.calculateSurplus(500, 2000, 0, "STORAGE")).toBe(0);
    });

    it("#calculate with priority STORAGE: no charging while the storage takes the surplus", () => {
        // 06.10.2026 10:00: PV 5 kW, house 1 kW, storage charges 4 kW (balancing), 42 ct
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, priority: "STORAGE", useStorageSurplus: true, storagePrice: 8 };
        const slot = EvcsPriceForecast.calculate([entry("10:00", 420, 5000, 1000, -4000)], settings, NOW)[0];
        expect(slot.surplus).toBe(0);
        expect(slot.storageCharge).toBe(4000);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.NO_SURPLUS);
        expect(slot.chargePower).toBe(0);
    });

    it("#calculate with priority STORAGE: charges from the grid-bound rest when the storage is limited", () => {
        // storage limited to 3 kW: 1 kW would go to grid -> blended price with storage at 8 ct: (1000*7 + 3140*8)/4140
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, priority: "STORAGE", useStorageSurplus: true, storagePrice: 8 };
        const slot = EvcsPriceForecast.calculate([entry("10:00", 420, 5000, 1000, -3000)], settings, NOW)[0];
        expect(slot.surplus).toBe(1000);
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slot.chargePower).toBe(4140);
        expect(slot.effectivePrice).toBe(7.76);
    });

    it("#calculate with priority STORAGE: storage priced like grid when the evening target is not reachable", () => {
        // same as above, but storagePrice = grid price 42 ct -> (1000*7 + 3140*42)/4140 = 33.5 ct -> locked
        const settings: EvcsPriceForecast.Settings = { ...SETTINGS, priority: "STORAGE", useStorageSurplus: true, storagePrice: 42 };
        const slot = EvcsPriceForecast.calculate([entry("10:00", 420, 5000, 1000, -3000)], settings, NOW)[0];
        expect(slot.zone).toBe(EvcsPriceForecast.Zone.NONE);
        expect(slot.effectivePrice).toBe(33.55);
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
            // 21:00: no PV, SoC 70 % (net 71 %) -> below target: storage like grid, no surplus -> too expensive? no:
            // nothing available (no PV, storage below target) -> NO_SURPLUS
            e("21:00", 500, 0, 1500, 1500, 70),
        ], settings, NOW);
        expect(slots[0].storagePrice).toBe(42);
        expect(slots[0].storageAboveTarget).toBe(false);
        // 2000 W PV at 7 ct + 2140 W storage at 42 ct = 25.09 ct -> still below the 30 ct limit
        expect(slots[0].zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slots[0].effectivePrice).toBe(25.09);
        expect(slots[1].storagePrice).toBe(8);
        expect(slots[1].storageAboveTarget).toBe(true);
        expect(slots[1].zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slots[2].storageAboveTarget).toBe(true);
        expect(slots[2].zone).toBe(EvcsPriceForecast.Zone.REDUCED);
        expect(slots[2].effectivePrice).toBe(8);
        expect(slots[3].storageAboveTarget).toBe(false);
        expect(slots[3].zone).toBe(EvcsPriceForecast.Zone.NO_SURPLUS);
        expect(slots[3].storagePrice).toBe(50);
    });
});
