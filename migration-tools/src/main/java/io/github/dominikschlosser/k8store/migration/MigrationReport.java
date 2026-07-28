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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects what the migration did and what needs operator attention. Written next to the
 * generated CRs so the decisions ("this placeholder was left verbatim", "this literal was baked
 * in") stay reviewable alongside them.
 */
public final class MigrationReport {

    private final List<String> lines = new ArrayList<>();
    private int warnings;

    public void info(String message) {
        lines.add(message);
    }

    public void warn(String message) {
        lines.add("WARNING: " + message);
        warnings++;
    }

    public int warningCount() {
        return warnings;
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }
}
