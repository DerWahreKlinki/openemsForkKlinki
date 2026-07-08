import { CommonModule } from "@angular/common";
import { Component } from "@angular/core";
import { ReactiveFormsModule } from "@angular/forms";
import { IonicModule } from "@ionic/angular";
import { TranslateModule } from "@ngx-translate/core";
import { BaseChartDirective } from "ng2-charts";
import { NgxSpinnerModule } from "ngx-spinner";
import { SharedPeakShavingChartComponent } from "src/app/edge/live/Controller/peak-shaving/shared/shared-chart";
import { ChartComponentsModule } from "src/app/shared/components/chart/chart.module";
import { HistoryDataErrorModule } from "src/app/shared/components/history-data-error/history-data-error.module";
import { ChannelAddress, ChartConstants } from "src/app/shared/shared";
import { HistoryUtils } from "src/app/shared/utils/utils";

@Component({
    selector: "oe-controller-peakshaving-threshold-chart",
    templateUrl:
        "../../../../../../../shared/components/chart/abstracthistorychart.html",
    standalone: true,
    imports: [
        BaseChartDirective,
        ReactiveFormsModule,
        CommonModule,
        IonicModule,
        TranslateModule,
        ChartComponentsModule,
        HistoryDataErrorModule,
        NgxSpinnerModule,
    ],
})
export class ThresholdPeakshavingChartComponent extends SharedPeakShavingChartComponent {

    protected override getChartData(): HistoryUtils.ChartData {
        const chartData = super.getChartData();

        return {
            ...chartData,

            input: [
                ...chartData.input,
                {
                    name: "PeakShavingThresholdPower",
                    powerChannel: new ChannelAddress(
                        this.component.id,
                        "_PropertyPeakShavingThresholdPower",
                    ),
                },
                {
                    name: "PeakShavingPower",
                    powerChannel: new ChannelAddress(
                        this.component.id,
                        "PeakShavingPower",
                    ),
                },
                {
                    name: "PeakShavingTargetPower",
                    powerChannel: new ChannelAddress(
                        this.component.id,
                        "PeakShavingTargetPower",
                    ),
                },
                {
                    name: "GridPowerWithoutPeakShaving",
                    powerChannel: new ChannelAddress(
                        this.component.id,
                        "GridPowerWithoutPeakShaving",
                    ),
                },
            ],

            output: (data: HistoryUtils.ChannelData) => [
                ...chartData.output(data),

                {
                    name: "Peak Shaving Threshold",
                    color: ChartConstants.Colors.GREY,
                    converter: () => data["PeakShavingThresholdPower"],
                    hideShadow: true,
                    borderDash: [3, 3],
                },
                {
                    name: "Peak Shaving Power",
                    color: ChartConstants.Colors.RED,
                    converter: () => data["PeakShavingPower"],
                },
                {
                    name: "Peak Shaving Target Power",
                    color: ChartConstants.Colors.GREEN,
                    converter: () => data["PeakShavingTargetPower"],
                    hideShadow: true,
                    borderDash: [10, 10],
                },
                {
                    name: "Grid Power without Peak Shaving",
                    color: ChartConstants.Colors.BLUE_GREY,
                    converter: () => data["GridPowerWithoutPeakShaving"],
                },
            ],
        };
    }


}
