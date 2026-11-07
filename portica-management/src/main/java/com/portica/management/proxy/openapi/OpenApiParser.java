package com.portica.management.proxy.openapi;

import com.portica.common.error.InvalidRequestException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads OpenAPI 3.x and Swagger 2.0 documents in YAML or JSON (YAML is a superset of JSON).
 * <p>
 * This is deliberately a light reader, not a full validator: it checks the document is a
 * recognisable OpenAPI/Swagger spec and extracts metadata, servers and operations. Full schema
 * validation belongs to a dedicated linting step (planned with policy design in Phase 3).
 */
@Component
public class OpenApiParser {

    public static final int MAX_SPEC_BYTES = 2 * 1024 * 1024;

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private final YAMLMapper yaml = new YAMLMapper();

    public OpenApiDocument parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new InvalidRequestException("OpenAPI spec is empty");
        }
        if (spec.getBytes(StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
            throw new InvalidRequestException("OpenAPI spec is larger than 2 MB");
        }
        Map<String, Object> root = readRoot(spec);

        String openapi = string(root.get("openapi"));
        String swagger = string(root.get("swagger"));
        boolean v3 = openapi != null && openapi.startsWith("3.");
        boolean v2 = "2.0".equals(swagger);
        if (!v3 && !v2) {
            throw new InvalidRequestException("Not an OpenAPI 3.x or Swagger 2.0 document "
                    + "(expected an 'openapi: 3.x' or 'swagger: \"2.0\"' field)");
        }

        Map<String, Object> info = map(root.get("info"));
        String title = string(info.get("title"));
        if (title == null || title.isBlank()) {
            throw new InvalidRequestException("OpenAPI spec is missing info.title");
        }

        return new OpenApiDocument(
                v3 ? openapi : swagger,
                title.strip(),
                string(info.get("version")),
                string(info.get("description")),
                v3 ? serverUrlV3(root) : serverUrlV2(root),
                operations(map(root.get("paths"))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRoot(String spec) {
        Object parsed;
        try {
            parsed = yaml.readValue(spec, Object.class);
        } catch (JacksonException e) {
            throw new InvalidRequestException("OpenAPI spec is not valid YAML or JSON: " + firstLine(e.getOriginalMessage()));
        }
        if (!(parsed instanceof Map<?, ?>)) {
            throw new InvalidRequestException("OpenAPI spec must be a YAML/JSON object");
        }
        return (Map<String, Object>) parsed;
    }

    private static String serverUrlV3(Map<String, Object> root) {
        if (root.get("servers") instanceof List<?> servers) {
            for (Object server : servers) {
                String url = string(map(server).get("url"));
                if (isAbsoluteHttpUrl(url)) {
                    return stripTrailingSlash(url);
                }
            }
        }
        return null;
    }

    private static String serverUrlV2(Map<String, Object> root) {
        String host = string(root.get("host"));
        if (host == null || host.isBlank()) {
            return null;
        }
        String scheme = "https";
        if (root.get("schemes") instanceof List<?> schemes && !schemes.isEmpty()
                && !schemes.contains("https") && schemes.contains("http")) {
            scheme = "http";
        }
        String basePath = string(root.get("basePath"));
        String url = scheme + "://" + host + (basePath == null ? "" : basePath);
        return isAbsoluteHttpUrl(url) ? stripTrailingSlash(url) : null;
    }

    private static List<OpenApiDocument.Operation> operations(Map<String, Object> paths) {
        List<OpenApiDocument.Operation> result = new ArrayList<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            for (Map.Entry<String, Object> opEntry : map(pathEntry.getValue()).entrySet()) {
                String method = opEntry.getKey().toLowerCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(method)) {
                    continue; // "parameters", "summary", vendor extensions ...
                }
                Map<String, Object> op = map(opEntry.getValue());
                result.add(new OpenApiDocument.Operation(method.toUpperCase(Locale.ROOT), pathEntry.getKey(),
                        string(op.get("operationId")), string(op.get("summary"))));
            }
        }
        return result;
    }

    /** Servers with variables ({@code https://{region}.example.com}) or relative URLs can't be used as targets. */
    static boolean isAbsoluteHttpUrl(String url) {
        if (url == null || url.contains("{")) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "parse error";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
