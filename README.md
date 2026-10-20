# LoadPilot (Cadence) — Documentation de déploiement client

LoadPilot est une plateforme de test de charge (performance testing) : on y déclare des **Applications** cibles, on y crée des **Scénarios** (suites d'étapes HTTP), on les **exécute** réellement (plusieurs utilisateurs virtuels, vraies requêtes HTTP, threads virtuels Java 21) et on en consulte les **résultats** (historique, rapports avec percentiles/débit, métriques, audit).

Ce document est destiné à l'équipe technique d'une société cliente chargée d'installer et d'exploiter LoadPilot. Toute information non vérifiable dans le dépôt au moment de la rédaction est explicitement signalée comme **NON DOCUMENTÉ / À FOURNIR PAR LE CLIENT** ou **À VALIDER LORS DU DÉPLOIEMENT** — rien n'est supposé ou inventé.

## 1. Présentation

LoadPilot permet de définir des cibles HTTP (Applications), des scénarios de charge (Scénarios composés d'Étapes HTTP), de les exécuter avec plusieurs utilisateurs virtuels réels, puis de consulter les résultats (rapports statistiques, métriques, historique, audit). L'authentification et la gestion des comptes/rôles sont entièrement déléguées à Keycloak (aucun mot de passe géré par LoadPilot lui-même).

**Hors scope de cette documentation** : la page `/settings/integrations` (Slack, Teams, Webhooks, Jira, PagerDuty) est présente dans l'interface à titre **purement informatif** — aucune de ces intégrations n'est fonctionnelle à ce jour. Elle ne doit pas être présentée au client comme une fonctionnalité opérationnelle.

## 2. Architecture

```
Navigateur client
        │  OAuth2 Authorization Code + PKCE S256
        ▼
Keycloak (réalm "loadpilot")            — port 8081
        │  JWT (Authorization: Bearer)
        ▼
Backend Spring Boot (backend/)          — port 8080
        │  JPA / Liquibase
        ▼
PostgreSQL                              — port 5432
        │
        ▼
HttpClientExecutionEngine (threads virtuels Java 21)
        │  vraies requêtes HTTP
        ▼
Application(s) cible(s) du client
```

| Composant | Rôle | Port par défaut | Obligatoire |
|---|---|---|---|
| `perftest-frontend/` | Interface web React (Vite) | 3000 | Oui |
| `backend/` | API Spring Boot — source de vérité unique | 8080 | Oui |
| PostgreSQL (externe, non fourni dans ce dépôt) | Base de données réelle | 5432 | Oui |
| Keycloak (externe, non fourni dans ce dépôt) | Authentification/rôles (OIDC/JWT) | 8081 | Oui |
| `banking-test-api/` | Serveur HTTP fictif, cible de démonstration pour créer des scénarios de test | 8888 | **Non — démonstration/test uniquement** |

## 3. Prérequis

- **JDK 21** pour le backend (`java -version` doit afficher 21.x).
- **Node.js 22.5+** pour le frontend (et pour `banking-test-api` si utilisé, qui dépend de `node:sqlite`).
- **PostgreSQL** — version **17.x** (PostgreSQL 17.5 a été validé en conditions réelles pour ce projet ; `docker-compose.yml` utilise l'image `postgres:17`).
- **Keycloak 26.7.3** — version réellement utilisée et validée pour ce projet.
- Accès réseau entre les composants (frontend, backend, Keycloak, PostgreSQL).
- Décision préalable sur l'exposition réseau (réseau interne/VPN vs Internet public — voir section 13, Sécurité) : **À VALIDER LORS DU DÉPLOIEMENT**.

## 4. Composants

| Composant | Technologie | Version | Obligatoire pour l'installation client |
|---|---|---|---|
| Backend | Spring Boot | 3.3.4 (Java 21) | Oui |
| Frontend | React + Vite + TypeScript | React 18.3.1 / Vite 5.4.0 / TypeScript 5.5.3 | Oui |
| PostgreSQL | — | 17.x (17.5 validé) | Oui |
| Keycloak | — | 26.7.3 | Oui |
| `banking-test-api` | Node.js / Express | — | Non (démonstration uniquement) |

`archive/legacy-json-server/` : archive historique, **non utilisée par le runtime, le build ou les tests actuels** — n'est pas un composant à installer.

## 5. PostgreSQL

Le schéma est **entièrement géré par Liquibase** (`backend/src/main/resources/db/changelog/`), appliqué automatiquement au démarrage du backend. Hibernate est configuré en `ddl-auto: validate` : il ne crée ni ne modifie jamais de table, il vérifie seulement que les entités correspondent au schéma déjà migré. **Ne jamais créer de table manuellement.**

- Base/rôle attendus par convention : `loadpilot` / `loadpilot` (personnalisable via les variables d'environnement ci-dessous).
- URL JDBC attendue : `jdbc:postgresql://<hôte>:5432/<base>`.
- Aucune extension PostgreSQL particulière requise (installation standard suffisante).
- Procédure d'installation détaillée : voir `postgresql/README.md` de ce dépôt.

## 6. Keycloak

- Réalm réel exporté dans ce dépôt : `keycloak/realm-export.json` (généré depuis une instance Keycloak 26.7.3 réellement démarrée).
- Rôles réels : `ROLE_SUPER_ADMIN`, `ROLE_PERFORMANCE_ENGINEER`, `ROLE_VIEWER`.
- Client `loadpilot-frontend` : public, PKCE S256 activé.
- Client `loadpilot-backend-admin` : confidentiel, compte de service dédié à l'administration des utilisateurs (`/api/users`).
- **Action obligatoire lors du déploiement client** : le champ `redirectUris`/`webOrigins` du client `loadpilot-frontend` est actuellement fixé à `http://localhost:3000/*` dans le fichier versionné — **à éditer manuellement dans la Console Admin Keycloak si le domaine final du client diffère de `localhost:3000`.**
- Protection anti-bruteforce activée dans `realm-export.json` : blocage temporaire après 10 échecs (1 min, puis +1 min par nouvelle série de 10 échecs, 15 min au plus, jamais définitif). Pendant le blocage, la page affiche le message générique « Nom d'utilisateur ou mot de passe incorrect » pour ne pas révéler l'existence du compte. À ajuster selon la politique du client. Un réalm importé avant ce changement garde l'ancien réglage : voir `keycloak/README.md`, section « Thème de connexion LoadPilot ».
- **Page de connexion LoadPilot (thème Keycloak)** :
  - **Thème** : `keycloak/themes/loadpilot`, un thème FreeMarker sans build qui hérite de `keycloak.v2` (Keycloak 26.x). Il couvre la connexion, le mot de passe oublié, le nouveau mot de passe, le profil, les pages d'erreur, d'information, de déconnexion et de page expirée.
  - **Réglages du réalm** : français par défaut, nom affiché « LoadPilot », « Rester connecté » et « Mot de passe oublié ? » activés. Le thème est appliqué au réalm et au client `loadpilot-frontend`.
  - **Chargement** : `docker-compose.yml` monte le dossier dans le conteneur, donc le thème est chargé au démarrage de Keycloak.
  - **Redéploiement** : voir `keycloak/README.md`, section « Déployer ou redéployer le thème ». En `start-dev`, il suffit de recharger la page ; en production (`start`), il faut redémarrer Keycloak.
  - **E-mails (mot de passe oublié)** :
    - en développement et en démo, `docker-compose.yml` démarre **Mailpit**, qui capture les e-mails ; on les lit sur http://localhost:8025 ;
    - en production, il suffit de renseigner les variables `KC_SMTP_HOST`, `KC_SMTP_PORT`, `KC_SMTP_FROM`, `KC_SMTP_AUTH`, `KC_SMTP_USER`, `KC_SMTP_PASSWORD`… (voir `.env.example`) ;
    - détail dans `keycloak/README.md`, section « E-mails ».
- `sslRequired` vaut `"none"` dans le fichier versionné — **À VALIDER LORS DU DÉPLOIEMENT** selon le contexte réseau (voir section 13).
- Procédure réelle d'installation et d'import du réalm (sans Docker) : voir `keycloak/README.md` de ce dépôt.
- Aucun utilisateur métier n'est fourni dans le réalm importé : les comptes doivent être créés manuellement (Console Admin → Users → Add user).

## 7. Backend

- Build : Maven (`backend/pom.xml`), Java 21, Spring Boot 3.3.4.
- Commande de démarrage (hors Docker) : `mvn spring-boot:run`, exécutée depuis `backend/`.
- Port : **8080**.
- Migrations Liquibase : appliquées automatiquement au démarrage, aucune commande manuelle.
- Profil actif par défaut : `dev` (`spring.profiles.active: dev` dans `application.yml`). Un profil `application-prod.yml` existe (désactive Swagger/OpenAPI, exige toutes les valeurs sensibles sans valeur par défaut) — **son fonctionnement en conditions réelles n'a pas été validé dans cet environnement (aucune infrastructure de production disponible) : À VALIDER LORS DU DÉPLOIEMENT.**
- Documentation API (Swagger UI) : `/swagger-ui.html`, publique en profil `dev`, désactivée en profil `prod`.
- Health check : `GET /actuator/health` (seul endpoint Actuator exposé, sans détail).

## 8. Frontend

- Commandes réelles (`perftest-frontend/package.json`) :
  - Installation : `npm install`
  - Développement : `npm run dev` (→ `vite`, port **3000**)
  - Build de production : `npm run build` (→ `tsc && vite build`, sortie dans `perftest-frontend/dist`)
  - Aperçu du build : `npm run preview`
  - Tests : `npm test` (→ `vitest run`)
- Configuration : fichier `.env` dans `perftest-frontend/` (voir `.env.example`), variables listées section 9.

## 9. Variables d'environnement

Aucune valeur ci-dessous n'est une valeur réelle — uniquement les noms et rôles des variables telles qu'utilisées par le code.

**Backend :**

| Variable | Obligatoire | Valeur par défaut | Utilisation |
|---|---|---|---|
| `DB_URL` | Oui | `jdbc:postgresql://localhost:5432/loadpilot` (dev uniquement) | URL JDBC PostgreSQL |
| `DB_USERNAME` | Oui | `loadpilot` (dev uniquement) | Utilisateur PostgreSQL |
| `DB_PASSWORD` | Oui | Aucune | Mot de passe PostgreSQL — `DB_PASSWORD=<SECRET>` |
| `KEYCLOAK_ISSUER_URI` | Oui | `http://localhost:8081/realms/loadpilot` (dev uniquement) | URL du réalm, résolue au démarrage pour le `JwtDecoder` |
| `KEYCLOAK_ADMIN_SERVER_URL` | Oui (pour `/api/users`) | `http://localhost:8081` (dev uniquement) | URL serveur pour l'Admin API Keycloak |
| `KEYCLOAK_ADMIN_REALM` | Non | `loadpilot` | Réalm pour l'Admin API |
| `KEYCLOAK_ADMIN_CLIENT_ID` | Non | `loadpilot-backend-admin` | Client confidentiel dédié à l'administration |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | Oui | Aucune | Secret du client d'administration — `KEYCLOAK_ADMIN_CLIENT_SECRET=<SECRET>` |
| `FRONTEND_ORIGIN` | Oui en profil `prod` (défaut en `dev` uniquement) | `http://localhost:3000` (dev uniquement) | Origine CORS autorisée |
| `AVAILABILITY_TIMEOUT_SECONDS` | Non | 5 | Timeout du test de disponibilité d'une Application |
| `EXECUTION_TIMEOUT_SECONDS` | Non | 10 | Timeout HTTP par requête d'exécution |
| `LOADPILOT_MAX_VUS_PER_EXECUTION` | Non | 500 | Limite d'utilisateurs virtuels par exécution |
| `LOADPILOT_MAX_GLOBAL_VUS` | Non | 200 | Limite globale d'utilisateurs virtuels simultanés |
| `LOADPILOT_MAX_CONCURRENT_EXECUTIONS` | Non | 10 | Limite d'exécutions simultanées |
| `LOADPILOT_TARGET_RPS_ADVISED_ABOVE_VUS` | Non | 50 | Au-delà de ce nombre d'utilisateurs virtuels sans débit cible : avertissement, jamais un refus (0 = jamais) |
| `LOADPILOT_MAX_RESULTS_IN_MEMORY` | Non | calculée : mémoire de la JVM ÷ 2,5 Ko (~1,6 M avec 4 Go) | Garde-fou mémoire : résultats gardés en mémoire, toutes exécutions confondues (voir section 17) |
| `LOADPILOT_SCHEDULER_POLL_INTERVAL_MS` | Non | 5000 | Intervalle du poller de planification |

**Frontend** (`perftest-frontend/.env`) :

| Variable | Obligatoire | Utilisation |
|---|---|---|
| `VITE_KEYCLOAK_URL` | Oui | URL Keycloak résolue par le navigateur |
| `VITE_KEYCLOAK_REALM` | Oui | Nom du réalm (`loadpilot`) |
| `VITE_KEYCLOAK_CLIENT_ID` | Oui | Client public (`loadpilot-frontend`) |
| `VITE_SPRING_API_URL` | Oui | URL du backend résolue par le navigateur |

**Docker Compose** (`.env` à la racine, voir `.env.example`) : `DB_USERNAME`, `DB_PASSWORD`, `DB_NAME`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET` — ce dernier n'est connu qu'après le premier import du réalm (voir `keycloak/README.md`).

## 10. Démarrage

**Configuration Docker (présente dans le dépôt mais non validée en exécution réelle — voir section 12) :**
```
cp .env.example .env   # puis renseigner de vraies valeurs locales
docker compose up -d --build
```

**Procédure manuelle (validée) :**

1. PostgreSQL démarré et accessible (voir `postgresql/README.md`).
2. Keycloak démarré, réalm importé (voir `keycloak/README.md`).
3. Backend :
   ```bash
   cd backend
   export DB_URL=jdbc:postgresql://localhost:5432/loadpilot
   export DB_USERNAME=loadpilot
   export DB_PASSWORD=...
   export KEYCLOAK_ISSUER_URI=http://localhost:8081/realms/loadpilot
   export KEYCLOAK_ADMIN_CLIENT_SECRET=...
   mvn spring-boot:run
   ```
   Liquibase applique automatiquement les migrations. Démarre sur `http://localhost:8080`.
4. Frontend :
   ```bash
   cd perftest-frontend
   npm install
   npm run dev
   ```
   Configurer `perftest-frontend/.env` au préalable (voir section 9). Démarre sur `http://localhost:3000`.

Ordre de démarrage obligatoire : PostgreSQL et Keycloak doivent être joignables **avant** le backend (celui-ci résout le JWK set Keycloak et se connecte à PostgreSQL au démarrage — il refuse de démarrer sinon).

### Préparer LoadPilot pour une application cible inconnue

Les contraintes réseau de l'application testée (certificat, proxy, lenteur) se règlent **au démarrage du backend**, par des variables d'environnement, sans modifier le code. Toutes sont optionnelles. Absentes, le backend démarre comme avant, avec une seule différence : sa mémoire maximale est fixée à 4 Go.

**Lancement :**
- **En local (Windows)** : définir les variables dans le terminal, puis lancer le script.
  ```powershell
  cd backend
  $env:DB_PASSWORD = '...'   # + les autres variables habituelles (section 9)
  .\start.ps1                # ou .\start.ps1 -DryRun pour voir les options sans démarrer
  ```
  Le script remplace `mvn spring-boot:run` et accepte les mêmes arguments Maven (ex. `.\start.ps1 -o`). Si Windows bloque les scripts : `powershell -ExecutionPolicy Bypass -File .\start.ps1`.
- **Docker** : renseigner les variables dans `.env` (modèle : `.env.example`). L'image démarre par `backend/start.sh`. Pour des certificats, décommenter le volume `./certs` du service `backend` dans `docker-compose.yml` et indiquer les chemins vus dans le conteneur (`/opt/loadpilot/certs/...`).

Le script affiche les options retenues, mots de passe masqués. Il refuse de démarrer, avec un message clair, si un fichier de certificat est introuvable ou si une variable est incomplète (hôte du proxy sans port, certificat client sans mot de passe).

| Ce que montre l'application cible (ou ce que dit la banque) | Variable(s) à renseigner |
|---|---|
| Rien de particulier : certificat public, accès direct | Aucune |
| Test de validation en erreur « PKIX path building failed » / « unable to find valid certification path » : certificat signé par une autorité interne | `LOADPILOT_TLS_TRUSTSTORE_PATH` (+ `LOADPILOT_TLS_TRUSTSTORE_PASSWORD`) pour tout le serveur, ou l'autorité de l'application seule (bloc « Réseau », section 17) |
| Connexion coupée pendant la négociation TLS (« bad_certificate », « handshake failure ») : certificat client exigé (authentification mutuelle) | `LOADPILOT_TLS_KEYSTORE_PATH` + `LOADPILOT_TLS_KEYSTORE_PASSWORD` |
| L'application n'est joignable qu'à travers le proxy de l'entreprise (« délai de connexion dépassé » sans proxy) | `LOADPILOT_HTTPS_PROXY_HOST` + `LOADPILOT_HTTPS_PROXY_PORT`, ou un proxy propre à l'application (bloc « Réseau », section 17) |
| Le proxy répond « 407 Proxy Authentication Required » (le test de validation affiche une réponse 407) | En plus : `LOADPILOT_HTTPS_PROXY_USERNAME` + `LOADPILOT_HTTPS_PROXY_PASSWORD` |
| Beaucoup d'erreurs « Délai d'attente dépassé (10s) » alors que l'application répond, mais lentement | `EXECUTION_TIMEOUT_SECONDS` (ex. 30) |
| Test long ou à fort débit, backend qui ralentit ou s'arrête faute de mémoire | `LOADPILOT_JVM_MAX_MEMORY` (ex. 6g) |
| Authentification par jeton ou par cookie | Aucune variable : se règle dans l'application (Connexion auto, capture d'en-tête ; les cookies de session sont gérés automatiquement, voir section 17) |

**À savoir :**
- **Le magasin de confiance remplace celui de Java.** Les autorités publiques n'y sont plus, et les autres sites en HTTPS échoueraient. Partez d'une copie du magasin de Java, puis ajoutez-y l'autorité de la banque :
  ```powershell
  # Dossier du JDK utilise par LoadPilot (JAVA_HOME peut etre vide, et keytool absent du PATH)
  $javaHome = (java -XshowSettings:properties -version 2>&1 | Out-String) -replace '(?s).*java\.home = ([^\r\n]+).*', '$1'
  Copy-Item "$javaHome\lib\security\cacerts" .\truststore.p12
  & "$javaHome\bin\keytool.exe" -importcert -noprompt -alias banque-ca -file ca-banque.crt -keystore .\truststore.p12 -storepass changeit
  ```
  Le mot de passe d'origine du magasin de Java est `changeit`.
- **Le certificat client** est un fichier `.p12` (ou `.pfx`) contenant le certificat et sa clé privée, fourni par la banque. Il est présenté à tout serveur qui en demande un.
- **Le proxy** ne s'applique qu'aux adresses `https://` (le Keycloak local en `http://` n'est pas concerné). `localhost` n'y passe jamais.
- **Proxy avec identifiant** : **authentification Basic uniquement**. Un proxy en NTLM ou Kerberos, fréquent avec Active Directory, n'est pas pris en charge par le client HTTP de Java : demandez au service réseau un compte Basic, ou une exception pour l'IP de LoadPilot.
  - Le **mot de passe est lu par le backend dans l'environnement**, comme `DB_PASSWORD`. Il n'est jamais transmis à Java en option, jamais affiché, jamais journalisé.
  - L'identifiant est envoyé au proxy (`Proxy-Authorization`) **seulement pour les requêtes qui passent par lui**. Il n'est jamais transmis à l'application testée.
  - Le script ajoute `-Djdk.http.auth.tunneling.disabledSchemes=`, car Java interdit par défaut Basic pour les tunnels HTTPS. En Basic, l'identifiant circule en clair (encodé en Base64) jusqu'au proxy, ce qui est inhérent à ce mode d'authentification.
  - Un 401 renvoyé par l'application reste un 401 (le renouvellement du jeton de la Connexion automatique est inchangé).
  - L'identifiant et le mot de passe vont ensemble, avec l'hôte et le port du proxy. L'identifiant ne doit pas contenir `:`.
- **Fichiers sensibles** : les certificats (`*.p12`, `*.pfx`, `*.jks`, dossier `certs/`) sont ignorés par Git. Ne les placez jamais ailleurs dans le dépôt, et ne commitez jamais leurs mots de passe.
- **Ces réglages valent pour tout le backend** (tous les tests, et le test de connectivité des applications). Ils sont pris en compte au démarrage : redémarrez le backend après un changement.

## 11. Premier Super Admin

**MANUAL CLIENT SETUP REQUIRED — aucun mécanisme automatique n'existe.** Aucun utilisateur n'est fourni dans le réalm importé. Procédure : dans la Console Admin Keycloak, créer un compte (Users → Add user → Credentials → définir un mot de passe), puis lui assigner le rôle `ROLE_SUPER_ADMIN` (Role mapping). Ce compte pourra ensuite gérer les autres utilisateurs/rôles depuis l'écran "Users/Roles" de LoadPilot.

## 12. Vérification fonctionnelle

1. `GET http://localhost:8080/actuator/health` → `{"status":"UP"}`.
2. `GET http://localhost:8081/realms/loadpilot/.well-known/openid-configuration` → JSON valide (200).
3. Se connecter sur le frontend avec le compte Super Admin créé à l'étape précédente.
4. Créer une Application, un Scénario avec au moins une Étape, lancer une Exécution, consulter le Rapport.

**Docker** : `docker-compose.yml`, `backend/Dockerfile`, `perftest-frontend/Dockerfile` et `nginx.conf` sont présents et statiquement cohérents (services, ports, dépendances, variables). **Configuration Docker présente dans le repository mais non validée en exécution réelle** dans l'environnement de développement de ce projet (`docker`/`docker compose` n'y sont pas installés). À valider par le client avant de retenir cette voie d'installation.

## 13. Sécurité

| Élément | État |
|---|---|
| Authentification | OAuth2/OIDC Authorization Code + PKCE S256 contre Keycloak — implémenté. |
| Stockage du token | `sessionStorage` (jamais `localStorage`), effacé à la fermeture de l'onglet — implémenté. |
| RBAC | `@PreAuthorize` appliqué côté backend sur les endpoints d'écriture (jamais uniquement côté frontend) — implémenté. |
| CORS | Origine explicitement configurée via `FRONTEND_ORIGIN`, aucun wildcard — implémenté. |
| CSRF | Désactivé — cohérent avec une API REST stateless à authentification JWT, aucune session serveur. |
| Swagger/OpenAPI | Public en profil `dev`, désactivable via le profil `prod` — implémenté, action de configuration à la charge du déploiement. |
| Actuator | Seul `/health` exposé, sans détail interne — implémenté. |
| Brute-force Keycloak | Activé dans le fichier de réalm versionné (10 échecs → blocage temporaire de 1 min, 15 min au plus), message générique pendant le blocage — à vérifier sur un réalm importé avant ce changement (voir section 6). |
| Page de connexion | Thème Keycloak `loadpilot` : le mot de passe n'est saisi que sur Keycloak, jamais dans React (pas de « password grant »). |
| HTTPS/TLS | **NON FOURNI PAR L'APPLICATION.** Aucun reverse proxy TLS n'est inclus dans ce dépôt. À configurer selon l'infrastructure client si une exposition au-delà d'un réseau interne de confiance est envisagée. |
| Secrets | Toutes les valeurs sensibles proviennent de variables d'environnement, sans valeur par défaut dangereuse — implémenté. |

## 14. Backup / Restore

**Backup/restore PostgreSQL : à définir selon la politique d'infrastructure du client.** Aucun script ni automatisation n'est fourni par LoadPilot dans ce dépôt. PostgreSQL reste sauvegardable par les outils standards (`pg_dump`/`pg_restore`) sans changement de code. Aucune procédure de sauvegarde de la configuration Keycloak n'est fournie non plus — à prévoir séparément si des comptes/paramètres sont modifiés après l'import initial du réalm.

## 15. Mise à jour

**Procédure officielle de mise à jour/rollback : NON DOCUMENTÉE À CE STADE.** Les migrations Liquibase sont séquentielles et jamais modifiées rétroactivement (nouvelle version = nouveaux changesets additifs), mais aucune procédure de déploiement d'une nouvelle version applicative (arrêt, sauvegarde préalable, bascule) n'est formalisée dans ce dépôt.

## 16. Troubleshooting

| Symptôme | Cause probable | Action |
|---|---|---|
| Le backend ne démarre pas / erreur `.well-known/openid-configuration` | Keycloak n'est pas joignable à l'URL configurée (`KEYCLOAK_ISSUER_URI`) | Vérifier que Keycloak est démarré et accessible avant le backend |
| 502 sur `/api/users` | `KEYCLOAK_ADMIN_CLIENT_SECRET` manquant/incorrect | Vérifier le secret du compte de service dans la Console Admin Keycloak |
| `FATAL: password authentication failed` (PostgreSQL) | `DB_PASSWORD` incorrect ou non défini | Redéfinir la variable d'environnement |
| `FATAL: database "loadpilot" does not exist` | La base n'a pas été créée | Voir `postgresql/README.md`, étape de création du rôle/base |
| "Port already in use" | Un service tourne déjà sur ce port | Arrêter le service en conflit avant de relancer |

## 17. Limitations

- **Installation mono-instance, pas de multi-tenance** : LoadPilot ne comporte aucun concept d'organisation/tenant. Applications, Scénarios et Exécutions sont visibles par tout utilisateur authentifié de l'installation, quel que soit son rôle (lecture). **Une installation LoadPilot doit être dédiée à une seule société cliente.**
- **Registre de capacité en mémoire, mono-instance** : les limites de charge (utilisateurs virtuels, exécutions concurrentes) sont appliquées par un registre en mémoire propre à un seul processus backend — pas de coordination multi-instance.
- **Moteur JSONPath simplifié** : la capture de variable dynamique et le chemin du jeton de la Connexion automatique supportent un sous-ensemble à notation par points (`token`, `$.token`, `data.token`), avec des index de tableau entre crochets à n'importe quel niveau (`data.accounts[0].id`, `items[2].name`, `data[0][1].value`, `$[0].id` si la réponse est un tableau). Pas de filtres, de wildcards (`[*]`) ni d'index négatifs. Une valeur introuvable est signalée avec sa raison (champ absent, index hors limites, tableau attendu…).
- **Capture depuis un en-tête de réponse** : une capture d'étape, comme le jeton de la Connexion automatique, peut lire sa valeur dans le corps JSON (par défaut) ou dans un en-tête de la réponse, choix « Corps JSON / En-tête de réponse ».
  - Le nom de l'en-tête est recherché **sans tenir compte de la casse** (`x-csrf-token` trouve `X-CSRF-Token`).
  - Si l'en-tête a plusieurs valeurs, **la première** est retenue.
  - Pour `Set-Cookie`, on indique le nom du cookie (stocké `Set-Cookie:SESSIONID`) : sa valeur est cherchée dans tous les en-têtes `Set-Cookie`, et le nom du cookie respecte la casse.
  - Un en-tête absent est signalé clairement, par exemple « en-tête « X-CSRF-Token » absent de la réponse ».
  - Les valeurs lues dans `Set-Cookie`, `Cookie` ou `Authorization` sont masquées dans les résultats.
  - Inutile pour une simple session : les cookies sont renvoyés automatiquement (point suivant). La capture sert quand on veut la valeur dans une variable visible, par exemple pour l'ajouter à une URL ou à un corps.
- **Capture depuis une page HTML** (choix « Page HTML » de l'éditeur d'étape), pour les applications web à formulaires : un **sélecteur CSS** désigne l'élément et un **attribut** indique quoi lire.
  - Exemples : `input[name=csrf_token]` + `value` pour le jeton caché d'un formulaire, `#compte` + `data-account-id` pour un attribut `data-*`.
  - Attribut laissé vide : c'est le texte de l'élément qui est lu.
  - Stocké sous la forme `input[name=csrf_token]::attr(value)`.
  - **Plusieurs éléments correspondent** : le premier de la page est retenu, et le test de validation le signale (« 3 éléments correspondent… »).
  - Un HTML mal formé est corrigé comme le ferait un navigateur.
  - Messages clairs pour un élément absent, un attribut absent ou vide, un élément sans texte ou un sélecteur invalide. La syntaxe du sélecteur est vérifiée à l'enregistrement de l'étape.
  - Une valeur dont le sélecteur ou le nom de l'élément évoque un jeton, une session, un mot de passe ou une protection CSRF (`csrf`, `xsrf`, `token`…) est masquée dans les résultats.
  - Analyse limitée aux 5 premiers Mo de la page. Le coût processeur ne concerne que les étapes en « Page HTML », à surveiller sur un test à fort débit avec de grosses pages.
  - Pas disponible pour la Connexion automatique, qui envoie ses identifiants en JSON : pour un formulaire de connexion, faites de la connexion une étape du scénario, avec un corps `application/x-www-form-urlencoded` et le jeton capturé.
- **Cookies de session gérés automatiquement**, sans aucune configuration : chaque utilisateur virtuel garde les cookies que l'application lui envoie (`Set-Cookie`) et les renvoie dans les requêtes suivantes, comme un navigateur.
  - **Un pot de cookies par utilisateur virtuel**, jamais partagé entre eux, utilisé aussi par le test de validation de l'assistant.
  - Domaine, chemin, expiration et attribut `Secure` respectés : un cookie `Secure` n'est jamais envoyé en `http://`, ni un cookie à un autre site.
  - **Durée de vie : une itération.** Comme les captures de variables, les cookies sont oubliés au début de chaque itération : chaque passage du scénario repart d'une session vierge et doit donc refaire sa connexion.
  - Un en-tête `Cookie` écrit dans une étape est conservé et les cookies automatiques s'y ajoutent. Pour un même nom de cookie, la valeur écrite dans l'étape l'emporte.
  - La Connexion automatique de l'application (un jeton partagé par tous les utilisateurs virtuels) ne transmet pas ses propres cookies aux utilisateurs virtuels. Pour une session par cookie propre à chaque utilisateur virtuel, faites de la connexion une étape du scénario.
  - Les cookies (`Cookie`, `Set-Cookie`) restent masqués dans le détail des requêtes et dans le test de validation.
- **Frein automatique sur taux d'erreurs** (réglage du scénario, étape « Charge & résumé », **désactivé par défaut**) : il protège l'application testée pendant le test.
  - Exemple : 20 % sur les 30 dernières secondes. Fenêtre de 5 à 300 s (30 s par défaut).
  - Vérifié toutes les demi-secondes, à partir de 20 requêtes dans la fenêtre : quelques erreurs au démarrage ne suffisent pas. Le temps passé en pause n'est pas jugé.
  - Seuil dépassé : le test est arrêté **comme une annulation manuelle**. Statut « Annulée », raison « Arrêt automatique : trop d'erreurs » dans la liste, message détaillé (taux, fenêtre, nombre de requêtes) sur l'exécution. Une ligne « Frein sur taux d'erreurs » est ajoutée au journal d'audit.
  - Ce n'est pas un seuil de réussite. Les seuils de réussite (« Seuils dépassés ») sont jugés à la fin, sur toute l'exécution. Le frein, lui, arrête le test en cours de route.
  - Annulation manuelle et frein en même temps : **la première demande l'emporte**, il n'y a qu'un seul statut final. Le réglage est figé au lancement : le modifier ne change pas une exécution en cours.
  - Coût : un compteur d'échecs ajouté au suivi en direct. Le temps de réponse des requêtes n'est pas affecté.
  - Les 429 respectés (point suivant) ne sont pas jugés par le frein.
- **Ralentissement sur 429 Too Many Requests** (réglage du scénario, étape « Charge & résumé », **activé par défaut**) : quand la cible demande d'attendre, LoadPilot attend au lieu de continuer au même débit.
  - Seul l'utilisateur virtuel qui a reçu le 429 attend, puis **rejoue la même étape**, avec sa session et ses captures. Les autres utilisateurs virtuels continuent normalement.
  - Délai : celui de l'en-tête `Retry-After`, en secondes (`Retry-After: 120`) ou en date HTTP (`Retry-After: Wed, 21 Oct 2026 07:28:00 GMT`). Sans en-tête, ou s'il est illisible : attente par défaut de **5 s**, réglable de 1 à 300 s.
  - Garde-fous :
    - attente plafonnée à 300 s, même si la cible demande davantage ;
    - jamais au-delà de la durée prévue du test ;
    - le temps de pause n'est pas décompté ;
    - une annulation interrompt l'attente immédiatement.
  - **3 rejeux** par étape au plus (de 0 à 10). Au-delà, l'étape échoue comme n'importe quelle erreur et l'itération s'arrête.
  - **Comment un 429 est compté :**
    - dans le résultat final, le rapport et les seuils de réussite, c'est un **échec** : la requête n'a pas été servie, et chaque envoi est compté ;
    - dans le frein automatique, il est **exclu**, ni comme erreur ni dans le total. LoadPilot ralentit déjà, le frein ne juge que les vraies erreurs (5xx, délais dépassés, connexions refusées) ;
    - conséquence : une cible qui ne renvoie que des 429 ne déclenche pas le frein. Le test va au bout, en « Seuils dépassés ».
  - **Désactivé** (test de résilience, pour voir comment la cible tient sous un mur de 429) : un 429 est un échec ordinaire. Pas d'attente ni de rejeu, l'itération s'arrête, et le frein le compte.
  - Pourquoi activé par défaut : insister malgré un 429 peut faire bloquer l'adresse de LoadPilot par le pare-feu applicatif de la cible. Le frein reste désactivé par défaut, car il arrête tout le test.
  - Le test de validation de l'assistant n'est pas concerné : il envoie chaque étape une seule fois et montre le 429 tel quel.
- **Variation aléatoire du think time** (réglage du scénario, étape « Charge & résumé », **désactivée par défaut**) : chaque pause est tirée uniformément autour du think time configuré. Par exemple, avec ± 20 % et 1 000 ms, chaque pause dure entre 800 et 1 200 ms.
  - Les utilisateurs virtuels ne se synchronisent plus sur le même rythme. La moyenne, donc la charge moyenne, ne change pas.
  - S'applique au think time du scénario et à celui de chaque étape.
  - Vide ou 0 : think time fixe, exactement comme avant. Un scénario existant n'est pas modifié.
- **Contrôles supplémentaires d'une étape** (onglet « Validation » de l'étape, **optionnels**) : ils s'ajoutent au code HTTP attendu et à « La réponse doit contenir », sans les remplacer.
  - **Temps de réponse maximal (ms)** : l'étape échoue si la réponse arrive plus tard. Message : « Temps de réponse trop long : 812 ms (maximum autorisé 500 ms) ».
  - **Assertion JSON** : « le champ X égale / contient Y ». Le champ s'écrit comme pour une capture (`$.status`, `data.items[0].id`).
    - « égale » compare le texte exact (sensible à la casse) ; un tableau ou un objet se compare en JSON.
    - « contient » cherche le texte dans la valeur du champ, ou dans le JSON d'un tableau.
    - Un champ absent, une valeur nulle ou une réponse non JSON font échouer l'étape, avec la raison.
  - Appliqués à l'identique par le test de charge et par « Tester » ; « Tester » affiche chaque contrôle dans l'onglet « Vérifications ».
  - Vides : aucun contrôle, comme avant. Une étape existante n'est pas modifiée.
- **Surveillance de l'injecteur** (LoadPilot lui-même) : pendant chaque exécution, le suivi en direct affiche les ressources de LoadPilot.
  - Mesures : son CPU (en % de la machine), celui de la machine, sa mémoire et ses threads virtuels actifs (un par utilisateur virtuel).
  - Fréquence : la même que la lecture CPU / RAM de la cible, ou 5 s par défaut.
  - Au-delà de 85 % de CPU (`LOADPILOT_INJECTOR_CPU_WARNING_PERCENT`), un avertissement **« Injecteur saturé »** reste affiché jusqu'à la fin du test : les temps de réponse mesurés peuvent alors être faussés.
  - Ces mesures sont aussi dans `GET /api/executions/{id}/status` (`injector`). Elles ne sont pas conservées après le test.
- **Débit cible conseillé** : au-delà de 50 utilisateurs virtuels (`LOADPILOT_TARGET_RPS_ADVISED_ABOVE_VUS`), un scénario sans débit cible reçoit un avertissement.
  - Il s'affiche dans l'étape « Charge & résumé » et dans la réponse de l'API (`loadWarnings`).
  - Sans débit cible, le débit n'est pas borné : la charge envoyée dépend de la vitesse de la cible, et la mémoire de LoadPilot peut atteindre sa limite de sécurité.
  - **Ce n'est jamais un refus** : un scénario ou une planification existants continuent de fonctionner.
- **Écriture des résultats pendant le test** : les résultats partent en base **au fil de l'exécution**, et non plus seulement à la fin.
  - Fonctionnement : un thread dédié par exécution écrit par lots de 100 résultats, ou ce qui est en attente après une seconde ; chaque lot a sa propre transaction courte.
  - Les utilisateurs virtuels ne font que déposer leurs résultats dans un tampon : aucun accès à la base ni aucune attente dans la boucle de charge.
  - **Plantage du processus** : seul le dernier lot pas encore écrit est perdu, environ une seconde de test. Au redémarrage, l'exécution passe à « Échouée » avec le motif « Backend restarted while execution was running (N résultat(s) enregistré(s) avant l'interruption, conservé(s)) ». Ses résultats partiels sont **conservés**, comptés et consultables comme ceux de toute exécution.
  - **Erreur inattendue pendant le test** : les résultats déjà obtenus sont écrits avant que l'exécution passe à « Échouée », et le message indique leur nombre.
  - **Base momentanément indisponible** : le lot est retenté. À la fin du test, chaque lot restant est retenté 3 fois, puis abandonné, avec une erreur au journal : la fin de l'exécution n'est jamais bloquée.
  - Statut final (taux d'erreurs, P95 exact) et compteurs : calculés sur toutes les requêtes, sans les garder en mémoire (4 octets par requête). Les compteurs sont relus en base.
- **Garde-fou mémoire** (réglage du serveur, toujours actif) : il ne compte plus que les résultats **encore en mémoire**, c'est-à-dire le tampon d'écriture et les totaux compacts. Les résultats déjà écrits en base ne comptent plus.
  - Un test dont l'écriture suit n'est donc plus limité par sa taille. Le garde-fou arrête un test dont le tampon grossit sans se vider, par exemple quand la base est trop lente ou indisponible.
  - Après le test, le résumé du Dashboard charge toutes les requêtes : il passe par le garde-fou des lectures (ci-dessous), comme le rapport.
- **Métriques calculées sur les résultats, sans copie** : la table `metric`, qui recopiait chaque requête, n'est plus ni écrite ni lue. Elle doublait le volume en base pendant 90 jours.
  - Les moyennes du Dashboard (temps de réponse, débit, taux d'erreurs) et `GET /api/metrics` sont calculées directement sur `execution_step_result`, par agrégats SQL.
  - Les valeurs sont **identiques** à l'ancien calcul : mêmes formules, mêmes arrondis par exécution, et exécutions en cours exclues. Un test compare l'ancien calcul et le nouveau sur un même jeu de données.
  - `GET /api/metrics` garde son contrat. L'identifiant d'une ligne est désormais celui du résultat.
  - Rapidité : un index sur la date des résultats est ajouté pour les plages de dates. Sans plage, un seul parcours sans regroupement suffit. Mesure sur 200 000 requêtes : aussi rapide ou plus rapide que l'ancienne lecture.
  - **La table `metric` est conservée** : la supprimer effacerait des données existantes. Ses lignes partent avec la purge habituelle à 90 jours ou avec la suppression de leur exécution. Sa suppression fera l'objet d'une migration séparée, avec accord, une fois la table vide.
  - **Limite globale**, toutes exécutions confondues, car elles partagent la même mémoire.
  - **Valeur par défaut calculée sur la mémoire de la JVM**, soit `LOADPILOT_JVM_MAX_MEMORY` ÷ 2,5 Ko : ~1,6 M de résultats avec 4 Go, ~3,3 M avec 8 Go. Le pic n'a pas lieu pendant le test (~0,25 Ko par résultat), mais juste après : finalisation, résumé et rapport, ~1,3 Ko par résultat, plus une marge. `LOADPILOT_MAX_RESULTS_IN_MEMORY` remplace cette valeur par un nombre fixe. La valeur retenue est écrite dans le journal au démarrage.
  - **Limite atteinte** : l'exécution en cours qui tient le plus de résultats est arrêtée **comme une annulation manuelle**.
    - statut « Annulée », raison « Arrêt automatique : limite mémoire » dans la liste ;
    - message détaillé sur l'exécution, et une ligne « Limite de résultats en mémoire » au journal d'audit ;
    - si la limite est encore dépassée une demi-seconde plus tard, l'exécution suivante est arrêtée à son tour.
  - **Les résultats déjà obtenus sont conservés** et enregistrés normalement.
  - Coût : aucun sur les requêtes. Le tampon de chaque exécution est mesuré toutes les demi-secondes par un thread dédié.
- **Garde-fou des lectures** (même budget que le garde-fou mémoire, toujours actif) : certaines lectures chargent **toutes** les requêtes d'une exécution. Avant de charger, LoadPilot compte ces requêtes (un simple `COUNT`) et réserve la mémoire correspondante dans le budget global, pour la durée de l'appel. Les tests en cours et les lectures partagent ainsi le même plafond.
  - **Lectures concernées et limite**, pour la valeur par défaut avec 4 Go (budget ~1,6 M), selon le poids de chaque lecture :

    | Lecture | Poids | Requêtes au plus |
    |---|---|---|
    | Rapport | 0,6 | ~2,8 M |
    | Comparaison (somme des exécutions comparées) | 0,6 | ~2,8 M |
    | Analyse dans le temps | 0,3 | ~5,6 M |
    | Exports CSV, ZIP, Excel et PDF | 1 | ~1,7 M |
    | Métriques (`GET /api/metrics`) | 1 | ~1,7 M |
    | Détail complet (`GET /api/executions/{id}?includeResults=true`, jamais utilisé par l'interface) | 1,2 | ~1,4 M |

  - **Refus, avec un message clair** donnant le nombre de requêtes et la limite :
    - **413** si l'exécution est trop volumineuse pour la mémoire de ce serveur, même seule. Il faut alors augmenter `LOADPILOT_JVM_MAX_MEMORY` ;
    - **409** si la lecture ne tient pas pour l'instant dans la mémoire restante (tests en cours, autres rapports ouverts). Il suffit de réessayer.
  - Un export compte une seule fois, même s'il calcule le rapport et l'analyse dans le temps.
  - Une exécution purgée, dont le rapport est figé, ne recharge rien et n'est jamais refusée.
  - Les limites ci-dessus sont celles d'une lecture seule : un test en cours ou une autre lecture réduit la place disponible.
  - Dans l'autre sens, une lecture en cours compte pour le garde-fou mémoire : un test peut être arrêté un peu plus tôt pendant l'ouverture d'un gros rapport.
  - **Page de détail d'une exécution** : le débit et le taux d'erreurs sont lus sur l'exécution elle-même. Auparavant, la page rechargeait **toutes** les métriques (une par requête) pour en afficher une seule.
  - **Manque de mémoire malgré tout**, en dernier recours :
    - une requête HTTP reçoit une réponse 503 claire, au lieu de l'erreur générique, et l'incident est journalisé en erreur ;
    - pour les traitements en arrière-plan (métriques, résumé, rapport figé, purge de 3 h 45, rattrapage au démarrage), l'erreur est journalisée au lieu de disparaître sans trace.
    - Pendant ce temps, d'autres traitements ont pu échouer : c'est un dernier recours, le garde-fou ci-dessus doit refuser avant.
  - **Rattrapage des rapports figés au démarrage** : une exécution trop volumineuse pour son rapport est sautée, avec un journal et une ligne d'audit « Rapport figé ». Sans cela, le chargement serait retenté à chaque redémarrage.
    - La purge des 90 jours ne supprime jamais les résultats d'une exécution sans rapport figé : les résultats d'une telle exécution sont donc conservés.
  - **Pour que le rapport d'un gros test existe**, restez sous ces limites : par exemple moins de ~1,7 M de requêtes pour pouvoir l'exporter avec 4 Go. Sinon, augmentez `LOADPILOT_JVM_MAX_MEMORY`.
- **Une exécution annulée garde ses résultats déjà obtenus**, quelle que soit la cause de l'arrêt (annulation manuelle, frein, pause trop longue, limite mémoire) : métriques, rapport et compteurs portent sur la partie jouée.
  - Les requêtes coupées par l'arrêt, donc sans réponse, ne sont pas comptées comme des échecs.
  - Auparavant, une exécution annulée n'enregistrait aucun résultat.
- **Proxy par application** (formulaire de l'application, bloc « Réseau ») : chaque application testée peut avoir son propre proxy, sans redémarrer le backend.
  - **Proxy du serveur** (par défaut, et pour toutes les applications existantes) : comportement d'avant, avec les variables `LOADPILOT_HTTPS_PROXY_*` (section 10).
  - **Aucun proxy** : connexion directe, même si un proxy est configuré sur le serveur (application interne, par exemple).
  - **Proxy de l'application** : hôte, port (de 1 à 65535) et identifiant Basic optionnel, avec les mêmes règles que les scripts de démarrage.
    - Le **mot de passe est chiffré en base** (AES-GCM, comme les autres secrets, voir `docs/SECRETS.md`).
    - Il n'est jamais renvoyé par l'API ni écrit au journal d'audit. Laissé vide en modification, il est conservé.
    - Il n'est envoyé qu'au proxy, jamais à l'application.
  - **Où il s'applique** : les exécutions (chaque utilisateur virtuel), la Connexion automatique et son renouvellement, le test de validation de l'assistant, le bouton « Tester » et la lecture CPU / RAM.
    - Le réglage est figé au lancement : le modifier ne change pas une exécution en cours.
  - **« Tester » et « Tester le monitoring » vérifient d'abord le proxy lui-même** : connexion directe sur son hôte et son port, avec un délai de 3 s. En cas d'échec, le message désigne le proxy, par exemple « Proxy injoignable (proxy.banque.local:8080) : connexion refusée par le proxy (port fermé). ». Les autres raisons sont « hôte du proxy introuvable » et « le proxy ne répond pas ».
  - **« Monitoring sans proxy »** (bloc Monitoring) : la lecture CPU / RAM se fait en direct, sans proxy. Utile car l'URL de monitoring est souvent interne. Par défaut, le monitoring passe par le même proxy que l'application.
  - **Comportement à connaître** : avec un proxy propre à l'application, toutes les adresses de l'application passent par lui, `localhost` compris. Le proxy du serveur, lui, exclut `localhost`.
  - **Identification Basic dans les tunnels HTTPS** : le backend l'autorise désormais toujours au démarrage (`jdk.http.auth.tunneling.disabledSchemes`), quel que soit le lancement (scripts, `mvn spring-boot:run`, Docker), et même sans proxy global. Une valeur passée explicitement avec `-D` est respectée.
  - **Autorité de certification TLS de l'application** (bloc « Réseau », optionnel) : pour une application en HTTPS dont le certificat est émis par une autorité interne, inconnue de Java (erreur « PKIX path building failed »).
    - On colle un ou plusieurs certificats au format PEM (`-----BEGIN CERTIFICATE-----`), ou on charge un fichier `.pem` / `.crt`. Le formulaire affiche ensuite le sujet et la date d'expiration de chaque certificat enregistré.
    - Ces autorités **s'ajoutent** à la confiance habituelle de Java (magasin global, ou `LOADPILOT_TLS_TRUSTSTORE_PATH`), **pour cette application seulement**. Les autres applications et le reste du serveur ne les voient pas.
    - La vérification reste complète : chaîne, dates et nom d'hôte. Rien n'est « accepté sans vérifier ».
    - S'applique au test de charge, à « Tester », à la Connexion automatique, au test de connexion et à la lecture CPU / RAM, y compris avec « Monitoring sans proxy ».
    - Vide (par défaut, et pour les applications existantes) : comportement d'avant.
    - Un texte contenant une clé privée est refusé. Seuls des certificats publics sont enregistrés, donc ils ne sont pas chiffrés en base ; le journal d'audit n'en garde que les sujets.
    - Le certificat client reste réglé pour tout le serveur (`LOADPILOT_TLS_KEYSTORE_*`, section 10) et il est toujours présenté, y compris aux applications qui ont leur propre autorité. Un certificat client par application n'est pas encore disponible.
    - Une autorité inconnue donne désormais le message « certificat du serveur non reconnu (autorité de certification inconnue) : ajoutez-la dans l'onglet Réseau de l'application ».
- **Clients HTTP fermés immédiatement** : chaque client HTTP de Java démarre un thread système et garde ses descripteurs de fichiers et ses connexions. Sans fermeture explicite, ils restent ouverts jusqu'au passage du GC.
  - Où c'est appliqué :
    - chaque utilisateur virtuel ferme ses clients dès qu'il s'arrête : fin normale, erreur ou annulation ;
    - la Connexion automatique, le test de connectivité et le test de validation ferment le leur dès l'appel terminé.
  - Le client qui ne suit pas les redirections n'est créé que si une étape le demande (« Suivre les redirections » décoché).
  - Ordre de grandeur :
    - pendant le test : 1 thread système et ~5 descripteurs sous Linux par utilisateur virtuel (2 threads et ~10 descripteurs si une étape ne suit pas les redirections), au lieu de 2 et ~10 systématiquement auparavant ;
    - après le test : plus rien n'est retenu.
  - Utile sur un serveur Linux avec une limite de descripteurs basse (`ulimit -n`, souvent 1024 hors Docker) : prévoir au moins 4 096 pour 200 utilisateurs virtuels avec marge.
- **Pacing approximatif** : le débit cible (`targetRps`) est un mécanisme de répartition simple par créneaux, partagé entre tous les utilisateurs virtuels d'une exécution, pas un contrôleur de débit avancé.
- **HTTPS non fourni** (voir section 13).
- **Docker non validé en exécution réelle** dans l'environnement de développement de ce projet (voir section 12).
- **Backup/restore non fourni** (voir section 14).
- **`archive/legacy-json-server/`** : contient des données de démonstration historiques (1 application, 16 scénarios, 33 exécutions, 4 comptes), non migrées vers PostgreSQL (propriétaires non attribuables, incohérences d'origine) et non utilisées par le runtime actuel — conservées uniquement à titre d'archive.

## 18. Support / informations à fournir par le client

Avant l'installation, le client doit fournir ou décider :

- L'infrastructure d'hébergement (serveur(s)/VM, réseau interne ou VPN) et si une exposition Internet publique est envisagée (impacte directement la nécessité de HTTPS, voir section 13).
- Les identifiants PostgreSQL (base et rôle dédiés).
- L'hébergement de Keycloak et le domaine final du frontend (nécessaire pour éditer `redirectUris`/`webOrigins`, voir section 6).
- Sa politique de sauvegarde/restauration (aucune n'est fournie par LoadPilot, voir section 14).
- La ou les personnes désignées pour recevoir le rôle `ROLE_SUPER_ADMIN` initial (voir section 11).
