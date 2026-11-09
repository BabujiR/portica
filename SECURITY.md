# Security

This document is the Phase 8 security review of Portica: how each service is protected, what was
changed as a result of the review, and what an operator must still do before going to production.

## Reporting a vulnerability

Please don't open a public issue. E-mail the maintainers with the details and a way to reproduce it;
we acknowledge reports within three working days.

## Trust boundaries

| Boundary | Who calls | Authentication | Authorization |
|---|---|---|---|
| Console → management API | People | Keycloak (OIDC, PKCE), JWT with audience `portica-management` | Per-organization roles and permissions, platform admin realm role |
| Gateway / AI gateway / MCP → management runtime API | Services | OAuth client credentials, realm role `portica-runtime` | Read-only runtime feed |
| Portal BFF → management portal API | `portica-portal` | Client credentials, realm role `portica-portal` | Only `/internal/v1/portal/**`; every `/me` call is scoped to the developer e-mail the portal took from the developer's verified token |
| Developers → portal | People | Keycloak JWT; e-mail must be verified | Own apps and keys only; only `PUBLIC` products can be requested |
| API clients → gateway | Apps | Policies per proxy: VerifyAPIKey, OAuthV2 (client credentials), VerifyJWT, VerifyClientCertificate (mutual TLS) | API products granted to the credential, scopes, quotas |
| Gateway → AI gateway / MCP | Gateway | `X-Portica-Internal-Token` shared secret (plus NetworkPolicy in the Helm chart) | Internal routes only |
| Gateway → backends | Gateway | Optional mutual TLS per target server (client certificate, private CA) | — |

## Findings and fixes (Phase 8)

| # | Finding | Severity | Fix |
|---|---|---|---|
| 1 | The OAuth token and revocation endpoints accepted unlimited client-authentication attempts, so a consumer secret could be guessed at line speed. | High | Attempts are limited per client id (default 30 per minute, `portica.gateway.oauth.client-attempts-per-minute`) and optionally per IP, with a GCRA limiter shared by all gateway instances through Redis. Excess attempts get `429` with `Retry-After`. |
| 2 | `/actuator/gateway` was exposed on the public gateway port, listing every route with its backend URL. | Medium | Removed from the default exposure; enable it only on an internal management port. |
| 3 | SpikeArrest counted per gateway instance, so N instances allowed N times the configured rate. | Medium | SpikeArrest is now distributed (GCRA in Redis, one Lua script, Redis server time). `failOpen` decides what happens when Redis is unreachable. |
| 4 | Secrets (KVM entries, AI provider keys, MCP tokens) were encrypted directly with one static key; rotating it meant re-encrypting everything offline. | Medium | Envelope encryption: a fresh AES-256 data key per value, wrapped by a key-encryption key held in config or in HashiCorp Vault Transit. Rotation re-wraps data keys only; a scheduled job and `POST /api/v1/platform/secrets/rewrap` do it online. Old `v1:` values keep working and are migrated by the job. |
| 5 | Plain HTTP between clients and the gateway, and no way to authenticate clients by certificate. | Medium | TLS on the gateway (`tls` profile, Spring Boot SSL bundle, hot reload of rotated certificates), optional client certificates (`want` / `need`), certificates registered on app keys and the VerifyClientCertificate policy, optionally bound to the app of an API key or token (RFC 8705 style). |
| 6 | Backends could only be reached with the JVM's default trust store and no client certificate. | Medium | Per-target-server TLS: client certificate and key (key encrypted at rest, decrypted only into the authenticated runtime feed), private CAs, host name verification. A route whose TLS material is missing fails closed (503) instead of falling back to plain trust. |
| 7 | The developer portal could become an open proxy for try-it requests. | Medium (design) | Try-it only reaches APIs published in the catalog, at their deployed base path, through the gateway (so the API's own policies apply). Hop-by-hop, cookie, forwarding and internal headers are dropped, redirects aren't followed, bodies are capped and each developer gets 60 requests a minute. |
| 8 | A portal-supplied developer identity could be abused if any client could call the portal API. | Medium (design) | The management portal API requires the `portica-portal` realm role; the portal derives the e-mail address from a verified token and allow-lists the paths it forwards. |

Unchanged but reviewed: consumer keys and secrets are stored as SHA-256 hashes (they are 192+ bit random
values, so no salt is needed) and compared in constant time; secrets are shown once with
`Cache-Control: no-store`; the console uses the authorization code flow with PKCE and keeps tokens out
of URLs; the OIDC return URL is restricted to same-origin paths; JSON and regular expression threat
protection policies cap payload sizes; trace sessions are time-boxed and limited to people with
`TRACE_RUN`.

## Production checklist

- Replace every development secret: `PORTICA_KVM_ENCRYPTION_KEY` / `portica.secrets.local.keys` (or use
  Vault), `PORTICA_INTERNAL_TOKEN`, Keycloak client secrets, database and ClickHouse passwords. The
  Helm chart refuses to render with the development defaults unless `allowDevSecrets=true`.
- Use Vault Transit (`portica.secrets.provider=vault`) with a token limited to `encrypt`, `decrypt`,
  `rewrap` and `rotate` on the Portica key, or rotate local keys by adding a new key and switching
  `active-key`.
- Terminate TLS on the gateway (or on an ingress that forwards client certificates) and set
  `PORTICA_TLS_CLIENT_AUTH=need` for APIs that must always use mutual TLS.
- Keep management actuator endpoints, the runtime API (`/internal/**`) and the AI gateway / MCP services
  off the public network. The Helm chart's NetworkPolicies allow only the gateway to reach the AI gateway
  and MCP services.
- Set `portica.gateway.policy.trust-forwarded-for=true` only behind a proxy that overwrites
  `X-Forwarded-For`.
- Turn on Keycloak brute-force protection (on in the dev realm), e-mail verification for self-registered
  developers, and short access-token lifetimes.
- Run the gateway with at least two replicas against Redis; quotas, SpikeArrest, token attempts and
  OAuth tokens are shared through it.
