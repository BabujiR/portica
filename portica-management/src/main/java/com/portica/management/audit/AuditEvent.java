package com.portica.management.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of a configuration change. Written in the same transaction as the change,
 * so a change is never committed without its audit entry.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "actor_subject", updatable = false)
    private String actorSubject;

    @Column(name = "actor_email", updatable = false, length = 320)
    private String actorEmail;

    @Column(name = "action", nullable = false, updatable = false, length = 128)
    private String action;

    @Column(name = "resource_type", nullable = false, updatable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_name", nullable = false, updatable = false)
    private String resourceName;

    /** JSON object, stored as JSONB. */
    @Column(name = "details", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String details;

    @Column(name = "request_id", updatable = false, length = 64)
    private String requestId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditEvent() {
        // JPA
    }

    AuditEvent(UUID organizationId, String actorSubject, String actorEmail, String action,
               String resourceType, String resourceName, String details, String requestId) {
        this.organizationId = organizationId;
        this.actorSubject = actorSubject;
        this.actorEmail = actorEmail;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceName = resourceName;
        this.details = details;
        this.requestId = requestId;
        this.occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceName() {
        return resourceName;
    }

    public String getDetails() {
        return details;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
