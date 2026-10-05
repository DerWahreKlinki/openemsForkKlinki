import { Component, input, ChangeDetectionStrategy } from "@angular/core";
import { CommonUiModule } from "src/app/shared/common-ui.module";
import { ComponentsBaseModule } from "src/app/shared/components/components.module";
import { AbstractModal } from "src/app/shared/components/modal/abstractModal";
import { ChannelAddress, CurrentData, EdgeConfig } from "src/app/shared/shared";

@Component({
    selector: "oe-common-storage-percentagebar",
    templateUrl: "./percentagebar.html",
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [CommonUiModule, ComponentsBaseModule],
})
export class CommonStoragePercentagebarComponent extends AbstractModal {
    public emergencyReserveController = input<EdgeConfig.Component | null>(null);
    public chargeDischargeLimiterController = input<EdgeConfig.Component | null>(null);
    public essComponentId = input<EdgeConfig.Component["id"] | null>(null);
    protected reserveSoc: number | null = null;
    protected isEmergencyReserveEnabled: boolean = false;
    protected limiterMinSoc: number | null = null;
    protected limiterMaxSoc: number | null = null;
    protected limiterBalancingSoc: number | null = null;
    protected limiterState: number | null = null;

    protected override getChannelAddresses(): ChannelAddress[] {
        const channelAddresses: ChannelAddress[] = [];
        const emergencyReserveController = this.emergencyReserveController();
        if (emergencyReserveController != null) {
            channelAddresses.push(
                new ChannelAddress(emergencyReserveController.id, "_PropertyIsReserveSocEnabled"),
                new ChannelAddress(emergencyReserveController.id, "_PropertyReserveSoc"),
            );
        }
        const limiterController = this.chargeDischargeLimiterController();
        if (limiterController != null) {
            channelAddresses.push(
                new ChannelAddress(limiterController.id, "_PropertyMinSoc"),
                new ChannelAddress(limiterController.id, "_PropertyMaxSoc"),
                new ChannelAddress(limiterController.id, "BalancingSoc"),
                new ChannelAddress(limiterController.id, "StateMachine"),
            );
        }
        return channelAddresses;
    }

    protected override onCurrentData(currentData: CurrentData): void {
        const limiterController = this.chargeDischargeLimiterController();
        if (limiterController != null) {
            this.limiterMinSoc = currentData.allComponents[limiterController.id + "/_PropertyMinSoc"] ?? null;
            this.limiterMaxSoc = currentData.allComponents[limiterController.id + "/_PropertyMaxSoc"] ?? null;
            this.limiterBalancingSoc = currentData.allComponents[limiterController.id + "/BalancingSoc"] ?? null;
            this.limiterState = currentData.allComponents[limiterController.id + "/StateMachine"] ?? null;
        }

        const emergencyReserveController = this.emergencyReserveController();
        if (emergencyReserveController == null) {
            return;
        }

        this.reserveSoc = currentData.allComponents[emergencyReserveController.id + "/_PropertyReserveSoc"];
        this.isEmergencyReserveEnabled =
            currentData.allComponents[emergencyReserveController.id + "/_PropertyIsReserveSocEnabled"];
    }
}
