# Master prompt final (Lot E) — build multi-stage standard (Maven + JRE),
# aucune dependance/etape inventee. Syntaxiquement valide et coherent avec
# pom.xml (Java 21, packaging jar via spring-boot-maven-plugin) — mais
# JAMAIS execute dans cet environnement (Docker absent, voir rapport de
# phase) : DOCKER RUNTIME TEST — NOT VERIFIED.
#
# Etage 1 : build - image officielle Maven+Temurin 21 (memes versions que
# pom.xml/<java.version>21</java.version>, verifie avant d'ecrire ce
# fichier). Cache Docker naturel : les dependances (~/.m2) ne sont
# retelechargees que si pom.xml change, pas a chaque modification du code.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# Etage 2 : execution - JRE seul (jamais le JDK complet ni Maven en
# production), image minimale.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Utilisateur non-root (bonne pratique standard, aucune dependance ajoutee).
RUN useradd --system --create-home --shell /usr/sbin/nologin loadpilot
COPY --from=build /build/target/*.jar app.jar
RUN chown loadpilot:loadpilot app.jar
USER loadpilot

EXPOSE 8080

# Aucun secret en dur : toutes les variables (DB_URL, KEYCLOAK_ISSUER_URI,
# KEYCLOAK_ADMIN_CLIENT_SECRET, FRONTEND_ORIGIN...) sont fournies par
# l'environnement du conteneur (voir docker-compose.yml), jamais bakees
# dans l'image.
ENTRYPOINT ["java", "-jar", "app.jar"]
