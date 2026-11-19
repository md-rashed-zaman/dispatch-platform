package dev.rashed.dispatch.flags;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FlagTest {

    @Test
    void theSameCompanyAlwaysLandsInTheSameBucket() {
        var tenant = UUID.fromString("01930000-0000-7000-8000-000000000001");
        var bucket = Flag.bucket("ai.auto-approve", tenant);

        assertThat(IntStream.range(0, 100).map(i -> Flag.bucket("ai.auto-approve", tenant)))
                .containsOnly(bucket);
        assertThat(bucket).isBetween(0, 99);
    }

    @Test
    void aboutTheRolloutShareOfCompaniesGetsIt() {
        var flag = flag(true, 20, Set.of(), Set.of());
        long on = IntStream.range(0, 10_000)
                .filter(i -> flag.evaluate(UUID.randomUUID()).on())
                .count();

        assertThat(on).isBetween(1_850L, 2_150L); // 20% of 10,000, within about 4 standard deviations
    }

    @Test
    void growingTheRolloutOnlyAddsCompanies() {
        var at20 = flag(true, 20, Set.of(), Set.of());
        var at50 = flag(true, 50, Set.of(), Set.of());
        for (int i = 0; i < 5_000; i++) {
            var tenant = UUID.randomUUID();
            if (at20.evaluate(tenant).on()) {
                assertThat(at50.evaluate(tenant).on())
                        .as("a company in at 20%% stays in at 50%%")
                        .isTrue();
            }
        }
    }

    @Test
    void differentFlagsPickDifferentCompanies() {
        long same = IntStream.range(0, 2_000)
                .mapToObj(i -> UUID.randomUUID())
                .filter(t -> Flag.bucket("ai.auto-approve", t) == Flag.bucket("payments.required", t))
                .count();
        assertThat(same).isLessThan(60); // about 1 in 100 by chance
    }

    @Test
    void theKillSwitchAndTheListsComeBeforeThePercentage() {
        var tenant = UUID.randomUUID();

        assertThat(flag(false, 100, Set.of(tenant), Set.of()).evaluate(tenant))
                .isEqualTo(new Flag.Evaluation(false, Flag.Reason.DISABLED, null));
        assertThat(flag(true, 100, Set.of(), Set.of(tenant)).evaluate(tenant).on())
                .isFalse();
        assertThat(flag(true, 0, Set.of(tenant), Set.of()).evaluate(tenant))
                .isEqualTo(new Flag.Evaluation(true, Flag.Reason.TARGETING_MATCH, null));
        assertThat(flag(true, 100, Set.of(tenant), Set.of(tenant))
                        .evaluate(tenant)
                        .on())
                .as("deny wins over allow")
                .isFalse();
        assertThat(flag(true, 100, Set.of(), Set.of()).evaluate(null).on())
                .as("no company: off")
                .isFalse();
        assertThat(flag(true, 100, Set.of(), Set.of()).evaluate(tenant).reason())
                .isEqualTo(Flag.Reason.SPLIT);
    }

    private static Flag flag(boolean enabled, int percent, Set<UUID> allow, Set<UUID> deny) {
        return new Flag("ai.auto-approve", "test", enabled, percent, allow, deny, 0, Instant.now(), null);
    }
}
