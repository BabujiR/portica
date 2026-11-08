package com.portica.management.developer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppCertificateRepository extends JpaRepository<AppCertificate, UUID> {

    boolean existsByFingerprint(String fingerprint);

    Optional<AppCertificate> findByCredential_IdAndFingerprint(UUID credentialId, String fingerprint);
}
