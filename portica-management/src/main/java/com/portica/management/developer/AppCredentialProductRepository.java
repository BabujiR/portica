package com.portica.management.developer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AppCredentialProductRepository extends JpaRepository<AppCredentialProduct, UUID> {

    boolean existsByApiProduct_Id(UUID apiProductId);

    long countByApiProduct_Id(UUID apiProductId);
}
