# Parcours bancaire complet (7 étapes)

Scénario de test de charge sur **banking-test-api** (`http://localhost:8888`), l'API bancaire fictive du dépôt. Il rejoue la journée type d'un agent bancaire, dans l'ordre :

1. Authentification
2. Consultation du solde et de la commission
3. Consultation de l'historique de l'agent
4. Transfert international
5. Transfert national
6. Paiement de facture
7. Versement client

Variante plus courte, sans écriture en base : `GUIDE_DEMO_CAPTURES.md`.

---

## Partie A — Les 7 endpoints de l'application cible

Tous existent dans `banking-test-api/src/index.js`. Données 100 % fictives (20 agents, 20 comptes, 6 000 factures), stockées dans un fichier SQLite local. Chaque route attend réellement une latence simulée avant de répondre :

- **100 à 300 ms** pour toutes les routes ;
- **400 à 900 ms** pour le transfert international (contrôles de conformité et réseau interbancaire simulés, voir `latencyConfig.routes` dans `src/config.js`).

Les étapes 2 à 7 exigent l'en-tête `Authorization: Bearer <token>`, sinon elles répondent `401`.

| # | Étape | Méthode et route | Corps envoyé (exemple) | Réponse (exemple réel) |
|---|---|---|---|---|
| 1 | Authentification | `POST /api/auth/login` | `{"username":"agent018","password":"test018"}` | `200` `{"success":true,"token":"TOKEN-xdohy…","user":{"id":"AG018","role":"agent"}}` |
| 2 | Solde et commission | `GET /api/account/balance` | — | `200` `{"success":true,"account":"ACC018","balance":36000,"currency":"MAD","commission":2.5}` |
| 3 | Historique de l'agent | `GET /api/agent/history` | — | `200` `{"success":true,"transactions":[{"id":"…","type":"transfer","amount":376,"currency":"MAD","status":"completed","date":"2026-10-26"}]}` |
| 4 | Transfert international | `POST /api/transfers/international` | `{"sourceAccount":"ACC018","destinationAccount":"FR7630006000011234567890189","amount":376,"currency":"MAD","destinationCountry":"FR"}` | `201` `{"success":true,"transferId":"TRF002","kind":"international",…,"status":"completed"}` |
| 5 | Transfert national | `POST /api/transfers/national` | `{"sourceAccount":"ACC018","destinationAccount":"ACC010","amount":376,"currency":"MAD"}` | `201` `{"success":true,"transferId":"TRF003","kind":"national",…,"status":"completed"}` |
| 6 | Paiement de facture | `POST /api/bills/pay` | `{"clientId":"CL018","billNumber":"FAC-2026-03278","amount":773.86}` | `201` `{"success":true,"paymentId":"BILL001",…,"status":"paid"}` |
| 7 | Versement client | `POST /api/deposits` | `{"account":"ACC018","amount":182}` | `201` `{"success":true,"depositId":"DEP001","account":"ACC018","amount":182,"status":"completed"}` |

**Règles métier à connaître :**

- **Virements** : le virement débite vraiment le compte source (solde insuffisant → `422`). Le national crédite le destinataire ; l'international part vers un IBAN externe et ne crédite rien ici.
- **Factures** : une facture ne se paie **qu'une fois**. La deuxième fois, la réponse est `409 Facture déjà payée`. C'est pour cela que le scénario prend une ligne de données différente à chaque itération (partie B) et que les données sont **remises à zéro avant chaque test** (partie C).
- **Erreurs aléatoires** : par défaut, 7 % d'erreurs 409/500 sont tirées au hasard sur les étapes 4 à 7. Pour un test sans ces erreurs, démarrer la cible avec `ERROR_RATE=0`.

---

## Partie B — Créer le scénario dans LoadPilot

### B1. L'application

**Applications > Ajouter une application** :

| Champ | Valeur |
|---|---|
| Nom | `Banque démo — parcours complet` |
| URL | `http://localhost:8888` |
| Authentification | **Aucune**. C'est le scénario lui-même qui se connecte, chaque utilisateur virtuel avec son propre agent : le jeton doit vraiment passer de l'étape 1 aux suivantes. |
| Réseau | « Aucun proxy » (valeur par défaut) |
| Monitoring | Prometheus, `http://localhost:8888/metrics`, toutes les 5 s |

Enregistrer, puis **Tester** (✔ disponible) et **Tester le monitoring** (✔ opérationnel).

### B2. Le scénario, étape « Informations »

**Scénarios > Nouveau scénario**, application `Banque démo — parcours complet`, nom `Démo — Parcours bancaire complet`.

### B3. Étape « Étapes »

**Jeu de données CSV** : **Importer un fichier** → `docs/samples/parcours-bancaire-5000.csv` (5 000 lignes, colonnes `username, password, clientId, sourceAccount, destinationAccount, amount, …, billNumber, billAmount, depositAccount, depositAmount`). Distribution : **« Une nouvelle ligne à chaque itération »**.

Puis **Ajouter une étape**, sept fois. Pour toutes les étapes `POST` : en-tête `Content-Type: application/json`. Pour les étapes 2 à 7 : en-tête `Authorization: Bearer ${userToken}`.

| # | Nom | Paramètres | Corps | Validation : code et « la réponse doit contenir » | Variables |
|---|---|---|---|---|---|
| 1 | Authentification | `POST` `/api/auth/login` | `{"username":"${username}","password":"${password}"}` | `200`, `"token"` | **Corps JSON**, variable `userToken`, chemin `$.token` |
| 2 | Solde et commission | `GET` `/api/account/balance` | — | `200`, `"commission"` | — |
| 3 | Historique agent | `GET` `/api/agent/history` | — | `200`, `"transactions"` | — |
| 4 | Transfert international | `POST` `/api/transfers/international` | `{"sourceAccount":"${sourceAccount}","destinationAccount":"FR7630006000011234567890189","amount":${amount},"currency":"MAD","destinationCountry":"FR"}` | `201`, `"completed"` | — |
| 5 | Transfert national | `POST` `/api/transfers/national` | `{"sourceAccount":"${sourceAccount}","destinationAccount":"${destinationAccount}","amount":${amount},"currency":"MAD"}` | `201`, `"completed"` | — |
| 6 | Paiement facture | `POST` `/api/bills/pay` | `{"clientId":"${clientId}","billNumber":"${billNumber}","amount":${billAmount}}` | `201`, `"paid"` | — |
| 7 | Versement client | `POST` `/api/deposits` | `{"account":"${depositAccount}","amount":${depositAmount}}` | `201`, `"completed"` | — |

**Propagation du jeton** : l'étape 1 capture le jeton renvoyé par la connexion (`$.token`) dans la variable `userToken`. Les étapes 2 à 7 l'envoient dans l'en-tête `Authorization`. Le nom contient « token » : LoadPilot masque sa valeur (`••••••`) dans les résultats de test. Ne pas nommer la variable `authToken` : ce nom est réservé au jeton de la « Connexion auto » d'une application.

### B4. Étape « Tester »

**Lancer le test** (1 utilisateur, 1 passage) : ✔ les 7 étapes sont vertes, et l'étape 1 montre `userToken` capturé (masqué).

> ⚠ Ce test **paie réellement une facture** de la première ligne du CSV. Il faut donc remettre les données à zéro avant le test de charge (partie C).

Contre-test : retirer l'en-tête `Authorization` de l'étape 2 et relancer → étape 2 en échec **401** « Authentification requise ». Remettre ensuite l'en-tête.

### B5. Étape « Charge & résumé »

| Réglage | Valeur | Pourquoi |
|---|---|---|
| Utilisateurs virtuels | **200** | le plafond par test |
| Montée en charge | **30 s** | arrivée progressive des utilisateurs |
| Itérations | **25** par utilisateur | 200 × 25 = **5 000** parcours, exactement le nombre de lignes du CSV : chaque facture n'est payée qu'une fois |
| Durée | **300 s** | filet de sécurité : le test s'arrête à la première limite atteinte |
| Think time | **4 000 ms** | la pause entre deux parcours, comme un agent qui saisit le suivant |
| Taux d'erreurs maximal | **1 %** | |
| P95 maximal | **1 500 ms** | le transfert international tire le P95 global vers 900 ms : 1 500 ms laisse de la marge |

Variante si le CPU de l'application dépasse 90 % ou si des erreurs apparaissent : **100 utilisateurs × 50 itérations**, toujours 5 000 parcours, donc toujours compatible avec le CSV.

**Ordres de grandeur attendus** (estimation, à confirmer par un test réel) :

- un parcours ≈ 6 × 200 ms + 650 ms + 4 s de pause ≈ **5,9 s** ;
- **≈ 240 req/s** au plateau ;
- **35 000 requêtes** au total ;
- le test dure **≈ 3 minutes** ;
- **0 % d'erreurs** avec `ERROR_RATE=0`.

Enregistrer le scénario.

---

## Partie C — Préparer la cible avant chaque test

```powershell
# Application cible sans erreurs aléatoires
cd banking-test-api
$env:ERROR_RATE = '0'; npm start          # affiche « Erreurs aléatoires : 0 % de 409, 0 % de 500 »

# Remise à zéro des données de démo (factures impayées, soldes d'origine).
# À la main uniquement, JAMAIS dans un scénario de charge.
curl.exe -s -X POST http://localhost:8888/api/reset

# Vérifications
curl.exe -s http://localhost:8888/health                  # {"status":"ok"}
curl.exe -s http://localhost:8080/actuator/health         # {"status":"UP"}
```

### Diagnostic des échecs courants

| Symptôme | Cause probable |
|---|---|
| Erreurs 409 sur « Paiement facture » | Remise à zéro oubliée : les factures déjà payées par un test précédent renvoient toutes 409. Appeler `/api/reset` puis relancer. |
| Erreurs 409 ou 500 réparties sur les étapes 4 à 7 | La cible tourne avec les erreurs aléatoires : la redémarrer avec `$env:ERROR_RATE = '0'`. |
| Toutes les étapes 2 à 7 en 401 | L'en-tête `Authorization: Bearer ${userToken}` manque, ou la capture de l'étape 1 est mal configurée. |

Pour obtenir volontairement un verdict **« Seuils dépassés »** : démarrer la cible **avec** ses erreurs aléatoires (`Remove-Item Env:ERROR_RATE; npm start`), appeler `/api/reset`, puis relancer le test (≈ 4 % d'erreurs, au-dessus du seuil de 1 %).
