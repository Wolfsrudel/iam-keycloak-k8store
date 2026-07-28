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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.dominikschlosser.k8store.kubernetes.CrNaming;
import io.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * CLI that converts a Keycloak realm export ({@code kc.sh export}) into k8store custom resource
 * YAML files ready to version-control and {@code kubectl apply}.
 *
 * <p>Intended migration path from the filestore extension (or any other store): run the export
 * locally against the existing configuration <b>with the placeholder environment variables
 * unset</b>, so {@code ${VAR}} placeholders survive into the export verbatim, then feed the
 * export to this tool. With {@code --migrate-references} the tool additionally inspects the old
 * deployment's container environment and turns matching placeholders into
 * {@code valuesFrom} Secret/ConfigMap references.
 */
public final class MigrationTools {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 2;

    private final PrintStream out;
    private final PrintStream err;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    // numeric-looking strings must stay quoted or a YAML re-parse turns them into numbers,
    // which the CRD schema rejects for map<string,string> fields like attributes
    private final YAMLMapper yamlMapper = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .build();

    public MigrationTools(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new MigrationTools(System.out, System.err).run(args));
    }

    int run(String[] args) {
        Path input = null;
        Path output = null;
        Set<String> realms = new LinkedHashSet<>();
        Set<String> areas = new LinkedHashSet<>(ExportSplitter.AREAS);
        boolean migrateReferences = false;
        String namespace = null;
        String deployment = null;
        String container = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    printUsage(out);
                    return EXIT_OK;
                }
                case "--migrate-references" -> migrateReferences = true;
                case "--realm" -> realms.add(requireValue(args, ++i, arg));
                case "--areas" -> {
                    areas = new LinkedHashSet<>(
                            Arrays.asList(requireValue(args, ++i, arg).split(",")));
                    if (!ExportSplitter.AREAS.containsAll(areas)) {
                        areas.removeAll(ExportSplitter.AREAS);
                        err.println("Unknown area(s): " + String.join(", ", areas) + " (valid: "
                                + String.join(", ", ExportSplitter.AREAS) + ")");
                        return EXIT_ERROR;
                    }
                }
                case "--namespace" -> namespace = requireValue(args, ++i, arg);
                case "--deployment" -> deployment = requireValue(args, ++i, arg);
                case "--container" -> container = requireValue(args, ++i, arg);
                default -> {
                    if (arg.startsWith("--")) {
                        err.println("Unknown option: " + arg);
                        printUsage(err);
                        return EXIT_ERROR;
                    }
                    if (input == null) {
                        input = Path.of(arg);
                    } else if (output == null) {
                        output = Path.of(arg);
                    } else {
                        err.println("Unexpected argument: " + arg);
                        printUsage(err);
                        return EXIT_ERROR;
                    }
                }
            }
        }
        if (input == null || output == null) {
            printUsage(err);
            return EXIT_ERROR;
        }
        if (migrateReferences && (namespace == null || deployment == null)) {
            err.println("--migrate-references needs --namespace and --deployment");
            return EXIT_ERROR;
        }

        try {
            MigrationReport report = new MigrationReport();
            List<JsonNode> exportedRealms = loadExport(input, realms, report);
            if (exportedRealms.isEmpty()) {
                err.println("No realms found in " + input
                        + (realms.isEmpty() ? "" : " matching " + String.join(", ", realms)));
                return EXIT_ERROR;
            }

            ReferenceMigrator referenceMigrator = null;
            if (migrateReferences) {
                try (KubernetesClient client = new KubernetesClientBuilder().build()) {
                    EnvVarSources sources = DeploymentEnvReader.read(client, namespace, deployment, container, report);
                    referenceMigrator = new ReferenceMigrator(sources, report);
                    convert(exportedRealms, output, areas, referenceMigrator, report);
                }
            } else {
                convert(exportedRealms, output, areas, null, report);
            }

            Path reportFile = output.resolve("migration-report.txt");
            report.write(reportFile);
            out.println();
            out.println("Report: " + reportFile);
            if (report.warningCount() > 0) {
                out.println(report.warningCount() + " warning(s) - review the report before applying the CRs.");
            }
            return EXIT_OK;
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return EXIT_ERROR;
        } catch (RuntimeException e) {
            err.println("Error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return EXIT_ERROR;
        }
    }

    private void convert(
            List<JsonNode> exportedRealms,
            Path output,
            Set<String> areas,
            ReferenceMigrator referenceMigrator,
            MigrationReport report)
            throws IOException {
        ExportSplitter splitter = new ExportSplitter(areas, report);
        for (JsonNode exportedRealm : exportedRealms) {
            List<CrDocument> docs = splitter.split(exportedRealm);
            if (referenceMigrator != null) {
                for (CrDocument doc : docs) {
                    referenceMigrator.migrate(doc);
                }
            }
            writeDocs(docs, output);
            String realm = exportedRealm.get("realm").asText();
            out.println(realm + ": " + docs.size() + " custom resource(s) written");
        }
        if (referenceMigrator != null) {
            referenceMigrator.summarize();
        }
    }

    private void writeDocs(List<CrDocument> docs, Path output) throws IOException {
        for (CrDocument doc : docs) {
            Path file;
            if (doc.category() == null) {
                file = output.resolve(doc.name() + ".yaml");
            } else {
                String realmDir = CrNaming.crName(KeycloakRealmCr.class, doc.realm(), doc.realm());
                file = output.resolve(realmDir).resolve(doc.category()).resolve(doc.fileName() + ".yaml");
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, yamlMapper.writeValueAsString(envelope(doc)));
        }
    }

    private ObjectNode envelope(CrDocument doc) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("apiVersion", doc.apiVersion());
        root.put("kind", doc.kind());
        ObjectNode metadata = root.putObject("metadata");
        metadata.put("name", doc.name());
        metadata.putObject("labels").put(K8sStorageBackend.REALM_LABEL, CrNaming.labelValue(doc.realm()));
        root.set("spec", doc.spec());
        return root;
    }

    /**
     * Loads realms from a single export file (one realm object or an array of them) or an export
     * directory ({@code <realm>-realm.json} files; {@code *-users-*.json} files are ignored - users
     * stay in the database).
     */
    private List<JsonNode> loadExport(Path input, Set<String> realmFilter, MigrationReport report) throws IOException {
        List<JsonNode> realms = new ArrayList<>();
        if (Files.isDirectory(input)) {
            try (Stream<Path> files = Files.list(input)) {
                List<Path> candidates = files.filter(
                                f -> f.getFileName().toString().endsWith(".json"))
                        .filter(f -> !f.getFileName().toString().matches(".*-(federated-)?users-\\d+\\.json"))
                        .sorted()
                        .toList();
                for (Path file : candidates) {
                    JsonNode node = jsonMapper.readTree(Files.readString(file));
                    collectRealms(node, realms);
                }
            }
        } else {
            JsonNode node = jsonMapper.readTree(Files.readString(input));
            collectRealms(node, realms);
        }
        if (!realmFilter.isEmpty()) {
            List<JsonNode> filtered = new ArrayList<>();
            for (JsonNode realm : realms) {
                if (realmFilter.contains(realm.get("realm").asText())) {
                    filtered.add(realm);
                } else {
                    report.info(realm.get("realm").asText() + ": skipped (not in --realm selection)");
                }
            }
            return filtered;
        }
        return realms;
    }

    private void collectRealms(JsonNode node, List<JsonNode> realms) {
        if (node.isArray()) {
            node.forEach(element -> collectRealms(element, realms));
            return;
        }
        if (node.isObject() && node.hasNonNull("realm") && node.get("realm").isTextual()) {
            realms.add(node);
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static void printUsage(PrintStream stream) {
        stream.println("""
                Usage: migration-tools <export-file-or-dir> <output-dir> [options]

                Converts a Keycloak realm export (kc.sh export) into k8store custom resource YAML.
                Run the export with placeholder environment variables UNSET so ${VAR} placeholders
                survive into the export, e.g.:

                  kc.sh export --dir /tmp/export --users skip

                Options:
                  --realm <name>         convert only this realm (repeatable; default: all)
                  --areas <list>         comma-separated areas to emit CRs for
                                         (realm,client,client-scope,role,group; default: all)
                  --migrate-references   translate ${VAR} placeholders into valuesFrom references
                                         by inspecting the old deployment's container environment
                  --namespace <ns>       namespace of that deployment (with --migrate-references)
                  --deployment <name>    deployment to inspect (with --migrate-references)
                  --container <name>     container to inspect (default: the first one)
                  -h, --help             show this help""");
    }
}
