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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.github.dominikschlosser.k8store.client.ClientCrStore;
import io.github.dominikschlosser.k8store.clientscope.ClientScopeCrStore;
import io.github.dominikschlosser.k8store.crd.ClientScopeSpec;
import io.github.dominikschlosser.k8store.crd.ClientSpec;
import io.github.dominikschlosser.k8store.crd.GroupSpec;
import io.github.dominikschlosser.k8store.crd.KeySelector;
import io.github.dominikschlosser.k8store.crd.RealmSpec;
import io.github.dominikschlosser.k8store.crd.RoleSpec;
import io.github.dominikschlosser.k8store.crd.UserSpec;
import io.github.dominikschlosser.k8store.crd.ValueFrom;
import io.github.dominikschlosser.k8store.crd.ValueReference;
import io.github.dominikschlosser.k8store.group.GroupCrStore;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig.Area;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRoleCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakUserCr;
import io.github.dominikschlosser.k8store.realm.RealmCrStore;
import io.github.dominikschlosser.k8store.role.RoleCrStore;
import io.github.dominikschlosser.k8store.user.UserCrStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.Version;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.ClientScopeProvider;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.IdentityProviderStorageProvider;
import org.keycloak.models.KeyManager;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RevokedTokenProvider;
import org.keycloak.models.RoleProvider;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.ThemeManager;
import org.keycloak.models.TokenManager;
import org.keycloak.models.UserLoginFailureProvider;
import org.keycloak.models.UserProvider;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.provider.InvalidationHandler.InvalidableObjectType;
import org.keycloak.provider.Provider;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.services.DefaultKeycloakTransactionManager;
import org.keycloak.services.clientpolicy.ClientPolicyManager;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.storage.ReadOnlyException;
import org.keycloak.tracing.NoopTracingProvider;
import org.keycloak.tracing.TracingProvider;
import org.keycloak.utils.KeycloakSessionUtil;
import org.keycloak.vault.VaultTranscriber;

@EnableKubernetesMockClient(crud = true)
class K8sStorageBackendTest {

    KubernetesClient client;
    KubernetesMockServer server;

    @AfterEach
    void shutdownBackend() {
        KeycloakSessionUtil.setKeycloakSession(null);
        K8sStorageBackend.shutdown();
        K8sStoreConfig.reset();
    }

    private K8sStorageBackend start(boolean readOnly) {
        K8sStoreConfig config = K8sStoreConfig.of(readOnly, EnumSet.allOf(Area.class), "test", false, 30);
        return K8sStorageBackend.initWithClient(client, config);
    }

    private K8sStorageBackend startResolving(boolean readOnly) {
        K8sStoreConfig config = K8sStoreConfig.of(readOnly, EnumSet.allOf(Area.class), "test", false, 30, true);
        return K8sStorageBackend.initWithClient(client, config);
    }

    private K8sStorageBackend startValidating() {
        K8sStoreConfig config = K8sStoreConfig.of(true, EnumSet.allOf(Area.class), "test", false, 30, true, true);
        return K8sStorageBackend.initWithClient(client, config);
    }

    private void createSecret(String name, String key, String value) {
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace("test")
                .endMetadata()
                .addToData(key, Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .build();
        client.resource(secret).create();
    }

    private void createConfigMap(String name, String key, String value) {
        ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace("test")
                .endMetadata()
                .addToData(key, value)
                .build();
        client.resource(configMap).create();
    }

    private static ValueReference secretRef(String targetPath, String name, String key) {
        KeySelector selector = new KeySelector();
        selector.setName(name);
        selector.setKey(key);
        ValueFrom valueFrom = new ValueFrom();
        valueFrom.setSecretKeyRef(selector);
        return valueReference(targetPath, valueFrom);
    }

    private static ValueReference configMapRef(String targetPath, String name, String key) {
        KeySelector selector = new KeySelector();
        selector.setName(name);
        selector.setKey(key);
        ValueFrom valueFrom = new ValueFrom();
        valueFrom.setConfigMapKeyRef(selector);
        return valueReference(targetPath, valueFrom);
    }

    private static ValueReference literalRef(String targetPath, String value) {
        ValueFrom valueFrom = new ValueFrom();
        valueFrom.setValue(value);
        return valueReference(targetPath, valueFrom);
    }

    private static ValueReference valueReference(String targetPath, ValueFrom valueFrom) {
        ValueReference reference = new ValueReference();
        reference.setTargetPath(targetPath);
        reference.setValueFrom(valueFrom);
        return reference;
    }

    private KeycloakClientCr clientCr(String name, ClientSpec spec) {
        KeycloakClientCr cr = new KeycloakClientCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace("test")
                .addToLabels(K8sStorageBackend.REALM_LABEL, spec.getRealm())
                .build());
        cr.setSpec(spec);
        return cr;
    }

    private KeycloakRealmCr realmCr(String name, RealmSpec spec) {
        KeycloakRealmCr cr = new KeycloakRealmCr();
        cr.setMetadata(
                new ObjectMetaBuilder().withName(name).withNamespace("test").build());
        cr.setSpec(spec);
        return cr;
    }

    @Test
    void readsPreExistingRealmCr() {
        RealmSpec realm = new RealmSpec();
        realm.setRealm("master");
        client.resource(realmCr("master", realm)).create();

        start(true);

        RealmSpec read = RealmCrStore.read("master");
        assertNotNull(read);
        assertEquals("master", read.getRealm());
        assertTrue(RealmCrStore.exists("master"));
        assertEquals(1, RealmCrStore.readAll().size());
    }

    @Test
    void defaultsRealmNameFromCrName() {
        // hand-authored CR without spec.realm: identity comes from metadata.name
        RealmSpec realm = new RealmSpec();
        realm.setDisplayName("hand-authored");
        client.resource(realmCr("my-realm", realm)).create();

        start(true);

        RealmSpec read = RealmCrStore.read("my-realm");
        assertNotNull(read);
        assertEquals("my-realm", read.getRealm());
    }

    @Test
    void picksUpOutOfBandChangesThroughInformer() {
        start(true);
        assertNull(RealmCrStore.read("late"));

        RealmSpec realm = new RealmSpec();
        realm.setRealm("late");
        client.resource(realmCr("late", realm)).create();

        awaitNonNull(() -> RealmCrStore.read("late"));

        client.resources(KeycloakRealmCr.class)
                .inNamespace("test")
                .withName("late")
                .delete();
        awaitNull(() -> RealmCrStore.read("late"));
    }

    @Test
    void clientSpecListenerSeesWatchUpsertsWithDefensiveCopies() {
        K8sStorageBackend backend = start(true);
        List<ClientSpec> seen = new CopyOnWriteArrayList<>();
        backend.setClientSpecListener(seen::add);

        ClientSpec spec = new ClientSpec();
        spec.setRealm("master");
        spec.setClientId("svc");
        spec.setServiceAccountsEnabled(true);
        client.resource(clientCr("master.svc", spec)).create();

        await(() -> !seen.isEmpty());
        ClientSpec delivered = seen.get(0);
        assertEquals("svc", delivered.getClientId());
        assertEquals(Boolean.TRUE, delivered.isServiceAccountsEnabled());
        // the listener gets a copy: mutating it must not poison the mirror
        delivered.setClientId("mutated");
        assertEquals("svc", ClientCrStore.read("master", "svc").getClientId());
    }

    @Test
    void writeModeCreatesAndDeletesCrs() {
        start(false);

        ClientSpec spec = new ClientSpec();
        spec.setId("my-client");
        spec.setClientId("my-client");
        spec.setRealm("master");
        ClientCrStore.save(spec);

        List<KeycloakClientCr> crs = client.resources(KeycloakClientCr.class)
                .inNamespace("test")
                .list()
                .getItems();
        assertEquals(1, crs.size());
        assertEquals(
                "master.my-client",
                crs.get(0).getMetadata().getName(),
                "a DNS-clean realm and id get a readable name with no hash suffix");
        assertEquals("my-client", crs.get(0).getSpec().getClientId());
        assertEquals("master", crs.get(0).getMetadata().getLabels().get(K8sStorageBackend.REALM_LABEL));

        // read-your-write without waiting for the watch
        assertNotNull(ClientCrStore.read("master", "my-client"));

        ClientCrStore.delete("master", "my-client");
        assertNull(ClientCrStore.read("master", "my-client"));
        assertTrue(client.resources(KeycloakClientCr.class)
                .inNamespace("test")
                .list()
                .getItems()
                .isEmpty());
    }

    @Test
    void clientSpecClientIdAndRealmDefaultFromMetadataAtInformerLoad() {
        // hand-authored client CR without spec.clientId: identity comes from metadata.name, the
        // realm from the realm label
        KeycloakClientCr cr = new KeycloakClientCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("hand-authored-client")
                .withNamespace("test")
                .addToLabels(K8sStorageBackend.REALM_LABEL, "master")
                .build());
        cr.setSpec(new ClientSpec());
        client.resource(cr).create();

        start(true);

        ClientSpec read = ClientCrStore.read("master", "hand-authored-client");
        assertNotNull(read);
        assertEquals("hand-authored-client", read.getClientId());
        assertEquals("master", read.getRealm());
    }

    @Test
    void clientScopeSpecRoundTripsThroughMirrorKeyedByName() {
        start(false);

        ClientScopeSpec spec = new ClientScopeSpec();
        spec.setId("email");
        spec.setName("email");
        spec.setRealm("master");
        spec.setProtocol("openid-connect");
        spec.setAttributes(new HashMap<>());
        spec.getAttributes().put("include.in.token.scope", "true");
        spec.setRealmScopeMappings(List.of("offline_access"));
        ClientScopeCrStore.save(spec);

        ClientScopeSpec read = ClientScopeCrStore.read("master", "email");
        assertNotNull(read);
        assertEquals("openid-connect", read.getProtocol());
        assertEquals("true", read.getAttributes().get("include.in.token.scope"));
        assertEquals(
                List.of("offline_access"),
                read.getRealmScopeMappings(),
                "scope mappings must round-trip through the CR spec");

        ClientScopeCrStore.delete("master", "email");
        assertNull(ClientScopeCrStore.read("master", "email"));
    }

    @Test
    void readOnlyModeRejectsWrites() {
        start(true);

        RealmSpec realm = new RealmSpec();
        realm.setRealm("nope");
        assertThrows(ReadOnlyException.class, () -> RealmCrStore.save(realm));
        assertThrows(ReadOnlyException.class, () -> RealmCrStore.delete("nope"));
        assertFalse(RealmCrStore.exists("nope"));
    }

    @Test
    void realmSpecMutationsPersistOnlyWhenSavedExplicitly() {
        start(false);

        RealmSpec realm = new RealmSpec();
        realm.setRealm("mutable");
        RealmCrStore.save(realm);

        // specs carry no write-through machinery: mutations reach the CR via an explicit save
        realm.setDisplayName("Changed");
        assertNull(
                RealmCrStore.read("mutable").getDisplayName(),
                "mutating a saved spec without saving again must not leak into the store");
        RealmCrStore.save(realm);

        KeycloakRealmCr cr = client.resources(KeycloakRealmCr.class)
                .inNamespace("test")
                .withName("mutable")
                .get();
        assertEquals("Changed", cr.getSpec().getDisplayName());
    }

    @Test
    void embeddedPerKindCollectionsAreDetectedAndNotSerialized() throws Exception {
        // per-kind CRs are the storage: embedded collections in a realm spec deserialize (so
        // they can be reported) but are excluded from serialization and thus the CRD schema
        String json = "{\"realm\":\"embedded\",\"clients\":[{\"clientId\":\"in-realm\"}],"
                + "\"groups\":[{\"name\":\"g\"}],\"displayName\":\"kept\"}";
        RealmSpec spec = K8sStorageBackend.configureMapper(new ObjectMapper()).readValue(json, RealmSpec.class);
        assertEquals(List.of("clients", "groups"), spec.ignoredEmbeddedCollections());
        assertEquals("kept", spec.getDisplayName());

        String out = K8sStorageBackend.configureMapper(new ObjectMapper()).writeValueAsString(spec);
        assertFalse(out.contains("in-realm"), out);
        assertFalse(out.contains("\"groups\""), out);
        assertTrue(out.contains("kept"), out);
    }

    @Test
    void nullMapValuesAreDroppedFromSerializedClientSpecs() throws Exception {
        // a real API server rejects null values in map<string,string> schema fields with 422;
        // maps inherited from the representation superclass are only covered by the mapper-level
        // default installed via configureMapper (the write client's mapper)
        ClientSpec spec = new ClientSpec();
        spec.setId("no-null-values");
        spec.setClientId("no-null-values");
        spec.setRealm("master");
        spec.setAttributes(new HashMap<>());
        spec.getAttributes().put("present", "value");
        spec.getAttributes().put("absent", null);

        // the exact shape that crashed master-realm bootstrap against a real API server:
        // a protocol mapper carrying a null config value ("rolePrefix": null)
        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName("realm roles");
        mapper.setConfig(new HashMap<>());
        mapper.getConfig().put("usermodel.realmRoleMapping.rolePrefix", null);
        mapper.getConfig().put("multivalued", "true");
        spec.setProtocolMappers(List.of(mapper));

        String json = K8sStorageBackend.configureMapper(new ObjectMapper()).writeValueAsString(spec);
        assertTrue(json.contains("present"), json);
        assertFalse(json.contains("absent"), json);
        assertTrue(json.contains("multivalued"), json);
        assertFalse(json.contains("rolePrefix"), json);
        assertFalse(json.contains(":null"), json);

        // and through the write client's actual serialization: fabric8's own mapper setup keeps
        // null map values by default and must not undo the null-dropping
        KeycloakClientCr cr = new KeycloakClientCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("no-null-values")
                .withNamespace("test")
                .build());
        cr.setSpec(spec);
        String wireJson = K8sStorageBackend.buildSerialization().asJson(cr);
        assertTrue(wireJson.contains("multivalued"), wireJson);
        assertFalse(wireJson.contains("rolePrefix"), wireJson);
        assertFalse(wireJson.contains(":null"), wireJson);
    }

    @Test
    void roleSpecIdAndRealmDefaultFromMetadataAtInformerLoad() {
        // hand-authored role CR without spec.id: identity comes from metadata.name, the realm
        // from the realm label
        KeycloakRoleCr cr = new KeycloakRoleCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("hand-authored-role")
                .withNamespace("test")
                .addToLabels(K8sStorageBackend.REALM_LABEL, "master")
                .build());
        RoleSpec spec = new RoleSpec();
        spec.setName("hand-authored-role");
        cr.setSpec(spec);
        client.resource(cr).create();

        start(true);

        RoleSpec read = RoleCrStore.read("master", "hand-authored-role");
        assertNotNull(read);
        assertEquals("hand-authored-role", read.getId());
        assertEquals("master", read.getRealm());
    }

    @Test
    void roleWritesStampRealmAndKeycloakVersionLabels() {
        start(false);

        RoleSpec spec = new RoleSpec();
        spec.setId("stamped-role");
        spec.setRealm("master");
        spec.setName("stamped-role");
        RoleCrStore.save(spec);

        List<KeycloakRoleCr> crs = client.resources(KeycloakRoleCr.class)
                .inNamespace("test")
                .list()
                .getItems();
        assertEquals(1, crs.size());
        Map<String, String> labels = crs.get(0).getMetadata().getLabels();
        assertEquals("master", labels.get(K8sStorageBackend.REALM_LABEL));
        assertEquals(
                Version.VERSION,
                labels.get(K8sStorageBackend.VERSION_LABEL),
                "every CR written by Keycloak must be stamped with the writing server's version");

        // read-your-write without waiting for the watch, and mirror isolation: the caller's
        // instance is not the mirror's instance
        RoleSpec read = RoleCrStore.read("master", "stamped-role");
        assertNotNull(read);
        spec.setDescription("mutated-after-save");
        assertNull(
                RoleCrStore.read("master", "stamped-role").getDescription(),
                "mutating a saved spec without saving again must not leak into the mirror");

        RoleCrStore.delete("master", "stamped-role");
        assertNull(RoleCrStore.read("master", "stamped-role"));
        assertTrue(client.resources(KeycloakRoleCr.class)
                .inNamespace("test")
                .list()
                .getItems()
                .isEmpty());
    }

    @Test
    void groupSpecRoundTripsThroughMirror() {
        start(false);

        GroupSpec spec = new GroupSpec();
        spec.setId("team-a");
        spec.setRealm("master");
        spec.setName("team-a");
        spec.setParentId("parent-group");
        spec.setAttributes(new HashMap<>());
        spec.getAttributes().put("tier", List.of("gold", "silver"));
        GroupCrStore.save(spec);

        GroupSpec read = GroupCrStore.read("master", "team-a");
        assertNotNull(read);
        assertEquals("parent-group", read.getParentId());
        assertEquals(
                List.of("gold", "silver"),
                read.getAttributes().get("tier"),
                "multi-valued attributes must round-trip losslessly");
    }

    @Test
    void representationSpecsDropNullValuesFromSerialization() throws Exception {
        // a real API server rejects null values in map<string,string> schema fields with 422;
        // the write client's mapper (K8sStorageBackend.buildClient) drops them, including in
        // maps inherited from the representation superclasses and in nested representations
        RoleSpec spec = new RoleSpec();
        spec.setId("no-nulls");
        spec.setRealm("master");
        spec.setName("no-nulls");
        spec.setAttributes(new HashMap<>());
        spec.getAttributes().put("present", List.of("value"));
        spec.getAttributes().put("absent", null);
        RoleRepresentation.Composites composites = new RoleRepresentation.Composites();
        composites.setRealm(Set.of("other-role"));
        spec.setComposites(composites);

        String json = K8sStorageBackend.configureMapper(new ObjectMapper()).writeValueAsString(spec);
        assertTrue(json.contains("present"), json);
        assertFalse(json.contains("absent"), json);
        assertFalse(json.contains(":null"), json);
        assertFalse(json.contains("\"description\""), json);

        // the spec classes' own @JsonInclude already drops null fields with any mapper (CRs
        // authored through other clients stay lean)
        String defaultJson = Serialization.jsonMapper().writeValueAsString(spec);
        assertFalse(defaultJson.contains("\"description\""), defaultJson);
    }

    @Test
    void crNamesAreSanitizedDeterministically() {
        // realms and scoped kinds keep plain readable names when every component is DNS-clean
        assertEquals("my-realm", K8sStorageBackend.crName(KeycloakRealmCr.class, "my-realm", "my-realm"));
        assertEquals("master.account", K8sStorageBackend.crName(KeycloakClientCr.class, "master", "account"));
        // a hash over the exact (realmId, id) pair is added only when dnsLabel mangles a component,
        // so distinct pairs that sanitize alike cannot collide on one CR name
        String weird = K8sStorageBackend.crName(KeycloakClientCr.class, "master", "My Weird/Client");
        assertTrue(weird.matches("master\\.my-weird-client-[0-9a-f]{8}"), weird);
        assertEquals(weird, K8sStorageBackend.crName(KeycloakClientCr.class, "master", "My Weird/Client"));

        // the historic collision cases: distinct pairs must never share a CR name
        assertTrue(!K8sStorageBackend.crName(KeycloakClientCr.class, "a", "b.c")
                .equals(K8sStorageBackend.crName(KeycloakClientCr.class, "a.b", "c")));
        assertTrue(!K8sStorageBackend.crName(KeycloakClientCr.class, "acme prod", "web")
                .equals(K8sStorageBackend.crName(KeycloakClientCr.class, "acme", "prod web")));
        // consecutive dots must never survive into a CR name (invalid DNS-1123)
        assertTrue(!K8sStorageBackend.crName(KeycloakClientCr.class, "master", "my..app")
                .contains(".."));
        assertTrue(
                !K8sStorageBackend.crName(KeycloakRealmCr.class, "a..b", "a..b").contains(".."));

        // a very long id whose truncation boundary lands on a hyphen must not leave a trailing
        // hyphen on any label, and every dot-separated label must stay within 63 characters
        String longId = "a".repeat(53) + "-" + "b".repeat(40);
        for (String name : List.of(
                K8sStorageBackend.crName(KeycloakClientCr.class, longId, longId),
                K8sStorageBackend.crName(KeycloakRealmCr.class, longId, longId))) {
            for (String label : name.split("\\.")) {
                assertTrue(label.length() <= 63, name);
                assertTrue(label.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?"), name);
            }
        }
    }

    // ------------------------------------------------------------- reference resolution

    @Test
    void resolveReferencesRequiresReadOnlyMode() {
        // resolution is a read-only-mode feature: in write mode a save would persist the resolved
        // value back into the CR, so the misconfiguration fails fast at config load
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> K8sStoreConfig.of(false, EnumSet.allOf(Area.class), "test", false, 30, true));
        assertTrue(e.getMessage().contains("read-only"), e.getMessage());

        // read-only + resolve-references is the supported combination
        assertTrue(K8sStoreConfig.of(true, EnumSet.allOf(Area.class), "test", false, 30, true)
                .isResolveReferences());
    }

    @Test
    void validateReferencesRequiresResolveReferences() {
        // there is nothing to validate when references are not resolved
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> K8sStoreConfig.of(true, EnumSet.allOf(Area.class), "test", false, 30, false, true));
        assertTrue(e.getMessage().contains("validate-references"), e.getMessage());

        // resolve-references + validate-references is the supported combination
        assertTrue(K8sStoreConfig.of(true, EnumSet.allOf(Area.class), "test", false, 30, true, true)
                .isValidateReferences());
    }

    @Test
    void resolvesSecretAndConfigMapReferencesOnReadWithoutTouchingTheStoredCr() {
        createSecret("kc-client-secrets", "my-client", "s3cr3t");
        createConfigMap("kc-client-settings", "APP_URL", "https://app.example");

        ClientSpec spec = new ClientSpec();
        spec.setClientId("my-client");
        spec.setRealm("master");
        spec.setSecret("${my-client}");
        spec.setRootUrl("${APP_URL}");
        spec.setDescription("build ${BUILD}");
        spec.setValuesFrom(List.of(
                secretRef("secret", "kc-client-secrets", "my-client"),
                configMapRef("rootUrl", "kc-client-settings", "APP_URL"),
                literalRef("description", "17")));
        client.resource(clientCr("master.my-client", spec)).create();

        startResolving(true);

        ClientSpec read = ClientCrStore.read("master", "my-client");
        assertNotNull(read);
        assertEquals("s3cr3t", read.getSecret(), "the secret reference must be resolved on read");
        assertEquals("https://app.example", read.getRootUrl(), "the configmap reference must be resolved on read");
        assertEquals("build 17", read.getDescription(), "the literal reference must be injected at the placeholder");

        // the stored CR still holds the raw placeholder: the secret is never written into the
        // config CR in clear
        KeycloakClientCr stored = client.resources(KeycloakClientCr.class)
                .inNamespace("test")
                .withName("master.my-client")
                .get();
        assertEquals("${my-client}", stored.getSpec().getSecret());
    }

    @Test
    void leavesUndeclaredPlaceholderVerbatim() {
        // a ${...} that no valuesFrom entry points at is a Keycloak token, not a reference
        createSecret("kc-client-secrets", "my-client", "s3cr3t");

        ClientSpec spec = new ClientSpec();
        spec.setClientId("my-client");
        spec.setRealm("master");
        spec.setSecret("${my-client}");
        spec.setDescription("${role_admin}");
        spec.setValuesFrom(List.of(secretRef("secret", "kc-client-secrets", "my-client")));
        client.resource(clientCr("master.my-client", spec)).create();

        startResolving(true);

        ClientSpec read = ClientCrStore.read("master", "my-client");
        assertNotNull(read);
        assertEquals("s3cr3t", read.getSecret());
        assertEquals("${role_admin}", read.getDescription(), "an undeclared placeholder is left verbatim");
    }

    @Test
    void leavesUnresolvableReferenceVerbatimAndStillServesTheEntity() {
        ClientSpec spec = new ClientSpec();
        spec.setClientId("broken");
        spec.setRealm("master");
        spec.setSecret("${key}");
        spec.setValuesFrom(List.of(secretRef("secret", "absent-secret", "key")));
        client.resource(clientCr("master.broken", spec)).create();

        startResolving(true);

        ClientSpec read = ClientCrStore.read("master", "broken");
        assertNotNull(read, "an unresolvable reference must not hide the entity");
        assertEquals("${key}", read.getSecret(), "a missing secret is left verbatim");
    }

    @Test
    void doesNotResolveReferencesForAlwaysWritableRuntimeKinds() {
        // resolution is a config concern: runtime kinds (users, sessions, ...) stay writable in
        // read-only mode, so resolving them would risk persisting the resolved value back on their
        // next write. They carry no valuesFrom and are served verbatim even with resolution on.
        UserSpec spec = new UserSpec();
        spec.setId("u1");
        spec.setRealm("master");
        spec.setUsername("${token}");
        KeycloakUserCr cr = new KeycloakUserCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("master.u1")
                .withNamespace("test")
                .addToLabels(K8sStorageBackend.REALM_LABEL, "master")
                .build());
        cr.setSpec(spec);
        client.resource(cr).create();

        startResolving(true);

        UserSpec read = UserCrStore.read("master", "u1");
        assertNotNull(read);
        assertEquals("${token}", read.getUsername(), "always-writable runtime kinds must not be resolved");
    }

    @Test
    void doesNotResolveReferencesWhenTheFeatureIsOff() {
        createSecret("kc-client-secrets", "my-client", "s3cr3t");

        ClientSpec spec = new ClientSpec();
        spec.setClientId("my-client");
        spec.setRealm("master");
        spec.setSecret("${my-client}");
        spec.setValuesFrom(List.of(secretRef("secret", "kc-client-secrets", "my-client")));
        client.resource(clientCr("master.my-client", spec)).create();

        start(true);

        ClientSpec read = ClientCrStore.read("master", "my-client");
        assertNotNull(read);
        assertEquals(
                "${my-client}", read.getSecret(), "with resolve-references off the placeholder is served verbatim");
    }

    @Test
    void startupValidationPassesWhenEveryReferenceResolves() {
        createSecret("kc-client-secrets", "my-client", "s3cr3t");
        createConfigMap("kc-client-settings", "APP_URL", "https://app.example");

        ClientSpec spec = new ClientSpec();
        spec.setClientId("my-client");
        spec.setRealm("master");
        spec.setSecret("${my-client}");
        spec.setRootUrl("${APP_URL}");
        spec.setValuesFrom(List.of(
                secretRef("secret", "kc-client-secrets", "my-client"),
                configMapRef("rootUrl", "kc-client-settings", "APP_URL")));
        client.resource(clientCr("master.my-client", spec)).create();

        // boot succeeds and still resolves on read
        startValidating();
        ClientSpec read = ClientCrStore.read("master", "my-client");
        assertNotNull(read);
        assertEquals("s3cr3t", read.getSecret());
        assertEquals("https://app.example", read.getRootUrl());
    }

    @Test
    void startupValidationFailsBootOnAMissingSecret() {
        ClientSpec spec = new ClientSpec();
        spec.setClientId("broken");
        spec.setRealm("master");
        spec.setSecret("${key}");
        spec.setValuesFrom(List.of(secretRef("secret", "absent-secret", "key")));
        client.resource(clientCr("master.broken", spec)).create();

        IllegalStateException e = assertThrows(IllegalStateException.class, this::startValidating);
        assertTrue(e.getMessage().contains("validate-references"), e.getMessage());
        assertTrue(e.getMessage().contains("absent-secret"), e.getMessage());
    }

    @Test
    void startupValidationFailsBootWhenTargetPathLacksThePlaceholder() {
        createSecret("kc-client-secrets", "my-client", "s3cr3t");

        ClientSpec spec = new ClientSpec();
        spec.setClientId("mismatch");
        spec.setRealm("master");
        spec.setSecret("not-a-placeholder");
        spec.setValuesFrom(List.of(secretRef("secret", "kc-client-secrets", "my-client")));
        client.resource(clientCr("master.mismatch", spec)).create();

        IllegalStateException e = assertThrows(IllegalStateException.class, this::startValidating);
        assertTrue(e.getMessage().contains("does not contain the placeholder"), e.getMessage());
    }

    @Test
    void startupValidationIgnoresAlwaysWritableRuntimeKinds() {
        // runtime kinds carry no valuesFrom. A stray placeholder in a user CR must not fail the boot
        UserSpec spec = new UserSpec();
        spec.setId("u1");
        spec.setRealm("master");
        spec.setUsername("${token}");
        KeycloakUserCr cr = new KeycloakUserCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("master.u1")
                .withNamespace("test")
                .addToLabels(K8sStorageBackend.REALM_LABEL, "master")
                .build());
        cr.setSpec(spec);
        client.resource(cr).create();

        startValidating();
        assertNotNull(UserCrStore.read("master", "u1"));
    }

    // ------------------------------------------------------------- transaction buffering

    /**
     * Binds a fake session with a real {@link DefaultKeycloakTransactionManager} to the current
     * thread and begins its transaction, so writes go through the session write buffer exactly
     * as they do inside the server.
     */
    private FakeKeycloakSession beginSession() {
        FakeKeycloakSession session = new FakeKeycloakSession();
        session.getTransactionManager().begin();
        KeycloakSessionUtil.setKeycloakSession(session);
        return session;
    }

    @Test
    void writesApplyToTheServerImmediatelyWithoutASession() {
        start(false);

        // no KeycloakSession on the thread (boot-time paths, unit tests): the fallback applies
        // the CR to the API server right away - current pre-buffering behavior
        RealmSpec realm = new RealmSpec();
        realm.setRealm("no-session");
        RealmCrStore.save(realm);

        assertNotNull(
                client.resources(KeycloakRealmCr.class)
                        .inNamespace("test")
                        .withName("no-session")
                        .get(),
                "without a session the write must reach the API server immediately");
    }

    @Test
    void transactionBuffersWritesAndAppliesEachKeyOnceAtCommit() {
        start(false);
        FakeKeycloakSession session = beginSession();

        RealmSpec realm = new RealmSpec();
        realm.setRealm("buffered");
        int before = server.getRequestCount();
        for (int i = 0; i <= 3; i++) {
            realm.setDisplayName("v" + i);
            RealmCrStore.save(realm);
        }
        assertEquals(
                before,
                server.getRequestCount(),
                "buffered saves must not produce any API-server request before commit");

        // read-your-write from the mirror before the commit
        assertEquals("v3", RealmCrStore.read("buffered").getDisplayName());
        assertNull(
                client.resources(KeycloakRealmCr.class)
                        .inNamespace("test")
                        .withName("buffered")
                        .get(),
                "the CR must not exist on the server before commit");

        session.getTransactionManager().commit();

        KeycloakRealmCr cr = client.resources(KeycloakRealmCr.class)
                .inNamespace("test")
                .withName("buffered")
                .get();
        assertNotNull(cr, "commit must flush the buffered write to the API server");
        assertEquals("v3", cr.getSpec().getDisplayName(), "the last buffered state wins");
        assertEquals(
                Version.VERSION,
                cr.getMetadata().getLabels().get(K8sStorageBackend.VERSION_LABEL),
                "flushed CRs carry the version stamp");
    }

    @Test
    void reconcileKeepsBufferedButUnflushedWrites() {
        K8sStorageBackend backend = start(false);
        FakeKeycloakSession session = beginSession();

        RealmSpec realm = new RealmSpec();
        realm.setRealm("in-flight");
        realm.setDisplayName("pending");
        RealmCrStore.save(realm);

        // the write is buffered: mirror has it, the server LIST does not. A reconcile in this
        // window must not drop it, or the transaction loses its own write before commit.
        backend.reconcileNow();
        assertNotNull(RealmCrStore.read("in-flight"), "reconcile must not remove a buffered-but-unflushed write");

        session.getTransactionManager().commit();
        assertNotNull(
                client.resources(KeycloakRealmCr.class)
                        .inNamespace("test")
                        .withName("in-flight")
                        .get(),
                "commit still flushes the write");
        // after commit the guard is released and reconcile is a plain no-op diff
        backend.reconcileNow();
        assertNotNull(RealmCrStore.read("in-flight"));
    }

    @Test
    void detectVersionDriftReportsExactlyTheCrsStampedByAnotherVersion() {
        // one CR stamped with the running version (not drifted) and two with an older stamp
        client.resource(stampedRealmCr("current", Version.VERSION)).create();
        client.resource(stampedRealmCr("old-a", "9.9.9")).create();
        client.resource(stampedRealmCr("old-b", "8.8.8")).create();
        // a hand-authored CR without any version stamp is not drift
        RealmSpec unstamped = new RealmSpec();
        unstamped.setRealm("unstamped");
        client.resource(realmCr("unstamped", unstamped)).create();

        K8sStorageBackend backend = start(true);

        List<String> drifted = backend.detectVersionDrift();
        assertEquals(2, drifted.size(), "only the two differently-stamped CRs are drift: " + drifted);
        assertTrue(drifted.stream().anyMatch(d -> d.contains("old-a") && d.contains("9.9.9")), drifted.toString());
        assertTrue(drifted.stream().anyMatch(d -> d.contains("old-b") && d.contains("8.8.8")), drifted.toString());
        assertFalse(drifted.stream().anyMatch(d -> d.contains("current")), "the running-version CR is not drift");
        assertFalse(drifted.stream().anyMatch(d -> d.contains("unstamped")), "an unstamped CR is not drift");
    }

    private KeycloakRealmCr stampedRealmCr(String name, String versionStamp) {
        RealmSpec spec = new RealmSpec();
        spec.setRealm(name);
        KeycloakRealmCr cr = new KeycloakRealmCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace("test")
                .addToLabels(K8sStorageBackend.VERSION_LABEL, versionStamp)
                .build());
        cr.setSpec(spec);
        return cr;
    }

    @Test
    void rollbackDiscardsBufferedWritesAndRepairsTheMirror() {
        RealmSpec existing = new RealmSpec();
        existing.setRealm("keep");
        existing.setDisplayName("original");
        client.resource(realmCr("keep", existing)).create();

        start(false);
        FakeKeycloakSession session = beginSession();

        RealmSpec modified = RealmCrStore.read("keep");
        modified.setDisplayName("changed");
        RealmCrStore.save(modified);
        RealmSpec created = new RealmSpec();
        created.setRealm("fresh");
        RealmCrStore.save(created);
        assertEquals("changed", RealmCrStore.read("keep").getDisplayName());
        assertNotNull(RealmCrStore.read("fresh"));

        session.getTransactionManager().rollback();

        assertEquals(
                "original",
                RealmCrStore.read("keep").getDisplayName(),
                "rollback must repair the mirror from the API server");
        assertNull(
                RealmCrStore.read("fresh"),
                "an entity created in the rolled-back transaction must vanish from the mirror");
        assertNull(
                client.resources(KeycloakRealmCr.class)
                        .inNamespace("test")
                        .withName("fresh")
                        .get(),
                "nothing of the rolled-back transaction may reach the API server");
    }

    @Test
    void rollbackRestoresADeletedEntryInTheMirror() {
        RealmSpec existing = new RealmSpec();
        existing.setRealm("undelete");
        client.resource(realmCr("undelete", existing)).create();

        start(false);
        FakeKeycloakSession session = beginSession();

        RealmCrStore.delete("undelete");
        assertNull(RealmCrStore.read("undelete"), "a buffered delete must vanish from the mirror immediately");

        session.getTransactionManager().rollback();

        assertNotNull(RealmCrStore.read("undelete"), "rollback must restore the deleted mirror entry");
        assertNotNull(
                client.resources(KeycloakRealmCr.class)
                        .inNamespace("test")
                        .withName("undelete")
                        .get(),
                "the CR must never have been deleted from the API server");
    }

    @Test
    void deleteWinsOverEarlierUpdatesWithinOneTransaction() {
        RealmSpec existing = new RealmSpec();
        existing.setRealm("gone");
        client.resource(realmCr("gone", existing)).create();

        start(false);
        FakeKeycloakSession session = beginSession();

        RealmSpec modified = RealmCrStore.read("gone");
        modified.setDisplayName("pointless");
        RealmCrStore.save(modified);
        RealmCrStore.delete("gone");

        // created and deleted within the same transaction: nothing to do on the server at all
        RealmSpec ephemeral = new RealmSpec();
        ephemeral.setRealm("ephemeral");
        RealmCrStore.save(ephemeral);
        RealmCrStore.delete("ephemeral");

        session.getTransactionManager().commit();

        assertNull(RealmCrStore.read("gone"));
        assertNull(
                client.resources(KeycloakRealmCr.class)
                        .inNamespace("test")
                        .withName("gone")
                        .get(),
                "the buffered delete must win over the earlier buffered update");
        assertNull(RealmCrStore.read("ephemeral"));
        assertNull(client.resources(KeycloakRealmCr.class)
                .inNamespace("test")
                .withName("ephemeral")
                .get());
    }

    @Test
    void updateAfterDeleteRecreatesWithinOneTransaction() {
        RealmSpec existing = new RealmSpec();
        existing.setRealm("phoenix");
        existing.setDisplayName("old");
        client.resource(realmCr("phoenix", existing)).create();

        start(false);
        FakeKeycloakSession session = beginSession();

        RealmCrStore.delete("phoenix");
        RealmSpec recreated = new RealmSpec();
        recreated.setRealm("phoenix");
        recreated.setDisplayName("new");
        RealmCrStore.save(recreated);

        session.getTransactionManager().commit();

        KeycloakRealmCr cr = client.resources(KeycloakRealmCr.class)
                .inNamespace("test")
                .withName("phoenix")
                .get();
        assertNotNull(cr);
        assertEquals("new", cr.getSpec().getDisplayName(), "the last buffered state wins over the delete");
    }

    private static void awaitNonNull(Supplier<Object> supplier) {
        await(() -> supplier.get() != null);
    }

    private static void awaitNull(Supplier<Object> supplier) {
        await(() -> supplier.get() == null);
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 10s");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /**
     * The slice of {@link KeycloakSession} the write buffer touches - attributes and the
     * transaction manager, which is Keycloak's real {@link DefaultKeycloakTransactionManager},
     * so the tests exercise the actual prepare/commit/rollback phase ordering of the server.
     * Everything else is unsupported.
     */
    private static final class FakeKeycloakSession implements KeycloakSession {

        private final KeycloakTransactionManager transactionManager = new DefaultKeycloakTransactionManager(this);
        private final Map<String, Object> attributes = new HashMap<>();

        @Override
        public KeycloakTransactionManager getTransactionManager() {
            return transactionManager;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Provider> T getProvider(Class<T> clazz) {
            if (clazz == TracingProvider.class) {
                return (T) new NoopTracingProvider();
            }
            return null;
        }

        @Override
        public Object getAttribute(String attribute) {
            return attributes.get(attribute);
        }

        @Override
        public <T> T getAttribute(String attribute, Class<T> clazz) {
            return clazz.cast(attributes.get(attribute));
        }

        @Override
        public Object removeAttribute(String attribute) {
            return attributes.remove(attribute);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public KeycloakContext getContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends Provider> T getProvider(Class<T> clazz, String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends Provider> T getComponentProvider(Class<T> clazz, String componentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends Provider> T getComponentProvider(
                Class<T> clazz, String componentId, Function<KeycloakSessionFactory, ComponentModel> modelGetter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends Provider> T getProvider(Class<T> clazz, ComponentModel componentModel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends Provider> Set<String> listProviderIds(Class<T> clazz) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends Provider> Set<T> getAllProviders(Class<T> clazz) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Class<? extends Provider> getProviderClass(String providerClassName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void invalidate(InvalidableObjectType type, Object... params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void enlistForClose(Provider provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KeycloakSessionFactory getKeycloakSessionFactory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RealmProvider realms() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClientProvider clients() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClientScopeProvider clientScopes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public GroupProvider groups() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RoleProvider roles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserSessionProvider sessions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserLoginFailureProvider loginFailures() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthenticationSessionProvider authenticationSessions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SingleUseObjectProvider singleUseObjects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RevokedTokenProvider revokedTokens() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IdentityProviderStorageProvider identityProviders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserProvider users() {
            throw new UnsupportedOperationException();
        }

        @Override
        public KeyManager keys() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ThemeManager theme() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenManager tokens() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VaultTranscriber vault() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClientPolicyManager clientPolicy() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
