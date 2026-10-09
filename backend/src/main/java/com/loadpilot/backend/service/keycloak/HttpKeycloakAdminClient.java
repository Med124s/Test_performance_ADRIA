package com.loadpilot.backend.service.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loadpilot.backend.config.KeycloakAdminProperties;
import com.loadpilot.backend.enums.AppRole;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ConflictException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    @Override
    public Map<String, Instant> lastLoginByUser() {
        // Evenements renvoyes du plus recent au plus ancien : le premier vu
        // pour un utilisateur est sa derniere connexion.
        JsonNode events = get("/admin/realms/" + properties.getRealm() + "/events?type=LOGIN&first=0&max=" + properties.getLoginEventsMax());
        Map<String, Instant> result = new HashMap<>();
        if (events.isArray()) {
            for (JsonNode event : events) {
                String userId = event.path("userId").asText(null);
                long time = event.path("time").asLong(0);
                if (userId != null && time > 0) {
                    result.merge(userId, Instant.ofEpochMilli(time), (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        }
        return result;
    }

    @Override
    public String createUser(NewKeycloakUser user) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", user.username());
        body.put("email", user.email());
        if (user.firstName() != null) body.put("firstName", user.firstName());
        if (user.lastName() != null) body.put("lastName", user.lastName());
        body.put("enabled", true);
        body.put("emailVerified", false);
        if (user.password() != null) {
            // Mot de passe initial : verifie par Keycloak selon la politique du realm ;
            // "temporary" ajoute l'action requise UPDATE_PASSWORD (changement a la 1re connexion).
            body.putArray("credentials").add(passwordCredential(user.password(), user.temporaryPassword()));
        }
        String path = "/admin/realms/" + properties.getRealm() + "/users";
        HttpResponse<String> response = send(newRequest(path, "POST")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build());
        if (response.statusCode() == 409) {
            throw new ConflictException("Un utilisateur avec ce nom d'utilisateur ou cet e-mail existe déjà.");
        }
        rejectPasswordPolicyViolation(response);
        requireSuccess(response, path);
        // Keycloak renvoie l'adresse du compte cree : .../users/{id}
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new KeycloakAdminException("Le service d'authentification n'a pas renvoyé l'identifiant du compte créé."));
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Override
    public void setPassword(String userId, String password, boolean temporary) {
        String path = "/admin/realms/" + properties.getRealm() + "/users/" + encode(userId) + "/reset-password";
        HttpResponse<String> response = send(newRequest(path, "PUT")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(writeJson(passwordCredential(password, temporary))))
                .build());
        if (response.statusCode() == 404) {
            throw new ResourceNotFoundException("Utilisateur introuvable : " + userId);
        }
        rejectPasswordPolicyViolation(response);
        requireSuccess(response, path);
    }

    /** Types lus pour le journal d'audit (categorie Securite). */
    static final List<String> SECURITY_EVENT_TYPES = List.of("LOGIN", "LOGIN_ERROR", "LOGOUT", "UPDATE_PASSWORD",
            "SEND_RESET_PASSWORD", "RESET_PASSWORD", "USER_DISABLED_BY_TEMPORARY_LOCKOUT", "USER_DISABLED_BY_PERMANENT_LOCKOUT");

    @Override
    public List<KeycloakSecurityEvent> securityEvents(int first, int max) {
        StringBuilder path = new StringBuilder("/admin/realms/").append(properties.getRealm()).append("/events?first=").append(first).append("&max=").append(max);
        SECURITY_EVENT_TYPES.forEach(t -> path.append("&type=").append(t));
        JsonNode events = get(path.toString());
        List<KeycloakSecurityEvent> result = new ArrayList<>();
        if (events.isArray()) {
            for (JsonNode e : events) {
                result.add(new KeycloakSecurityEvent(
                        e.path("id").asText(null),
                        e.path("type").asText(""),
                        e.path("time").asLong(0),
                        e.hasNonNull("userId") ? e.get("userId").asText() : null,
                        e.path("details").hasNonNull("username") ? e.path("details").get("username").asText() : null,
                        e.hasNonNull("ipAddress") ? e.get("ipAddress").asText() : null,
                        e.hasNonNull("error") ? e.get("error").asText() : null));
            }
        }
        return result;
    }

    @Override
    public String getPasswordPolicy() {
        // Necessite le role realm-management "view-realm" pour le compte de service.
        JsonNode realm = get("/admin/realms/" + properties.getRealm());
        return realm.path("passwordPolicy").asText("");
    }

    @Override
    public List<KeycloakSession> userSessions(String userId) {
        JsonNode sessions = get("/admin/realms/" + properties.getRealm() + "/users/" + encode(userId) + "/sessions");
        List<KeycloakSession> result = new ArrayList<>();
        if (sessions.isArray()) {
            for (JsonNode s : sessions) {
                List<String> clients = new ArrayList<>();
                s.path("clients").forEach(c -> clients.add(c.asText()));
                result.add(new KeycloakSession(s.path("id").asText(null), text(s, "ipAddress"), epochMillis(s, "start"),
                        epochMillis(s, "lastAccess"), clients, null, null, null, false, false));
            }
        }
        return result;
    }

    @Override
    public List<KeycloakSession> accountSessions(String userAccessToken) {
        // API « compte » : appelee avec le jeton de l'utilisateur lui-meme (jamais le compte de service).
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getServerUrl() + "/realms/" + properties.getRealm()
                        + "/account/sessions/devices"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + userAccessToken)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new KeycloakAdminException("Sessions indisponibles (statut " + response.statusCode() + ").");
        }
        JsonNode devices;
        try {
            devices = objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new KeycloakAdminException("Réponse du service d'authentification illisible.");
        }
        List<KeycloakSession> result = new ArrayList<>();
        for (JsonNode d : devices) {
            String os = text(d, "os");
            String osVersion = text(d, "osVersion");
            String osLabel = os == null || "Other".equalsIgnoreCase(os) ? null
                    : osVersion == null || "Unknown".equalsIgnoreCase(osVersion) ? os : os + " " + osVersion;
            String device = text(d, "device");
            for (JsonNode s : d.path("sessions")) {
                List<String> clients = new ArrayList<>();
                s.path("clients").forEach(c -> clients.add(c.hasNonNull("clientName") && !c.get("clientName").asText().isBlank()
                        ? c.get("clientName").asText() : c.path("clientId").asText()));
                String browser = text(s, "browser");
                result.add(new KeycloakSession(s.path("id").asText(null), text(s, "ipAddress"), epochSeconds(s, "started"),
                        epochSeconds(s, "lastAccess"), clients, browser, osLabel,
                        device == null || "Other".equalsIgnoreCase(device) ? null : device,
                        d.path("mobile").asBoolean(false), s.path("current").asBoolean(false)));
            }
        }
        return result;
    }

    @Override
    public void logoutUser(String userId) {
        String path = "/admin/realms/" + properties.getRealm() + "/users/" + encode(userId) + "/logout";
        HttpResponse<String> response = send(newRequest(path, "POST").POST(HttpRequest.BodyPublishers.noBody()).build());
        if (response.statusCode() == 404) {
            throw new ResourceNotFoundException("Utilisateur introuvable : " + userId);
        }
        requireSuccess(response, path);
    }

    @Override
    public List<KeycloakLogin> recentLogins(String userId, int max) {
        JsonNode events = get("/admin/realms/" + properties.getRealm() + "/events?type=LOGIN&user=" + encode(userId)
                + "&first=0&max=" + Math.max(1, max));
        List<KeycloakLogin> result = new ArrayList<>();
        if (events.isArray()) {
            for (JsonNode e : events) {
                long time = e.path("time").asLong(0);
                if (time > 0) result.add(new KeycloakLogin(Instant.ofEpochMilli(time), text(e, "ipAddress")));
            }
        }
        result.sort((a, b) -> b.time().compareTo(a.time()));
        return result;
    }

    @Override
    public KeycloakSmtpInfo smtpInfo() {
        JsonNode smtp = get("/admin/realms/" + properties.getRealm()).path("smtpServer");
        if (!smtp.isObject() || text(smtp, "host") == null) return null;
        // Jamais "user" ni "password" : seuls l'hote, le port et l'expediteur sont lus.
        return new KeycloakSmtpInfo(text(smtp, "host"), text(smtp, "port"), text(smtp, "from"), text(smtp, "fromDisplayName"),
                "true".equalsIgnoreCase(text(smtp, "auth")));
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.get(field).asText().isBlank() ? node.get(field).asText() : null;
    }

    private static Instant epochMillis(JsonNode node, String field) {
        long v = node.path(field).asLong(0);
        return v > 0 ? Instant.ofEpochMilli(v) : null;
    }

    private static Instant epochSeconds(JsonNode node, String field) {
        long v = node.path(field).asLong(0);
        return v > 0 ? Instant.ofEpochSecond(v) : null;
    }

    private ObjectNode passwordCredential(String password, boolean temporary) {
        ObjectNode credential = objectMapper.createObjectNode();
        credential.put("type", "password");
        credential.put("value", password);
        credential.put("temporary", temporary);
        return credential;
    }

    /** 400 de Keycloak pour un mot de passe refuse -> message francais (sans jamais le mot de passe). */
    private void rejectPasswordPolicyViolation(HttpResponse<String> response) {
        if (response.statusCode() == 400) {
            String message = PasswordPolicyMessages.fromKeycloakError(response.body(), objectMapper);
            if (message != null) {
                throw new BadRequestException(message);
            }
        }
    }

    @Override
    public void deleteUser(String userId) {
        String path = "/admin/realms/" + properties.getRealm() + "/users/" + encode(userId);
        requireSuccess(send(newRequest(path, "DELETE").DELETE().build()), path);
    }

    @Override
    public void sendActionsEmail(String userId, List<String> actions) {
        String path = "/admin/realms/" + properties.getRealm() + "/users/" + encode(userId) + "/execute-actions-email"
                + "?client_id=" + encode(properties.getActionsClientId())
                + "&redirect_uri=" + encode(properties.getActionsRedirectUri())
                + "&lifespan=" + properties.getActionsLifespanSeconds();
        ArrayNode body = objectMapper.createArrayNode();
        actions.forEach(body::add);
        put(path, body);
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
                "Rôle introuvable dans le service d'authentification : " + keycloakRoleName + " (configuration incomplète ?).");
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
                node.hasNonNull("firstName") ? node.get("firstName").asText() : null,
                node.hasNonNull("lastName") ? node.get("lastName").asText() : null,
                node.path("enabled").asBoolean(false),
                createdTimestamp != null ? Instant.ofEpochMilli(createdTimestamp) : null);
    }

    // ---- Bas niveau HTTP + token ----

    private synchronized String getServiceAccountToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry.minus(TOKEN_REFRESH_MARGIN))) {
            return cachedToken;
        }
        if (properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
            log.warn("Compte de service Keycloak non configure : app.keycloak.admin.client-secret (KEYCLOAK_ADMIN_CLIENT_SECRET) manquant.");
            throw new KeycloakAdminException(
                    "Gestion des utilisateurs indisponible : compte de service non configuré.");
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
                throw new KeycloakAdminException("Gestion des utilisateurs indisponible (authentification du compte de service refusée).");
            }
            JsonNode body = objectMapper.readTree(response.body());
            cachedToken = body.path("access_token").asText();
            long expiresIn = body.path("expires_in").asLong(60);
            cachedTokenExpiry = Instant.now().plusSeconds(expiresIn);
            return cachedToken;
        } catch (IOException e) {
            throw new KeycloakAdminException("Service d'authentification injoignable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeycloakAdminException("Service d'authentification injoignable (interrompu).");
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
            throw new KeycloakAdminException("Service d'authentification injoignable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeycloakAdminException("Service d'authentification injoignable (interrompu).");
        }
    }

    private JsonNode parseOrThrow(HttpResponse<String> response, String path) {
        requireSuccess(response, path);
        try {
            return response.body() == null || response.body().isBlank() ? objectMapper.createArrayNode() : objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new KeycloakAdminException("Réponse du service d'authentification illisible.");
        }
    }

    private void requireSuccess(HttpResponse<String> response, String path) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Appel Admin API Keycloak {} a echoue avec le statut {}", path, response.statusCode());
            throw new KeycloakAdminException("Le service d'authentification a refusé l'opération (statut " + response.statusCode() + ").");
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
