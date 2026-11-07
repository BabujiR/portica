package com.portica.common.policy.config;

import com.portica.common.policy.template.MessageTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * AssignMessage: changes the request or response, and sets flow variables. All values are templates.
 *
 * @param target            {@code request} (default) or {@code response}
 * @param setHeaders        headers to set (replacing existing values)
 * @param addHeaders        headers to add (keeping existing values)
 * @param removeHeaders     header names to remove
 * @param setQueryParams    request only
 * @param removeQueryParams request only
 * @param setVerb           request only, e.g. {@code POST}
 * @param setPath           request only: new path suffix sent to the target, e.g. {@code /v2/items}
 * @param setBody           new body
 * @param setContentType    content type of {@code setBody}
 * @param setStatusCode     response only
 * @param setVariables      flow variables to set
 */
public record AssignMessageConfig(String target, Map<String, String> setHeaders, Map<String, String> addHeaders,
                                  List<String> removeHeaders, Map<String, String> setQueryParams,
                                  List<String> removeQueryParams, String setVerb, String setPath, String setBody,
                                  String setContentType, Integer setStatusCode, Map<String, String> setVariables)
        implements PolicyConfig {

    private static final Set<String> TARGETS = Set.of("request", "response");
    private static final Set<String> VERBS = Set.of("get", "head", "post", "put", "patch", "delete", "options");

    public AssignMessageConfig {
        target = target == null ? "request" : target.toLowerCase(java.util.Locale.ROOT);
        setHeaders = setHeaders == null ? Map.of() : Map.copyOf(setHeaders);
        addHeaders = addHeaders == null ? Map.of() : Map.copyOf(addHeaders);
        removeHeaders = removeHeaders == null ? List.of() : List.copyOf(removeHeaders);
        setQueryParams = setQueryParams == null ? Map.of() : Map.copyOf(setQueryParams);
        removeQueryParams = removeQueryParams == null ? List.of() : List.copyOf(removeQueryParams);
        setVariables = setVariables == null ? Map.of() : Map.copyOf(setVariables);
    }

    public boolean targetsResponse() {
        return target.equals("response");
    }

    /** Every template in this config, for static analysis (e.g. body buffering). */
    public List<MessageTemplate> templates() {
        List<String> all = new ArrayList<>();
        all.addAll(setHeaders.values());
        all.addAll(addHeaders.values());
        all.addAll(setQueryParams.values());
        all.addAll(setVariables.values());
        Stream.of(setVerb, setPath, setBody, setContentType).filter(java.util.Objects::nonNull).forEach(all::add);
        return all.stream().map(MessageTemplate::of).toList();
    }

    @Override
    public List<String> validate() {
        boolean response = targetsResponse();
        return new Problems()
                .oneOf(target, TARGETS, "target")
                .headerNames(setHeaders.keySet(), "setHeaders")
                .headerNames(addHeaders.keySet(), "addHeaders")
                .headerNames(removeHeaders, "removeHeaders")
                .require(!response || (setQueryParams.isEmpty() && removeQueryParams.isEmpty()
                        && setVerb == null && setPath == null),
                        "setQueryParams, removeQueryParams, setVerb and setPath only apply to target 'request'")
                .require(response || setStatusCode == null, "'setStatusCode' only applies to target 'response'")
                .range(setStatusCode, 100, 599, "setStatusCode")
                .require(setVerb == null || setVerb.contains("{") || VERBS.contains(setVerb.toLowerCase(java.util.Locale.ROOT)),
                        "'setVerb' must be an HTTP method")
                .require(setPath == null || setPath.startsWith("/") || setPath.startsWith("{"), "'setPath' must start with /")
                .list();
    }
}
