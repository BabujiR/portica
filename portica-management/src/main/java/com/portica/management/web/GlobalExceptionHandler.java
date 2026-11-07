package com.portica.management.web;

import com.portica.common.error.ErrorCode;
import com.portica.common.error.PorticaException;
import com.portica.management.config.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Maps every failure to an RFC 7807 problem document with a stable {@code code} and the
 * request's correlation id. Internal details (SQL, stack traces) are logged, never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PorticaException.class)
    ResponseEntity<ProblemDetail> handlePortica(PorticaException ex) {
        return respond(problem(ex.errorCode(), ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        // Covers AuthorizationDeniedException from @PreAuthorize. Never reveal which permission was missing.
        return respond(problem(ErrorCode.ACCESS_DENIED, "You do not have permission to perform this operation."));
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    ResponseEntity<ProblemDetail> handleUnauthenticated(AuthenticationCredentialsNotFoundException ex) {
        return respond(problem(ErrorCode.AUTHENTICATION_REQUIRED, "A valid bearer token is required."));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return respond(problem(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified by another request. Reload it and try again."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return respond(problem(ErrorCode.RESOURCE_CONFLICT,
                "The request conflicts with the current state of the resource."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(problem(ErrorCode.BAD_REQUEST, "Invalid value for parameter '%s'.".formatted(ex.getName())));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(problem(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Quote the requestId when contacting support."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** Framework-raised errors (bad JSON, unsupported media type, 404 routes ...) get the same envelope. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            enrich(problem, codeFor(statusCode.value()), false);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ErrorCode codeFor(int status) {
        return switch (status) {
            case 401 -> ErrorCode.AUTHENTICATION_REQUIRED;
            case 403 -> ErrorCode.ACCESS_DENIED;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 409 -> ErrorCode.RESOURCE_CONFLICT;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.BAD_REQUEST;
        };
    }

    private static Map<String, String> toFieldError(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
    }

    private static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(code.httpStatus()), detail);
        enrich(problem, code, true);
        return problem;
    }

    private static void enrich(ProblemDetail problem, ErrorCode code, boolean overrideType) {
        if (overrideType || problem.getType() == null || "about:blank".equals(problem.getType().toString())) {
            problem.setType(URI.create(code.typeUri()));
            problem.setTitle(code.title());
        }
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", code.name());
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
