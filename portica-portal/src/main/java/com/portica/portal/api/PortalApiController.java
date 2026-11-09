package com.portica.portal.api;

import com.portica.common.runtime.ManagementApiClient;
import com.portica.common.runtime.ManagementApiClient.ManagementApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The portal's REST API for browsers: {@code /api/portal/organizations/{org}/...} maps onto the management
 * plane's {@code /internal/v1/portal/organizations/{org}/...}. Catalog and docs are public; {@code /me/**}
 * carries the developer's e-mail address taken from their token. Responses, including RFC 7807 problems,
 * are passed through.
 */
@RestController
public class PortalApiController {

    static final String PREFIX = "/api/portal/organizations/";
    private static final Pattern ORG = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    /** What may be forwarded: the catalog, docs and tools, and the developer's own resources. */
    private static final Pattern ALLOWED = Pattern.compile(
            "catalog|apis/[a-z0-9-]+/environments/[a-z0-9-]+/docs|mcp/[a-z0-9-]+/environments/[a-z0-9-]+/tools"
                    + "|me(/apps(/[a-z0-9-]+(/keys(/[0-9a-f-]{36}(/certificates(/[0-9a-f]{64})?)?)?)?)?|/usage)?");

    private final ManagementApiClient management;
    private final DeveloperIdentity identity;

    public PortalApiController(ManagementApiClient management, DeveloperIdentity identity) {
        this.management = management;
        this.identity = identity;
    }

    @RequestMapping(value = PREFIX + "{org}/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE})
    public ResponseEntity<String> forward(@PathVariable String org, HttpServletRequest request,
                                          @RequestBody(required = false) String body, Authentication authentication) {
        if (!ORG.matcher(org).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String rest = request.getRequestURI().substring(request.getContextPath().length() + PREFIX.length() + org.length());
        rest = rest.startsWith("/") ? rest.substring(1) : rest;
        if (!ALLOWED.matcher(rest).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        boolean personal = rest.equals("me") || rest.startsWith("me/");
        Map<String, String> headers = personal ? Map.of("X-Portica-Developer", identity.email(authentication)) : Map.of();
        String path = "/internal/v1/portal/organizations/" + org + "/" + rest + query(request);
        ManagementApiClient.Response response;
        try {
            response = management.exchange(request.getMethod(), path, body == null || body.isBlank() ? null : body, headers);
        } catch (ManagementApiException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The developer portal is temporarily unavailable");
        }
        if (response.status() == 401 || response.status() == 403 && !personal) {
            // the portal's own credential was refused: an operator problem, not the developer's
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The developer portal is not set up correctly");
        }
        ResponseEntity.BodyBuilder out = ResponseEntity.status(response.status())
                .contentType(MediaType.parseMediaType(response.contentType()));
        out.cacheControl(personal ? CacheControl.noStore() : CacheControl.maxAge(java.time.Duration.ofSeconds(30)).cachePublic());
        return response.status() == 204 ? out.build() : out.body(response.body());
    }

    /** Only the usage window parameters travel; anything else is dropped. */
    private static String query(HttpServletRequest request) {
        StringBuilder q = new StringBuilder();
        for (String name : new String[]{"from", "to", "environment"}) {
            String value = request.getParameter(name);
            if (value != null && !value.isBlank()) {
                q.append(q.isEmpty() ? '?' : '&').append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        return q.toString();
    }
}
