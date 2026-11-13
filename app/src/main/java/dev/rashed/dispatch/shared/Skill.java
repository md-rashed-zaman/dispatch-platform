package dev.rashed.dispatch.shared;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/** A skill code like {@code pos-terminal} or {@code networking}: lowercase words joined by dashes. */
public record Skill(String code) {

    public Skill {
        if (code == null || !code.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Invalid skill code: " + code);
        }
    }

    /** Validates codes and returns them sorted and de-duplicated, ready to store in a text[] column. */
    public static Set<String> codes(Collection<String> codes) {
        var result = new TreeSet<String>();
        for (var code : codes) {
            result.add(new Skill(code).code());
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("At least one skill is required");
        }
        return result;
    }
}
