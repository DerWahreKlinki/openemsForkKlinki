import { CommonModule } from "@angular/common";
import { ChangeDetectionStrategy, Component } from "@angular/core";
import { FormControl, FormGroup, ReactiveFormsModule } from "@angular/forms";
import { IonicModule } from "@ionic/angular";
import { FormlyModule } from "@ngx-formly/core";
import { TranslateModule, TranslateService } from "@ngx-translate/core";
import { EnergySchedulerV2 } from "src/app/shared/components/edge/config-components/energy/energy";
import { GetSchedule } from "src/app/shared/components/edge/config-components/energy/getSchedule";
import { Converter } from "src/app/shared/components/shared/converter";
import { DataService } from "src/app/shared/components/shared/dataservice";
import { Name } from "src/app/shared/components/shared/name";
import { AbstractFormlyComponent, OeFormlyField, OeFormlyView, } from "src/app/shared/components/shared/oe-formly-component";
import { ChannelAddress, CurrentData, Edge, EdgeConfig, Service, Utils } from "src/app/shared/shared";
import { AssertionUtils } from "src/app/shared/utils/assertions/assertions.utils";
import { TimeLineChartComponent } from "../../../../../shared/components/chart/timeline-chart/timeline-chart";
import { LiveDataService } from "../../../livedataservice";
import { SharedStorage } from "../shared/shared";
import { ChargeDischargeChartComponent } from "./chart/charge-discharge-chart";
import { ModeChartComponent } from "./chart/mode-chart";
import { SocChartComponent } from "./chart/soc-chart";
import { CommonStoragePercentagebarComponent } from "./percentagebar/percentagebar";

@Component({
    selector: "oe-common-storage",
    templateUrl: "../../../../../shared/components/formly/formly-field-modal/template.html",
    providers: [{ provide: DataService, useClass: LiveDataService }],
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [CommonModule, IonicModule, ReactiveFormsModule, FormlyModule, TranslateModule],
})
export class CommonStorageHomeComponent extends AbstractFormlyComponent {
    protected override formlyWrapper: "formly-field-modal" | "formly-field-navigation" = "formly-field-navigation";

    public static async getFormlyGeneralView(
        translate: TranslateService,
        service: Service,
        edge: Edge,
        config: EdgeConfig,
        energyScheduler: EnergySchedulerV2,
    ): Promise<OeFormlyView> {
        return {
            title: translate.instant("GENERAL.STORAGE_SYSTEM"),
            helpKey: "REDIRECT.COMMON_STORAGE",
            lines: await CommonStorageHomeComponent.getLines(translate, service, edge, config, energyScheduler),
            component: new EdgeConfig.Component(),
            isCommonWidget: true,
        };
    }

    private static async getLines(
        translate: TranslateService,
        service: Service,
        edge: Edge,
        config: EdgeConfig,
        energyScheduler: EnergySchedulerV2,
    ): Promise<OeFormlyField[]> {
        await energyScheduler?.updateSchedule(edge, service.websocket);
        const essComponents: EdgeConfig.Component[] = SharedStorage.getEssComponents(config);
        const emergencyReserveComponents: {
            [essId: string]: EdgeConfig.Component;
        } = config
            .getComponentsByFactory("Controller.Ess.EmergencyCapacityReserve")
            .filter(component => component.isEnabled)
            .reduce((result, component) => {
                return {
                    ...result,
                    [component.properties["ess.id"]]: component,
                };
            }, {});
        const prepareBatteryExtensionCtrl: { [essId: string]: EdgeConfig.Component } = config.getComponentsByFactory("Controller.Ess.PrepareBatteryExtension")
            .filter(component => component.isEnabled)
            .reduce((result, component) => {
                return {
                    ...result,
                    [component.properties["ess.id"]]: component,
                };
            }, {});

        const chargeDischargeLimiterComponents: { [essId: string]: EdgeConfig.Component } = config
            .getComponentsByFactory("Controller.Ess.ChargeDischargeLimiter")
            .filter((component) => component.isEnabled)
            .reduce((result, component) => {
                return {
                    ...result,
                    [component.properties["ess.id"]]: component,
                };
            }, {});

        const controllerLines = essComponents.reduce((arr: OeFormlyField[] = [], ess, i) => {
            if (essComponents.length > 1) {
                arr.push({
                    type: "name-line",
                    name: Name.METER_ALIAS_OR_ID(ess),
                });
            }

            const emergencyReserveCtrl = emergencyReserveComponents[ess.id];
            const chargeDischargeLimiterCtrl = chargeDischargeLimiterComponents[ess.id] ?? null;
            arr.push(
                {
                    type: "component-line",
                    component: CommonStoragePercentagebarComponent,
                    inputs: {
                        essComponentId: ess.id,
                        emergencyReserveController: emergencyReserveCtrl,
                        chargeDischargeLimiterController: chargeDischargeLimiterCtrl,
                    },
                },
                ...SharedStorage.getChargeDischargeLinesInKw(ess, config, translate),
            );

            if (chargeDischargeLimiterCtrl !== null) {
                arr.push(...CommonStorageHomeComponent.getChargeDischargeLimiterLines(chargeDischargeLimiterCtrl, translate));
            }

            const prepareBatteryExtensionCtrlForEss =
                ess.id in prepareBatteryExtensionCtrl ? prepareBatteryExtensionCtrl[ess.id] : null;

            if (prepareBatteryExtensionCtrlForEss !== null) {
                arr.push(
                    { type: "horizontal-line" },
                    {
                        type: "value-from-channels-line",
                        channelsToSubscribe: [
                            ChannelAddress.fromString(prepareBatteryExtensionCtrlForEss.id + "/_PropertyIsRunning"),
                            ChannelAddress.fromString(prepareBatteryExtensionCtrlForEss.id + "/CtrlIsBlockingEss"),
                            ChannelAddress.fromString(prepareBatteryExtensionCtrlForEss.id + "/CtrlIsChargingEss"),
                            ChannelAddress.fromString(prepareBatteryExtensionCtrlForEss.id + "/CtrlIsDischargingEss"),
                            ChannelAddress.fromString(prepareBatteryExtensionCtrlForEss.id + "/CtrlIsInReferenceCycle"),
                            ChannelAddress.fromString(
                                prepareBatteryExtensionCtrlForEss.id + "/_PropertyTargetTimeSpecified",
                            ),
                            ChannelAddress.fromString(prepareBatteryExtensionCtrlForEss.id + "/_PropertyTargetTime"),
                        ],
                        singleLine: true,
                        value: (currentData: CurrentData) =>
                            SharedStorage.getBatteryCapacityExtensionStatus(
                                translate,
                                currentData,
                                prepareBatteryExtensionCtrlForEss.id,
                            )?.text ?? null,
                        filter: (currentData: CurrentData) =>
                            SharedStorage.getBatteryCapacityExtensionStatus(
                                translate,
                                currentData,
                                prepareBatteryExtensionCtrlForEss.id,
                            )?.text != null,
                    },
                );
            }

            if (i < essComponents.length - 1) {
                arr.push({
                    type: "horizontal-line",
                });
            }

            return arr;
        }, []);

        const lines: OeFormlyField[] = [];

        if (energyScheduler.schedule !== GetSchedule.Response.empty) {
            lines.push(
                {
                    type: "component-line",
                    component: TimeLineChartComponent,
                    inputs: {
                        data: energyScheduler.schedule,
                    },
                },
                {
                    type: "horizontal-line",
                },
                {
                    type: "channel-line",
                    name: translate.instant("GENERAL.POWER"),
                    channel: new ChannelAddress("_sum", "EssDischargePower").toString(),
                    style: {
                        name: { fontSize: "large" },
                        value: { fontSize: "large" },
                    },
                    cssClass: "ion-padding-top",
                    converter: ESS_CHARGE_OR_DISCHARGE(translate),
                },
                {
                    type: "component-line",
                    component: ChargeDischargeChartComponent,
                    inputs: {
                        edge: edge,
                        refresh: false,
                        data: energyScheduler.schedule,
                    },
                },
                {
                    type: "channel-line",
                    name: translate.instant("GENERAL.SOC"),
                    channel: new ChannelAddress("_sum", "EssSoc").toString(),
                    converter: Converter.STATE_IN_PERCENT,
                    style: {
                        name: { fontSize: "large" },
                        value: { fontSize: "large", textAlign: "right" },
                    },
                    cssClass: "ion-padding-top",
                },
                {
                    type: "component-line",
                    component: SocChartComponent,
                    inputs: {
                        edge: edge,
                        refresh: false,
                        data: energyScheduler.schedule,
                    },
                },
            );

            if (config.hasFactories(["Controller.Ess.Time-Of-Use-Tariff"])) {
                lines.push(
                    {
                        type: "channel-line",
                        name: translate.instant("GENERAL.MODE"),
                        channel: new ChannelAddress("ctrlEssTimeOfUseTariff0", "StateMachine").toString(),
                        converter: Utils.CONVERT_TIME_OF_USE_TARIFF_STATE(translate),
                        style: {
                            name: { fontSize: "large" },
                            value: { fontSize: "large" },
                        },
                        cssClass: "ion-padding-top",
                    },
                    {
                        type: "component-line",
                        component: ModeChartComponent,
                        inputs: {
                            edge: edge,
                            refresh: false,
                            data: energyScheduler.schedule,
                        },
                    },
                );

                lines.push({
                    type: "horizontal-line",
                });
            }

            lines.push({
                type: "name-line",
                name: translate.instant("GENERAL.DETAILS"),
                style: {
                    name: { fontSize: "large" },
                },
                cssClass: "ion-padding-top",
            });
        }

        lines.push(...controllerLines);
        return lines;
    }

    /**
     * Gets the lines for a 'Controller.Ess.ChargeDischargeLimiter': state, SoC range and - while relevant - the
     * progress of the balancing.
     *
     * @param controller The controller
     * @param translate The translate service
     * @returns The lines
     */
    private static getChargeDischargeLimiterLines(
        controller: EdgeConfig.Component,
        translate: TranslateService,
    ): OeFormlyField[] {
        const prefix = "EDGE.INDEX.WIDGETS.CHARGE_DISCHARGE_STATE.";
        const channel = (channelId: string) => new ChannelAddress(controller.id, channelId);
        const value = (currentData: CurrentData, channelId: string): number | null =>
            currentData.allComponents[controller.id + "/" + channelId] ?? null;

        return [
            {
                type: "value-from-channels-line",
                name: translate.instant("EDGE.INDEX.CHARGE_DISCHARGE_LIMITER.CHARGE_DISCHARGE_LIMITER"),
                channelsToSubscribe: [channel("_PropertyMinSoc"), channel("_PropertyMaxSoc")],
                value: (currentData: CurrentData) => {
                    const minSoc = value(currentData, "_PropertyMinSoc");
                    const maxSoc = value(currentData, "_PropertyMaxSoc");
                    return minSoc == null || maxSoc == null ? null : minSoc + " % - " + maxSoc + " %";
                },
            },
            {
                type: "value-from-channels-line",
                name: translate.instant("GENERAL.MODE"),
                channelsToSubscribe: [channel("StateMachine")],
                value: (currentData: CurrentData) => {
                    const state = value(currentData, "StateMachine");
                    const key = state == null ? null : CHARGE_DISCHARGE_LIMITER_STATES[state];
                    return key == null ? null : translate.instant(prefix + key);
                },
            },
            {
                type: "value-from-channels-line",
                name: translate.instant(prefix + "BALANCING_DEFERRAL_REASON_LABEL"),
                channelsToSubscribe: [channel("StateMachine"), channel("BalancingDeferralReason")],
                value: (currentData: CurrentData) => {
                    const reason = value(currentData, "BalancingDeferralReason");
                    const key = reason == null ? null : CHARGE_DISCHARGE_LIMITER_DEFERRAL_REASONS[reason];
                    return key == null ? null : translate.instant(prefix + "BALANCING_DEFERRAL_REASON." + key);
                },
                // Only while balancing is wanted and deferred
                filter: (currentData: CurrentData) =>
                    value(currentData, "StateMachine") === 7 && (value(currentData, "BalancingDeferralReason") ?? 0) > 0,
            },
        ];
    }

    public override getFormGroup(): FormGroup {
        return new FormGroup({
            soc: new FormControl(null),
        });
    }

    protected override async generateView(): Promise<OeFormlyView> {
        const edge = this.service.currentEdge();
        AssertionUtils.assertIsDefined(edge);

        const config = edge.getCurrentConfig();
        AssertionUtils.assertIsDefined(config);

        const energy = new EnergySchedulerV2(config);

        return await CommonStorageHomeComponent.getFormlyGeneralView(
            this.translate,
            this.service,
            edge,
            config,
            energy,
        );
    }


    protected override onCurrentData(currentData: CurrentData): void {
        this.setFormControlSafelyWithValue(this.form, "soc", currentData.allComponents["_sum/EssSoc"]);
    }

    protected override getChannelAddresses(): Promise<ChannelAddress[]> {
        return Promise.resolve([new ChannelAddress("_sum", "EssSoc")]);
    }

}

/** States of the 'Controller.Ess.ChargeDischargeLimiter'; value to translation key */
const CHARGE_DISCHARGE_LIMITER_STATES: { [state: number]: string } = {
    [-1]: "UNDEFINED",
    0: "NORMAL",
    1: "ERROR",
    2: "BELOW_MIN_SOC",
    3: "ABOVE_MAX_SOC",
    4: "MIN_SOC_REACHED",
    5: "MAX_SOC_REACHED",
    6: "FORCE_CHARGE_ACTIVE",
    7: "BALANCING_WANTED",
    8: "BALANCING_ACTIVE",
    10: "APPROACHING_MIN_SOC",
    11: "APPROACHING_MAX_SOC",
};

/** Reasons for a deferred balancing; value to translation key */
const CHARGE_DISCHARGE_LIMITER_DEFERRAL_REASONS: { [reason: number]: string } = {
    1: "PEAKSHAVING",
    2: "PRICE_LIMIT",
};

export const ESS_CHARGE_OR_DISCHARGE =
    (translate: TranslateService): Converter =>
    (raw): string => {
        const displayText = (power: string, color: "danger" | "success" | "inherit", text: string): string => {
            return `<span>${power}&nbsp;<ion-label color="${color}">${text}</ion-label></span>`;
        };

        return Converter.IF_NUMBER(raw, (value) => {
            if (value > 0) {
                return displayText(
                    Converter.POWER_IN_KILO_WATT(value),
                    "danger",
                    translate.instant("GENERAL.DISCHARGE"),
                );
            } else if (value < 0) {
                return displayText(
                    Converter.POWER_IN_KILO_WATT(Math.abs(value)),
                    "success",
                    translate.instant("GENERAL.CHARGE"),
                );
            } else {
                return Converter.POWER_IN_KILO_WATT(value);
            }
        });
    };
