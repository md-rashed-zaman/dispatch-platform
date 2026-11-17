package dev.rashed.dispatch.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactionTest {

    @Test
    void removesPhoneNumbersAndEmails() {
        assertThat(Redaction.redact("Call 01712-345678, +8801712345678 or (017) 1234 5678; mail a.b+x@bank.com.bd"))
                .isEqualTo("Call [phone], [phone] or [phone]; mail [email]");
    }

    @Test
    void keepsWhatTheModelNeeds() {
        var text = "Printer E05 on floor 3, 2026-12-01 10:00, budget 3000 taka, 5 cameras, ticket 4521";
        assertThat(Redaction.redact(text)).isEqualTo(text);
    }

    @Test
    void keepsTextWithDollarSignsIntact() {
        assertThat(Redaction.redact("Costs $25 or 2500000 taka")).isEqualTo("Costs $25 or 2500000 taka");
    }
}
