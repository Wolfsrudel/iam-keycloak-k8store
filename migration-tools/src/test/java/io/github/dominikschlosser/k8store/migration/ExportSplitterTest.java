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
package io.github.dominikschlosser.k8store.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExportSplitterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode export;
    private MigrationReport report;
    private List<CrDocument> docs;

    @BeforeEach
    void split() throws Exception {
        export = mapper.readTree(getClass().getResourceAsStream("/example-export.json"));
        report = new MigrationReport();
        docs = new ExportSplitter(ExportSplitter.AREAS, report).split(export);
    }

    private CrDocument doc(String kind, String entityId) {
        return docs.stream()
                .filter(d -> d.kind().equals(kind) && d.entityId().equals(entityId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " for " + entityId));
    }

    @Test
    void emitsOneCrPerEntity() {
        assertEquals(
                1, docs.stream().filter(d -> d.kind().equals("KeycloakRealm")).count());
        assertEquals(
                3, docs.stream().filter(d -> d.kind().equals("KeycloakClient")).count());
        assertEquals(
                3,
                docs.stream()
                        .filter(d -> d.kind().equals("KeycloakClientScope"))
                        .count());
        assertEquals(
                4, docs.stream().filter(d -> d.kind().equals("KeycloakRole")).count());
        assertEquals(
                2, docs.stream().filter(d -> d.kind().equals("KeycloakGroup")).count());
    }

    @Test
    void realmSpecDropsEmbeddedCollectionsAndKeepsConfig() {
        JsonNode realm = doc("KeycloakRealm", "example").spec();
        for (String excluded : new String[] {
            "clients",
            "clientScopes",
            "roles",
            "groups",
            "scopeMappings",
            "clientScopeMappings",
            "users",
            "keycloakVersion"
        }) {
            assertFalse(realm.has(excluded), excluded + " must not be part of the realm spec");
        }
        assertEquals("example", realm.get("realm").asText());
        assertEquals("example", realm.get("id").asText());
        assertEquals("example-realm", realm.get("masterAdminClient").asText());
        // export shapes copy through: flows, configs, components, identity providers
        assertEquals(
                "browser", realm.get("authenticationFlows").get(0).get("alias").asText());
        assertEquals(
                "forms",
                realm.get("authenticationFlows")
                        .get(0)
                        .get("authenticationExecutions")
                        .get(1)
                        .get("flowAlias")
                        .asText());
        assertEquals(
                "review profile config",
                realm.get("authenticatorConfig").get(0).get("alias").asText());
        JsonNode ldap = realm.get("components")
                .get("org.keycloak.storage.UserStorageProvider")
                .get(0);
        assertEquals(
                "${LDAP_PASSWORD}",
                ldap.get("config").get("bindCredential").get(0).asText());
        assertEquals(
                "uid",
                ldap.get("subComponents")
                        .get("org.keycloak.storage.ldap.mappers.LDAPStorageMapper")
                        .get(0)
                        .get("config")
                        .get("ldap.attribute")
                        .get(0)
                        .asText());
        assertEquals(
                "${IDP_SECRET}",
                realm.get("identityProviders")
                        .get(0)
                        .get("config")
                        .get("clientSecret")
                        .asText());
        assertEquals("${SMTP_PASSWORD}", realm.get("smtpServer").get("password").asText());
    }

    @Test
    void emptyUserProfileComponentIsDroppedWithAWarning() {
        JsonNode components = doc("KeycloakRealm", "example").spec().get("components");
        assertFalse(
                components.has("org.keycloak.userprofile.UserProfileProvider"),
                "a user-profile component without configuration must not survive the migration");
        assertTrue(
                report.lines().stream().anyMatch(l -> l.startsWith("WARNING") && l.contains("user-profile component")));
    }

    @Test
    void emptyUserProfileComponentOnMasterGetsTheRelaxedBootstrapConfig() throws Exception {
        JsonNode masterExport = mapper.readTree("""
                {"realm": "master", "enabled": true, "components": {
                  "org.keycloak.userprofile.UserProfileProvider": [
                    {"id": "up", "providerId": "declarative-user-profile", "subComponents": {}, "config": {}}
                  ]}}""");
        MigrationReport masterReport = new MigrationReport();
        List<CrDocument> masterDocs = new ExportSplitter(ExportSplitter.AREAS, masterReport).split(masterExport);
        JsonNode component = masterDocs
                .get(0)
                .spec()
                .get("components")
                .get("org.keycloak.userprofile.UserProfileProvider")
                .get(0);
        String config =
                component.get("config").get("kc.user.profile.config").get(0).asText();
        JsonNode parsed = mapper.readTree(config);
        // the relaxed master profile Keycloak's bootstrap writes: attributes present, nothing
        // required except (implicitly) the username
        assertTrue(parsed.get("attributes").size() >= 4);
        for (JsonNode attribute : parsed.get("attributes")) {
            assertFalse(attribute.has("required"), attribute.get("name").asText() + " must not be required on master");
        }
        assertTrue(masterReport.lines().stream()
                .anyMatch(l -> l.startsWith("WARNING") && l.contains("Restored the relaxed master profile")));
    }

    @Test
    void clientProfilesMoveIntoTheServedAttribute() {
        JsonNode attributes = doc("KeycloakRealm", "example").spec().get("attributes");
        String profiles = attributes.get("client-policies.profiles").asText();
        assertTrue(profiles.contains("custom-profile"));
        assertFalse(profiles.contains("built-in-global-profile"), "global profiles are Keycloak built-ins");
        // empty policies list -> no attribute
        assertNull(attributes.get("client-policies.policies"));
    }

    @Test
    void clientCarriesScopeMappingsAndClientIdAsId() {
        CrDocument webApp = doc("KeycloakClient", "web-app");
        assertEquals("example.web-app", webApp.name());
        JsonNode spec = webApp.spec();
        assertEquals("example", spec.get("realm").asText());
        assertEquals("web-app", spec.get("id").asText());
        assertEquals("offline_access", spec.get("realmScopeMappings").get(0).asText());
        assertEquals(
                "view-users",
                spec.get("clientScopeMappings").get("realm-management").get(0).asText());
        assertEquals("${CLIENT_SECRET}", spec.get("secret").asText());
    }

    @Test
    void authorizationSettingsAreDroppedWithAWarning() {
        assertFalse(doc("KeycloakClient", "service").spec().has("authorizationSettings"));
        assertTrue(report.lines().stream().anyMatch(l -> l.contains("authorizationSettings")));
    }

    @Test
    void clientScopeCarriesRealmRoleMappings() {
        JsonNode spec = doc("KeycloakClientScope", "offline_access").spec();
        assertEquals("offline_access", spec.get("realmScopeMappings").get(0).asText());
        assertEquals("example", spec.get("realm").asText());
    }

    @Test
    void rolesKeepExportIdsAndGetContainerReferences() {
        JsonNode admin = doc("KeycloakRole", "admin").spec();
        assertEquals("example", admin.get("containerId").asText());
        assertFalse(admin.get("clientRole").asBoolean());
        assertEquals(
                "view-users",
                admin.get("composites")
                        .get("client")
                        .get("realm-management")
                        .get(0)
                        .asText());

        CrDocument editor = doc("KeycloakRole", "web-app:editor");
        JsonNode editorSpec = editor.spec();
        assertEquals("web-app", editorSpec.get("containerId").asText());
        assertTrue(editorSpec.get("clientRole").asBoolean());
        // the ':' in the id is not DNS-safe, so the CR name carries the disambiguating hash
        assertTrue(editor.name().startsWith("example.web-app-editor-"));
    }

    @Test
    void groupsAreFlattenedWithParentReferences() {
        JsonNode admins = doc("KeycloakGroup", "admins").spec();
        assertFalse(admins.has("parentId"));
        assertFalse(admins.has("path"), "path is derivable and not persisted");
        assertFalse(admins.has("subGroups"));
        assertEquals("admin", admins.get("realmRoles").get(0).asText());

        JsonNode ops = doc("KeycloakGroup", "admins:ops").spec();
        assertEquals("admins", ops.get("parentId").asText());
        assertEquals("editor", ops.get("clientRoles").get("web-app").get(0).asText());
    }

    @Test
    void areaSelectionSkipsUnselectedKinds() {
        MigrationReport partialReport = new MigrationReport();
        List<CrDocument> partial = new ExportSplitter(Set.of("realm", "client"), partialReport).split(export);
        assertTrue(partial.stream()
                .allMatch(d -> d.kind().equals("KeycloakRealm") || d.kind().equals("KeycloakClient")));
        assertTrue(partialReport.lines().stream().anyMatch(l -> l.contains("roles skipped")));
    }
}
