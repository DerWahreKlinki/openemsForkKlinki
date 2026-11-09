import { ChangeDetectionStrategy, Component } from "@angular/core";
import { interval, startWith, Subscription } from "rxjs";
import { AbstractModal } from "src/app/shared/components/modal/abstractModal";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";
import { PriceForecast } from "../shared/price-forecast.model";

/**
 * Modal of a TimeOfUseTariff.Forecast (fork): today in detail and the outlook per day.
 */
@Component({
    templateUrl: "./modal.html",
    styleUrls: ["../shared/price-forecast.scss"],
    changeDetection: ChangeDetectionStrategy.Eager,
    standalone: false,
})
export class PriceForecastModalComponent extends AbstractModal {

    private static readonly FETCH_INTERVAL_MS = 15 /* minutes */ * 60 * 1000;
    private static readonly PREVIEW_STEP_HOURS = 3;

    protected outlook: PriceForecast.Outlook | null = null;
    protected current: PriceForecast.Quarter | null = null;
    protected currentRating: number | null = null;
    protected today: PriceForecast.Day | null = null;
    protected upcoming: PriceForecast.Quarter[] = [];
    protected days: PriceForecast.Day[] = [];

    private fetchSubscription?: Subscription;

    public override ngOnDestroy(): void {
        this.fetchSubscription?.unsubscribe();
        super.ngOnDestroy();
    }

    protected override onIsInitialized(): void {
        this.fetchSubscription = interval(PriceForecastModalComponent.FETCH_INTERVAL_MS)
            .pipe(startWith(0))
            .subscribe(() => this.fetchOutlook());
    }

    protected override getChannelAddresses(): ChannelAddress[] {
        if (this.component == null) {
            return [];
        }
        return [new ChannelAddress(this.component.id, "CurrentRating")];
    }

    protected override onCurrentData(currentData: CurrentData): void {
        this.applyOutlook();
    }

    protected rate(price: number | null | undefined): number | null {
        return this.outlook ? PriceForecast.rate(price, this.outlook.thresholds) : null;
    }

    private fetchOutlook(): void {
        if (this.edge == null || this.component == null) {
            return;
        }
        PriceForecast.fetch(this.edge, this.websocket, this.component.id).then(outlook => {
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
        this.upcoming = PriceForecast.upcoming(this.outlook, PriceForecastModalComponent.PREVIEW_STEP_HOURS, 8, now);
        this.days = PriceForecast.daysAfter(this.outlook, now);
        this.currentRating = this.outlook && this.current
            ? PriceForecast.rate(this.current.price, this.outlook.thresholds)
            : null;
    }
}
