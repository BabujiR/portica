package com.portica.common.policy.config;

import com.portica.common.net.CidrRange;
import com.portica.common.policy.condition.ConditionParser;
import com.portica.common.policy.condition.ConditionSyntaxException;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Small helper to collect validation problems fluently. */
public final class Problems {

    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Pattern XML_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9._-]*$");

    private final List<String> problems = new ArrayList<>();

    public Problems require(boolean ok, String message) {
        if (!ok) {
            problems.add(message);
        }
        return this;
    }

    public Problems notBlank(String value, String field) {
        return require(value != null && !value.isBlank(), "'" + field + "' is required");
    }

    public Problems range(Number value, long min, long max, String field) {
        if (value != null && (value.longValue() < min || value.longValue() > max)) {
            problems.add("'%s' must be between %d and %d".formatted(field, min, max));
        }
        return this;
    }

    public Problems oneOf(String value, Set<String> allowed, String field) {
        if (value != null && !allowed.contains(value.toLowerCase(Locale.ROOT))) {
            problems.add("'%s' must be one of %s".formatted(field, allowed.stream().sorted().toList()));
        }
        return this;
    }

    public Problems headerNames(Collection<String> names, String field) {
        if (names != null) {
            for (String name : names) {
                if (name == null || !HEADER_NAME.matcher(name).matches()) {
                    problems.add("'%s' contains an invalid header name '%s'".formatted(field, name));
                }
            }
        }
        return this;
    }

    public Problems regexes(Collection<String> patterns, String field) {
        if (patterns != null) {
            for (String p : patterns) {
                try {
                    Pattern.compile(p == null ? "" : p);
                } catch (PatternSyntaxException e) {
                    problems.add("'%s' has an invalid regular expression '%s': %s".formatted(field, p, e.getDescription()));
                }
            }
        }
        return this;
    }

    public Problems condition(String condition, String field) {
        if (condition != null) {
            try {
                ConditionParser.parse(condition);
            } catch (ConditionSyntaxException e) {
                problems.add("'%s': %s".formatted(field, e.getMessage()));
            }
        }
        return this;
    }

    public Problems httpUrl(String url, String field) {
        if (url == null || url.isBlank()) {
            return this;
        }
        if (url.contains("{")) {
            return this; // templated; checked at runtime
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            require((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null,
                    "'" + field + "' must be an absolute http(s) URL");
        } catch (Exception e) {
            problems.add("'" + field + "' is not a valid URL");
        }
        return this;
    }

    public Problems cidrs(Collection<String> ranges, String field) {
        if (ranges != null) {
            for (String r : ranges) {
                try {
                    CidrRange.parse(r);
                } catch (IllegalArgumentException e) {
                    problems.add("'%s': %s".formatted(field, e.getMessage()));
                }
            }
        }
        return this;
    }

    public Problems xmlName(String name, String field) {
        if (name != null && !XML_NAME.matcher(name).matches()) {
            problems.add("'" + field + "' must be a valid XML element name");
        }
        return this;
    }

    public List<String> list() {
        return List.copyOf(problems);
    }
}
