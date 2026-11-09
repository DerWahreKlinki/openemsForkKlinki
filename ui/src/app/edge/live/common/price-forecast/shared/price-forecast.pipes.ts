import { formatNumber } from "@angular/common";
import { inject, LOCALE_ID, Pipe, PipeTransform } from "@angular/core";

/** [Currency/MWh] to "47,5", the unit is added in the template. */
@Pipe({ name: "priceCt", standalone: true, pure: true })
export class PriceCtPipe implements PipeTransform {
    private readonly locale = inject(LOCALE_ID);

    transform(price: number | null | undefined, digits: string = "1.1-1"): string {
        if (price === null || price === undefined || !Number.isFinite(price)) {
            return "-";
        }
        return formatNumber(price / 10, this.locale, digits);
    }
}

/** Rating 1..5 to "€".."€€€€€". */
@Pipe({ name: "euroRating", standalone: true, pure: true })
export class EuroRatingPipe implements PipeTransform {
    transform(rating: number | null | undefined): string {
        if (rating === null || rating === undefined || rating < 1) {
            return "-";
        }
        return "€".repeat(Math.min(5, Math.round(rating)));
    }
}
