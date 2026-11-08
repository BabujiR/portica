package com.portica.management.trace;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.analytics.AnalyticsStore;
import com.portica.management.audit.AuditService;
import com.portica.management.deployment.DeploymentRepository;
import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentFinder;
import com.portica.management.identity.CurrentUserService;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.proxy.ApiProxy;
import com.portica.management.proxy.ApiProxyRepository;
import com.portica.management.trace.TraceDtos.RuntimeTraceSession;
import com.portica.management.trace.TraceDtos.StartTraceRequest;
import com.portica.management.trace.TraceDtos.TraceSessionResponse;
import com.portica.management.trace.TraceDtos.TraceState;
import com.portica.management.trace.TraceDtos.TraceTransaction;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Debug trace sessions. Sessions live in Postgres; gateways poll the active ones and publish captured
 * calls to Kafka, and portica-analytics stores them in ClickHouse, where they are read back here.
 */
@Service
public class TraceSessionService {

    static final String RESOURCE_TYPE = "TraceSession";
    static final int DEFAULT_DURATION_SECONDS = 300;
    static final int DEFAULT_MAX_TRANSACTIONS = 20;
    /** Concurrent sessions per proxy and environment. */
    static final int MAX_ACTIVE_PER_PROXY = 3;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final OrganizationFinder organizations;
    private final EnvironmentFinder environments;
    private final ApiProxyRepository proxies;
    private final DeploymentRepository deployments;
    private final CurrentUserService currentUsers;
    private final AuditService audit;
    private final AnalyticsStore store;
    private final JsonMapper json;
    private final Clock clock;

    public TraceSessionService(JdbcClient jdbc, OrganizationFinder organizations, EnvironmentFinder environments,
                               ApiProxyRepository proxies, DeploymentRepository deployments,
                               CurrentUserService currentUsers, AuditService audit, AnalyticsStore store,
                               JsonMapper json) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.environments = environments;
        this.proxies = proxies;
        this.deployments = deployments;
        this.currentUsers = currentUsers;
        this.audit = audit;
        this.store = store;
        this.json = json;
        this.clock = Clock.systemUTC();
    }

    @Transactional
    public TraceSessionResponse start(String org, String env, String api, StartTraceRequest request) {
        Organization organization = organizations.getActive(org);
        Environment environment = environments.get(organization, env);
        ApiProxy proxy = proxies.findByOrganization_IdAndName(organization.getId(), api)
                .orElseThrow(() -> new ResourceNotFoundException("ApiProxy", api));
        if (deployments.findByEnvironment_IdAndApiProxy_Id(environment.getId(), proxy.getId()).isEmpty()) {
            throw new BusinessRuleViolationException("API proxy '" + api + "' is not deployed to environment '"
                    + env + "'; deploy it before tracing.");
        }
        Instant now = now();
        Integer active = jdbc.sql("""
                        SELECT count(*) FROM trace_sessions
                        WHERE environment_id = :env AND api_proxy_id = :proxy AND stopped_at IS NULL AND expires_at > :now
                        """)
                .param("env", environment.getId()).param("proxy", proxy.getId()).param("now", Timestamp.from(now))
                .query(Integer.class).single();
        if (active >= MAX_ACTIVE_PER_PROXY) {
            throw new BusinessRuleViolationException("At most " + MAX_ACTIVE_PER_PROXY
                    + " trace sessions can run at once for a proxy in an environment; stop one first.");
        }
        StartTraceRequest r = request == null ? new StartTraceRequest(null, null, null, null, null) : request;
        String header = blankToNull(r.filterHeader());
        String value = header == null ? null : blankToNull(r.filterValue());
        String clientIp = blankToNull(r.filterClientIp());
        int duration = r.durationSeconds() == null ? DEFAULT_DURATION_SECONDS : r.durationSeconds();
        int max = r.maxTransactions() == null ? DEFAULT_MAX_TRANSACTIONS : r.maxTransactions();
        UUID id = UUID.randomUUID();
        String actor = currentUsers.currentUser().actor();
        jdbc.sql("""
                        INSERT INTO trace_sessions (id, organization_id, environment_id, api_proxy_id, organization_name,
                            environment_name, api_proxy_name, filter_header, filter_value, filter_client_ip,
                            max_transactions, created_by, created_at, expires_at)
                        VALUES (:id, :org, :env, :proxy, :orgName, :envName, :proxyName, :header, :value, :ip,
                            :max, :createdBy, :createdAt, :expiresAt)
                        """)
                .param("id", id).param("org", organization.getId()).param("env", environment.getId())
                .param("proxy", proxy.getId()).param("orgName", organization.getName())
                .param("envName", environment.getName()).param("proxyName", proxy.getName())
                .param("header", header).param("value", value).param("ip", clientIp).param("max", max)
                .param("createdBy", actor).param("createdAt", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(now.plusSeconds(duration)))
                .update();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("environment", env);
        details.put("durationSeconds", duration);
        details.put("maxTransactions", max);
        if (header != null) details.put("filterHeader", header);
        if (clientIp != null) details.put("filterClientIp", clientIp);
        audit.record(organization.getId(), "trace.started", RESOURCE_TYPE, api, details);
        return find(organization.getId(), id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<TraceSessionResponse> list(String org, String env, String api) {
        Organization organization = organizations.getAny(org);
        Environment environment = environments.get(organization, env);
        ApiProxy proxy = proxies.findByOrganization_IdAndName(organization.getId(), api)
                .orElseThrow(() -> new ResourceNotFoundException("ApiProxy", api));
        return jdbc.sql("""
                        SELECT * FROM trace_sessions
                        WHERE environment_id = :env AND api_proxy_id = :proxy AND created_at > :since
                        ORDER BY created_at DESC
                        LIMIT 50
                        """)
                .param("env", environment.getId()).param("proxy", proxy.getId())
                .param("since", Timestamp.from(now().minus(1, ChronoUnit.DAYS)))
                .query(responses()).list();
    }

    @Transactional(readOnly = true)
    public TraceSessionResponse get(String org, UUID id) {
        return find(organizations.getAny(org).getId(), id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, id.toString()));
    }

    @Transactional
    public TraceSessionResponse stop(String org, UUID id) {
        Organization organization = organizations.getAny(org);
        TraceSessionResponse session = find(organization.getId(), id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, id.toString()));
        if (session.state() != TraceState.ACTIVE) {
            return session;
        }
        jdbc.sql("UPDATE trace_sessions SET stopped_at = :now WHERE id = :id AND stopped_at IS NULL")
                .param("now", Timestamp.from(now())).param("id", id).update();
        audit.record(organization.getId(), "trace.stopped", RESOURCE_TYPE, session.apiProxy(),
                Map.of("environment", session.environment(), "sessionId", id.toString()));
        return find(organization.getId(), id).orElseThrow();
    }

    /** Captured calls of a session, oldest first; with {@code exclude}, only those not already seen. */
    public List<TraceTransaction> transactions(String org, UUID id, Collection<String> exclude) {
        TraceSessionResponse session = get(org, id);
        String excluded = exclude == null || exclude.isEmpty() ? "" : exclude.stream()
                .map(TraceSessionService::quote).collect(Collectors.joining(",", "[", "]"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("session", session.id().toString());
        params.put("org", org);
        params.put("excluded", excluded.isEmpty() ? "[]" : excluded);
        return store.query("""
                        SELECT request_id, toUnixTimestamp64Milli(event_time) AS t, status, total_ms, payload
                        FROM trace_transactions
                        WHERE session_id = {session:String} AND organization = {org:String}
                          AND NOT has({excluded:Array(String)}, request_id)
                        ORDER BY event_time
                        LIMIT 100
                        """, params).stream()
                .map(r -> new TraceTransaction(String.valueOf(r.get("request_id")),
                        Instant.ofEpochMilli(((Number) r.get("t")).longValue()), ((Number) r.get("status")).intValue(),
                        ((Number) r.get("total_ms")).longValue(), payload(r.get("payload"))))
                .toList();
    }

    /** The sessions gateways should apply right now. */
    @Transactional(readOnly = true)
    public List<RuntimeTraceSession> active() {
        return jdbc.sql("""
                        SELECT id, organization_name, environment_name, api_proxy_name, filter_header, filter_value,
                               filter_client_ip, max_transactions, expires_at
                        FROM trace_sessions
                        WHERE stopped_at IS NULL AND expires_at > :now
                        """)
                .param("now", Timestamp.from(now()))
                .query((rs, n) -> new RuntimeTraceSession(rs.getObject("id", UUID.class), rs.getString("organization_name"),
                        rs.getString("environment_name"), rs.getString("api_proxy_name"), rs.getString("filter_header"),
                        rs.getString("filter_value"), rs.getString("filter_client_ip"), rs.getInt("max_transactions"),
                        rs.getTimestamp("expires_at").toInstant()))
                .list();
    }

    // ------------------------------------------------------------------ helpers

    private Optional<TraceSessionResponse> find(UUID organizationId, UUID id) {
        return jdbc.sql("SELECT * FROM trace_sessions WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId)
                .query(responses()).optional();
    }

    private RowMapper<TraceSessionResponse> responses() {
        Instant now = now();
        return (rs, n) -> {
            Timestamp stopped = rs.getTimestamp("stopped_at");
            Instant expires = rs.getTimestamp("expires_at").toInstant();
            TraceState state = stopped != null ? TraceState.STOPPED
                    : expires.isAfter(now) ? TraceState.ACTIVE : TraceState.EXPIRED;
            return new TraceSessionResponse(rs.getObject("id", UUID.class), rs.getString("environment_name"),
                    rs.getString("api_proxy_name"), rs.getString("filter_header"), rs.getString("filter_value"),
                    rs.getString("filter_client_ip"), rs.getInt("max_transactions"), state, rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant(), expires, stopped == null ? null : stopped.toInstant());
        };
    }

    private Map<String, Object> payload(Object raw) {
        if (raw == null || raw.toString().isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw.toString(), MAP);
        } catch (RuntimeException e) {
            return Map.of("raw", raw.toString());
        }
    }

    /** A ClickHouse string literal for an Array(String) parameter. */
    static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    Duration remaining(TraceSessionResponse s) {
        return Duration.between(now(), s.expiresAt());
    }
}
