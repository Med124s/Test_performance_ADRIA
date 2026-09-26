# Keycloak local — infrastructure reproductible (P1-M)

Ce dossier contient l'export réel et validé du réalm Keycloak utilisé par LoadPilot, pour permettre à une nouvelle machine de reproduire l'authentification sans reconfiguration manuelle complexe.

`realm-export.json` a été généré par `POST /admin/realms/loadpilot/partial-export?exportClients=true&exportGroupsAndRoles=true` sur une instance Keycloak 26.7.3 réellement démarrée et validée (P1-M) — ce n'est pas un fichier écrit à la main. Il contient le réalm `loadpilot`, ses 3 rôles réels (`ROLE_SUPER_ADMIN`, `ROLE_PERFORMANCE_ENGINEER`, `ROLE_VIEWER`) et ses 2 clients (`loadpilot-frontend`, `loadpilot-backend-admin`). **Il ne contient aucun utilisateur métier, aucun mot de passe, aucun secret réel** (le secret du client confidentiel est masqué par Keycloak lui-même à l'export — `"secret":"**********"` — et sera régénéré à l'import).

## Pourquoi pas Docker

Aucun `docker-compose` n'est fourni pour Keycloak dans ce dépôt car **Docker n'est pas disponible dans l'environnement où cette phase a été réalisée** (vérifié : `docker` introuvable). L'alternative retenue — une distribution Keycloak standalone (Quarkus, aucune dépendance à Docker) — est celle réellement utilisée et validée pour ce projet depuis ses premières phases. Si Docker est disponible dans votre environnement, `realm-export.json` reste directement réutilisable dans un conteneur Keycloak officiel (`quay.io/keycloak/keycloak:26.7.3`) via son mécanisme d'import de réalm standard.

## Prérequis

- JDK 21 (le même que celui utilisé pour `backend/`).
- Environ 500 Mo d'espace disque pour la distribution Keycloak.

## Démarrage (sans Docker)

```bash
# 1. Télécharger et extraire Keycloak 26.7.3 (version réellement utilisée par ce projet)
curl -L -o keycloak-26.7.3.zip https://github.com/keycloak/keycloak/releases/download/26.7.3/keycloak-26.7.3.zip
unzip keycloak-26.7.3.zip
cd keycloak-26.7.3

# 2. Créer un compte admin local (une seule fois)
export KEYCLOAK_ADMIN=admin
export KEYCLOAK_ADMIN_PASSWORD=<choisissez un mot de passe local>

# 3. Démarrer en mode développement, sur le port attendu par le backend/frontend LoadPilot
bin/kc.sh start-dev --http-port=8081
# Windows (cmd/PowerShell, hors Git Bash) : bin\kc.bat start-dev --http-port=8081
```

Vérifier que Keycloak répond réellement :
```bash
curl http://localhost:8081/realms/master/.well-known/openid-configuration
```

## Importer le réalm LoadPilot

Keycloak doit être démarré (étape précédente) avant l'import.

**Option A — via l'Admin Console** (http://localhost:8081/admin, se connecter avec le compte admin créé ci-dessus) : Realm settings → **Import** → sélectionner `keycloak/realm-export.json` de ce dépôt.

**Option B — via l'API Admin** (scriptable) :
```bash
ADMIN_TOKEN=$(curl -s -X POST "http://localhost:8081/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=admin-cli&username=$KEYCLOAK_ADMIN&password=$KEYCLOAK_ADMIN_PASSWORD" \
  | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

curl -X POST "http://localhost:8081/admin/realms" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  --data-binary @keycloak/realm-export.json
```

Vérifier l'import :
```bash
curl http://localhost:8081/realms/loadpilot/.well-known/openid-configuration
```
Cette commande doit retourner un JSON valide (code 200) — **c'est le critère de réussite de cette phase, réellement testé** (voir rapport P1-M).

## Après l'import — ce qui reste à faire manuellement

1. **Secret du client `loadpilot-backend-admin`** (confidentiel, requis pour `KEYCLOAK_ADMIN_CLIENT_SECRET` côté backend) : régénéré automatiquement par Keycloak à l'import (l'ancien secret n'est jamais exporté). Le récupérer dans l'Admin Console : Clients → `loadpilot-backend-admin` → onglet Credentials → Regenerate/Copy. **Ne jamais commiter cette valeur** — la placer uniquement dans la variable d'environnement du backend, jamais dans `perftest-frontend/`.
2. **Comptes utilisateurs métier** : le réalm importé ne contient volontairement aucun utilisateur (voir "Pourquoi aucun utilisateur n'est fourni" ci-dessous). Créer au moins un compte par rôle pour tester (Users → Add user → Credentials → définir un mot de passe → Role mapping → assigner `ROLE_SUPER_ADMIN`, `ROLE_PERFORMANCE_ENGINEER` ou `ROLE_VIEWER`).

## Pourquoi aucun utilisateur n'est fourni

Les 4 comptes historiques de démonstration (`db.json` legacy, mot de passe en clair) **n'ont jamais eu d'identité Keycloak réelle** et ne peuvent pas être transformés automatiquement en comptes Keycloak sans fabriquer une identité qui n'a jamais existé (voir rapports P1-H/P1-MASTER). Créer les comptes de test nécessaires est une action volontairement manuelle, décrite ci-dessus.

## Variables backend attendues (déjà documentées dans `backend/src/main/resources/application-dev.yml`)

```
KEYCLOAK_ISSUER_URI=http://localhost:8081/realms/loadpilot
KEYCLOAK_ADMIN_SERVER_URL=http://localhost:8081
KEYCLOAK_ADMIN_REALM=loadpilot
KEYCLOAK_ADMIN_CLIENT_ID=loadpilot-backend-admin
KEYCLOAK_ADMIN_CLIENT_SECRET=<valeur récupérée dans l'Admin Console, jamais commitée>
```

## Variables frontend attendues (déjà documentées dans `perftest-frontend/.env.example`)

```
VITE_KEYCLOAK_URL=http://localhost:8081
VITE_KEYCLOAK_REALM=loadpilot
VITE_KEYCLOAK_CLIENT_ID=loadpilot-frontend
```

(`VITE_AUTH_PROVIDER` n'existe plus depuis le retrait du Mode mock — P1-O :
`AUTH_PROVIDER` vaut désormais toujours la constante `'keycloak'`, voir
`perftest-frontend/src/services/auth/keycloakConfig.ts`.)

Le client `loadpilot-frontend` importé est déjà configuré avec `redirectUris=["http://localhost:3000/*"]` et `webOrigins=["http://localhost:3000"]`, correspondant exactement au port par défaut de Vite (`perftest-frontend/vite.config.ts`).

## Renouvellement de session (refresh token)

Le frontend renouvelle automatiquement l'access token via le grant OAuth2
standard `refresh_token` (voir `perftest-frontend/src/services/auth/keycloakClient.ts`)
avant son expiration réelle (marge de 30s), sans jamais redemander de mot de
passe tant que le refresh token est valide. **Testé réellement (PROUVÉ)**
contre cette configuration de réalm : un appel `grant_type=refresh_token`
avec le `refresh_token` obtenu à la connexion renvoie un nouveau couple
access/refresh token, et Keycloak applique la rotation du refresh token
(un nouveau `refresh_token` est renvoyé à chaque renouvellement). Si le
refresh token est expiré/révoqué, le backend Keycloak répond en erreur : le
frontend efface alors la session locale et l'utilisateur doit se
ré-authentifier normalement (aucun token n'est jamais fabriqué côté client).

La durée de vie de l'access token (`accessTokenLifespan`) et celle du refresh
token (`ssoSessionMaxLifespan`/`clientOfflineSessionMaxLifespan` selon le
type de session) sont des réglages du réalm — ce dépôt ne prescrit aucune
valeur particulière au-delà de celles déjà présentes dans
`realm-export.json`.

## Production hardening (recommandations — rien de ce qui suit n'est activé par ce dépôt)

`realm-export.json` reflète une configuration de **développement local**. Les
points suivants sont des recommandations pour un déploiement de production ;
aucun n'a été appliqué ici, et ce document ne modifie pas `realm-export.json`
en conséquence (une telle modification devrait être validée contre une
instance Keycloak réellement démarrée, pas éditée à l'aveugle dans le JSON) :

- **Protection anti-bruteforce** : `bruteForceProtected` vaut `false` dans
  le fichier `realm-export.json` versionné (snapshot pris avant ce
  durcissement). **P1-Q Étape B — activé réellement (PROUVÉ)** sur
  l'instance Keycloak locale utilisée pour le développement de ce projet,
  via l'API Admin (`PUT /admin/realms/loadpilot`, un seul champ modifié,
  vérifié par une relecture de la configuration après coup :
  `bruteForceProtected: true`). Cette activation concerne **uniquement
  l'instance locale en cours d'exécution**, pas le fichier `realm-export.json`
  du dépôt (volontairement non ré-exporté ici pour éviter un diff large et
  non maîtrisé sur ce fichier suite à un changement d'un seul champ — voir
  rapport P1-Q Étape B). **Conséquence concrète** : réimporter
  `realm-export.json` tel quel sur une nouvelle instance (ou via
  `docker-compose.yml` — voir racine du dépôt) redonnera
  `bruteForceProtected: false` ; reproduire l'activation nécessite de
  répéter cette même commande Admin API (ou de l'activer manuellement dans
  la Console Admin : Realm settings → Security defenses → Brute force
  detection) sur cette nouvelle instance.
- **HTTPS obligatoire** : `sslRequired` vaut `"none"` dans l'export actuel,
  ce qui convient uniquement à un usage `localhost` de développement (aucune
  modification apportée — un passage à `"external"`/`"all"` sans un vrai
  reverse proxy TLS devant Keycloak casserait l'accès local actuel).
  RECOMMANDÉ de passer à `"external"` (ou `"all"`) dès que Keycloak est
  exposé au-delà de la machine locale, **accompagné d'un vrai certificat/
  reverse proxy TLS** (non fourni par ce dépôt — aucune infrastructure HTTPS
  fictive n'est simulée ici) : sans cela, activer cette valeur bloquerait
  l'accès plutôt que de le sécuriser. Ne jamais désactiver TLS entre le
  frontend/backend et Keycloak en dehors du contexte `localhost` documenté
  ici.
- **Conserver PKCE** : le client `loadpilot-frontend` doit rester `public`
  avec PKCE (`S256`) actif — ne jamais lui attribuer de secret (un secret
  dans une SPA est visible dans le code livré au navigateur et n'apporte
  aucune sécurité réelle, voir commentaire dans `keycloakClient.ts`).
- **Secret du client confidentiel `loadpilot-backend-admin`** : à gérer
  exclusivement côté serveur (variable d'environnement backend
  `KEYCLOAK_ADMIN_CLIENT_SECRET`), jamais commité, et à régénérer
  périodiquement ainsi qu'immédiatement en cas de doute sur une fuite.
- **Jamais de Resource Owner Password Grant pour l'application réelle** : ce
  grant (`grant_type=password`) n'a été utilisé dans ce projet que comme
  **outil de validation technique en ligne de commande** (curl), jamais par
  le frontend — celui-ci utilise exclusivement le flux Authorization Code +
  PKCE. Le conserver ainsi en production (ne jamais l'exposer comme méthode
  de connexion de l'application).
- **URLs de production** : `redirectUris`/`webOrigins` du client
  `loadpilot-frontend` pointent vers `http://localhost:3000` dans l'export
  actuel — à remplacer par les URLs réelles de production avant tout
  déploiement (jamais un wildcard large en production).
