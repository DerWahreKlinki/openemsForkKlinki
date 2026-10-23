import { ChangeDetectionStrategy, ChangeDetectorRef, Component, Inject } from "@angular/core";
import { FormBuilder, FormControl, FormGroup } from "@angular/forms";
import { ActivatedRoute } from "@angular/router";
import { IonRange, ModalController, PopoverController } from "@ionic/angular";
import { TranslateService } from "@ngx-translate/core";
import { EvcsComponent } from "src/app/shared/components/edge/config-components/evcs/evcsComponent";
import { AbstractModal } from "src/app/shared/components/modal/abstractModal";
import { Formatter } from "src/app/shared/components/shared/formatter";
import { SetChannelValueRequest } from "src/app/shared/jsonrpc/request/setChannelValueRequest";
import { ChannelAddress, CurrentData, EdgeConfig, Service, Utils, Websocket } from "src/app/shared/shared";

import { FormUtils } from "src/app/shared/utils/form/form.utils";
import { AdministrationComponent } from "../administration/administration.component";
import { PopoverComponent } from "../popover/popover";

import { EvcsPriceForecast } from "../price/price-forecast";

type ChargeMode = "FORCE_CHARGE" | "EXCESS_POWER" | "TARGET_CHARGE";
type ModeButton = { name: string; value: string; icon: { color: string; name: string } };
@Component({
    selector: "oe-controller-evcs-modal",
    templateUrl: "./modal.html",
    changeDetection: ChangeDetectionStrategy.Eager,
    standalone: false,
})
export class ModalComponent extends AbstractModal {
    public readonly CONVERT_MANUAL_ON_OFF_AUTOMATIC = Utils.CONVERT_MODE_TO_MANUAL_OFF_AUTOMATIC(this.translate);
    public readonly CONVERT_MANUAL_ON_OFF = Utils.CONVERT_MANUAL_ON_OFF(this.translate);

    protected controller: EdgeConfig.Component | null = null;
    protected evcsComponent: EdgeConfig.Component | null = null;
    protected isConnectionSuccessful: boolean = false;
    protected readonly emptyValue: string = "-";
    protected status: string | null = null;
    protected chargePowerLimit: string | null = null;
    protected chargePower: { name: string; value: number } | null = null;
    protected state: string = "";
    protected energySession: string | null = null;
    protected minChargePower: number | null = null;
    protected maxChargePower: number | null = null;
    protected numberOfPhases: number = 3; // Defaults to three phases
    protected defaultChargeMinPower: number | null = null;
    protected energyLimit: boolean | null = null;
    protected chargeMode: ChargeMode | null = null;
    protected isEnergySinceBeginningAllowed: boolean = false;
    protected isChargingEnabled: boolean = false;
    protected sessionLimit: number | null = null;
    protected awaitingHysteresis: boolean | null = null;
    protected isReadWrite: boolean = true;
    protected helpKey: string | null = null;
    protected minHardwarePower: number | null = null;
    protected maxHardwarePower: number | null = null;
    // Controller.Evcs.Price: blended price and storage state
    protected blendedPrice: number | null = null;
    /** Replacement cost of storage power in [Cent/kWh] */
    protected storagePrice: number | null = null;
    /** Total storage capacity in [Wh] */
    protected storageCapacity: number | null = null;
    protected storageTargetReachable: boolean | null = null;
    protected storageNetSoc: number | null = null;
    /** Projected energy above the evening target at the end of PV production in [Wh] */
    protected storageSurplusEnergy: number | null = null;
    /** Power the storage delivers to the car in [W]: measured while charging, assumed before the start */
    protected storagePowerForCar: number | null = null;
    /** The Time-of-Use-Tariff controller blocks the storage discharge right now */
    protected storageDischargeBlocked: boolean | null = null;
    /** Current state of the price controller for the forecast chart; replaced only when a value changes */
    protected live: EvcsPriceForecast.Live | null = null;
    protected readonly Zone = EvcsPriceForecast.Zone;
    /** "Do not charge today" (channel PauseToday of the controller); null if not available */
    protected pauseToday: boolean | null = null;
    // Controller.Evcs.Price: configuration values used by the forecast chart
    protected pvPrice: number | null = null;
    protected priceChargePower: number | null = null;
    protected hasTimeOfUseTariff: boolean = false;
    private showKEBAwarining: boolean = false;

    private chargePoint: EvcsComponent | null = null;
    /** The buttons of the mode selection; "Target charging" only with Controller.Evcs.Price */
    private modeButtons: ModeButton[] = [];
    /** Target charging (property targetCharging of the controller); null if not available */
    private targetCharging: boolean | null = null;
    /** State of target charging: NONE, PLANNED, UNREACHABLE, PRICES_MISSING or REACHED */
    private targetStatus: string | null = null;
    /** Target time as epoch seconds; null while target charging is off */
    private targetDeadline: number | null = null;
    /** Energy still missing for the target in [Wh]; null while target charging is off */
    private targetRemaining: number | null = null;
    private storageLocked: boolean = false;
    /** The car takes nothing although power is offered (e.g. full): the plan does not count for the optimizer */
    private carTakesNothing: boolean = false;
    private sessionEnergy: number | null = null;
    private targetPlanTime: number | null = null;
    private targetPlanQuarters: number | null = null;
    /** Energy of the charging plan in [Wh]; null without a plan */
    private planEnergy: number | null = null;
    /** Part of the planned energy from the storage in [Wh] */
    private planFromStorage: number | null = null;
    /** Part of the planned energy from grid in [Wh] */
    private planFromGrid: number | null = null;
    /** Cost of the planned energy in [ct] */
    private planCost: number | null = null;

    constructor(
        @Inject(Websocket) protected override websocket: Websocket,
        @Inject(ActivatedRoute) protected override route: ActivatedRoute,
        @Inject(Service) protected override service: Service,
        @Inject(ModalController) public override modalController: ModalController,
        @Inject(ModalController) public detailViewController: ModalController,
        @Inject(PopoverController) public popoverctrl: PopoverController,
        @Inject(TranslateService) protected override translate: TranslateService,
        @Inject(FormBuilder) public override formBuilder: FormBuilder,
        public override ref: ChangeDetectorRef,
    ) {
        super(websocket, route, service, modalController, translate, formBuilder, ref);
    }

    protected static getHelpKey(factoryId: string): string | null {
        const map: Record<string, string> = {
            "Evcs.Keba.P40": "EVCS_KEBA",
            "Evcs.Keba.KeContact": "EVCS_KEBA",
            "Evcs.HardyBarth": "EVCS_HARDY_BARTH",
            "Evcs.Mennekes": "EVCS_MENNEKES",
            "Evse.Alfen": "EVSE_ALFEN",
            "Evcs.Goe.Http": "EVCS_GO_E",
            "Evcs.Ocpp.IesKeywattSingle": "EVCS_IES",
            "Evcs.AlpitronicHypercharger": "EVCS_ALPITRONIC_HYPER",
        };

        const key = map[factoryId];
        return key ? `REDIRECT.${key}` : null;
    }

    /**
     * Converts a config property to a number.
     *
     * @param value The property value
     * @returns The value as number, null if not available
     */
    private static toNumber(value: unknown): number | null {
        if (value == null || value === "") {
            return null;
        }
        const result = Number(value);
        return isNaN(result) ? null : result;
    }

    /**
     * Converts a config property to a boolean.
     *
     * @param value The property value
     * @returns True if the property is set
     */
    private static isTrue(value: unknown): boolean {
        return value === true || value === "true" || value === 1;
    }

    /**
     * Formats minutes after midnight as HH:MM.
     *
     * @param minutes The minutes after midnight
     * @returns The time of day
     */
    private static formatTimeOfDay(minutes: number): string {
        return String(Math.floor(minutes / 60)).padStart(2, "0") + ":" + String(minutes % 60).padStart(2, "0");
    }

    protected readonly KILO_WATT_HOURS_PIN_FORMATTER: IonRange["pinFormatter"] = (val) =>
        this.Converter.TO_KILO_WATT_HOURS(val);
    protected readonly WATT_PIN_FORMATTER: IonRange["pinFormatter"] = (val) => this.Converter.POWER_IN_WATT(val);
    protected readonly CENT_PIN_FORMATTER: IonRange["pinFormatter"] = (val) => val + " ct";
    protected readonly PERCENT_PIN_FORMATTER: IonRange["pinFormatter"] = (val) => val + " %";
    /** Converts a price in [Currency/MWh] to a string in ct/kWh */
    protected readonly CONVERT_PRICE_TO_CENT_PER_KWH = (value: number | null): string =>
        value == null ? "-" : (value / 10).toFixed(1) + " ct/kWh";
    protected readonly CONVERT_TO_CENT_PER_KWH = (value: number | null): string =>
        value == null ? "-" : value + " ct/kWh";
    protected readonly CONVERT_TO_KILO_WATT_HOURS = (value: number | null): string =>
        value == null ? "-" : (value / 1000).toFixed(1) + " kWh";
    /** Converts minutes after midnight to HH:MM */
    protected readonly CONVERT_TIME_OF_DAY = (value: number | null): string =>
        value == null ? "-" : ModalComponent.formatTimeOfDay(value);
    protected readonly TIME_OF_DAY_PIN_FORMATTER: IonRange["pinFormatter"] = (val) =>
        ModalComponent.formatTimeOfDay(val);
    /** Converts a cost in [ct] to Euro */
    protected readonly CONVERT_CENT_TO_EURO = (value: number | null): string =>
        value == null ? "-" : (value / 100).toFixed(2) + " €";

    /**
     * "Do not charge today": writes the switch to the controller, which pauses charging until midnight.
     *
     * @param event The toggle event
     */
    /**
     * The colour dot in front of a line of the colour explanation.
     *
     * @param zone the zone
     * @returns the style of the dot
     */
    protected zoneDot(zone: EvcsPriceForecast.Zone): string {
        return "display:inline-block;width:9px;height:9px;margin-right:6px;border-radius:2px;vertical-align:baseline;background-color:" + EvcsPriceForecast.ZONE_COLORS[zone];
    }

    protected setPauseToday(event: CustomEvent): void {
        if (this.edge == null || this.controller == null) {
            return;
        }
        const checked = event.detail.checked === true;
        this.pauseToday = checked;
        this.edge
            .sendRequest(
                this.websocket,
                new SetChannelValueRequest({
                    componentId: this.controller.id,
                    channelId: "PauseToday",
                    value: checked,
                }),
            )
            .catch((reason) => console.warn(reason));
    }

    protected async presentPopover() {
        const popover = await this.popoverctrl.create({
            component: PopoverComponent,
            componentProps: {
                chargeMode: this.formGroup?.controls["chargeMode"].value ?? null,
            },
        });
        return await popover.present();
    }

    protected async presentModal() {
        const modal = await this.detailViewController.create({
            component: AdministrationComponent,
            componentProps: {
                evcsComponent: this.evcsComponent,
                edge: this.edge,
            },
        });
        modal.onDidDismiss().then(() => {
            this.updateRenaultZoeConfig();
        });
        return await modal.present();
    }

    protected override getChannelAddresses(): ChannelAddress[] {
        if (this.component == null || this.edge == null || this.config == null) {
            return [];
        }
        this.chargePoint = EvcsComponent.from(this.component, this.edge.getCurrentConfig(), this.edge);
        this.controller =
            [
                ...this.config.getComponentsByFactory("Controller.Evcs"),
                ...this.config.getComponentsByFactory("Controller.Evcs.Price"),
            ].find(
                (element) =>
                    element.isEnabled &&
                    "evcs.id" in element.properties &&
                    element.properties["evcs.id"] == this.component?.id,
            ) || null;

        this.evcsComponent = this.config.getComponent(this.component.id);
        this.pvPrice = ModalComponent.toNumber(this.controller?.properties.pvPrice);
        this.priceChargePower = ModalComponent.toNumber(this.controller?.properties.priceChargePower);
        this.hasTimeOfUseTariff = EvcsPriceForecast.getTimeOfUseTariffController(this.config) != null;
        this.modeButtons = [
            {
                name: this.translate.instant("GENERAL.MANUALLY"),
                value: "FORCE_CHARGE",
                icon: { color: "success", name: "options-outline" },
            },
            {
                name: this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.OPTIMIZED_CHARGE_MODE.SHORT_NAME"),
                value: "EXCESS_POWER",
                icon: { color: "primary", name: "sunny-outline" },
            },
            ...(this.controller?.factoryId === "Controller.Evcs.Price"
                ? [
                      {
                          name: this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.TARGET_CHARGE.SHORT_NAME"),
                          value: "TARGET_CHARGE",
                          icon: { color: "primary", name: "flag-outline" },
                      },
                  ]
                : []),
            {
                name: this.translate.instant("GENERAL.OFF"),
                value: "OFF",
                icon: { color: "danger", name: "power-outline" },
            },
        ];

        const channels: ChannelAddress[] = [];

        if (this.chargePoint != null) {
            channels.push(this.chargePoint.powerChannel);
        }

        channels.push(
            new ChannelAddress(this.component.id, "Phases"),
            new ChannelAddress(this.component.id, "Plug"),
            new ChannelAddress(this.component.id, "Status"),
            new ChannelAddress(this.component.id, "State"),
            new ChannelAddress(this.component.id, "EnergySession"),
            new ChannelAddress(this.component.id, "MinimumHardwarePower"),
            new ChannelAddress(this.component.id, "MaximumHardwarePower"),
            new ChannelAddress(this.component.id, "SetChargePowerLimit"),
            new ChannelAddress(this.component.id, "_PropertyReadOnly"),
        );

        if (this.controller == null) {
            return channels;
        }

        channels.push(
            new ChannelAddress(this.controller.id, "_PropertyChargeMode"),
            new ChannelAddress(this.controller.id, "_PropertyEnabledCharging"),
            new ChannelAddress(this.controller.id, "_PropertyDefaultChargeMinPower"),
            new ChannelAddress(this.controller.id, "AwaitingHysteresis"),
        );
        if (this.controller.factoryId === "Controller.Evcs.Price") {
            channels.push(
                new ChannelAddress(this.controller.id, "BlendedPrice"),
                new ChannelAddress(this.controller.id, "StoragePrice"),
                new ChannelAddress("_sum", "EssCapacity"),
                new ChannelAddress(this.controller.id, "StorageTargetReachable"),
                new ChannelAddress(this.controller.id, "StorageNetSoc"),
                new ChannelAddress(this.controller.id, "StorageSurplusEnergy"),
                new ChannelAddress(this.controller.id, "StoragePowerForCar"),
                new ChannelAddress(this.controller.id, "StorageDischargeBlocked"),
                new ChannelAddress(this.controller.id, "PauseToday"),
                new ChannelAddress(this.controller.id, "ChargePowerLimit"),
                new ChannelAddress("_sum", "ProductionActivePower"),
                new ChannelAddress(this.controller.id, "_PropertyTargetCharging"),
                new ChannelAddress(this.controller.id, "TargetStatus"),
                new ChannelAddress(this.controller.id, "TargetDeadline"),
                new ChannelAddress(this.controller.id, "TargetRemainingEnergy"),
                new ChannelAddress(this.controller.id, "StorageLocked"),
                new ChannelAddress(this.controller.id, "CarTakesNothing"),
                new ChannelAddress(this.controller.id, "SessionEnergy"),
                new ChannelAddress(this.controller.id, "TargetPlanTime"),
                new ChannelAddress(this.controller.id, "TargetPlanQuarters"),
                new ChannelAddress(this.controller.id, "PlanEnergy"),
                new ChannelAddress(this.controller.id, "PlanEnergyFromStorage"),
                new ChannelAddress(this.controller.id, "PlanEnergyFromGrid"),
                new ChannelAddress(this.controller.id, "PlanCost"),
            );
        }

        return channels;
    }

    protected override onCurrentData(currentData: CurrentData) {
        const isDirty = this.formGroup?.dirty;
        if (this.component == null) {
            return;
        }

        this.isReadWrite = this.component.hasPropertyValue<boolean>("readOnly", true) === false;
        this.showKEBAwarining = this.helpKey === "REDIRECT.EVCS_KEBA";
        this.isConnectionSuccessful = currentData.allComponents[this.component.id + "/State"] !== 3 ? true : false; // 0 !== 3 -> true
        this.status = this.getState(
            this.controller ? currentData.allComponents[this.controller.id + "/_PropertyEnabledCharging"] === 1 : false,
            currentData.allComponents[this.component.id + "/Status"],
            currentData.allComponents[this.component.id + "/Plug"],
        );
        if (this.chargePoint != null) {
            this.chargePower = Utils.convertChargeDischargePower(
                this.translate,
                currentData.allComponents[this.chargePoint.powerChannel.toString()],
            );
        }

        if (this.controller != null) {
            this.setControllerChannelValues(currentData, isDirty ? isDirty : false);
        }
        if (isDirty) {
            return;
        }
        this.state = currentData.allComponents[this.component.id + "/Status"];
        this.energySession = Utils.CONVERT_TO_WATTHOURS(
            currentData.allComponents[this.component.id + "/EnergySession"],
        );
        this.minHardwarePower = currentData.allComponents[this.component.id + "/MinimumHardwarePower"] ?? null;
        this.maxHardwarePower = currentData.allComponents[this.component.id + "/MaximumHardwarePower"] ?? null;
        this.minChargePower = this.formatNumber(currentData.allComponents[this.component.id + "/MinimumHardwarePower"]);
        this.maxChargePower = this.formatNumber(currentData.allComponents[this.component.id + "/MaximumHardwarePower"]);
        this.numberOfPhases = currentData.allComponents[this.component.id + "/Phases"]
            ? currentData.allComponents[this.component.id + "/Phases"]
            : 3;
        this.chargePowerLimit = Utils.CONVERT_TO_WATT(
            this.formatNumber(currentData.allComponents[this.component.id + "/SetChargePowerLimit"]),
        );
    }

    protected setControllerChannelValues(currentData: CurrentData, isDirty: boolean): void {
        this.defaultChargeMinPower = currentData.allComponents[this.controller?.id + "/_PropertyDefaultChargeMinPower"];

        this.blendedPrice = currentData.allComponents[this.controller?.id + "/BlendedPrice"] ?? null;
        const storagePrice = currentData.allComponents[this.controller?.id + "/StoragePrice"];
        this.storagePrice = storagePrice == null ? null : storagePrice / 10; // [Currency/MWh] to [Cent/kWh]
        this.storageCapacity = currentData.allComponents["_sum/EssCapacity"] ?? null;
        const reachable = currentData.allComponents[this.controller?.id + "/StorageTargetReachable"];
        this.storageTargetReachable = reachable == null ? null : reachable === 1 || reachable === true;
        this.storageNetSoc = currentData.allComponents[this.controller?.id + "/StorageNetSoc"] ?? null;
        this.storageSurplusEnergy = currentData.allComponents[this.controller?.id + "/StorageSurplusEnergy"] ?? null;
        this.storagePowerForCar = currentData.allComponents[this.controller?.id + "/StoragePowerForCar"] ?? null;
        const blocked = currentData.allComponents[this.controller?.id + "/StorageDischargeBlocked"];
        this.storageDischargeBlocked = blocked == null ? null : blocked === 1 || blocked === true;
        const pauseToday = currentData.allComponents[this.controller?.id + "/PauseToday"];
        this.pauseToday = pauseToday == null ? null : pauseToday === 1 || pauseToday === true;
        const targetCharging = currentData.allComponents[this.controller?.id + "/_PropertyTargetCharging"];
        this.targetCharging = targetCharging == null ? null : ModalComponent.isTrue(targetCharging);
        this.targetStatus = currentData.allComponents[this.controller?.id + "/TargetStatus"] ?? null;
        this.targetDeadline = currentData.allComponents[this.controller?.id + "/TargetDeadline"] ?? null;
        this.targetRemaining = currentData.allComponents[this.controller?.id + "/TargetRemainingEnergy"] ?? null;
        this.storageLocked = ModalComponent.isTrue(currentData.allComponents[this.controller?.id + "/StorageLocked"]);
        this.carTakesNothing = ModalComponent.isTrue(currentData.allComponents[this.controller?.id + "/CarTakesNothing"]);
        this.sessionEnergy = currentData.allComponents[this.controller?.id + "/SessionEnergy"] ?? null;
        this.targetPlanTime = currentData.allComponents[this.controller?.id + "/TargetPlanTime"] ?? null;
        this.targetPlanQuarters = currentData.allComponents[this.controller?.id + "/TargetPlanQuarters"] ?? null;
        this.planEnergy = currentData.allComponents[this.controller?.id + "/PlanEnergy"] ?? null;
        this.planFromStorage = currentData.allComponents[this.controller?.id + "/PlanEnergyFromStorage"] ?? null;
        this.planFromGrid = currentData.allComponents[this.controller?.id + "/PlanEnergyFromGrid"] ?? null;
        this.planCost = currentData.allComponents[this.controller?.id + "/PlanCost"] ?? null;

        if (this.controller?.factoryId === "Controller.Evcs.Price") {
            const blendedPrice = currentData.allComponents[this.controller.id + "/BlendedPrice"];
            const production = currentData.allComponents["_sum/ProductionActivePower"];
            const live: EvcsPriceForecast.Live = {
                chargePower: currentData.allComponents[this.controller.id + "/ChargePowerLimit"] ?? null,
                blendedPrice: blendedPrice == null ? null : Math.round(blendedPrice) / 10, // [Currency/MWh] to [Cent/kWh]
                production: production == null ? null : Math.round(production / 100) * 100,
                awaitingHysteresis: (() => {
                    const value = currentData.allComponents[this.controller.id + "/AwaitingHysteresis"];
                    return value == null ? null : value === 1 || value === true;
                })(),
            };
            // Keep the same object unless a value changed, so the chart is not redrawn every second
            if (JSON.stringify(live) !== JSON.stringify(this.live)) {
                this.live = live;
            }
        }

        if (isDirty) {
            return;
        }
        this.awaitingHysteresis = currentData.allComponents[this.controller?.id + "/AwaitingHysteresis"];
    }

    protected override onIsInitialized(): void {
        if (this.formGroup == null || this.component == null || this.edge == null) {
            return;
        }

        this.chargePoint = EvcsComponent.from(this.component, this.edge.getCurrentConfig(), this.edge);

        const energyLimitCtrl = FormUtils.findFormControlSafely(
            this.formGroup as FormGroup,
            "energyLimit",
        ) as FormControl;
        const energySessionLimitCtrl = FormUtils.findFormControlSafely(
            this.formGroup as FormGroup,
            "energySessionLimit",
        ) as FormControl;
        const energySessionLimitKwhCtrl = FormUtils.findFormControlSafely(
            this.formGroup as FormGroup,
            "energySessionLimitKwh",
        ) as FormControl;
        const chargeModeCtrl = FormUtils.findFormControlSafely(
            this.formGroup as FormGroup,
            "chargeMode",
        ) as FormControl;
        const enabledChargingCtrl = FormUtils.findFormControlSafely(
            this.formGroup as FormGroup,
            "enabledCharging",
        ) as FormControl;
        const minGuaranteeCtrl = FormUtils.findFormControlSafely(
            this.formGroup as FormGroup,
            "minGuarantee",
        ) as FormControl;
        const defaultChargeMinPowerCtrl = FormUtils.findFormControlSafely(
            this.formGroup as FormGroup,
            "defaultChargeMinPower",
        ) as FormControl;
        const targetChargingCtrl = FormUtils.findFormControlSafely(
            this.formGroup as FormGroup,
            "targetCharging",
        ) as FormControl;
        const targetEnergyCtrl = FormUtils.findFormControlSafely(
            this.formGroup as FormGroup,
            "targetEnergy",
        ) as FormControl;
        const targetEnergyKwhCtrl = FormUtils.findFormControlSafely(
            this.formGroup as FormGroup,
            "targetEnergyKwh",
        ) as FormControl;

        if (
            energyLimitCtrl == null ||
            energySessionLimitCtrl == null ||
            energySessionLimitKwhCtrl == null ||
            chargeModeCtrl == null ||
            enabledChargingCtrl == null ||
            minGuaranteeCtrl == null ||
            defaultChargeMinPowerCtrl == null
        ) {
            return;
        }

        const DEFAULT_ENERGY_SESSION_LIMIT = 20000;

        this.subscription.add(
            energyLimitCtrl.valueChanges.subscribe((isEnergyLimit: boolean) => {
                const newValue = isEnergyLimit
                    ? energySessionLimitCtrl.value === 0
                        ? DEFAULT_ENERGY_SESSION_LIMIT
                        : energySessionLimitCtrl.value
                    : 0;

                energySessionLimitCtrl.setValue(newValue);
                energySessionLimitCtrl.markAsDirty();
            }),
        );

        this.subscription.add(
            chargeModeCtrl.valueChanges.subscribe((chargeMode: "OFF" | string) => {
                if (chargeMode === "OFF") {
                    enabledChargingCtrl.setValue(false);
                    chargeModeCtrl.markAsPristine();
                } else if (chargeMode === "TARGET_CHARGE") {
                    // only a view: the switch on it starts target charging; nothing to save
                    // unless charging was off
                    chargeModeCtrl.markAsPristine();
                    if (enabledChargingCtrl.value !== true) {
                        enabledChargingCtrl.setValue(true);
                        enabledChargingCtrl.markAsDirty();
                    }
                } else {
                    enabledChargingCtrl.setValue(true);
                }
                if (chargeMode !== "TARGET_CHARGE") {
                    enabledChargingCtrl.markAsDirty();
                }
                // leaving the view ends target charging
                if (chargeMode !== "TARGET_CHARGE" && targetChargingCtrl?.value === true) {
                    targetChargingCtrl.setValue(false);
                    targetChargingCtrl.markAsDirty();
                }
            }),
        );
        if (targetEnergyCtrl != null && targetEnergyKwhCtrl != null) {
            this.subscription.add(
                targetEnergyKwhCtrl.valueChanges.subscribe((newValue: number) => {
                    targetEnergyCtrl.setValue(newValue * 1000);
                    targetEnergyCtrl.markAsDirty();
                    targetEnergyKwhCtrl.markAsPristine();
                }),
            );
        }

        if (this.subscription == null) {
            return;
        }

        this.subscription.add(
            minGuaranteeCtrl.valueChanges.subscribe((minGuarantee: boolean) => {
                const minPerPhase = 1400;
                defaultChargeMinPowerCtrl.setValue(minGuarantee ? minPerPhase * this.numberOfPhases : 0);
                defaultChargeMinPowerCtrl.markAsDirty();
            }),
        );

        this.subscription.add(
            energySessionLimitKwhCtrl.valueChanges.subscribe((newValue: number) => {
                energySessionLimitCtrl.setValue(newValue * 1000);
                energySessionLimitCtrl.markAsDirty();
                energySessionLimitKwhCtrl.markAsPristine();
            }),
        );

        this.helpKey = ModalComponent.getHelpKey(this.component.factoryId);
    }

    protected override getFormGroup(): FormGroup {
        return this.formBuilder.group({
            chargeMode: new FormControl(
                this.controller?.properties.enabledCharging == false
                    ? "OFF"
                    : ModalComponent.isTrue(this.controller?.properties.targetCharging)
                      ? "TARGET_CHARGE"
                      : this.controller?.properties.chargeMode,
            ),
            energyLimit: new FormControl(this.controller?.properties.energySessionLimit > 0),
            minGuarantee: new FormControl(this.controller?.properties.defaultChargeMinPower > 0),
            defaultChargeMinPower: new FormControl(this.controller?.properties.defaultChargeMinPower),
            forceChargeMinPower: new FormControl(this.controller?.properties.forceChargeMinPower),
            priority: new FormControl(this.controller?.properties.priority),
            // EnergySessionLimit as Wh value
            energySessionLimit: new FormControl(this.controller?.properties.energySessionLimit),
            // EnergySessionLimit as kWh value, for ion-range
            energySessionLimitKwh: new FormControl(Math.round(this.controller?.properties.energySessionLimit / 1000)),
            enabledCharging: new FormControl(this.isChargingEnabled),
            // Only available with Controller.Evcs.Price
            enablePriceCharging: new FormControl(this.controller?.properties.enablePriceCharging !== false),
            priceLimit: new FormControl(ModalComponent.toNumber(this.controller?.properties.priceLimit)),
            priceLimitFullPower: new FormControl(
                ModalComponent.toNumber(this.controller?.properties.priceLimitFullPower),
            ),
            useStorageSurplus: new FormControl(this.controller?.properties.useStorageSurplus === true),
            storageTargetSocNet: new FormControl(
                ModalComponent.toNumber(this.controller?.properties.storageTargetSocNet),
            ),
            // Target charging, only available with Controller.Evcs.Price; the switch is written at once
            targetCharging: new FormControl(ModalComponent.isTrue(this.controller?.properties.targetCharging)),
            targetEnergy: new FormControl(ModalComponent.toNumber(this.controller?.properties.targetEnergy) ?? 10000),
            // Target energy as kWh value, for ion-range
            targetEnergyKwh: new FormControl(
                Math.round((ModalComponent.toNumber(this.controller?.properties.targetEnergy) ?? 10000) / 1000),
            ),
            targetTime: new FormControl(ModalComponent.toNumber(this.controller?.properties.targetTime) ?? 420),
        });
    }

    /**
     * Formats the pin value
     *
     * @param value The value
     * @returns A formatted value
     */
    protected pinFormatter(value: number): string {
        return Formatter.FORMAT_WATT(value);
    }

    /**
     * Updates the Min-Power of force charging
     *
     * @param event
     */
    protected updateForceMinPower(event: CustomEvent, currentController: EdgeConfig.Component, numberOfPhases: number) {
        if (this.formGroup == null) {
            return;
        }
        const newMinChargePower = event.detail.value / numberOfPhases;

        const forceChargeMinPowerCtrl = this.formGroup.get("forceChargeMinPower");
        if (forceChargeMinPowerCtrl == null) {
            return;
        }

        forceChargeMinPowerCtrl.markAsDirty();
        forceChargeMinPowerCtrl.setValue(newMinChargePower);
    }

    /** Updates the MinChargePower for Renault Zoe Charging Mode if activated in administration component */
    protected updateRenaultZoeConfig() {
        if (this.controller && this.evcsComponent?.properties["minHwCurrent"] == 10000) {
            const oldMinChargePower = this.controller.properties.forceChargeMinPower;
            const maxAllowedChargePower = 10 /* Ampere */ * 230; /* Volt */

            if (oldMinChargePower >= maxAllowedChargePower) {
                return;
            }

            const newMinChargePower = maxAllowedChargePower;

            if (this.edge == null) {
                return;
            }

            this.edge
                .updateComponentConfig(this.websocket, this.controller.id, [
                    { name: "forceChargeMinPower", value: newMinChargePower },
                ])
                .then(() => {
                    this.setProperties(newMinChargePower);
                })
                .catch((reason) => {
                    this.setProperties(oldMinChargePower);
                    console.warn(reason);
                });
        }
    }

    protected setProperties(value: number) {
        if (
            this.controller != null &&
            this.controller.properties != null &&
            this.controller.properties.forceChargeMinPower != null
        ) {
            this.controller.properties.forceChargeMinPower = value;
        }
    }

    protected formatNumber(i: number) {
        const round = Math.ceil(i / 100) * 100;
        return round;
    }

    /**
     * Returns the state of the EVCS
     *
     * @param state The state
     * @param plug The plug
     */
    private getState(enabledCharging: boolean, state: number, plug: number): string {
        if (this.isReadWrite === true && enabledCharging === false) {
            return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.CHARGING_STATION_DEACTIVATED");
        }

        if (plug == null) {
            if (state == null) {
                return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.NOT_CHARGING");
            }
        } else if (
            plug != ChargePlug.PLUGGED_ON_EVCS_AND_ON_EV_AND_LOCKED &&
            this.chargePower != null &&
            this.chargePower?.value > 450
        ) {
            return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.CABLE_NOT_CONNECTED");
        }
        switch (state) {
            case ChargeState.STARTING:
                return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.STARTING");
            case ChargeState.UNDEFINED:
            case ChargeState.ERROR:
                return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.ERROR");
            case ChargeState.READY_FOR_CHARGING:
                return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.READY_FOR_CHARGING");
            case ChargeState.NOT_READY_FOR_CHARGING:
                return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.NOT_READY_FOR_CHARGING");
            case ChargeState.AUTHORIZATION_REJECTED:
                return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.NOT_CHARGING");
            case ChargeState.CHARGING:
                return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.CHARGING");
            case ChargeState.ENERGY_LIMIT_REACHED:
                return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.CHARGE_LIMIT_REACHED");
            case ChargeState.CHARGING_FINISHED:
                return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.CAR_FULL");
            default:
                return "";
        }
    }

    /**
     * Formats the target time as weekday and time of day.
     *
     * @param epochSeconds The target time
     * @returns The formatted time
     */
    private formatDeadline(epochSeconds: number): string {
        return new Date(epochSeconds * 1000).toLocaleString(undefined, {
            weekday: "short",
            hour: "2-digit",
            minute: "2-digit",
        });
    }

    /**
     * Target charging: writes the switch to the controller at once, like "Do not charge today". The target energy and
     * time are saved with the form.
     *
     * @param event The toggle event
     */
    private setTargetCharging(event: CustomEvent): void {
        if (this.edge == null || this.controller == null) {
            return;
        }
        const checked = event.detail.checked === true;
        this.targetCharging = checked;
        this.formGroup?.get("targetCharging")?.setValue(checked, { emitEvent: false });
        this.edge
            .updateComponentConfig(this.websocket, this.controller.id, [{ name: "targetCharging", value: checked }])
            .catch((reason) => console.warn(reason));
    }

    /**
     * The planned shares of PV, storage and grid.
     *
     * @returns The shares in [kWh]
     */
    /** "Plan from 13:02, 5 quarter-hours": when the target plan was made. */
    private getTargetPlanInfo(): string | null {
        if (this.targetPlanTime == null || this.targetPlanQuarters == null) {
            return null;
        }
        return this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.TARGET_CHARGE.PLAN_INFO", {
            time: this.formatDeadline(this.targetPlanTime),
            quarters: this.targetPlanQuarters,
        });
    }

    private getPlanShares(): string {
        if (this.planEnergy == null) {
            return "-";
        }
        const storage = this.planFromStorage ?? 0;
        const grid = this.planFromGrid ?? 0;
        const kwh = (wh: number): string => (wh / 1000).toFixed(1);
        return kwh(this.planEnergy - storage - grid) + " / " + kwh(storage) + " / " + kwh(grid) + " kWh";
    }
}

enum ChargeState {
    UNDEFINED = -1, //Undefined
    STARTING, //Starting
    NOT_READY_FOR_CHARGING, //Not ready for Charging e.g. unplugged, X1 or "ena" not enabled, RFID not enabled,...
    READY_FOR_CHARGING, //Ready for Charging waiting for EV charging request
    CHARGING, //Charging
    ERROR, //Error
    AUTHORIZATION_REJECTED, //Authorization rejected
    ENERGY_LIMIT_REACHED, //Energy limit reached
    CHARGING_FINISHED, //Charging has finished
}

enum ChargePlug {
    UNDEFINED = -1, //Undefined
    UNPLUGGED, //Unplugged
    PLUGGED_ON_EVCS, //Plugged on EVCS
    PLUGGED_ON_EVCS_AND_LOCKED = 3, //Plugged on EVCS and locked
    PLUGGED_ON_EVCS_AND_ON_EV = 5, //Plugged on EVCS and on EV
    PLUGGED_ON_EVCS_AND_ON_EV_AND_LOCKED = 7, //Plugged on EVCS and on EV and locked
}
