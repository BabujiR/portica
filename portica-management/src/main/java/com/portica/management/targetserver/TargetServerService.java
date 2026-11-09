package com.portica.management.targetserver;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.InvalidRequestException;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.audit.AuditService;
import com.portica.management.deployment.DeploymentRepository;
import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentFinder;
import com.portica.management.kvm.KvmCipher;
import com.portica.management.shared.SearchTerms;
import com.portica.management.targetserver.TargetServerDtos.CreateTargetServerRequest;
import com.portica.management.targetserver.TargetServerDtos.TargetServerRequest;
import com.portica.management.targetserver.TargetServerDtos.TargetTlsRequest;
import com.portica.common.security.Pem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TargetServerService {

    static final String RESOURCE_TYPE = "TargetServer";

    private final TargetServerRepository targetServers;
    private final EnvironmentFinder environments;
    private final DeploymentRepository deployments;
    private final AuditService audit;
    private final KvmCipher cipher;

    public TargetServerService(TargetServerRepository targetServers, EnvironmentFinder environments,
                               DeploymentRepository deployments, AuditService audit, KvmCipher cipher) {
        this.cipher = cipher;
        this.targetServers = targetServers;
        this.environments = environments;
        this.deployments = deployments;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<TargetServer> list(String org, String env, String q, Pageable pageable) {
        Environment environment = environments.get(org, env);
        return targetServers.search(environment.getId(), SearchTerms.normalize(q), pageable);
    }

    @Transactional(readOnly = true)
    public TargetServer get(String org, String env, String name) {
        return find(environments.get(org, env), name);
    }

    @Transactional
    public TargetServer create(String org, String env, CreateTargetServerRequest request) {
        Environment environment = environments.get(org, env);
        if (targetServers.existsByEnvironment_IdAndName(environment.getId(), request.name())) {
            throw ResourceConflictException.alreadyExists(RESOURCE_TYPE, request.name());
        }
        TargetServer server = new TargetServer(environment, request.name(), request.description(),
                request.host(), request.port(), request.protocol(), !Boolean.FALSE.equals(request.enabled()));
        applyTls(server, request.protocol(), request.tls());
        TargetServer created = targetServers.save(server);
        environment.markRuntimeConfigChanged();
        audit.record(environment.getOrganization().getId(), "target-server.created", RESOURCE_TYPE,
                env + "/" + created.getName(), details(created));
        return created;
    }

    /** Full replacement of the target server's settings. Live proxies pick up the change on the next gateway sync. */
    @Transactional
    public TargetServer update(String org, String env, String name, TargetServerRequest request) {
        Environment environment = environments.get(org, env);
        TargetServer server = find(environment, name);
        boolean enabled = !Boolean.FALSE.equals(request.enabled());
        if (!enabled && server.isEnabled() && deployments.isTargetServerInUse(environment.getId(), name)) {
            throw new BusinessRuleViolationException(
                    "Target server '%s' is used by a deployed API proxy in '%s'; undeploy it before disabling"
                            .formatted(name, env));
        }
        server.update(request.description(), request.host(), request.port(), request.protocol(), enabled);
        applyTls(server, request.protocol(), request.tls());
        environment.markRuntimeConfigChanged();
        audit.record(environment.getOrganization().getId(), "target-server.updated", RESOURCE_TYPE,
                env + "/" + name, details(server));
        return server;
    }

    @Transactional
    public void delete(String org, String env, String name) {
        Environment environment = environments.get(org, env);
        TargetServer server = find(environment, name);
        if (deployments.isTargetServerInUse(environment.getId(), name)) {
            throw new BusinessRuleViolationException(
                    "Target server '%s' is used by a deployed API proxy in '%s'; undeploy it first".formatted(name, env));
        }
        targetServers.delete(server);
        environment.markRuntimeConfigChanged();
        audit.record(environment.getOrganization().getId(), "target-server.deleted", RESOURCE_TYPE,
                env + "/" + name, Map.of());
    }

    /**
     * Validates and stores the TLS settings: HTTPS only; a client certificate needs its private key (sent
     * now, or kept from before when the certificate is unchanged), and the key must match it.
     */
    private void applyTls(TargetServer server, TargetServerProtocol protocol, TargetTlsRequest tls) {
        if (tls == null || (blank(tls.clientCertificate()) && blank(tls.trustedCertificates())
                && !Boolean.FALSE.equals(tls.verifyHostname()))) {
            server.updateTls(null, null, null, true);
            return;
        }
        if (protocol != TargetServerProtocol.HTTPS) {
            throw new InvalidRequestException("tls: TLS settings need protocol HTTPS");
        }
        String certificate = null;
        String encryptedKey = null;
        if (!blank(tls.clientCertificate())) {
            List<X509Certificate> chain = parse(tls.clientCertificate(), "tls.clientCertificate");
            certificate = tls.clientCertificate().strip();
            if (!blank(tls.clientKey())) {
                PrivateKey key;
                try {
                    key = Pem.privateKey(tls.clientKey());
                } catch (IllegalArgumentException e) {
                    throw new InvalidRequestException("tls.clientKey: " + e.getMessage());
                }
                if (!Pem.matches(key, chain.get(0))) {
                    throw new InvalidRequestException("tls.clientKey: doesn't match the client certificate");
                }
                cipher.requireEnabled();
                encryptedKey = cipher.encrypt(tls.clientKey().strip(), server.keyAssociatedData());
            } else if (certificate.equals(server.getTlsClientCertificate()) && server.getTlsClientKeyEncrypted() != null) {
                encryptedKey = server.getTlsClientKeyEncrypted();
            } else {
                throw new InvalidRequestException("tls.clientKey: required with a new client certificate");
            }
        } else if (!blank(tls.clientKey())) {
            throw new InvalidRequestException("tls.clientKey: given without tls.clientCertificate");
        }
        String trusted = null;
        if (!blank(tls.trustedCertificates())) {
            parse(tls.trustedCertificates(), "tls.trustedCertificates");
            trusted = tls.trustedCertificates().strip();
        }
        server.updateTls(certificate, encryptedKey, trusted, !Boolean.FALSE.equals(tls.verifyHostname()));
    }

    private static List<X509Certificate> parse(String pem, String field) {
        try {
            return Pem.certificates(pem);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(field + ": " + e.getMessage());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private TargetServer find(Environment environment, String name) {
        return targetServers.findByEnvironment_IdAndName(environment.getId(), name)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, name));
    }

    private static Map<String, Object> details(TargetServer server) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("baseUri", server.baseUri());
        details.put("enabled", server.isEnabled());
        details.put("mutualTls", server.getTlsClientCertificate() != null);
        details.put("customTrust", server.getTlsTrustedCertificates() != null);
        return details;
    }
}
