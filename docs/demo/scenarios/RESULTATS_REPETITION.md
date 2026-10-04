# Résultats de la répétition du 02/10/2026

J'ai d'abord répété la démonstration avec de vraies requêtes : API LoadPilot (`localhost:8080`) et banking-test-api (`localhost:8888`).
Le script était temporaire et hors du dépôt. Aucun fichier de code n'a été modifié.

Résultat : **45 actions sur 46 conformes** avant l'arrêt. L'arrêt est expliqué dans l'anomalie A3.

## Actions et résultats obtenus

| Partie | Action | Attendu | Obtenu |
|---|---|---|---|
| 0 | `GET /health` (banking) | 200 | 200 `{"status":"ok"}` |
| 0 | Test de l'application « Banking (connexion auto) » | CONNECTED | CONNECTED — « Authentification réussie (token obtenu). » |
| 1 | Créer un brouillon | 201, DRAFT, étape 1 | 201, DRAFT, wizardStep 1 |
| 1 | Lancer le brouillon | 400 | 400 — « Ce scénario est un brouillon : terminez-le dans l'assistant (« Reprendre ») avant de l'exécuter. » |
| 1 | Planifier le brouillon | 400 | 400 — « … avant de le planifier. » |
| 1 | Publier sans étape | 400 | 400 — « Ajoutez au moins une étape avant d'enregistrer le scénario. » |
| 1 | Étape au corps JSON invalide | 400 | 400 — « Corps JSON invalide : vérifiez les guillemets, virgules et accolades. » |
| 1 | Étape à l'URL invalide | 400 | 400 — « url : La ressource ne doit pas contenir d'espace. » |
| 1 | CSV à l'en-tête invalide | 400 | 400 — « CSV invalide : Nom de colonne invalide « compte source »… » |
| 1 | Import de `transactions.csv` et des variables | 200 | 200, distribution PER_VIRTUAL_USER |
| 1 | Publier | ACTIVE | 200, ACTIVE |
| 1 | Supprimer « brouillon » sur un scénario actif | 409 | 409 |
| 1 | Quitter sans enregistrer (brouillon jeté) | 204 puis 404 | 204 puis 404 |
| 2 | Tester le scénario | 9/9 | 9/9 en 2 250 ms, 1ᵉʳ essai |
| 2 | En-tête `Bearer ${authToken}` | masqué | `••••••` |
| 2 | Tester une seule étape | 1/1 | 1/1 |
| 2 | Tester en flux (NDJSON) | started, auth, 9 step-started, 9 step, done | 1ʳᵉ fois : conforme (21 lignes). 2ᵉ fois : **3 step-started** au lieu de 9 (voir A1) |
| 2 | Variable `${inconnue}` | échec avant envoi | FAILED, aucune réponse HTTP, « Variable ${inconnue} introuvable : ni capturée par une étape, ni colonne du CSV, ni variable du scénario. » |
| 3 | Exécution par VU (3 VU, 3 s, 20 s, 500 ms) | fin normale | FAILED (erreurs simulées) : 239 requêtes, 28 itérations, 200 × 159, 201 × 74, 409 × 5, 500 × 1, succès 97,49 %, moyenne 198 ms, P95 310 ms |
| 3 | Détail des échecs | seulement des 409/500 simulés | dépôt 409 × 3, facture 500 × 1, facture 409 × 1, virement 409 × 1 |
| 3 | Lignes CSV utilisées (table bills) | lignes 1 à 3 | FAC-2026-0001 × 9, 0002 × 7, 0003 × 9 |
| 3 | Isolation transferId | 0 mélange | 27 itérations, 27 transferId distincts, 0 mélange |
| 3 | Suivi en direct | relevé chaque seconde | 24 relevés, de 3 % à 83 %, 3 VU actifs, environ 10 à 11,6 req/s |
| 3 | Exécution par itération | lignes 1 à 12 | FAILED (409 × 2 sur facture) : 230 requêtes, 26 itérations, succès 99,13 %, P95 320 ms. Factures 0001 à 0012 toutes reçues |
| 3 | Isolation (par itération) | 0 mélange | 26 sur 26 distincts, 0 mélange |
| 3 | Traçabilité par résultat | champs remplis | ex. VU 0, itération 0, `http://localhost:8888/health` |
| 5 | users.csv : Tester | 2/2 | 2/2, jeton capturé |
| 5 | users.csv : 2 VU × 3 itérations | SUCCESS, 12 requêtes | SUCCESS, 6 × « Se connecter » 200 et 6 × « Solde » 200 |
| 6 | users-invalid.csv : 1 VU × 3 itérations | SUCCESS, 3 × 401 | SUCCESS, `[401, 401, 401]` |
| 4 | Tester une version non enregistrée | 3/3, base inchangée | 3/3, 2 étapes en base |
| 4 | `PUT /full` avec une étape invalide | 400, rien d'enregistré | 400 — « Étape 3 « Historique avec le jeton » : Corps JSON invalide… », nom inchangé |
| 4 | `PUT /full` valide | 3 étapes | 200, étapes 1 « Se connecter », 2 « Solde (renommée) », 3 « Historique avec le jeton » |
| 7 | Créer une planification cron `0 2 * * *` | 201 | **400** (voir A2) |

Les parties 7 (suite), 8 et 9 **n'ont pas été répétées**.

## Anomalies constatées

### A1 — Flux « Tester » : événements `step-started` incomplets (gravité : faible, à confirmer)
- **Pour reproduire** : `POST /api/scenarios/{id}/test-run?stream=true` sur le scénario de 9 étapes, puis compter les lignes NDJSON par `type`. Recommencer plusieurs fois.
- **Attendu** : 1 `started`, 1 `auth`, 9 `step-started`, 9 `step`, 1 `done` (21 lignes).
- **Obtenu** : conforme la 1ʳᵉ fois. La 2ᵉ fois, 15 lignes avec **3 `step-started`** et 9 `step`.
- **Impact** : l'indicateur « étape en cours » du panneau Tester peut sauter des étapes. Les résultats des 9 étapes restent complets.
- **À faire** : refaire le test 5 fois pour voir si c'est systématique ou aléatoire.

### A2 — Création d'une planification refusée (400) (gravité : non concluante)
- **Pour reproduire** : `POST /api/scheduled-executions` avec `scheduleType` = `RECURRING_CRON`, `cronExpression` = `0 2 * * *` et `timezone` = `Africa/Casablanca` sur le scénario `DEMO — Connexion utilisateurs`.
- **Attendu** : 201.
- **Obtenu** : 400. Le message n'a pas été capturé.
- **Cause probable** : le scénario venait d'être supprimé (voir A3). À refaire dans la partie 7 du guide.

### A3 — Données supprimées pendant la répétition (incident, pas un défaut du produit)
- Le journal d'audit montre deux entrées à 21:45:16, utilisateur `admin-test` :
  - « Application permanently deleted: Banking (connexion auto) (6 scenario(s), 29 step(s), 18 execution(s)) » ;
  - « Application permanently deleted: Banking (1 scenario(s), 6 step(s), 1 execution(s)) ».
- Le script de répétition n'appelle jamais `DELETE /api/applications`. Il ne supprime que les scénarios préfixés « DEMO — ».
- Toutes les applications, scénarios et exécutions de LoadPilot ont donc disparu. La répétition a été arrêtée et rien n'a été recréé, à votre demande.

### Rappel : comportements attendus qui ne sont pas des anomalies
- **FAILED** dès qu'une requête échoue : banking-test-api injecte 7 % de 409/500 sur les écritures.
- Annulation sans résultats partiels, absence d'échappement JSON, JSONPath sans tableau : limites déjà connues.
