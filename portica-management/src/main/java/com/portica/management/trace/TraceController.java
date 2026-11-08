package com.portica.management.trace;

import com.portica.management.trace.TraceDtos.StartTraceRequest;
import com.portica.management.trace.TraceDtos.TraceSessionResponse;
import com.portica.management.trace.TraceDtos.TraceTransaction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * Debug trace sessions: capture live calls of a deployed proxy with every policy step, the target call
 * and the response. Captured data is kept for a day. Credentials are masked by the gateway.
 */
@RestController
@Tag(name = "Trace", description = "Live debug sessions for deployed API proxies")
public class TraceController {

    private final TraceSessionService sessions;
    private final TraceStreamer streamer;

    public TraceController(TraceSessionService sessions, TraceStreamer streamer) {
        this.sessions = sessions;
        this.streamer = streamer;
    }

    @PostMapping("/api/v1/organizations/{org}/environments/{env}/apis/{api}/trace-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.hasPermission(#org, 'TRACE_RUN')")
    @Operation(summary = "Start a trace session",
            description = "Gateways pick the session up within a few seconds. Runs for durationSeconds or until "
                    + "maxTransactions calls are captured.")
    public TraceSessionResponse start(@PathVariable String org, @PathVariable String env, @PathVariable String api,
                                      @Valid @RequestBody(required = false) StartTraceRequest request) {
        return sessions.start(org, env, api, request);
    }

    @GetMapping("/api/v1/organizations/{org}/environments/{env}/apis/{api}/trace-sessions")
    @PreAuthorize("@authz.hasPermission(#org, 'TRACE_RUN')")
    @Operation(summary = "Trace sessions of the last 24 hours, newest first")
    public List<TraceSessionResponse> list(@PathVariable String org, @PathVariable String env, @PathVariable String api) {
        return sessions.list(org, env, api);
    }

    @GetMapping("/api/v1/organizations/{org}/trace-sessions/{id}")
    @PreAuthorize("@authz.hasPermission(#org, 'TRACE_RUN')")
    @Operation(summary = "Get a trace session")
    public TraceSessionResponse get(@PathVariable String org, @PathVariable UUID id) {
        return sessions.get(org, id);
    }

    @DeleteMapping("/api/v1/organizations/{org}/trace-sessions/{id}")
    @PreAuthorize("@authz.hasPermission(#org, 'TRACE_RUN')")
    @Operation(summary = "Stop a trace session", description = "Captured calls stay readable.")
    public TraceSessionResponse stop(@PathVariable String org, @PathVariable UUID id) {
        return sessions.stop(org, id);
    }

    @GetMapping("/api/v1/organizations/{org}/trace-sessions/{id}/transactions")
    @PreAuthorize("@authz.hasPermission(#org, 'TRACE_RUN')")
    @Operation(summary = "Captured calls of a session, oldest first")
    public List<TraceTransaction> transactions(@PathVariable String org, @PathVariable UUID id) {
        return sessions.transactions(org, id, List.of());
    }

    @GetMapping(path = "/api/v1/organizations/{org}/trace-sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authz.hasPermission(#org, 'TRACE_RUN')")
    @Operation(summary = "Live stream of captured calls (Server-Sent Events)",
            description = "Events: session, transaction, heartbeat, ended. Send the bearer token in the "
                    + "Authorization header (read the stream with fetch, not EventSource).")
    public SseEmitter stream(@PathVariable String org, @PathVariable UUID id) {
        return streamer.open(org, id);
    }
}
