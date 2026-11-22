# banking-test-api

Serveur HTTP de démonstration (Node.js / Express) servant de **cible de test** pour LoadPilot. Il n'est pas nécessaire en production.

```powershell
cd banking-test-api
npm install   # une seule fois
npm start     # http://localhost:8888
```

Le port peut être changé avec la variable d'environnement `PORT`.

### Monitoring CPU / RAM

`GET /metrics` expose les métriques Prometheus du processus Node (bibliothèque `prom-client`). Dans LoadPilot, l'application
« Banking » est configurée avec **Monitoring : Prometheus**, URL `http://localhost:8888/metrics` : pendant une exécution,
LoadPilot y lit le CPU et la RAM **de ce serveur** (pas de la machine entière) toutes les 5 secondes.
Après une mise à jour, relancez `npm install` puis `npm start`.

La latence est simulée : 100 à 300 ms, et 400 à 900 ms pour le virement international (contrôles de conformité et réseau interbancaire simulés, voir `latencyConfig.routes`). Le paramètre `?latency=<ms>` la remplace sur n'importe quelle route. En plus, 7 % d'erreurs 409/500 aléatoires sont injectées sur les requêtes d'écriture valides (voir `src/config.js`) : quelques échecs pendant un test de charge sont donc normaux.

### Régler les erreurs aléatoires

| Variable | Rôle | Défaut |
|---|---|---|
| `ERROR_RATE` | Taux total d'erreurs aléatoires, réparti 5/7 en 409 et 2/7 en 500 | `0.07` |
| `CONFLICT_RATE` | Taux de 409 seul (prioritaire) | 5 % |
| `SERVER_ERROR_RATE` | Taux de 500 seul (prioritaire) | 2 % |

Une valeur s'écrit `0.07`, `7` ou `7%`. Le taux appliqué est affiché au démarrage.

```powershell
# Démo sans aucune erreur aléatoire
$env:ERROR_RATE = '0'; npm start

# Démo avec plus d'erreurs (20 %)
$env:ERROR_RATE = '20'; npm start

# Revenir au comportement par défaut
Remove-Item Env:ERROR_RATE; npm start
```

Les erreurs 400 (validation) et 401 (authentification) ne sont pas concernées : elles restent déterministes.

### Capacité simulée (démo du mode stress)

Sans réglage, cette API tient bien plus que les plafonds de LoadPilot : un test en mode « Point de rupture » se termine alors par « Aucune rupture ». Pour montrer une rupture, fixez une capacité :

| Variable | Rôle | Défaut |
|---|---|---|
| `CAPACITY_RPS` | Requêtes par seconde tenues sans ralentir. Au-delà, chaque requête attend 200 ms de plus par tranche de 10 % de dépassement (+1 s à 150 %) ; au-delà du double, réponse `503` immédiate | non défini = désactivée |

`/health`, `/metrics` et les routes de remise à zéro ne sont jamais concernés. La capacité appliquée est affichée au démarrage.

```powershell
# Démo du point de rupture : capacité de 50 req/s, sans erreur aléatoire
$env:CAPACITY_RPS = '50'; $env:ERROR_RATE = '0'; npm start

# Revenir au comportement par défaut
Remove-Item Env:CAPACITY_RPS; Remove-Item Env:ERROR_RATE; npm start
```

Avec 50 req/s et un scénario d'une étape en lecture, LoadPilot a détecté la rupture vers 63 req/s offertes, environ 2 minutes après le lancement (guide : `docs/demo/GUIDE_DEMO_STRESS.md`).

## Routes

| Méthode | Route | Authentification |
|---|---|---|
| GET | `/`, `/health` | Non |
| GET | `/metrics` | Non : métriques Prometheus du processus Node (CPU, mémoire), lues par le monitoring de LoadPilot |
| POST | `/api/auth/login` | Non (renvoie un jeton) |
| GET | `/api/auth/verify` | Oui : renvoie `{"authenticated":true,"method":"bearer\|api_key\|basic"}`, ou 401. Sans latence ni erreur aléatoire |
| GET | `/api/account/balance`, `/api/agent/history` | Oui |
| POST | `/api/transfers/national`, `/api/transfers/international` | Oui |
| PUT / PATCH / DELETE | `/api/transfers/:id` | Oui |
| POST | `/api/bills/pay`, `/api/deposits` | Oui |
| POST | `/api/admin/reset` | En-tête `X-Admin-Key` = `ADMIN_RESET_KEY` : remise à zéro entre deux exécutions (voir ci-dessous) |
| POST | `/api/reset` | Non (restaure les données de démo ; jamais dans un scénario de charge) |
| GET | `/demo/accounts`, `/demo/accounts/:id/session`, `/demo/transfer-form` | Oui : démonstration des captures (voir ci-dessous) |
| POST | `/demo/transfer-form` | Oui : formulaire `application/x-www-form-urlencoded` |

## Remise à zéro entre deux exécutions (`POST /api/admin/reset`)

Remet la base dans son état de départ, en une transaction (environ 100 ms) :

- **vidées** : `transfers`, `bills`, `deposits`, `transactions` (tout ce que créent les tests), puis réinsertion de `TRF001`, `TRX001` et `TRX002` ;
- **restaurées** : `accounts` (soldes de départ) et `invoices` (6 000 factures impayées) ;
- **oubliées** : sessions en mémoire (jetons de connexion, sessions `/demo`). Le jeton fixe `TEST-TOKEN-123` reste valide ;
- **inchangées** : `users`, `agents`, `clients`. Les identifiants de démo restent valides.

Protection :

- désactivée (403) tant que `ADMIN_RESET_KEY` n'est pas définie ;
- refusée (403) si `NODE_ENV=production`, même avec la clé ;
- en-tête `X-Admin-Key` égal à `ADMIN_RESET_KEY` obligatoire, sinon 401.

Ne jamais l'ajouter à un scénario de charge.

```powershell
# Démarrage avec la remise à zéro activée (choisir sa propre clé)
$env:ADMIN_RESET_KEY = 'ma-cle-locale'; npm start

# Appel, entre deux exécutions
curl.exe -s -X POST http://localhost:8888/api/admin/reset -H "X-Admin-Key: ma-cle-locale"
```

Réponse :

```json
{"status":"reset_ok","tables_cleared":["transfers","bills","deposits","transactions"],
 "tables_restored":["accounts","invoices"],"deleted_rows":{"transfers":10666,…},"sessions_cleared":1,"duration_ms":100}
```

## Démonstration des captures (`/demo/*`)

Quatre routes enchaînées pour montrer les 3 types de capture de LoadPilot (corps JSON, en-tête de réponse, page HTML). Chaque route **vérifie** la valeur reçue : une capture ratée fait échouer l'étape suivante, avec la raison. Latence simulée comme ailleurs, **jamais d'erreur aléatoire** (`ERROR_RATE` ne s'applique pas). Guide pas à pas : `docs/demo/GUIDE_DEMO_CAPTURES.md`.

| # | Route | Renvoie | Vérifie |
|---|---|---|---|
| 1 | `GET /demo/accounts` | `{"data":{"agent":"AG001","accounts":[{"id":"ACC001","access":"gestion",…},{"id":"ACC002","access":"lecture",…}]}}` : le compte de l'agent en premier | authentification |
| 2 | `GET /demo/accounts/:id/session` | en-tête `X-CSRF-Token`, cookie `DEMO_SESSION` (`Path=/demo`, `HttpOnly`) | compte existant (404), géré par l'agent (403 pour un compte en lecture) |
| 3 | `GET /demo/transfer-form` | page HTML avec `<input type="hidden" name="form_token" value="…">` | cookie `DEMO_SESSION` de l'agent **et** en-tête `X-CSRF-Token` de la session (403 sinon) |
| 4 | `POST /demo/transfer-form` (`form_token`, `account`, `amount`) | `{"status":"accepted","reference":"DEMO-…"}` | cookie, `form_token` émis pour cette session et **à usage unique**, même compte, montant > 0 (403 / 400 sinon) |

Sessions et jetons en mémoire : 10 minutes de validité, 100 000 au plus chacun (les plus anciens sont oubliés au-delà). Ils repartent à vide au redémarrage. Aucune donnée bancaire n'est modifiée : le virement de l'étape 4 est fictif.

## Données de démo

Créées au premier démarrage et restaurées à l'identique par `POST /api/reset` (`src/db.js`). Toutes fictives.

| Donnée | Contenu |
|---|---|
| Agents | `agent001` … `agent020` (utilisateurs `AG001` … `AG020`) |
| Mots de passe de démo | `agent001` / `test123`, puis `agentNNN` / `testNNN` (ex. `agent007` / `test007`) |
| Clients | `CL001` … `CL020` : l'agent `agentNNN` gère le client `CLNNN` |
| Comptes | `ACC001` … `ACC020` : le client `CLNNN` possède le compte `ACCNNN` (30 000 à 49 000 MAD). C'est le compte de `GET /api/account/balance` pour `agentNNN` |
| Factures impayées | 6 000 : `FAC-2026-00001` … `FAC-2026-06000`, 300 par client (facture *k* → client `CL((k−1) mod 20 + 1)`), de 20 à 800 MAD |
| Transfert | `TRF001` (`ACC001` → `ACC002`, 500 MAD) |

Les CSV de `docs/samples/` (`parcours-bancaire-5000.csv`, `users-login*.csv`) sont générés à partir de ces données par
`docs/samples/generate-parcours-bancaire.mjs`.

## Règles métier et codes de réponse

| Opération | Règle | Code si non respectée |
|---|---|---|
| Toutes les écritures | Champs requis présents, montant > 0 | 400 |
| Virement | Compte source existant ; destinataire existant pour un virement national | 404 |
| Virement | Source et destinataire différents | 400 |
| Virement, PATCH, PUT | Solde suffisant : le virement débite la source et crédite le destinataire | 422 |
| DELETE d'un virement | Annule le virement : la source est remboursée, le destinataire débité | — |
| Paiement de facture | La facture existe et appartient au `clientId` | 404 |
| Paiement de facture | Montant égal à celui de la facture | 400 |
| Paiement de facture | **Facture déjà payée** | **409** |
| Dépôt | Compte existant ; crédite le compte | 404 |

Déclencheurs volontaires : `destinationAccount` = `LOCKED` (409), `NOTFOUND` (404), `ERROR500` (500) ; `billNumber` = `NOTFOUND` (404).
En plus, les erreurs aléatoires de `ERROR_RATE` (ci-dessus) s'appliquent aux virements, factures et dépôts.

## Authentification : 4 modes acceptés

Toute route protégée accepte l'un des modes suivants, sinon elle répond `401`.

| Mode | Ce qu'il faut envoyer |
|---|---|
| Jeton de session | `Authorization: Bearer <token>`, le jeton étant renvoyé par `POST /api/auth/login` |
| Jeton fixe | `Authorization: Bearer TEST-TOKEN-123` |
| Clé API | En-tête `X-API-Key: DEMO-API-KEY-2026`, ou paramètre `?api_key=DEMO-API-KEY-2026` |
| Basic | `Authorization: Basic base64(agent001:test123)` |

## Configuration dans LoadPilot

Dans **Applications → Ajouter une application**, avec l'URL `http://localhost:8888` :

| Mode | Endpoint | Identifiants corrects → CONNECTED | Identifiants faux → FAILED |
|---|---|---|---|
| Aucune | — | — | — |
| Connexion auto | `POST /api/auth/login` | corps `{"username":"agent001","password":"test123"}`, chemin du token `$.token` | `{"username":"agent001","password":"mauvais"}` → « Identifiants refusés (401) » |
| Jeton Bearer | `GET /api/auth/verify` | `TEST-TOKEN-123` | `FAUX-TOKEN` → « Jeton refusé (401) » |
| Clé API | `GET /api/auth/verify` | `X-API-Key` = `DEMO-API-KEY-2026` (ou paramètre `api_key`) | `X-API-Key` = `MAUVAISE-CLE` → « Clé API refusée (401) » |
| Basic | `GET /api/auth/verify` | `agent001` / `test123` | `agent001` / `mauvais` → « Identifiants Basic refusés (401) » |

**Ce que vérifie le bouton « Tester »** :
- **Aucune** : `GET` sur l'URL de l'application.
- **Connexion auto** : le `POST` réel sur l'endpoint de connexion. Il faut un 2xx et un token trouvé au chemin indiqué.
- **Jeton Bearer, Clé API, Basic** : deux `GET` sur l'endpoint de vérification.
  1. Sans identifiants, la réponse doit être 401 ou 403. Une route publique comme `/health` est refusée (« Endpoint non protégé »).
  2. Avec les identifiants, la réponse doit être 2xx.

Des identifiants faux ne donnent jamais CONNECTED.

**En exécution**, LoadPilot ajoute l'authentification à chaque étape des scénarios : une étape `GET /api/account/balance` n'a besoin d'aucun en-tête.
- Avec **Connexion auto**, LoadPilot se connecte une seule fois par exécution. Le token est aussi disponible dans les étapes via `${authToken}`.
- Un en-tête défini dans une étape remplace celui de l'application.
