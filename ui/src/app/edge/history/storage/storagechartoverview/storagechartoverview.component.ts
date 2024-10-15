// @ts-strict-ignore
import { Component, OnInit } from "@angular/core";
import { ActivatedRoute } from "@angular/router";
import { Edge, EdgeConfig, Service, Utils } from "../../../../shared/shared";

@Component({
    selector: StorageChartOverviewComponent.SELECTOR,
    templateUrl: "./storagechartoverview.component.html",
})
export class StorageChartOverviewComponent implements OnInit {

    private static readonly SELECTOR = "storage-chart-overview";

    public edge: Edge | null = null;

    public essComponents: EdgeConfig.Component[] | null = null;
    public chargerComponents: EdgeConfig.Component[] | null = null;

    public showPhases: boolean = false;
    public showTotal: boolean = false;
    public isOnlyChart = null;

    // reference to the Utils method to access via html
    public isLastElement = Utils.isLastElement;

    constructor(
        public service: Service,
        private route: ActivatedRoute,
    ) { }

    ngOnInit() {
        console.log("ngOnInit: StorageChartOverviewComponent initialized");

        // Fetch the current edge
        this.service.getCurrentEdge().then(edge => {
            console.log("getCurrentEdge response:", edge);
            this.edge = edge;

            // Fetch the configuration for the edge
            this.service.getConfig().then(config => {
                console.log("getConfig response:", config);

                // Get components implementing the ESS nature
                this.essComponents = config.getComponentsImplementingNature("io.openems.edge.ess.api.SymmetricEss")
                    .filter(component => {
                        console.log("Filtering component:", component);
                        return !component.factoryId.includes("Ess.Cluster");
                    });

                console.log("Filtered ESS Components:", this.essComponents);

                // Get components implementing the charger nature
                this.chargerComponents = config.getComponentsImplementingNature("io.openems.edge.ess.dccharger.api.EssDcCharger");
                console.log("Charger Components:", this.chargerComponents);

                // Logic to decide if there is only one chart
                if (this.essComponents != null && this.essComponents.length == 1) {
                    console.log("Only one ESS component found, setting isOnlyChart to true");
                    this.isOnlyChart = true;
                } else if (this.essComponents && this.essComponents.length > 1) {
                    console.log("More than one ESS component found, initializing total view");
                    this.showTotal = false;
                    this.isOnlyChart = false;
                } else {
                    console.log("No ESS components found, setting isOnlyChart to false");
                    this.isOnlyChart = false;
                }
            }).catch(error => {
                console.error("Error fetching config:", error);
            });
        }).catch(error => {
            console.error("Error fetching edge:", error);
        });
    }

    onNotifyPhases(showPhases: boolean): void {
        console.log("onNotifyPhases: Toggling phases view", showPhases);
        this.showPhases = showPhases;
    }

    onNotifyTotal(showTotal: boolean): void {
        console.log("onNotifyTotal: Toggling total view", showTotal);
        this.showTotal = showTotal;
    }
}
