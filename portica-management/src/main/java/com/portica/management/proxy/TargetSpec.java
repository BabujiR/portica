package com.portica.management.proxy;

import com.portica.common.error.InvalidRequestException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where a revision sends traffic: either a direct {@code url} (e.g. {@code https://api.example.com/v2})
 * or a named target server plus an optional path prefix. Exactly one of {@code url} and {@code server}
 * is set; {@code path} is never null (empty means "/").
 */
public record TargetSpec(String url, String server, String path) {

    private static final Pattern SERVER_NAME = Pattern.compile(com.portica.common.validation.ResourceName.PATTERN);
    private static final Pattern TARGET_PATH = Pattern.compile("^(/[A-Za-z0-9._~!$&'()*+,;=:@%-]+)*$");

    public TargetSpec {
        path = path == null ? "" : path;
    }

    /**
     * Validates and normalizes user input. For URL targets the URL's path becomes {@code path}
     * and the URL keeps only scheme, host and port, so both styles share one runtime model.
     */
    public static TargetSpec of(String url, String server, String path) {
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasServer = server != null && !server.isBlank();
        if (hasUrl == hasServer) {
            throw new InvalidRequestException("Target must specify exactly one of 'url' or 'server'");
        }
        if (hasServer) {
            String name = server.strip();
            if (!SERVER_NAME.matcher(name).matches()) {
                throw new InvalidRequestException("Target server name '%s' is invalid".formatted(name));
            }
            return new TargetSpec(null, name, normalizePath(path));
        }
        if (path != null && !path.isBlank()) {
            throw new InvalidRequestException("Target 'path' is only used with 'server'; put the path in 'url'");
        }
        URI uri = parseUrl(url.strip());
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
        return new TargetSpec(origin, null, normalizePath(uri.getRawPath()));
    }

    private static URI parseUrl(String raw) {
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("Target url is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidRequestException("Target url must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new InvalidRequestException("Target url must have a host and no credentials");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new InvalidRequestException("Target url must not contain a query string or fragment");
        }
        return uri;
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "";
        }
        String p = path.strip();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.length() > 1024 || !TARGET_PATH.matcher(p).matches()) {
            throw new InvalidRequestException("Target path '%s' is invalid".formatted(path));
        }
        return p;
    }

    public boolean usesServer() {
        return server != null;
    }

    /** Human-readable form, e.g. {@code https://api.example.com/v2} or {@code server:orders-backend/v2}. */
    public String describe() {
        return usesServer() ? "server:" + server + path : url + path;
    }
}
