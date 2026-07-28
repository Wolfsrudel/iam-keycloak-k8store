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
package io.github.dominikschlosser.k8store.client;

import io.github.dominikschlosser.k8store.common.CrStore;
import io.github.dominikschlosser.k8store.crd.ClientSpec;
import java.util.List;

/**
 * Access to {@code KeycloakClient} custom resources, keyed by {@code (realm, clientId)} - the
 * clientId is the store id in this store. Reads come from the informer mirror and hand out
 * defensive copies; every mutation of a spec must be persisted explicitly through {@link #save}
 * - specs carry no write-through machinery.
 */
public final class ClientCrStore {

    private static final CrStore<ClientSpec> STORE =
            new CrStore<>(ClientSpec.class, ClientSpec::getRealm, ClientSpec::getClientId);

    private ClientCrStore() {}

    public static ClientSpec read(String realmId, String clientId) {
        return STORE.read(realmId, clientId);
    }

    public static boolean exists(String realmId, String clientId) {
        return STORE.exists(realmId, clientId);
    }

    public static List<ClientSpec> allInRealm(String realmId) {
        return STORE.allInRealm(realmId);
    }

    public static List<ClientSpec> all() {
        return STORE.readAll();
    }

    public static ClientSpec save(ClientSpec spec) {
        return STORE.save(spec);
    }

    public static void delete(String realmId, String clientId) {
        STORE.delete(realmId, clientId);
    }
}
