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

import io.github.dominikschlosser.k8store.crd.ClientSpec;
import java.util.Objects;
import org.jboss.logging.Logger;
import org.keycloak.common.constants.ServiceAccountConstants;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Materializes the service-account user for every client CR with {@code serviceAccountsEnabled}.
 *
 * <p>A service account is not client configuration: it is a <em>user</em>
 * ({@code service-account-<clientId>}) that Keycloak's own write paths create alongside the
 * client ({@code ClientManager.enableServiceAccount}, realm import). A client authored as a
 * custom resource never runs those paths, so without this step its {@code client_credentials}
 * grant fails with "the associated service account for the client does not exist". Users are
 * dynamic data and always writable - also in read-only mode - so this store creates the missing
 * user itself.
 *
 * <p>Runs a full sweep over the client mirror on the post-migration event (the earliest point
 * with a fully booted session factory) and afterwards processes every client spec the backend's
 * watch delivers. Both paths converge on {@link #ensure}: create the user when it is missing, or
 * repoint its service-account link when it references a client id that no longer matches (the
 * migration-from-another-store case, where client ids become clientIds). Every step is
 * idempotent, and the duplicate-insert race between replicas resolves through
 * {@link ModelDuplicateException} plus re-read.
 */
public final class ServiceAccountEnsurer {

    private static final Logger LOG = Logger.getLogger(ServiceAccountEnsurer.class);

    private final KeycloakSessionFactory sessionFactory;
    private volatile boolean active;

    public ServiceAccountEnsurer(KeycloakSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /** Sweeps every client CR currently mirrored and starts serving watch events. */
    public void activate() {
        active = true;
        for (ClientSpec spec : ClientCrStore.all()) {
            ensure(spec);
        }
    }

    /**
     * Watch callback. Events before {@link #activate} are dropped on purpose: the session
     * factory is not fully booted yet, and the activation sweep covers everything present.
     */
    public void onClientSpec(ClientSpec spec) {
        if (active) {
            ensure(spec);
        }
    }

    private void ensure(ClientSpec spec) {
        if (!Boolean.TRUE.equals(spec.isServiceAccountsEnabled())) {
            return;
        }
        try {
            KeycloakModelUtils.runJobInTransaction(
                    sessionFactory, session -> ensureInSession(session, spec.getRealm(), spec.getClientId()));
        } catch (RuntimeException e) {
            // never break the informer thread; the next event or boot sweep retries
            LOG.warnv(
                    e,
                    "k8store: could not materialize the service account of client {0}/{1}",
                    spec.getRealm(),
                    spec.getClientId());
        }
    }

    private static void ensureInSession(KeycloakSession session, String realmId, String clientId) {
        RealmModel realm = session.realms().getRealm(realmId);
        if (realm == null) {
            return;
        }
        ClientModel client = session.clients().getClientByClientId(realm, clientId);
        if (client == null || !client.isServiceAccountsEnabled()) {
            return;
        }
        if (session.users().getServiceAccount(client) != null) {
            return;
        }
        String username = ServiceAccountConstants.SERVICE_ACCOUNT_USER_PREFIX + client.getClientId();
        UserModel existing = session.users().getUserByUsername(realm, username);
        if (existing != null) {
            // the user exists but its link points elsewhere - a store migration changed the
            // client id (this store keys clients by clientId); repoint instead of failing
            if (!Objects.equals(existing.getServiceAccountClientLink(), client.getId())) {
                existing.setServiceAccountClientLink(client.getId());
                LOG.infov(
                        "k8store: relinked service account user {0} to client {1}/{2}",
                        username, realmId, client.getClientId());
            }
            return;
        }
        try {
            UserModel user = session.users().addUser(realm, username);
            user.setEnabled(true);
            user.setServiceAccountClientLink(client.getId());
            LOG.infov(
                    "k8store: created service account user {0} for client CR {1}/{2}",
                    username, realmId, client.getClientId());
        } catch (ModelDuplicateException raced) {
            // another replica created it between our lookup and insert; that copy wins
            LOG.debugv("k8store: service account user {0} was created concurrently", username);
        }
    }
}
