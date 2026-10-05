import { formatNumber } from "@angular/common";
import { ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnChanges, OnDestroy, OnInit, } from "@angular/core";
import { ChartConstants } from "src/app/shared/components/chart/chart.constants";
import { Edge, Websocket } from "src/app/shared/shared";
import { Language } from "src/app/shared/type/language";

import { EvcsPriceForecast } from "./price-forecast";

type Segment = { color: string; hasSurplus: boolean; title: string };
type Tick = { label: string; position: number };

/**
 * Compact forecast of a 'Controller.Evcs.Price' for the live widget: a band colored by the expected behaviour of the
 * controller and the cheapest window.
 */
@Component({
    selector: "oe-evcs-price-band",
    templateUrl: "./price-band.html",
    changeDetection: ChangeDetectionStrategy.Eager,
    standalone: false,
    styles: [
        `
            .band,
            .surplus {
                display: flex;
                width: 100%;
            }
            .band {
                height: 14px;
                border-radius: 4px;
                overflow: hidden;
            }
            .surplus {
                height: 4px;
                margin-bottom: 2px;
            }
            .band > div,
            .surplus > div {
                flex: 1 1 0;
            }
            .ticks {
                position: relative;
                height: 1.2em;
                font-size: x-small;
            }
            .ticks > span {
                position: absolute;
                transform: translateX(-50%);
            }
            .legend {
                display: flex;
                flex-wrap: wrap;
                gap: 0 10px;
                font-size: x-small;
            }
            .legend i {
                display: inline-block;
                width: 8px;
                height: 8px;
                margin-right: 3px;
                border-radius: 2px;
            }
        `,
    ],
})
export class EvcsPriceBandComponent implements OnInit, OnChanges, OnDestroy {
    private static readonly REFRESH_INTERVAL = 60_000; // [ms]

    @Input({ required: true }) public edge!: Edge;
    @Input({ required: true }) public controllerId!: string;
    @Input() public minPower: number | null = null;
    @Input() public maxPower: number | null = null;

    protected readonly COLOR_FULL = ChartConstants.Colors.GREEN;
    protected readonly COLOR_REDUCED = ChartConstants.Colors.ORANGE;
    protected readonly COLOR_NONE = ChartConstants.Colors.RED;
    protected readonly COLOR_SURPLUS = ChartConstants.Colors.BLUE;

    protected segments: Segment[] = [];
    protected ticks: Tick[] = [];
    protected cheapestWindow: string | null = null;

    private timer: ReturnType<typeof setInterval> | null = null;

    constructor(
        private websocket: Websocket,
        private cdRef: ChangeDetectorRef,
    ) {}

    public ngOnInit(): void {
        this.timer = setInterval(() => this.load(), EvcsPriceBandComponent.REFRESH_INTERVAL);
    }

    public ngOnChanges(): void {
        this.load();
    }

    public ngOnDestroy(): void {
        if (this.timer != null) {
            clearInterval(this.timer);
        }
    }

    private async load(): Promise<void> {
        const config = this.edge?.getCurrentConfig();
        const controller = config?.getComponent(this.controllerId);
        if (config == null || controller == null) {
            return;
        }
        try {
            const settings = EvcsPriceForecast.getSettings(controller, this.minPower, this.maxPower);
            const slots = await EvcsPriceForecast.load(this.edge, this.websocket, config, settings);
            this.update(slots, settings);
        } catch (error) {
            console.warn(error);
            this.update([], null);
        }
        this.cdRef.markForCheck();
    }

    private update(slots: EvcsPriceForecast.Slot[], settings: EvcsPriceForecast.Settings | null): void {
        const locale: string = (Language.getByKey(localStorage.LANGUAGE) ?? Language.DEFAULT).i18nLocaleKey;
        const time = (date: Date): string => date.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" });

        this.segments = slots.map((slot) => ({
            color:
                slot.zone === EvcsPriceForecast.Zone.FULL
                    ? this.COLOR_FULL
                    : slot.zone === EvcsPriceForecast.Zone.REDUCED
                      ? this.COLOR_REDUCED
                      : this.COLOR_NONE,
            hasSurplus: settings != null && slot.surplus >= settings.minPower,
            title: time(slot.timestamp) + ": " + formatNumber(slot.effectivePrice, locale, "1.0-1") + " ct/kWh",
        }));

        this.ticks = slots
            .map((slot, index) => ({ slot, index }))
            .filter(({ slot }) => slot.timestamp.getMinutes() === 0 && slot.timestamp.getHours() % 3 === 0)
            .map(({ slot, index }) => ({
                label: slot.timestamp.getHours() + ":00",
                position: (index / slots.length) * 100,
            }));

        const window = EvcsPriceForecast.getCheapestWindow(slots);
        this.cheapestWindow =
            window == null
                ? null
                : time(window.from) +
                  " - " +
                  time(window.to) +
                  ", Ø " +
                  formatNumber(window.averagePrice, locale, "1.0-1") +
                  " ct/kWh";
    }
}
