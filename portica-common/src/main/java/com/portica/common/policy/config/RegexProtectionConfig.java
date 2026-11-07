package com.portica.common.policy.config;

import java.util.List;
import java.util.Map;

/**
 * RegularExpressionProtection: blocks requests whose parts match any pattern (e.g. SQL injection).
 * Patterns are Java regular expressions searched anywhere in the value.
 *
 * @param path        patterns for the request path
 * @param queryParams parameter name ({@code *} for all) to patterns
 * @param headers     header name ({@code *} for all) to patterns
 * @param body        patterns for the request body (buffers the body)
 */
public record RegexProtectionConfig(List<String> path, Map<String, List<String>> queryParams,
                                    Map<String, List<String>> headers, List<String> body) implements PolicyConfig {

    public RegexProtectionConfig {
        path = path == null ? List.of() : List.copyOf(path);
        queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? List.of() : List.copyOf(body);
    }

    @Override
    public List<String> validate() {
        Problems p = new Problems()
                .require(!path.isEmpty() || !queryParams.isEmpty() || !headers.isEmpty() || !body.isEmpty(),
                        "configure at least one of 'path', 'queryParams', 'headers' or 'body'")
                .regexes(path, "path").regexes(body, "body");
        queryParams.values().forEach(v -> p.regexes(v, "queryParams"));
        headers.values().forEach(v -> p.regexes(v, "headers"));
        return p.list();
    }
}
