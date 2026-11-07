package com.portica.management.developer;

import com.portica.management.organization.Organization;
import com.portica.management.shared.AuditableEntity;
import com.portica.management.shared.JsonColumns;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.util.Map;
import java.util.Objects;

/** An application registered by a developer. It calls APIs with its credentials. */
@Entity
@Table(name = "developer_apps")
public class DeveloperApp extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "developer_id", nullable = false, updatable = false)
    private Developer developer;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "callback_url", length = 2048)
    private String callbackUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AppStatus status;

    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String attributesJson;

    protected DeveloperApp() {
        // JPA
    }

    public DeveloperApp(Developer developer, String name) {
        this.developer = Objects.requireNonNull(developer, "developer");
        this.organization = developer.getOrganization();
        this.name = Objects.requireNonNull(name, "name");
        this.displayName = name;
        this.status = AppStatus.APPROVED;
        this.attributesJson = JsonColumns.writeMap(Map.of());
    }

    /** Null arguments leave the field unchanged; blank strings clear optional fields. */
    public void update(String displayName, String description, String callbackUrl, Map<String, String> attributes) {
        if (displayName != null) {
            this.displayName = displayName.isBlank() ? name : displayName.strip();
        }
        if (description != null) {
            this.description = description.isBlank() ? null : description;
        }
        if (callbackUrl != null) {
            this.callbackUrl = callbackUrl.isBlank() ? null : callbackUrl.strip();
        }
        if (attributes != null) {
            this.attributesJson = JsonColumns.writeMap(attributes);
        }
    }

    public void setStatus(AppStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public Organization getOrganization() {
        return organization;
    }

    public Developer getDeveloper() {
        return developer;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public AppStatus getStatus() {
        return status;
    }

    public Map<String, String> attributes() {
        return JsonColumns.readMap(attributesJson);
    }
}
