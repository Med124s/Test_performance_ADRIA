# Guide de démonstration — corrections récentes et captures enchaînées

Démonstration guidée, à dérouler dans LoadPilot, en deux temps :

- **Partie A** : vérifier les 5 corrections récentes (limites 200 / 500, valeurs par défaut des nouveaux scénarios, « Aucun proxy » par défaut, lignes non cliquables et exports Excel / PDF, purge du journal d'audit).
- **Parties B à E** : un scénario qui enchaîne les **3 types de capture** (corps JSON, en-tête de réponse, page HTML) contre banking-test-api, puis des niveaux de charge raisonnables.

Durée : environ 45 minutes. Toutes les données sont fictives.

## Prérequis

| Composant | Commande | Vérification |
|---|---|---|
| PostgreSQL, Keycloak | `docker compose up -d postgres keycloak` | conteneurs `loadpilot-db` et `keycloak` démarrés |
| Backend LoadPilot (version à jour) | `cd backend; .\start.ps1` | journal : « Started BackendApplication » |
| Frontend | `cd perftest-frontend; npm run dev` | http://localhost:3000 |
| banking-test-api (avec les routes `/demo`) | `cd banking-test-api; npm start` | http://localhost:8888/health → `{"status":"ok"}` |

- **Redémarrez banking-test-api** s'il tournait déjà avant l'ajout des routes `/demo`.
- Compte LoadPilot : un **Super administrateur** pour la partie A (Paramètres > Système).
- Identifiants de démo de la cible : `agent001` / `test123`.

---

## Partie A — Les 5 corrections récentes

### A1. Limites 200 / 500

1. **Paramètres > Système > Limites**.
2. ✔ « Utilisateurs virtuels au total » est le **premier** champ, avec sous lui « Maximum 500 (plafond du serveur). ».
3. ✔ « Utilisateurs virtuels par test » vient ensuite, avec « Maximum 200 (plafond du serveur). ».
4. Saisissez **150** dans « au total » (plus petit que « par test », 200).
   - ✔ Sous les **deux** champs, en **rouge** : « Le total doit être supérieur ou égal au maximum par test. »
   - ✔ Les deux champs ont une bordure rouge ; « Enregistrer » est désactivé.
5. Remettez **500** : les aides grises reviennent. Ne pas enregistrer.
6. Plus tard (partie B), essayez **201** utilisateurs virtuels dans un scénario : ✔ refusé, le maximum indiqué est 200.

> Si les aides affichent « Maximum 500 » sous « par test » et « Maximum 200 » sous « au total », le backend n'est pas à jour : relancez-le.

### A2. Valeurs par défaut des nouveaux scénarios

1. **Paramètres > Système > Seuils par défaut**.
2. ✔ Valeurs initiales : frein automatique désactivé (20 % sur 30 s s'il est activé), ralentissement sur 429 activé (5 s, 3 rejeux), détail des requêtes « Échecs + échantillon ».
3. Activez le **frein à 25 %** et mettez l'**attente après un 429 à 7 s**. Enregistrez.
   - ✔ Message de succès ; une ligne « Seuils par défaut » apparaît au journal d'audit.
4. La vérification se fait à la création du scénario (partie B, étape B3) : ✔ l'assistant propose frein activé à 25 % et attente de 7 s.
5. Ouvrez un **scénario existant** : ✔ ses réglages n'ont pas changé.
6. À la fin de la démo, remettez les valeurs initiales si besoin.

### A3. « Aucun proxy » par défaut

Se vérifie à la création de l'application (partie B, étape B1) :

- ✔ Bloc **Réseau** : « Aucun proxy » sélectionné d'office.
- ✔ Bloc **Monitoring** : la case « Monitoring sans proxy » est **grisée**, avec une explication (il n'y a pas de proxy à contourner).

### A4. Lignes non cliquables, exports Excel / PDF

Se vérifie après les exécutions (partie E).

### A5. Purge du journal d'audit

> ⚠ **La purge supprime réellement les entrées.** Faites d'abord une sauvegarde :
> `docker exec loadpilot-db pg_dump -U loadpilot -Fc loadpilot > D:\Adria_Stage\backups\loadpilot-avant-demo.dump`

1. Regardez l'âge des entrées pour choisir une durée qui ne supprime qu'un petit nombre connu :
   ```powershell
   docker exec loadpilot-db psql -U loadpilot -d loadpilot -c "SELECT min(date), count(*) FILTER (WHERE date < now() - interval '14 days') AS plus_de_14j, count(*) FROM audit_log;"
   ```
2. Arrêtez le backend, puis relancez-le avec un passage toutes les 2 minutes et la durée choisie (exemple : 14 jours) :
   ```powershell
   cd backend
   $env:LOADPILOT_AUDIT_PURGE_CRON = '0 */2 * * * *'
   $env:LOADPILOT_AUDIT_RETENTION_OVERRIDE = '14d'
   .\start.ps1
   ```
   - ✔ Au démarrage : `LOADPILOT_AUDIT_RETENTION_OVERRIDE actif : journal d'audit purges au-dela de 14 jours…`
3. Attendez au plus 2 minutes.
   - ✔ Journal du backend, à **chaque** passage : `Purge du journal d'audit : coupure <date> (plus de 14 jours (durée de test …)), N entree(s) supprimee(s)` (même si N = 0).
   - ✔ **Paramètres > Système** : « Dernière purge » du journal avec la date et le nombre d'entrées supprimées.
   - ✔ **Journal d'audit** : une entrée « Purge automatique quotidienne » (acteur « Système ») si N > 0, et plus aucune entrée plus ancienne que 14 jours.
4. **Remettez la configuration normale** avant la suite :
   ```powershell
   Remove-Item Env:LOADPILOT_AUDIT_PURGE_CRON, Env:LOADPILOT_AUDIT_RETENTION_OVERRIDE
   .\start.ps1
   ```

---

## Partie B — Application et scénario « 3 captures »

### B1. Application « Démo captures »

**Applications > Ajouter une application** :

| Champ | Valeur |
|---|---|
| Nom | `Démo captures` |
| URL | `http://localhost:8888` |
| Authentification | **Connexion auto** : `POST /api/auth/login`, corps `{"username":"agent001","password":"test123"}`, chemin du jeton `$.token` |
| Réseau | laisser « Aucun proxy » (✔ vérification A3) |
| Monitoring | Prometheus, `http://localhost:8888/metrics`, 5 s |

Enregistrez, puis **Tester** : ✔ « Authentification réussie (token obtenu). » Puis **Tester le monitoring** : ✔ « Monitoring opérationnel… ».

### B2. Ce que fait la cible

| # | Route | Ce qu'on capture | Ce que la route vérifie |
|---|---|---|---|
| 1 | `GET /demo/accounts` | **corps JSON** : l'identifiant du 1er compte | l'authentification |
| 2 | `GET /demo/accounts/{compte}/session` | **en-tête** `X-CSRF-Token` (+ le cookie `DEMO_SESSION`) | compte existant et géré par l'agent |
| 3 | `GET /demo/transfer-form` | **page HTML** : le jeton caché `form_token` | le cookie **et** l'en-tête `X-CSRF-Token` de la session |
| 4 | `POST /demo/transfer-form` | — | le `form_token` (à usage unique), le cookie, le compte |

Une valeur mal capturée fait **échouer l'étape suivante** (403 ou 404, avec la raison) : c'est ce qui prouve le chaînage.

### B3. Scénario « Démo — chaînage des 3 captures »

**Scénarios > Nouveau scénario**, application « Démo captures ».

✔ Vérification A2 : dans « Charge & résumé », le frein est déjà activé à 25 % et l'attente après un 429 vaut 7 s.

#### Étape 1 — Comptes (capture dans le corps JSON)

| Onglet | Valeur |
|---|---|
| Paramètres | `GET` `/demo/accounts` |
| Validation | code `200`, la réponse doit contenir `"accounts"` |
| Variables | **Corps JSON** ; variable `accountId` ; chemin `$.data.accounts[0].id` |

#### Étape 2 — Session (capture d'en-tête et de cookie)

| Onglet | Valeur |
|---|---|
| Paramètres | `GET` `/demo/accounts/${accountId}/session` |
| Validation | code `200`, la réponse doit contenir `Session ouverte` |
| Variables | **En-tête de réponse** ; variable `csrfToken` ; en-tête `X-CSRF-Token` |

Facultatif, pour montrer aussi la capture d'un cookie : une étape ne capture qu'une variable, donc dupliquez l'étape 2 (sinon le cookie est de toute façon renvoyé automatiquement) avec **En-tête de réponse** ; variable `sessionCookie` ; en-tête `Set-Cookie`, cookie `DEMO_SESSION`. Attention : chaque passage ouvre une nouvelle session, c'est la dernière qui compte.

#### Étape 3 — Formulaire (capture dans la page HTML)

| Onglet | Valeur |
|---|---|
| Paramètres | `GET` `/demo/transfer-form` |
| En-têtes | `X-CSRF-Token` : `${csrfToken}` |
| Validation | code `200`, la réponse doit contenir `form_token` |
| Variables | **Page HTML** ; variable `formToken` ; sélecteur `input[name=form_token]` ; attribut `value` |

#### Étape 4 — Envoi du formulaire (réutilisation)

| Onglet | Valeur |
|---|---|
| Paramètres | `POST` `/demo/transfer-form` |
| En-têtes | `Content-Type` : `application/x-www-form-urlencoded` |
| Corps | `form_token=${formToken}&account=${accountId}&amount=100` |
| Validation | code `200`, la réponse doit contenir `"accepted"` ; assertion JSON : `$.status` **égale** `accepted` |

**Le cookie `DEMO_SESSION`** n'apparaît dans aucune étape : LoadPilot le garde et le renvoie automatiquement (un pot de cookies par utilisateur virtuel, vidé à chaque itération).

---

## Partie C — Tester le chaînage

1. Bouton **Tester** de l'assistant (1 utilisateur, 1 passage).
   - ✔ Les 4 étapes sont vertes.
   - ✔ Étape 1 : `accountId` = `ACC001`.
   - ✔ Étapes 2 et 3 : `csrfToken` et `formToken` **masqués** (`••••••`) : leur nom évoque un jeton. Le succès des étapes suivantes prouve qu'ils ont été capturés et réutilisés.
   - ✔ Étape 4 : réponse `{"status":"accepted","reference":"DEMO-…"}`.
2. **Contre-tests** (modifier, **Tester**, puis remettre la valeur d'origine) :

| Modification | Résultat attendu |
|---|---|
| Étape 1 : chemin `$.data.accounts[1].id` (compte en lecture seule) | étape 2 en échec : 403 « Compte ACC002 en lecture seule pour cet agent : session refusée. » |
| Étape 1 : chemin `$.data.accounts[5].id` | capture en échec « index hors limites » ; étape 2 signale la variable `accountId` introuvable |
| Étape 3 : supprimer l'en-tête `X-CSRF-Token` | étape 3 en échec : 403 « Jeton CSRF absent ou invalide… » |
| Étape 3 : sélecteur `input[name=autre]` | capture en échec (élément absent) ; étape 4 signale `formToken` introuvable |
| Étape 4 : corps avec `account=ACC002` | étape 4 en échec : 403 « Compte ACC002 différent de celui du formulaire (ACC001). » |

3. Enregistrez le scénario.

---

## Partie D — Niveaux de charge

Ordre de grandeur : 4 requêtes par itération, ~200 ms chacune (latence simulée), puis le think time **entre deux itérations**. Avec un think time de 2 000 ms, une itération dure ~2,8 s, soit **~1,4 req/s par utilisateur virtuel**.

| Niveau | Réglages (« Charge & résumé ») | Charge attendue | Objectif |
|---|---|---|---|
| **Smoke test** | 1 utilisateur, 3 itérations, think time 1 000 ms | 12 requêtes | vérifier le chaînage en exécution : **0 échec** attendu |
| **Démonstration normale** | 20 utilisateurs, ramp-up 30 s, durée 180 s, think time 2 000 ms | ~28 req/s, ~5 000 requêtes | suivi en direct lisible, rapports et exports instantanés |
| **Démonstration chargée** (facultatif) | 100 utilisateurs, ramp-up 60 s, durée 300 s, think time 2 000 ms, débit cible 150 req/s | ≤ 150 req/s, ~40 000 requêtes | montrer le débit cible et la surveillance de l'injecteur |
| **Ne pas dépasser sans banc de capacité** | **200 utilisateurs, 10 minutes, ~100 000 requêtes par test** | — | voir ci-dessous |

Pourquoi cette limite :

- 200 utilisateurs virtuels est le plafond par test.
- Le plus gros test jamais enregistré compte ~100 000 requêtes ; au-delà, rapports et exports n'ont jamais été vérifiés à ce volume.
- La seule mesure de capacité (500 utilisateurs, ~105 Mo de tas, 40 % de CPU) ne couvre que l'injection, pas l'écriture en base ni les rapports.
- banking-test-api est un seul processus Node avec SQLite : il saturera probablement avant LoadPilot. Surveillez le CPU de la cible (monitoring) et l'avertissement « Injecteur saturé » de LoadPilot.

Pendant chaque exécution, **Suivre en direct** : ✔ utilisateurs actifs, req/s, temps moyen, CPU / RAM de la cible, ressources de LoadPilot. Les sessions et jetons de démo étant à usage unique et par utilisateur virtuel, **le taux d'échec doit rester à 0 %** : un échec signale un vrai problème de capture.

---

## Partie E — Après les exécutions (vérification A4)

1. **Exécutions** :
   - ✔ Cliquer sur une **ligne** ne fait rien.
   - ✔ La colonne **Actions** propose les icônes (dont « Suivre en direct » pour une exécution en cours).
2. **Rapports** :
   - ✔ Ligne non cliquable ; actions dans la colonne Actions.
   - ✔ Exports proposés : **Excel et PDF uniquement** (ni CSV ni ZIP), dans la table et sur la page du rapport.
   - Ouvrez le rapport de la démonstration normale : ✔ 4 étapes, 0 % d'erreurs, P95 de l'ordre de 300 ms ; section CPU / RAM de la cible.
3. **Planification** : créez une planification du scénario.
   - ✔ Ligne non cliquable ; icône « Voir le détail » dans la colonne Actions.
   - ✔ Dans l'historique d'une planification, les liens vers les exécutions restent cliquables.
4. Exportez le rapport en **Excel** et en **PDF** ; cochez « **Conserver** » si vous voulez garder ses résultats au-delà de 90 jours.

## Vérifier en base (facultatif)

```powershell
docker exec -it loadpilot-db psql -U loadpilot -d loadpilot
```

```sql
-- Dernières exécutions : statut, P95, débit
SELECT id, status, started_at, p95_ms, throughput_rps FROM execution ORDER BY started_at DESC LIMIT 5;

-- Requêtes enregistrées pour une exécution
SELECT count(*) FROM execution_step_result WHERE execution_id = '<uuid>';
```

## Remise à zéro

- banking-test-api : un redémarrage efface les sessions et jetons de démo. Les routes `/demo` ne modifient aucune donnée bancaire.
- LoadPilot : remettre les « Seuils par défaut » (A2) si besoin ; supprimer ou archiver l'application « Démo captures ».
