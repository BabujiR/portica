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

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** An API consumer registered in an organization (not a console user). Identified by email. */
@Entity
@Table(name = "developers")
public class Developer extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "email", nullable = false, updatable = false, length = 320)
    private String email;

    @Column(name = "first_name", nullable = false, length = 128)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 128)
    private String lastName;

    @Column(name = "user_name", length = 128)
    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeveloperStatus status;

    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String attributesJson;

    protected Developer() {
        // JPA
    }

    public Developer(Organization organization, String email, String firstName, String lastName, String userName,
                     Map<String, String> attributes) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.email = normalizeEmail(email);
        this.status = DeveloperStatus.ACTIVE;
        update(firstName, lastName, userName, attributes);
    }

    public static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
    }

    public void update(String firstName, String lastName, String userName, Map<String, String> attributes) {
        if (firstName != null) {
            this.firstName = firstName.strip();
        }
        if (lastName != null) {
            this.lastName = lastName.strip();
        }
        if (userName != null) {
            this.userName = userName.isBlank() ? null : userName.strip();
        }
        if (attributes != null || attributesJson == null) {
            this.attributesJson = JsonColumns.writeMap(attributes);
        }
    }

    public void setStatus(DeveloperStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public Organization getOrganization() {
        return organization;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getUserName() {
        return userName;
    }

    public DeveloperStatus getStatus() {
        return status;
    }

    public Map<String, String> attributes() {
        return JsonColumns.readMap(attributesJson);
    }
}
