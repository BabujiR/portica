package com.portica.common.policy.config;

import java.util.List;
import java.util.Set;

/**
 * JSONToXML: converts a JSON body to XML. Objects become elements, array items become repeated
 * elements with the field name ({@code arrayItemElement} for top-level or nested arrays).
 */
public record JsonToXmlConfig(String target, String rootElement, String arrayItemElement) implements PolicyConfig {

    public JsonToXmlConfig {
        target = target == null ? "response" : target.toLowerCase(java.util.Locale.ROOT);
        rootElement = rootElement == null ? "root" : rootElement;
        arrayItemElement = arrayItemElement == null ? "item" : arrayItemElement;
    }

    @Override
    public List<String> validate() {
        return new Problems().oneOf(target, Set.of("request", "response"), "target")
                .xmlName(rootElement, "rootElement").xmlName(arrayItemElement, "arrayItemElement").list();
    }
}
