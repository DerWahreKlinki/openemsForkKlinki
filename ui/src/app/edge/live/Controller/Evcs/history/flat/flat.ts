import { ChangeDetectionStrategy, Component } from "@angular/core";
import { differenceInDays } from "date-fns";

import { ChronoUnit, Resolution } from "src/app/edge/history/shared";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { QueryHistoricTimeseriesDataRequest } from "src/app/shared/jsonrpc/request/queryHistoricTimeseriesDataRequest";
import { QueryHistoricTimeseriesDataResponse } from "src/app/shared/jsonrpc/response/queryHistoricTimeseriesDataResponse";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";
import { Language } from "src/app/shared/type/language";

/**
 * History tile of Controller.Evcs.Price, from the existing history only: the energy of the
 * wallbox meter in the period, and from the time series the cost at the blended price, the
 * storage share, the number of plug-ins and the charging time.
 */
@Component({
    selector: "oe-evcs-price-history-widget",
    templateUrl: "./flat.html",
    changeDetection: ChangeDetectionStrategy.Eager,
    standalone: false,
})
export class EvcsPriceHistoryFlatComponent extends AbstractFlatWidget {

    /** Below this power in [W] the car does not charge. */
    private static readonly CHARGING_W = 100;
    /** Keba plug state from which a car is plugged in. */
    private static readonly PLUGGED_IN = 5;

    protected charged: number | null = null;
    protected fromStorage: number | null = null;
    protected cost: string | null = null;
    protected averagePrice: string | null = null;
    protected sessions: number | null = null;
    protected chargingSeconds: number | null = null;

    // the period the time series were queried for, to query once per period
    private queriedPeriod: string | null = null;
    // the cost of the period in [ct]; null until the time series arrived
    private costCt: number | null = null;

    override getChannelAddresses(): ChannelAddress[] {
        const evcsId = this.evcsId();
        return evcsId == null ? [] : [new ChannelAddress(evcsId, "ActiveConsumptionEnergy")];
    }

    protected override onCurrentData(currentData: CurrentData) {
        const evcsId = this.evcsId();
        if (evcsId == null) {
            return;
        }
        this.charged = currentData.allComponents[evcsId + "/ActiveConsumptionEnergy"] ?? null;
        this.updateAveragePrice();
        const period = this.service.historyPeriod.value;
        const key = period.from.toISOString() + "|" + period.to.toISOString();
        if (key !== this.queriedPeriod) {
            this.queriedPeriod = key;
            void this.queryTimeSeries(evcsId, period.from, period.to);
        }
    }

    private evcsId(): string | null {
        return this.component?.properties?.["evcs.id"] ?? null;
    }

    /**
     * Sums the time series of the period: cost, storage share, plug-ins and charging time.
     *
     * @param evcsId the wallbox
     * @param from   the start of the period
     * @param to     the end of the period
     */
    private async queryTimeSeries(evcsId: string, from: Date, to: Date): Promise<void> {
        const resolution: Resolution = Math.abs(differenceInDays(to, from)) <= 31
            ? { value: 15, unit: ChronoUnit.Type.MINUTES }
            : { value: 1, unit: ChronoUnit.Type.HOURS };
        const power = evcsId + "/ActivePower";
        const plug = evcsId + "/Plug";
        const blended = this.componentId + "/BlendedPrice";
        const storage = this.componentId + "/StoragePowerForCar";
        try {
            const response = await this.edge.sendRequest<QueryHistoricTimeseriesDataResponse>(this.websocket,
                new QueryHistoricTimeseriesDataRequest(from, to,
                    [power, plug, blended, storage].map((c) => ChannelAddress.fromString(c)), resolution));
            const { timestamps, data } = response.result;
            const hours = resolution.unit === ChronoUnit.Type.HOURS ? resolution.value : resolution.value / 60;
            let costCt = 0;
            let storageWh = 0;
            let seconds = 0;
            let sessions = 0;
            let lastPlugged: boolean | null = null;
            for (let i = 0; i < timestamps.length; i++) {
                const p = Math.max(data[power]?.[i] ?? 0, 0);
                if (p >= EvcsPriceHistoryFlatComponent.CHARGING_W) {
                    seconds += hours * 3600;
                    const price = data[blended]?.[i];
                    if (price != null) {
                        // [W] * [h] / 1000 = [kWh]; [Currency/MWh] / 10 = [ct/kWh]
                        costCt += p * hours / 1000 * price / 10;
                    }
                    storageWh += Math.min(Math.max(data[storage]?.[i] ?? 0, 0), p) * hours;
                }
                const plugState = data[plug]?.[i];
                if (plugState != null) {
                    const plugged = plugState >= EvcsPriceHistoryFlatComponent.PLUGGED_IN;
                    if (plugged && lastPlugged === false) {
                        sessions++;
                    }
                    if (lastPlugged == null && plugged) {
                        // plugged in at the start of the period: counts as one
                        sessions++;
                    }
                    lastPlugged = plugged;
                }
            }
            const locale: string = (Language.getByKey(localStorage.LANGUAGE) ?? Language.DEFAULT).i18nLocaleKey;
            this.cost = new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }).format(costCt / 100);
            this.costCt = costCt;
            this.fromStorage = Math.round(storageWh);
            this.chargingSeconds = Math.round(seconds);
            this.sessions = sessions;
            this.updateAveragePrice();
        } catch (reason) {
            console.warn("Evcs price history: time series not available", reason);
            this.queriedPeriod = null;
        }
    }

    private updateAveragePrice() {
        if (this.costCt == null || this.charged == null || this.charged <= 0) {
            this.averagePrice = null;
            return;
        }
        const locale: string = (Language.getByKey(localStorage.LANGUAGE) ?? Language.DEFAULT).i18nLocaleKey;
        this.averagePrice = new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 })
            .format(this.costCt / (this.charged / 1000)) + " ct/kWh";
    }
}
