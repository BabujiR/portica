package com.portica.common.policy.config;

import java.util.List;
import java.util.Set;

/**
 * XMLToJSON: converts an XML body to JSON. Attributes become {@code "@name"} fields, mixed text
 * becomes {@code "#text"}, repeated elements become arrays. DTDs and external entities are rejected.
 */
public record XmlToJsonConfig(String target, Boolean recognizeNumbers, Boolean recognizeBooleans)
        implements PolicyConfig {

    public XmlToJsonConfig {
        target = target == null ? "response" : target.toLowerCase(java.util.Locale.ROOT);
        recognizeNumbers = recognizeNumbers != null && recognizeNumbers;
        recognizeBooleans = recognizeBooleans != null && recognizeBooleans;
    }

    @Override
    public List<String> validate() {
        return new Problems().oneOf(target, Set.of("request", "response"), "target").list();
    }
}
