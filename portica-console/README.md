# portica-console

The Portica web console: React 18 + TypeScript + Vite + Ant Design 5. It talks only to the
management API (`/api/v1`) with the signed-in user's token, so everything it can do follows the
user's roles in each organization.

It isn't a Maven module: it has its own `npm` build and ships as static files (nginx image below).

## Run it (development)

Prerequisites: Node.js 20+, and the backend running (`docker compose up -d` and the management API on
port 8080, see the root README).

```bash
cd portica-console
npm install
npm run dev          # http://localhost:5173
```

Sign in as `admin` / `admin` (platform admin: sees every organization and can create new ones), or as a
user who belongs to an organization, e.g. `alice` / `alice` after the root README's curl walkthrough
makes her the admin of `acme`. Settings go in `.env.local` (copy `.env.example`):

| Variable | Default | |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | Management API |
| `VITE_GATEWAY_URL` | `http://localhost:8081` | Shown in proxy URLs and curl examples |
| `VITE_OIDC_AUTHORITY` | `http://localhost:8180/realms/portica` | Keycloak realm (any OIDC provider works) |
| `VITE_OIDC_CLIENT_ID` | `portica-console` | Public client, Authorization Code + PKCE |

Other scripts: `npm run build` (typecheck + production build into `dist/`), `npm run typecheck`,
`npm test` (Vitest unit tests for the flow editor model and proxy presets), `npm run preview`.

## Run it (container)

```bash
docker compose --profile console up -d --build console    # http://localhost:5173
```

The image serves the build with nginx and writes `config.js` from `PORTICA_API_BASE_URL`,
`PORTICA_GATEWAY_URL`, `PORTICA_OIDC_AUTHORITY` and `PORTICA_OIDC_CLIENT_ID` at start-up, so the same
image works in every environment. Remember to add the console's URL to the Keycloak client's redirect
URIs and web origins, and to the management API's `portica.security.cors-allowed-origins`.

## What's inside

| Area | Pages |
|---|---|
| Shell | Sign-in (OIDC, PKCE, tokens in session storage, silent renewal), organization and environment switchers, light/dark mode, permission-aware navigation |
| API proxies | List with live revision per environment; create wizard (backend URL or OpenAPI import, target URL or target server, starter security policies, deploy); detail with overview, **flow editor**, revisions, deployments and a live **trace** |
| Flow editor | Drag policies from the palette onto PreFlow, conditional flows, PostFlow and fault rules; drag steps to reorder or move; step conditions; JSON policy configuration with examples; saves a new immutable revision and can deploy it |
| Shared flows | List, editor (same drag-and-drop editor), deployments |
| Publish | API products (proxies, paths, environments, scopes, quota, approval); developers; apps with keys: issue, rotate with grace period, revoke, expiry, approve/revoke/remove products |
| Configure | Environments (with what's deployed), target servers, key value maps (environment and organization scope, encrypted values never shown) |
| Analytics | Requests, error rate and latency tiles; traffic and latency over time, top proxies, top apps and status codes (ECharts); recent calls; time range, environment and proxy filters; live refresh |
| Trace | Start a session per environment (optional header or client IP filter), watch calls arrive over Server-Sent Events, inspect each call: policy steps (ran, skipped, fault), target call, headers and flow variables (secrets masked) |
| Administer | Members (invite, roles, remove) and the built-in roles with their permissions; audit log with search, resource type and date filters |

Layout of `src/`: `auth/` (OIDC), `api/` (typed client for every endpoint, problem-detail errors),
`app/` (theme, workspace context, shell), `components/` (shared UI bits), `pages/` (one folder per area;
`pages/flow/` holds the editor and its pure, unit-tested model).

## Design notes

- **Permissions** come from `GET /api/v1/me` per organization; actions a role can't perform are hidden
  or disabled, and the API enforces them anyway.
- **Tokens** live in session storage (cleared when the tab closes), never in local storage.
- **Secrets** (consumer secrets) are shown once in a modal right after they're issued; the API can't
  return them again.
- **Unsaved flow edits** are protected: leaving the page or switching revisions asks first.
- **Charts** use ECharts with only the line and bar charts registered, and the Analytics page is
  lazy-loaded, so the rest of the console doesn't download the charting library.
- **Live trace** reads the SSE stream with `fetch` (EventSource can't send the bearer token) and
  reconnects with backoff if the connection drops.
- **Drag and drop** uses the browser's native API, and every drag action also has a button (add step,
  remove, move flows up/down), so the editor works with a keyboard.
