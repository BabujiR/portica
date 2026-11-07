package com.portica.common.policy.config;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SpikeArrest: smooths traffic to {@code rate} per second ({@code 100ps}) or per minute ({@code 600pm}).
 * Enforced per gateway instance with a token bucket; {@code burst} is how many requests may arrive
 * back-to-back (default 1, i.e. strict smoothing like Apigee).
 */
public record SpikeArrestConfig(String rate, String identifier, Integer burst) implements PolicyConfig {

    private static final Pattern RATE = Pattern.compile("^(\\d{1,9})(ps|pm)$");

    public SpikeArrestConfig {
        identifier = identifier == null ? "" : identifier;
        burst = burst == null ? 1 : burst;
    }

    /** Requests per second, or -1 when {@code rate} is invalid. */
    public double ratePerSecond() {
        if (rate == null) {
            return -1;
        }
        Matcher m = RATE.matcher(rate.strip());
        if (!m.matches()) {
            return -1;
        }
        double n = Double.parseDouble(m.group(1));
        return m.group(2).equals("ps") ? n : n / 60.0;
    }

    @Override
    public List<String> validate() {
        return new Problems()
                .notBlank(rate, "rate")
                .require(rate == null || ratePerSecond() > 0, "'rate' must look like 100ps or 600pm")
                .range(burst, 1, 100_000, "burst")
                .list();
    }
}
