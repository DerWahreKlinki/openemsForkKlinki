import { Component } from "@angular/core";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";

@Component({
    selector: "oe-controller-chp-cost-optimization",
    templateUrl: "./flat.html",
    standalone: false,
})
export class FlatComponent extends AbstractFlatWidget {

    protected producedEnergy: number | null = null;

    protected override getChannelAddresses(): ChannelAddress[] {
        return [
            new ChannelAddress(this.componentId, "ChpActiveProductionEnergy"),
        ];
    }

    protected override onCurrentData(currentData: CurrentData) {
        this.producedEnergy = currentData.allComponents[this.componentId + "/ChpActiveProductionEnergy"];
    }
}
