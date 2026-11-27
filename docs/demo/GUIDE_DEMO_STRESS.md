# Guide de démonstration — mode « Point de rupture » (stress automatique)

Démonstration guidée, à dérouler dans LoadPilot : un scénario en mode « Point de rupture » contre banking-test-api, à qui l'on donne une capacité limitée pour que la rupture soit visible en quelques minutes.

- **Partie A** : préparer la cible avec une capacité simulée.
- **Partie B** : créer l'application et le scénario, sans aucun réglage de charge.
- **Partie C** : lancer et suivre la montée en direct.
- **Partie D** : lire le résultat (rapport, graphique, PDF).
- **Partie E** : variantes et règles du mode (aucune rupture, test seul, pas de pause, pas de planification).

Durée : environ 20 minutes, dont 2 à 3 minutes de test. Toutes les données sont fictives.

## Prérequis

| Composant | Commande | Vérification |
|---|---|---|
| PostgreSQL, Keycloak | `docker compose up -d postgres keycloak` | conteneurs `loadpilot-db` et `keycloak` démarrés |
| Backend LoadPilot (version avec le mode stress) | `cd backend; .\start.ps1` | journal : « Started BackendApplication » |
| Frontend | `cd perftest-frontend; npm run dev` | http://localhost:3000 |

- Compte LoadPilot : **Testeur** ou **Super administrateur** (Super administrateur pour la variante E1).
- **Aucune autre exécution en cours** : un test de stress tourne seul sur le serveur.
- Pour une longue session de démo, désactiver la mise en veille automatique du poste : une veille fige le test et fausse le résultat.

---

## Partie A — La cible avec une capacité simulée

Sans réglage, banking-test-api tient bien plus que les plafonds de LoadPilot (500 req/s par défaut) : le test finirait par « Aucune rupture ». La variable `CAPACITY_RPS` lui donne une capacité, comme un vrai serveur saturé :

- jusqu'à `CAPACITY_RPS` requêtes par seconde : réponses normales (100 à 300 ms) ;
- au-delà : chaque requête attend 200 ms de plus par tranche de 10 % de dépassement (+1 s à 150 %) ;
- au-delà du double : réponse `503` immédiate.

```powershell
cd banking-test-api
$env:CAPACITY_RPS = '50'; $env:ERROR_RATE = '0'; npm start
```

Le démarrage affiche : « Capacité simulée : 50 req/s, puis ralentissement et 503 au-delà du double (CAPACITY_RPS) ». `ERROR_RATE=0` retire les erreurs aléatoires, qui ne concernent de toute façon que les écritures.

## Partie B — Application et scénario

### B1. Application « Démo stress »

**Applications > Ajouter une application** :

| Champ | Valeur |
|---|---|
| Nom | `Démo stress` |
| URL | `http://localhost:8888` |
| Authentification | **Jeton Bearer** : `TEST-TOKEN-123` ; chemin de vérification `/api/account/balance` |
| Réseau | « Aucun proxy » (valeur par défaut) |
| Monitoring | Prometheus, `http://localhost:8888/metrics`, 5 s (facultatif : CPU et RAM de la cible sur le graphique) |

Enregistrer, puis **Tester** : ✔ authentification acceptée.

### B2. Scénario « Démo — point de rupture »

**Scénarios > Nouveau scénario**, application `Démo stress`.

| Étape | Méthode et route | Validation |
|---|---|---|
| Solde du compte | `GET /api/account/balance` | code `200` |

Une seule étape **en lecture** : pas d'écriture en base, rien à remettre à zéro entre deux répétitions.

Étape **Tester** : ✔ l'étape est verte.

### B3. Étape « Charge & résumé »

Choisir le mode **« Point de rupture »**. Points à montrer :

- ✔ plus aucun champ de charge : ni profil, ni utilisateurs, ni durée, ni débit, ni seuil de réussite ;
- ✔ l'encadré explique la montée (jusqu'aux limites réelles du serveur, ex. « jusqu'à 500 req/s, 200 utilisateurs virtuels ou 1 h de test »), les critères de rupture et les résultats possibles ;
- ✔ le résumé à droite affiche « Auto / Rupture / +1 req/s par seconde » ;
- ✔ « Réglage avancé : vitesse de montée » reste replié et vide : +1 req/s par seconde, la vitesse la plus précise (rupture située à ± 5 req/s près). Une vitesse plus haute (jusqu'à 50) sert aux applications robustes, pour atteindre plus vite un débit élevé.

Enregistrer le scénario. Dans la liste des scénarios, le détail affiche « Point de rupture : montée automatique ».

## Partie C — Lancer et suivre la montée

**Lancer un test** → `Démo — point de rupture`. La fenêtre de lancement indique « Point de rupture : montée automatique, test seul sur le serveur ». Le suivi en direct s'ouvre.

| Moment | Ce qu'on voit dans le suivi en direct |
|---|---|
| 0 à 10 s | phase **Échauffement**, 2 req/s offertes, 1 utilisateur virtuel |
| 10 à 40 s | phase **Mesure de référence** ; à 40 s, le **P95 de référence** s'affiche (≈ 300 ms) |
| à partir de 40 s | phase **Montée** : +1 req/s chaque seconde, les utilisateurs virtuels s'ajoutent au besoin |
| vers 1 min 40 s | la cible dépasse sa capacité : les temps de réponse montent, le débit obtenu décroche |
| ≈ 1 min 55 s | rupture confirmée (3 fenêtres de 5 s) : le test **s'arrête seul** |

À montrer pendant la montée :

- pas de pourcentage de progression : la fin dépend de l'application, la barre affiche la phase (« Montée · 57 req/s offertes ») ;
- pas de bouton **Pause** (voir E3) ;
- le bloc « Injecteur (LoadPilot) » : si LoadPilot saturait avant la cible, le test finirait en « Annulée — non concluant » au lieu d'une fausse rupture.

Mesure de référence de ce guide (une répétition, capacité 50 req/s) : rupture détectée **vers 63 req/s offertes et 33 utilisateurs virtuels**, à 1 min 40 s, confirmée à 1 min 55 s, critère « temps de réponse (P95) » (942 ms, 3,1 × la référence de 308 ms). Les chiffres varient d'une répétition à l'autre : à confirmer avant la démo.

## Partie D — Lire le résultat

Fin du test : statut **« Seuils dépassés »**, raison sous le badge « Point de rupture : 63 req/s ».

**Voir le rapport** :

- encadré **POINT DE RUPTURE** : critère(s), début de l'anomalie et confirmation, utilisateurs virtuels, débit offert et obtenu, vitesse de montée et précision (± 5 req/s), P95 comparé à la référence, taux de refus ;
- le niveau de charge rapporté est celui du **début** de l'anomalie, pas celui du moment où le test s'est arrêté ;
- graphique « Utilisateurs, débit et temps de réponse » : **zone verte** = mesure de référence, **ligne orange** = début de la rupture, **ligne rouge** = confirmation ;
- export **PDF** : mêmes informations, repères sur les graphiques, conclusion qui commence par le point de rupture.

## Partie E — Variantes et règles du mode

### E1. Aucune rupture jusqu'aux limites du serveur

1. Super administrateur : **Paramètres > Système > Limites**, débit cible maximal à **100** req/s.
2. Redémarrer banking-test-api **sans** capacité : `Remove-Item Env:CAPACITY_RPS; npm start`.
3. Relancer le scénario : la montée atteint 100 req/s (≈ 2 min 20 s), maintient ce débit 30 s, puis s'arrête.

Résultat : **« Réussie »**, note « Aucune rupture détectée jusqu'à X VU / 100 req/s offertes (limite de LoadPilot atteinte : débit cible maximal) ».

Remettre ensuite le débit cible maximal à sa valeur d'origine.

### E2. Un test de stress tourne seul

Pendant un test de stress, lancer n'importe quel autre scénario : refus « Un test de stress est en cours : il tourne seul sur le serveur. Réessayez quand il sera terminé. » À l'inverse, un test de stress est refusé tant qu'une autre exécution tourne.

### E3. Pas de pause

Le bouton Pause n'apparaît pas. Une pause fausserait la comparaison à la mesure de référence : un test de stress se termine ou s'annule.

### E4. Pas de planification

**Planification > Nouvelle planification** : les scénarios en mode « Point de rupture » ne sont pas proposés. Un scénario déjà planifié ne peut pas passer en mode stress tant qu'il a des planifications.

## Fin de la démo

```powershell
# banking-test-api : revenir au comportement par défaut
Remove-Item Env:CAPACITY_RPS; Remove-Item Env:ERROR_RATE; npm start
```

| Symptôme | Cause probable |
|---|---|
| « Échouée » avec « Baseline impossible » | la cible ne répond pas à 2 req/s pendant la mesure de référence (arrêtée, URL ou jeton faux) |
| « Réussie », aucune rupture, alors qu'une rupture était attendue | banking-test-api lancé sans `CAPACITY_RPS` |
| Lancement refusé (429) | une autre exécution tourne encore : attendre sa fin |
| « Annulée — non concluant » | LoadPilot a saturé avant la cible : fermer les applications gourmandes du poste, ou baisser `CAPACITY_RPS` |
