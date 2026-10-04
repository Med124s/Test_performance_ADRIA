# Démonstration — Module Gestion des scénarios (LoadPilot)

Guide à suivre seul, dans l'interface LoadPilot, avec banking-test-api comme API cible.
Chaque partie indique **quoi faire**, puis **ce que vous devez voir**. Quand c'est utile, le guide donne aussi la valeur
**obtenue lors de la répétition du 02/10/2026** (voir [RESULTATS_REPETITION.md](RESULTATS_REPETITION.md)).

Durée : 45 min environ.

---

## 0. Préparation

### 0.1 Services

| Service | Adresse | Vérification |
|---|---|---|
| Frontend LoadPilot | http://localhost:3000 | la page de connexion s'affiche |
| Backend LoadPilot | http://localhost:8080 | `GET /actuator/health` → 200 |
| Keycloak | http://localhost:8081 | — |
| banking-test-api | http://localhost:8888 | `GET /health` → `{"status":"ok"}` |

Connectez-vous à LoadPilot avec votre compte **SUPER_ADMIN** (pas de mot de passe ici).

### 0.2 Remettre banking-test-api à zéro

À faire **avant chaque exécution** dont vous voulez vérifier les chiffres :

```powershell
Invoke-RestMethod -Method Post http://localhost:8888/api/reset
```

Données de départ réelles (lues dans `banking-test-api/banking.db` après un reset) :

| Table | Contenu |
|---|---|
| users | `AG001` · `agent001` · rôle `agent` (seul utilisateur) |
| accounts | `ACC001` (agent, 15 420,50 MAD) · `ACC002` (client CL001, 8 000 MAD) |
| clients | `CL001` · Client Démo |
| transfers | `TRF001` (ACC001 → ACC002, 500) |
| bills | aucune facture |

> **À savoir sur banking-test-api** (lu dans `src/index.js`) :
> - 7 % des requêtes d'écriture valides reçoivent un **409 ou 500 aléatoire** (virement, facture, dépôt).
>   Quelques échecs pendant une charge sont donc **normaux**. Une exécution avec au moins un échec finit en **FAILED**.
> - Un virement **ne débite pas** le solde : aucun « solde insuffisant » n'est possible.
> - `POST /api/bills/pay` crée la facture payée. Le `billNumber` est une référence libre (sauf `NOTFOUND`, qui donne 404).

### 0.3 Créer l'application

**Applications → Ajouter une application**, avec les valeurs du README de banking-test-api :

| Champ | Valeur |
|---|---|
| Nom | `Banking (connexion auto)` |
| URL | `http://localhost:8888` |
| Authentification | **Connexion auto** |
| Endpoint de connexion | `POST /api/auth/login` |
| Corps | `{"username":"agent001","password":"test123"}` |
| Chemin du token | `$.token` |

Cliquez sur **Tester**. Résultat attendu : **CONNECTED** (« Authentification réussie (token obtenu). »).

### 0.4 Fichiers CSV de la démo

Dans `docs/demo/scenarios/data/` :

| Fichier | Contenu | Utilisé en |
|---|---|---|
| `transactions.csv` | 12 lignes : `sourceAccount, destinationAccount, amount, patchAmount, putAmount, clientId, billNumber, billAmount, depositAccount, depositAmount`. Comptes réels ACC001/ACC002, client réel CL001, factures `FAC-2026-0001` à `0012` | partie 1 |
| `users.csv` | `agent001, test123, ACC001` (le seul utilisateur réel) | partie 5 |
| `users-invalid.csv` | 3 identifiants faux : mot de passe faux, utilisateur inexistant, casse différente | partie 6 |

Limites à respecter si vous modifiez ces fichiers :
- aucun `"` ni `\` dans les valeurs, car elles ne sont pas échappées en JSON ;
- noms de colonnes avec lettres, chiffres et `_` uniquement.

---

## 1. Créer le scénario avec l'assistant (brouillon, validation, CSV)

**Scénarios → Nouveau scénario.** L'assistant a 4 étapes : *Informations · Étapes · Tester · Charge & résumé*.

### 1.1 Informations
- Application : `Banking (connexion auto)`.
- Nom : `DEMO — Parcours bancaire (transactions.csv)`.
- Description : `Virement, PATCH, PUT, DELETE, facture, dépôt, historique.`

### 1.2 Brouillon
1. Cliquez sur **Enregistrer comme brouillon**, puis revenez à **Scénarios**.
   - ✔ Le scénario apparaît avec le statut **Brouillon** et le bouton **Reprendre**.
2. Le brouillon ne s'exécute pas et ne se planifie pas. Messages obtenus pendant la répétition (via l'API) :
   - lancement : « Ce scénario est un brouillon : terminez-le dans l'assistant (« Reprendre ») avant de l'exécuter. »
   - planification : « … avant de le planifier. »
3. Cliquez sur **Reprendre** : vous revenez à l'étape où vous étiez.

### 1.3 Étapes : saisir les 9 requêtes

Pour chaque requête : **Ajouter une étape**, puis remplissez les onglets *Paramètres · En-têtes · Corps · Validation · Variables*.

| # | Nom | Méthode / URL | En-têtes · Corps | Validation (code + texte attendu) | Variables / Paramètres |
|---|---|---|---|---|---|
| 1 | Santé de l'API | GET `/health` | — | 200 · `"status":"ok"` | — |
| 2 | Consulter le solde | GET `/api/account/balance` | En-tête `Authorization` = `Bearer ${authToken}` | 200 · `"account":"ACC001"` | — |
| 3 | Virement national | POST `/api/transfers/national` | `Content-Type: application/json` · corps A | 201 · `"status":"completed"` | **Capture** : nom `transferId`, JSONPath `$.transferId` |
| 4 | Modifier le montant (PATCH) | PATCH `/api/transfers/:transferId` | JSON · `{"amount": ${patchAmount}}` | 200 · `"success":true` | **Paramètres** → variable de chemin `transferId` = `${transferId}` |
| 5 | Remplacer le virement (PUT) | PUT `/api/transfers/:transferId` | JSON · corps B | 200 · `"destinationCountry":"MA"` | idem étape 4 |
| 6 | Supprimer le virement | DELETE `/api/transfers/:transferId` | — | 200 · `"status":"deleted"` | idem étape 4 |
| 7 | Payer une facture | POST `/api/bills/pay` | JSON · corps C | 201 · `"status":"paid"` | — |
| 8 | Effectuer un dépôt | POST `/api/deposits` | JSON · `{"account": "${depositAccount}", "amount": ${depositAmount}}` | 201 · `"status":"completed"` | — |
| 9 | Historique de l'agent | GET `/api/agent/history` | — | 200 · `"transactions"` | **Paramètres** → paramètre de requête `page` = `1` |

- **Corps A :** `{"sourceAccount": "${sourceAccount}", "destinationAccount": "${destinationAccount}", "amount": ${amount}, "currency": "${currency}"}`
- **Corps B :** `{"destinationAccount": "${destinationAccount}", "amount": ${putAmount}, "currency": "${currency}", "destinationCountry": "${destinationCountry}"}`
- **Corps C :** `{"clientId": "${clientId}", "billNumber": "${billNumber}", "amount": ${billAmount}}`

À montrer pendant la saisie :
- **Validation partagée** : écrivez un corps invalide, par exemple `{"amount": 1,}`.
  - ✔ Le bouton **Suivant** reste grisé et l'erreur s'affiche sous le champ. Il n'y a plus de bloc « Pour continuer ».
  - Le backend applique la même règle : « Corps JSON invalide : vérifiez les guillemets, virgules et accolades. »
- **URL avec un espace** : « La ressource ne doit pas contenir d'espace. »
- **Badges de source** dans l'onglet Paramètres : `${transferId}` affiche *Capture (étape 3)*, une valeur sans variable affiche *Valeur fixe*, et une variable inconnue affiche *Inconnue*.

### 1.4 Données CSV et variables du scénario
1. Dans le panneau **Données** : **Importer un fichier**, puis `data/transactions.csv`.
   - ✔ L'aperçu affiche 12 lignes et 10 colonnes, sans alerte de variable manquante.
2. **Distribution des lignes** : choisissez **Une ligne par utilisateur virtuel**.
3. Dans **Variables du scénario**, cliquez deux fois sur **Ajouter une variable** :
   - `currency` = `MAD` ;
   - `destinationCountry` = `MA`.
4. Test négatif du CSV : importez un fichier dont l'en-tête contient un espace (par exemple `compte source;montant`).
   - Message obtenu : « CSV invalide : Nom de colonne invalide « compte source » : lettres, chiffres et _ uniquement, sans commencer par un chiffre. »
   - Réimportez ensuite `transactions.csv`.

---

## 2. Tester (vérification réelle, 1 utilisateur, 1 passage)

Remettez banking-test-api à zéro (0.2), puis allez à l'étape **Tester** et cliquez sur **Lancer le test**.

- ✔ Les étapes s'affichent une par une. Chaque carte a les onglets *Réponse · En-têtes · Requête envoyée · Vérifications*.
- ✔ Répétition : **9/9 étapes réussies en 2 250 ms**, avec la 1ʳᵉ ligne du CSV.
- ✔ Étape 3 → *Vérifications* : la capture `transferId` vaut `TRF002` (1ᵉʳ transfert après TRF001).
- ✔ Étapes 4 à 6 → *Requête envoyée* : l'URL contient `/api/transfers/TRF002`.
- ✔ Étape 2 → *Requête envoyée* : l'en-tête `Authorization` est **masqué** (`••••••`).
- ✔ Aucun message « Connexion réussie… » ne s'affiche quand l'authentification réussit. Il n'apparaît qu'en cas d'échec.
- Si une étape POST affiche **409 ou 500**, c'est l'erreur aléatoire de l'API : relancez le test.

**Variable introuvable** : ajoutez provisoirement une étape GET `/api/transfers/:id` avec la variable de chemin `id` = `${inconnue}`, puis testez.
- ✔ L'étape échoue **sans envoyer de requête**. Message obtenu : « Variable ${inconnue} introuvable : ni capturée par une étape, ni colonne du CSV, ni variable du scénario. »
- Supprimez ensuite cette étape.

---

## 3. Charge & résumé, puis exécution suivie en direct

1. Étape **Charge & résumé**, profil **Personnalisé** :

   | Champ | Valeur |
   |---|---|
   | Utilisateurs virtuels | 3 |
   | Think time (ms) | 500 |
   | Ramp-up (s) | 3 |
   | Durée (s) | 20 |
   | Itérations | vide |

   - ✔ L'aide du ramp-up affiche « un nouvel utilisateur toutes les 1 s ».
2. Remettez banking-test-api à zéro (0.2), puis cliquez sur **Enregistrer et lancer**.
   - Le scénario passe de **Brouillon** à **Actif**.
3. Page de suivi en direct (`/executions/live/<id>`), mise à jour chaque seconde :
   - progression en %, utilisateurs actifs, requêtes, succès et échecs, req/s, temps moyen ;
   - compteurs par étape.

   Relevés de la répétition :

   | t | Statut | Progression | VU actifs | Requêtes | OK | KO | Débit |
   |---|---|---|---|---|---|---|---|
   | 1 s | RUNNING | 3 % | 1 | 4 | 4 | 0 | 4,0 req/s |
   | 5 s | RUNNING | 23 % | 3 | 47 | 47 | 0 | 9,9 req/s |
   | 9 s | RUNNING | 43 % | 3 | 96 | 95 | 1 | 11,6 req/s |
   | 17 s | RUNNING | 83 % | 3 | 186 | 181 | 5 | 11,2 req/s |

4. Changez de page pendant l'exécution : ✔ le suivi continue (indicateur global) et la fenêtre de résultat s'ouvre à la fin.
5. Fenêtre de fin, avec **Voir le détail de l'exécution** et **Voir le rapport**. Répétition :
   - **FAILED** à cause des erreurs aléatoires de l'API ;
   - 239 requêtes, 28 itérations ;
   - codes 200 × 159, 201 × 74, 409 × 5, 500 × 1 ;
   - succès 97,49 %, moyenne 198 ms, P95 310 ms ;
   - 0 erreur autre que les 409/500 simulés.

### Vérifier la distribution du CSV
Une ligne par VU : avec 3 VU, seules les **lignes 1 à 3** doivent être utilisées. Pour le vérifier, comptez les factures reçues par l'API (lecture seule) :

```powershell
python -c "import sqlite3;c=sqlite3.connect('file:D:/Adria_Stage/Test_performance_ADRIA/banking-test-api/banking.db?mode=ro',uri=True);print(c.execute('select bill_number,count(*) from bills group by 1').fetchall())"
```

- Répétition : `FAC-2026-0001` × 9, `0002` × 7, `0003` × 9. Aucune autre ligne.

Ensuite, **modifiez le scénario** (partie 4), passez à **Une nouvelle ligne à chaque itération**, remettez l'API à zéro et relancez.
- Répétition : FAC-2026-0001 à 0012 ont toutes été utilisées.
- 230 requêtes, 26 itérations, 409 × 2, succès 99,13 %, P95 320 ms.

### Isolation par utilisateur virtuel
Chaque itération modifie et supprime **son propre** virement : jamais celui d'un autre VU.
- Répétition : 27 itérations avec virement, **27 transferId distincts**, 0 mélange. Second lancement : 26 sur 26.
- Pour le vérifier : **Voir le détail de l'exécution**. L'API renvoie `virtualUser`, `iteration` et `requestUrl` pour chaque résultat, mais la page de détail **ne les affiche pas encore** (limite connue).

---

## 4. Page de modification

Depuis **Scénarios**, ouvrez la modification de `DEMO — Parcours bancaire` (page `/scenarios/<id>/edit`).

1. ✔ Le titre est **Modifier le scénario**, tout est prérempli, et on navigue librement entre les sections.
2. Modifiez le nom d'une étape. ✔ La section porte le badge **Modifié** et **Enregistrer** devient actif.
3. Cliquez sur **Tester** : la version affichée est testée **sans être enregistrée**.
   - Répétition : 3/3, et la base gardait encore 2 étapes.
4. Rendez une étape invalide, puis enregistrez. ✔ Rien n'est enregistré, avec un message comme « Étape 3 « … » : Corps JSON invalide… ».
5. **Annuler** avec des modifications ouvre « Quitter sans enregistrer ? ».
6. **Enregistrer** applique tout en une seule opération (`PUT /api/scenarios/{id}/full`).

**Ajouter une étape** hors assistant : dans la liste **Scénarios**, ouvrez les détails du scénario, puis **Ajouter une étape**.
- ✔ Un panneau latéral s'ouvre avec le même éditeur. On peut tester l'étape avant de cliquer sur **Enregistrer l'étape**.

---

## 5. Connexion par utilisateur (`users.csv`)

Créez `DEMO — Connexion utilisateurs (users.csv)` :
- 2 étapes ;
- CSV `data/users.csv`, **Une ligne par utilisateur virtuel** ;
- 2 VU, 3 itérations, durée vide.

| # | Étape | Requête | Validation | Variables |
|---|---|---|---|---|
| 1 | Se connecter | POST `/api/auth/login`, JSON `{"username": "${username}", "password": "${password}"}` | 200 · `"success":true` | Capture `token` = `$.token` |
| 2 | Solde avec le jeton capturé | GET `/api/account/balance`, en-tête `Authorization` = `Bearer ${token}` | 200 · `"account":"ACC001"` | — |

- **Tester** : ✔ 2/2, le jeton est capturé puis réutilisé.
- **Lancer** : ✔ répétition **SUCCESS, 12 requêtes**, soit 6 connexions et 6 soldes, tous en 200.

## 6. Test négatif (`users-invalid.csv`)

Créez `DEMO — Connexion refusée (users-invalid.csv)` :
- 1 étape POST `/api/auth/login` avec le même corps qu'en partie 5 ;
- **code attendu 401**, texte attendu `Identifiants invalides` ;
- CSV `data/users-invalid.csv`, **Une nouvelle ligne à chaque itération** ;
- 1 VU, 3 itérations.

- ✔ Répétition : **SUCCESS**, codes `[401, 401, 401]`. Les trois refus étaient attendus, ce sont donc des succès.

---

## 7. Statut INACTIF et planifications

Sur `DEMO — Connexion utilisateurs` :
1. **Planifications** : créez une planification récurrente sur ce scénario.
2. **Scénarios** → **Désactiver**. ✔ Le statut devient **Inactif** et la planification est suspendue.
3. Lancez le scénario. ✔ Le lancement est refusé : « Le scénario est inactif, activez-le pour l'exécuter ».
4. **Activer**. ✔ LoadPilot propose de réactiver les planifications suspendues.
5. Supprimez la planification de démo.

> Cette partie n'a **pas pu être menée jusqu'au bout** pendant la répétition (voir les anomalies A2 et A3 dans RESULTATS_REPETITION.md). Notez vos propres résultats.

## 8. Liste paginée et actions groupées

1. Créez 3 scénarios simples : `DEMO — Temporaire 1/2/3`, avec chacun une étape GET `/health`.
2. Liste **Scénarios** :
   - recherche `DEMO` ;
   - tri par nom ;
   - taille de page 5 → ✔ pagination serveur (page 1 / page 2) ;
   - filtre de statut.
3. Cochez les 3 temporaires. La barre d'actions groupées propose **Activer**, **Désactiver**, **Archiver**, **Restaurer** et **Supprimer définitivement**.
   - **Désactiver**, puis filtre *Inactif* ✔ 3 résultats, puis **Activer**.
   - **Archiver** ✔ les scénarios disparaissent de la liste et sont visibles avec le filtre des archivés. Puis **Restaurer**.
   - **Supprimer définitivement** ✔ la fenêtre d'impact affiche le total d'étapes, d'exécutions et de planifications, puis un résultat par scénario.

## 9. Pendant une exécution, puis annulation (limite connue)

1. Lancez `DEMO — Parcours bancaire` (20 s).
2. Pendant l'exécution, essayez :
   - d'**Enregistrer** une modification → ✔ refus 409 ;
   - de **Désactiver** → ✔ refus 409 ;
   - de **Supprimer définitivement** → ✔ refus 409.
3. Après environ 5 s : **Annuler l'exécution**, puis confirmez.
   - ✔ Le statut passe à **CANCELLED**.
   - ⚠ **Limite connue** : les résultats déjà mesurés ne sont **pas conservés**. Le compteur en direct affichait N requêtes, mais le détail est vide.

> Cette partie n'a pas été répétée (arrêt de la répétition, voir l'anomalie A3).

## 10. Nettoyage

- Supprimez les scénarios `DEMO — …` (action groupée **Supprimer définitivement**).
- `POST http://localhost:8888/api/reset`.

---

## Limites connues à rappeler pendant la démo

| Limite | Effet |
|---|---|
| Pas d'échappement JSON des valeurs injectées | un `"` ou un `\` dans le CSV casse le corps JSON |
| JSONPath sans index de tableau | `$.items[0].id` n'est pas pris en charge, seulement `$.a.b` |
| Annulation | les résultats partiels sont perdus |
| VU, itération et URL finale | présents dans l'API, pas encore affichés dans le détail d'une exécution |
| Timeout, think time et redirections par étape | gérés par le backend, pas modifiables dans l'éditeur d'étape |

## Fiche de résultats (à remplir pendant votre démo)

| Partie | Attendu | Obtenu | OK ? |
|---|---|---|---|
| 0.3 Application | CONNECTED | | |
| 1.2 Brouillon non exécutable | refus | | |
| 1.3 Suivant grisé si invalide | oui | | |
| 1.4 Import CSV | 12 lignes, 10 colonnes | | |
| 2 Tester | 9/9 | | |
| 2 Variable inconnue | échec avant envoi | | |
| 3 Exécution par VU | lignes 1 à 3 seulement | | |
| 3 Exécution par itération | lignes 1 à 12 | | |
| 3 Isolation transferId | 0 mélange | | |
| 4 Modification | Modifié / Enregistrer / Tester | | |
| 5 users.csv | SUCCESS, 12 requêtes | | |
| 6 users-invalid.csv | SUCCESS, 3 × 401 | | |
| 7 INACTIF | refus, planif. suspendue puis réactivée | | |
| 8 Actions groupées | succès par scénario | | |
| 9 409 pendant l'exécution | 3 refus | | |
| 9 Annulation | CANCELLED, résultats perdus | | |
