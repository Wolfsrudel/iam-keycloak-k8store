#!/usr/bin/env bash
# Copyright 2026 Dominik Schlosser
# SPDX-License-Identifier: Apache-2.0
#
# Deploys a Keycloak 26.7.0 instance backed by the FILESTORE extension into the kind cluster -
# the "existing installation" half of the migration demo. Config comes from filestore files
# (seeded from deploy/filestore/02-import-realm.yaml with verbatim ${VAR} placeholders, fed at
# runtime from a Kubernetes Secret/ConfigMap through the container environment); users and
# sessions live in the shared postgres, which stays untouched by the later migration.
#
# Run this on a FRESH cluster (scripts/kind-down.sh && scripts/kind-up.sh): it bootstraps the
# admin user into postgres, which collides if a k8store deployment already bootstrapped it.
#
# Migrate it to k8store CRs afterwards with scripts/migrate-filestore.sh (see docs/DEMO.md).
#
# Usage: scripts/deploy-filestore.sh
set -euo pipefail
cd "$(dirname "$0")/.."

KUBECTL="kubectl --context kind-k8store"
IMAGE=localhost:5001/keycloak-filestore:dev

echo "Building and pushing ${IMAGE}..."
docker build -f deploy/filestore/Dockerfile -t "${IMAGE}" deploy/filestore
docker push "${IMAGE}"

${KUBECTL} apply -f deploy/00-namespace.yaml
${KUBECTL} apply -f deploy/10-postgres.yaml
${KUBECTL} -n keycloak rollout status deployment/postgres --timeout=300s
${KUBECTL} apply -f deploy/filestore/

${KUBECTL} -n keycloak rollout restart deployment/keycloak-filestore
${KUBECTL} -n keycloak rollout status deployment/keycloak-filestore --timeout=600s

# Smoke check: the imported demo realm must answer, and the web-app client must authenticate
# with the secret that lives only in the Kubernetes Secret (resolved by filestore from the env).
echo "Smoke check: demo realm and web-app client credentials..."
${KUBECTL} -n keycloak port-forward svc/keycloak-filestore 18081:8080 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "${PF_PID}" 2>/dev/null || true' EXIT
STATUS=000
for _ in $(seq 1 30); do
  STATUS=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:18081/realms/demo || true)
  [ "${STATUS}" = "200" ] && break
  sleep 2
done
if [ "${STATUS}" != "200" ]; then
  echo "Smoke check FAILED: /realms/demo returned HTTP ${STATUS}" >&2
  exit 1
fi
SECRET_VALUE=$(${KUBECTL} -n keycloak get secret keycloak-demo-secrets -o jsonpath='{.data.CLIENT_SECRET}' | base64 -d)
GRANT=$(curl -s -o /dev/null -w '%{http_code}' \
  -d 'client_id=web-app' -d "client_secret=${SECRET_VALUE}" -d 'grant_type=client_credentials' \
  http://localhost:18081/realms/demo/protocol/openid-connect/token || true)
kill "${PF_PID}" 2>/dev/null || true
trap - EXIT
if [ "${GRANT}" != "200" ]; then
  echo "Smoke check FAILED: web-app client_credentials grant returned HTTP ${GRANT}" >&2
  exit 1
fi
echo "Smoke check OK: demo realm serves and the web-app secret resolves from the Kubernetes Secret"

cat <<'EOF'

Filestore Keycloak is running. Useful commands:
  Reach it on the host (the default kind cluster publishes the NodePort):
    http://localhost:8081  (admin console; bootstrap admin/admin)
  On a cluster created with KIND_PUBLISH_KEYCLOAK_PORTS=0, port-forward instead:
    kubectl -n keycloak port-forward svc/keycloak-filestore 8081:8080
  Look at the filestore files (note the verbatim ${...} placeholders):
    kubectl -n keycloak exec deploy/keycloak-filestore -- cat /opt/keycloak/data/filestore/demo/clients/web-app.yaml
  Migrate it to k8store CRs:
    scripts/migrate-filestore.sh
EOF
