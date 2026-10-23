import { NgModule } from "@angular/core";
import { BrowserModule } from "@angular/platform-browser";
import { SharedModule } from "src/app/shared/shared.module";
import { EvcsPriceHistoryChartComponent } from "./chart/chart";
import { EvcsPriceHistoryFlatComponent } from "./flat/flat";
import { EvcsPriceHistoryOverviewComponent } from "./overview/overview";

@NgModule({
    imports: [
        BrowserModule,
        SharedModule,
    ],
    declarations: [
        EvcsPriceHistoryFlatComponent,
        EvcsPriceHistoryOverviewComponent,
        EvcsPriceHistoryChartComponent,
    ],
    exports: [
        EvcsPriceHistoryFlatComponent,
        EvcsPriceHistoryOverviewComponent,
        EvcsPriceHistoryChartComponent,
    ],
})
export class ControllerEvcsPriceHistory { }
