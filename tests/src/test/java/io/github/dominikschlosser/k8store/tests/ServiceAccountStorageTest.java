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
package io.github.dominikschlosser.k8store.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.github.dominikschlosser.k8store.crd.ClientSpec;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientCr;
import io.github.dominikschlosser.k8store.tests.config.ReferenceResolutionServerConfig;
import io.github.dominikschlosser.k8store.tests.framework.Await;
import io.github.dominikschlosser.k8store.tests.framework.InjectKindCluster;
import io.github.dominikschlosser.k8store.tests.framework.InjectTestNamespace;
import io.github.dominikschlosser.k8store.tests.framework.KindCluster;
import io.github.dominikschlosser.k8store.tests.framework.TestNamespace;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.testframework.annotations.InjectAdminClient;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.server.KeycloakUrls;

/**
 * A service account is a user, which Keycloak only creates on its own client-write paths - paths
 * a CR-authored client never runs. The store materializes the missing user itself when a client
 * CR carries {@code serviceAccountsEnabled}, so the client_credentials grant works for clients
 * that exist purely as custom resources. Read-only mode on purpose (the strictest case): user
 * writes are dynamic data and stay allowed.
 */
@Order(2)
@KeycloakIntegrationTest(config = ReferenceResolutionServerConfig.class)
public class ServiceAccountStorageTest {

    private static final String CLIENT_ID = "cr-service-client";
    private static final String CLIENT_SECRET = "cr-service-secret";

    @InjectKindCluster
    KindCluster kube;

    @InjectTestNamespace
    TestNamespace namespace;

    @InjectAdminClient(mode = InjectAdminClient.Mode.BOOTSTRAP)
    Keycloak adminClient;

    @InjectKeycloakUrls
    KeycloakUrls urls;

    @Test
    public void serviceAccountIsMaterializedForClientCrAndClientCredentialsGrantWorks() {
        ClientSpec spec = new ClientSpec();
        spec.setRealm("master");
        spec.setClientId(CLIENT_ID);
        spec.setEnabled(true);
        spec.setProtocol("openid-connect");
        spec.setPublicClient(false);
        spec.setClientAuthenticatorType("client-secret");
        spec.setSecret(CLIENT_SECRET);
        spec.setServiceAccountsEnabled(true);
        KeycloakClientCr cr = new KeycloakClientCr();
        cr.setMetadata(new ObjectMetaBuilder()
                .withName("master." + CLIENT_ID)
                .withNamespace(namespace.name())
                .build());
        cr.setSpec(spec);
        kube.client().resource(cr).inNamespace(namespace.name()).create();

        Await.await("the client CR to be served", () -> !adminClient
                .realm("master")
                .clients()
                .findByClientId(CLIENT_ID)
                .isEmpty());

        Await.await("the service account user to be materialized", () -> !adminClient
                .realm("master")
                .users()
                .search("service-account-" + CLIENT_ID, true)
                .isEmpty());

        try (Keycloak serviceClient = KeycloakBuilder.builder()
                .serverUrl(urls.getBase())
                .realm("master")
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(CLIENT_ID)
                .clientSecret(CLIENT_SECRET)
                .build()) {
            assertNotNull(
                    serviceClient.tokenManager().getAccessToken().getToken(),
                    "the CR-authored client must obtain a client_credentials token");
        }
    }
}
