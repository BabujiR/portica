package com.portica.portal.config;

import com.portica.common.runtime.ManagementApiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class PortalConfiguration {

    @Bean
    ManagementApiClient managementApiClient(PortalProperties properties) {
        PortalProperties.Auth auth = properties.auth();
        return new ManagementApiClient(properties.managementUrl(), auth.tokenUri(), auth.clientId(), auth.clientSecret(),
                auth.timeout());
    }

    /** Try-it requests: no redirects (credentials stay with the gateway), HTTP/1.1 like most clients. */
    @Bean
    HttpClient tryItHttpClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
