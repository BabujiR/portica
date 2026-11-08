package com.portica.management.trace;

import com.portica.management.trace.TraceDtos.TraceSessionResponse;
import com.portica.management.trace.TraceDtos.TraceState;
import com.portica.management.trace.TraceDtos.TraceTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams a trace session's captured calls as Server-Sent Events. One virtual thread per open stream
 * polls ClickHouse every second for calls it hasn't sent yet.
 * <ul>
 *   <li>{@code session}: the session, first and whenever its state changes</li>
 *   <li>{@code transaction}: a captured call (id = request id)</li>
 *   <li>{@code heartbeat}: every 15 seconds, keeps proxies from closing an idle connection</li>
 *   <li>{@code ended}: the session expired or was stopped; the stream then closes</li>
 * </ul>
 * Calls written to ClickHouse after the session ends are still picked up for a few seconds.
 */
@Component
public class TraceStreamer {

    private static final Logger log = LoggerFactory.getLogger(TraceStreamer.class);
    static final Duration POLL = Duration.ofSeconds(1);
    static final Duration HEARTBEAT = Duration.ofSeconds(15);
    /** Captured calls reach ClickHouse a few seconds after they happen; keep reading this long after the end. */
    static final Duration DRAIN = Duration.ofSeconds(6);

    private final TraceSessionService sessions;

    public TraceStreamer(TraceSessionService sessions) {
        this.sessions = sessions;
    }

    public SseEmitter open(String org, UUID id) {
        TraceSessionResponse initial = sessions.get(org, id);   // 404 before the stream starts
        long timeoutMs = Math.max(0, sessions.remaining(initial).toMillis()) + DRAIN.toMillis() + 30_000;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(e -> open.set(false));
        Thread.ofVirtual().name("trace-stream-" + id).start(() -> pump(org, id, initial, emitter, open));
        return emitter;
    }

    private void pump(String org, UUID id, TraceSessionResponse initial, SseEmitter emitter, AtomicBoolean open) {
        Set<String> sent = new HashSet<>();
        TraceState lastState = initial.state();
        Instant endedAt = initial.state() == TraceState.ACTIVE ? null : Instant.now();
        Instant lastBeat = Instant.now();
        try {
            send(emitter, "session", null, initial);
            while (open.get()) {
                List<TraceTransaction> fresh = sessions.transactions(org, id, sent);
                for (TraceTransaction t : fresh) {
                    if (sent.add(t.requestId())) {
                        send(emitter, "transaction", t.requestId(), t);
                    }
                }
                if (endedAt == null) {
                    TraceSessionResponse current = sessions.get(org, id);
                    if (current.state() != lastState) {
                        lastState = current.state();
                        send(emitter, "session", null, current);
                    }
                    if (current.state() != TraceState.ACTIVE || sent.size() >= current.maxTransactions()) {
                        endedAt = Instant.now();
                    }
                } else if (Instant.now().isAfter(endedAt.plus(DRAIN))) {
                    send(emitter, "ended", null, Map.of("state", lastState, "transactions", sent.size()));
                    emitter.complete();
                    return;
                }
                if (Instant.now().isAfter(lastBeat.plus(HEARTBEAT))) {
                    send(emitter, "heartbeat", null, Map.of("at", Instant.now()));
                    lastBeat = Instant.now();
                }
                Thread.sleep(POLL);
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Trace stream {} closed: {}", id, e.toString());   // the client went away
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException e) {
            log.warn("Trace stream {} failed: {}", id, e.toString());
            try {
                send(emitter, "error", null, Map.of("message", "The trace stream failed; reload to retry."));
            } catch (IOException | IllegalStateException ignored) {
                // nothing more to tell the client
            }
            emitter.complete();
        }
    }

    private static void send(SseEmitter emitter, String event, String id, Object data) throws IOException {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON);
        if (id != null) {
            builder.id(id);
        }
        emitter.send(builder);
    }
}
