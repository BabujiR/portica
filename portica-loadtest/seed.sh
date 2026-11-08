#!/usr/bin/env bash
# Prepares a load-test API on a local Portica (docker compose --profile app up -d): proxy "echo" in acme/dev
# with VerifyAPIKey + product quota + SpikeArrest in front of the demo backend, a product with a large quota,
# a developer and an app. Prints the API key for the Gatling run.
set -euo pipefail
KC=${KC:-http://localhost:8180}
API=${API:-http://localhost:8080/api/v1}
BACKEND=${BACKEND:-http://demo-backend:8080}   # as the gateway container reaches it
token() {
  curl -sf "$KC/realms/portica/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=portica-cli -d username="$1" -d password="$1" | jq -r .access_token
}
ADMIN=$(token admin); ALICE=$(token alice)
call() { curl -s -o /dev/null -w '%{http_code} ' "$@"; }

call -X POST "$API/organizations" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"acme","displayName":"Acme Corp","adminEmail":"alice@portica.local"}'
curl -s "$API/me" -H "Authorization: Bearer $ALICE" > /dev/null
call -X POST "$API/organizations/acme/apis" -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d @- <<JSON
{"name":"echo","revision":{"basePath":"/echo","target":{"url":"$BACKEND"},
 "policies":[
   {"name":"key","type":"VerifyAPIKey","config":{}},
   {"name":"quota","type":"Quota","config":{"useProductQuota":true}},
   {"name":"spike","type":"SpikeArrest","config":{"rate":"5000ps","identifier":"{client_id}"}}],
 "flows":{"preFlow":{"request":[{"policy":"key"},{"policy":"quota"},{"policy":"spike"}]}}}}
JSON
call -X POST "$API/organizations/acme/environments/dev/apis/echo/revisions/1/deployments" -H "Authorization: Bearer $ALICE"
call -X POST "$API/organizations/acme/apiproducts" -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"name":"load","proxies":["echo"],"environments":["dev"],"quota":{"count":100000000,"interval":1,"timeUnit":"hour"}}'
call -X POST "$API/organizations/acme/developers" -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"email":"load@example.com","firstName":"Load","lastName":"Test"}'
echo
KEY=$(curl -s -X POST "$API/organizations/acme/developers/load@example.com/apps" -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d "{\"name\":\"load-$(date +%s)\",\"apiProducts\":[\"load\"]}" | jq -r .credential.consumerKey)
sleep 6   # the gateway picks up the deployment and the key within its poll interval
curl -sf -o /dev/null "http://localhost:8081/acme/dev/echo/get" -H "x-api-key: $KEY" && echo "ready"
echo "API_KEY=$KEY"
