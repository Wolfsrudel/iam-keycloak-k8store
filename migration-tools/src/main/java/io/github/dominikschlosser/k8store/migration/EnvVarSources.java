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

/**
 * Where an environment variable of the filestore deployment got its value from. The reference
 * migration uses this to translate a {@code ${VAR}} placeholder into the equivalent k8store
 * {@code valuesFrom} entry.
 */
@FunctionalInterface
public interface EnvVarSources {

    /** The source feeding {@code envName}, or {@code null} when the deployment does not set it. */
    Source lookup(String envName);

    enum Kind {
        SECRET,
        CONFIG_MAP,
        LITERAL
    }

    /** For SECRET/CONFIG_MAP: object {@code name} and {@code key}. For LITERAL: the plain {@code value}. */
    record Source(Kind kind, String name, String key, String value) {

        public static Source secret(String name, String key) {
            return new Source(Kind.SECRET, name, key, null);
        }

        public static Source configMap(String name, String key) {
            return new Source(Kind.CONFIG_MAP, name, key, null);
        }

        public static Source literal(String value) {
            return new Source(Kind.LITERAL, null, null, value);
        }
    }
}
