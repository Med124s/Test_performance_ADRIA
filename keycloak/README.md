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

## Thème de connexion LoadPilot (`themes/loadpilot`)

La page de connexion est un **thème Keycloak FreeMarker** aux couleurs de LoadPilot. Le flux reste Authorization Code + PKCE : le mot de passe n'est saisi que sur la page Keycloak, jamais dans React. L'application n'a plus de page `/login` intermédiaire : un utilisateur non connecté est envoyé directement sur ce thème, puis ramené sur la page qu'il avait demandée.

- **Compatibilité** : Keycloak 26.x. Le thème hérite de `keycloak.v2` (`parent=keycloak.v2`), donc les pages non redessinées (OTP, conditions d'utilisation…) restent fonctionnelles dans le même cadre.
- **Aucun build** : pas de npm ni de Maven, Keycloak lit les fichiers tels quels.

```
themes/loadpilot/login/
  theme.properties            parent=keycloak.v2, styles, langues fr/en
  template.ftl                mise en page commune (panneau de marque + carte)
  lp.ftl                      composants : logo, icônes, champs, bouton afficher/masquer
  login.ftl                   connexion
  login-reset-password.ftl    mot de passe oublié
  login-update-password.ftl   nouveau mot de passe
  login-update-profile.ftl    compléter le profil (action UPDATE_PROFILE)
  error.ftl, info.ftl, logout-confirm.ftl, login-page-expired.ftl
  messages/messages_fr.properties, messages_en.properties   textes et messages d'erreur
  resources/css/loadpilot.css, resources/js/loadpilot.js, resources/img/favicon.svg
```

### Réglages du réalm (déjà présents dans `realm-export.json`)

- Réalm :
  - `displayName=LoadPilot` ;
  - `loginTheme=loadpilot` ;
  - `internationalizationEnabled=true`, `supportedLocales=[fr, en]`, `defaultLocale=fr` ;
  - `rememberMe=true` (case « Rester connecté ») ;
  - `resetPasswordAllowed=true` (lien « Mot de passe oublié ? ») ;
  - protection contre la force brute (`bruteForceProtected=true`, voir « Blocage après échecs » ci-dessous) ;
  - serveur d'e-mails `smtpServer` lu dans les variables `KC_SMTP_*` (voir « E-mails » ci-dessous).
- Client `loadpilot-frontend` :
  - attribut `login_theme=loadpilot` ;
  - `baseUrl=http://localhost:3000/`, utilisé par le bouton « Retour à LoadPilot » des pages d'erreur.

L'import (`--import-realm`) **ne modifie jamais un réalm qui existe déjà**. Sur une instance déjà configurée, appliquez les réglages une fois de l'une de ces deux façons :

- **Avec l'Admin Console** :
  - Realm settings → General : *Display name* ;
  - Realm settings → Themes : *Login theme* = `loadpilot` ;
  - Realm settings → Localization : activer, `fr` par défaut ;
  - Realm settings → Login : *Remember me*, *Forgot password* ;
  - Realm settings → Security defenses → Brute force detection ;
  - Clients → `loadpilot-frontend` → Login settings : *Login theme* = `loadpilot`.
- **Avec kcadm**, dans le conteneur. Saisissez les identifiants admin à l'invite ou passez-les par des variables d'environnement, jamais en clair dans un fichier :

```bash
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"
$K update realms/loadpilot -s displayName=LoadPilot -s loginTheme=loadpilot \
  -s internationalizationEnabled=true -s 'supportedLocales=["fr","en"]' -s defaultLocale=fr \
  -s rememberMe=true -s resetPasswordAllowed=true \
  -s bruteForceProtected=true -s failureFactor=10 -s waitIncrementSeconds=60 -s maxFailureWaitSeconds=900
ID=$($K get clients -r loadpilot -q clientId=loadpilot-frontend --fields id --format csv --noquotes)
$K update clients/$ID -r loadpilot -s 'attributes."login_theme"=loadpilot' -s baseUrl=http://localhost:3000/
```

### Déployer ou redéployer le thème

| Situation | Commande |
|---|---|
| `docker compose` du dépôt | Le dossier est monté par `docker-compose.yml` (`./keycloak/themes/loadpilot:/opt/keycloak/themes/loadpilot:ro`) : `docker compose up -d keycloak`. |
| Conteneur `keycloak` déjà lancé **sans** ce volume | `docker cp keycloak/themes/loadpilot/. keycloak:/opt/keycloak/themes/loadpilot/`. Ne recréez pas ce conteneur pour ajouter le volume : sans volume de données, ses utilisateurs et secrets seraient perdus. |
| Keycloak standalone (zip) | Copier `keycloak/themes/loadpilot` dans `<dossier-keycloak>/themes/loadpilot`. |

- **En `start-dev`**, le cache des thèmes est désactivé : il suffit de recharger la page de connexion après une modification.
- **En `start`** (production), les thèmes sont mis en cache : redémarrez Keycloak après chaque modification.
- Pour vérifier, ouvrez http://localhost:3000 sans être connecté : la page « Connexion » LoadPilot doit s'afficher directement.

### Modifier les textes

Les textes sont dans `messages/messages_fr.properties` (et `_en`). Une apostrophe s'écrit `''` (deux apostrophes droites). Les clés absentes reprennent la traduction standard de Keycloak.

### Blocage après échecs (protection contre la force brute)

| Réglage | Valeur | Effet |
|---|---|---|
| `failureFactor` | 10 | Le compte est bloqué après 10 mots de passe erronés. |
| `waitIncrementSeconds` | 60 | Le premier blocage dure 1 min ; chaque nouvelle série de 10 échecs ajoute 1 min (stratégie `MULTIPLE`). |
| `maxFailureWaitSeconds` | 900 | Le blocage ne dépasse jamais 15 min. |
| `quickLoginCheckMilliSeconds` / `minimumQuickLoginWaitSeconds` | 1000 / 60 | Deux échecs à moins d'1 s d'écart (robot) bloquent aussi 1 min. |
| `maxDeltaTimeSeconds` | 43200 | Le compteur d'échecs repart à zéro après 12 h sans échec, ou après une connexion réussie. |
| `permanentLockout` | false | Jamais de blocage définitif : le compte se débloque seul. |

Pendant le blocage, **même le bon mot de passe est refusé avec le message générique « Nom d'utilisateur ou mot de passe incorrect. »**. C'est le comportement sûr de Keycloak : un attaquant ne peut pas savoir qu'un compte existe ni qu'il est bloqué. Sous le formulaire, un texte fixe, identique pour tous, prévient que l'accès peut être bloqué temporairement après plusieurs échecs.

Un administrateur peut débloquer un compte tout de suite : Admin Console → Users → l'utilisateur → bouton *Unlock* (ou `kcadm.sh delete attack-detection/brute-force/users/<id> -r loadpilot`).

### E-mails (mot de passe oublié) : Mailpit en développement, vrai SMTP en production

En développement et en démo, `docker-compose.yml` démarre **Mailpit**, un faux serveur SMTP qui capture tous les e-mails envoyés par Keycloak sans les délivrer :

- SMTP : `mailpit:1025` depuis les conteneurs, `localhost:1025` depuis la machine ;
- interface web : http://localhost:8025, où l'on lit l'e-mail « Réinitialiser le mot de passe » et clique sur son lien.

Le réalm lit sa configuration SMTP dans des variables d'environnement au moment de l'import. `realm-export.json` contient des emplacements `${KC_SMTP_HOST:mailpit}`, etc. ; la valeur après `:` est utilisée si la variable est absente. Pour un vrai serveur, renseignez ces variables (dans `.env` avec `docker compose`, voir `.env.example`) :

| Variable | Défaut (Mailpit) | Exemple production |
|---|---|---|
| `KC_SMTP_HOST` | `mailpit` | `smtp.exemple.com` |
| `KC_SMTP_PORT` | `1025` | `587` |
| `KC_SMTP_FROM` | `no-reply@loadpilot.local` | `no-reply@exemple.com` |
| `KC_SMTP_FROM_NAME` | `LoadPilot` | `LoadPilot` |
| `KC_SMTP_STARTTLS` / `KC_SMTP_SSL` | `false` / `false` | `true` / `false` |
| `KC_SMTP_AUTH` | `false` | `true` |
| `KC_SMTP_USER` / `KC_SMTP_PASSWORD` | vides | identifiants du serveur, **uniquement dans `.env`, jamais dans Git** |

Comme les autres réglages, ces variables ne s'appliquent qu'à l'**import d'un nouveau réalm**. Sur un réalm existant, renseignez le serveur dans Admin Console → Realm settings → Email (bouton *Test connection*), ou avec kcadm :

```bash
$K update realms/loadpilot -s 'smtpServer={"host":"mailpit","port":"1025","from":"no-reply@loadpilot.local","fromDisplayName":"LoadPilot","starttls":"false","ssl":"false","auth":"false"}'
```

Le compte qui demande une réinitialisation doit avoir une adresse e-mail dans Keycloak. Par sécurité, la page affiche toujours le même message (« Si un compte correspond… »), que le compte existe ou non.

### Limites connues

- **Contenu des e-mails** : ils utilisent le gabarit standard de Keycloak, en français ; seul l'expéditeur affiche « LoadPilot ». Ils ne reprennent pas les couleurs du thème.
- **Mailpit** n'a pas de volume : les e-mails capturés disparaissent quand son conteneur est recréé.

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
