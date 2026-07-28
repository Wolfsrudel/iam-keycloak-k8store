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

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the env-var translation table from the filestore Keycloak Deployment: which environment
 * variable of the Keycloak container came from which Secret/ConfigMap key (or was a plain value).
 *
 * <p>{@code envFrom} imports are expanded by reading the referenced Secret/ConfigMap and listing
 * its keys (with the optional prefix applied); explicit {@code env} entries are collected
 * directly and, matching Kubernetes semantics, override {@code envFrom} imports of the same name.
 * {@code fieldRef}/{@code resourceFieldRef} sources have no k8store equivalent and are reported.
 */
public final class DeploymentEnvReader {

    private DeploymentEnvReader() {}

    public static EnvVarSources read(
            KubernetesClient client,
            String namespace,
            String deploymentName,
            String containerName,
            MigrationReport report) {
        Deployment deployment = client.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(deploymentName)
                .get();
        if (deployment == null) {
            throw new IllegalArgumentException(
                    "deployment '" + deploymentName + "' not found in namespace '" + namespace + "'");
        }
        Container container = pickContainer(deployment, deploymentName, containerName, report);

        Map<String, EnvVarSources.Source> byName = new LinkedHashMap<>();
        if (container.getEnvFrom() != null) {
            for (EnvFromSource envFrom : container.getEnvFrom()) {
                String prefix = envFrom.getPrefix() == null ? "" : envFrom.getPrefix();
                if (envFrom.getSecretRef() != null) {
                    String name = envFrom.getSecretRef().getName();
                    Secret secret = client.secrets()
                            .inNamespace(namespace)
                            .withName(name)
                            .get();
                    if (secret == null || secret.getData() == null) {
                        report.warn("envFrom secret '" + name + "' not readable - its keys cannot be matched");
                        continue;
                    }
                    for (String key : secret.getData().keySet()) {
                        byName.put(prefix + key, EnvVarSources.Source.secret(name, key));
                    }
                }
                if (envFrom.getConfigMapRef() != null) {
                    String name = envFrom.getConfigMapRef().getName();
                    ConfigMap configMap = client.configMaps()
                            .inNamespace(namespace)
                            .withName(name)
                            .get();
                    if (configMap == null || configMap.getData() == null) {
                        report.warn("envFrom configmap '" + name + "' not readable - its keys cannot be matched");
                        continue;
                    }
                    for (String key : configMap.getData().keySet()) {
                        byName.put(prefix + key, EnvVarSources.Source.configMap(name, key));
                    }
                }
            }
        }
        if (container.getEnv() != null) {
            for (EnvVar env : container.getEnv()) {
                if (env.getValueFrom() != null) {
                    if (env.getValueFrom().getSecretKeyRef() != null) {
                        byName.put(
                                env.getName(),
                                EnvVarSources.Source.secret(
                                        env.getValueFrom().getSecretKeyRef().getName(),
                                        env.getValueFrom().getSecretKeyRef().getKey()));
                    } else if (env.getValueFrom().getConfigMapKeyRef() != null) {
                        byName.put(
                                env.getName(),
                                EnvVarSources.Source.configMap(
                                        env.getValueFrom().getConfigMapKeyRef().getName(),
                                        env.getValueFrom().getConfigMapKeyRef().getKey()));
                    } else {
                        report.warn("env var '" + env.getName() + "' uses a fieldRef/resourceFieldRef source,"
                                + " which has no k8store equivalent - placeholders fed by it stay verbatim");
                    }
                } else if (env.getValue() != null) {
                    byName.put(env.getName(), EnvVarSources.Source.literal(env.getValue()));
                }
            }
        }
        report.info("deployment '" + deploymentName + "' container '" + container.getName() + "': " + byName.size()
                + " environment variable(s) available for placeholder matching");
        return byName::get;
    }

    private static Container pickContainer(
            Deployment deployment, String deploymentName, String containerName, MigrationReport report) {
        var containers = deployment.getSpec().getTemplate().getSpec().getContainers();
        if (containers == null || containers.isEmpty()) {
            throw new IllegalArgumentException("deployment '" + deploymentName + "' has no containers");
        }
        if (containerName != null) {
            return containers.stream()
                    .filter(c -> containerName.equals(c.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "deployment '" + deploymentName + "' has no container named '" + containerName + "'"));
        }
        if (containers.size() > 1) {
            report.info("deployment '" + deploymentName + "' has " + containers.size()
                    + " containers, using the first ('" + containers.get(0).getName()
                    + "') - pass --container to pick another");
        }
        return containers.get(0);
    }
}
