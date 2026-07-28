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

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One custom resource produced by the migration: the envelope facts (kind, api version, CR name)
 * plus the spec as a mutable JSON tree, so the reference migration can rewrite placeholder
 * strings and attach {@code valuesFrom} before the document is written.
 */
public record CrDocument(
        String kind, String apiVersion, String realm, String entityId, String name, String category, ObjectNode spec) {

    /** File name inside the realm directory: the CR name without the leading realm label. */
    public String fileName() {
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
