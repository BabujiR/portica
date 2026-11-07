package com.portica.gateway.oauth;

import reactor.core.publisher.Mono;

import java.time.Duration;

/** Issued access tokens by token hash, shared by all gateway instances (Redis) or local (memory). */
public interface AccessTokenStore {

    Mono<Void> save(String tokenHash, AccessTokenRecord token, Duration ttl);

    Mono<AccessTokenRecord> find(String tokenHash);

    Mono<Void> delete(String tokenHash);
}
