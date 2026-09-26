package com.loadpilot.backend.service.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loadpilot.backend.config.KeycloakAdminProperties;
import com.loadpilot.backend.enums.AppRole;
import com.loadpilot.backend.exception.KeycloakAdminException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementation reelle unique (java.net.http.HttpClient, aucune dependance
 * ajoutee - meme choix que service.monitoring.HttpMonitoringClient et
 * service.execution.HttpClientExecutionEngine) de l'Admin REST API Keycloak,
 * via le compte de service "loadpilot-backend-admin" (client confidentiel,
 * serviceAccountsEnabled=true, roles realm-management view-users/
 * manage-users/query-users UNIQUEMENT - provisionne separement dans
 * Keycloak, jamais dans ce depot).
 *
 * Le token du compte de service (grant_type=client_credentials) est mis en
 * cache en memoire et rafraichi automatiquement avant expiration - jamais
 * demande a chaque appel, jamais stocke ni transmis au frontend.
 */
@Component
public class HttpKeycloakAdminClient implements KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(HttpKeycloakAdminClient.class);
    /** Marge de securite avant l'expiration reelle du token, pour ne jamais
     * envoyer une requete avec un token qui expire pendant le trajet. */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(10);

    private final KeycloakAdminProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    public HttpKeycloakAdminClient(KeycloakAdminProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    @Override
    public List<KeycloakUserRecord> listUsers() {
        JsonNode array = get("/admin/realms/" + properties.getRealm() + "/users?max=500");
        List<KeycloakUserRecord> result = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(node -> result.add(toUserRecord(node)));
        }
        return result;
    }

    @Override
    public KeycloakUserRecord getUser(String userId) {
        JsonNode node = getOrThrowNotFound("/admin/realms/" + properties.getRealm() + "/users/" + encode(userId),
                "Utilisateur introuvable : " + userId);
        return toUserRecord(node);
    }

    @Override
    public Set<String> getUserRealmRoleNames(String userId) {
        JsonNode array = getOrThrowNotFound(
                "/admin/realms/" + properties.getRealm() + "/users/" + encode(userId) + "/role-mappings/realm",
                "Utilisateur introuvable : " + userId);
        Set<String> names = new HashSet<>();
        if (array.isArray()) {
            array.forEach(n -> {
                JsonNode nameNode = n.get("name");
                if (nameNode != null) {
                    names.add(nameNode.asText());
                }
            });
        }
        return names;
    }

    @Override
    public void setUserEnabled(String userId, boolean enabled) {
        // Keycloak attend une representation complete pour PUT /users/{id} -
        // on recupere donc l'objet reel, on modifie uniquement "enabled", et
        // on le renvoie tel quel (jamais reconstruit champ par champ, pour ne
        // jamais perdre/ecraser un attribut Keycloak existant).
        JsonNode current = getOrThrowNotFound("/admin/realms/" + properties.getRealm() + "/users/" + encode(userId),
                "Utilisateur introuvable : " + userId);
        ObjectNode updated = current.deepCopy();
        updated.put("enabled", enabled);
        put("/admin/realms/" + properties.getRealm() + "/users/" + encode(userId), updated);
    }

    @Override
    public void replaceUserAppRole(String userId, AppRole newRole) {
        // NB (Phase 25) : lire la representation d'un role via
        // GET /roles/{name} exige le role Keycloak "view-realm", plus large
        // que le strict necessaire (voir compte de service
        // loadpilot-backend-admin, limite a view-users/manage-users/
        // query-users). On utilise donc exclusivement des endpoints
        // "sous-ressource utilisateur" (role-mappings/realm[/available]),
        // couverts par manage-users/view-users - jamais /roles/{name}.
        String rolesMappingPath = "/admin/realms/" + properties.getRealm() + "/users/" + encode(userId) + "/role-mappings/realm";

        JsonNode currentMappings = getOrThrowNotFound(rolesMappingPath, "Utilisateur introuvable : " + userId);
        ArrayNode toRemove = objectMapper.createArrayNode();
        boolean alreadyHasTargetRole = false;
        if (currentMappings.isArray()) {
            for (JsonNode mapping : currentMappings) {
                String name = mapping.path("name").asText("");
                if (name.equals(newRole.toKeycloakRoleName())) {
                    alreadyHasTargetRole = true;
                } else if (isAppRoleName(name)) {
                    toRemove.add(mapping);
                }
            }
        }

        // 1) Assigner D'ABORD le nouveau role (si pas deja present) : en cas
        // d'echec (ex: role non trouve), l'utilisateur garde son role actuel
        // au lieu de se retrouver sans aucun role applicatif.
        if (!alreadyHasTargetRole) {
            JsonNode targetRole = findAvailableRole(rolesMappingPath + "/available", newRole.toKeycloakRoleName(), userId);
            ArrayNode toAdd = objectMapper.createArrayNode();
            toAdd.add(targetRole);
            post(rolesMappingPath, toAdd);
        }

        // 2) Retirer ENSUITE les autres roles applicatifs precedemment
        // assignes (seulement une fois le nouveau role confirme assigne).
        if (!toRemove.isEmpty()) {
            delete(rolesMappingPath, toRemove);
        }
    }

    private JsonNode findAvailableRole(String availableRolesPath, String keycloakRoleName, String userId) {
        JsonNode available = getOrThrowNotFound(availableRolesPath, "Utilisateur introuvable : " + userId);
        if (available.isArray()) {
            for (JsonNode role : available) {
                if (keycloakRoleName.equals(role.path("name").asText(""))) {
                    return role;
                }
            }
        }
        throw new ResourceNotFoundException(
                "Role Keycloak introuvable ou deja assigne autrement : " + keycloakRoleName + " (provisionnement du realm incomplet ?)");
    }

    private boolean isAppRoleName(String name) {
        for (AppRole role : AppRole.values()) {
            if (role.toKeycloakRoleName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private KeycloakUserRecord toUserRecord(JsonNode node) {
        Long createdTimestamp = node.hasNonNull("createdTimestamp") ? node.get("createdTimestamp").asLong() : null;
        return new KeycloakUserRecord(
                node.path("id").asText(null),
                node.path("username").asText(null),
                node.hasNonNull("email") ? node.get("email").asText() : null,
                node.path("enabled").asBoolean(false),
                createdTimestamp != null ? Instant.ofEpochMilli(createdTimestamp) : null);
    }

    // ---- Bas niveau HTTP + token ----

    private synchronized String getServiceAccountToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry.minus(TOKEN_REFRESH_MARGIN))) {
            return cachedToken;
        }
        if (properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
            throw new KeycloakAdminException(
                    "Administration Keycloak indisponible : compte de service non configure (app.keycloak.admin.client-secret manquant).");
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + encode(properties.getClientId())
                + "&client_secret=" + encode(properties.getClientSecret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getServerUrl() + "/realms/" + properties.getRealm() + "/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new KeycloakAdminException("Administration Keycloak indisponible (authentification du compte de service refusee).");
            }
            JsonNode body = objectMapper.readTree(response.body());
            cachedToken = body.path("access_token").asText();
            long expiresIn = body.path("expires_in").asLong(60);
            cachedTokenExpiry = Instant.now().plusSeconds(expiresIn);
            return cachedToken;
        } catch (IOException e) {
            throw new KeycloakAdminException("Administration Keycloak injoignable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeycloakAdminException("Administration Keycloak injoignable (interrompu).");
        }
    }

    private JsonNode getOrThrowNotFound(String path, String notFoundMessage) {
        HttpResponse<String> response = send(newRequest(path, "GET").build());
        if (response.statusCode() == 404) {
            throw new ResourceNotFoundException(notFoundMessage);
        }
        return parseOrThrow(response, path);
    }

    private JsonNode get(String path) {
        HttpResponse<String> response = send(newRequest(path, "GET").build());
        return parseOrThrow(response, path);
    }

    private void put(String path, JsonNode body) {
        HttpRequest request = newRequest(path, "PUT")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build();
        HttpResponse<String> response = send(request);
        requireSuccess(response, path);
    }

    private void post(String path, JsonNode body) {
        HttpRequest request = newRequest(path, "POST")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build();
        HttpResponse<String> response = send(request);
        requireSuccess(response, path);
    }

    private void delete(String path, JsonNode body) {
        HttpRequest request = newRequest(path, "DELETE")
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build();
        HttpResponse<String> response = send(request);
        requireSuccess(response, path);
    }

    private HttpRequest.Builder newRequest(String path, String method) {
        return HttpRequest.newBuilder(URI.create(properties.getServerUrl() + path))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + getServiceAccountToken());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new KeycloakAdminException("Administration Keycloak injoignable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeycloakAdminException("Administration Keycloak injoignable (interrompu).");
        }
    }

    private JsonNode parseOrThrow(HttpResponse<String> response, String path) {
        requireSuccess(response, path);
        try {
            return response.body() == null || response.body().isBlank() ? objectMapper.createArrayNode() : objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new KeycloakAdminException("Reponse Keycloak illisible.");
        }
    }

    private void requireSuccess(HttpResponse<String> response, String path) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Appel Admin API Keycloak {} a echoue avec le statut {}", path, response.statusCode());
            throw new KeycloakAdminException("Administration Keycloak a refuse l'operation (statut " + response.statusCode() + ").");
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (IOException e) {
            throw new KeycloakAdminException("Serialisation JSON impossible.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
