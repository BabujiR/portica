# portica-console

The Portica web console (React 18 + TypeScript + Vite + Ant Design) is built in **Phase 5**.

It is intentionally not a Maven module: it has its own `package.json` build and is packaged
as a static bundle served by a lightweight web server (or a CDN) in front of the management API.

Pre-wired for Phase 5:

- Keycloak client: `portica-console` (public client, Authorization Code + PKCE S256)
- Redirect URI: `http://localhost:5173/*` (Vite dev server default)
- Management API CORS already allows `http://localhost:5173`
- `GET /api/v1/me` returns the current user, their organizations and the effective
  permissions per organization, so the UI can show/hide actions without hard-coding roles
