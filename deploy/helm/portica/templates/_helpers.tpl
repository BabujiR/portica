{{/* Names */}}
{{- define "portica.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}{{ .Release.Name | trunc 63 | trimSuffix "-" }}{{- else -}}{{ printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" }}{{- end -}}
{{- end -}}

{{- define "portica.component" -}}
{{- printf "%s-%s" (include "portica.fullname" .root) .name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "portica.labels" -}}
app.kubernetes.io/name: portica
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version }}
app.kubernetes.io/component: {{ .name }}
{{- end -}}

{{- define "portica.selector" -}}
app.kubernetes.io/name: portica
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .name }}
{{- end -}}

{{- define "portica.secretName" -}}
{{- default (printf "%s-secrets" (include "portica.fullname" .)) .Values.secrets.existingSecret -}}
{{- end -}}

{{- define "portica.image" -}}
{{- $registry := .root.Values.image.registry -}}
{{- $tag := default .root.Chart.AppVersion .root.Values.image.tag -}}
{{- if $registry -}}{{ printf "%s/portica-%s:%s" $registry .name $tag }}{{- else -}}{{ printf "portica/portica-%s:%s" .name $tag }}{{- end -}}
{{- end -}}

{{/* Internal URL of a component: http://<release>-portica-<name>:<port> */}}
{{- define "portica.url" -}}
{{- printf "http://%s:%v" (include "portica.component" (dict "root" .root "name" .name)) .port -}}
{{- end -}}

{{- define "portica.secretEnv" -}}
- name: {{ .env }}
  valueFrom:
    secretKeyRef:
      name: {{ include "portica.secretName" .root }}
      key: {{ .key }}
      {{- if .optional }}
      optional: true
      {{- end }}
{{- end -}}

{{/* Environment of each component (non-secret values first, then secrets, then user overrides) */}}
{{- define "portica.env" -}}
{{- $root := .root -}}
{{- $v := $root.Values -}}
{{- $mgmt := include "portica.url" (dict "root" $root "name" "management" "port" $v.management.port) -}}
{{- $gw := include "portica.url" (dict "root" $root "name" "gateway" "port" $v.gateway.port) -}}
{{- if eq .name "management" }}
- { name: SPRING_DATASOURCE_URL, value: {{ $v.infra.postgres.url | quote }} }
- { name: PORTICA_DB_USERNAME, value: {{ $v.infra.postgres.username | quote }} }
- { name: PORTICA_OIDC_ISSUER, value: {{ $v.urls.oidcIssuer | quote }} }
- { name: PORTICA_OIDC_JWKS, value: {{ $v.infra.oidc.jwks | quote }} }
- { name: PORTICA_PUBLIC_URL, value: {{ $v.urls.api | quote }} }
- { name: PORTICA_CONSOLE_URL, value: {{ $v.urls.console | quote }} }
- { name: PORTICA_GATEWAY_PUBLIC_URL, value: {{ $v.urls.gateway | quote }} }
- { name: PORTICA_AI_GATEWAY_URL, value: {{ include "portica.url" (dict "root" $root "name" "ai-gateway" "port" $v.aiGateway.port) | quote }} }
- { name: PORTICA_MCP_URL, value: {{ include "portica.url" (dict "root" $root "name" "mcp" "port" $v.mcp.port) | quote }} }
- { name: PORTICA_CLICKHOUSE_URL, value: {{ $v.infra.clickhouse.url | quote }} }
- { name: PORTICA_CLICKHOUSE_DATABASE, value: {{ $v.infra.clickhouse.database | quote }} }
- { name: PORTICA_CLICKHOUSE_USER, value: {{ $v.infra.clickhouse.user | quote }} }
- { name: PORTICA_SECRETS_PROVIDER, value: {{ $v.secretsProvider.type | quote }} }
- { name: PORTICA_SECURITY_CORS_ALLOWED_ORIGINS, value: {{ $v.urls.console | quote }} }
{{- if eq $v.secretsProvider.type "vault" }}
- { name: PORTICA_VAULT_ADDR, value: {{ $v.secretsProvider.vault.address | quote }} }
- { name: PORTICA_VAULT_NAMESPACE, value: {{ $v.secretsProvider.vault.namespace | quote }} }
- { name: PORTICA_VAULT_TOKEN_FILE, value: {{ $v.secretsProvider.vault.tokenFile | quote }} }
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_VAULT_TOKEN" "key" "vault-token" "optional" true) }}
{{- end }}
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_DB_PASSWORD" "key" "db-password") }}
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_CLICKHOUSE_PASSWORD" "key" "clickhouse-password") }}
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_INTERNAL_TOKEN" "key" "internal-token") }}
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_KVM_ENCRYPTION_KEY" "key" "kvm-encryption-key" "optional" true) }}
{{- else if eq .name "gateway" }}
- { name: PORTICA_MANAGEMENT_URL, value: {{ $mgmt | quote }} }
- { name: PORTICA_GATEWAY_ENVIRONMENTS, value: {{ $v.gateway.environments | quote }} }
- { name: PORTICA_REDIS_HOST, value: {{ $v.infra.redis.host | quote }} }
- { name: PORTICA_REDIS_PORT, value: {{ $v.infra.redis.port | quote }} }
- { name: PORTICA_KAFKA_BOOTSTRAP, value: {{ $v.infra.kafka.bootstrap | quote }} }
- { name: PORTICA_OIDC_TOKEN_URI, value: {{ $v.infra.oidc.tokenUri | quote }} }
{{- if $v.gateway.tls.enabled }}
- { name: SPRING_PROFILES_ACTIVE, value: tls }
- { name: PORTICA_GATEWAY_TLS_PORT, value: {{ $v.gateway.tls.port | quote }} }
- { name: PORTICA_TLS_CLIENT_AUTH, value: {{ $v.gateway.tls.clientAuth | quote }} }
- { name: PORTICA_TLS_CERTIFICATE, value: file:/etc/portica/tls/tls.crt }
- { name: PORTICA_TLS_PRIVATE_KEY, value: file:/etc/portica/tls/tls.key }
- { name: PORTICA_TLS_CLIENT_CA, value: file:/etc/portica/client-ca/client-ca.crt }
{{- end }}
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_GATEWAY_CLIENT_SECRET" "key" "gateway-client-secret") }}
{{- else if eq .name "ai-gateway" }}
- { name: PORTICA_MANAGEMENT_URL, value: {{ $mgmt | quote }} }
- { name: PORTICA_REDIS_HOST, value: {{ $v.infra.redis.host | quote }} }
- { name: PORTICA_REDIS_PORT, value: {{ $v.infra.redis.port | quote }} }
- { name: PORTICA_KAFKA_BOOTSTRAP, value: {{ $v.infra.kafka.bootstrap | quote }} }
- { name: PORTICA_OIDC_TOKEN_URI, value: {{ $v.infra.oidc.tokenUri | quote }} }
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_GATEWAY_CLIENT_SECRET" "key" "gateway-client-secret") }}
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_INTERNAL_TOKEN" "key" "internal-token") }}
{{- else if eq .name "mcp" }}
- { name: PORTICA_MANAGEMENT_URL, value: {{ $mgmt | quote }} }
- { name: PORTICA_GATEWAY_INTERNAL_URL, value: {{ $gw | quote }} }
- { name: PORTICA_KAFKA_BOOTSTRAP, value: {{ $v.infra.kafka.bootstrap | quote }} }
- { name: PORTICA_OIDC_TOKEN_URI, value: {{ $v.infra.oidc.tokenUri | quote }} }
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_GATEWAY_CLIENT_SECRET" "key" "gateway-client-secret") }}
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_INTERNAL_TOKEN" "key" "internal-token") }}
{{- else if eq .name "analytics" }}
- { name: PORTICA_KAFKA_BOOTSTRAP, value: {{ $v.infra.kafka.bootstrap | quote }} }
- { name: PORTICA_CLICKHOUSE_URL, value: {{ $v.infra.clickhouse.url | quote }} }
- { name: PORTICA_CLICKHOUSE_DATABASE, value: {{ $v.infra.clickhouse.database | quote }} }
- { name: PORTICA_CLICKHOUSE_USER, value: {{ $v.infra.clickhouse.user | quote }} }
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_CLICKHOUSE_PASSWORD" "key" "clickhouse-password") }}
{{- else if eq .name "portal" }}
- { name: PORTICA_MANAGEMENT_URL, value: {{ $mgmt | quote }} }
- { name: PORTICA_GATEWAY_INTERNAL_URL, value: {{ $gw | quote }} }
- { name: PORTICA_OIDC_ISSUER, value: {{ $v.urls.oidcIssuer | quote }} }
- { name: PORTICA_OIDC_JWKS, value: {{ $v.infra.oidc.jwks | quote }} }
- { name: PORTICA_OIDC_TOKEN_URI, value: {{ $v.infra.oidc.tokenUri | quote }} }
- { name: PORTICA_PORTAL_ORIGINS, value: {{ $v.urls.console | quote }} }
{{ include "portica.secretEnv" (dict "root" $root "env" "PORTICA_PORTAL_CLIENT_SECRET" "key" "portal-client-secret") }}
{{- else if eq .name "console" }}
- { name: PORTICA_API_BASE_URL, value: {{ $v.urls.api | quote }} }
- { name: PORTICA_GATEWAY_URL, value: {{ $v.urls.gateway | quote }} }
- { name: PORTICA_PORTAL_API_URL, value: {{ $v.urls.portal | quote }} }
- { name: PORTICA_OIDC_AUTHORITY, value: {{ $v.urls.oidcIssuer | quote }} }
- { name: PORTICA_OIDC_CLIENT_ID, value: portica-console }
{{- end }}
{{- range $k, $val := .values.env }}
- { name: {{ $k }}, value: {{ $val | quote }} }
{{- end }}
{{- end -}}

{{/* The components: name, values key */}}
{{- define "portica.components" -}}
management: management
gateway: gateway
ai-gateway: aiGateway
mcp: mcp
analytics: analytics
portal: portal
console: console
{{- end -}}
