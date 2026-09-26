# LoadPilot (Cadence) — Documentation de déploiement client

LoadPilot est une plateforme de test de charge (performance testing) : on y déclare des **Applications** cibles, on y crée des **Scénarios** (suites d'étapes HTTP), on les **exécute** réellement (plusieurs utilisateurs virtuels, vraies requêtes HTTP, threads virtuels Java 21) et on en consulte les **résultats** (historique, rapports avec percentiles/écart-type/débit, métriques, audit).

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
| `banking-test-api/` | Serveur HTTP fictif, cible de démonstration pour créer des scénarios de test | 8000 | **Non — démonstration/test uniquement** |

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
- **Action obligatoire lors du déploiement client** : `bruteForceProtected` vaut `false` dans le fichier `realm-export.json` versionné (protection anti-bruteforce **non activée par défaut à l'import**) — à activer manuellement (Console Admin → Realm settings → Security defenses → Brute force detection, ou via l'API Admin) avant toute mise en service.
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
| Brute-force Keycloak | **NON ACTIVÉ PAR DÉFAUT** dans le fichier de réalm versionné — action obligatoire lors du déploiement (voir section 6). |
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
- **Moteur JSONPath simplifié** : la capture de variable dynamique supporte uniquement un sous-ensemble à notation par points (`token`, `$.token`, `data.token`), sans tableaux, filtres ni wildcards.
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
