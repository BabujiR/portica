package com.portica.management.developer;

import com.portica.management.product.ApiProduct;
import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A consumer key + secret pair of an app, with the API products it may call. Only SHA-256 hashes
 * of the key and secret are stored; the plain values are shown once, when the credential is issued.
 */
@Entity
@Table(name = "app_credentials")
public class AppCredential extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_id", nullable = false, updatable = false)
    private DeveloperApp app;

    @Column(name = "key_hash", nullable = false, updatable = false, length = 64)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, updatable = false, length = 8)
    private String keyPrefix;

    @Column(name = "secret_hash", nullable = false, updatable = false, length = 64)
    private String secretHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CredentialStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @OneToMany(mappedBy = "credential", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt")
    private List<AppCredentialProduct> products = new ArrayList<>();

    protected AppCredential() {
        // JPA
    }

    AppCredential(DeveloperApp app, String keyHash, String keyPrefix, String secretHash, Instant expiresAt) {
        this.app = Objects.requireNonNull(app, "app");
        this.keyHash = Objects.requireNonNull(keyHash, "keyHash");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.secretHash = Objects.requireNonNull(secretHash, "secretHash");
        this.status = CredentialStatus.APPROVED;
        this.expiresAt = expiresAt;
    }

    AppCredentialProduct grant(ApiProduct product, ProductGrantStatus grantStatus) {
        Optional<AppCredentialProduct> existing = findGrant(product.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        AppCredentialProduct grant = new AppCredentialProduct(this, product, grantStatus);
        products.add(grant);
        return grant;
    }

    Optional<AppCredentialProduct> findGrant(UUID productId) {
        return products.stream().filter(g -> g.getApiProduct().getId().equals(productId)).findFirst();
    }

    boolean removeGrant(UUID productId) {
        return products.removeIf(g -> g.getApiProduct().getId().equals(productId));
    }

    void setStatus(CredentialStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public DeveloperApp getApp() {
        return app;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public CredentialStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public List<AppCredentialProduct> getProducts() {
        return List.copyOf(products);
    }
}
