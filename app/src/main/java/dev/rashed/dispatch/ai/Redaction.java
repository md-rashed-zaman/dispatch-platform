package dev.rashed.dispatch.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal details from text before it goes to a model or into the audit table. A model needs "printer
 * shows E05", not the caller's phone number; and what never leaves can't leak (T27, T32).
 *
 * <ul>
 *   <li>Emails and phone numbers: always.
 *   <li>The street-level part of an address ("House 12, Road 5", "Flat 3B", "বাড়ি ১২"): unless the feature needs
 *       the address (intake reads it out of the text). The area ("Dhanmondi") stays: it is coarse and the job
 *       often needs it.
 * </ul>
 *
 * <p>shortcut: by pattern; names stay. A real deployment adds a named-entity redactor and a provider contract
 * that excludes training.
 */
final class Redaction {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    // A run of digits with the separators people type in phone numbers. Counted as a phone number only with 9
    // to 15 digits (01712-345678 has 11): dates (2026-12-01, 8 digits) and times stay readable for the model.
    private static final Pattern PHONE_LIKE = Pattern.compile("(?<![\\w(])\\(?\\+?\\d[\\d\\s().-]{6,}\\d(?![\\w])");
    // "2026-12-01 10:00" would otherwise read as one 10-digit run.
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}");

    // "House 12", "Road #5", "Flat 3B", "Plot 7/A", "Holding no. 45", and the Bangla words, with ASCII or Bangla
    // digits; neighbouring parts ("House 12, Road 5") become one "[address]".
    private static final String NUMBER =
            "(?:no\\.?\\s*)?#?\\s*[0-9০-৯]+[A-Za-z]?(?:\\s*[/-]\\s*[0-9০-৯A-Za-z]+)?(?![\\p{L}\\p{N}])"; // not \\b:
    // ASCII-only
    // since Java
    // 19
    private static final String PART = "(?:house|road|rd\\.?|flat|apt\\.?|apartment|plot|holding|lane|"
            + "বা(?:\u09DC|\u09A1\u09BC)ি|বাসা|রোড|স(?:\u09DC|\u09A1\u09BC)ক|ফ্ল্যাট|প্লট|হোল্ডিং|লেন)\\s*" + NUMBER;
    private static final Pattern STREET_ADDRESS = Pattern.compile(
            "(?<![\\p{L}\\p{M}\\p{N}])" + PART + "(?:\\s*,?\\s*" + PART + ")*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Emails, phone numbers and street addresses removed. */
    static String redact(String text) {
        return redact(text, false);
    }

    static String redact(String text, boolean keepAddresses) {
        if (text == null) {
            return null;
        }
        var withoutEmails = EMAIL.matcher(text).replaceAll("[email]");
        var withoutAddresses = keepAddresses
                ? withoutEmails
                : STREET_ADDRESS.matcher(withoutEmails).replaceAll("[address]");
        return PHONE_LIKE.matcher(withoutAddresses).replaceAll(match -> {
            var run = match.group();
            long digits = run.chars().filter(Character::isDigit).count();
            boolean phone = digits >= 9 && digits <= 15 && !DATE.matcher(run).find();
            return phone ? "[phone]" : Matcher.quoteReplacement(run);
        });
    }

    private Redaction() {}
}
