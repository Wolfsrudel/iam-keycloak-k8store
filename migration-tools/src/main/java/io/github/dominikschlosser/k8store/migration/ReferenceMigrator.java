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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the {@code ${VAR}} placeholders of a migrated spec into k8store {@code valuesFrom}
 * references, using the environment of the old filestore deployment as the translation table.
 *
 * <p>Filestore substituted placeholders from environment variables at file-parse time; k8store
 * resolves declared references on the read path instead. For a placeholder whose variable the
 * deployment fed from a Secret or ConfigMap key, the equivalent reference is a
 * {@code secretKeyRef}/{@code configMapKeyRef} entry at the string's path - with the placeholder
 * rewritten to {@code ${<key>}} when the variable name and the object key differ, because the
 * resolver pairs a placeholder with the referenced key. A variable set to a plain value becomes a
 * literal entry when the string holds exactly one placeholder (a literal replaces the first
 * {@code ${...}} in the string, so more than one would be ambiguous). A {@code ${VAR:-default}}
 * with no source gets its default baked in, matching what filestore resolved at boot with the
 * variable unset. Everything unresolved is left verbatim and reported - k8store serves undeclared
 * placeholders as-is, exactly like the export contained them.
 */
public final class ReferenceMigrator {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^}]+)}");

    private final EnvVarSources sources;
    private final MigrationReport report;

    public ReferenceMigrator(EnvVarSources sources, MigrationReport report) {
        this.sources = sources;
        this.report = report;
    }

    /** Placeholder names that read like environment variables, as opposed to Keycloak's own tokens. */
    private static final Pattern ENV_VAR_SHAPED = Pattern.compile("[A-Z][A-Z0-9_]*");

    /** Distinct non-env-shaped tokens left verbatim, and in how many documents each appeared. */
    private final Map<String, Integer> verbatimTokens = new LinkedHashMap<>();

    /** Rewrites placeholder strings in place and attaches the collected {@code valuesFrom}. */
    public void migrate(CrDocument doc) {
        ArrayNode entries = JsonNodeFactory.instance.arrayNode();
        Set<String> entryKeys = new HashSet<>();
        Set<String> keycloakTokens = new LinkedHashSet<>();
        String where = doc.kind() + "/" + doc.name();
        walk(doc.spec(), "", (parent, slot, path, text) -> {
            String updated = migrateString(where, path, text, entries, entryKeys, keycloakTokens);
            if (!updated.equals(text)) {
                set(parent, slot, updated);
            }
        });
        if (!entries.isEmpty()) {
            doc.spec().set("valuesFrom", entries);
        }
        for (String token : keycloakTokens) {
            verbatimTokens.merge(token, 1, Integer::sum);
        }
    }

    /**
     * One summary line for all placeholders that had no source and do not read like environment
     * variables. Those are almost always Keycloak's own tokens (localization keys like
     * {@code ${role_admin}}), which must stay verbatim - so they are collected instead of warned
     * per occurrence. Call after the last {@link #migrate}.
     */
    public void summarize() {
        if (verbatimTokens.isEmpty()) {
            return;
        }
        String tokens = verbatimTokens.entrySet().stream()
                .map(e -> e.getValue() == 1 ? e.getKey() : e.getKey() + " (" + e.getValue() + " CRs)")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        report.info("left verbatim, no source and not environment-variable-shaped (most likely Keycloak's own"
                + " tokens, which must stay as they are): " + tokens);
    }

    private String migrateString(
            String where,
            String path,
            String text,
            ArrayNode entries,
            Set<String> entryKeys,
            Set<String> keycloakTokens) {
        if (path == null) {
            // a segment of this path cannot be expressed in targetPath syntax
            if (TOKEN.matcher(text).find()) {
                report.warn(where + ": placeholder in a field whose path cannot be expressed as a"
                        + " targetPath, left verbatim: " + text);
            }
            return text;
        }
        for (String token : tokensOf(text)) {
            Matcher parts = TOKEN.matcher(token);
            if (!parts.matches()) {
                continue;
            }
            String inner = parts.group(1);
            int defSep = inner.indexOf(":-");
            String var = defSep < 0 ? inner : inner.substring(0, defSep);
            String defaultValue = defSep < 0 ? null : inner.substring(defSep + 2);

            EnvVarSources.Source source = sources.lookup(var);
            if (source == null) {
                if (defaultValue != null) {
                    text = text.replace(token, defaultValue);
                    report.info(where + " " + path + ": no source for ${" + var + "}, default '" + defaultValue
                            + "' baked in (what filestore resolved with the variable unset)");
                } else if (ENV_VAR_SHAPED.matcher(var).matches()) {
                    report.warn(where + " " + path + ": no source found for ${" + var + "}, left verbatim");
                } else {
                    keycloakTokens.add(token);
                }
                continue;
            }
            switch (source.kind()) {
                case LITERAL -> {
                    if (countTokens(text) == 1) {
                        entries.add(literalEntry(path, source.value()));
                        report.info(where + " " + path + ": ${" + var + "} mapped to a literal valuesFrom entry"
                                + " (plain env value in the deployment)");
                    } else {
                        report.warn(where + " " + path + ": ${" + var + "} has a plain env value but the string"
                                + " holds several placeholders - literal entries are position-based, migrate"
                                + " this one by hand");
                    }
                }
                case SECRET, CONFIG_MAP -> {
                    String keyToken = "${" + source.key() + "}";
                    if (!token.equals(keyToken)) {
                        if (text.contains(keyToken)) {
                            report.warn(where + " " + path + ": cannot rewrite ${" + var + "} to " + keyToken
                                    + " - the string already contains that placeholder, migrate by hand");
                            continue;
                        }
                        text = text.replace(token, keyToken);
                    }
                    String entryKey = path + "\0" + source.kind() + "\0" + source.name() + "\0" + source.key();
                    if (entryKeys.add(entryKey)) {
                        entries.add(keyRefEntry(path, source));
                        report.info(where + " " + path + ": ${" + var + "} -> "
                                + (source.kind() == EnvVarSources.Kind.SECRET ? "secret" : "configmap") + " '"
                                + source.name() + "' key '" + source.key() + "'");
                    }
                }
            }
        }
        return text;
    }

    private static ObjectNode literalEntry(String path, String value) {
        ObjectNode entry = JsonNodeFactory.instance.objectNode();
        entry.put("targetPath", path);
        entry.putObject("valueFrom").put("value", value);
        return entry;
    }

    private static ObjectNode keyRefEntry(String path, EnvVarSources.Source source) {
        ObjectNode entry = JsonNodeFactory.instance.objectNode();
        entry.put("targetPath", path);
        ObjectNode ref = entry.putObject("valueFrom")
                .putObject(source.kind() == EnvVarSources.Kind.SECRET ? "secretKeyRef" : "configMapKeyRef");
        ref.put("name", source.name());
        ref.put("key", source.key());
        return entry;
    }

    /** Distinct placeholder tokens of {@code text}, in order of first appearance. */
    private static List<String> tokensOf(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return new ArrayList<>(tokens);
    }

    private static int countTokens(String text) {
        int count = 0;
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ tree walk

    private interface StringVisitor {
        /** {@code path} is {@code null} when a segment cannot be expressed in targetPath syntax. */
        void visit(JsonNode parent, Object slot, String path, String text);
    }

    private static void walk(JsonNode node, String path, StringVisitor visitor) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                String childPath = fieldPath(path, field.getKey());
                JsonNode child = field.getValue();
                if (child instanceof TextNode text) {
                    visitor.visit(object, field.getKey(), childPath, text.asText());
                } else {
                    walk(child, childPath, visitor);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                String childPath = path == null ? null : path + "[" + i + "]";
                JsonNode child = array.get(i);
                if (child instanceof TextNode text) {
                    visitor.visit(array, i, childPath, text.asText());
                } else {
                    walk(child, childPath, visitor);
                }
            }
        }
    }

    /**
     * Appends a field segment following the resolver's targetPath grammar: plain fields join with
     * a dot, a field containing dots or brackets uses the {@code [key]} form. A key containing a
     * closing bracket cannot be expressed at all - the path degrades to {@code null} and strings
     * below it are reported instead of migrated.
     */
    private static String fieldPath(String parent, String field) {
        if (parent == null) {
            return null;
        }
        if (field.contains("]")) {
            return null;
        }
        if (field.contains(".") || field.contains("[")) {
            return parent + "[" + field + "]";
        }
        return parent.isEmpty() ? field : parent + "." + field;
    }

    private static void set(JsonNode parent, Object slot, String value) {
        if (parent instanceof ObjectNode object && slot instanceof String field) {
            object.set(field, TextNode.valueOf(value));
        } else if (parent instanceof ArrayNode array && slot instanceof Integer index) {
            array.set(index, TextNode.valueOf(value));
        }
    }
}
