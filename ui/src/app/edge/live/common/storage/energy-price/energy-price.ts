import { formatNumber } from "@angular/common";
import { ChangeDetectionStrategy, Component, Input, OnChanges, ViewChild } from "@angular/core";
import { TranslateService } from "@ngx-translate/core";
import { ChartData, ChartOptions, TooltipItem } from "chart.js";
import { BaseChartDirective } from "ng2-charts";
import { CommonUiModule } from "src/app/shared/common-ui.module";
import { PipeComponentsModule } from "src/app/shared/pipe/pipe.module";
import { Language } from "src/app/shared/type/language";

/**
 * The content of one ESS by its origin (grid, PV, unknown) as a ring, with the
 * price of a delivered kWh in the middle. Reads the channels of an
 * Ess.EnergyPrice component from the current data.
 */
@Component({
    selector: "oe-storage-energy-price",
    templateUrl: "./energy-price.html",
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [CommonUiModule, PipeComponentsModule, BaseChartDirective],
})
export class StorageEnergyPriceComponent implements OnChanges {

    /** Colours of the ring: grid, PV, unknown */
    public static readonly COLORS = ["rgb(220, 53, 69)", "rgb(255, 193, 7)", "rgb(160, 160, 160)"];

    /** Id of the Ess.EnergyPrice component */
    @Input({ required: true }) public componentId!: string;
    /** Current channel values of the edge, by channel address */
    @Input({ required: true }) public currentData!: { [channel: string]: any };

    @ViewChild(BaseChartDirective) private chart?: BaseChartDirective;

    protected readonly colors = StorageEnergyPriceComponent.COLORS;
    /** Price of a delivered kWh [ct/kWh] */
    protected price: number | null = null;
    protected storedEnergy: number | null = null;
    protected gridEnergy: number | null = null;
    protected pvEnergy: number | null = null;
    protected unpricedEnergy: number | null = null;
    protected efficiency: number | null = null;
    protected chargedGridEnergy: number | null = null;
    protected chargedPvEnergy: number | null = null;
    protected priceMissing: boolean = false;

    protected data: ChartData<"doughnut"> = {
        labels: [],
        datasets: [{
            data: [0, 0, 0],
            backgroundColor: StorageEnergyPriceComponent.COLORS,
            borderWidth: 1,
        }],
    };

    protected options: ChartOptions<"doughnut">;

    constructor(private translate: TranslateService) {
        const locale = Language.getByKey(localStorage.LANGUAGE)?.i18nLocaleKey ?? "de";
        this.options = {
            responsive: true,
            maintainAspectRatio: true,
            cutout: "68%",
            animation: { duration: 300 },
            plugins: {
                legend: { display: false },
                tooltip: {
                    callbacks: {
                        label: (item: TooltipItem<"doughnut">) =>
                            " " + item.label + ": " + formatNumber((item.raw as number) / 1000, locale, "1.0-1") + " kWh",
                    },
                },
                // chartjs-plugin-datalabels, if registered: the share on the segment
                datalabels: {
                    color: "#fff",
                    font: { weight: "bold" },
                    formatter: (value: number, context: any) => {
                        const total = (context.dataset.data as number[]).reduce((sum, v) => sum + (v ?? 0), 0);
                        const share = total > 0 ? value / total * 100 : 0;
                        return share >= 8 ? Math.round(share) + " %" : "";
                    },
                },
            } as any,
        };
        this.data.labels = [
            this.translate.instant("EDGE.INDEX.WIDGETS.ENERGY_PRICE.GRID"),
            this.translate.instant("EDGE.INDEX.WIDGETS.ENERGY_PRICE.PV"),
            this.translate.instant("EDGE.INDEX.WIDGETS.ENERGY_PRICE.UNKNOWN"),
        ];
    }

    ngOnChanges(): void {
        const read = (channel: string): number | null => {
            const value = this.currentData?.[this.componentId + "/" + channel];
            return value == null || isNaN(value) ? null : Number(value);
        };
        const price = read("Price");
        this.price = price == null ? null : price / 10;
        this.storedEnergy = read("StoredEnergy");
        this.gridEnergy = read("StoredGridEnergy");
        this.pvEnergy = read("StoredPvEnergy");
        this.unpricedEnergy = read("UnpricedEnergy");
        this.efficiency = read("Efficiency");
        this.chargedGridEnergy = read("ChargeGridEnergy");
        this.chargedPvEnergy = read("ChargePvEnergy");
        this.priceMissing = this.currentData?.[this.componentId + "/PriceMissing"] == 1;
        this.data.datasets[0].data = [this.gridEnergy ?? 0, this.pvEnergy ?? 0, this.unpricedEnergy ?? 0];
        this.chart?.update();
    }

    /**
     * Share of a part of the content.
     *
     * @param value the part [Wh]
     * @returns the share [%]; null without content
     */
    protected share(value: number | null): number | null {
        const total = (this.gridEnergy ?? 0) + (this.pvEnergy ?? 0) + (this.unpricedEnergy ?? 0);
        if (value == null || total <= 0) {
            return null;
        }
        return Math.round(value / total * 100);
    }
}
