package com.portica.management.audit;

import com.portica.management.config.RequestIdFilter;
import com.portica.management.identity.CurrentUser;
import com.portica.management.identity.CurrentUserService;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Writes audit events. Must be called inside the transaction that performs the change
 * ({@link Propagation#MANDATORY}) so the change and its audit record commit or roll back together.
 * Never put secrets or credential material in {@code details}.
 */
@Service
public class AuditService {

    private final AuditEventRepository events;
    private final CurrentUserService currentUserService;
    private final JsonMapper jsonMapper;

    public AuditService(AuditEventRepository events, CurrentUserService currentUserService, JsonMapper jsonMapper) {
        this.events = events;
        this.currentUserService = currentUserService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID organizationId, String action, String resourceType, String resourceName,
                       Map<String, ?> details) {
        CurrentUser actor = currentUserService.currentUser();
        String json = jsonMapper.writeValueAsString(details == null ? Map.of() : details);
        events.save(new AuditEvent(organizationId, actor.subject(), actor.email(), action, resourceType,
                resourceName, json, MDC.get(RequestIdFilter.MDC_KEY)));
    }

    /** A platform-wide event (no organization), in its own transaction when there is none. */
    @Transactional
    public void recordPlatform(String action, String resourceName, Map<String, ?> details) {
        record(null, action, "Platform", resourceName, details);
    }
}
