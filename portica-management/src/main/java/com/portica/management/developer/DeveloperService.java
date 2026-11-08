package com.portica.management.developer;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.InvalidRequestException;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.common.security.Hashing;
import com.portica.common.security.Pem;
import com.portica.management.audit.AuditService;
import com.portica.management.developer.DeveloperDtos.CreateAppRequest;
import com.portica.management.developer.DeveloperDtos.CreateCredentialRequest;
import com.portica.management.developer.DeveloperDtos.CreateDeveloperRequest;
import com.portica.management.developer.DeveloperDtos.IssuedCredential;
import com.portica.management.developer.DeveloperDtos.RotateCredentialRequest;
import com.portica.management.developer.DeveloperDtos.UpdateAppRequest;
import com.portica.management.developer.DeveloperDtos.UpdateCredentialRequest;
import com.portica.management.developer.DeveloperDtos.UpdateDeveloperRequest;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.product.ApiProduct;
import com.portica.management.product.ApiProductService;
import com.portica.management.product.ApprovalType;
import com.portica.management.runtime.RuntimeChanges;
import com.portica.management.shared.SearchTerms;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Developers, their apps and app credentials. Any change that affects what a credential may call
 * (status, expiry, products) bumps every environment of the organization so gateways pick it up.
 */
@Service
public class DeveloperService {

    static final String DEVELOPER = "Developer";
    static final String APP = "DeveloperApp";
    static final String CREDENTIAL = "AppCredential";
    static final long DEFAULT_GRACE_SECONDS = 86_400;

    private final DeveloperRepository developers;
    private final DeveloperAppRepository apps;
    private final AppCredentialRepository credentials;
    private final AppCertificateRepository certificateRepository;
    private final ApiProductService products;
    private final OrganizationFinder organizations;
    private final CredentialGenerator generator;
    private final RuntimeChanges runtimeChanges;
    private final AuditService audit;
    private final Clock clock;

    public DeveloperService(DeveloperRepository developers, DeveloperAppRepository apps,
                            AppCredentialRepository credentials, AppCertificateRepository certificateRepository,
                            ApiProductService products,
                            OrganizationFinder organizations, CredentialGenerator generator,
                            RuntimeChanges runtimeChanges, AuditService audit) {
        this.developers = developers;
        this.apps = apps;
        this.credentials = credentials;
        this.certificateRepository = certificateRepository;
        this.products = products;
        this.organizations = organizations;
        this.generator = generator;
        this.runtimeChanges = runtimeChanges;
        this.audit = audit;
        this.clock = Clock.systemUTC();
    }

    public Instant now() {
        return clock.instant();
    }

    // ----------------------------------------------------------------- developers

    @Transactional(readOnly = true)
    public Page<Developer> list(String org, String q, Pageable pageable) {
        return developers.search(organizations.getActive(org).getId(), SearchTerms.normalize(q), pageable);
    }

    @Transactional(readOnly = true)
    public Developer get(String org, String email) {
        return findDeveloper(organizations.getActive(org), email);
    }

    @Transactional
    public Developer create(String org, CreateDeveloperRequest r) {
        Organization organization = organizations.getActive(org);
        String email = Developer.normalizeEmail(r.email());
        if (developers.existsByOrganization_IdAndEmail(organization.getId(), email)) {
            throw ResourceConflictException.alreadyExists(DEVELOPER, email);
        }
        Developer developer = developers.save(new Developer(organization, email, r.firstName(), r.lastName(),
                r.userName(), r.attributes()));
        audit.record(organization.getId(), "developer.created", DEVELOPER, email, Map.of());
        return developer;
    }

    @Transactional
    public Developer update(String org, String email, UpdateDeveloperRequest r) {
        Organization organization = organizations.getActive(org);
        Developer developer = findDeveloper(organization, email);
        developer.update(r.firstName(), r.lastName(), r.userName(), r.attributes());
        Map<String, Object> details = new LinkedHashMap<>();
        if (r.status() != null && r.status() != developer.getStatus()) {
            developer.setStatus(r.status());
            details.put("status", r.status());
            runtimeChanges.organizationChanged(organization);
        }
        audit.record(organization.getId(), "developer.updated", DEVELOPER, developer.getEmail(), details);
        return developer;
    }

    /** Deletes the developer with all apps and credentials. */
    @Transactional
    public void delete(String org, String email) {
        Organization organization = organizations.getActive(org);
        Developer developer = findDeveloper(organization, email);
        developers.delete(developer);
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "developer.deleted", DEVELOPER, developer.getEmail(), Map.of());
    }

    // ----------------------------------------------------------------- apps

    @Transactional(readOnly = true)
    public Page<DeveloperApp> listApps(String org, String email, Pageable pageable) {
        return apps.findByDeveloper_Id(findDeveloper(organizations.getActive(org), email).getId(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<DeveloperApp> searchApps(String org, String q, Pageable pageable) {
        return apps.search(organizations.getActive(org).getId(), SearchTerms.normalize(q), pageable);
    }

    @Transactional(readOnly = true)
    public DeveloperApp getApp(String org, String email, String appName) {
        return findApp(organizations.getActive(org), email, appName);
    }

    @Transactional(readOnly = true)
    public List<AppCredential> credentialsOf(DeveloperApp app) {
        List<AppCredential> list = credentials.findByApp_IdOrderByCreatedAtAsc(app.getId());
        list.forEach(DeveloperService::initialized);
        return list;
    }

    /** Creates the app and its first credential. */
    @Transactional
    public IssuedCredential createApp(String org, String email, CreateAppRequest r) {
        Organization organization = organizations.getActive(org);
        Developer developer = findDeveloper(organization, email);
        if (apps.existsByDeveloper_IdAndName(developer.getId(), r.name())) {
            throw ResourceConflictException.alreadyExists(APP, r.name());
        }
        List<ApiProduct> requested = products.requireAll(organization, r.apiProducts());
        DeveloperApp app = new DeveloperApp(developer, r.name());
        app.update(r.displayName(), r.description(), r.callbackUrl(), r.attributes());
        app = apps.save(app);
        IssuedCredential issued = issue(app, requested, expiry(r.keyExpiresInSeconds()));
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "developer-app.created", APP, developer.getEmail() + "/" + app.getName(),
                Map.of("apiProducts", names(requested), "credential", issued.credential().getId()));
        return issued;
    }

    @Transactional
    public DeveloperApp updateApp(String org, String email, String appName, UpdateAppRequest r) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        app.update(r.displayName(), r.description(), r.callbackUrl(), r.attributes());
        Map<String, Object> details = new LinkedHashMap<>();
        if (r.status() != null && r.status() != app.getStatus()) {
            app.setStatus(r.status());
            details.put("status", r.status());
            runtimeChanges.organizationChanged(organization);
        }
        audit.record(organization.getId(), "developer-app.updated", APP, email + "/" + appName, details);
        return app;
    }

    @Transactional
    public void deleteApp(String org, String email, String appName) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        apps.delete(app);
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "developer-app.deleted", APP, email + "/" + appName, Map.of());
    }

    // ----------------------------------------------------------------- credentials

    @Transactional(readOnly = true)
    public AppCredential getCredential(String org, String email, String appName, UUID keyId) {
        return initialized(findCredential(findApp(organizations.getActive(org), email, appName), keyId));
    }

    /** Issues an additional credential (e.g. to rotate keys without downtime). */
    @Transactional
    public IssuedCredential createCredential(String org, String email, String appName, CreateCredentialRequest r) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        List<ApiProduct> requested = r.apiProducts() != null
                ? products.requireAll(organization, r.apiProducts())
                : newest(app).map(c -> c.getProducts().stream()
                        .filter(g -> g.getStatus() != ProductGrantStatus.REVOKED)
                        .map(AppCredentialProduct::getApiProduct).toList()).orElse(List.of());
        IssuedCredential issued = issue(app, requested, expiry(r.expiresInSeconds()));
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "app-credential.created", CREDENTIAL, credentialName(app, issued.credential()),
                Map.of("apiProducts", names(requested)));
        return issued;
    }

    /**
     * Rotation: issues a new credential with the same product grants and lets the old one expire
     * after {@code graceSeconds} (0 revokes it immediately), so clients can switch without downtime.
     */
    @Transactional
    public RotationResult rotateCredential(String org, String email, String appName, UUID keyId,
                                           RotateCredentialRequest r) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        AppCredential old = findCredential(app, keyId);
        if (old.getStatus() == CredentialStatus.REVOKED) {
            throw new BusinessRuleViolationException("A revoked credential can't be rotated; create a new one instead");
        }
        String key = generator.consumerKey();
        String secret = generator.consumerSecret();
        AppCredential fresh = new AppCredential(app, Hashing.sha256Hex(key), key.substring(0, 6),
                Hashing.sha256Hex(secret), expiry(r == null ? null : r.expiresInSeconds()));
        old.getProducts().forEach(g -> fresh.grant(g.getApiProduct(), g.getStatus()));
        AppCredential saved = credentials.save(fresh);

        long grace = r == null || r.graceSeconds() == null ? DEFAULT_GRACE_SECONDS : r.graceSeconds();
        if (grace == 0) {
            old.setStatus(CredentialStatus.REVOKED);
        } else {
            Instant until = now().plusSeconds(grace);
            if (old.getExpiresAt() == null || old.getExpiresAt().isAfter(until)) {
                old.setExpiresAt(until);
            }
        }
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "app-credential.rotated", CREDENTIAL, credentialName(app, old),
                Map.of("newCredential", saved.getId(), "graceSeconds", grace));
        return new RotationResult(new IssuedCredential(initialized(saved), key, secret), initialized(old));
    }

    public record RotationResult(IssuedCredential issued, AppCredential previous) {
    }

    @Transactional
    public AppCredential updateCredential(String org, String email, String appName, UUID keyId,
                                          UpdateCredentialRequest r) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        AppCredential c = findCredential(app, keyId);
        Map<String, Object> details = new LinkedHashMap<>();
        if (r.status() != null && r.status() != c.getStatus()) {
            c.setStatus(r.status());
            details.put("status", r.status());
        }
        if (r.expiresAt() != null) {
            c.setExpiresAt(r.expiresAt());
            details.put("expiresAt", r.expiresAt());
        }
        if (!details.isEmpty()) {
            runtimeChanges.organizationChanged(organization);
        }
        audit.record(organization.getId(), "app-credential.updated", CREDENTIAL, credentialName(app, c), details);
        return initialized(c);
    }

    @Transactional
    public void deleteCredential(String org, String email, String appName, UUID keyId) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        AppCredential c = findCredential(app, keyId);
        credentials.delete(c);
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "app-credential.deleted", CREDENTIAL, credentialName(app, c), Map.of());
    }

    /**
     * Registers a TLS client certificate on a credential. A certificate identifies one credential across
     * Portica; expired certificates and anything that isn't a single valid X.509 certificate are refused.
     */
    @Transactional
    public AppCredential addCertificate(String org, String email, String appName, UUID keyId, String pem) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        AppCredential c = findCredential(app, keyId);
        X509Certificate certificate;
        try {
            certificate = Pem.certificates(pem).get(0);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        if (certificate.getNotAfter().toInstant().isBefore(now())) {
            throw new InvalidRequestException("The certificate expired on " + certificate.getNotAfter().toInstant());
        }
        String fingerprint = Pem.fingerprint(certificate);
        if (certificateRepository.existsByFingerprint(fingerprint)) {
            throw ResourceConflictException.alreadyExists("AppCertificate", fingerprint);
        }
        c.addCertificate(fingerprint, certificate.getSubjectX500Principal().getName(),
                certificate.getIssuerX500Principal().getName(), certificate.getSerialNumber().toString(16),
                certificate.getNotBefore().toInstant(), certificate.getNotAfter().toInstant(), Pem.encode(certificate));
        credentials.saveAndFlush(c);
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "app-credential.certificate-added", CREDENTIAL, credentialName(app, c),
                Map.of("fingerprint", fingerprint, "subject", certificate.getSubjectX500Principal().getName()));
        return initialized(c);
    }

    @Transactional
    public AppCredential removeCertificate(String org, String email, String appName, UUID keyId, String fingerprint) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        AppCredential c = findCredential(app, keyId);
        if (!c.removeCertificate(fingerprint.toLowerCase(java.util.Locale.ROOT))) {
            throw new ResourceNotFoundException("AppCertificate", fingerprint);
        }
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "app-credential.certificate-removed", CREDENTIAL, credentialName(app, c),
                Map.of("fingerprint", fingerprint));
        return initialized(c);
    }

    /** Adds a product to a credential: approved at once for AUTO products, pending for MANUAL ones. */
    @Transactional
    public AppCredential addProduct(String org, String email, String appName, UUID keyId, String productName) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        AppCredential c = findCredential(app, keyId);
        ApiProduct product = products.find(organization, productName);
        if (c.findGrant(product.getId()).isPresent()) {
            throw ResourceConflictException.alreadyExists("ApiProductGrant", productName);
        }
        AppCredentialProduct grant = c.grant(product, initialStatus(product));
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "app-credential.product-added", CREDENTIAL, credentialName(app, c),
                Map.of("apiProduct", productName, "status", grant.getStatus()));
        return initialized(c);
    }

    /** Approves, revokes or re-pends one product of a credential. */
    @Transactional
    public AppCredential setProductStatus(String org, String email, String appName, UUID keyId, String productName,
                                          ProductGrantStatus status) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        AppCredential c = findCredential(app, keyId);
        ApiProduct product = products.find(organization, productName);
        AppCredentialProduct grant = c.findGrant(product.getId())
                .orElseThrow(() -> new ResourceNotFoundException("ApiProductGrant", productName));
        if (grant.getStatus() != status) {
            grant.setStatus(status);
            runtimeChanges.organizationChanged(organization);
        }
        audit.record(organization.getId(), "app-credential.product-status", CREDENTIAL, credentialName(app, c),
                Map.of("apiProduct", productName, "status", status));
        return initialized(c);
    }

    @Transactional
    public AppCredential removeProduct(String org, String email, String appName, UUID keyId, String productName) {
        Organization organization = organizations.getActive(org);
        DeveloperApp app = findApp(organization, email, appName);
        AppCredential c = findCredential(app, keyId);
        ApiProduct product = products.find(organization, productName);
        if (!c.removeGrant(product.getId())) {
            throw new ResourceNotFoundException("ApiProductGrant", productName);
        }
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "app-credential.product-removed", CREDENTIAL, credentialName(app, c),
                Map.of("apiProduct", productName));
        return initialized(c);
    }

    // ----------------------------------------------------------------- helpers

    /** Loads what responses read, while the transaction is still open (open-in-view is off). */
    private static AppCredential initialized(AppCredential c) {
        c.getProducts().forEach(g -> g.getApiProduct().getName());
        c.getCertificates().size();
        return c;
    }

    private IssuedCredential issue(DeveloperApp app, List<ApiProduct> requested, Instant expiresAt) {
        String key = generator.consumerKey();
        String secret = generator.consumerSecret();
        AppCredential credential = new AppCredential(app, Hashing.sha256Hex(key), key.substring(0, 6),
                Hashing.sha256Hex(secret), expiresAt);
        requested.forEach(p -> credential.grant(p, initialStatus(p)));
        return new IssuedCredential(credentials.save(credential), key, secret);
    }

    private static ProductGrantStatus initialStatus(ApiProduct product) {
        return product.getApprovalType() == ApprovalType.MANUAL ? ProductGrantStatus.PENDING : ProductGrantStatus.APPROVED;
    }

    private Instant expiry(Long seconds) {
        return seconds == null ? null : now().plusSeconds(seconds);
    }

    private java.util.Optional<AppCredential> newest(DeveloperApp app) {
        List<AppCredential> all = credentials.findByApp_IdOrderByCreatedAtAsc(app.getId());
        return all.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(all.get(all.size() - 1));
    }

    private static List<String> names(List<ApiProduct> list) {
        return list.stream().map(ApiProduct::getName).toList();
    }

    private static String credentialName(DeveloperApp app, AppCredential c) {
        return app.getDeveloper().getEmail() + "/" + app.getName() + "/" + c.getKeyPrefix();
    }

    private Developer findDeveloper(Organization organization, String email) {
        return developers.findByOrganization_IdAndEmail(organization.getId(), Developer.normalizeEmail(email))
                .orElseThrow(() -> new ResourceNotFoundException(DEVELOPER, email));
    }

    private DeveloperApp findApp(Organization organization, String email, String appName) {
        Developer developer = findDeveloper(organization, email);
        return apps.findByDeveloper_IdAndName(developer.getId(), appName)
                .orElseThrow(() -> new ResourceNotFoundException(APP, appName));
    }

    private AppCredential findCredential(DeveloperApp app, UUID keyId) {
        return credentials.findByIdAndApp_Id(keyId, app.getId())
                .orElseThrow(() -> new ResourceNotFoundException(CREDENTIAL, keyId.toString()));
    }
}
