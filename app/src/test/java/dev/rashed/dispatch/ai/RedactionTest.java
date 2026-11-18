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

    @Test
    void removesTheStreetPartOfAnAddressButKeepsTheArea() {
        assertThat(Redaction.redact("ATM at House 12, Road 5, Dhanmondi is down; also Flat 3B, plot #7/A in Uttara"))
                .isEqualTo("ATM at [address], Dhanmondi is down; also [address] in Uttara");
        assertThat(Redaction.redact("বাড়ি ১২, রোড ৫, ধানমন্ডি এর প্রিন্টার নষ্ট"))
                .isEqualTo("[address], ধানমন্ডি এর প্রিন্টার নষ্ট");
        assertThat(Redaction.redact("Holding no. 45, Lane 3 near the market")).isEqualTo("[address] near the market");
    }

    @Test
    void keepsAddressesForAFeatureThatNeedsThem() {
        assertThat(Redaction.redact("Banani Road 11, call 01712-345678", true))
                .isEqualTo("Banani Road 11, call [phone]");
    }

    @Test
    void doesNotMistakeOtherWordsForAnAddress() {
        var text = "Printer on the 3rd floor, Sector 7 office, 5 houses wired, roadside cabinet 2, flat rate 500";
        assertThat(Redaction.redact(text)).isEqualTo(text);
    }
}
