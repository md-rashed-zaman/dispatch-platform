package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Offers", description = "Jobs offered to you, the technician")
@RestController
class OfferController {

    private final OfferService offers;

    OfferController(OfferService offers) {
        this.offers = offers;
    }

    @Operation(summary = "Your open offers, newest first")
    @GetMapping("/api/me/offers")
    CursorPage<OfferView> myOffers(
            @Parameter(description = "Page size, 1 to 100") @RequestParam(defaultValue = "20") @Min(1) @Max(100)
                    int limit,
            @Parameter(description = "The \"next\" value from the previous page; leave out for the first page")
                    @RequestParam(required = false)
                    String after) {
        return offers.myOpenOffers(limit, after);
    }

    @Operation(
            summary = "Accept an offer; the first technician to accept gets the job",
            description = "409 already-taken if another technician accepted first, 409 offer-closed if the offer"
                    + " expired. Accepting an offer you already won returns it again.")
    @PostMapping("/api/offers/{id}/accept")
    OfferView accept(@PathVariable UUID id) {
        return offers.accept(id);
    }
}
