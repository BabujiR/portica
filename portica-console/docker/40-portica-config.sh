#!/bin/sh
# Writes the console's runtime configuration from environment variables (nginx image entrypoint hook).
set -eu
escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
cat > /usr/share/nginx/html/config.js <<CONFIG
window.__PORTICA_CONFIG__ = {
  apiBaseUrl: "$(escape "${PORTICA_API_BASE_URL}")",
  gatewayUrl: "$(escape "${PORTICA_GATEWAY_URL}")",
  oidcAuthority: "$(escape "${PORTICA_OIDC_AUTHORITY}")",
  oidcClientId: "$(escape "${PORTICA_OIDC_CLIENT_ID}")"
};
CONFIG
