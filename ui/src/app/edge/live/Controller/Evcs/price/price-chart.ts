import { formatNumber } from "@angular/common";
import { ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnChanges, SimpleChanges } from "@angular/core";
import { ActivatedRoute } from "@angular/router";
import { TranslateService } from "@ngx-translate/core";
import * as Chart from "chart.js";

import { AbstractHistoryChart } from "src/app/shared/components/chart/abstracthistorychart";
import { ChartConstants } from "src/app/shared/components/chart/chart.constants";
import { NavigationService } from "src/app/shared/components/navigation/service/navigation.service";
import { Currency, Edge, EdgeConfig, Logger, Service, Websocket } from "src/app/shared/shared";
import { Language } from "src/app/shared/type/language";
import { AssertionUtils } from "src/app/shared/utils/assertions/assertions.utils";
import { ColorUtils } from "src/app/shared/utils/color/color.utils";
import { ChartAxis, HistoryUtils, TimeOfUseTariffUtils, YAxisType } from "src/app/shared/utils/utils";

import { EvcsPriceForecast } from "./price-forecast";

/**
 * Shows the forecast of a 'Controller.Evcs.Price': the effective charge price as bars, colored by the expected
 * behaviour of the controller, and the predicted PV production as dashed line.
 */
@Component({
    selector: "oe-evcs-price-chart",
    templateUrl: "../../../../history/abstracthistorychart.html",
    changeDetection: ChangeDetectionStrategy.Eager,
    standalone: false,
})
export class EvcsPriceChartComponent extends AbstractHistoryChart implements OnChanges {
    @Input({ required: true }) public override edge!: Edge;
    @Input({ required: true }) public override component!: EdgeConfig.Component;
    @Input({ required: true }) public priceLimit!: number;
    @Input({ required: true }) public priceLimitFullPower!: number;
    @Input({ required: true }) public priceChargePower!: number;
    @Input({ required: true }) public pvPrice!: number;
    @Input() public minPower: number | null = null;
    @Input() public maxPower: number | null = null;

    private currencyLabel: Currency.Label | undefined = undefined;
    private currencyUnit: Currency.Unit | undefined = undefined;
    private slots: EvcsPriceForecast.Slot[] = [];

    constructor(
        public override service: Service,
        public override cdRef: ChangeDetectorRef,
        protected override translate: TranslateService,
        protected override route: ActivatedRoute,
        protected override logger: Logger,
        protected override navigationService: NavigationService,
        private websocket: Websocket,
    ) {
        super(service, cdRef, translate, route, logger, navigationService);
    }

    public ngOnChanges(changes: SimpleChanges): void {
        if (!this.config) {
            return;
        }
        if (Object.keys(changes).length > 0) {
            this.updateChart();
        }
    }

    protected override getChartData(): HistoryUtils.ChartData {
        return {
            input: [],
            output: () => [],
            tooltip: {
                formatNumber: ChartConstants.NumberFormat.ZERO_TO_TWO,
            },
            yAxes: [
                {
                    unit: YAxisType.CURRENCY,
                    position: "left",
                    yAxisId: ChartAxis.LEFT,
                    customTitle: this.currencyUnit ?? "",
                    scale: { dynamicScale: true },
                },
                {
                    unit: YAxisType.POWER,
                    position: "right",
                    yAxisId: ChartAxis.RIGHT,
                    displayGrid: false,
                },
            ],
        };
    }

    protected override getChartHeight(): number | null {
        return TimeOfUseTariffUtils.getChartHeight(this.service.getIsSmartphoneResolution());
    }

    protected override async loadChart(): Promise<void> {
        if (this.edge == null || this.component == null || this.config == null) {
            return;
        }

        this.labels = [];
        this.errorResponse = null;
        this.loading = true;
        this.chartType = "line";

        try {
            const meta: EdgeConfig.Component = this.config.getComponent("_meta");
            const currency = this.config.getPropertyFromComponent<string>(meta, "currency");
            if (currency != null) {
                this.currencyLabel = Currency.getCurrencyLabelByCurrency(currency);
                this.currencyUnit = Currency.getChartCurrencyUnitLabel(currency);
            }

            this.chartObject = this.getChartData();

            this.slots = await EvcsPriceForecast.load(this.edge, this.websocket, this.config, {
                priceLimit: this.priceLimit ?? 0,
                priceLimitFullPower: this.priceLimitFullPower ?? 0,
                priceChargePower: this.priceChargePower ?? 0,
                pvPrice: this.pvPrice ?? 0,
                minPower: EvcsPriceForecast.getSettings(this.component, this.minPower, this.maxPower).minPower,
                maxPower: this.maxPower,
            });

            if (this.slots.length === 0) {
                this.initializeChart();
                return;
            }

            this.labels = this.slots.map((slot) => slot.timestamp);
            this.datasets = this.createDatasets(this.slots);
            this.legendOptions = this.datasets.map((dataset) => ({
                label: dataset.label?.toString() ?? "",
                strokeThroughHidingStyle: false,
                hideLabelInLegend: false,
            }));
            this.options = this.createOptions();

            this.loading = false;
            this.stopSpinner();

            this.setChartConfig.emit({
                chartType: this.chartType,
                datasets: this.datasets,
                labels: this.labels,
                options: this.options,
            });
        } catch (error) {
            console.error(error);
            this.initializeChart();
        }
    }

    private createDatasets(slots: EvcsPriceForecast.Slot[]): Chart.ChartDataset[] {
        const bar = (zone: EvcsPriceForecast.Zone, translationKey: string, color: string): Chart.ChartDataset => ({
            type: "bar",
            label: this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.PRICE_FORECAST." + translationKey),
            data: slots.map((slot) => (slot.zone === zone ? slot.effectivePrice : null)),
            hidden: false,
            order: 1,
            // All zones share the same position on the x axis
            grouped: false,
            yAxisID: ChartAxis.LEFT,
            backgroundColor: ColorUtils.rgbStringToRgba(color, 0.5),
            borderColor: ColorUtils.rgbStringToRgba(color, 1),
        });

        return [
            bar(EvcsPriceForecast.Zone.FULL, "ZONE_FULL", ChartConstants.Colors.GREEN),
            bar(EvcsPriceForecast.Zone.REDUCED, "ZONE_REDUCED", ChartConstants.Colors.ORANGE),
            bar(EvcsPriceForecast.Zone.NONE, "ZONE_NONE", ChartConstants.Colors.RED),
            {
                type: "line",
                label: this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.PRICE_FORECAST.PRODUCTION"),
                data: slots.map((slot) => slot.production / 1000), // [W] to [kW]
                hidden: false,
                order: 0,
                yAxisID: ChartAxis.RIGHT,
                borderDash: ChartConstants.Plugins.Datasets.DEFAULT_BORDER_DASH,
                pointRadius: 0,
                fill: false,
                backgroundColor: ColorUtils.rgbStringToRgba(ChartConstants.Colors.BLUE, 0.2),
                borderColor: ColorUtils.rgbStringToRgba(ChartConstants.Colors.BLUE, 1),
            },
        ];
    }

    private createOptions(): Chart.ChartOptions {
        let options = AbstractHistoryChart.getDefaultXAxisOptions(this.xAxisScalingType, this.service, this.labels);

        AssertionUtils.assertIsDefined(this.chartObject);

        for (const yAxis of this.chartObject.yAxes) {
            options = AbstractHistoryChart.getYAxisOptions(
                options,
                yAxis,
                this.translate,
                "line",
                this.datasets,
                true,
                this.chartObject.tooltip.formatNumber,
            );
        }

        const locale: string = (Language.getByKey(localStorage.LANGUAGE) ?? Language.DEFAULT).i18nLocaleKey;
        const currencyLabel = this.currencyLabel ?? "";
        const tooltipCallbacks = options.plugins?.tooltip?.callbacks;
        const xScale = options.scales?.x as Chart.TimeScaleOptions | undefined;
        const rightScale = options.scales?.[ChartAxis.RIGHT];
        const leftScale = options.scales?.[ChartAxis.LEFT];

        if (xScale != null) {
            xScale.offset = false;
            xScale.ticks = {
                ...xScale.ticks,
                source: "auto",
                autoSkip: false,
                maxTicksLimit: 30,
                color: getComputedStyle(document.documentElement).getPropertyValue("--ion-color-chart-xAxis-ticks"),
                callback: (value) => {
                    const date = new Date(value as string | number);
                    return date.getMinutes() === 0 ? `${date.getHours()}:00` : "";
                },
            };
        }

        if (tooltipCallbacks != null) {
            tooltipCallbacks.label = (item: Chart.TooltipItem<any>): string | undefined => {
                const value = item.dataset.data[item.dataIndex];
                if (value == null) {
                    return undefined;
                }
                const label = item.dataset.label ?? "";
                if (item.dataset.yAxisID === ChartAxis.RIGHT) {
                    return label + ": " + formatNumber(value, locale, "1.0-1") + " kW";
                }
                return (
                    label + ": " + formatNumber(value, locale, ChartConstants.NumberFormat.TWO) + " " + currencyLabel
                );
            };

            tooltipCallbacks.afterBody = (items: Chart.TooltipItem<any>[]): string[] => {
                const slot = items.length > 0 ? this.slots[items[0].dataIndex] : null;
                if (slot == null) {
                    return [];
                }
                const prefix = "EDGE.INDEX.WIDGETS.EVCS.PRICE_FORECAST.";
                return [
                    this.translate.instant(prefix + "GRID_PRICE") +
                        ": " +
                        formatNumber(slot.price, locale, ChartConstants.NumberFormat.TWO) +
                        " " +
                        currencyLabel,
                    this.translate.instant(prefix + "PV_SHARE") + ": " + slot.pvShare + " %",
                    this.translate.instant(prefix + "CHARGE_POWER") +
                        ": " +
                        formatNumber(slot.chargePower / 1000, locale, "1.0-1") +
                        " kW",
                ];
            };
        }

        if (options.plugins?.tooltip != null) {
            options.plugins.tooltip.mode = "index";
        }

        if (rightScale != null) {
            rightScale.grid = {
                ...rightScale.grid,
                display: false,
            };
            rightScale.suggestedMin = 0;
            rightScale.suggestedMax = 1;
        }

        if (leftScale != null && options.scales != null) {
            const leftDatasets = this.datasets.filter(
                (dataset) => (dataset as { yAxisID?: string }).yAxisID === ChartAxis.LEFT,
            );

            options.scales[ChartAxis.LEFT] = {
                ...leftScale,
                ...ChartConstants.DEFAULT_Y_SCALE_OPTIONS(
                    {
                        position: "left",
                        unit: YAxisType.CURRENCY,
                        yAxisId: ChartAxis.LEFT,
                        customTitle: this.currencyUnit,
                        scale: { dynamicScale: true },
                    },
                    this.translate,
                    "bar",
                    leftDatasets,
                    true,
                ),
            };
        }

        // Bars start at zero, as long as there are no negative prices
        const leftAxis = options.scales?.[ChartAxis.LEFT];
        if (leftAxis != null && this.slots.every((slot) => slot.effectivePrice >= 0)) {
            leftAxis.min = 0;
        }

        options.animation = false;

        return options;
    }
}
