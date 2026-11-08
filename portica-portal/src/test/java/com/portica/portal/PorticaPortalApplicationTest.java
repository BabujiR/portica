package com.portica.portal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The portal starts without reaching the identity provider or the management API. */
@SpringBootTest(properties = "portica.portal.auth.token-uri=")
class PorticaPortalApplicationTest {

    @Test
    void contextLoads() {
    }
}
