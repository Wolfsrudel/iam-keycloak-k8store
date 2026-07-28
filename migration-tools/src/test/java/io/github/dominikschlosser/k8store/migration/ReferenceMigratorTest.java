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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dominikschlosser.k8store.kubernetes.references.ValueReferenceResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the placeholder-to-valuesFrom translation and proves the emitted entries against the
 * real k8store resolver: what this tool writes must resolve on the k8store read path.
 */
class ReferenceMigratorTest {

    private static final Map<String, EnvVarSources.Source> ENV = Map.of(
            "CLIENT_SECRET", EnvVarSources.Source.secret("keycloak-secrets", "client-secret"),
            "ROOT_URL", EnvVarSources.Source.configMap("keycloak-config", "ROOT_URL"),
            "SMTP_PASSWORD", EnvVarSources.Source.secret("keycloak-secrets", "smtp-password"),
            "LDAP_PASSWORD", EnvVarSources.Source.secret("keycloak-secrets", "LDAP_PASSWORD"),
            "REVISION", EnvVarSources.Source.literal("42"));

    private final ObjectMapper mapper = new ObjectMapper();
    private MigrationReport report;
    private List<CrDocument> docs;

    @BeforeEach
    void migrate() throws Exception {
        JsonNode export = mapper.readTree(getClass().getResourceAsStream("/example-export.json"));
        report = new MigrationReport();
        docs = new ExportSplitter(ExportSplitter.AREAS, report).split(export);
        ReferenceMigrator migrator = new ReferenceMigrator(ENV::get, report);
        docs.forEach(migrator::migrate);
        migrator.summarize();
    }

    private CrDocument doc(String kind, String entityId) {
        return docs.stream()
                .filter(d -> d.kind().equals(kind) && d.entityId().equals(entityId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void rewritesPlaceholderToTheSecretKeyAndAddsTheEntry() {
        JsonNode spec = doc("KeycloakClient", "web-app").spec();
        // env var name and secret key differ, so the placeholder is rewritten to the key
        assertEquals("${client-secret}", spec.get("secret").asText());
        assertTrue(hasEntry(spec, "secret", "secretKeyRef", "keycloak-secrets", "client-secret"));
    }

    @Test
    void configMapKeyMatchingTheVarNameNeedsNoRewrite() {
        JsonNode spec = doc("KeycloakClient", "web-app").spec();
        assertEquals("${ROOT_URL}", spec.get("rootUrl").asText());
        assertEquals("${ROOT_URL}/callback", spec.get("redirectUris").get(0).asText());
        assertTrue(hasEntry(spec, "rootUrl", "configMapKeyRef", "keycloak-config", "ROOT_URL"));
        assertTrue(hasEntry(spec, "redirectUris[0]", "configMapKeyRef", "keycloak-config", "ROOT_URL"));
    }

    @Test
    void plainEnvValueBecomesALiteralEntry() {
        JsonNode spec = doc("KeycloakClient", "web-app").spec();
        assertEquals("rev ${REVISION}", spec.get("description").asText());
        boolean found = false;
        for (JsonNode entry : spec.get("valuesFrom")) {
            if ("description".equals(entry.get("targetPath").asText())) {
                assertEquals("42", entry.get("valueFrom").get("value").asText());
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void defaultValueIsBakedInWhenNoSourceExists() {
        JsonNode spec = doc("KeycloakClient", "web-app").spec();
        assertEquals("fallback", spec.get("attributes").get("note").asText());
    }

    @Test
    void unmatchedPlaceholdersStayVerbatimAndAreReported() {
        JsonNode spec = doc("KeycloakClientScope", "offline_access").spec();
        assertEquals(
                "${offlineAccessScopeConsentText}",
                spec.get("attributes").get("consent.screen.text").asText());
        assertFalse(spec.has("valuesFrom"));
        assertTrue(report.lines().stream()
                .anyMatch(l -> l.contains("offlineAccessScopeConsentText") && l.contains("left verbatim")));
        // env-var-shaped placeholders without a source are warnings, Keycloak-style tokens are not
        assertTrue(report.lines().stream().anyMatch(l -> l.startsWith("WARNING") && l.contains("IDP_SECRET")));
        assertFalse(report.lines().stream()
                .anyMatch(l -> l.startsWith("WARNING") && l.contains("offlineAccessScopeConsentText")));
    }

    @Test
    void realmEntriesCoverNestedComponentConfigAndSmtp() {
        JsonNode spec = doc("KeycloakRealm", "example").spec();
        assertTrue(hasEntry(spec, "smtpServer.password", "secretKeyRef", "keycloak-secrets", "smtp-password"));
        assertTrue(hasEntry(
                spec,
                "components[org.keycloak.storage.UserStorageProvider][0].config.bindCredential[0]",
                "secretKeyRef",
                "keycloak-secrets",
                "LDAP_PASSWORD"));
    }

    /** The emitted references must resolve through the actual k8store resolver. */
    @Test
    void entriesResolveWithTheRealResolver() {
        Map<String, String> secretValues = Map.of(
                "client-secret", "s3cret",
                "smtp-password", "mailpass",
                "LDAP_PASSWORD", "ldappass");
        ValueReferenceResolver resolver = new ValueReferenceResolver(
                (name, key) -> "keycloak-secrets".equals(name) ? secretValues.get(key) : null,
                (name, key) ->
                        "keycloak-config".equals(name) && "ROOT_URL".equals(key) ? "https://app.example.com" : null);

        JsonNode client =
                resolver.resolveTree(doc("KeycloakClient", "web-app").spec().deepCopy());
        assertEquals("s3cret", client.get("secret").asText());
        assertEquals("https://app.example.com", client.get("rootUrl").asText());
        assertEquals(
                "https://app.example.com/callback",
                client.get("redirectUris").get(0).asText());
        assertEquals("rev 42", client.get("description").asText());

        JsonNode realm =
                resolver.resolveTree(doc("KeycloakRealm", "example").spec().deepCopy());
        assertEquals("mailpass", realm.get("smtpServer").get("password").asText());
        assertEquals(
                "ldappass",
                realm.get("components")
                        .get("org.keycloak.storage.UserStorageProvider")
                        .get(0)
                        .get("config")
                        .get("bindCredential")
                        .get(0)
                        .asText());
    }

    private static boolean hasEntry(JsonNode spec, String targetPath, String refField, String name, String key) {
        JsonNode valuesFrom = spec.get("valuesFrom");
        if (valuesFrom == null) {
            return false;
        }
        for (JsonNode entry : valuesFrom) {
            if (targetPath.equals(entry.get("targetPath").asText())) {
                JsonNode ref = entry.get("valueFrom").get(refField);
                if (ref != null
                        && name.equals(ref.get("name").asText())
                        && key.equals(ref.get("key").asText())) {
                    return true;
                }
            }
        }
        return false;
    }
}
