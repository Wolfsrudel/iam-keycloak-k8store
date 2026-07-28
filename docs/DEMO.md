# Demo walkthroughs

Two self-contained demos on a local kind cluster. Both start from a fresh cluster:

```sh
scripts/kind-down.sh   # if one is running
scripts/kind-up.sh
```

## Demo 1: k8store with a Secret-backed client

Shows the GitOps read path: a committed `KeycloakClient` CR that holds only a `${client-secret}`
placeholder, with the real value pulled from a Kubernetes Secret on read.

```sh
# 1. First deploy in write mode - Keycloak bootstraps the master realm into CRs
scripts/deploy.sh

# 2. The sample: a Secret + ConfigMap and a client whose secret/URLs reference them
kubectl --context kind-k8store -n keycloak apply -f examples/references/

# 3. Switch to the GitOps posture: read-only, references resolved on read
scripts/deploy.sh --read-only true --resolve-references true
```

Verify (the default kind cluster publishes Keycloak on http://localhost:8080):

```sh
# The committed CR never contains the secret ...
kubectl --context kind-k8store -n keycloak get keycloakclient master.reference-example \
  -o jsonpath='{.spec.secret}'                      # -> ${client-secret}

# ... but the client authenticates with the value from the Kubernetes Secret. Its
# service-account user is materialized by the store (Keycloak itself only creates service
# accounts on its own client-write paths, which a CR-authored client never runs).
curl -d 'client_id=reference-example' -d 'client_secret=super-secret-value' \
  -d 'grant_type=client_credentials' \
  http://localhost:8080/realms/master/protocol/openid-connect/token
```

The admin console (http://localhost:8080, admin/admin) works too; config edits are rejected -
that is the read-only mode doing its job. Editing means changing the CRs.

## Demo 2: filestore -> migrate -> k8store

Migrates a running [keycloak-extension-filestore](https://github.com/opdt/keycloak-extension-filestore)
instance to k8store CRs. The filestore instance keeps its config in YAML files with `${VAR}`
placeholders fed from a Kubernetes Secret/ConfigMap through the environment; users and sessions
(including the demo client's service account) live in postgres. The migration swaps only the
config store - the database is untouched, so every user keeps working.

```sh
# 1. The "existing installation": Keycloak + filestore, demo realm seeded with placeholders
scripts/deploy-filestore.sh
```

Show what it is (optional):

```sh
# the filestore files hold verbatim placeholders ...
kubectl --context kind-k8store -n keycloak exec deploy/keycloak-filestore -- \
  cat /opt/keycloak/data/filestore/demo/clients/web-app.yaml
# ... resolved at runtime from the env (Secret/ConfigMap); the instance is on localhost:8081
curl -d 'client_id=web-app' -d 'client_secret=demo-client-secret-value' \
  -d 'grant_type=client_credentials' \
  http://localhost:8081/realms/demo/protocol/openid-connect/token
```

```sh
# 2. Migrate: copies the files out, exports with placeholders preserved, converts to CRs and
#    translates the placeholders into valuesFrom references by inspecting the deployment env.
#    Output: ./migrated-crs + migration-report.txt. Nothing is applied yet.
scripts/migrate-filestore.sh

# 3. Review migrated-crs/migration-report.txt, then cut over: stops filestore, applies the CRs,
#    deploys k8store read-only with reference resolution, and smoke-checks the migrated client
scripts/switch-to-k8store.sh
```

The master realm CR comes from the migration, so the read-only first boot works; Keycloak's
bootstrap only has to create the admin user in the database (`admin`/`admin` - already present
here, since filestore bootstrapped it into the same postgres).

Verify the same client against the migrated instance (now on http://localhost:8080):

```sh
# served from CRs now - same secret, resolved from the same Kubernetes Secret, and the service
# account carried over through the shared database
curl -d 'client_id=web-app' -d 'client_secret=demo-client-secret-value' \
  -d 'grant_type=client_credentials' \
  http://localhost:8080/realms/demo/protocol/openid-connect/token

# the CR keeps the placeholder; the reference points at the Secret
kubectl --context kind-k8store -n keycloak get keycloakclient demo.web-app -o yaml \
  | grep -A8 valuesFrom
```

From here the `migrated-crs/` directory is what you would commit: the CRs are the config,
`kubectl apply` is the deployment, and the Secret/ConfigMap stay the single place holding real
values.

## Notes

- The default kind cluster publishes the instances on the host: k8store Keycloak on
  http://localhost:8080 (management on :9000), the filestore demo on http://localhost:8081. On a
  cluster created with `KIND_PUBLISH_KEYCLOAK_PORTS=0` (the integration-test setup), port-forward
  the services instead.
- Run each demo on a fresh cluster. In particular `deploy-filestore.sh` must come before any
  k8store deploy: both bootstrap the admin user into the same postgres and collide otherwise.
- The filestore demo re-seeds its realm files on every pod restart (init container), so console
  edits on the filestore side do not survive a restart.
- `migration-report.txt` lists every migrated reference and everything left verbatim -
  Keycloak's own `${role_...}`/`${client_...}` tokens are supposed to stay placeholders.
