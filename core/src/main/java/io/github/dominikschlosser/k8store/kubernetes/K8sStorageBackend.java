/*
 * Copyright 2026 Dominik Schlosser
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dominikschlosser.k8store.kubernetes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import io.github.dominikschlosser.k8store.crd.AuthSessionSpec;
import io.github.dominikschlosser.k8store.crd.AuthzPolicySpec;
import io.github.dominikschlosser.k8store.crd.AuthzResourceSpec;
import io.github.dominikschlosser.k8store.crd.AuthzScopeSpec;
import io.github.dominikschlosser.k8store.crd.ClientScopeSpec;
import io.github.dominikschlosser.k8store.crd.ClientSpec;
import io.github.dominikschlosser.k8store.crd.GroupSpec;
import io.github.dominikschlosser.k8store.crd.IssuedVerifiableCredentialSpec;
import io.github.dominikschlosser.k8store.crd.LoginFailureSpec;
import io.github.dominikschlosser.k8store.crd.OrganizationInvitationSpec;
import io.github.dominikschlosser.k8store.crd.OrganizationSpec;
import io.github.dominikschlosser.k8store.crd.PermissionTicketSpec;
import io.github.dominikschlosser.k8store.crd.RealmSpec;
import io.github.dominikschlosser.k8store.crd.ResourceServerSpec;
import io.github.dominikschlosser.k8store.crd.RevokedTokenSpec;
import io.github.dominikschlosser.k8store.crd.RoleSpec;
import io.github.dominikschlosser.k8store.crd.SingleUseObjectSpec;
import io.github.dominikschlosser.k8store.crd.UserSessionSpec;
import io.github.dominikschlosser.k8store.crd.UserSpec;
import io.github.dominikschlosser.k8store.crd.UserVerifiableCredentialSpec;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import io.github.dominikschlosser.k8store.kubernetes.crd.K8sCrd;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakAuthSessionCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakAuthzPolicyCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakAuthzResourceCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakAuthzScopeCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientScopeCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakGroupCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakIssuedVerifiableCredentialCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakLoginFailureCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakOrganizationCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakOrganizationInvitationCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakPermissionTicketCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakResourceServerCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRevokedTokenCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRoleCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakSingleUseObjectCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserSessionCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserVerifiableCredentialCr;
import io.github.dominikschlosser.k8store.kubernetes.references.ConfigMapMirror;
import io.github.dominikschlosser.k8store.kubernetes.references.SecretMirror;
import io.github.dominikschlosser.k8store.kubernetes.references.ValueReferenceResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.keycloak.common.Profile;
import org.keycloak.common.Version;
import org.keycloak.common.util.Time;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.storage.ReadOnlyException;
import org.keycloak.utils.KeycloakSessionUtil;

/**
 * The Kubernetes side of the k8store datastore.
 *
 * <p>Runs one informer per CRD kind and mirrors all custom resources into in-memory maps indexed
 * by {@code (realmId, id)}. All model reads are served from these maps - there is no API-server
 * round trip on the hot path, and Kubernetes watch events keep every Keycloak node's mirror in
 * sync with out-of-band CR changes.
 *
 * <p>Writes (only permitted when {@code read-only=false}) become visible in the local mirror
 * immediately, so a transaction reads its own writes before any watch event - but the
 * API-server calls are buffered per {@code KeycloakSession} and flushed once per
 * {@code (kind, realm, id)} key in the transaction's <em>prepare</em> phase, which runs before
 * the database commit: a server-side-apply failure fails the request and rolls the database
 * back. On rollback the buffer is discarded and the affected mirror entries are re-read from
 * the API server, so mirror divergence does not outlive the rollback. Writes on threads without
 * a resolvable session or active transaction (boot-time paths; never the informer threads) fall
 * back to the previous behavior and are applied to the cluster immediately. Every CR written by
 * Keycloak is stamped with the {@linkplain #VERSION_LABEL running Keycloak version}; at startup
 * the backend warns about CRs stamped by a different version - a drift/migration signal after
 * Keycloak upgrades.
 */
public final class K8sStorageBackend implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(K8sStorageBackend.class);

    /** Informational label put on CRs written by Keycloak; not used for reads. */
    public static final String REALM_LABEL = "k8store.dominikschlosser.github.io/realm";

    /**
     * Label stamped on every CR written by Keycloak with the writing server's version. At boot
     * the backend logs a warning for CRs whose stamp differs from the running version, so config
     * predating a Keycloak upgrade is visible instead of silently served (model migrations do
     * not run against CR-backed areas).
     */
    public static final String VERSION_LABEL = "k8store.dominikschlosser.github.io/keycloak-version";

    public static final String FIELD_MANAGER = "keycloak-k8store";

    /**
     * Pseudo-realm under which kinds without a realm scope (single-use objects, revoked tokens)
     * are indexed. The {@code @} prefix keeps it out of the namespace of real realm names.
     */
    public static final String GLOBAL_PSEUDO_REALM = "@global";

    private static volatile K8sStorageBackend instance;

    /**
     * Mapper for defensive copies of mirror entries and for the mirror's own copies of written
     * specs. Not the fabric8 client mapper: copies must work with externally supplied clients
     * (tests) too, and must tolerate unknown properties from newer schema generations.
     */
    private static final ObjectMapper COPY_MAPPER = configureMapper(new ObjectMapper());

    private final KubernetesClient client;
    private final boolean closeClient;
    private final K8sStoreConfig config;
    private final String writeNamespace;
    private final Map<Class<?>, KindState<?, ?>> kinds = new HashMap<>();
    private final List<SharedIndexInformer<?>> informers = new ArrayList<>();
    private ScheduledExecutorService reconciler;

    /**
     * Notified with a defensive copy of every client spec that lands in the mirror (watch adds,
     * updates and reconcile repairs). Registered after {@link #start()} has synced the informers,
     * so the initial inventory does not pass through it - consumers sweep the mirror themselves
     * when they come up (the service-account materialization does so on the post-migration
     * event). Runs on the informer thread: implementations must be quick and must not throw.
     */
    private volatile Consumer<ClientSpec> clientSpecListener;

    public void setClientSpecListener(Consumer<ClientSpec> listener) {
        this.clientSpecListener = listener;
    }

    /**
     * Reference resolution on the read path, or {@code null} when {@code resolve-references} is
     * off. When set, every read-out copy has its {@code valuesFrom} references resolved, and
     * {@link #secretMirror}/{@link #configMapMirror} watch the Secrets and ConfigMaps they name.
     */
    private final ValueReferenceResolver resolver;

    private final SecretMirror secretMirror;

    private final ConfigMapMirror configMapMirror;

    private K8sStorageBackend(KubernetesClient client, boolean closeClient, K8sStoreConfig config) {
        this.client = client;
        this.closeClient = closeClient;
        this.config = config;
        String ns = config.getNamespace() != null ? config.getNamespace() : client.getNamespace();
        this.writeNamespace = ns != null ? ns : "default";
        if (config.isResolveReferences()) {
            this.secretMirror = new SecretMirror(client, writeNamespace);
            this.configMapMirror = new ConfigMapMirror(client, writeNamespace);
            this.resolver = new ValueReferenceResolver(secretMirror, configMapMirror);
        } else {
            this.secretMirror = null;
            this.configMapMirror = null;
            this.resolver = null;
        }

        // the realm kind's id (spec.realm, the realm name) doubles as its realm id, so it has
        // no separate realm setter - a realm CR without spec.realm falls back to metadata.name,
        // never to labels. Clients are keyed by clientId, scopes by name - both human-readable
        // and defaulting to metadata.name in hand-authored CRs.
        register(new KindState<>(
                KeycloakRealmCr.class,
                KeycloakRealmCr::new,
                RealmSpec.class,
                RealmSpec::getRealm,
                RealmSpec::setRealm,
                RealmSpec::getRealm,
                null));
        register(new KindState<>(
                KeycloakClientCr.class,
                KeycloakClientCr::new,
                ClientSpec.class,
                ClientSpec::getClientId,
                ClientSpec::setClientId,
                ClientSpec::getRealm,
                ClientSpec::setRealm));
        register(new KindState<>(
                KeycloakClientScopeCr.class,
                KeycloakClientScopeCr::new,
                ClientScopeSpec.class,
                ClientScopeSpec::getName,
                ClientScopeSpec::setName,
                ClientScopeSpec::getRealm,
                ClientScopeSpec::setRealm));
        register(new KindState<>(
                KeycloakRoleCr.class,
                KeycloakRoleCr::new,
                RoleSpec.class,
                RoleSpec::getId,
                RoleSpec::setId,
                RoleSpec::getRealm,
                RoleSpec::setRealm));
        register(new KindState<>(
                KeycloakGroupCr.class,
                KeycloakGroupCr::new,
                GroupSpec.class,
                GroupSpec::getId,
                GroupSpec::setId,
                GroupSpec::getRealm,
                GroupSpec::setRealm));

        // the authorization area is opt-in (config-class but not part of the config default):
        // its kinds are registered only when the area is enabled, so default deployments do not
        // need the authorization CRDs. Resource servers, resources, scopes and policies honor
        // read-only mode (they are realm configuration); permission tickets are UMA runtime data
        // and stay writable like the dynamic kinds. None of these entities expire.
        if (config.getAreas().contains(Area.AUTHORIZATION)) {
            register(new KindState<>(
                    KeycloakResourceServerCr.class,
                    KeycloakResourceServerCr::new,
                    ResourceServerSpec.class,
                    ResourceServerSpec::getClientId,
                    ResourceServerSpec::setClientId,
                    ResourceServerSpec::getRealm,
                    ResourceServerSpec::setRealm));
            register(new KindState<>(
                    KeycloakAuthzResourceCr.class,
                    KeycloakAuthzResourceCr::new,
                    AuthzResourceSpec.class,
                    AuthzResourceSpec::getId,
                    AuthzResourceSpec::setId,
                    AuthzResourceSpec::getRealm,
                    AuthzResourceSpec::setRealm));
            register(new KindState<>(
                    KeycloakAuthzScopeCr.class,
                    KeycloakAuthzScopeCr::new,
                    AuthzScopeSpec.class,
                    AuthzScopeSpec::getId,
                    AuthzScopeSpec::setId,
                    AuthzScopeSpec::getRealm,
                    AuthzScopeSpec::setRealm));
            register(new KindState<>(
                    KeycloakAuthzPolicyCr.class,
                    KeycloakAuthzPolicyCr::new,
                    AuthzPolicySpec.class,
                    AuthzPolicySpec::getId,
                    AuthzPolicySpec::setId,
                    AuthzPolicySpec::getRealm,
                    AuthzPolicySpec::setRealm));
            register(new KindState<>(
                    KeycloakPermissionTicketCr.class,
                    KeycloakPermissionTicketCr::new,
                    PermissionTicketSpec.class,
                    PermissionTicketSpec::getId,
                    PermissionTicketSpec::setId,
                    PermissionTicketSpec::getRealm,
                    PermissionTicketSpec::setRealm,
                    true,
                    null));
        }

        // the organization area is opt-in like authorization. The organization kind is
        // configuration (honors read-only mode); invitations are runtime data written by the
        // invitation flows and stay writable like the dynamic kinds. Expired invitations are
        // deliberately NOT wired into the expiry filtering/reaping - they remain listable with
        // the EXPIRED status filter, like upstream's database rows.
        if (config.getAreas().contains(Area.ORGANIZATION)) {
            register(new KindState<>(
                    KeycloakOrganizationCr.class,
                    KeycloakOrganizationCr::new,
                    OrganizationSpec.class,
                    OrganizationSpec::getId,
                    OrganizationSpec::setId,
                    OrganizationSpec::getRealm,
                    OrganizationSpec::setRealm));
            register(new KindState<>(
                    KeycloakOrganizationInvitationCr.class,
                    KeycloakOrganizationInvitationCr::new,
                    OrganizationInvitationSpec.class,
                    OrganizationInvitationSpec::getId,
                    OrganizationInvitationSpec::setId,
                    OrganizationInvitationSpec::getRealm,
                    OrganizationInvitationSpec::setRealm,
                    true,
                    null));
        }

        // dynamic kinds are registered ONLY when their area is enabled: a deployment on the
        // default (config-only) areas must boot without the session CRDs installed and without
        // a single new watch. Dynamic kinds are always writable (read-only mode guards config
        // CRs only - logins must work) and, where the entities expire, carry an expiresAt
        // accessor for read-path filtering and the background reaper.
        if (config.getAreas().contains(Area.USER)) {
            // users are low-churn but runtime-mutated (self-registration, credential updates,
            // lockout flags), so the kind is always writable; user entities never expire
            register(new KindState<>(
                    KeycloakUserCr.class,
                    KeycloakUserCr::new,
                    UserSpec.class,
                    UserSpec::getId,
                    UserSpec::setId,
                    UserSpec::getRealm,
                    UserSpec::setRealm,
                    true,
                    null));
            // the OID4VC verifiable-credential kinds only exist when the (experimental)
            // oid4vc-vci feature is on - user-area deployments without it need neither the
            // CRDs nor the watches. Issued credentials expire: reads filter them and the
            // background reaper deletes their CRs (upstream's scheduled cleanup task is not
            // registered under this datastore, the reaper is its replacement).
            if (oid4vcFeatureEnabled()) {
                register(new KindState<>(
                        KeycloakUserVerifiableCredentialCr.class,
                        KeycloakUserVerifiableCredentialCr::new,
                        UserVerifiableCredentialSpec.class,
                        UserVerifiableCredentialSpec::getId,
                        UserVerifiableCredentialSpec::setId,
                        UserVerifiableCredentialSpec::getRealm,
                        UserVerifiableCredentialSpec::setRealm,
                        true,
                        null));
                register(new KindState<>(
                        KeycloakIssuedVerifiableCredentialCr.class,
                        KeycloakIssuedVerifiableCredentialCr::new,
                        IssuedVerifiableCredentialSpec.class,
                        IssuedVerifiableCredentialSpec::getId,
                        IssuedVerifiableCredentialSpec::setId,
                        IssuedVerifiableCredentialSpec::getRealm,
                        IssuedVerifiableCredentialSpec::setRealm,
                        true,
                        IssuedVerifiableCredentialSpec::getExpiresAt));
            }
        }
        if (config.getAreas().contains(Area.USER_SESSION)) {
            register(new KindState<>(
                    KeycloakUserSessionCr.class,
                    KeycloakUserSessionCr::new,
                    UserSessionSpec.class,
                    UserSessionSpec::getId,
                    UserSessionSpec::setId,
                    UserSessionSpec::getRealm,
                    UserSessionSpec::setRealm,
                    true,
                    UserSessionSpec::getExpiresAt));
        }
        if (config.getAreas().contains(Area.AUTH_SESSION)) {
            register(new KindState<>(
                    KeycloakAuthSessionCr.class,
                    KeycloakAuthSessionCr::new,
                    AuthSessionSpec.class,
                    AuthSessionSpec::getId,
                    AuthSessionSpec::setId,
                    AuthSessionSpec::getRealm,
                    AuthSessionSpec::setRealm,
                    true,
                    AuthSessionSpec::getExpiresAt));
        }
        if (config.getAreas().contains(Area.LOGIN_FAILURE)) {
            register(new KindState<>(
                    KeycloakLoginFailureCr.class,
                    KeycloakLoginFailureCr::new,
                    LoginFailureSpec.class,
                    LoginFailureSpec::getUserId,
                    LoginFailureSpec::setUserId,
                    LoginFailureSpec::getRealm,
                    LoginFailureSpec::setRealm,
                    true,
                    null));
        }
        if (config.getAreas().contains(Area.SINGLE_USE_OBJECT)) {
            register(new KindState<>(
                    KeycloakSingleUseObjectCr.class,
                    KeycloakSingleUseObjectCr::new,
                    SingleUseObjectSpec.class,
                    SingleUseObjectSpec::getKey,
                    SingleUseObjectSpec::setKey,
                    spec -> GLOBAL_PSEUDO_REALM,
                    null,
                    true,
                    SingleUseObjectSpec::getExpiresAt));
        }
        if (config.getAreas().contains(Area.REVOKED_TOKEN)) {
            register(new KindState<>(
                    KeycloakRevokedTokenCr.class,
                    KeycloakRevokedTokenCr::new,
                    RevokedTokenSpec.class,
                    RevokedTokenSpec::getTokenId,
                    RevokedTokenSpec::setTokenId,
                    spec -> GLOBAL_PSEUDO_REALM,
                    null,
                    true,
                    RevokedTokenSpec::getExpiresAt));
        }
    }

    private void register(KindState<?, ?> state) {
        kinds.put(state.specClass, state);
    }

    /**
     * Null-safe feature check: at server runtime the profile is always initialized before the
     * datastore boots (the factory checks the stateless feature first); in unit tests without a
     * profile the feature counts as disabled - matching its experimental default.
     */
    private static boolean oid4vcFeatureEnabled() {
        return Profile.getInstance() != null && Profile.isFeatureEnabled(Profile.Feature.OID4VC_VCI);
    }

    /**
     * Drops {@code null} properties and {@code null} map values from serialized specs - a real
     * API server rejects explicit nulls in {@code map<string,string>} schema fields with 422.
     * The class-level {@code @JsonInclude} of the spec classes only covers properties they
     * declare themselves, not maps inherited from the representation superclasses, so the
     * write path enforces the rule at the mapper level. Server-side apply makes null-dropping
     * semantically correct: fields absent from an applied manifest are removed from the CR
     * anyway. Unknown properties are ignored on read so newer schema generations do not break
     * older nodes. Package-visible for tests.
     */
    static ObjectMapper configureMapper(ObjectMapper mapper) {
        mapper.setDefaultPropertyInclusion(
                JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Returns the started singleton, initializing it from Keycloak config on first access. */
    public static K8sStorageBackend get() {
        K8sStorageBackend backend = instance;
        if (backend == null) {
            synchronized (K8sStorageBackend.class) {
                if (instance == null) {
                    K8sStoreConfig config = K8sStoreConfig.get();
                    K8sStorageBackend created = new K8sStorageBackend(buildClient(config), true, config);
                    created.start();
                    instance = created;
                }
                backend = instance;
            }
        }
        return backend;
    }

    private static KubernetesClient buildClient(K8sStoreConfig config) {
        KubernetesClientBuilder builder =
                new KubernetesClientBuilder().withKubernetesSerialization(buildSerialization());
        if (config.getContext() != null) {
            builder.withConfig(Config.autoConfigure(config.getContext()));
        }
        return builder.build();
    }

    /**
     * The serialization of the backend's own client. fabric8's {@code configureMapper} resets
     * the inclusion default to <em>keep</em> null map values ("omit null fields, but keep null
     * map values"), so {@link #configureMapper} must be re-applied on top - a real API server
     * rejects explicit nulls in {@code map<string,string>} schema fields with 422 (e.g. the
     * "roles" scope's protocol mapper carrying {@code "rolePrefix": null} at bootstrap).
     * Package-visible for tests.
     */
    static KubernetesSerialization buildSerialization() {
        return new KubernetesSerialization(new ObjectMapper(), true) {
            @Override
            protected void configureMapper(ObjectMapper mapper) {
                super.configureMapper(mapper);
                K8sStorageBackend.configureMapper(mapper);
            }
        };
    }

    /** Initializes the backend with an externally managed client (tests). */
    public static K8sStorageBackend initWithClient(KubernetesClient client, K8sStoreConfig config) {
        synchronized (K8sStorageBackend.class) {
            shutdown();
            K8sStorageBackend created = new K8sStorageBackend(client, false, config);
            created.start();
            instance = created;
            return created;
        }
    }

    /** The running backend, or {@code null} when not started. */
    static K8sStorageBackend runningInstance() {
        return instance;
    }

    public static void shutdown() {
        synchronized (K8sStorageBackend.class) {
            if (instance != null) {
                instance.close();
                instance = null;
            }
        }
    }

    private void start() {
        for (KindState<?, ?> state : kinds.values()) {
            informers.add(state.startInformer());
        }
        // the Secret and ConfigMap watches feed valuesFrom resolution. They are awaited alongside
        // the kind informers below so references resolve from a fully synced cache on the first read
        if (secretMirror != null) {
            informers.add(secretMirror.start());
            informers.add(configMapMirror.start());
        }
        long deadline = System.currentTimeMillis() + config.getSyncTimeoutSeconds() * 1000L;
        for (SharedIndexInformer<?> informer : informers) {
            while (!informer.hasSynced()) {
                if (!informer.isRunning() || System.currentTimeMillis() > deadline) {
                    close();
                    throw new IllegalStateException("k8store: informer for "
                            + informer.getApiTypeClass().getSimpleName()
                            + " did not sync within " + config.getSyncTimeoutSeconds()
                            + "s. Does the service account have list/watch permissions on it (for "
                            + K8sCrd.GROUP + " kinds, are the CRDs installed; for Secrets and"
                            + " ConfigMaps, is resolve-references paired with their RBAC)?");
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for k8store informers to sync", e);
                }
            }
        }
        warnAboutVersionDrift();
        if (resolver != null && config.isValidateReferences()) {
            validateReferencesOrFail();
        }
        boolean anyExpiring = kinds.values().stream().anyMatch(state -> state.expiresAtFn != null);
        if (config.getReconcileIntervalSeconds() > 0 || (anyExpiring && config.getExpirationSweepSeconds() > 0)) {
            reconciler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "k8store-reconcile");
                thread.setDaemon(true);
                return thread;
            });
            if (config.getReconcileIntervalSeconds() > 0) {
                reconciler.scheduleWithFixedDelay(
                        this::reconcileAll,
                        config.getReconcileIntervalSeconds(),
                        config.getReconcileIntervalSeconds(),
                        TimeUnit.SECONDS);
            }
            if (anyExpiring && config.getExpirationSweepSeconds() > 0) {
                reconciler.scheduleWithFixedDelay(
                        this::sweepExpiredGuarded,
                        config.getExpirationSweepSeconds(),
                        config.getExpirationSweepSeconds(),
                        TimeUnit.SECONDS);
            }
        }
        LOG.infov(
                "k8store storage backend started (namespace={0}, readOnly={1}, areas={2}, watching kinds: {3})",
                config.isAllNamespaces() ? "<all>" : writeNamespace,
                config.isReadOnly(),
                config.getAreas(),
                kinds.values().stream()
                        // strip only the "Cr" class-name suffix (kind names may contain "Cr",
                        // e.g. KeycloakUserVerifiableCredentialCr)
                        .map(state -> state.crClass
                                .getSimpleName()
                                .substring(0, state.crClass.getSimpleName().length() - 2))
                        .sorted()
                        .collect(Collectors.joining(", ")));
    }

    /**
     * Validates the {@code valuesFrom} references of every config CR against the synced Secret and
     * ConfigMap mirrors, and fails the boot with an aggregated report when any is invalid. Runs once
     * at startup when {@code validate-references} is on, so misconfiguration surfaces up front
     * instead of being served as an unresolved placeholder. Only config kinds are checked (the
     * always-writable runtime kinds carry no references).
     */
    private void validateReferencesOrFail() {
        List<String> problems = new ArrayList<>();
        for (KindState<?, ?> state : kinds.values()) {
            if (!state.alwaysWritable) {
                collectReferenceProblems(state, problems);
            }
        }
        if (!problems.isEmpty()) {
            String report = problems.stream().collect(Collectors.joining("\n  - ", "  - ", ""));
            close();
            throw new IllegalStateException("k8store: validate-references found " + problems.size()
                    + " invalid valuesFrom reference(s) at boot:\n" + report
                    + "\nFix the referenced Secrets/ConfigMaps or the custom resources, or disable"
                    + " --spi-datastore--k8store--validate-references.");
        }
    }

    private <S> void collectReferenceProblems(KindState<S, ?> state, List<String> problems) {
        for (Map<String, S> realm : state.byRealm.values()) {
            for (S spec : realm.values()) {
                if (state.isExpired(spec)) {
                    continue;
                }
                for (String problem : resolver.validate(COPY_MAPPER.valueToTree(spec))) {
                    problems.add(state.crClass.getSimpleName() + " " + state.realmFn.apply(spec) + "/"
                            + state.idFn.apply(spec) + ": " + problem);
                }
            }
        }
    }

    private void sweepExpiredGuarded() {
        try {
            sweepExpired();
        } catch (RuntimeException e) {
            LOG.debugv(e, "k8store expiration sweep failed; next run in {0}s", config.getExpirationSweepSeconds());
        }
    }

    /**
     * Background reaper of the dynamic kinds: deletes every mirrored CR whose expiration
     * timestamp has passed. Reads filter expired entities anyway, so this only keeps the
     * cluster free of session/token garbage; deletion failures are retried implicitly on the
     * next sweep. Package-visible so tests can trigger a sweep deterministically.
     */
    void sweepExpired() {
        for (KindState<?, ?> state : kinds.values()) {
            if (state.expiresAtFn != null) {
                state.sweepExpired();
            }
        }
    }

    /**
     * Logs one warning listing every CR whose {@link #VERSION_LABEL} stamp differs from the
     * running Keycloak version. Such CRs were written by (or for) another Keycloak version and
     * survived the upgrade unmigrated - model migrations do not run against CR-backed areas, so
     * this is the operator's signal to review the upstream migration notes and refresh the CRs.
     * CRs without a stamp (hand-authored / GitOps) are not reported.
     */
    private void warnAboutVersionDrift() {
        List<String> drifted = detectVersionDrift();
        if (!drifted.isEmpty()) {
            LOG.warnv(
                    "k8store: {0} custom resource(s) carry a {1} stamp different from the running Keycloak"
                            + " version {2}. Model migrations do not run against CR-backed config - review the"
                            + " Keycloak migration notes and refresh these CRs: {3}",
                    drifted.size(), VERSION_LABEL, Version.VERSION, String.join(", ", drifted));
        }
    }

    /**
     * Identifiers of every mirrored CR whose {@link #VERSION_LABEL} stamp differs from the running
     * Keycloak version - the drift signal the boot warning reports. CRs without a stamp
     * (hand-authored / GitOps) are not drifted. Package-visible so a test can assert the detection
     * without scraping logs.
     */
    List<String> detectVersionDrift() {
        String runningStamp = sanitizeLabel(Version.VERSION);
        List<String> drifted = new ArrayList<>();
        for (SharedIndexInformer<?> informer : informers) {
            for (Object item : informer.getStore().list()) {
                HasMetadata resource = (HasMetadata) item;
                Map<String, String> labels = resource.getMetadata().getLabels();
                String stamp = labels == null ? null : labels.get(VERSION_LABEL);
                if (stamp != null && !stamp.equals(runningStamp)) {
                    drifted.add(
                            resource.getKind() + "/" + resource.getMetadata().getNamespace() + "/"
                                    + resource.getMetadata().getName() + " (written by " + stamp + ")");
                }
            }
        }
        return drifted;
    }

    /**
     * Safety net against watch connections that silently stop delivering events (observed with kind: a watch
     * stays established but stops delivering events for minutes): periodically list every kind
     * and reconcile the mirror. With healthy watches this is a no-op diff.
     */
    private void reconcileAll() {
        for (KindState<?, ?> state : kinds.values()) {
            try {
                state.reconcile();
            } catch (RuntimeException e) {
                // a failing reconcile is operationally significant (the mirror can drift from the
                // cluster until it recovers), so warn rather than hide it at debug
                LOG.warnv(
                        e,
                        "k8store mirror reconciliation for {0} failed; next run in {1}s",
                        state.specClass.getSimpleName(),
                        config.getReconcileIntervalSeconds());
            }
        }
    }

    /** Test hook: runs one reconcile pass synchronously. */
    void reconcileNow() {
        reconcileAll();
    }

    @Override
    public void close() {
        if (reconciler != null) {
            reconciler.shutdownNow();
            reconciler = null;
        }
        informers.forEach(SharedIndexInformer::close);
        informers.clear();
        if (closeClient) {
            client.close();
        }
        kinds.values().forEach(KindState::clear);
    }

    // ------------------------------------------------------------------ reads

    @SuppressWarnings("unchecked")
    private <S> KindState<S, ?> state(Class<S> specClass) {
        KindState<S, ?> state = (KindState<S, ?>) kinds.get(specClass);
        if (state == null) {
            throw new IllegalArgumentException("Unknown k8store spec type " + specClass.getName());
        }
        return state;
    }

    public <S> S read(Class<S> specClass, String realmId, String id) {
        if (realmId == null || id == null) {
            return null;
        }
        KindState<S, ?> state = state(specClass);
        Map<String, S> realm = state.byRealm.get(realmId);
        S spec = realm == null ? null : realm.get(id);
        if (spec == null || state.isExpired(spec)) {
            return null;
        }
        return copyOf(state, spec);
    }

    /**
     * Like {@link #read}, but on a mirror miss falls back to a direct API-server GET by the
     * deterministic CR name (and feeds a hit back into the mirror). Used by the single-use and
     * revoked-token stores, whose entries written on another node must be visible before the
     * watch event arrives - a mirror-only read could miss a fresh cross-node write for a few
     * milliseconds, which for single-use semantics is the difference between correct and broken.
     */
    public <S> S fetch(Class<S> specClass, String realmId, String id) {
        S mirrored = read(specClass, realmId, id);
        if (mirrored != null) {
            return mirrored;
        }
        if (realmId == null || id == null) {
            return null;
        }
        KindState<S, ?> state = state(specClass);
        try {
            state.refreshFromServer(realmId, id);
        } catch (RuntimeException e) {
            LOG.debugv(
                    e,
                    "k8store: direct read of {0} {1}/{2} from the API server failed",
                    specClass.getSimpleName(),
                    realmId,
                    id);
        }
        return read(specClass, realmId, id);
    }

    /**
     * Defensive copy of a mirror entry. Handing out the live instance would let a request
     * thread mutate what every other session on this node reads - including mutations that the
     * API server (or read-only mode) later rejects, silently corrupting the mirror.
     *
     * <p>When reference resolution is on, the copy also has its {@code valuesFrom} references
     * resolved. Resolution happens only here on the read-out path, so the mirror keeps the raw
     * placeholders and a write serializes them back verbatim. Secrets are never stored in the
     * config-CR mirror in clear.
     *
     * <p>Only config kinds are resolved. The always-writable runtime kinds (users, sessions,
     * single-use objects, ...) are machine-written data that never carries references, and
     * resolving them would risk writing a resolved value back on their next update - a leak
     * read-only mode does not guard against, since those kinds stay writable in read-only mode.
     */
    private <S> S copyOf(KindState<S, ?> state, S spec) {
        if (spec == null) {
            return null;
        }
        if (resolver == null || state.alwaysWritable) {
            return COPY_MAPPER.convertValue(spec, state.specClass);
        }
        try {
            JsonNode tree = resolver.resolveTree(COPY_MAPPER.valueToTree(spec));
            return COPY_MAPPER.treeToValue(tree, state.specClass);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "k8store: resolving references while reading " + state.specClass.getSimpleName() + " failed", e);
        }
    }

    public <S> boolean exists(Class<S> specClass, String realmId, String id) {
        return read(specClass, realmId, id) != null;
    }

    public <S> List<S> readAll(Class<S> specClass) {
        KindState<S, ?> state = state(specClass);
        return liveCopies(state, state.byRealm.values().stream().flatMap(m -> m.values().stream()));
    }

    public <S> List<S> readAllInRealm(Class<S> specClass, String realmId) {
        KindState<S, ?> state = state(specClass);
        Map<String, S> realm = state.byRealm.get(realmId);
        return realm == null ? List.of() : liveCopies(state, realm.values().stream());
    }

    /** Drops expired entities and hands out a defensive copy of each survivor. */
    private <S> List<S> liveCopies(KindState<S, ?> state, Stream<S> specs) {
        return specs.filter(s -> !state.isExpired(s)).map(s -> copyOf(state, s)).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ writes

    /** Session attribute under which the transaction's {@link SessionWriteBuffer} is stored. */
    private static final String SESSION_BUFFER_ATTRIBUTE = "io.github.dominikschlosser.k8store.txWriteBuffer";

    /**
     * Write entry point of the providers. A no-op when no backend is running yet - a spec being
     * populated outside the store is not attached to any persistence context.
     *
     * <p>With a session on the thread, the mirror is updated immediately (read-your-write) and
     * the API-server apply is buffered until the transaction commits; without one, the apply
     * happens first, so a rejected write never reaches the mirror.
     */
    public static <S> S update(Class<S> specClass, String realmId, String id, S spec) {
        K8sStorageBackend backend = instance;
        if (backend == null) {
            return spec;
        }
        KindState<S, ?> state = backend.state(specClass);
        backend.checkWritable(state);
        SessionWriteBuffer buffer = backend.sessionBuffer();
        if (buffer != null) {
            buffer.recordUpdate(state, realmId, id, state.mirrorPut(realmId, id, spec));
        } else {
            state.applyToServer(realmId, id, spec);
            state.mirrorPut(realmId, id, spec);
        }
        return spec;
    }

    public static <S> void delete(Class<S> specClass, String realmId, String id) {
        K8sStorageBackend backend = instance;
        if (backend == null) {
            return;
        }
        KindState<S, ?> state = backend.state(specClass);
        backend.checkWritable(state);
        SessionWriteBuffer buffer = backend.sessionBuffer();
        if (buffer != null) {
            state.mirrorRemove(realmId, id);
            buffer.recordDelete(state, realmId, id);
        } else {
            state.deleteOnServer(realmId, id);
            state.mirrorRemove(realmId, id);
        }
    }

    /**
     * Unbuffered write: applies to the API server immediately, bypassing the transaction
     * buffer. Single-use objects and revoked tokens need this - their entries must be visible
     * to other requests (and other nodes) the moment the call returns, not at commit time.
     */
    public static <S> S updateNow(Class<S> specClass, String realmId, String id, S spec) {
        K8sStorageBackend backend = instance;
        if (backend == null) {
            return spec;
        }
        KindState<S, ?> state = backend.state(specClass);
        backend.checkWritable(state);
        state.applyToServer(realmId, id, spec);
        state.mirrorPut(realmId, id, spec);
        return spec;
    }

    /**
     * Unbuffered create-if-absent: relies on the API server's create semantics for atomicity -
     * exactly one of any number of concurrent creators (across all nodes) wins; the others get
     * a 409 and {@code false}. The put-if-absent primitive of the single-use-object and
     * revoked-token stores.
     */
    public static <S> boolean createNow(Class<S> specClass, String realmId, String id, S spec) {
        K8sStorageBackend backend = instance;
        if (backend == null) {
            return false;
        }
        KindState<S, ?> state = backend.state(specClass);
        backend.checkWritable(state);
        return state.createOnServer(realmId, id, spec);
    }

    /**
     * Unbuffered atomic put-if-absent that also reclaims an expired CR: creates the spec if the
     * key is free, and if an <em>expired</em> CR still holds the key, replaces it under optimistic
     * concurrency so exactly one concurrent caller wins. Returns {@code false} for a live entry or
     * a lost reclaim race. The single-use-object and revoked-token put-if-absent primitive.
     */
    public static <S> boolean putIfAbsentNow(Class<S> specClass, String realmId, String id, S spec) {
        K8sStorageBackend backend = instance;
        if (backend == null) {
            return false;
        }
        KindState<S, ?> state = backend.state(specClass);
        backend.checkWritable(state);
        return state.putIfAbsentOnServer(realmId, id, spec);
    }

    /**
     * Unbuffered delete reporting whether <em>this</em> call deleted the CR: Kubernetes DELETE
     * answers exactly once per object across all nodes, which is what gives
     * {@code SingleUseObjectProvider.remove} its single-consumer guarantee.
     */
    public static <S> boolean deleteNow(Class<S> specClass, String realmId, String id) {
        K8sStorageBackend backend = instance;
        if (backend == null) {
            return false;
        }
        KindState<S, ?> state = backend.state(specClass);
        backend.checkWritable(state);
        boolean deleted = state.deleteOnServerChecked(realmId, id);
        state.mirrorRemove(realmId, id);
        return deleted;
    }

    private void checkWritable(KindState<?, ?> state) {
        if (config.isReadOnly() && !state.alwaysWritable) {
            throw new ReadOnlyException(
                    "The k8store datastore is in read-only mode; modify the KeycloakRealm/KeycloakClient/... custom"
                            + " resources in the cluster instead, or start Keycloak with"
                            + " --spi-datastore--k8store--read-only=false");
        }
    }

    /**
     * The write buffer of the current transaction, created and enlisted on first use, or
     * {@code null} when writes cannot be buffered - no session on the thread, no active
     * transaction, or the transaction is already completing - and must be applied to the API
     * server immediately instead.
     */
    private SessionWriteBuffer sessionBuffer() {
        KeycloakSession session = KeycloakSessionUtil.getKeycloakSession();
        if (session == null) {
            return null;
        }
        KeycloakTransactionManager tm = session.getTransactionManager();
        if (tm == null || !tm.isActive()) {
            return null;
        }
        SessionWriteBuffer buffer = (SessionWriteBuffer) session.getAttribute(SESSION_BUFFER_ATTRIBUTE);
        if (buffer == null || buffer.isSpent()) {
            // no buffer yet, or the previous one already flushed/rolled back with its hooks
            // (a rolled-back transaction manager can be begun again): enlist a fresh one
            buffer = new SessionWriteBuffer();
            try {
                tm.enlistPrepare(buffer.new FlushOnCommit());
                tm.enlistAfterCompletion(buffer.new RepairOnRollback());
            } catch (IllegalStateException e) {
                // the transaction is already completing (a write from a completion listener):
                // too late to buffer, fall back to the immediate apply
                return null;
            }
            session.setAttribute(SESSION_BUFFER_ATTRIBUTE, buffer);
        }
        return buffer;
    }

    /**
     * The buffered CR writes of one Keycloak transaction. {@link #update} and {@link #delete}
     * change the mirror immediately; the API-server calls are deferred here, keeping only the
     * last write per {@code (kind, realm, id)} key (a delete recorded after an update wins, and
     * vice versa). Two hooks are enlisted with the buffer:
     *
     * <ul>
     *   <li>{@link FlushOnCommit} in the <em>prepare</em> phase, which Keycloak's transaction
     *       manager commits before the main (JPA) transactions: each buffered key is applied to
     *       the API server exactly once, and a failure rolls the whole request - including the
     *       database - back.</li>
     *   <li>{@link RepairOnRollback} in the after-completion phase, because the transaction
     *       manager never calls rollback on prepare-phase hooks: on rollback the buffer is
     *       discarded and every buffered key is re-read from the API server, so the optimistic
     *       mirror updates do not outlive the transaction.</li>
     * </ul>
     *
     * <p>Sessions are single-threaded, so the buffer needs no synchronization.
     */
    private static final class SessionWriteBuffer {

        /** Insertion-ordered last write per key; a {@code null} spec marks a delete. */
        private final Map<String, PendingWrite<?>> pending = new LinkedHashMap<>();

        private boolean flushed;
        private boolean rolledBack;

        /** A spent buffer's hooks already ran; it must not receive further writes. */
        boolean isSpent() {
            return flushed || rolledBack;
        }

        <S> void recordUpdate(KindState<S, ?> state, String realmId, String id, S spec) {
            state.pendingWriteKeys.add(key(realmId, id));
            pending.put(bufferKey(state, realmId, id), new PendingWrite<>(state, realmId, id, spec));
        }

        void recordDelete(KindState<?, ?> state, String realmId, String id) {
            state.pendingWriteKeys.add(key(realmId, id));
            pending.put(bufferKey(state, realmId, id), new PendingWrite<>(state, realmId, id, null));
        }

        private static String bufferKey(KindState<?, ?> state, String realmId, String id) {
            return state.specClass.getName() + KEY_SEPARATOR + key(realmId, id);
        }

        private void flush() {
            for (PendingWrite<?> write : pending.values()) {
                write.flush();
            }
            // reached only when every write applied; on a partial flush the buffer stays
            // intact so the rollback hook can repair all affected mirror entries
            pending.values().forEach(PendingWrite::clearPending);
            pending.clear();
            flushed = true;
        }

        private void discardAndRepair() {
            rolledBack = true;
            for (PendingWrite<?> write : pending.values()) {
                try {
                    write.repairMirror();
                } catch (RuntimeException e) {
                    LOG.warnv(
                            e,
                            "k8store: re-reading {0} after transaction rollback failed; the local mirror"
                                    + " may be stale until the next reconciliation",
                            write.describe());
                }
            }
            pending.values().forEach(PendingWrite::clearPending);
            pending.clear();
        }

        /** Prepare-phase hook: applies the buffered writes before the database commits. */
        final class FlushOnCommit extends AbstractKeycloakTransaction {
            @Override
            protected void commitImpl() {
                flush();
            }

            @Override
            protected void rollbackImpl() {
                // Keycloak's transaction manager does not roll back prepare-phase hooks
                // (RepairOnRollback covers that); kept for direct callers
                discardAndRepair();
            }
        }

        /** After-completion hook: undoes the optimistic mirror updates when the tx rolls back. */
        final class RepairOnRollback extends AbstractKeycloakTransaction {
            @Override
            protected void commitImpl() {
                // nothing to do: the writes were flushed in the prepare phase
            }

            @Override
            protected void rollbackImpl() {
                if (flushed) {
                    // the CR writes committed but a later (database) transaction failed; CRs
                    // cannot be un-applied - the mirror already matches the API server, the
                    // database rolled back
                    LOG.warn("k8store: transaction rolled back after its custom-resource writes were already"
                            + " applied; the CRs in the cluster are ahead of the database for this operation");
                    return;
                }
                discardAndRepair();
            }
        }
    }

    /** The last buffered state of one {@code (kind, realm, id)} key; a null spec is a delete. */
    private static final class PendingWrite<S> {

        private final KindState<S, ?> state;
        private final String realmId;
        private final String id;
        private final S spec;

        PendingWrite(KindState<S, ?> state, String realmId, String id, S spec) {
            this.state = state;
            this.realmId = realmId;
            this.id = id;
            this.spec = spec;
        }

        void flush() {
            if (spec == null) {
                state.deleteOnServer(realmId, id);
            } else {
                state.applyToServer(realmId, id, spec);
            }
        }

        void repairMirror() {
            state.refreshFromServer(realmId, id);
        }

        /** Releases the reconcile guard once this write has been flushed or repaired. */
        void clearPending() {
            state.pendingWriteKeys.remove(key(realmId, id));
        }

        String describe() {
            return state.specClass.getSimpleName() + " " + realmId + "/" + id;
        }
    }

    // ------------------------------------------------------------------ per-kind state

    /**
     * One CRD kind: its informer, the {@code realmId -> id -> spec} mirror and the CR names
     * backing each entry. Spec classes are plain data holders; identity access goes through the
     * supplied accessor functions, so representation-based and legacy spec types plug in alike.
     */
    private final class KindState<S, C extends CustomResource<S, Void>> {

        private final Class<C> crClass;
        private final Supplier<C> crFactory;
        private final Class<S> specClass;
        private final Function<S, String> idFn;
        private final BiConsumer<S, String> idSetter;
        private final Function<S, String> realmFn;
        /** Null when the kind's id doubles as its realm id (realm kind, pseudo-realm kinds). */
        private final BiConsumer<S, String> realmSetter;
        /** Dynamic kinds bypass read-only mode: Keycloak owns their CRs entirely. */
        private final boolean alwaysWritable;
        /** Expiration accessor (epoch millis; null/0 = never), or null when the kind never expires. */
        private final Function<S, Long> expiresAtFn;

        /** realmId -> id -> spec. */
        private final Map<String, Map<String, S>> byRealm = new ConcurrentHashMap<>();

        /** realmId + \0 + id -> CR metadata (name/namespace) backing that entry. */
        private final Map<String, CrRef> refs = new ConcurrentHashMap<>();

        /**
         * Keys with an unflushed buffered transaction write. Their mirror entries exist locally
         * but not yet on the API server, so the periodic reconcile must not remove them just
         * because a server LIST does not see them.
         */
        private final Set<String> pendingWriteKeys = ConcurrentHashMap.newKeySet();

        KindState(
                Class<C> crClass,
                Supplier<C> crFactory,
                Class<S> specClass,
                Function<S, String> idFn,
                BiConsumer<S, String> idSetter,
                Function<S, String> realmFn,
                BiConsumer<S, String> realmSetter) {
            this(crClass, crFactory, specClass, idFn, idSetter, realmFn, realmSetter, false, null);
        }

        KindState(
                Class<C> crClass,
                Supplier<C> crFactory,
                Class<S> specClass,
                Function<S, String> idFn,
                BiConsumer<S, String> idSetter,
                Function<S, String> realmFn,
                BiConsumer<S, String> realmSetter,
                boolean alwaysWritable,
                Function<S, Long> expiresAtFn) {
            this.crClass = crClass;
            this.crFactory = crFactory;
            this.specClass = specClass;
            this.idFn = idFn;
            this.idSetter = idSetter;
            this.realmFn = realmFn;
            this.realmSetter = realmSetter;
            this.alwaysWritable = alwaysWritable;
            this.expiresAtFn = expiresAtFn;
        }

        /** True when the entity carries an expiration timestamp that has passed. */
        boolean isExpired(S spec) {
            if (expiresAtFn == null) {
                return false;
            }
            Long expiresAt = expiresAtFn.apply(spec);
            return expiresAt != null && expiresAt > 0 && expiresAt <= Time.currentTimeMillis();
        }

        private MixedOperation<C, KubernetesResourceList<C>, Resource<C>> operation() {
            return client.resources(crClass);
        }

        SharedIndexInformer<C> startInformer() {
            var informable = config.isAllNamespaces()
                    ? operation().inAnyNamespace()
                    : operation().inNamespace(writeNamespace);
            return informable.inform(new ResourceEventHandler<C>() {
                @Override
                public void onAdd(C cr) {
                    upsert(cr);
                }

                @Override
                public void onUpdate(C oldCr, C newCr) {
                    // upsert first: a remove-then-upsert would open a window in which
                    // concurrent reads (e.g. the echo of Keycloak's own write) miss the entity
                    upsert(newCr);
                    String oldKey = identityKey(oldCr);
                    if (oldKey != null && !oldKey.equals(identityKey(newCr))) {
                        remove(oldCr);
                    }
                }

                @Override
                public void onDelete(C cr, boolean deletedFinalStateUnknown) {
                    remove(cr);
                }
            });
        }

        private String labelOf(C cr, String label) {
            Map<String, String> labels = cr.getMetadata().getLabels();
            return labels == null ? null : labels.get(label);
        }

        private void upsert(C cr) {
            S spec = cr.getSpec();
            if (spec == null) {
                LOG.warnv(
                        "Ignoring {0}/{1}: empty spec",
                        cr.getKind(), cr.getMetadata().getName());
                return;
            }
            if (idFn.apply(spec) == null) {
                idSetter.accept(spec, cr.getMetadata().getName());
            }
            if (realmSetter != null && realmFn.apply(spec) == null) {
                String labelRealm = labelOf(cr, REALM_LABEL);
                if (labelRealm != null) {
                    realmSetter.accept(spec, labelRealm);
                }
            }
            String realmId = realmFn.apply(spec);
            if (realmId == null) {
                LOG.warnv(
                        "Ignoring {0}/{1}: no realm in the spec or the {2} label",
                        cr.getKind(), cr.getMetadata().getName(), REALM_LABEL);
                return;
            }
            byRealm.computeIfAbsent(realmId, k -> new ConcurrentHashMap<>()).put(idFn.apply(spec), spec);
            refs.put(
                    key(realmId, idFn.apply(spec)),
                    new CrRef(cr.getMetadata().getName(), cr.getMetadata().getNamespace()));
            Consumer<ClientSpec> listener = clientSpecListener;
            if (listener != null && spec instanceof ClientSpec clientSpec) {
                listener.accept(COPY_MAPPER.convertValue(clientSpec, ClientSpec.class));
            }
        }

        /** (realmId, id) key of a CR, or null if it has no resolvable identity. */
        private String identityKey(C cr) {
            S spec = cr.getSpec();
            if (spec == null) {
                return null;
            }
            String id = idFn.apply(spec) != null
                    ? idFn.apply(spec)
                    : cr.getMetadata().getName();
            String realmId = realmFn.apply(spec);
            if (realmId == null && realmSetter != null) {
                realmId = labelOf(cr, REALM_LABEL);
            }
            if (realmId == null && realmSetter == null) {
                realmId = id;
            }
            return realmId == null ? null : key(realmId, id);
        }

        private void remove(C cr) {
            S spec = cr.getSpec();
            if (spec == null) {
                return;
            }
            String id = idFn.apply(spec) != null
                    ? idFn.apply(spec)
                    : cr.getMetadata().getName();
            String realmId = realmFn.apply(spec);
            if (realmId == null && realmSetter != null) {
                realmId = labelOf(cr, REALM_LABEL);
            }
            if (realmId == null && realmSetter == null) {
                realmId = id;
            }
            if (realmId == null) {
                return;
            }
            Map<String, S> realm = byRealm.get(realmId);
            if (realm != null) {
                realm.remove(id);
            }
            refs.remove(key(realmId, id));
        }

        /**
         * The local-visibility half of a write. The mirror holds its own copy: the caller keeps
         * mutating its instance and each mutation is persisted explicitly by the model layer.
         * Returns the stored copy - the write buffer keeps exactly that snapshot for the flush.
         */
        S mirrorPut(String realmId, String id, S spec) {
            S copy = COPY_MAPPER.convertValue(spec, specClass);
            byRealm.computeIfAbsent(realmId, k -> new ConcurrentHashMap<>()).put(id, copy);
            return copy;
        }

        void mirrorRemove(String realmId, String id) {
            // The empty per-realm map is intentionally not pruned: pruning races with a
            // concurrent mirrorPut that already holds a reference to the map and would lose that
            // write. The maps are bounded by the number of realms, so leaving them is harmless.
            Map<String, S> realm = byRealm.get(realmId);
            if (realm != null) {
                realm.remove(id);
            }
        }

        /**
         * A CR of this kind with its name/namespace, the realm and Keycloak-version labels
         * stamped, and the spec set. {@code resourceVersion} is passed only by the optimistic
         * reclaim path (put-if-absent) and is null everywhere else.
         */
        private C buildCr(String name, String namespace, String realmId, S spec, String resourceVersion) {
            ObjectMetaBuilder meta = new ObjectMetaBuilder()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels(REALM_LABEL, sanitizeLabel(realmId))
                    .addToLabels(VERSION_LABEL, sanitizeLabel(Version.VERSION));
            if (resourceVersion != null) {
                meta.withResourceVersion(resourceVersion);
            }
            C cr = crFactory.get();
            cr.setMetadata(meta.build());
            cr.setSpec(spec);
            return cr;
        }

        /** The API-server half of a write: server-side apply of the CR, stamped with labels. */
        void applyToServer(String realmId, String id, S spec) {
            CrRef ref = refs.get(key(realmId, id));
            String name = ref != null ? ref.name() : crName(crClass, realmId, id);
            String namespace = ref != null ? ref.namespace() : writeNamespace;
            C cr = buildCr(name, namespace, realmId, spec, null);
            try {
                client.resource(cr).fieldManager(FIELD_MANAGER).forceConflicts().serverSideApply();
            } catch (KubernetesClientException e) {
                // the mock API server used in tests does not support server-side apply
                // (404 on missing resources, 405/415 on apply-patch content type)
                if (e.getCode() == 404 || e.getCode() == 405 || e.getCode() == 415) {
                    client.resource(cr).createOr(NonDeletingOperation::update);
                } else {
                    throw e;
                }
            }
            refs.put(key(realmId, id), new CrRef(name, namespace));
        }

        /**
         * The API-server half of a delete. The backing CR reference is kept until this point so
         * that a buffered delete still finds a hand-authored CR's real name at flush time; an
         * entity created and deleted within one transaction has no reference and nothing to
         * delete on the server.
         */
        void deleteOnServer(String realmId, String id) {
            CrRef ref = refs.remove(key(realmId, id));
            if (ref != null) {
                operation().inNamespace(ref.namespace()).withName(ref.name()).delete();
            }
        }

        /**
         * Immediate create-if-absent through the API server's atomic create: {@code false} on a
         * name conflict (someone - possibly another node - created it first). On success the
         * mirror is updated so the entry is readable before the watch echo.
         */
        boolean createOnServer(String realmId, String id, S spec) {
            String name = crName(crClass, realmId, id);
            C cr = buildCr(name, writeNamespace, realmId, spec, null);
            try {
                client.resource(cr).create();
            } catch (KubernetesClientException e) {
                if (e.getCode() == 409) {
                    return false;
                }
                throw e;
            }
            refs.put(key(realmId, id), new CrRef(name, writeNamespace));
            mirrorPut(realmId, id, spec);
            return true;
        }

        /**
         * Atomic put-if-absent that also reclaims an expired CR. Tries an ordinary create first;
         * on a name conflict it inspects the existing CR and, only if it is expired, replaces it
         * under its {@code resourceVersion} so exactly one concurrent reclaimer wins (the losers
         * get a 409 and {@code false}). A live (unexpired) entry, or a lost race, yields
         * {@code false}; a create win or a reclaim win yields {@code true}.
         */
        boolean putIfAbsentOnServer(String realmId, String id, S spec) {
            if (createOnServer(realmId, id, spec)) {
                return true;
            }
            String name = crName(crClass, realmId, id);
            C current = operation().inNamespace(writeNamespace).withName(name).get();
            if (current == null) {
                // the conflicting CR vanished between the create and this read; create can win now
                return createOnServer(realmId, id, spec);
            }
            if (!isExpired(current.getSpec())) {
                return false;
            }
            C replacement = buildCr(
                    name, writeNamespace, realmId, spec, current.getMetadata().getResourceVersion());
            try {
                operation().inNamespace(writeNamespace).resource(replacement).update();
            } catch (KubernetesClientException e) {
                if (e.getCode() == 409) {
                    return false;
                }
                throw e;
            }
            refs.put(key(realmId, id), new CrRef(name, writeNamespace));
            mirrorPut(realmId, id, spec);
            return true;
        }

        /**
         * Immediate delete reporting whether this call removed the CR - Kubernetes answers a
         * DELETE with the deleted object exactly once and 404 for everyone else, which is the
         * atomic consume primitive of the single-use-object store. Falls back to the
         * deterministic CR name when the mirror holds no reference (e.g. the entry was written
         * by another node and never seen here).
         */
        boolean deleteOnServerChecked(String realmId, String id) {
            CrRef ref = refs.remove(key(realmId, id));
            String name = ref != null ? ref.name() : crName(crClass, realmId, id);
            String namespace = ref != null ? ref.namespace() : writeNamespace;
            return !operation().inNamespace(namespace).withName(name).delete().isEmpty();
        }

        /**
         * Rollback repair: re-reads one key's backing CR from the API server and resets the
         * mirror entry to what the cluster actually holds - restoring it when the CR exists,
         * dropping it when it does not (e.g. an entity optimistically created in the rolled-back
         * transaction).
         */
        void refreshFromServer(String realmId, String id) {
            CrRef ref = refs.get(key(realmId, id));
            String name = ref != null ? ref.name() : crName(crClass, realmId, id);
            String namespace = ref != null ? ref.namespace() : writeNamespace;
            C cr = operation().inNamespace(namespace).withName(name).get();
            if (cr != null) {
                upsert(cr);
            } else {
                refs.remove(key(realmId, id));
                mirrorRemove(realmId, id);
            }
        }

        void clear() {
            byRealm.clear();
            refs.clear();
        }

        /** Deletes every mirrored entry of this kind whose expiration timestamp has passed. */
        void sweepExpired() {
            for (Map.Entry<String, Map<String, S>> realmEntry : byRealm.entrySet()) {
                for (Map.Entry<String, S> entry :
                        new ArrayList<>(realmEntry.getValue().entrySet())) {
                    if (isExpired(entry.getValue())) {
                        String realmId = realmEntry.getKey();
                        String id = entry.getKey();
                        try {
                            deleteOnServerChecked(realmId, id);
                            mirrorRemove(realmId, id);
                            LOG.tracev(
                                    "k8store reaper deleted expired {0} {1}/{2}",
                                    specClass.getSimpleName(), realmId, id);
                        } catch (RuntimeException e) {
                            LOG.debugv(
                                    e,
                                    "k8store reaper could not delete expired {0} {1}/{2}; retrying next sweep",
                                    specClass.getSimpleName(),
                                    realmId,
                                    id);
                        }
                    }
                }
            }
        }

        /** Lists all CRs of this kind and replays them through the normal event handling. */
        void reconcile() {
            List<C> current = config.isAllNamespaces()
                    ? operation().inAnyNamespace().list().getItems()
                    : operation().inNamespace(writeNamespace).list().getItems();
            Set<String> liveKeys = new HashSet<>();
            for (C cr : current) {
                upsert(cr);
                String key = identityKey(cr);
                if (key != null) {
                    liveKeys.add(key);
                }
            }
            for (Map.Entry<String, Map<String, S>> realmEntry : byRealm.entrySet()) {
                for (String id : new ArrayList<>(realmEntry.getValue().keySet())) {
                    String key = key(realmEntry.getKey(), id);
                    // Keep entries with an unflushed buffered write: their CR is not on the
                    // server LIST yet, so liveKeys would wrongly drop them mid-transaction.
                    if (!liveKeys.contains(key) && !pendingWriteKeys.contains(key)) {
                        realmEntry.getValue().remove(id);
                        refs.remove(key);
                    }
                }
            }
        }
    }

    private record CrRef(String name, String namespace) {}

    /**
     * Separator for {@code (realmId, id)} composite keys. A NUL byte cannot occur in a realm name
     * or an entity id, so the concatenation is unambiguous. Defined once here and reused wherever
     * a composite key is formed (mirror indexing, the write buffer, the adapter caches).
     */
    public static final char KEY_SEPARATOR = '\0';

    /** Canonical {@code (realmId, id)} composite key. */
    public static String key(String realmId, String id) {
        return realmId + KEY_SEPARATOR + id;
    }

    // ------------------------------------------------------------------ naming

    /** See {@link CrNaming#crName}; kept here as the backend's naming entry point. */
    static String crName(Class<?> crClass, String realmId, String id) {
        return CrNaming.crName(crClass, realmId, id);
    }

    private static String sanitizeLabel(String value) {
        return CrNaming.labelValue(value);
    }
}
