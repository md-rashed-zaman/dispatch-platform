package dev.rashed.dispatch.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes contact details from text before it goes to a model or into the audit table. A model needs "printer
 * shows E05", not the caller's phone number; and what never leaves can't leak (T27).
 *
 * <p>shortcut: emails and phone numbers only, by pattern. Names and addresses stay, since features need the
 * address; a real deployment adds a named-entity redactor and a provider contract that excludes training.
 */
final class Redaction {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    // A run of digits with the separators people type in phone numbers. Counted as a phone number only with 9
    // to 15 digits (01712-345678 has 11): dates (2026-12-01, 8 digits) and times stay readable for the model.
    private static final Pattern PHONE_LIKE = Pattern.compile("(?<![\\w(])\\(?\\+?\\d[\\d\\s().-]{6,}\\d(?![\\w])");
    // "2026-12-01 10:00" would otherwise read as one 10-digit run.
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}");

    static String redact(String text) {
        if (text == null) {
            return null;
        }
        var withoutEmails = EMAIL.matcher(text).replaceAll("[email]");
        return PHONE_LIKE.matcher(withoutEmails).replaceAll(match -> {
            var run = match.group();
            long digits = run.chars().filter(Character::isDigit).count();
            boolean phone = digits >= 9 && digits <= 15 && !DATE.matcher(run).find();
            return phone ? "[phone]" : Matcher.quoteReplacement(run);
        });
    }

    private Redaction() {}
}
