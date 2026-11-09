package com.portica.aigateway.pii;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds personal data in text and replaces it with typed placeholders ({@code [EMAIL]}, {@code [PHONE]} ...).
 * Pattern based, with checksums where formats have one (Luhn for cards, mod-97 for IBANs) to keep
 * false positives down. It catches well-formed identifiers, not names or free-form addresses; treat it as
 * a guardrail, not as a guarantee.
 */
public final class PiiScanner {

    public enum Type { EMAIL, PHONE, CREDIT_CARD, SSN, IP_ADDRESS, IBAN }

    /** What was found: counts per type. */
    public record Findings(Map<Type, Integer> counts) {

        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        public boolean any() {
            return total() > 0;
        }

        public List<String> types() {
            return counts.keySet().stream().map(Enum::name).toList();
        }
    }

    public record Result(String text, Findings findings) {
    }

    // Order matters: the most specific formats first, so a card number isn't half-eaten as a phone number.
    private static final Map<Type, Pattern> PATTERNS = new java.util.LinkedHashMap<>();

    static {
        PATTERNS.put(Type.IBAN, Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b"));
        PATTERNS.put(Type.CREDIT_CARD, Pattern.compile("(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])"));
        PATTERNS.put(Type.SSN, Pattern.compile("(?<!\\d)(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}(?!\\d)"));
        PATTERNS.put(Type.EMAIL, Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"));
        PATTERNS.put(Type.IP_ADDRESS, Pattern.compile(
                "(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?![\\d.])"));
        PATTERNS.put(Type.PHONE, Pattern.compile(
                // not inside a longer run of digit groups (order numbers, card-like numbers)
                "(?<![\\w+])(?<!\\d[ .-])(?:\\+\\d{1,3}[ .-]?)?(?:\\(\\d{2,4}\\)[ .-]?|\\d{2,4}[ .-])\\d{3,4}[ .-]\\d{3,4}"
                        + "(?![\\w])(?![ .-]?\\d)"));
    }

    private final Set<Type> enabled;

    /** {@code types} empty or null means every type. */
    public PiiScanner(Collection<String> types) {
        EnumSet<Type> set = EnumSet.noneOf(Type.class);
        if (types == null || types.isEmpty()) {
            set.addAll(EnumSet.allOf(Type.class));
        } else {
            for (String t : types) {
                try {
                    set.add(Type.valueOf(t));
                } catch (IllegalArgumentException ignored) {
                    // unknown type from a newer management plane
                }
            }
        }
        this.enabled = set;
    }

    public Result redact(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, new Findings(Map.of()));
        }
        Map<Type, Integer> counts = new EnumMap<>(Type.class);
        String current = text;
        for (Map.Entry<Type, Pattern> entry : PATTERNS.entrySet()) {
            Type type = entry.getKey();
            if (!enabled.contains(type)) {
                continue;
            }
            Matcher m = entry.getValue().matcher(current);
            StringBuilder out = new StringBuilder();
            int found = 0;
            while (m.find()) {
                if (!valid(type, m.group())) {
                    m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                    continue;
                }
                found++;
                m.appendReplacement(out, Matcher.quoteReplacement("[" + type.name() + "]"));
            }
            m.appendTail(out);
            if (found > 0) {
                counts.put(type, found);
                current = out.toString();
            }
        }
        return new Result(current, new Findings(counts));
    }

    static boolean valid(Type type, String value) {
        return switch (type) {
            case CREDIT_CARD -> luhn(value.replaceAll("[ -]", ""));
            case IBAN -> iban(value.replace(" ", ""));
            case PHONE -> value.replaceAll("\\D", "").length() >= 9;
            default -> true;
        };
    }

    static boolean luhn(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    static boolean iban(String iban) {
        if (iban.length() < 15 || iban.length() > 34) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int mod = 0;
        for (char c : rearranged.toCharArray()) {
            String chunk = Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10);
            for (char d : chunk.toCharArray()) {
                mod = (mod * 10 + (d - '0')) % 97;
            }
        }
        return mod == 1;
    }

    /** Merges findings of several texts. */
    public static Findings merge(List<Findings> all) {
        Map<Type, Integer> counts = new EnumMap<>(Type.class);
        for (Findings f : all) {
            f.counts().forEach((k, v) -> counts.merge(k, v, Integer::sum));
        }
        return new Findings(counts);
    }

    public static Findings none() {
        return new Findings(Map.of());
    }
}
