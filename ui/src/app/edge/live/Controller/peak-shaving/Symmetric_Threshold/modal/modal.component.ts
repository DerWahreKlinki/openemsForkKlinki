// @ts-strict-ignore
import { Component, Input, OnInit } from "@angular/core";
import {
    FormBuilder,
    FormControl,
    FormGroup,
    Validators,
} from "@angular/forms";
import { ModalController } from "@ionic/angular";
import { TranslateService } from "@ngx-translate/core";
import {
    Edge,
    EdgeConfig,
    Service,
    Websocket,
} from "../../../../../../shared/shared";

@Component({
    selector: "thresholdpeakshaving-modal",
    templateUrl: "./modal.component.html",
    standalone: false,
})
export class Controller_Symmetric_Threshold_PeakShavingModalComponent implements OnInit {
    @Input() protected component: EdgeConfig.Component | null = null;
    @Input() protected edge: Edge | null = null;

    public formGroup: FormGroup;
    public loading: boolean = false;

    constructor(
        public formBuilder: FormBuilder,
        public modalCtrl: ModalController,
        public service: Service,
        public translate: TranslateService,
        public websocket: Websocket,
    ) {}

    ngOnInit() {
        const nonNegativeIntegerValidators = Validators.compose([
            Validators.pattern("^(?:[1-9][0-9]*|0)$"),
            Validators.required,
        ]);

        this.formGroup = this.formBuilder.group({
            peakShavingPower: new FormControl(
                this.component.properties.peakShavingPower,
                nonNegativeIntegerValidators,
            ),
            rechargePower: new FormControl(
                this.component.properties.rechargePower,
                nonNegativeIntegerValidators,
            ),
            peakShavingThresholdPower: new FormControl(
                this.component.properties.peakShavingThresholdPower,
                nonNegativeIntegerValidators,
            ),
        });
    }

    applyChanges() {
        if (this.edge == null || this.component == null) {
            return;
        }

        if (!this.edge.roleIsAtLeast("owner")) {
            this.service.toast(
                this.translate.instant("GENERAL.INSUFFICIENT_RIGHTS"),
                "danger",
            );
            return;
        }

        const peakShavingPower = this.formGroup.controls["peakShavingPower"];
        const rechargePower = this.formGroup.controls["rechargePower"];
        const peakShavingThresholdPower =
            this.formGroup.controls["peakShavingThresholdPower"];

        if (
            !peakShavingPower.valid ||
            !rechargePower.valid ||
            !peakShavingThresholdPower.valid
        ) {
            this.service.toast(
                this.translate.instant("GENERAL.INPUT_NOT_VALID"),
                "danger",
            );
            return;
        }

        if (Number(peakShavingPower.value) < Number(rechargePower.value)) {
            this.service.toast(
                this.translate.instant(
                    "EDGE.INDEX.WIDGETS.PEAKSHAVING.RELATION_ERROR",
                ),
                "danger",
            );
            return;
        }

        const updateComponentArray = Object.keys(this.formGroup.controls)
            .filter((key) => this.formGroup.controls[key].dirty)
            .map((key) => ({
                name: key,
                value: this.formGroup.controls[key].value,
            }));

        this.loading = true;

        this.edge
            .updateComponentConfig(
                this.websocket,
                this.component.id,
                updateComponentArray,
            )
            .then(() => {
                this.component.properties.peakShavingPower =
                    peakShavingPower.value;
                this.component.properties.rechargePower = rechargePower.value;
                this.component.properties.peakShavingThresholdPower =
                    peakShavingThresholdPower.value;

                this.service.toast(
                    this.translate.instant("GENERAL.CHANGE_ACCEPTED"),
                    "success",
                );
            })
            .catch((reason) => {
                peakShavingPower.setValue(
                    this.component.properties.peakShavingPower,
                );
                rechargePower.setValue(this.component.properties.rechargePower);
                peakShavingThresholdPower.setValue(
                    this.component.properties.peakShavingThresholdPower,
                );

                this.service.toast(
                    this.translate.instant("GENERAL.CHANGE_FAILED") +
                        "\n" +
                        reason.error.message,
                    "danger",
                );
                console.warn(reason);
            })
            .finally(() => {
                this.loading = false;
                this.formGroup.markAsPristine();
            });
    }
}
