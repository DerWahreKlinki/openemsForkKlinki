import { ChangeDetectionStrategy, Component } from "@angular/core";
import { AbstractHistoryChartOverview } from "src/app/shared/components/chart/abstractHistoryChartOverview";

@Component({
    selector: "oe-evcs-price-history-overview",
    templateUrl: "./overview.html",
    changeDetection: ChangeDetectionStrategy.Eager,
    standalone: false,
})
export class EvcsPriceHistoryOverviewComponent extends AbstractHistoryChartOverview {}
