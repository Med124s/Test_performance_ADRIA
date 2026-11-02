# Image du frontend LoadPilot : build Vite (Node), puis fichiers statiques servis par nginx.
#
# Les variables VITE_* sont integrees au build ; elles designent les ports de l'hote, utilises par le navigateur.
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

# Etage 2 : nginx sert le build statique (dist/).
FROM nginx:alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
