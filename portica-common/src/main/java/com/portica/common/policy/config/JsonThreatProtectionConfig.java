package com.portica.common.policy.config;

import java.util.List;

/** JSONThreatProtection: rejects JSON request bodies exceeding structural limits. */
public record JsonThreatProtectionConfig(Integer maxDepth, Integer maxObjectEntries, Integer maxArrayElements,
                                         Integer maxStringLength, Integer maxNameLength, Integer maxBodyBytes)
        implements PolicyConfig {

    public JsonThreatProtectionConfig {
        maxDepth = maxDepth == null ? 20 : maxDepth;
        maxObjectEntries = maxObjectEntries == null ? 1_000 : maxObjectEntries;
        maxArrayElements = maxArrayElements == null ? 10_000 : maxArrayElements;
        maxStringLength = maxStringLength == null ? 100_000 : maxStringLength;
        maxNameLength = maxNameLength == null ? 256 : maxNameLength;
        maxBodyBytes = maxBodyBytes == null ? 1_048_576 : maxBodyBytes;
    }

    @Override
    public List<String> validate() {
        return new Problems()
                .range(maxDepth, 1, 400, "maxDepth")
                .range(maxObjectEntries, 1, 1_000_000, "maxObjectEntries")
                .range(maxArrayElements, 1, 10_000_000, "maxArrayElements")
                .range(maxStringLength, 1, 100_000_000, "maxStringLength")
                .range(maxNameLength, 1, 100_000, "maxNameLength")
                .range(maxBodyBytes, 1, 100 * 1_048_576, "maxBodyBytes")
                .list();
    }
}
