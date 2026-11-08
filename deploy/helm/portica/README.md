# Portica Helm chart

Deploys the Portica services: management API, API gateway, AI gateway, MCP, analytics ingestion, developer
portal backend and the console (which also serves the developer portal UI). PostgreSQL, Redis, Kafka,
ClickHouse and Keycloak (and optionally Vault) are external; point `infra.*` and `urls.*` at them.

```bash
# images: mvn -DskipTests package, then docker build each portica-* module (CI publishes them)
helm upgrade --install portica deploy/helm/portica -n portica --create-namespace \
  --set image.registry=ghcr.io/babujir --set image.tag=0.1.0 \
  --set secrets.existingSecret=portica-secrets \
  -f my-values.yaml
```

The secret (`secrets.existingSecret`) needs the keys `db-password`, `clickhouse-password`, `internal-token`,
`gateway-client-secret`, `portal-client-secret`, `kvm-encryption-key` (local key-encryption key) and
optionally `vault-token`. Without `existingSecret` the chart creates it from `secrets.*` and refuses
development values unless `allowDevSecrets=true`.

| Setting | Default | |
|---|---|---|
| `gateway.autoscaling.*` | 3 to 20 replicas at 65% CPU | the gateway is stateless; quotas, SpikeArrest and tokens live in Redis |
| `gateway.tls.enabled` | false | TLS and client certificates on the gateway itself (`tls` profile); mount a `kubernetes.io/tls` secret, e.g. from cert-manager, rotated without restarts |
| `gateway.tls.clientAuth` | want | `need` makes mutual TLS mandatory for every API |
| `gateway.service.type` | ClusterIP | `LoadBalancer` (TCP) when clients present certificates directly to the gateway |
| `secretsProvider.type` | local | `vault` uses Vault Transit for the key-encryption key |
| `networkPolicy.enabled` | true | only the gateway reaches the AI gateway and MCP services |
| `serviceMonitor.enabled` | false | Prometheus Operator scraping of `/actuator/prometheus` |

Render locally to review: `helm template portica deploy/helm/portica --set allowDevSecrets=true`.
