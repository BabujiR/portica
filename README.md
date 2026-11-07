# Portica

**The front door to your APIs.** A self-hosted, cloud-agnostic API management platform:
High-level completeness without the cost or lock-in.

> **Status: Phase 3 – Policy engine.** Organizations, environments, members and RBAC (Phase 1);
> target servers, API proxies with immutable revisions, OpenAPI import and live deployments (Phase 2);
> and a policy engine with PreFlow / conditional flows / PostFlow / FaultRules, shared flows and
> 16 built-in policies, enforced by the gateway on every request (Phase 3).

---

## Architecture

```mermaid
flowchart LR
    subgraph Clients
        Console["portica-console<br/>(React, Phase 5)"]
        Portal["Developer Portal UI<br/>(Phase 8)"]
        Apps["API consumers<br/>& AI agents"]
    end

    subgraph ControlPlane["Control plane"]
        Mgmt["portica-management<br/>:8080"]
        PortalApi["portica-portal<br/>:8085"]
        Analytics["portica-analytics<br/>:8084"]
    end

    subgraph DataPlane["Data plane (stateless, scale out)"]
        GW["portica-gateway<br/>:8081"]
        AIGW["portica-ai-gateway<br/>:8082"]
        MCP["portica-mcp<br/>:8083"]
    end

    KC[(Keycloak<br/>:8180)]
    PG[(PostgreSQL)]
    RD[(Redis)]
    KF[[Kafka]]
    CH[(ClickHouse)]

    Console -- OIDC --> KC
    Console -- JWT --> Mgmt
    Portal --> PortalApi
    Apps --> GW & AIGW & MCP
    Mgmt --> PG
    GW & AIGW & MCP -- config sync --> Mgmt
    GW & AIGW & MCP -- quotas --> RD
    GW & AIGW & MCP -- analytics events --> KF --> Analytics --> CH
```

### Modules

| Module | Port | Phase 1 state | Becomes |
|---|---|---|---|
| `portica-common` | – | Error model, headers, validation, paging | Shared library |
| `portica-management` | 8080 | **Implemented** | Admin API for everything |
| `portica-gateway` | 8081 | **Implemented**: dynamic routing | + policy engine (Phase 3) |
| `portica-analytics` | 8084 | Skeleton | Kafka → ClickHouse pipeline + query API (Phase 6) |
| `portica-ai-gateway` | 8082 | Skeleton | LLM gateway (Phase 7) |
| `portica-mcp` | 8083 | Skeleton | MCP gateway (Phase 7) |
| `portica-portal` | 8085 | Skeleton | Developer portal backend (Phase 8) |
| `portica-console` | 5173 | Placeholder | React console (Phase 5) |

### Phase 1 data model

```mermaid
erDiagram
    ORGANIZATIONS ||--o{ ENVIRONMENTS : contains
    ORGANIZATIONS ||--o{ ROLE_BINDINGS : grants
    USERS ||--o{ ROLE_BINDINGS : holds
    ROLES ||--o{ ROLE_BINDINGS : "is granted by"
    ROLES ||--|{ ROLE_PERMISSIONS : includes
    ORGANIZATIONS ||..o{ AUDIT_EVENTS : "logged for"

    ORGANIZATIONS {
        uuid id PK
        string name UK
        string display_name
        string status
        bigint version
    }
    ENVIRONMENTS {
        uuid id PK
        uuid organization_id FK
        string name
        string display_name
    }
    USERS {
        uuid id PK
        string keycloak_subject UK
        string email UK
        string status
    }
    ROLES {
        uuid id PK
        string code UK
        boolean built_in
        uuid organization_id "reserved for custom roles"
    }
    ROLE_PERMISSIONS {
        uuid role_id FK
        string permission
    }
    ROLE_BINDINGS {
        uuid id PK
        uuid organization_id FK
        uuid user_id FK
        uuid role_id FK
    }
    AUDIT_EVENTS {
        uuid id PK
        uuid organization_id
        string action
        jsonb details
        string request_id
    }
```

### Phase 2 data model

```mermaid
erDiagram
    ORGANIZATIONS ||--o{ API_PROXIES : owns
    API_PROXIES ||--|{ API_PROXY_REVISIONS : "has (immutable)"
    ENVIRONMENTS ||--o{ TARGET_SERVERS : defines
    ENVIRONMENTS ||--o{ DEPLOYMENTS : runs
    API_PROXIES ||--o{ DEPLOYMENTS : "live as"
    API_PROXY_REVISIONS ||--o{ DEPLOYMENTS : "is deployed by"

    API_PROXIES {
        uuid id PK
        string name UK
        int latest_revision
    }
    API_PROXY_REVISIONS {
        uuid id PK
        int revision
        string base_path
        string target_url "or target_server"
        string target_path
        text openapi_spec
    }
    TARGET_SERVERS {
        uuid id PK
        string name UK
        string host
        int port
        string protocol
    }
    DEPLOYMENTS {
        uuid id PK
        uuid environment_id FK
        uuid api_proxy_id FK "unique per environment"
        uuid revision_id FK
        string base_path "unique per environment"
    }
```

### How a deployment reaches traffic

```mermaid
sequenceDiagram
    participant U as Console / API client
    participant M as portica-management
    participant G as portica-gateway
    participant B as Backend
    U->>M: POST .../environments/dev/apis/orders/revisions/2/deployments
    M->>M: swap deployment row, bump dev config version (one transaction)
    loop every 5s
        G->>M: GET /internal/v1/runtime/environments (versions only)
    end
    G->>M: dev version moved: GET snapshot of acme/dev
    G->>G: rebuild routes, RefreshRoutesEvent (atomic swap)
    U->>G: GET /acme/dev/orders/42
    G->>B: GET {target}/42 + X-Portica-* headers
```

---

## Prerequisites

- Java 21+ and Maven 3.9+
- Docker (for local infrastructure and the integration tests)
- `jq` (optional, for the curl walkthrough)

## Run it

```bash
# 1. Start infrastructure (Postgres, Redis, Kafka, ClickHouse, Keycloak with the portica realm)
docker compose up -d

# 2. Build and test everything (integration tests start their own Postgres via Testcontainers)
mvn clean verify

# 3. Run the management API, then (in a second terminal) the gateway
mvn -pl portica-management spring-boot:run
mvn -pl portica-gateway spring-boot:run -Dspring-boot.run.profiles=local
```

The gateway needs Redis (Quota counters, response cache), which `docker compose up -d` starts. The
`local` profile adds a development API key (`dev-acme-key-123` for org `acme`) for the VerifyAPIKey
policy until developer apps arrive in Phase 4. In IntelliJ, set *Active profiles* to `local` in the
gateway's run configuration.

> **Upgrading from Phase 1?** Keycloak only imports the realm the first time it starts, so recreate it
> once to get the new `portica-gateway` client: `docker compose up -d --force-recreate keycloak`.
> (Local Keycloak data lives inside the container, so this resets it to the seeded users.)

- API docs: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- Keycloak admin: http://localhost:8180 (admin / admin)

To run the management API and gateway in Docker instead:

```bash
mvn -pl portica-management,portica-gateway -am package -DskipTests
docker compose --profile app up -d --build
```

### Seeded Keycloak users (local only)

| Username | Password | Realm role |
|---|---|---|
| `admin` | `admin` | `portica-platform-admin` |
| `alice` | `alice` | – |
| `bob` | `bob` | – |

## Try it with curl

```bash
token() {
  curl -s http://localhost:8180/realms/portica/protocol/openid-connect/token \
    -d grant_type=password -d client_id=portica-cli -d username="$1" -d password="$1" | jq -r .access_token
}
ADMIN=$(token admin); ALICE=$(token alice); BOB=$(token bob)
API=http://localhost:8080/api/v1

# Platform admin creates an organization with Alice as its first ORG_ADMIN (dev/test/prod are created too)
curl -s -X POST $API/organizations -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"acme","displayName":"Acme Corp","adminEmail":"alice@portica.local"}' | jq

# Alice signs in: invitation linked, memberships and effective permissions returned
curl -s $API/me -H "Authorization: Bearer $ALICE" | jq

# Alice invites Bob as a read-only VIEWER
curl -s -X POST $API/organizations/acme/members -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d '{"email":"bob@portica.local","roles":["VIEWER"]}' | jq

# Bob can list environments ...
curl -s "$API/organizations/acme/environments?q=pro" -H "Authorization: Bearer $BOB" | jq
# ... but cannot create one (403, RFC 7807 body)
curl -s -X POST $API/organizations/acme/environments -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' -d '{"name":"staging"}' | jq

# Audit trail
curl -s $API/organizations/acme/audit-events -H "Authorization: Bearer $ALICE" | jq '.items[] | {action, actor, resourceName}'
```

### Phase 2: publish an API through the gateway

The demo backend from docker-compose (`http://localhost:8090`) echoes requests, so you can see exactly
what the gateway sends upstream.

```bash
# Alice creates an API proxy whose revision 1 points at the demo backend
curl -s -X POST $API/organizations/acme/apis -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"name":"echo","revision":{"basePath":"/echo","target":{"url":"http://localhost:8090/anything"}}}' | jq

# Deploy revision 1 to dev
curl -s -X POST $API/organizations/acme/environments/dev/apis/echo/revisions/1/deployments \
  -H "Authorization: Bearer $ALICE" | jq

# Within ~5 seconds the gateway serves it: /acme/dev/echo/** -> http://localhost:8090/anything/**
curl -s "http://localhost:8081/acme/dev/echo/orders/42?status=open" | jq '{url, headers}'
# The backend sees X-Portica-Org/Env/Proxy/Revision and X-Portica-Request-Id headers.

# Change something = new revision. Deploying it swaps the route in place (no downtime, no restart).
curl -s -X POST $API/organizations/acme/apis/echo/revisions -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d '{"basePath":"/echo","target":{"url":"http://localhost:8090/anything/v2"}}' | jq
curl -s -X POST $API/organizations/acme/environments/dev/apis/echo/revisions/2/deployments -H "Authorization: Bearer $ALICE" | jq

# Import a real OpenAPI spec (YAML or JSON): creates the proxy, or a new revision if it exists
SPEC=$(curl -s https://petstore3.swagger.io/api/v3/openapi.json)
jq -n --arg spec "$SPEC" '{name:"petstore", spec:$spec}' | \
  curl -s -X POST $API/organizations/acme/apis/import -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d @- | jq '{createdApiProxy, operationCount, revision: .revision.target}'

# What is the gateway serving right now?
curl -s http://localhost:8081/actuator/gateway/routes | jq '.[].route_id'
```

### Phase 3: protect the API with policies

A revision can carry a policy bundle: `policies` (named, configured instances) and `flows` (where they run).

```bash
# Revision 3 of "echo": CORS, API key, quota per app, header enrichment, a fault rule and JSON->XML on demand
cat > bundle.json <<'EOF'
{"basePath":"/echo","target":{"url":"http://localhost:8090/anything"},
 "policies":[
   {"name":"cors","type":"CORS","config":{"allowOrigins":["http://localhost:3000"],"allowHeaders":["*"]}},
   {"name":"verify-key","type":"VerifyAPIKey","config":{}},
   {"name":"quota","type":"Quota","config":{"count":5,"interval":1,"timeUnit":"minute",
                                             "identifier":"{verifyapikey.verify-key.client_id}"}},
   {"name":"spike","type":"SpikeArrest","config":{"rate":"20ps"}},
   {"name":"enrich","type":"AssignMessage","config":{
      "setHeaders":{"X-App":"{verifyapikey.verify-key.app.name}"},"removeHeaders":["x-api-key"]}},
   {"name":"to-xml","type":"JSONToXML","config":{"rootElement":"response"}},
   {"name":"no-key","type":"RaiseFault","config":{"statusCode":401,"headers":{"WWW-Authenticate":"ApiKey"},
      "body":"{\"error\":\"unauthorized\",\"reason\":\"{fault.name}\"}"}}],
 "flows":{
   "preFlow":{"request":[{"policy":"cors"},{"policy":"spike"},{"policy":"verify-key"},
                         {"policy":"quota"},{"policy":"enrich"}]},
   "conditionalFlows":[{"name":"xml","condition":"request.header.accept == \"application/xml\"",
                        "response":[{"policy":"to-xml"}]}],
   "faultRules":[{"name":"auth","condition":"fault.name == \"InvalidApiKey\" or fault.name == \"FailedToResolveAPIKey\"",
                  "steps":[{"policy":"no-key"}]}]}}
EOF
curl -s -X POST $API/organizations/acme/apis/echo/revisions -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d @bundle.json | jq '{revision, policies: [.policies[].name]}'
curl -s -X POST $API/organizations/acme/environments/dev/apis/echo/revisions/3/deployments -H "Authorization: Bearer $ALICE" | jq

curl -i http://localhost:8081/acme/dev/echo/pets                                   # 401, reshaped by the fault rule
curl -s http://localhost:8081/acme/dev/echo/pets -H 'x-api-key: dev-acme-key-123' | jq .headers  # X-App: demo-app
curl -s http://localhost:8081/acme/dev/echo/pets -H 'x-api-key: dev-acme-key-123' -H 'Accept: application/xml'
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -w '%{http_code} ' http://localhost:8081/acme/dev/echo/pets \
  -H 'x-api-key: dev-acme-key-123'; done; echo                                       # ... 429 once the quota is used

# Invalid bundles are rejected with every problem listed
curl -s -X POST $API/organizations/acme/apis/echo/revisions -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"basePath":"/echo","target":{"url":"http://localhost:8090"},"policies":[{"name":"q","type":"Quota","config":{"count":0}}],
       "flows":{"preFlow":{"request":[{"policy":"nope"}]}}}' | jq '{code, errors}'

# Shared flows: reusable chains, deployed per environment, called with FlowCallout
curl -s -X POST $API/organizations/acme/sharedflows -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"name":"security","revision":{"policies":[{"name":"spike","type":"SpikeArrest","config":{"rate":"50ps"}}],
       "steps":[{"policy":"spike"}]}}' | jq
curl -s -X POST $API/organizations/acme/environments/dev/sharedflows/security/revisions/1/deployments \
  -H "Authorization: Bearer $ALICE" | jq
# then add {"name":"common","type":"FlowCallout","config":{"sharedFlow":"security"}} to any proxy bundle

# The policy catalog (what the console's policy palette will show)
curl -s $API/policy-types -H "Authorization: Bearer $ALICE" | jq '.[] | {type, category}'
```

On Windows PowerShell, use the token snippet from the IntelliJ guide and `Invoke-RestMethod`, or run the
commands above from Git Bash.

## Policies (Phase 3)

### How a request flows

```mermaid
flowchart LR
    C[Client] --> PRE[PreFlow request]
    PRE --> CF{first conditional flow<br/>whose condition matches}
    CF --> POST[PostFlow request]
    POST --> T[(Target)]
    T --> PRER[PreFlow response]
    PRER --> CFR[matched flow response]
    CFR --> POSTR[PostFlow response]
    POSTR --> C
    PRE & CF & POST & T & PRER & CFR & POSTR -. fault .-> FR[first matching FaultRule<br/>or DefaultFaultRule]
    FR --> C
```

- A step is `{"policy": "<name>", "condition": "<optional>"}`; it runs only when its condition is true.
- A policy that answers the request itself (CORS preflight, ResponseCache hit) skips the rest of the
  request flow and the target; the response flow still runs.
- A fault stops the flow. The first FaultRule whose condition matches runs (otherwise the
  DefaultFaultRule), shaping the error response. Without one, clients get
  `{"fault":{"name","message","requestId"}}` with the fault's status. Stack traces are never returned.
- `continueOnError: true` on a policy records its fault in `<policy>.failed` / `<policy>.fault.name`
  and carries on.
- `enabled: false` keeps a policy in the bundle but drops its steps.

### Built-in policies

| Policy | Category | What it does | Faults |
|---|---|---|---|
| `VerifyAPIKey` | Security | Validates an API key (default from `x-api-key`); sets `verifyapikey.<name>.client_id`, `.app.name`, `.developer.email`, `.apiproduct.name` | `FailedToResolveAPIKey`, `InvalidApiKey` (401) |
| `OAuthV2` | Security | `VerifyAccessToken`: validates a bearer JWT against a JWKS (issuer, audiences, required scopes) | `MissingAccessToken`, `InvalidAccessToken` (401), `InsufficientScope` (403) |
| `VerifyJWT` | Security | Verifies a JWT from any source; RS*/ES* via JWKS or HS* with a shared secret; issuer, audience, subject, claims | `JwtMissing`, `JwtInvalid` (401) |
| `AccessControl` | Security | IP/CIDR allow and deny lists | `AccessDenied` (403) |
| `RegularExpressionProtection` | Threat protection | Blocks requests whose path, query, headers or body match patterns | `ThreatDetected` (400) |
| `JSONThreatProtection` | Threat protection | Limits depth, entries, array size, string and name length, body size | `JsonThreatDetected` (400) |
| `Quota` | Traffic | Fixed-window counts per identifier, **shared by all gateways** (Redis); `RateLimit-*` headers | `QuotaViolation` (429), `QuotaUnavailable` (503) |
| `SpikeArrest` | Traffic | Token-bucket smoothing (`100ps`, `600pm`, with `burst`), per gateway instance | `SpikeArrestViolation` (429) |
| `ResponseCache` | Traffic | Caches GET/HEAD responses in Redis; skips `private`/`no-store`/`Set-Cookie` | – |
| `CORS` | Mediation | Answers preflight requests and adds CORS headers to every response, errors included | – |
| `AssignMessage` | Mediation | Sets/adds/removes headers and query params; sets verb, path, body, status, variables | – |
| `JSONToXML` / `XMLToJSON` | Mediation | Converts request or response bodies (XML parsing is XXE-safe) | `InvalidJSON` / `InvalidXML` |
| `RaiseFault` | Mediation | Raises a custom fault; inside a FaultRule, replaces the error response | configurable |
| `ServiceCallout` | Extension | Calls another HTTP service; exposes `servicecallout.<name>.response.*` | `ServiceCalloutFailed` (502) |
| `FlowCallout` | Extension | Runs a deployed shared flow | its policies' faults |

`GET /api/v1/policy-types` returns the same catalog with each policy's config fields. Unknown config
fields are rejected, so typos fail at revision creation, not at runtime.

### Conditions and templates

Conditions: `==  !=  =| (starts with)  ~ (wildcard)  ~~ (regex)  <  <=  >  >=`, combined with
`and`/`&&`, `or`/`||`, `not`/`!` and parentheses; `null`, `true`, `false`, numbers and quoted strings.
A bare variable is true when it is set and not `false`/empty. Example:
`request.verb == "GET" and proxy.pathsuffix ~ "/pets/*" and not request.header.x-debug`.

Templates use `{variable}` anywhere a policy takes text (headers, bodies, identifiers). Braces that don't
wrap a variable name, like JSON, stay literal.

Variables: `organization.name`, `environment.name`, `apiproxy.name`, `apiproxy.revision`, `proxy.basepath`,
`proxy.pathsuffix`, `request.verb`, `request.uri`, `request.path`, `request.querystring`,
`request.header.<name>`, `request.queryparam.<name>`, `request.content`, `client.ip`, `messageid`,
`response.status.code`, `response.header.<name>`, `response.content`, `fault.name`, `fault.message`,
`error.status.code`, `current.flow.name`, `system.timestamp`, plus those set by policies.

### Shared flows

A shared flow is a named list of steps with its own policies and immutable revisions, deployed per
environment and called with `FlowCallout`. Deployment enforces order: a proxy or shared flow can only be
deployed where every shared flow it calls is live, a shared flow can't be undeployed while something
calls it, and call cycles are refused (the gateway also stops at 5 levels of nesting).

## Management API (v1)

| Method | Path | Permission |
|---|---|---|
| GET | `/me` | authenticated |
| GET | `/roles` | authenticated |
| GET | `/organizations` | authenticated (scoped to memberships; all for platform admin) |
| POST | `/organizations` | platform admin |
| GET / PATCH | `/organizations/{org}` | `ORG_READ` / `ORG_UPDATE` |
| DELETE | `/organizations/{org}` (archive) | platform admin |
| GET | `/organizations/{org}/environments[/{env}]` | `ENV_READ` |
| POST / PATCH / DELETE | `/organizations/{org}/environments[/{env}]` | `ENV_CREATE` / `ENV_UPDATE` / `ENV_DELETE` |
| GET | `/organizations/{org}/members[/{userId}]` | `MEMBER_READ` |
| POST / PUT `/{userId}/roles` / DELETE | `/organizations/{org}/members` | `MEMBER_MANAGE` |
| GET | `/organizations/{org}/audit-events` | `AUDIT_READ` |
| GET | `/organizations/{org}/environments/{env}/target-servers[/{name}]` | `ENV_READ` |
| POST / PUT / DELETE | `/organizations/{org}/environments/{env}/target-servers[/{name}]` | `TARGET_SERVER_WRITE` |
| GET | `/organizations/{org}/apis[/{api}]` | `PROXY_READ` |
| POST / PATCH / DELETE | `/organizations/{org}/apis[/{api}]` | `PROXY_WRITE` |
| POST | `/organizations/{org}/apis/import` (OpenAPI 3.x / Swagger 2.0) | `PROXY_WRITE` |
| GET | `/organizations/{org}/apis/{api}/revisions[/{rev}]`, `.../{rev}/openapi`, `.../{rev}/operations` | `PROXY_READ` |
| POST / DELETE | `/organizations/{org}/apis/{api}/revisions[/{rev}]` | `PROXY_WRITE` |
| POST / DELETE | `/organizations/{org}/environments/{env}/apis/{api}/revisions/{rev}/deployments` | `PROXY_DEPLOY` |
| GET | `/organizations/{org}/environments/{env}/deployments`, `/organizations/{org}/apis/{api}/deployments` | `PROXY_READ` |
| GET | `/organizations/{org}/sharedflows[/{name}]`, `.../{name}/revisions[/{rev}]`, `.../{name}/deployments` | `PROXY_READ` |
| POST / DELETE | `/organizations/{org}/sharedflows[/{name}]`, `.../{name}/revisions[/{rev}]` | `PROXY_WRITE` |
| POST / DELETE | `/organizations/{org}/environments/{env}/sharedflows/{name}/revisions/{rev}/deployments` | `PROXY_DEPLOY` |
| GET | `/organizations/{org}/environments/{env}/sharedflow-deployments` | `PROXY_READ` |
| GET | `/policy-types` (policy catalog with config fields) | authenticated |
| GET | `/internal/v1/runtime/environments`, `/internal/v1/runtime/organizations/{org}/environments/{env}` | `portica-runtime` realm role (gateways) |

List endpoints take `page`, `size` (max 100), `sort` (allow-listed fields) and `q` (case-insensitive search)
and return `{items, page, size, totalElements, totalPages}`. Errors are `application/problem+json` with a stable
`code` and the `requestId` (also returned in the `X-Portica-Request-Id` header).

### Built-in roles

| Permission | ORG_ADMIN | API_ADMIN | OPS_ADMIN | BUSINESS_USER | ANALYST | VIEWER |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| ORG_READ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ORG_UPDATE | ✓ | | | | | |
| ENV_READ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ENV_CREATE / UPDATE / DELETE | ✓ | | ✓ | | | |
| TARGET_SERVER_WRITE | ✓ | ✓ | ✓ | | | |
| MEMBER_READ | ✓ | ✓ | ✓ | | | ✓ |
| MEMBER_MANAGE | ✓ | | | | | |
| AUDIT_READ | ✓ | | ✓ | | | |
| PROXY_READ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PROXY_WRITE | ✓ | ✓ | | | | |
| PROXY_DEPLOY | ✓ | ✓ | ✓ | | | |
| PRODUCT_READ / DEVELOPER_READ | ✓ | ✓ | | ✓ | ✓ | ✓ |
| PRODUCT_WRITE / DEVELOPER_WRITE | ✓ | ✓ | | ✓ | | |
| ANALYTICS_READ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| TRACE_RUN | ✓ | ✓ | ✓ | | | |
| AI_READ / MCP_READ | ✓ | ✓ | ✓ | | ✓ | ✓ |
| AI_WRITE / MCP_WRITE | ✓ | ✓ | | | | |

Permissions for later phases are defined now so role meaning stays stable as features ship.

---

## Design decisions and trade-offs

**Spring Boot 4.1 instead of 3.x.** Spring Boot 3.5, the last 3.x line, reached open-source end of life on
June 30, 2026. A new product should not start on an unsupported framework, so Portica uses Boot 4.1.x with
Spring Cloud 2025.1.x (Oakwood) and springdoc 3.1.x. Visible consequences: Jackson 3 (`tools.jackson.*`),
Testcontainers 2 artifact names (`testcontainers-postgresql`), and Boot 4's renamed starters
(`spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-resource-server`).

**Keycloak authenticates, Portica authorizes.** Keycloak owns identities, passwords, MFA and federation.
Organization membership and roles live in Portica's database, because they are product data (audited,
shown in the console, scoped per organization). The only role read from the token is
`portica-platform-admin`. Trade-off: two places to look when debugging access; in return, tenants don't
need Keycloak admin rights and RBAC works with any OIDC provider later.

**Just-in-time users and invitations.** Users are created on their first authenticated request. Members
can be added by email before they ever sign in (`INVITED`); the invitation is linked only when the token's
email is **verified**, preventing invitation takeover.

**403 for unknown organizations.** Non-admins get 403 (not 404) for organizations they can't access, so
organization names cannot be enumerated.

**Soft-delete organizations.** Archiving keeps data and history, blocks access immediately, and keeps the
name reserved. Hard deletion/purge will be a separate, explicit operation.

**At least one ORG_ADMIN.** Removing or demoting the last admin is rejected (422). The check runs under a
row lock on the organization so concurrent requests can't both pass it.

**Audit in the same transaction.** Each change and its audit event commit together. Audit rows have no
foreign keys, so history survives deletions.

**Metrics and probes are unauthenticated** on the API port for local convenience. In production, expose
actuator on a separate management port or internal network only.

**The `portica-cli` client uses the password grant** purely for local curl testing. It is not part of
OAuth 2.1 and must not be enabled outside a laptop. The console will use Authorization Code + PKCE.

### Phase 2

**Immutable revisions.** A revision is never edited; changing a proxy means creating revision N+1.
What is deployed is always exactly what was reviewed, the audit trail is unambiguous, and rollback is just
deploying the previous revision.

**Zero-downtime swaps.** Each proxy has at most one live revision per environment. Deploying another
revision updates that deployment row in place; the gateway replaces the route atomically, and in-flight
requests complete on the old route.

**Gateway pulls; management never pushes.** Gateways poll a tiny version index (default every 5 s) and
fetch an environment's full snapshot only when its version moves. Gateways stay stateless, scale out with
no registration, and keep serving the last known good config if the management plane is down.
Trade-off: changes take up to one poll interval to reach traffic. A push channel (Kafka/Redis) can cut
that later without changing the contract.

**Concurrent changes in one environment are serialized.** Every runtime-relevant change bumps the
environment's config version, which is also optimistically locked, so racing deploys get a 409 instead
of silently conflicting. The database also enforces one revision per proxy and unique base paths per
environment.

**Path-based routing.** Clients call `/{org}/{env}{basePath}/...`. It works with zero DNS setup, but
exposes organization and environment names in URLs. Hostname-based routing (environment groups with
virtual hosts, like Apigee) is planned for the hardening phase.

**Target servers are resolved by the management plane.** Revisions can name a target server instead of a
URL, so one revision promotes from dev to prod and reaches each environment's backend. Changing a target
server re-routes live traffic on the next sync without redeploying. Gateways only ever see concrete URIs.

**Light OpenAPI reader.** Import reads metadata, servers and operations with Jackson's YAML support
instead of a full OpenAPI toolchain. It rejects documents that aren't OpenAPI 3.x/Swagger 2.0 but does not
validate every schema detail; linting can be added as a policy-like check later.

**Gateway-owned headers can't be spoofed.** The gateway strips client-supplied `X-Portica-Org/Env/Proxy/
Revision` headers and sets its own, so backends can trust them. Inbound authentication (API keys, OAuth,
JWT) arrives with the policy engine in Phase 3, so until then a deployed proxy is publicly callable.

**Not yet restricted: where targets may point.** Any http(s) URL is accepted as a target. Before exposing
API creation to less trusted users, add an allow-list of target hosts/networks to prevent the gateway
being used to reach internal services (SSRF).

### Phase 3

**Policies compile once, run many.** The gateway turns each deployed revision into executable objects
when the environment snapshot arrives (configs bound, conditions parsed, shared flows linked). Requests
never parse JSON config or condition text.

**Fail closed.** A revision that doesn't compile on a gateway (unknown policy type, broken shared flow
reference) is not routed at all, so its URL returns 404 instead of running without its security policies.
The management plane validates bundles first, so this is a safety net for version skew between components.

**Streaming unless a policy needs the body.** The engine works out per revision whether any policy reads
the request body or changes the response body. If none does, the request streams to the target and
response policies (headers, status, CORS, rate-limit headers) run on headers only while the body streams
back. Buffering is capped (`portica.gateway.policy.max-request-body-bytes` / `max-response-body-bytes`, 10 MB
by default) and returns 413 / 502 beyond that.

**Quota is distributed; SpikeArrest is local.** Quota uses one atomic Redis script per request, so limits
hold across any number of gateways. If Redis is down, Quota fails open by default (logs a warning,
lets traffic through); set `failOpen: false` to fail with 503 instead. SpikeArrest smooths bursts on each
gateway instance with no network call, like Apigee's per-message-processor behaviour, so its effective
cluster rate is `rate × replicas`.

**CORS is a per-proxy policy.** WebFlux normally answers browser preflight requests itself before any
gateway filter runs. Portica passes them through to the proxy's flows so each API controls its own CORS.

**API keys are temporary in Phase 3.** VerifyAPIKey resolves keys through a `CredentialResolver`. Until
developers, apps and API products arrive in Phase 4, keys come from configuration (the `local` profile),
are stored hashed in memory, and never appear in logs.

**ServiceCallout can reach any http(s) URL.** As with targets, add an outbound allow-list before letting
less trusted users author bundles (SSRF).

## Next: Phase 4

Products, developers and apps: API products bundling proxies with quotas and scopes, developer and app
registration, API key and OAuth client credentials issuance, and VerifyAPIKey/OAuthV2 backed by them.
