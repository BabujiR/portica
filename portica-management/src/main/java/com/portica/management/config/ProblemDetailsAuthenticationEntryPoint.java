package com.portica.management.config;

import com.portica.common.error.ErrorCode;
import com.portica.common.http.PorticaHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Returns 401 responses as RFC 7807 problem documents while keeping the standard
 * {@code WWW-Authenticate: Bearer ...} header produced by Spring Security.
 * <p>
 * The body is static on purpose: token validation errors are not echoed back to the
 * caller (they are available in the {@code WWW-Authenticate} header and server logs).
 */
public class ProblemDetailsAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        delegate.commence(request, response, authException);
        ErrorCode code = ErrorCode.AUTHENTICATION_REQUIRED;
        String requestId = response.getHeader(PorticaHeaders.REQUEST_ID);
        String body = """
                {"type":"%s","title":"%s","status":%d,"detail":"A valid bearer token is required.","code":"%s"%s}"""
                .formatted(code.typeUri(), code.title(), code.httpStatus(), code.name(),
                        requestId == null ? "" : ",\"requestId\":\"" + requestId + "\"");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
