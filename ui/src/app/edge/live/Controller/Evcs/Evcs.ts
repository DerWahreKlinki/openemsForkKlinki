import { NgModule } from "@angular/core";
import { BrowserModule } from "@angular/platform-browser";
import { RangeSliderComponent } from "src/app/shared/components/range-slider/range-slider";
import { SharedModule } from "src/app/shared/shared.module";
import { FlatComponent } from "./flat/flat";
import { ModalComponent } from "./modal/modal";
import { PopoverComponent } from "./popover/popover";
import { EvcsPriceBandComponent } from "./price/price-band";
import { EvcsPriceChartComponent } from "./price/price-chart";

@NgModule({
    imports: [
        BrowserModule,
        SharedModule,
        RangeSliderComponent,
    ],
    declarations: [
        FlatComponent,
        ModalComponent,
        PopoverComponent,
        EvcsPriceBandComponent,
        EvcsPriceChartComponent,
    ],
    exports: [
        FlatComponent,
    ],
})
export class Controller_Evcs { }
