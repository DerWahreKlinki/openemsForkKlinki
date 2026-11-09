import { NgModule } from "@angular/core";
import { TranslateService } from "@ngx-translate/core";
import { SharedModule } from "src/app/shared/shared.module";
import { Language } from "src/app/shared/type/language";
import { PriceForecastComponent } from "./flat/flat";
import { PriceForecastModalComponent } from "./modal/modal";
import de from "./shared/i18n/de.json";
import en from "./shared/i18n/en.json";
import { EuroRatingPipe, PriceCtPipe } from "./shared/price-forecast.pipes";

/**
 * Fork (klinki): widget "Strompreisprognose" for a TimeOfUseTariff.Forecast component.
 */
@NgModule({
    imports: [
        SharedModule,
        PriceCtPipe,
        EuroRatingPipe,
    ],
    declarations: [
        PriceForecastComponent,
        PriceForecastModalComponent,
    ],
    exports: [PriceForecastComponent],
})
export class PriceForecastModule {
    constructor(private translate: TranslateService) {
        Language.normalizeAdditionalTranslationFiles({
            de: de,
            en: en,
        } as any).then((translations) => {
            for (const { lang, translation, shouldMerge } of translations) {
                translate.setTranslation(lang, translation, shouldMerge);
            }
        });
    }
}
