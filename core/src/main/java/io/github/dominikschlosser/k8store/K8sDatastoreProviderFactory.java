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

package io.github.dominikschlosser.k8store;

import com.google.auto.service.AutoService;
import io.github.dominikschlosser.k8store.client.ServiceAccountEnsurer;
import io.github.dominikschlosser.k8store.kubernetes.K8sStorageBackend;
import io.github.dominikschlosser.k8store.kubernetes.K8sStoreConfig;
import org.keycloak.Config;
import org.keycloak.common.Profile;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.storage.DatastoreProvider;
import org.keycloak.storage.DatastoreProviderFactory;
import org.keycloak.storage.datastore.DefaultDatastoreProviderFactory;

/**
 * Activated with {@code --spi-datastore--provider=k8store}. Requires the {@code stateless}
 * feature (Keycloak 26.7+): with it, the only state left besides the database are the config
 * entities this datastore serves from Kubernetes custom resources.
 */
@AutoService(DatastoreProviderFactory.class)
public class K8sDatastoreProviderFactory implements DatastoreProviderFactory {

    public static final String PROVIDER_ID = "k8store";

    /**
     * Isolated on purpose so upstream feature renames stay a one-line change (the feature
     * started as {@code cacheless} and was renamed to {@code stateless} for Keycloak 26.7,
     * keycloak#50619).
     */
    private static final Profile.Feature REQUIRED_FEATURE = Profile.Feature.STATELESS;

    /**
     * Initialized delegate for the storage areas that stay on the database: the inherited
     * DefaultDatastoreProvider paths dereference their factory (timeouts, migration flags), and
     * the registered {@code legacy} factory is pruned by Quarkus when the datastore provider is
     * fixed to k8store.
     */
    private DefaultDatastoreProviderFactory legacyDelegate;

    @Override
    public DatastoreProvider create(KeycloakSession session) {
        return new K8sDatastoreProvider(session, legacyDelegate);
    }

    @Override
    public void init(Config.Scope config) {
        // re-read the configuration on every boot: in embedded test runs the JVM survives
        // server restarts with different options
        K8sStoreConfig.reset();
        legacyDelegate = new DefaultDatastoreProviderFactory();
        legacyDelegate.init(Config.scope("datastore", "legacy"));
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        if (!Profile.isFeatureEnabled(REQUIRED_FEATURE)) {
            throw new IllegalStateException("The k8store datastore requires the '"
                    + REQUIRED_FEATURE.getKey()
                    + "' feature. Start Keycloak with --features=" + REQUIRED_FEATURE.getKey());
        }
        // Fail fast at boot: connect to the Kubernetes API and sync all informer caches so the
        // node never serves partial configuration.
        K8sStorageBackend backend = K8sStorageBackend.get();
        if (K8sStoreConfig.isAreaEnabled(K8sStoreConfig.Area.CLIENT)) {
            // Service accounts are users, not client configuration: Keycloak only creates them on
            // its own client-write paths, which a CR-authored client never runs. Sweep once the
            // session factory is fully booted (post-migration), then follow the watch.
            ServiceAccountEnsurer ensurer = new ServiceAccountEnsurer(factory);
            backend.setClientSpecListener(ensurer::onClientSpec);
            factory.register(event -> {
                if (event instanceof PostMigrationEvent) {
                    ensurer.activate();
                }
            });
        }
    }

    @Override
    public void close() {
        K8sStorageBackend.shutdown();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
