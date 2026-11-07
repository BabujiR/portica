package com.portica.common.policy.condition;

import java.util.regex.Pattern;

/**
 * Path-style wildcards: {@code *} matches within one path segment, {@code **} matches across
 * segments, everything else is literal. Example: {@code /pets/*} matches {@code /pets/42};
 * {@code /pets/**} also matches {@code /pets/42/photos}.
 */
public final class WildcardPattern {

    private WildcardPattern() {
    }

    public static Pattern compile(String wildcard) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < wildcard.length(); i++) {
            char c = wildcard.charAt(i);
            if (c == '*') {
                flush(regex, literal);
                if (i + 1 < wildcard.length() && wildcard.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else {
                literal.append(c);
            }
        }
        flush(regex, literal);
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private static void flush(StringBuilder regex, StringBuilder literal) {
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }
}
