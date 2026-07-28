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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class DeploymentEnvReaderTest {

    KubernetesClient client;

    @BeforeEach
    void setUp() {
        client.apps()
                .deployments()
                .inNamespace("keycloak")
                .resource(new DeploymentBuilder()
                        .withNewMetadata()
                        .withName("keycloak")
                        .endMetadata()
                        .withNewSpec()
                        .withNewTemplate()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("keycloak")
                        .addNewEnv()
                        .withName("CLIENT_SECRET")
                        .withNewValueFrom()
                        .withNewSecretKeyRef("client-secret", "keycloak-secrets", null)
                        .endValueFrom()
                        .endEnv()
                        .addNewEnv()
                        .withName("ROOT_URL")
                        .withNewValueFrom()
                        .withNewConfigMapKeyRef("ROOT_URL", "keycloak-config", null)
                        .endValueFrom()
                        .endEnv()
                        .addNewEnv()
                        .withName("REVISION")
                        .withValue("42")
                        .endEnv()
                        .addNewEnvFrom()
                        .withPrefix("LDAP_")
                        .withNewSecretRef("ldap-secrets", null)
                        .endEnvFrom()
                        .endContainer()
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build())
                .create();
        client.secrets()
                .inNamespace("keycloak")
                .resource(new SecretBuilder()
                        .withNewMetadata()
                        .withName("ldap-secrets")
                        .endMetadata()
                        .addToData("PASSWORD", "cGFzcw==")
                        .build())
                .create();
        client.configMaps()
                .inNamespace("keycloak")
                .resource(new ConfigMapBuilder()
                        .withNewMetadata()
                        .withName("unrelated")
                        .endMetadata()
                        .build())
                .create();
    }

    @Test
    void collectsEnvAndExpandedEnvFromSources() {
        MigrationReport report = new MigrationReport();
        EnvVarSources sources = DeploymentEnvReader.read(client, "keycloak", "keycloak", null, report);

        EnvVarSources.Source secret = sources.lookup("CLIENT_SECRET");
        assertEquals(EnvVarSources.Kind.SECRET, secret.kind());
        assertEquals("keycloak-secrets", secret.name());
        assertEquals("client-secret", secret.key());

        EnvVarSources.Source configMap = sources.lookup("ROOT_URL");
        assertEquals(EnvVarSources.Kind.CONFIG_MAP, configMap.kind());
        assertEquals("keycloak-config", configMap.name());
        assertEquals("ROOT_URL", configMap.key());

        EnvVarSources.Source literal = sources.lookup("REVISION");
        assertEquals(EnvVarSources.Kind.LITERAL, literal.kind());
        assertEquals("42", literal.value());

        // envFrom with prefix: env name = prefix + secret key, reference keeps the bare key
        EnvVarSources.Source envFrom = sources.lookup("LDAP_PASSWORD");
        assertEquals(EnvVarSources.Kind.SECRET, envFrom.kind());
        assertEquals("ldap-secrets", envFrom.name());
        assertEquals("PASSWORD", envFrom.key());

        assertNull(sources.lookup("UNKNOWN"));
    }

    @Test
    void missingDeploymentFailsClearly() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DeploymentEnvReader.read(client, "keycloak", "nope", null, new MigrationReport()));
    }
}
