import { FormControl, FormGroup } from "@angular/forms";
import { TranslateService } from "@ngx-translate/core";
import {
    NavigationConstants,
    NavigationTree,
} from "src/app/shared/components/navigation/shared";
import { Converter } from "src/app/shared/components/shared/converter";
import { Name } from "src/app/shared/components/shared/name";
import {
    OeFormlyField,
    OeFormlyView,
} from "src/app/shared/components/shared/oe-formly-component";
import { ChannelAddress, Edge, EdgeConfig } from "src/app/shared/shared";
import { Role } from "src/app/shared/type/role";
import { Icon } from "src/app/shared/type/widget";

export namespace SharedControllerThresholdPeakshaving {
    export const getFormlyFlatLines = (
        translate: TranslateService,
        component: EdgeConfig.Component,
    ): OeFormlyView["lines"] => {
        const lines: OeFormlyField[] = [];
        const meterId = component.getPropertyFromComponent<string>("meter.id");

        if (meterId != null) {
            lines.push({
                type: "channel-line",
                name: translate.instant("GENERAL.MEASURED_VALUE"),
                channel: meterId + "/ActivePower",
                converter: Converter.POWER_IN_KILO_WATT,
            });
        }

        lines.push(
            {
                type: "channel-line",
                name: translate.instant(
                    "EDGE.INDEX.WIDGETS.PEAKSHAVING.PEAKSHAVING_POWER",
                ),
                channel: component.id + "/_PropertyPeakShavingPower",
                converter: Converter.POWER_IN_KILO_WATT,
            },
            {
                type: "channel-line",
                name: translate.instant(
                    "EDGE.INDEX.WIDGETS.PEAKSHAVING.RECHARGE_POWER",
                ),
                channel: component.id + "/_PropertyRechargePower",
                converter: Converter.POWER_IN_KILO_WATT,
            },
            {
                type: "channel-line",
                name: translate.instant(
                    "EDGE.INDEX.WIDGETS.PEAKSHAVING.PEAKSHAVING_THRESHOLD_POWER",
                ),
                channel: component.id + "/_PropertyPeakShavingThresholdPower",
                converter: Converter.POWER_IN_KILO_WATT,
            },
        );

        return lines;
    };

    export const getFormlySettingsLines = (
        translate: TranslateService,
        component: EdgeConfig.Component,
        edge: Edge,
    ): OeFormlyView["lines"] => {
        const lines: OeFormlyField[] = [];
        const meterId = component.getPropertyFromComponent<string>("meter.id");

        if (meterId != null) {
            lines.push(
                {
                    type: "channel-line",
                    name: translate.instant("GENERAL.MEASURED_VALUE"),
                    channel: meterId + "/ActivePower",
                    converter: Converter.POWER_IN_KILO_WATT,
                },
                {
                    type: "horizontal-line",
                },
            );
        }

        if (edge.roleIsAtLeast(Role.OWNER)) {
            lines.push(
                {
                    type: "input-line",
                    name: translate.instant(
                        "EDGE.INDEX.WIDGETS.PEAKSHAVING.PEAKSHAVING_POWER",
                    ),
                    controlName: "peakShavingPower",
                    properties: {
                        unit: "W",
                    },
                },
                {
                    type: "input-line",
                    name: translate.instant(
                        "EDGE.INDEX.WIDGETS.PEAKSHAVING.RECHARGE_POWER",
                    ),
                    controlName: "rechargePower",
                    properties: {
                        unit: "W",
                    },
                },
                {
                    type: "input-line",
                    name: translate.instant(
                        "EDGE.INDEX.WIDGETS.PEAKSHAVING.PEAKSHAVING_THRESHOLD_POWER",
                    ),
                    controlName: "peakShavingThresholdPower",
                    properties: {
                        unit: "W",
                    },
                },
            );
        }

        return lines;
    };

    export function getChannelAddresses(
        component: EdgeConfig.Component,
    ): Promise<ChannelAddress[]> {
        const meterId = component.getPropertyFromComponent<string>("meter.id");

        return Promise.resolve([
            ...(meterId == null
                ? []
                : [new ChannelAddress(meterId, "ActivePower")]),
            new ChannelAddress(component.id, "_PropertyPeakShavingPower"),
            new ChannelAddress(component.id, "_PropertyRechargePower"),
            new ChannelAddress(component.id,"_PropertyPeakShavingThresholdPower"),
            new ChannelAddress(component.id, "PeakShavingPower"),
            new ChannelAddress(component.id, "PeakShavingTargetPower"),
            new ChannelAddress(component.id, "GridPowerWithoutPeakShaving"),
            new ChannelAddress(component.id, "PeakShavingStateMachine"),
        ]);
    }

    export function getFormGroup(): FormGroup {
        return new FormGroup({
            peakShavingPower: new FormControl(null),
            rechargePower: new FormControl(null),
            peakShavingThresholdPower: new FormControl(null),
        });
    }

    export function getNavigationTree(
        translate: TranslateService,
        component: EdgeConfig.Component,
    ): ConstructorParameters<typeof NavigationTree> {
        return new NavigationTree(
            component.id,
            {
                baseString:
                    "controller/peak-shaving-symmetric-threshold/" +
                    component.id,
            },
            { name: "trending-down-outline", color: "normal" },
            Name.METER_ALIAS_OR_ID(component),
            "label",
            [
                NavigationConstants.CommonNodes.HISTORY(translate),
                NavigationConstants.CommonNodes.SETTINGS(translate),
            ],
            null,
        ).toConstructorParams();
    }

    export const SHARED_ICON: Icon = {
        name: "trending-down-outline",
        color: "medium",
        size: "large",
    };
}
