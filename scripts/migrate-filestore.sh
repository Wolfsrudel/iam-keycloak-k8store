#!/usr/bin/env bash
# Copyright 2026 Dominik Schlosser
# SPDX-License-Identifier: Apache-2.0
#
# Migrates the running filestore Keycloak (scripts/deploy-filestore.sh) to k8store CR YAML,
# entirely locally:
#   1. copies the filestore files out of the pod
#   2. runs `kc.sh export` in a local container with the placeholder env vars UNSET, so the
#      ${VAR} placeholders survive verbatim into the export
#   3. runs migration-tools on the export, translating the placeholders into valuesFrom
#      Secret/ConfigMap references by inspecting the filestore deployment's environment
#
# The CRs land in ./migrated-crs (or the directory given as first argument) together with a
# migration-report.txt. Nothing is applied to the cluster; review, commit, then
#   kubectl -n keycloak apply -R -f migrated-crs/
#
# Usage: scripts/migrate-filestore.sh [output-dir] [--no-references]
set -euo pipefail
cd "$(dirname "$0")/.."

KUBECTL="kubectl --context kind-k8store"
IMAGE=localhost:5001/keycloak-filestore:dev
NS=keycloak
DEPLOYMENT=keycloak-filestore
FILES_DIR=/opt/keycloak/data/filestore

OUT_DIR=migrated-crs
MIGRATE_REFERENCES=true
while [ $# -gt 0 ]; do
  case "$1" in
    --no-references) MIGRATE_REFERENCES=false; shift ;;
    -*) echo "Unknown argument: $1" >&2; exit 1 ;;
    *) OUT_DIR="$1"; shift ;;
  esac
done

WORK_DIR=$(mktemp -d)
trap 'rm -rf "${WORK_DIR}"' EXIT
mkdir -p "${WORK_DIR}/files" "${WORK_DIR}/export"

# 1. Filestore files out of the pod. kubectl cp needs tar, which the Keycloak image does not
#    ship, so list the files and cat them out one by one.
echo "Copying filestore files from ${NS}/deploy/${DEPLOYMENT}..."
POD=$(${KUBECTL} -n "${NS}" get pods -l app=keycloak-filestore --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')
FILE_LIST=$(${KUBECTL} -n "${NS}" exec "${POD}" -- bash -c \
  "cd '${FILES_DIR}' && shopt -s globstar nullglob && printf '%s\n' **/*.yaml")
FILE_COUNT=0
while IFS= read -r FILE; do
  [ -n "${FILE}" ] || continue
  mkdir -p "${WORK_DIR}/files/$(dirname "${FILE}")"
  ${KUBECTL} -n "${NS}" exec "${POD}" -- cat "${FILES_DIR}/${FILE}" > "${WORK_DIR}/files/${FILE}"
  FILE_COUNT=$((FILE_COUNT + 1))
done <<< "${FILE_LIST}"
if [ "${FILE_COUNT}" = "0" ]; then
  echo "No filestore files found in ${POD}:${FILES_DIR}" >&2
  exit 1
fi
echo "  ${FILE_COUNT} files"

# 2. Local export with the placeholder env vars unset (docker run inherits none of the pod env,
#    so filestore serves the ${...} placeholders verbatim and the export preserves them)
echo "Running kc.sh export locally (placeholders survive)..."
docker run --rm \
  -v "${WORK_DIR}/files":/files \
  -v "${WORK_DIR}/export":/export \
  "${IMAGE}" export --dir /export --users skip \
  --features=stateless \
  --spi-datastore--provider=file \
  --spi-map-storage--file--dir=/files \
  --features-disabled=authorization,admin-fine-grained-authz,organization \
  >"${WORK_DIR}/export.log" 2>&1 || { cat "${WORK_DIR}/export.log" >&2; exit 1; }
ls "${WORK_DIR}/export/"*-realm.json >/dev/null 2>&1 || { cat "${WORK_DIR}/export.log" >&2; exit 1; }

# 3. Convert the export into CRs
find_jar() {
  ls migration-tools/target/keycloak-k8store-migration-tools-*.jar 2>/dev/null \
    | grep -vE 'original-|-javadoc|-sources' | head -1 || true
}
JAR=$(find_jar)
if [ -z "${JAR}" ]; then
  echo "Building migration-tools..."
  mvn -q -pl migration-tools -am -DskipTests package
  JAR=$(find_jar)
fi

rm -rf "${OUT_DIR}"
REF_ARGS=()
if [ "${MIGRATE_REFERENCES}" = true ]; then
  REF_ARGS=(--migrate-references --namespace "${NS}" --deployment "${DEPLOYMENT}")
fi
java -jar "${JAR}" "${WORK_DIR}/export" "${OUT_DIR}" "${REF_ARGS[@]}"

cat <<EOF

Migration done. Next steps:
  1. Review ${OUT_DIR}/migration-report.txt and the generated CRs.
  2. Cut over (stops filestore, applies the CRs, deploys k8store read-only):
       scripts/switch-to-k8store.sh ${OUT_DIR}
EOF
