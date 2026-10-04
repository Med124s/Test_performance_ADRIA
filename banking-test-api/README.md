# banking-test-api

Serveur HTTP de démonstration (Node.js / Express) servant de **cible de test** pour LoadPilot. Il n'est pas nécessaire en production.

```powershell
cd banking-test-api
npm install   # une seule fois
npm start     # http://localhost:8888
```

Le port peut être changé avec la variable d'environnement `PORT`.

La latence est simulée, et 7 % d'erreurs 409/500 aléatoires sont injectées sur les requêtes valides (voir `src/config.js`) : quelques échecs pendant un test de charge sont donc normaux.

## Routes

| Méthode | Route | Authentification |
|---|---|---|
| GET | `/`, `/health` | Non |
| POST | `/api/auth/login` | Non (renvoie un jeton) |
| GET | `/api/auth/verify` | Oui : renvoie `{"authenticated":true,"method":"bearer\|api_key\|basic"}`, ou 401. Sans latence ni erreur aléatoire |
| GET | `/api/account/balance`, `/api/agent/history` | Oui |
| POST | `/api/transfers/national`, `/api/transfers/international` | Oui |
| PUT / PATCH / DELETE | `/api/transfers/:id` | Oui |
| POST | `/api/bills/pay`, `/api/deposits` | Oui |
| POST | `/api/reset` | Oui (restaure les données de démo) |

Compte de démonstration : **agent001 / test123**.

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
