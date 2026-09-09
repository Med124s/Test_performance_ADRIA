# Cadence

Cadence est une plateforme de test de charge (performance testing) : on y crée des **scénarios** (suites d'étapes HTTP), on les **exécute** (plusieurs utilisateurs virtuels, vraies requêtes HTTP) et on en consulte les **résultats** (succès/échec par étape, temps de réponse, rapports).

Le projet est composé de **trois applications indépendantes** à lancer séparément :

| Dossier | Rôle | Port par défaut |
|---|---|---|
| `perftest-frontend/` | L'application Cadence elle-même (React + Vite) | `3000` |
| `perftest-frontend/server/` (lancé via un script npm) | Fausse API interne à Cadence (applications, scénarios, exécutions...) — sert de "base de données" | `4001` |
| `banking-test-api/` | Serveur bancaire fictif, **cible réelle** pour tester Cadence (login, virements, etc.) | `8000` |
| `perftest-frontend/local-monitoring-server/` | *(optionnel)* Expose le vrai CPU/RAM de la machine locale, pour le monitoring serveur dans les rapports | `5000` |

Rien de tout ça n'est une vraie banque ou un vrai backend de production : toutes les données sont fictives, générées pour tester Cadence lui-même.

## Prérequis

- **Node.js 22.5 ou supérieur** (le serveur `banking-test-api` utilise `node:sqlite`, un module natif disponible seulement à partir de Node 22.5).
- npm (fourni avec Node).

Vérifier sa version :
```bash
node --version
```

## Installation

À faire une seule fois, dans **chacun** des deux dossiers principaux (une deuxième installation se déclenche automatiquement pour `local-monitoring-server` si besoin, voir plus bas) :

```bash
cd perftest-frontend
npm install

cd ../banking-test-api
npm install
```

## Démarrage rapide (recommandé)

Deux terminaux suffisent pour tout avoir en marche :

**Terminal 1 — Cadence (interface + sa base de données interne) :**
```bash
cd perftest-frontend
npm run dev:all
```
Ceci lance en parallèle :
- l'interface sur **http://localhost:3000**
- la fausse API interne sur **http://localhost:4001**

**Terminal 2 — Le serveur bancaire de test (cible des scénarios) :**
```bash
cd banking-test-api
npm run dev
```
Démarre sur **http://localhost:8000** (mode `--watch` : redémarre tout seul si le code change).

Une fois les deux terminaux lancés, ouvrir **http://localhost:3000** dans le navigateur (si le port 3000 est déjà occupé, Vite choisit automatiquement le suivant disponible et l'indique dans le Terminal 1 — regarder la ligne `Local:` affichée).

## Se connecter

L'écran de connexion ne vérifie aucun vrai compte (aucun backend d'authentification n'est branché) :
- **Email** : n'importe quelle adresse valide (ex. `admin@perftest.com`, déjà pré-remplie)
- **Mot de passe** : n'importe quoi (4 caractères minimum)
- Les cartes **"Admin" / "Testeur" / "Visiteur"** sous le formulaire remplissent le formulaire automatiquement pour choisir un rôle en un clic.

## Premier test de bout en bout

1. Aller dans **Applications** → **Nouvelle application**, et déclarer une application pointant vers `http://localhost:8000` (le serveur bancaire de test lancé au Terminal 2). Lui donner le nom **exact** `Banking Test API` pour bénéficier des scénarios prêts à l'emploi.
2. Aller dans **Scénarios** → **Nouveau scénario** → choisir cette application : une liste de **scénarios recommandés** apparaît (Authentification, Consultation solde, Transfert national/international, Modification/Suppression de transfert, Réinitialisation des données...). Cliquer **"Utiliser ce scénario"** sur celui de son choix.
3. Revenir à la liste des **Scénarios** et cliquer sur le bouton ▶ **"Exécuter le scénario"**.
4. Aller dans **Exécutions** pour suivre le résultat en direct, puis cliquer sur l'icône 👁 pour voir le détail complet (statut de chaque étape, code HTTP, temps de réponse, cause d'un échec éventuel).

## Lancer chaque service séparément (alternative à `dev:all`)

```bash
# Interface uniquement
cd perftest-frontend
npm run dev            # http://localhost:3000

# Fausse API interne uniquement
cd perftest-frontend
npm run server          # http://localhost:4001

# Serveur bancaire de test
cd banking-test-api
npm run dev              # http://localhost:8000 (redémarre seul sur changement)
# ou, sans redémarrage automatique :
npm start
```

## Monitoring serveur local (optionnel)

Pour que les rapports d'exécution affichent du vrai CPU/RAM de la machine (au lieu de rien), un service séparé peut être lancé (installation à faire une seule fois, séparément des deux dossiers précédents) :

```bash
cd perftest-frontend/local-monitoring-server
npm install               # une seule fois

cd ..
npm run monitor            # http://localhost:5000
```
Puis, dans la fiche d'une **Application**, renseigner son URL de monitoring (`http://localhost:5000/server-metrics`).

## Build de production (Cadence)

```bash
cd perftest-frontend
npm run build            # génère perftest-frontend/dist
npm run preview          # sert le build généré, pour vérification locale
```
`banking-test-api` n'a pas de build : `npm start` suffit pour le lancer tel quel.

## Dépannage

- **"Port already in use" / port déjà utilisé** : un service tourne déjà sur ce port (peut-être depuis un terminal oublié). Fermer le terminal concerné, ou identifier puis arrêter le processus qui occupe le port avant de relancer.
- **Une étape de scénario échoue avec une erreur réseau/CORS** : vérifier que `banking-test-api` (Terminal 2) est bien démarré et que l'application créée dans Cadence pointe bien vers `http://localhost:8000`.
- **Réinitialiser les données du serveur bancaire de test** : soit exécuter le scénario recommandé "Réinitialisation des données de test" (`POST /api/reset`) depuis Cadence, soit arrêter `banking-test-api` et supprimer le fichier `banking-test-api/banking.db` avant de le relancer (il est recréé automatiquement, données 100% fictives).
- **Réinitialiser les données de Cadence** (applications/scénarios/exécutions) : arrêter `npm run server`/`dev:all`, puis vider les tableaux voulus dans `perftest-frontend/db.json` (garder un JSON valide, ex. `"executions": []`) avant de relancer.
