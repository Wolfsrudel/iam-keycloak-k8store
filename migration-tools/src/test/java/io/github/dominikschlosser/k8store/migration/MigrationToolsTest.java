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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationToolsTest {

    @TempDir
    Path tmp;

    private final YAMLMapper yaml = new YAMLMapper();

    private int run(String... args) {
        return new MigrationTools(new PrintStream(new ByteArrayOutputStream()), System.err).run(args);
    }

    private Path exportFile() throws Exception {
        Path file = tmp.resolve("example-realm.json");
        try (InputStream in = getClass().getResourceAsStream("/example-export.json")) {
            Files.copy(in, file);
        }
        return file;
    }

    @Test
    void writesOneYamlFilePerCrAndAReport() throws Exception {
        Path out = tmp.resolve("out");
        assertEquals(MigrationTools.EXIT_OK, run(exportFile().toString(), out.toString()));

        JsonNode realm = yaml.readTree(Files.readString(out.resolve("example.yaml")));
        assertEquals("KeycloakRealm", realm.get("kind").asText());
        assertEquals(
                "k8store.dominikschlosser.github.io/v1alpha1",
                realm.get("apiVersion").asText());
        assertEquals("example", realm.get("metadata").get("name").asText());
        assertEquals(
                "example",
                realm.get("metadata")
                        .get("labels")
                        .get("k8store.dominikschlosser.github.io/realm")
                        .asText());
        // numeric-looking attribute values must survive a YAML round trip as strings
        assertTrue(realm.get("spec").get("attributes").get("retention").isTextual());

        JsonNode client = yaml.readTree(Files.readString(out.resolve("example/clients/web-app.yaml")));
        assertEquals("KeycloakClient", client.get("kind").asText());
        assertEquals("${CLIENT_SECRET}", client.get("spec").get("secret").asText());

        assertTrue(Files.exists(out.resolve("example/client-scopes/profile.yaml")));
        assertTrue(Files.exists(out.resolve("example/roles/admin.yaml")));
        assertTrue(Files.exists(out.resolve("example/groups/admins.yaml")));
        assertTrue(Files.exists(out.resolve("migration-report.txt")));
    }

    @Test
    void exportDirectoryIsAcceptedAndUsersFilesAreIgnored() throws Exception {
        exportFile();
        Files.writeString(tmp.resolve("example-users-0.json"), "{\"realm\":\"example\",\"users\":[]}");
        Path out = tmp.resolve("out");
        assertEquals(MigrationTools.EXIT_OK, run(tmp.toString(), out.toString()));
        assertTrue(Files.exists(out.resolve("example.yaml")));
    }

    @Test
    void areaSelectionLimitsTheOutput() throws Exception {
        Path out = tmp.resolve("out");
        assertEquals(MigrationTools.EXIT_OK, run(exportFile().toString(), out.toString(), "--areas", "realm,client"));
        assertTrue(Files.exists(out.resolve("example/clients/web-app.yaml")));
        assertTrue(Files.notExists(out.resolve("example/roles")));
        assertTrue(Files.notExists(out.resolve("example/groups")));
    }

    @Test
    void realmFilterAndBadArgumentsAreHandled() throws Exception {
        Path out = tmp.resolve("out");
        String export = exportFile().toString();
        assertNotEquals(MigrationTools.EXIT_OK, run(export, out.toString(), "--realm", "other"));
        assertNotEquals(MigrationTools.EXIT_OK, run(export, out.toString(), "--areas", "bogus"));
        assertNotEquals(MigrationTools.EXIT_OK, run(export, out.toString(), "--migrate-references"));
        assertNotEquals(MigrationTools.EXIT_OK, run());
    }
}
