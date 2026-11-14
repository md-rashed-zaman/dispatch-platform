package dev.rashed.dispatch.shared;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/** An amount in minor units (poisha for BDT). Never a double: 0.1 + 0.2 != 0.3 in floating point. */
@Embeddable
public record Money(long minor, String currency) {

    public Money {
        if (minor < 0) {
            throw new IllegalArgumentException("Money can't be negative: " + minor);
        }
        Objects.requireNonNull(currency, "currency");
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be an ISO 4217 code: " + currency);
        }
    }

    public static Money bdt(long poisha) {
        return new Money(poisha, "BDT");
    }

    @JsonIgnore // a question about the value, not part of it: keeps "positive" out of API responses
    public boolean isPositive() {
        return minor > 0;
    }
}
