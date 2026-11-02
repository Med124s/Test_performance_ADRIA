# Image du backend LoadPilot, construite en deux etages.
#
# Etage 1 : build Maven (Java 21) ; les dependances sont telechargees avant la copie du code.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# Etage 2 : execution avec le JRE seul.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Utilisateur non-root.
RUN useradd --system --create-home --shell /usr/sbin/nologin loadpilot
COPY --from=build /build/target/*.jar app.jar
# Script de demarrage : options Java (memoire, certificats, proxy) lues dans l'environnement (voir start.sh).
COPY start.sh /app/start.sh
RUN sed -i 's/\r$//' /app/start.sh && chmod 755 /app/start.sh && chown loadpilot:loadpilot app.jar
USER loadpilot

EXPOSE 8080

# Configuration fournie par l'environnement du conteneur (voir docker-compose.yml).
ENTRYPOINT ["/app/start.sh"]
