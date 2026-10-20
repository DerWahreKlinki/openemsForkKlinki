// @ts-strict-ignore
import { Component, ChangeDetectionStrategy } from "@angular/core";
import { EvcsComponent } from "src/app/shared/components/edge/config-components/evcs/evcsComponent";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { Modal } from "src/app/shared/components/flat/flat";
import { ChannelAddress, CurrentData, EdgeConfig, Utils } from "src/app/shared/shared";
import { DefaultTypes } from "src/app/shared/type/defaulttypes";

import { ModalComponent } from "../modal/modal";
import { EvcsPriceForecast } from "../price/price-forecast";

type ChargeMode = "FORCE_CHARGE" | "EXCESS_POWER" | "OFF";
const TARGET_CHARGE = "EDGE.INDEX.WIDGETS.EVCS.TARGET_CHARGE";

@Component({
    selector: "Controller_Evcs",
    templateUrl: "./flat.html",
    changeDetection: ChangeDetectionStrategy.Eager,
    standalone: false,
})
export class FlatComponent extends AbstractFlatWidget {
    public readonly CONVERT_WATT_TO_KILOWATT = Utils.CONVERT_WATT_TO_KILOWATT;
    public readonly CONVERT_MANUAL_ON_OFF = Utils.CONVERT_MANUAL_ON_OFF(this.translate);

    protected controller: EdgeConfig.Component;
    protected evcsComponent: EdgeConfig.Component | null = null;
    protected isConnectionSuccessful: boolean = false;
    protected isEnergySinceBeginningAllowed: boolean = false;
    protected mode: string;
    protected isChargingEnabled: boolean = false;
    protected defaultChargeMinPower: number;
    protected prioritization: string;
    protected phases: number;
    protected maxChargingValue: number;
    protected energySessionLimit: number;
    protected state: string = "";
    protected minChargePower: number;
    protected maxChargePower: number;
    protected forceChargeMinPower: string;
    protected chargeMode: ChargeMode | null = null;
    protected readonly CONVERT_TO_WATT = Utils.CONVERT_TO_WATT;
    protected readonly CONVERT_TO_KILO_WATTHOURS = Utils.CONVERT_TO_KILO_WATTHOURS;
    protected readonly CONVERT_MANUAL_ON_OFF_AUTOMATIC = Utils.CONVERT_MODE_TO_MANUAL_OFF_AUTOMATIC(this.translate);
    protected chargeTarget: string;
    protected energySession: string;
    protected chargeDischargePower: { name: string; value: number };
    protected propertyMode: DefaultTypes.ManualOnOff | null = null;
    protected status: string;
    protected isReadWrite: boolean;
    protected modalComponent: Modal | null = null;
    protected isPriceController: boolean = false;
    protected minHardwarePower: number | null = null;
    protected maxHardwarePower: number | null = null;
    /** Replacement cost of storage power in [Cent/kWh], Controller.Evcs.Price only */
    protected storagePrice: number | null = null;
    /** Total storage capacity in [Wh] */
    protected storageCapacity: number | null = null;
    /** Target charging of Controller.Evcs.Price is on */
    private targetCharging: boolean = false;
    /** Target energy and time, e.g. "10 kWh until 07:00" */
    private targetText: string | null = null;
    /** Planned energy and cost, e.g. "8.2 kWh, 2.95 EUR" */
    private plannedText: string | null = null;
    /** Warning of target charging; null while the plan covers the target */
    private targetStatusText: string | null = null;
    private storageLockedText: string | null = null;

    private chargePoint: EvcsComponent;

    protected override afterIsInitialized(): void {
        this.modalComponent = this.getModalComponent();
    }

    protected getModalComponent(): Modal {
        return {
            component: ModalComponent,
            componentProps: {
                component: this.component,
            },
        };
    }

    protected override getChannelAddresses(): ChannelAddress[] {
        this.chargePoint = EvcsComponent.from(this.component, this.edge.getCurrentConfig(), this.edge);

        const result = [
            this.chargePoint.powerChannel,
            new ChannelAddress(this.component.id, "Phases"),
            new ChannelAddress(this.component.id, "Plug"),
            new ChannelAddress(this.component.id, "Status"),
            new ChannelAddress(this.component.id, "State"),
            new ChannelAddress(this.component.id, "EnergySession"),
            // channels for modal component, subscribe here for better UX
            new ChannelAddress(this.component.id, "MinimumHardwarePower"),
            new ChannelAddress(this.component.id, "MaximumHardwarePower"),
            new ChannelAddress(this.component.id, "SetChargePowerLimit"),
        ];

        const controllers = [
            ...this.config.getComponentsByFactory("Controller.Evcs"),
            ...this.config.getComponentsByFactory("Controller.Evcs.Price"),
        ].filter((controller) => controller.isEnabled);
        for (const controller of controllers) {
            const properties = controller.properties;
            if ("evcs.id" in properties && properties["evcs.id"] === this.componentId) {
                this.controller = controller;
                result.push(new ChannelAddress(controller.id, "_PropertyEnabledCharging"));
                if (controller.factoryId === EvcsPriceForecast.FACTORY_ID) {
                    result.push(new ChannelAddress(controller.id, "StoragePrice"));
                    result.push(new ChannelAddress(controller.id, "AwaitingHysteresis"));
                    result.push(new ChannelAddress(controller.id, "HysteresisRemaining"));
                    result.push(new ChannelAddress(controller.id, "ChargePowerLimit"));
                    result.push(new ChannelAddress(controller.id, "PauseToday"));
                    result.push(new ChannelAddress("_sum", "EssCapacity"));
                    result.push(new ChannelAddress(controller.id, "_PropertyTargetCharging"));
                    result.push(new ChannelAddress(controller.id, "TargetStatus"));
                    result.push(new ChannelAddress(controller.id, "StorageLocked"));
                    result.push(new ChannelAddress(controller.id, "SessionEnergy"));
                    result.push(new ChannelAddress(controller.id, "TargetDeadline"));
                    result.push(new ChannelAddress(controller.id, "PlanEnergy"));
                    result.push(new ChannelAddress(controller.id, "PlanCost"));
                }
            }
        }
        return result;
    }

    protected override onCurrentData(currentData: CurrentData) {
        if (this.controller != null && this.controller.factoryId === EvcsPriceForecast.FACTORY_ID) {
            const storagePrice = currentData.allComponents[this.controller.id + "/StoragePrice"];
            this.storagePrice = storagePrice == null ? null : storagePrice / 10;
            this.storageCapacity = currentData.allComponents["_sum/EssCapacity"] ?? null;
        }
        this.evcsComponent = this.config.getComponent(this.component.id);
        this.isConnectionSuccessful = currentData.allComponents[this.component.id + "/State"] != 3 ? true : false;
        this.isReadWrite = this.component.hasPropertyValue<boolean>("readOnly", true) === false;

        // Check if Energy since beginning is allowed
        if (
            currentData.allComponents[this.chargePoint.powerChannel.toString()] > 0 ||
            currentData.allComponents[this.component.id + "/Status"] == 2 ||
            currentData.allComponents[this.component.id + "/Status"] == 7
        ) {
            this.isEnergySinceBeginningAllowed = true;
        }

        // Mode
        if (this.isChargingEnabled) {
            if (this.targetCharging) {
                this.mode = this.translate.instant(TARGET_CHARGE + ".SHORT_NAME");
            } else if (this.chargeMode == "FORCE_CHARGE") {
                this.mode = this.translate.instant("GENERAL.MANUALLY");
            } else if (this.chargeMode == "EXCESS_POWER") {
                this.mode = this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.OPTIMIZED_CHARGE_MODE.SHORT_NAME");
            }
        }

        // Check if Controller is set
        if (this.controller) {
            // ChargeMode
            this.chargeMode = this.controller.properties["chargeMode"];
            // Check if Charging is enabled
            this.isChargingEnabled =
                currentData.allComponents[this.controller.id + "/_PropertyEnabledCharging"] === 1 ? true : false;
            // DefaultChargeMinPower
            this.defaultChargeMinPower = this.controller.properties["defaultChargeMinPower"];
            // Prioritization
            // TODO translation string should be explicit
            this.prioritization =
                this.controller.properties["priority"] in Prioritization
                    ? "EDGE.INDEX.WIDGETS.EVCS.OPTIMIZED_CHARGE_MODE.CHARGING_PRIORITY." +
                      this.controller.properties["priority"]
                    : "";
            // MaxChargingValue
            if (this.phases) {
                this.maxChargingValue = Utils.multiplySafely(
                    this.controller.properties["forceChargeMinPower"],
                    this.phases,
                );
            } else {
                this.maxChargingValue = Utils.multiplySafely(this.controller.properties["forceChargeMinPower"], 3);
            }
            // EnergySessionLimit
            this.energySessionLimit = this.controller.properties["energySessionLimit"];
            this.isPriceController = this.controller.factoryId === EvcsPriceForecast.FACTORY_ID;
        }

        this.status = this.getState(
            this.isChargingEnabled,
            currentData.allComponents[this.component.id + "/Status"],
            currentData.allComponents[this.component.id + "/Plug"],
        );
        // Price controller: target charging with its target, plan and warning
        if (this.isPriceController && this.controller != null) {
            const targetCharging = currentData.allComponents[this.controller.id + "/_PropertyTargetCharging"];
            this.targetCharging = targetCharging === 1 || targetCharging === true || targetCharging === "true";
            const deadline = currentData.allComponents[this.controller.id + "/TargetDeadline"];
            const targetEnergy = Number(this.controller.properties["targetEnergy"] ?? 0);
            this.targetText =
                this.targetCharging && deadline != null
                    ? this.translate.instant(TARGET_CHARGE + ".UNTIL", {
                          energy: (targetEnergy / 1000).toFixed(1) + " kWh",
                          time: new Date(deadline * 1000).toLocaleString(undefined, {
                              weekday: "short",
                              hour: "2-digit",
                              minute: "2-digit",
                          }),
                      })
                    : null;
            const planEnergy = currentData.allComponents[this.controller.id + "/PlanEnergy"];
            const planCost = currentData.allComponents[this.controller.id + "/PlanCost"];
            this.plannedText =
                this.targetCharging && planEnergy != null
                    ? (planEnergy / 1000).toFixed(1) +
                      " kWh" +
                      (planCost == null ? "" : ", " + (planCost / 100).toFixed(2) + " €")
                    : null;
            const targetStatus = currentData.allComponents[this.controller.id + "/TargetStatus"];
            this.targetStatusText =
                this.targetCharging && targetStatus != null && targetStatus !== "PLANNED" && targetStatus !== "NONE"
                    ? this.translate.instant(TARGET_CHARGE + ".STATUS_" + targetStatus)
                    : null;
            const storageLocked = currentData.allComponents[this.controller.id + "/StorageLocked"];
            this.storageLockedText =
                this.targetCharging && (storageLocked === 1 || storageLocked === true)
                    ? this.translate.instant(TARGET_CHARGE + ".STORAGE_LOCKED")
                    : null;
        }
        // Price controller: "Do not charge today" replaces the state; while a start or stop
        // hysteresis runs, show it with the remaining time
        if (this.isPriceController && this.isChargingEnabled && this.controller != null) {
            const pauseToday = currentData.allComponents[this.controller.id + "/PauseToday"];
            const awaiting = currentData.allComponents[this.controller.id + "/AwaitingHysteresis"];
            if (pauseToday === 1 || pauseToday === true) {
                this.status = this.translate.instant("EDGE.INDEX.WIDGETS.EVCS.PAUSE_TODAY");
            } else if (awaiting === 1 || awaiting === true) {
                const remaining = currentData.allComponents[this.controller.id + "/HysteresisRemaining"];
                const limit = currentData.allComponents[this.controller.id + "/ChargePowerLimit"] ?? 0;
                const time =
                    remaining == null
                        ? ""
                        : Math.floor(remaining / 60) + ":" + String(remaining % 60).padStart(2, "0") + " min";
                this.status = this.translate.instant(
                    limit > 0 ? "EDGE.INDEX.WIDGETS.EVCS.HYSTERESIS_STOP" : "EDGE.INDEX.WIDGETS.EVCS.HYSTERESIS_START",
                    { time: time },
                );
            }
        }
        // Phases
        this.phases = currentData.allComponents[this.componentId + "/Phases"];

        this.chargeDischargePower = Utils.convertChargeDischargePower(
            this.translate,
            currentData.allComponents[this.chargePoint.powerChannel.toString()],
        );
        this.chargeTarget = Utils.CONVERT_TO_WATT(
            this.formatNumber(currentData.allComponents[this.component.id + "/SetChargePowerLimit"]),
        );
        this.energySession = Utils.CONVERT_TO_WATT(currentData.allComponents[this.component.id + "/EnergySession"]);

        this.minChargePower = this.formatNumber(currentData.allComponents[this.component.id + "/MinimumHardwarePower"]);
        this.maxChargePower = this.formatNumber(currentData.allComponents[this.component.id + "/MaximumHardwarePower"]);
        this.minHardwarePower = currentData.allComponents[this.component.id + "/MinimumHardwarePower"] ?? null;
        this.maxHardwarePower = currentData.allComponents[this.component.id + "/MaximumHardwarePower"] ?? null;
        this.state = currentData.allComponents[this.component.id + "/Status"];
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
        } else if (plug != ChargePlug.PLUGGED_ON_EVCS_AND_ON_EV_AND_LOCKED) {
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
        }
    }

    private formatNumber(i: number) {
        const round = Math.ceil(i / 100) * 100;
        return round;
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
enum Prioritization {
    CAR,
    STORAGE,
}
