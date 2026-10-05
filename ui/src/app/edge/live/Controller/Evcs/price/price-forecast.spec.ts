import { EvcsPriceForecast } from "./price-forecast";

describe("EvcsPriceForecast", () => {
    const SETTINGS: EvcsPriceForecast.Settings = {
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
    ): EvcsPriceForecast.ScheduleEntry {
        return {
            timestamp: "2026-10-02T" + time + ":00Z",
            price: price,
            production: production,
            consumption: consumption,
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
});
