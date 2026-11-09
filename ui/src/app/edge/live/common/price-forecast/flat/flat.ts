import { ChangeDetectionStrategy, Component } from "@angular/core";
import { interval, startWith, Subscription } from "rxjs";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { Modal } from "src/app/shared/components/flat/flat";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";
import { PriceForecastModalComponent } from "../modal/modal";
import { PriceForecast } from "../shared/price-forecast.model";

/**
 * Live widget of a TimeOfUseTariff.Forecast (fork): today's price with rating and the next hours.
 */
@Component({
    selector: "price-forecast",
    templateUrl: "./flat.html",
    styleUrls: ["../shared/price-forecast.scss"],
    changeDetection: ChangeDetectionStrategy.Eager,
    standalone: false,
})
export class PriceForecastComponent extends AbstractFlatWidget {

    private static readonly FETCH_INTERVAL_MS = 15 /* minutes */ * 60 * 1000;
    private static readonly PREVIEW_STEP_HOURS = 3;

    protected outlook: PriceForecast.Outlook | null = null;
    protected current: PriceForecast.Quarter | null = null;
    protected currentRating: number | null = null;
    protected today: PriceForecast.Day | null = null;
    protected upcoming: PriceForecast.Quarter[] = [];
    protected isSmartphone: boolean = false;

    private fetchSubscription?: Subscription;

    protected get modalComponent(): Modal {
        return {
            component: PriceForecastModalComponent,
            componentProps: { component: this.component },
        };
    }

    public override ngOnDestroy(): void {
        this.fetchSubscription?.unsubscribe();
        super.ngOnDestroy();
    }

    protected override afterIsInitialized(): void {
        this.isSmartphone = this.service.getIsSmartphoneResolution();
        this.fetchSubscription = interval(PriceForecastComponent.FETCH_INTERVAL_MS)
            .pipe(startWith(0))
            .subscribe(() => this.fetchOutlook());
    }

    protected override getChannelAddresses(): ChannelAddress[] {
        if (this.componentId == null) {
            return [];
        }
        return [
            new ChannelAddress(this.componentId, "CurrentPrice"),
            new ChannelAddress(this.componentId, "CurrentRating"),
        ];
    }

    protected override onCurrentData(currentData: CurrentData): void {
        if (this.componentId == null) {
            return;
        }
        const rating = currentData.allComponents[this.componentId + "/CurrentRating"];
        if (typeof rating === "number") {
            this.currentRating = rating;
        }
        // the quarter changed: refresh the derived views from the last outlook
        this.applyOutlook();
    }

    protected rate(price: number | null | undefined): number | null {
        return this.outlook ? PriceForecast.rate(price, this.outlook.thresholds) : null;
    }

    private fetchOutlook(): void {
        if (this.edge == null || this.componentId == null) {
            return;
        }
        PriceForecast.fetch(this.edge, this.websocket, this.componentId).then(outlook => {
            this.outlook = outlook;
            this.applyOutlook();
        }).catch(error => {
            console.warn("Error fetching price forecast", error);
        });
    }

    private applyOutlook(): void {
        const now = new Date();
        this.current = PriceForecast.current(this.outlook, now);
        this.today = PriceForecast.dayOf(this.outlook, now);
        this.upcoming = PriceForecast.upcoming(this.outlook, PriceForecastComponent.PREVIEW_STEP_HOURS,
            this.isSmartphone ? 5 : 6, now);
        if (this.outlook && this.current) {
            this.currentRating = PriceForecast.rate(this.current.price, this.outlook.thresholds);
        }
    }
}
