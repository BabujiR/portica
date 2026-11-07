package com.portica.management.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI porticaManagementOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Portica Management API")
                        .version("v1")
                        .description("Administer Portica organizations, environments, members and roles. "
                                + "Errors use RFC 7807 problem details (application/problem+json).")
                        .license(new License().name("Proprietary")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token issued by the Portica Keycloak realm")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
