// @ts-strict-ignore
import { ChangeDetectionStrategy, ChangeDetectorRef, Component, effect } from "@angular/core";
import { ActivatedRoute } from "@angular/router";
import { TranslateService } from "@ngx-translate/core";
import * as Chart from "chart.js";
import { calculateResolution, ChronoUnit, Resolution } from "src/app/edge/history/shared";
import { AbstractHistoryChart } from "src/app/shared/components/chart/abstracthistorychart";
import { ChartConstants } from "src/app/shared/components/chart/chart.constants";
import { NavigationService } from "src/app/shared/components/navigation/service/navigation.service";
import { ChannelAddress, Currency, EdgeConfig, Logger, Service, Websocket } from "src/app/shared/shared";
import { Language } from "src/app/shared/type/language";
import { ColorUtils } from "src/app/shared/utils/color/color.utils";
import { ChartAxis, HistoryUtils, TimeOfUseTariffUtils, Utils, YAxisType } from "src/app/shared/utils/utils";

/** What the controller really did in a quarter-hour. */
enum Mode {
    SURPLUS,
    PRICE,
    TARGET,
}

/**
 * History of Controller.Evcs.Price: per quarter-hour the grid price as bar height with the blended
 * price as coloured base in the colour of the mode that really ran, the real charge power and the
 * planned charge power (dashed); PV production and the storage SoC on demand.
 */
@Component({
    selector: "oe-evcs-price-history-chart",
    templateUrl: "../../../../../../shared/components/chart/abstracthistorychart.html",
    changeDetection: ChangeDetectionStrategy.Eager,
    standalone: false,
})
export class EvcsPriceHistoryChartComponent extends AbstractHistoryChart {

    private static readonly PREFIX = "EDGE.INDEX.WIDGETS.EVCS.PRICE_HISTORY.";
    /** Below this average power in [kW] a quarter-hour did not charge. */
    private static readonly CHARGING_KW = 0.1;

    private currencyUnit: Currency.Unit | null = null;
    private currencyLabel: Currency.Label;

    constructor(
        private websocket: Websocket,
        public override service: Service,
        public override cdRef: ChangeDetectorRef,
        protected override translate: TranslateService,
        protected override route: ActivatedRoute,
        protected override logger: Logger,
        protected override navigationService: NavigationService,
    ) {
        super(service, cdRef, translate, route, logger, navigationService);
        effect(() => {
            const edge = this.service.currentEdge();
            if (!edge) {
                return;
            }
            edge.getFirstValidConfig(this.websocket).then((config) => {
                const meta: EdgeConfig.Component = config?.getComponent("_meta");
                const currency: string = config?.getPropertyFromComponent<string>(meta, "currency");
                this.currencyUnit = Currency.getChartCurrencyUnitLabel(currency);
            });
        });
    }

    protected override getChartData(): HistoryUtils.ChartData {
        const componentId: string =
            this.route.snapshot.params["componentId"] ??
            this.config.getComponentIdsByFactory("Controller.Evcs.Price")[0];
        this.component = this.config.components[componentId];
        const evcsId: string = this.component.properties["evcs.id"];

        const meta: EdgeConfig.Component = this.config?.getComponent("_meta");
        const currency: string = this.config?.getPropertyFromComponent<string>(meta, "currency");
        this.currencyLabel = Currency.getCurrencyLabelByCurrency(currency);
        this.chartType = "bar";
        const p = EvcsPriceHistoryChartComponent.PREFIX;

        return {
            input: [
                { name: "GridPrice", powerChannel: ChannelAddress.fromString("_sum/GridBuyPrice") },
                { name: "BlendedPrice", powerChannel: ChannelAddress.fromString(this.component.id + "/BlendedPrice") },
                { name: "PriceCharging", powerChannel: ChannelAddress.fromString(this.component.id + "/PriceCharging") },
                {
                    name: "TargetRemaining",
                    powerChannel: ChannelAddress.fromString(this.component.id + "/TargetRemainingEnergy"),
                },
                { name: "PlanPower", powerChannel: ChannelAddress.fromString(this.component.id + "/PlanPower") },
                {
                    name: "CarPower",
                    powerChannel: ChannelAddress.fromString(evcsId + "/ActivePower"),
                    converter: HistoryUtils.ValueConverter.NEGATIVE_AS_ZERO,
                },
                {
                    name: "Production",
                    powerChannel: ChannelAddress.fromString("_sum/ProductionActivePower"),
                    converter: HistoryUtils.ValueConverter.NEGATIVE_AS_ZERO,
                },
                { name: "Soc", powerChannel: ChannelAddress.fromString("_sum/EssSoc") },
            ],
            output: (data: HistoryUtils.ChannelData) => [
                {
                    name: this.translate.instant(p + "MODE_SURPLUS"),
                    converter: () => this.getModeDataset(data, Mode.SURPLUS),
                    color: ChartConstants.Colors.GREEN,
                    stack: 1,
                    order: 2,
                },
                {
                    name: this.translate.instant(p + "MODE_PRICE"),
                    converter: () => this.getModeDataset(data, Mode.PRICE),
                    color: ChartConstants.Colors.ORANGE,
                    stack: 1,
                    order: 2,
                },
                {
                    name: this.translate.instant(p + "MODE_TARGET"),
                    converter: () => this.getModeDataset(data, Mode.TARGET),
                    color: ChartConstants.Colors.PURPLE,
                    stack: 1,
                    order: 2,
                },
                {
                    // grey part up to the grid price; the whole bar while the car did not charge
                    name: this.translate.instant(p + "GRID_PRICE"),
                    converter: () => this.getGridPriceRest(data),
                    color: ChartConstants.Colors.DARK_GREY,
                    stack: 1,
                    order: 2,
                },
                {
                    name: this.translate.instant(p + "CHARGE_POWER"),
                    converter: () => data["CarPower"],
                    color: ChartConstants.Colors.GREEN,
                    yAxisId: ChartAxis.RIGHT,
                    custom: { type: "line", unit: YAxisType.POWER, formatNumber: "1.1-1" },
                    order: 0,
                },
                {
                    name: this.translate.instant(p + "PLANNED_POWER"),
                    converter: () => data["PlanPower"],
                    color: ChartConstants.Colors.GREEN,
                    borderDash: [6, 3],
                    yAxisId: ChartAxis.RIGHT,
                    custom: { type: "line", unit: YAxisType.POWER, formatNumber: "1.1-1" },
                    order: 0,
                },
                {
                    name: this.translate.instant(p + "PRODUCTION"),
                    converter: () => data["Production"],
                    color: ChartConstants.Colors.BLUE,
                    borderDash: [2, 3],
                    yAxisId: ChartAxis.RIGHT,
                    custom: { type: "line", unit: YAxisType.POWER, formatNumber: "1.1-1" },
                    hiddenOnInit: true,
                    order: 1,
                },
                {
                    name: this.translate.instant("GENERAL.SOC"),
                    converter: () => data["Soc"]?.map((value) => Utils.multiplySafely(value, 1000)),
                    color: "rgb(189, 195, 199)",
                    borderDash: [10, 10],
                    yAxisId: ChartAxis.RIGHT_2,
                    custom: { type: "line", unit: YAxisType.PERCENTAGE, formatNumber: "1.0-0" },
                    hiddenOnInit: true,
                    order: 1,
                },
            ],
            tooltip: {
                formatNumber: ChartConstants.NumberFormat.TWO,
            },
            yAxes: [
                {
                    unit: YAxisType.CURRENCY,
                    position: "left",
                    yAxisId: ChartAxis.LEFT,
                    customTitle: Currency.getChartCurrencyUnitLabel(currency),
                    scale: { dynamicScale: true },
                },
                {
                    unit: YAxisType.POWER,
                    position: "right",
                    yAxisId: ChartAxis.RIGHT,
                    displayGrid: false,
                },
                {
                    unit: YAxisType.PERCENTAGE,
                    position: "right",
                    yAxisId: ChartAxis.RIGHT_2,
                    displayGrid: false,
                },
            ],
        };
    }

    protected override async loadChart() {
        this.labels = [];
        this.errorResponse = null;

        const unit: Resolution = { unit: ChronoUnit.Type.MINUTES, value: 15 };
        this.queryHistoricTimeseriesData(
            this.service.historyPeriod.value.from,
            this.service.historyPeriod.value.to,
            unit,
        ).then((dataResponse) => {
            this.chartType = "line";
            this.chartObject = this.getChartData();

            const displayValues = AbstractHistoryChart.fillChart(this.chartType, this.chartObject, dataResponse);
            this.datasets = displayValues.datasets;
            this.legendOptions = displayValues.legendOptions;
            this.labels = displayValues.labels;
            this.setChartLabel();

            this.chartObject.yAxes.forEach((element) => {
                this.options = AbstractHistoryChart.getYAxisOptions(
                    this.options,
                    element,
                    this.translate,
                    this.chartType,
                    this.datasets,
                    true,
                    this.chartObject.tooltip.formatNumber,
                );
            });

            this.options.scales.x["time"].unit = calculateResolution(
                this.service,
                this.service.historyPeriod.value.from,
                this.service.historyPeriod.value.to,
            ).timeFormat;
            this.options.scales.x.ticks["source"] = "auto";
            this.options.scales.x.grid = { offset: false };
            this.options.plugins.tooltip.mode = "index";
            this.options.scales.x.ticks.maxTicksLimit = 30;
            this.options.scales[ChartAxis.LEFT].min = 0;

            this.options.plugins.tooltip.callbacks.labelColor = (item: Chart.TooltipItem<any>) => ({
                borderColor: ColorUtils.changeOpacityFromRGBA(item.dataset.borderColor, 1),
                backgroundColor: item.dataset.backgroundColor,
            });
            this.options.scales.x["bounds"] = "ticks";
            this.options.plugins.tooltip.callbacks.label = (item: Chart.TooltipItem<any>) => this.getTooltipLabel(item);

            this.options.scales[ChartAxis.LEFT]["title"].text = this.currencyUnit;
            this.datasets = this.datasets.map((el) => {
                const opacity = el.type === "line" ? 0.2 : 0.5;
                el.backgroundColor = ColorUtils.changeOpacityFromRGBA(el.backgroundColor.toString(), opacity);
                el.borderColor = ColorUtils.changeOpacityFromRGBA(el.borderColor.toString(), 1);
                return el;
            });

            this.options.scales.x["offset"] = false;
            this.options["animation"] = false;
        });
    }

    /**
     * The mode that really ran in each quarter-hour; null while the car did not charge.
     *
     * @param data the historic data
     * @returns the mode per quarter-hour
     */
    private getModes(data: HistoryUtils.ChannelData): (Mode | null)[] {
        const carPower = data["CarPower"] ?? [];
        return carPower.map((power, i) => {
            if (power == null || power < EvcsPriceHistoryChartComponent.CHARGING_KW) {
                return null;
            }
            if (data["TargetRemaining"]?.[i] != null) {
                return Mode.TARGET;
            }
            const priceCharging = Utils.multiplySafely(data["PriceCharging"]?.[i], 1000);
            return priceCharging != null && priceCharging >= 0.5 ? Mode.PRICE : Mode.SURPLUS;
        });
    }

    /**
     * The blended price in [ct/kWh] of the quarter-hours that ran in the mode, never above the
     * grid price.
     *
     * @param data the historic data
     * @param mode the mode
     * @returns the dataset
     */
    private getModeDataset(data: HistoryUtils.ChannelData, mode: Mode): (number | null)[] {
        const modes = this.getModes(data);
        return modes.map((m, i) => (m === mode ? this.getBlendedPrice(data, i) : null));
    }

    /**
     * The grey part of a bar: from the blended price up to the grid price while the car charged,
     * the whole grid price while it did not.
     *
     * @param data the historic data
     * @returns the dataset
     */
    private getGridPriceRest(data: HistoryUtils.ChannelData): (number | null)[] {
        const modes = this.getModes(data);
        return modes.map((m, i) => {
            const gridPrice = this.getGridPrice(data, i);
            if (gridPrice == null) {
                return null;
            }
            if (m == null) {
                return gridPrice;
            }
            return Math.max(gridPrice - (this.getBlendedPrice(data, i) ?? gridPrice), 0);
        });
    }

    private getGridPrice(data: HistoryUtils.ChannelData, i: number): number | null {
        return TimeOfUseTariffUtils.formatPrice(Utils.multiplySafely(data["GridPrice"]?.[i], 1000));
    }

    private getBlendedPrice(data: HistoryUtils.ChannelData, i: number): number | null {
        const gridPrice = this.getGridPrice(data, i);
        const blended = TimeOfUseTariffUtils.formatPrice(Utils.multiplySafely(data["BlendedPrice"]?.[i], 1000));
        if (blended == null) {
            return gridPrice;
        }
        return gridPrice == null ? blended : Math.min(blended, gridPrice);
    }

    /**
     * The tooltip line of a dataset: prices in [ct/kWh], powers in [kW], the SoC in [%]. The grey
     * part shows the grid price itself, not the difference.
     *
     * @param item the tooltip item
     * @returns the line; undefined for no value
     */
    private getTooltipLabel(item: Chart.TooltipItem<any>): string | undefined {
        const value = item.dataset.data[item.dataIndex] as number;
        if (value == null) {
            return undefined;
        }
        const locale: string = (Language.getByKey(localStorage.LANGUAGE) ?? Language.DEFAULT).i18nLocaleKey;
        const label = item.dataset.label;
        const yAxisId = (item.dataset as { yAxisID?: string }).yAxisID;
        const format = (v: number, digits: string) => new Intl.NumberFormat(locale, {
            minimumFractionDigits: Number(digits.split("-")[0].split(".")[1]),
            maximumFractionDigits: Number(digits.split("-")[1]),
        }).format(v);
        if (yAxisId === ChartAxis.RIGHT) {
            return label + ": " + format(value, "1.1-1") + " kW";
        }
        if (yAxisId === ChartAxis.RIGHT_2) {
            return label + ": " + format(value, "1.0-0") + " %";
        }
        if (label === this.translate.instant(EvcsPriceHistoryChartComponent.PREFIX + "GRID_PRICE")) {
            // the stacked parts add up to the grid price
            const gridPrice = this.datasets
                .filter((ds) => ds.type !== "line")
                .map((ds) => ds.data[item.dataIndex] as number)
                .filter((v) => v != null)
                .reduce((sum, v) => sum + v, 0);
            return label + ": " + format(gridPrice, "1.2-2") + " " + (this.currencyLabel ?? "");
        }
        return this.translate.instant(EvcsPriceHistoryChartComponent.PREFIX + "BLENDED_PRICE") + ": "
            + format(value, "1.2-2") + " " + (this.currencyLabel ?? "") + " (" + label + ")";
    }
}
