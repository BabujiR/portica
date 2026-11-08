package com.portica.management.mcp;

import com.portica.common.error.InvalidRequestException;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the operations of an OpenAPI 3.x / Swagger 2.0 document into MCP tools. Each operation becomes a
 * tool whose input schema has one property per path, query and header parameter plus {@code body} for a
 * JSON request body; local {@code $ref}s are inlined (cycles and very deep schemas are cut short). The
 * HTTP mapping travels with the tool so portica-mcp can turn a call back into a request.
 */
public final class OpenApiTools {

    /** MCP tool names: letters, digits, underscore and hyphen (dots are avoided for client compatibility). */
    static final int MAX_NAME = 64;
    private static final int MAX_DEPTH = 8;
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");
    private static final YAMLMapper YAML = new YAMLMapper();

    /** How a tool argument maps onto the HTTP request. {@code in}: path, query or header. */
    public record HttpParameter(String name, String in) {
    }

    /** The request a tool call becomes; {@code path} is relative to the proxy base path. */
    public record HttpMapping(String method, String path, List<HttpParameter> parameters, boolean hasBody,
                              String bodyContentType) {
    }

    public record Tool(String name, String title, String description, Map<String, Object> inputSchema,
                       boolean readOnly, HttpMapping http) {
    }

    private OpenApiTools() {
    }

    public static List<Tool> generate(String spec) {
        Map<String, Object> root = read(spec);
        Resolver resolver = new Resolver(root);
        List<Tool> tools = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, Object> pathEntry : map(root.get("paths")).entrySet()) {
            String path = pathEntry.getKey();
            Map<String, Object> pathItem = resolver.deref(map(pathEntry.getValue()));
            List<Object> shared = list(pathItem.get("parameters"));
            for (Map.Entry<String, Object> opEntry : pathItem.entrySet()) {
                String method = opEntry.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(method)) {
                    continue;
                }
                Map<String, Object> op = map(opEntry.getValue());
                tools.add(tool(resolver, path, method, op, shared, names));
            }
        }
        return tools;
    }

    private static Tool tool(Resolver resolver, String path, String method, Map<String, Object> op,
                             List<Object> sharedParameters, Set<String> usedNames) {
        String name = uniqueName(toolName(text(op.get("operationId")), method, path), usedNames);
        String summary = text(op.get("summary"));
        String description = text(op.get("description"));
        String text = summary != null && description != null && !description.equals(summary)
                ? summary + "\n\n" + description
                : summary != null ? summary : description != null ? description : method.toUpperCase(Locale.ROOT) + " " + path;

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<HttpParameter> parameters = new ArrayList<>();
        // operation parameters override path-level ones with the same name and location
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Object p : sharedParameters) {
            Map<String, Object> param = resolver.deref(map(p));
            byKey.put(param.get("in") + ":" + param.get("name"), param);
        }
        for (Object p : list(op.get("parameters"))) {
            Map<String, Object> param = resolver.deref(map(p));
            byKey.put(param.get("in") + ":" + param.get("name"), param);
        }
        boolean hasBody = false;
        String bodyType = null;
        for (Map<String, Object> param : byKey.values()) {
            String in = text(param.get("in"));
            String pname = text(param.get("name"));
            if (pname == null || in == null) {
                continue;
            }
            if (in.equals("body")) {                       // Swagger 2.0 body parameter
                properties.put("body", described(resolver.inline(map(param.get("schema"))), text(param.get("description"))));
                if (Boolean.TRUE.equals(param.get("required"))) {
                    required.add("body");
                }
                hasBody = true;
                bodyType = "application/json";
                continue;
            }
            if (!in.equals("path") && !in.equals("query") && !in.equals("header")) {
                continue;                                  // cookies and formData are not mapped
            }
            Map<String, Object> schema = param.containsKey("schema")
                    ? resolver.inline(map(param.get("schema")))
                    : swagger2Schema(param);
            properties.put(pname, described(schema, text(param.get("description"))));
            if (in.equals("path") || Boolean.TRUE.equals(param.get("required"))) {
                required.add(pname);
            }
            parameters.add(new HttpParameter(pname, in));
        }
        Map<String, Object> requestBody = resolver.deref(map(op.get("requestBody")));
        if (!requestBody.isEmpty()) {
            Map<String, Object> content = map(requestBody.get("content"));
            String type = content.keySet().stream().filter(t -> t.toLowerCase(Locale.ROOT).contains("json")).findFirst()
                    .orElse(content.isEmpty() ? null : content.keySet().iterator().next());
            if (type != null) {
                Map<String, Object> schema = resolver.inline(map(map(content.get(type)).get("schema")));
                properties.put("body", described(schema.isEmpty() ? Map.of("type", "object") : schema,
                        text(requestBody.get("description"))));
                if (Boolean.TRUE.equals(requestBody.get("required"))) {
                    required.add("body");
                }
                hasBody = true;
                bodyType = type;
            }
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("properties", properties);
        if (!required.isEmpty()) {
            input.put("required", required.stream().distinct().toList());
        }
        String title = summary != null ? summary : name;
        return new Tool(name, truncate(title, 200), truncate(text, 2000), input,
                method.equals("get") || method.equals("head"),
                new HttpMapping(method.toUpperCase(Locale.ROOT), path, List.copyOf(parameters), hasBody, bodyType));
    }

    private static Map<String, Object> swagger2Schema(Map<String, Object> param) {
        Map<String, Object> schema = new LinkedHashMap<>();
        for (String key : List.of("type", "format", "enum", "items", "default", "minimum", "maximum")) {
            if (param.containsKey(key)) {
                schema.put(key, param.get(key));
            }
        }
        if (!schema.containsKey("type")) {
            schema.put("type", "string");
        }
        return schema;
    }

    private static Map<String, Object> described(Map<String, Object> schema, String description) {
        if (description == null || schema.containsKey("description")) {
            return schema;
        }
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.put("description", truncate(description, 1000));
        return copy;
    }

    /** operationId when usable, else {@code get_pets_petId} from method and path. */
    static String toolName(String operationId, String method, String path) {
        String base = operationId != null && !operationId.isBlank()
                ? operationId
                : method + "_" + path.replaceAll("[{}]", "").replaceAll("[^A-Za-z0-9]+", "_");
        String clean = base.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (clean.isEmpty()) {
            clean = method;
        }
        return clean.length() > MAX_NAME ? clean.substring(0, MAX_NAME) : clean;
    }

    private static String uniqueName(String name, Set<String> used) {
        String candidate = name;
        for (int i = 2; !used.add(candidate); i++) {
            String suffix = "_" + i;
            candidate = (name.length() + suffix.length() > MAX_NAME ? name.substring(0, MAX_NAME - suffix.length()) : name) + suffix;
        }
        return candidate;
    }

    // ------------------------------------------------------------------ $ref handling

    /** Inlines local references ({@code #/...}); remote references are left as an open object. */
    static final class Resolver {
        private final Map<String, Object> root;

        Resolver(Map<String, Object> root) {
            this.root = root;
        }

        /** Follows a top-level $ref (parameters, request bodies, path items). */
        Map<String, Object> deref(Map<String, Object> node) {
            Map<String, Object> current = node;
            for (int i = 0; i < MAX_DEPTH && current.get("$ref") instanceof String ref; i++) {
                current = lookup(ref);
            }
            return current;
        }

        Map<String, Object> inline(Map<String, Object> schema) {
            return inline(schema, new ArrayDeque<>(), 0);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> inline(Map<String, Object> schema, Deque<String> stack, int depth) {
            if (schema.get("$ref") instanceof String ref) {
                if (stack.contains(ref) || depth >= MAX_DEPTH) {
                    return Map.of("type", "object", "description", "Recursive or deeply nested: " + ref);
                }
                stack.push(ref);
                Map<String, Object> resolved = inline(lookup(ref), stack, depth + 1);
                stack.pop();
                return resolved;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : schema.entrySet()) {
                Object v = e.getValue();
                if (e.getKey().equals("properties") && v instanceof Map<?, ?> props) {
                    Map<String, Object> inlined = new LinkedHashMap<>();
                    ((Map<String, Object>) props).forEach((k, s) -> inlined.put(k, inline(map(s), stack, depth + 1)));
                    out.put("properties", inlined);
                } else if (v instanceof Map<?, ?> m && Set.of("items", "additionalProperties", "not").contains(e.getKey())) {
                    out.put(e.getKey(), inline((Map<String, Object>) m, stack, depth + 1));
                } else if (v instanceof List<?> l && Set.of("allOf", "anyOf", "oneOf").contains(e.getKey())) {
                    out.put(e.getKey(), l.stream().map(x -> (Object) inline(map(x), stack, depth + 1)).toList());
                } else if (!e.getKey().startsWith("x-") && !Set.of("xml", "externalDocs", "example", "discriminator",
                        "readOnly", "writeOnly", "deprecated", "nullable").contains(e.getKey())) {
                    out.put(e.getKey(), v);
                }
            }
            if (Boolean.TRUE.equals(schema.get("nullable")) && out.get("type") instanceof String t) {
                out.put("type", List.of(t, "null"));    // OpenAPI 3.0 nullable -> JSON Schema
            }
            return out;
        }

        private Map<String, Object> lookup(String ref) {
            if (!ref.startsWith("#/")) {
                return Map.of("type", "object", "description", "External reference: " + ref);
            }
            Object node = root;
            for (String part : ref.substring(2).split("/")) {
                String key = part.replace("~1", "/").replace("~0", "~");
                node = node instanceof Map<?, ?> m ? m.get(key) : null;
            }
            return map(node);
        }
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new InvalidRequestException("The API proxy revision has no OpenAPI spec");
        }
        try {
            Object parsed = YAML.readValue(spec, Object.class);
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (JacksonException e) {
            // fall through
        }
        throw new InvalidRequestException("The API proxy's OpenAPI spec can't be read");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString().strip();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
