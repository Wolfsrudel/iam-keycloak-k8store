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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.github.dominikschlosser.k8store.crd.ClientScopeSpec;
import io.github.dominikschlosser.k8store.crd.ClientSpec;
import io.github.dominikschlosser.k8store.crd.GroupSpec;
import io.github.dominikschlosser.k8store.crd.RealmSpec;
import io.github.dominikschlosser.k8store.crd.RoleSpec;
import io.github.dominikschlosser.k8store.kubernetes.CrNaming;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakClientScopeCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakGroupCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRealmCr;
import io.github.dominikschlosser.k8store.kubernetes.crd.KeycloakRoleCr;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ComponentExportRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.ScopeMappingRepresentation;

/**
 * Splits one exported realm (the output of {@code kc.sh export}) into the per-kind custom
 * resources this store serves: the realm itself plus clients, client scopes, roles and groups.
 *
 * <p>The export format is already the representation shape the CR specs extend, so most fields
 * copy through unchanged - including {@code ${VAR}} placeholders that survived the export because
 * the variable was unset during the export boot. What this class actually has to do:
 *
 * <ul>
 *   <li>Cut the embedded collections out of the realm and re-emit them as their own CRs.
 *   <li>Redistribute the realm-level {@code scopeMappings}/{@code clientScopeMappings} onto the
 *       name-based carrier fields of the client and client-scope specs.
 *   <li>Keep exported entity ids: they are what the database references (user role mappings,
 *       group memberships, consents, federation links), so preserving them keeps the existing
 *       database valid. For filestore instances they are the human-readable natural keys anyway.
 *       The one exception is the client id, which this store hard-wires to the clientId.
 *   <li>Synthesize {@code masterAdminClient}, which exports never carry.
 *   <li>Move parsed client profiles/policies back into the realm attributes this store serves
 *       them from ({@code client-policies.profiles}/{@code client-policies.policies}).
 * </ul>
 */
public final class ExportSplitter {

    /** The storage areas this tool can emit CRs for, named like the k8store {@code areas} option. */
    public static final Set<String> AREAS = Set.of("realm", "client", "client-scope", "role", "group");

    private static final String CLIENT_PROFILES_ATTRIBUTE = "client-policies.profiles";
    private static final String CLIENT_POLICIES_ATTRIBUTE = "client-policies.policies";

    private final ObjectMapper mapper;
    private final Set<String> areas;
    private final MigrationReport report;

    public ExportSplitter(Set<String> areas, MigrationReport report) {
        this.mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.areas = areas;
        this.report = report;
    }

    public List<CrDocument> split(JsonNode exportedRealm) {
        RealmRepresentation rep = mapper.convertValue(exportedRealm, RealmRepresentation.class);
        String realm = rep.getRealm();
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("export document has no 'realm' field");
        }

        ScopeMappingIndex scopeMappings = new ScopeMappingIndex(rep, realm, report);
        List<CrDocument> docs = new ArrayList<>();

        if (areas.contains("realm")) {
            docs.add(realmDoc(exportedRealm, rep, realm));
        } else {
            report.info(realm + ": realm CR skipped (area not selected)");
        }
        if (areas.contains("client")) {
            for (ClientRepresentation client : orEmpty(rep.getClients())) {
                docs.add(clientDoc(realm, client, scopeMappings));
            }
        } else if (rep.getClients() != null && !rep.getClients().isEmpty()) {
            report.info(realm + ": " + rep.getClients().size() + " client(s) skipped (area not selected)");
        }
        if (areas.contains("client-scope")) {
            for (ClientScopeRepresentation scope : orEmpty(rep.getClientScopes())) {
                docs.add(clientScopeDoc(realm, scope, scopeMappings));
            }
        } else if (rep.getClientScopes() != null && !rep.getClientScopes().isEmpty()) {
            report.info(realm + ": " + rep.getClientScopes().size() + " client scope(s) skipped (area not selected)");
        }
        if (areas.contains("role")) {
            if (rep.getRoles() != null) {
                for (RoleRepresentation role : orEmpty(rep.getRoles().getRealm())) {
                    docs.add(realmRoleDoc(realm, role));
                }
                if (rep.getRoles().getClient() != null) {
                    for (Map.Entry<String, List<RoleRepresentation>> entry :
                            rep.getRoles().getClient().entrySet()) {
                        for (RoleRepresentation role : orEmpty(entry.getValue())) {
                            docs.add(clientRoleDoc(realm, entry.getKey(), role));
                        }
                    }
                }
            }
        } else if (rep.getRoles() != null) {
            report.info(realm + ": roles skipped (area not selected)");
        }
        if (areas.contains("group")) {
            flattenGroups(realm, orEmpty(rep.getGroups()), null, docs);
        } else if (rep.getGroups() != null && !rep.getGroups().isEmpty()) {
            report.info(realm + ": " + rep.getGroups().size() + " top-level group(s) skipped (area not selected)");
        }
        return docs;
    }

    // ------------------------------------------------------------------ realm

    private CrDocument realmDoc(JsonNode exportedRealm, RealmRepresentation rep, String realm) {
        warnAboutDroppedRealmContent(exportedRealm, realm);

        RealmSpec spec = mapper.convertValue(exportedRealm, RealmSpec.class);
        // per-kind CRs are the storage for these; they were split off above
        spec.setUsers(null);
        spec.setFederatedUsers(null);
        spec.setClients(null);
        spec.setClientScopes(null);
        spec.setRoles(null);
        spec.setGroups(null);
        spec.setClientScopeMappings(null);
        spec.setRealm(realm);
        spec.setId(realm);
        // export-run metadata, not realm configuration
        spec.setKeycloakVersion(null);
        // exports never carry the admin-client reference; it is deterministic
        spec.setMasterAdminClient("master".equals(realm) ? "master-realm" : realm + "-realm");
        restoreClientPolicyAttributes(exportedRealm, spec, realm);
        healEmptyUserProfileComponent(spec, realm);

        ObjectNode specNode = mapper.valueToTree(spec);
        return doc(KeycloakRealmCr.class, realm, realm, null, specNode);
    }

    private static final String USER_PROFILE_PROVIDER_TYPE = "org.keycloak.userprofile.UserProfileProvider";
    private static final String USER_PROFILE_CONFIG_KEY = "kc.user.profile.config";

    /**
     * Filestore does not persist the user-profile configuration: the component is written at
     * realm creation, but the configuration Keycloak stores into it afterwards (a nested
     * component update) never reaches the file. What survives is a {@code
     * declarative-user-profile} component with an empty config - and to Keycloak, a
     * <em>present</em> component with no configuration means "enforce the strict system-default
     * profile", which locks profile-less users (like the bootstrap admin, which has no email) out
     * of direct grants with "Account is not fully set up".
     *
     * <p>The heal restores what a healthy realm of that kind carries. For {@code master},
     * Keycloak's bootstrap ({@code ApplianceBootstrap}) writes the system default with every
     * {@code required} constraint removed except the username's ("In master realm the UP config
     * is more relaxed") - the exact configuration filestore lost - so that is reconstructed from
     * the bundled default profile. Any other realm gets the component dropped: realms created
     * through import or the admin API carry no user-profile component until one is configured.
     * Reported so an intentionally customized user profile can be re-applied.
     */
    private void healEmptyUserProfileComponent(RealmSpec spec, String realm) {
        if (spec.getComponents() == null) {
            return;
        }
        List<ComponentExportRepresentation> userProfileComponents =
                spec.getComponents().get(USER_PROFILE_PROVIDER_TYPE);
        if (userProfileComponents == null) {
            return;
        }
        List<ComponentExportRepresentation> empty = userProfileComponents.stream()
                .filter(component -> {
                    MultivaluedHashMap<String, String> config = component.getConfig();
                    String raw = config == null ? null : config.getFirst(USER_PROFILE_CONFIG_KEY);
                    return raw == null || raw.isBlank();
                })
                .toList();
        if (empty.isEmpty()) {
            return;
        }
        if ("master".equals(realm)) {
            for (ComponentExportRepresentation component : empty) {
                if (component.getConfig() == null) {
                    component.setConfig(new MultivaluedHashMap<>());
                }
                component.getConfig().putSingle(USER_PROFILE_CONFIG_KEY, relaxedMasterUserProfileConfig());
            }
            report.warn(realm + ": user-profile component had an empty configuration - the source store"
                    + " lost the configuration Keycloak wrote into it (filestore does not persist nested"
                    + " component updates), which would enforce the strict default profile and lock the"
                    + " profile-less bootstrap admin out of direct grants. Restored the relaxed master"
                    + " profile Keycloak's bootstrap writes (no required attributes except username);"
                    + " re-apply your own configuration if master had a customized one");
        } else {
            userProfileComponents.removeAll(empty);
            if (userProfileComponents.isEmpty()) {
                spec.getComponents().remove(USER_PROFILE_PROVIDER_TYPE);
            }
            report.warn(realm + ": user-profile component with an empty configuration dropped - the source"
                    + " store lost the configuration Keycloak wrote into it (filestore does not persist"
                    + " nested component updates), and an empty configuration would enforce the strict"
                    + " default profile. Without the component the realm behaves like a freshly created"
                    + " one; re-apply the realm's user-profile configuration if it had a customized one");
        }
    }

    /**
     * The user-profile configuration Keycloak's bootstrap writes for the master realm: the
     * bundled system default (from the pinned Keycloak version) with every {@code required}
     * constraint removed except on {@code username}.
     */
    private String relaxedMasterUserProfileConfig() {
        try (InputStream in = getClass().getResourceAsStream("/keycloak-default-user-profile.json")) {
            ObjectNode config = (ObjectNode) mapper.readTree(in);
            for (JsonNode attribute : config.get("attributes")) {
                if (!"username".equals(attribute.get("name").asText())) {
                    ((ObjectNode) attribute).remove("required");
                }
            }
            return config.toString();
        } catch (IOException e) {
            throw new IllegalStateException("cannot load the bundled default user profile", e);
        }
    }

    /**
     * Exports carry client profiles/policies as parsed JSON fields and exclude the backing realm
     * attributes; this store serves them from the attributes, so put them back. Global profiles
     * and policies are Keycloak built-ins and are not part of the realm's own configuration.
     */
    private void restoreClientPolicyAttributes(JsonNode exportedRealm, RealmSpec spec, String realm) {
        putPolicyAttribute(exportedRealm, spec, realm, "clientProfiles", "profiles", CLIENT_PROFILES_ATTRIBUTE);
        putPolicyAttribute(exportedRealm, spec, realm, "clientPolicies", "policies", CLIENT_POLICIES_ATTRIBUTE);
    }

    private void putPolicyAttribute(
            JsonNode exportedRealm, RealmSpec spec, String realm, String field, String listField, String attribute) {
        JsonNode node = exportedRealm.get(field);
        if (node == null || !node.isObject()) {
            return;
        }
        ObjectNode own = ((ObjectNode) node).deepCopy();
        own.remove("globalProfiles");
        own.remove("globalPolicies");
        JsonNode list = own.get(listField);
        if (list == null || !list.isArray() || list.isEmpty()) {
            return;
        }
        if (spec.getAttributes() == null) {
            spec.setAttributes(new LinkedHashMap<>());
        }
        spec.getAttributes().put(attribute, own.toString());
        report.info(realm + ": " + field + " moved into realm attribute '" + attribute + "'");
    }

    private void warnAboutDroppedRealmContent(JsonNode exportedRealm, String realm) {
        warnIfPresent(exportedRealm, realm, "users", "users are not migrated - they stay in the database");
        warnIfPresent(exportedRealm, realm, "federatedUsers", "federated users are not migrated");
        warnIfPresent(exportedRealm, realm, "organizations", "organizations are not supported by this store");
        warnIfPresent(exportedRealm, realm, "applications", "deprecated 'applications' shape is not supported");
        warnIfPresent(exportedRealm, realm, "oauthClients", "deprecated 'oauthClients' shape is not supported");
        warnIfPresent(exportedRealm, realm, "clientTemplates", "deprecated client templates are not supported");
        warnIfPresent(exportedRealm, realm, "socialProviders", "deprecated social providers are not supported");
        warnIfPresent(
                exportedRealm, realm, "protocolMappers", "deprecated realm-level protocol mappers are not supported");
        warnIfPresent(
                exportedRealm,
                realm,
                "userFederationProviders",
                "deprecated user federation shape is not supported (user storage components are migrated)");
        warnIfPresent(
                exportedRealm,
                realm,
                "userFederationMappers",
                "deprecated user federation mappers are not supported (user storage components are migrated)");
        for (String legacyKeyField : new String[] {"privateKey", "publicKey", "certificate", "codeSecret"}) {
            JsonNode value = exportedRealm.get(legacyKeyField);
            if (value != null && value.isTextual() && !value.asText().isEmpty()) {
                report.warn(realm + ": legacy realm field '" + legacyKeyField
                        + "' dropped - realm keys are key-provider components");
            }
        }
    }

    private void warnIfPresent(JsonNode exportedRealm, String realm, String field, String message) {
        JsonNode value = exportedRealm.get(field);
        if (value != null && !value.isNull() && !(value.isContainerNode() && value.isEmpty())) {
            report.warn(realm + ": '" + field + "' present in export, dropped - " + message);
        }
    }

    // ------------------------------------------------------------------ clients and client scopes

    private CrDocument clientDoc(String realm, ClientRepresentation client, ScopeMappingIndex scopeMappings) {
        String clientId = client.getClientId();
        if (client.getAuthorizationSettings() != null) {
            report.warn(realm + "/" + clientId + ": authorizationSettings dropped - migrate authorization"
                    + " data separately (authorization CR kinds are not covered by this tool)");
        }
        ClientSpec spec = mapper.convertValue(client, ClientSpec.class);
        spec.setRealm(realm);
        if (client.getId() != null && !client.getId().equals(clientId)) {
            report.warn(realm + "/" + clientId + ": client id '" + client.getId() + "' becomes '" + clientId
                    + "' (this store keys clients by clientId) - database references to the old id"
                    + " (consents, offline sessions) will not resolve");
        }
        spec.setId(clientId);
        spec.setRealmScopeMappings(scopeMappings.realmRolesForClient(clientId));
        spec.setClientScopeMappings(scopeMappings.clientRolesForClient(clientId));
        return doc(KeycloakClientCr.class, realm, clientId, "clients", mapper.valueToTree(spec));
    }

    private CrDocument clientScopeDoc(String realm, ClientScopeRepresentation scope, ScopeMappingIndex scopeMappings) {
        ClientScopeSpec spec = mapper.convertValue(scope, ClientScopeSpec.class);
        spec.setRealm(realm);
        String id = scope.getId() != null ? scope.getId() : scope.getName();
        spec.setId(id);
        spec.setRealmScopeMappings(scopeMappings.realmRolesForScope(scope.getName()));
        spec.setClientScopeMappings(scopeMappings.clientRolesForScope(scope.getName()));
        return doc(KeycloakClientScopeCr.class, realm, id, "client-scopes", mapper.valueToTree(spec));
    }

    // ------------------------------------------------------------------ roles

    private CrDocument realmRoleDoc(String realm, RoleRepresentation role) {
        RoleSpec spec = mapper.convertValue(role, RoleSpec.class);
        spec.setRealm(realm);
        String id = role.getId() != null ? role.getId() : role.getName();
        spec.setId(id);
        spec.setContainerId(realm);
        spec.setClientRole(false);
        return doc(KeycloakRoleCr.class, realm, id, "roles", mapper.valueToTree(spec));
    }

    private CrDocument clientRoleDoc(String realm, String clientId, RoleRepresentation role) {
        RoleSpec spec = mapper.convertValue(role, RoleSpec.class);
        spec.setRealm(realm);
        String id = role.getId() != null ? role.getId() : clientId + ":" + role.getName();
        spec.setId(id);
        // the container reference is the owning client's id, which is its clientId in this store
        spec.setContainerId(clientId);
        spec.setClientRole(true);
        return doc(KeycloakRoleCr.class, realm, id, "roles", mapper.valueToTree(spec));
    }

    // ------------------------------------------------------------------ groups

    private void flattenGroups(String realm, List<GroupRepresentation> groups, String parentId, List<CrDocument> out) {
        for (GroupRepresentation group : groups) {
            String id = group.getId() != null ? group.getId() : group.getName();
            GroupSpec spec = mapper.convertValue(group, GroupSpec.class);
            spec.setRealm(realm);
            spec.setId(id);
            spec.setParentId(parentId);
            // derivable from the parent chain; the store does not persist it
            spec.setPath(null);
            out.add(doc(KeycloakGroupCr.class, realm, id, "groups", mapper.valueToTree(spec)));
            if (group.getSubGroups() != null) {
                flattenGroups(realm, group.getSubGroups(), id, out);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private CrDocument doc(
            Class<? extends HasMetadata> crClass, String realm, String id, String category, ObjectNode spec) {
        return new CrDocument(
                HasMetadata.getKind(crClass),
                HasMetadata.getApiVersion(crClass),
                realm,
                id,
                CrNaming.crName(crClass, realm, id),
                category,
                spec);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * The realm-level scope-mapping collections of an export, indexed by grantee. Realm exports
     * store all scope mappings centrally ({@code scopeMappings} for realm roles,
     * {@code clientScopeMappings} keyed by the role-owning client for client roles); this store
     * keeps them on the client/client-scope CR they belong to.
     */
    private static final class ScopeMappingIndex {

        private final Map<String, List<String>> realmRolesByClient = new LinkedHashMap<>();
        private final Map<String, List<String>> realmRolesByScope = new LinkedHashMap<>();
        private final Map<String, Map<String, List<String>>> clientRolesByClient = new LinkedHashMap<>();
        private final Map<String, Map<String, List<String>>> clientRolesByScope = new LinkedHashMap<>();

        ScopeMappingIndex(RealmRepresentation rep, String realm, MigrationReport report) {
            for (ScopeMappingRepresentation mapping : orEmpty(rep.getScopeMappings())) {
                if (mapping.getRoles() == null || mapping.getRoles().isEmpty()) {
                    continue;
                }
                List<String> roles = new ArrayList<>(mapping.getRoles());
                if (mapping.getClient() != null) {
                    realmRolesByClient
                            .computeIfAbsent(mapping.getClient(), k -> new ArrayList<>())
                            .addAll(roles);
                } else if (mapping.getClientScope() != null) {
                    realmRolesByScope
                            .computeIfAbsent(mapping.getClientScope(), k -> new ArrayList<>())
                            .addAll(roles);
                } else {
                    report.warn(realm + ": scope mapping without client/clientScope grantee dropped: " + roles);
                }
            }
            if (rep.getClientScopeMappings() != null) {
                for (Map.Entry<String, List<ScopeMappingRepresentation>> entry :
                        rep.getClientScopeMappings().entrySet()) {
                    String ownerClientId = entry.getKey();
                    for (ScopeMappingRepresentation mapping : orEmpty(entry.getValue())) {
                        if (mapping.getRoles() == null || mapping.getRoles().isEmpty()) {
                            continue;
                        }
                        List<String> roles = new ArrayList<>(mapping.getRoles());
                        if (mapping.getClient() != null) {
                            clientRolesByClient
                                    .computeIfAbsent(mapping.getClient(), k -> new LinkedHashMap<>())
                                    .computeIfAbsent(ownerClientId, k -> new ArrayList<>())
                                    .addAll(roles);
                        } else if (mapping.getClientScope() != null) {
                            clientRolesByScope
                                    .computeIfAbsent(mapping.getClientScope(), k -> new LinkedHashMap<>())
                                    .computeIfAbsent(ownerClientId, k -> new ArrayList<>())
                                    .addAll(roles);
                        } else {
                            report.warn(realm + ": client scope mapping of " + ownerClientId
                                    + " without client/clientScope grantee dropped: " + roles);
                        }
                    }
                }
            }
        }

        List<String> realmRolesForClient(String clientId) {
            return realmRolesByClient.get(clientId);
        }

        List<String> realmRolesForScope(String scopeName) {
            return realmRolesByScope.get(scopeName);
        }

        Map<String, List<String>> clientRolesForClient(String clientId) {
            return clientRolesByClient.get(clientId);
        }

        Map<String, List<String>> clientRolesForScope(String scopeName) {
            return clientRolesByScope.get(scopeName);
        }
    }
}
