package com.loadpilot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Master prompt final (Lot C) — verifie le CONTENU DECLARE du profil "prod"
 * (application-prod.yml) sans demarrer de contexte Spring complet sous ce
 * profil : un vrai demarrage necessiterait un PostgreSQL/Keycloak de
 * production reels (aucune valeur par defaut n'est fournie dans ce
 * fichier, par conception - voir son en-tete), non disponibles dans cet
 * environnement. Ce test est donc un test STATIQUE de configuration
 * (le fichier existe, contient exactement les cles attendues, sans defaut
 * dangereux) — le comportement runtime complet sous SPRING_PROFILES_ACTIVE=prod
 * reste NON VERIFIE dans cet environnement (voir rapport de phase).
 *
 * SnakeYAML est deja une dependance reelle transitive de spring-boot-starter
 * (utilisee en interne par Spring Boot lui-meme pour parser les YAML) —
 * aucune nouvelle dependance ajoutee pour ce test.
 */
class ProdProfileConfigTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadProdYaml() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application-prod.yml")) {
            assertThat(in).as("application-prod.yml doit exister sur le classpath principal").isNotNull();
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> root, String... path) {
        Map<String, Object> current = root;
        for (String key : path) {
            Object value = current.get(key);
            assertThat(value).as("cle attendue absente : " + String.join(".", path)).isNotNull();
            current = (Map<String, Object>) value;
        }
        return current;
    }

    @Test
    void swaggerIsDisabled_inProdProfile() {
        Map<String, Object> yaml = loadProdYaml();
        Map<String, Object> apiDocs = nested(yaml, "springdoc", "api-docs");
        Map<String, Object> swaggerUi = nested(yaml, "springdoc", "swagger-ui");

        assertThat(apiDocs.get("enabled")).isEqualTo(false);
        assertThat(swaggerUi.get("enabled")).isEqualTo(false);
    }

    @Test
    void datasourceHasNoDefaultValue_forcesExplicitEnvVars() {
        Map<String, Object> yaml = loadProdYaml();
        Map<String, Object> datasource = nested(yaml, "spring", "datasource");

        // Pas de "${DB_URL:valeur-par-defaut}" - uniquement "${DB_URL}",
        // jamais de fallback localhost dangereux en production.
        assertThat(datasource.get("url")).isEqualTo("${DB_URL}");
        assertThat(datasource.get("username")).isEqualTo("${DB_USERNAME}");
        assertThat(datasource.get("password")).isEqualTo("${DB_PASSWORD}");
        assertThat(datasource.get("driver-class-name")).isEqualTo("org.postgresql.Driver");
    }

    @Test
    void keycloakIssuerHasNoDefaultValue() {
        Map<String, Object> yaml = loadProdYaml();
        Map<String, Object> jwt = nested(yaml, "spring", "security", "oauth2", "resourceserver", "jwt");

        assertThat(jwt.get("issuer-uri")).isEqualTo("${KEYCLOAK_ISSUER_URI}");
    }

    @Test
    void corsOriginHasNoDefaultValue() {
        Map<String, Object> yaml = loadProdYaml();
        Map<String, Object> app = (Map<String, Object>) yaml.get("app");
        Map<String, Object> cors = (Map<String, Object>) app.get("cors");

        assertThat(cors.get("allowed-origins")).isEqualTo(java.util.List.of("${FRONTEND_ORIGIN}"));
    }

    @Test
    void noRealSecretOrCredential_onlyPlaceholders() {
        // Balayage textuel simple du fichier entier : aucune valeur en
        // clair ressemblant a un mot de passe/secret reel, uniquement des
        // placeholders "${...}".
        String content;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application-prod.yml")) {
            content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(content).doesNotContainPattern("(?i)password\\s*:\\s*[^$\\s#][^\\n]*");
        assertThat(content).doesNotContain("BEGIN RSA").doesNotContain("BEGIN PRIVATE");
    }
}
