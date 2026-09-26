# Master prompt final (Lot E) — build multi-stage standard (Node + nginx),
# aucune dependance/etape inventee. Syntaxiquement valide et coherent avec
# package.json (script "build" = tsc && vite build, sortie dans dist/) —
# mais JAMAIS execute dans cet environnement (Docker absent, verrou Avast/
# esbuild egalement documente pour le build LOCAL non-Docker, voir rapport
# de phase) : DOCKER RUNTIME TEST — NOT VERIFIED.
#
# IMPORTANT (variables VITE_*) : Vite les integre au JavaScript au moment du
# BUILD, jamais au demarrage du conteneur (contrairement a une variable
# d'environnement backend classique). Le NAVIGATEUR (cote HOTE, jamais dans
# le reseau interne Docker) doit pouvoir les resoudre : elles doivent donc
# pointer vers les ports EXPOSES A L'HOTE par docker-compose.yml
# (http://localhost:8081, http://localhost:8080), jamais vers un nom de
# service Docker interne ("keycloak"/"backend") que le navigateur ne peut
# pas resoudre.
FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .

ARG VITE_KEYCLOAK_URL=http://localhost:8081
ARG VITE_KEYCLOAK_REALM=loadpilot
ARG VITE_KEYCLOAK_CLIENT_ID=loadpilot-frontend
ARG VITE_SPRING_API_URL=http://localhost:8080
ENV VITE_KEYCLOAK_URL=${VITE_KEYCLOAK_URL} \
    VITE_KEYCLOAK_REALM=${VITE_KEYCLOAK_REALM} \
    VITE_KEYCLOAK_CLIENT_ID=${VITE_KEYCLOAK_CLIENT_ID} \
    VITE_SPRING_API_URL=${VITE_SPRING_API_URL}

RUN npx tsc && npx vite build

# Etage 2 : nginx sert le build statique (dist/) - jamais "npm run dev" en
# production (serveur de developpement, non concu pour cet usage).
FROM nginx:alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
