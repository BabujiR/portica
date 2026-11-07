package com.portica.management.developer;

import com.portica.management.product.ApiProduct;
import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/** One API product granted to one credential, with its approval status. */
@Entity
@Table(name = "app_credential_products")
public class AppCredentialProduct extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_id", nullable = false, updatable = false)
    private AppCredential credential;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_product_id", nullable = false, updatable = false)
    private ApiProduct apiProduct;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProductGrantStatus status;

    protected AppCredentialProduct() {
        // JPA
    }

    AppCredentialProduct(AppCredential credential, ApiProduct apiProduct, ProductGrantStatus status) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.apiProduct = Objects.requireNonNull(apiProduct, "apiProduct");
        this.status = Objects.requireNonNull(status, "status");
    }

    void setStatus(ProductGrantStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public AppCredential getCredential() {
        return credential;
    }

    public ApiProduct getApiProduct() {
        return apiProduct;
    }

    public ProductGrantStatus getStatus() {
        return status;
    }
}
