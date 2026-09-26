# PostgreSQL — reproductibilité locale

Ce document explique comment provisionner, sur une machine de développement,
une instance PostgreSQL réelle compatible avec le backend Spring Boot
(`backend/src/main/resources/application-dev.yml`). Il ne contient **aucun
mot de passe réel** : uniquement des valeurs de convention (placeholders) à
remplacer par vos propres secrets locaux, jamais commités.

## Ce qui est PROUVÉ vs ce qui est une convention

- **PROUVÉ** (validé en conditions réelles lors d'une phase antérieure) :
  PostgreSQL **17.5** (binaires officiels EnterpriseDB, distribution
  "binaries" Windows x64, sans installeur) démarre, accepte l'authentification
  par mot de passe sur TCP, et le backend Spring Boot applique avec succès les
  15 changesets Liquibase sur une base fraîche via ce profil.
- **Convention** : les noms de rôle/base (`loadpilot`/`loadpilot`) sont ceux
  attendus par défaut par `application-dev.yml` — vous pouvez utiliser
  d'autres noms tant que vous surchargez `DB_URL`/`DB_USERNAME` en
  conséquence.

## 1. Prérequis

- Une machine Windows/Linux/macOS avec suffisamment d'espace disque
  (~350 Mo pour les binaires + l'espace de données).
- Aucun autre service n'écoutant déjà sur le port `5432` (ou choisissez un
  autre port et adaptez `DB_URL`).
- Le backend LoadPilot ne requiert **aucune extension PostgreSQL
  particulière** (pas de PostGIS, pas de pgvector) : une installation
  "stock" suffit.

## 2. Installation

Deux approches possibles, au choix :

### Option A — Gestionnaire de paquets de votre OS

Utilisez le paquet PostgreSQL 17.x de votre distribution/gestionnaire habituel
(`apt`, `brew`, etc.) si votre environnement en dispose. C'est l'option la
plus simple quand elle est disponible.

### Option B — Binaires portables (sans droits administrateur), utilisée pour la validation réelle de ce projet

1. Téléchargez l'archive des binaires PostgreSQL 17.x pour votre plateforme
   (par ex. `postgresql-17.x-...-binaries.zip` pour Windows) depuis une
   source officielle PostgreSQL/EnterpriseDB.
2. Extrayez l'archive dans un dossier local (ex. `~/postgresql-local-dev/`).
3. Initialisez un nouveau cluster de données (une seule fois) :
   ```
   <dossier_extrait>/bin/initdb.exe -D <dossier_extrait>/data -U postgres -W
   ```
   `-W` vous demande de définir un mot de passe superutilisateur local —
   choisissez-en un et conservez-le uniquement sur votre poste (par exemple
   dans un fichier `.pg_superuser_pw_do_not_commit` **hors du dépôt Git**, ou
   dans votre gestionnaire de secrets habituel). Ne le commitez jamais.

## 3. Démarrage

```
<dossier_extrait>/bin/postgres.exe -D <dossier_extrait>/data -p 5432
```

(sur Linux/macOS, l'exécutable équivalent est généralement `postgres` ou se
lance via `pg_ctl start`).

Laissez ce processus tourner tant que vous développez/testez le backend.

## 4. Création du rôle et de la base `loadpilot`

Une fois le serveur démarré, connectez-vous en tant que superutilisateur pour
créer le rôle applicatif et sa base, puis choisissez un mot de passe pour ce
rôle (différent du mot de passe superutilisateur) :

```sql
-- via psql.exe -U postgres -h localhost
CREATE ROLE loadpilot WITH LOGIN PASSWORD '<mot-de-passe-local-a-choisir>';
CREATE DATABASE loadpilot OWNER loadpilot;
```

Conservez ce mot de passe uniquement en local (par exemple dans un fichier
`.pg_loadpilot_pw_do_not_commit` **hors du dépôt Git**, jamais versionné, ou
dans votre gestionnaire de secrets habituel) — ne le placez jamais dans
`application-dev.yml`, dans `.env`, ni dans aucun fichier suivi par Git.

### Pourquoi pas un script SQL fourni dans ce dossier ?

Volontairement absent : un script `.sql` figé devrait soit contenir un mot de
passe en clair (inacceptable), soit deviner un mécanisme de saisie
interactive/de variables d'environnement spécifique à votre shell (risque de
mal fonctionner silencieusement selon l'OS). La procédure `psql` ci-dessus,
exécutée une fois manuellement, est plus sûre et tout aussi rapide qu'un
script pour une opération qui n'a lieu qu'une seule fois par poste de
développement.

## 5. Variables d'environnement attendues par le backend

Le profil `dev` (`backend/src/main/resources/application-dev.yml`) lit ces
variables d'environnement (valeurs par défaut entre parenthèses quand elles
existent) :

| Variable      | Rôle                                             | Valeur par défaut si absente            |
|---------------|---------------------------------------------------|------------------------------------------|
| `DB_URL`      | URL JDBC complète de la base                      | `jdbc:postgresql://localhost:5432/loadpilot` |
| `DB_USERNAME` | Rôle PostgreSQL applicatif                        | `loadpilot`                              |
| `DB_PASSWORD` | Mot de passe de ce rôle                           | **aucune** (chaîne vide, volontaire — voir commentaire dans `application-dev.yml`) |

`DB_PASSWORD` n'a délibérément aucune valeur par défaut dans le code : sans
la définir explicitement, le démarrage échouera à l'authentification
PostgreSQL plutôt que de fonctionner "par accident" avec un mot de passe
deviné ou trivial.

Exemple (à définir dans votre shell avant de lancer le backend, jamais dans
un fichier suivi par Git) :

```
# PowerShell
$env:DB_URL = "jdbc:postgresql://localhost:5432/loadpilot"
$env:DB_USERNAME = "loadpilot"
$env:DB_PASSWORD = "<votre-mot-de-passe-local>"

# bash
export DB_URL="jdbc:postgresql://localhost:5432/loadpilot"
export DB_USERNAME="loadpilot"
export DB_PASSWORD="<votre-mot-de-passe-local>"
```

## 6. Liquibase — ne jamais créer les tables manuellement

Le schéma est **entièrement géré par Liquibase**
(`backend/src/main/resources/db/changelog/`), appliqué automatiquement au
démarrage de Spring Boot (`spring.liquibase.enabled: true`). Hibernate est en
mode `ddl-auto: validate` : il ne crée ni ne modifie jamais de table, il
vérifie seulement que les entités JPA correspondent au schéma déjà migré.

**Ne créez jamais de table à la main** dans la base `loadpilot` : laissez
toujours Liquibase appliquer ses changesets sur une base vide (ou déjà
migrée par une exécution précédente).

## 7. Vérification

1. Le port est bien en écoute :
   ```
   # PowerShell
   Test-NetConnection -ComputerName localhost -Port 5432
   # bash
   (echo > /dev/tcp/127.0.0.1/5432) && echo "5432 ouvert"
   ```
2. Démarrez le backend (`mvn spring-boot:run` avec les variables d'environnement
   ci-dessus définies) et observez dans les logs :
   - des lignes `liquibase` confirmant l'application des changesets (une
     seule fois sur une base vide — les exécutions suivantes doivent
     afficher qu'aucun changeset n'est "outstanding" si rien n'a changé) ;
   - aucune erreur `FATAL: password authentication failed` ni
     `Connection refused`.
3. Appelez `GET http://localhost:8080/actuator/health` : une réponse
   `{"status":"UP"}` confirme que le backend est connecté à la base.

## 8. Ordre de démarrage recommandé

1. PostgreSQL (ce document)
2. Keycloak (voir `keycloak/README.md`)
3. Backend Spring Boot (`mvn spring-boot:run`, profil `dev`)
4. Frontend (`npm run dev` dans `perftest-frontend/`)

Le backend interroge Keycloak au démarrage (`.well-known/openid-configuration`
sur `KEYCLOAK_ISSUER_URI`) pour construire son `JwtDecoder` : s'il démarre
avant Keycloak, il refusera de démarrer. PostgreSQL doit lui aussi être déjà
accessible, sans quoi Liquibase échouera à se connecter.

## 9. Dépannage

| Symptôme | Cause probable | Action |
|---|---|---|
| `Connection refused` sur `5432` | PostgreSQL n'est pas démarré, ou démarré sur un autre port | Vérifiez le processus `postgres`/`postgres.exe`, le port utilisé au lancement |
| `FATAL: password authentication failed for user "loadpilot"` | `DB_PASSWORD` incorrect ou non défini | Redéfinissez la variable d'environnement avec le mot de passe choisi à l'étape 4 |
| `FATAL: database "loadpilot" does not exist` | La base n'a pas été créée (étape 4 sautée) | Reconnectez-vous en superutilisateur et exécutez `CREATE DATABASE loadpilot OWNER loadpilot;` |
| `FATAL: role "loadpilot" does not exist` | Le rôle n'a pas été créé (étape 4 sautée) | Exécutez `CREATE ROLE loadpilot WITH LOGIN PASSWORD '...';` |
| Échec Liquibase (`liquibase.exception...`) au démarrage | Schéma partiellement appliqué par une exécution interrompue, ou base non vide provenant d'un autre usage | Inspectez la table `databasechangelog` dans la base `loadpilot` ; en développement local, le plus simple est souvent de repartir d'une base vide dédiée (jamais une base de production) |
| Port `5432` déjà occupé par un autre PostgreSQL | Une autre instance (locale ou système) écoute déjà | Arrêtez l'autre instance, ou changez de port ici et mettez à jour `DB_URL` en conséquence |
