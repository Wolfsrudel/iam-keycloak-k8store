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

import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Deterministic naming of the custom resources this store writes. Pure string logic, shared
 * between the storage backend and external tooling (the migration CLI), so both produce the
 * same names for the same entities.
 */
public final class CrNaming {

    private CrNaming() {}

    /**
     * Deterministic DNS-1123 name for a CR written by Keycloak. A realm CR is named {@code <id>};
     * a scoped CR {@code <realm>.<id>}, two dot-separated DNS-1123 labels. A hash suffix is
     * appended only when {@link #dnsLabel} actually changed a component: {@code dnsLabel} is lossy
     * (it folds arbitrary ids to DNS characters and truncates), so two entities that sanitize
     * alike would otherwise collide on one name; the hash over the exact {@code (realmId, id)}
     * pair keeps them apart. When every component survives {@code dnsLabel} unchanged there is
     * nothing to disambiguate - distinct clean pairs already yield distinct names - so the
     * readable name is used as-is (e.g. {@code master.web-origins}).
     */
    public static String crName(Class<?> crClass, String realmId, String id) {
        if (crClass == KeycloakRealmCr.class) {
            String label = dnsLabel(id);
            return label.equals(id) ? label : label + "-" + shortHash(id);
        }
        String realmLabel = dnsLabel(realmId);
        String idLabel = dnsLabel(id);
        String name = realmLabel + "." + idLabel;
        if (realmLabel.equals(realmId) && idLabel.equals(id)) {
            return name;
        }
        return name + "-" + shortHash(realmId + K8sStorageBackend.KEY_SEPARATOR + id);
    }

    /** A valid Kubernetes label value derived from an arbitrary string, hash-suffixed when lossy. */
    public static String labelValue(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "-")
                // label values must start and end alphanumeric (e.g. the "@global" pseudo-realm)
                .replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
        if (sanitized.isEmpty()) {
            sanitized = "x";
        }
        if (sanitized.length() > 63) {
            sanitized = sanitized.substring(0, 55) + "-" + shortHash(value).substring(0, 7);
        }
        return sanitized;
    }

    /**
     * One DNS-1123 label from an arbitrary string: lowercase, runs of non-alphanumeric characters
     * become a single hyphen, edges trimmed, capped short enough to leave room for a
     * {@code "-<hash>"} suffix within the 63-character label limit.
     */
    private static String dnsLabel(String raw) {
        String label =
                raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (label.length() > 54) {
            label = label.substring(0, 54).replaceAll("-+$", "");
        }
        return label.isEmpty() ? "x" : label;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
