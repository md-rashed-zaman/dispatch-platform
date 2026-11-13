package dev.rashed.dispatch.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValueObjectsTest {

    @Test
    void moneyRejectsNegativeAmountsAndBadCurrencies() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.bdt(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(100, "taka"));
        assertThat(Money.bdt(150_000)).isEqualTo(new Money(150_000, "BDT")); // value equality
    }

    @Test
    void geoPointRejectsOutOfRangeCoordinates() {
        // Dhaka is (23.81, 90.41). Swapping them gives latitude 90.41, which can't exist.
        assertThatIllegalArgumentException().isThrownBy(() -> new GeoPoint(90.41, 23.81));
        assertThat(new GeoPoint(23.81, 90.41).latitude()).isEqualTo(23.81);
    }

    @Test
    void timeWindowMustEndAfterItStarts() {
        var now = Instant.parse("2026-11-13T04:00:00Z");
        assertThatIllegalArgumentException().isThrownBy(() -> new TimeWindow(now, now));
        assertThatIllegalArgumentException().isThrownBy(() -> new TimeWindow(now, now.minusSeconds(60)));
    }

    @Test
    void skillCodesAreValidatedSortedAndDeduplicated() {
        assertThat(Skill.codes(List.of("networking", "pos-terminal", "networking")))
                .containsExactly("networking", "pos-terminal");
        assertThatIllegalArgumentException().isThrownBy(() -> Skill.codes(List.of("POS Terminal")));
        assertThatIllegalArgumentException().isThrownBy(() -> Skill.codes(List.of()));
    }
}
