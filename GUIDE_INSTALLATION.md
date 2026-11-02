# Guide d'installation de LoadPilot sur une nouvelle machine

Ce guide part d'une machine **vierge** (rien d'installé pour LoadPilot) et va jusqu'à l'application ouverte dans le navigateur, avec un compte administrateur.

Deux façons de faire tourner LoadPilot :

| | [Partie A : Docker Compose](#partie-a--docker-compose-tout-conteneurisé) | [Partie B : sans Docker](#partie-b--sans-docker-un-terminal-par-service) |
|---|---|---|
| À installer | Docker Desktop, Git | Java 21, Maven, Node.js, PostgreSQL, Keycloak, Git |
| Durée (1re fois) | 20 à 30 min (dont 5 à 15 min de build) | 45 min à 1 h |
| Recommandé pour | une démo, une installation rapide | le développement (rechargement à chaud du frontend) |

Les commandes sont écrites pour **Windows 10/11, dans PowerShell** (pas l'invite `cmd`). Copiez-les telles quelles. Toutes les commandes partent du **dossier racine du dépôt** (`Test_performance_ADRIA`), sauf indication contraire.

> **Ce qui a été vérifié (2 novembre 2026).**
> - **Partie A**, rejouée de bout en bout sur une stack Compose neuve, avec les commandes de ce guide copiées telles quelles dans PowerShell :
>   - génération du `.env`, puis `docker compose up -d --build` ;
>   - import du realm avec le secret du `.env`, accepté par Keycloak sans autre manipulation ;
>   - création du premier compte avec `kcadm`, et des tables par Liquibase sur une base vide ;
>   - appels à l'API avec un vrai jeton Keycloak : 200 avec un jeton valide (dont `/api/users`) ; 401 sans jeton, avec un jeton altéré ou avec un jeton d'un autre émetteur ;
>   - après `docker compose down` puis `up -d`, le compte existe toujours (volume Keycloak).
>
>   Le test tournait sur d'autres ports pour ne pas gêner l'installation existante : la connexion dans le navigateur sur `http://localhost:3000` reste à confirmer à la première installation.
> - **Partie B** : le lancement du backend (`start.ps1`) et du frontend (`npm run dev`) est celui utilisé tous les jours en développement. L'installation de PostgreSQL par l'installeur Windows et de Keycloak depuis le zip n'a pas été refaite sur une machine vierge.

---

## Ce qui tourne, et sur quels ports

| Service | Rôle | Adresse |
|---|---|---|
| Frontend (React) | l'interface web | http://localhost:3000 |
| Backend (Spring Boot) | l'API et le moteur de charge | http://localhost:8080 |
| Keycloak | comptes, rôles, page de connexion | http://localhost:8081 |
| PostgreSQL | la base de données `loadpilot` | localhost:5432 |
| Mailpit | capture les e-mails (rien n'est réellement envoyé) | http://localhost:8025 (SMTP : 1025) |
| banking-test-api *(facultatif)* | application bancaire de démonstration, cible des tests | http://localhost:8888 |

> **Toujours utiliser `localhost`**, jamais `127.0.0.1` ni l'IP de la machine. Keycloak n'autorise que `http://localhost:3000`, et le backend n'accepte que les jetons émis pour `http://localhost:8081`.

### Vérifier qu'aucun port n'est déjà pris (à faire avant les deux parties)

```powershell
Get-NetTCPConnection -State Listen -LocalPort 3000,8080,8081,5432,8025,1025,8888 -ErrorAction SilentlyContinue |
  Select-Object LocalAddress, LocalPort, OwningProcess, @{n='Processus';e={(Get-Process -Id $_.OwningProcess).ProcessName}}
```

- **Aucune ligne** : parfait, passez à la suite.
- **Une ligne** : ce port est occupé. Voir [Port déjà utilisé](#port-déjà-utilisé). Le cas le plus fréquent est un **PostgreSQL installé sur Windows** qui occupe le port 5432 : voir [Piège n° 1](#piège-n-1--un-postgresql-windows-occupe-déjà-le-port-5432).

### Récupérer le code

Installer Git si besoin : https://git-scm.com/download/win (options par défaut).

```powershell
cd $HOME\Documents
git clone https://github.com/Med124s/Test_performance_ADRIA.git
cd Test_performance_ADRIA
```

---

# Partie A : Docker Compose (tout conteneurisé)

Cinq conteneurs démarrent : `postgres`, `keycloak`, `mailpit`, `backend`, `frontend`.

## A.1 Prérequis

| Outil | Version | Vérification |
|---|---|---|
| Docker Desktop | 4.30 ou plus récent (Docker Compose **v2.24+**) | `docker compose version` |
| Git | toute version récente | `git --version` |
| Mémoire | 8 Go minimum, 16 Go conseillés (le backend peut prendre jusqu'à 4 Go) | |
| Disque | environ 5 Go libres (images + build) | |

**Installer Docker Desktop :**

1. Télécharger : https://www.docker.com/products/docker-desktop/
2. Installer avec l'option **« Use WSL 2 instead of Hyper-V »** cochée.
3. Redémarrer Windows si on vous le demande.
4. Lancer Docker Desktop et attendre que l'icône de la baleine indique **« Engine running »**.
5. Vérifier dans un **nouveau** terminal PowerShell :

```powershell
docker version
docker compose version
docker run --rm hello-world
```

La dernière commande doit afficher `Hello from Docker!`.

> Si `wsl` n'est pas installé, Docker Desktop vous le dira. Dans ce cas, ouvrez PowerShell **en administrateur**, lancez `wsl --install`, puis redémarrez Windows.

## A.2 Créer le fichier `.env`

Le fichier `.env`, à la racine du dépôt, contient les mots de passe. Il n'est **jamais** envoyé sur Git (il est dans `.gitignore`).

| Variable | Obligatoire ? | Défaut | Rôle |
|---|---|---|---|
| `DB_PASSWORD` | **Oui** | aucun | mot de passe de la base PostgreSQL |
| `KEYCLOAK_ADMIN_PASSWORD` | **Oui** | aucun | mot de passe du compte `admin` de la console Keycloak |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | **Oui** | aucun | secret du client `loadpilot-backend-admin`, que le backend utilise pour gérer les utilisateurs. **Vous le choisissez** : Keycloak le reçoit au premier import du realm et le backend l'utilise, rien à recopier |
| `LOADPILOT_SECRETS_KEY` | **Oui** | aucun | clé de chiffrement : **exactement 32 octets en Base64**. Sinon le backend refuse de démarrer. **Ne jamais la perdre** (voir `docs/SECRETS.md`) |
| `DB_USERNAME` | Non | `loadpilot` | utilisateur PostgreSQL |
| `DB_NAME` | Non | `loadpilot` | nom de la base |
| Toutes les autres (`LOADPILOT_MAX_*`, `LOADPILOT_MAIL_*`, `KC_SMTP_*`, proxy, certificats…) | Non | valeurs de `application.yml` | à laisser commentées pour une première installation (détail dans `.env.example`) |

Dans `.env.example`, les secrets sont des placeholders (`<...-a-generer>`) qui **ne fonctionnent pas** tels quels. **Copier-coller ce bloc** : il crée `.env` à partir de `.env.example` et génère des valeurs aléatoires pour les 4 variables obligatoires :

```powershell
function New-Hex([int]$n) { $b = New-Object byte[] $n; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ([BitConverter]::ToString($b) -replace '-', '').ToLower() }
$k = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($k)
$values = @{
  'DB_PASSWORD'                  = New-Hex 16
  'KEYCLOAK_ADMIN_PASSWORD'      = New-Hex 12
  'KEYCLOAK_ADMIN_CLIENT_SECRET' = New-Hex 32
  'LOADPILOT_SECRETS_KEY'        = [Convert]::ToBase64String($k)
}
$lines = [IO.File]::ReadAllLines("$PWD\.env.example") | ForEach-Object {
  if ($_ -match '^([A-Z_]+)=' -and $values.ContainsKey($Matches[1])) { "$($Matches[1])=$($values[$Matches[1]])" } else { $_ }
}
[IO.File]::WriteAllLines("$PWD\.env", $lines)
Select-String -Path .env -Pattern '^(DB_|KEYCLOAK_|LOADPILOT_SECRETS)'
```

La dernière commande affiche les 6 lignes renseignées. **Sauvegardez `.env`** dans un endroit sûr, hors du dépôt (gestionnaire de mots de passe par exemple), surtout `LOADPILOT_SECRETS_KEY`.

> **Faites cette étape avant le premier démarrage.** `KEYCLOAK_ADMIN_PASSWORD` et `KEYCLOAK_ADMIN_CLIENT_SECRET` ne sont lus qu'**une fois**, quand Keycloak s'initialise (au premier `docker compose up`). Les changer ensuite dans `.env` ne modifie pas Keycloak (voir [A.7](#a7-erreurs-courantes-avec-docker)).

> **Éditer `.env` à la main ?** Utilisez VS Code ou le Bloc-notes. N'utilisez **pas** `Set-Content` ou `Out-File` de PowerShell 5 : ils ajoutent un BOM (caractère invisible) ou changent l'encodage, et Docker Compose lit alors mal le fichier.

## A.3 Construire et démarrer tous les services

```powershell
docker compose up -d --build
```

Le **premier lancement prend 10 à 20 minutes** : téléchargement des images (environ 1 Go), puis build du backend et du frontend (dépendances Maven et npm). Les suivants prennent quelques secondes.

Suivez le démarrage de Keycloak, qui importe le realm `loadpilot` au premier lancement (environ 1 minute) :

```powershell
docker compose logs -f keycloak
```

Attendez ces deux lignes, puis faites **Ctrl+C** (cela quitte l'affichage du journal, sans arrêter Keycloak) :

```
... Realm 'loadpilot' imported
... Keycloak 26.7.3 on JVM (powered by Quarkus ...) started in ...s. Listening on: http://0.0.0.0:8080
```

Puis celui du backend :

```powershell
docker compose logs -f backend
```

Attendez la ligne `Started BackendApplication in ... seconds`, puis **Ctrl+C**. Au premier démarrage, Liquibase crée d'abord toutes les tables : c'est normal que cela défile beaucoup.

## A.4 Créer le premier compte Super administrateur

Le realm importé ne contient **aucun utilisateur**. Créez le premier compte, avec le rôle `ROLE_SUPER_ADMIN`. Ce compte pourra ensuite créer les autres depuis LoadPilot (**Administration** → **Utilisateurs et rôles**).

> Le mot de passe doit faire au moins 8 caractères, avec au moins une majuscule, une minuscule et un chiffre (politique du realm).

### Méthode 1 : en ligne de commande

Remplacez l'identifiant, l'e-mail, le nom et le mot de passe dans les 3 dernières lignes :

```powershell
# Connexion de l'outil d'administration kcadm (à l'intérieur du conteneur keycloak)
$kcpw = (Select-String -Path .env -Pattern '^KEYCLOAK_ADMIN_PASSWORD=(.*)$').Matches[0].Groups[1].Value
docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password $kcpw --config /tmp/kcadm.config
function kc { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh @args --config /tmp/kcadm.config }

# Création du compte
kc create users -r loadpilot -s username=admin.loadpilot -s email=admin@exemple.com -s firstName=Admin -s lastName=LoadPilot -s enabled=true -s emailVerified=true
kc set-password -r loadpilot --username admin.loadpilot --new-password 'Changer-Moi-2026'
kc add-roles -r loadpilot --uusername admin.loadpilot --rolename ROLE_SUPER_ADMIN
```

La commande `kc create users` affiche `Created new user with id '...'`.

### Méthode 2 : avec la console d'administration

1. Ouvrir http://localhost:8081/admin et se connecter avec `admin` et la valeur de `KEYCLOAK_ADMIN_PASSWORD` du fichier `.env`.
2. En haut à gauche, choisir le realm **loadpilot** (pas « master »).
3. **Users** → **Create new user**. Renseigner *Username*, *Email*, *First name*, *Last name*. Cocher *Email verified*, puis **Create**.
4. Onglet **Credentials** → **Set password** → saisir le mot de passe, **décocher *Temporary*** → **Save**.
5. Onglet **Role mapping** → **Assign role** → filtre **Filter by realm roles** → cocher `ROLE_SUPER_ADMIN` → **Assign**.

## A.5 Vérifier que tout fonctionne

**1. Les 5 conteneurs sont démarrés :**

```powershell
docker compose ps
```

Les 5 services (`postgres`, `keycloak`, `mailpit`, `backend`, `frontend`) doivent être `Up` ou `running`. `postgres` doit être `(healthy)`.

**2. Les URL répondent :**

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health                                      # attendu : status = UP
(Invoke-RestMethod http://localhost:8081/realms/loadpilot/.well-known/openid-configuration).issuer   # attendu : http://localhost:8081/realms/loadpilot
(Invoke-WebRequest http://localhost:3000 -UseBasicParsing).StatusCode                        # attendu : 200
(Invoke-WebRequest http://localhost:8025 -UseBasicParsing).StatusCode                        # attendu : 200
```

**3. Dans le navigateur :**

1. Ouvrir **http://localhost:3000**.
2. La page de connexion LoadPilot (thème Keycloak) s'affiche : se connecter avec le compte créé à l'étape A.4.
3. Le tableau de bord s'affiche, et la section **Administration** est visible dans le menu de gauche (réservée au Super administrateur).
4. Ouvrir **Administration** → **Utilisateurs et rôles** : la liste s'affiche, avec le compte créé. Cela prouve que le secret du client backend est le même des deux côtés.

Si une étape échoue, voir [A.7 Erreurs courantes](#a7-erreurs-courantes-avec-docker).

## A.6 Utilisation au quotidien

| Action | Commande |
|---|---|
| Arrêter | `docker compose stop` (ou `docker compose down`, qui supprime aussi les conteneurs mais garde les données) |
| Redémarrer | `docker compose start` (ou `docker compose up -d` après un `down`) |
| Voir l'état | `docker compose ps` |
| Voir les journaux d'un service | `docker compose logs -f backend` (ou `keycloak`, `frontend`, `postgres`) |
| Appliquer une modification de `.env` | `docker compose up -d` (recrée seulement les conteneurs concernés) |
| Reconstruire après une mise à jour du code (`git pull`) | `docker compose up -d --build backend frontend` |

Les données sont dans deux **volumes** Docker, conservés quand les conteneurs sont recréés (`docker compose down`, modification de `.env` ou de `docker-compose.yml`) :

| Volume | Contenu |
|---|---|
| `<dossier>_loadpilot_postgres_data` | base LoadPilot : applications, scénarios, résultats |
| `<dossier>_loadpilot_keycloak_data` | base de Keycloak : utilisateurs, mots de passe, réglages |

`<dossier>` est le nom du dossier du dépôt en minuscules, par exemple `test_performance_adria`. Pour les lister : `docker volume ls`.

> **Ne lancez jamais `docker compose down -v`** sauf pour tout effacer : `-v` supprime les deux volumes, donc **toutes les données LoadPilot et tous les comptes**. Au démarrage suivant, Keycloak réimporte le realm vide : il faut refaire l'étape A.4.

> **`docker compose restart` ne relit pas `.env`.** Après une modification de `.env`, utilisez `docker compose up -d`.

## A.7 Erreurs courantes avec Docker

| Symptôme | Cause | Solution |
|---|---|---|
| `required variable XXX is missing a value` | variable obligatoire absente ou vide dans `.env` | Vérifier que `.env` est **à la racine du dépôt** (même dossier que `docker-compose.yml`) et contient les 4 variables obligatoires. Relancer le bloc de l'étape A.2 si besoin |
| `Bind for 0.0.0.0:5432 failed: port is already allocated` (ou 8080, 8081, 3000…) | un autre programme utilise ce port | Voir [Port déjà utilisé](#port-déjà-utilisé) |
| `error during connect` / `The system cannot find the file specified` / `Cannot connect to the Docker daemon` | Docker Desktop n'est pas lancé | Lancer Docker Desktop, attendre « Engine running », relancer la commande |
| Backend : `LOADPILOT_SECRETS_KEY ... n'est pas du Base64 valide` ou `doit contenir exactement 32 octets` | clé restée au placeholder de `.env.example`, ou mal formée | Générer une vraie clé : `$k = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($k); [Convert]::ToBase64String($k)`, la mettre dans `.env`, puis `docker compose up -d backend`. **Seulement à la première installation** : changer la clé rend illisibles les secrets déjà enregistrés |
| Backend : `password authentication failed for user "loadpilot"` | `DB_PASSWORD` a changé **après** le premier démarrage de PostgreSQL : le mot de passe est fixé une fois pour toutes à la création du volume | Remettre l'ancien `DB_PASSWORD` dans `.env`. Sur une installation **sans données à garder** uniquement : `docker compose down -v`, puis tout reprendre depuis A.3 |
| Connexion OK, mais l'écran **Utilisateurs et rôles** affiche une erreur (502) | le secret de `.env` et celui de Keycloak diffèrent : `KEYCLOAK_ADMIN_CLIENT_SECRET` a été modifié dans `.env` **après** le premier démarrage | Remettre l'ancienne valeur dans `.env`, ou aligner Keycloak sur la nouvelle (commandes sous ce tableau) |
| La console Keycloak refuse `admin` avec le mot de passe de `.env` | `KEYCLOAK_ADMIN_PASSWORD` a été modifié dans `.env` **après** le premier démarrage | Utiliser l'ancien mot de passe (le compte `admin` n'est créé qu'une fois) |
| Après la connexion, toutes les pages sont vides ou en erreur, et `docker compose logs backend` montre des 401 | jeton refusé : application ouverte via `127.0.0.1` ou une IP au lieu de `localhost` | Ouvrir **http://localhost:3000** |
| Page Keycloak « Invalid parameter: redirect_uri » | même cause : adresse autre que `http://localhost:3000` | Ouvrir **http://localhost:3000** |
| « Nom d'utilisateur ou mot de passe incorrect » alors que le mot de passe est bon | compte bloqué temporairement après 10 échecs (protection anti-force brute, 1 à 15 min) | Attendre, ou console Keycloak → Users → le compte → **Unlock** |
| Le build échoue sur `mvn dependency:go-offline` ou `npm ci` (timeout, `Could not transfer artifact`) | pas d'accès Internet, ou proxy d'entreprise | Vérifier la connexion. Derrière un proxy : Docker Desktop → Settings → Resources → Proxies |
| Le backend redémarre en boucle, journal avec `OutOfMemoryError` ou `Killed` | Docker n'a pas assez de mémoire | Docker Desktop → Settings → Resources : donner au moins 6 Go. Sinon ajouter `LOADPILOT_JVM_MAX_MEMORY=2g` dans `.env`, puis `docker compose up -d backend` |

**Aligner le secret de Keycloak sur celui de `.env`** (dans un terminal où la connexion `kcadm` de l'étape A.4, méthode 1, a été faite) :

```powershell
$secret = (Select-String -Path .env -Pattern '^KEYCLOAK_ADMIN_CLIENT_SECRET=(.*)$').Matches[0].Groups[1].Value
$cid = (kc get clients -r loadpilot -q clientId=loadpilot-backend-admin --fields id --format csv --noquotes).Trim()
kc update "clients/$cid" -r loadpilot -s "secret=$secret"
docker compose up -d backend
```

---

# Partie B : sans Docker (un terminal par service)

Chaque service est installé sur Windows et lancé dans son propre terminal PowerShell.

> **Variante plus simple** si Docker Desktop est disponible : faire tourner seulement PostgreSQL, Keycloak et Mailpit dans Docker (étape A.2, puis `docker compose up -d postgres keycloak mailpit`, puis étape A.4), et lancer le backend et le frontend comme en B.6 et B.7. C'est la configuration la plus pratique pour développer.

## B.1 Prérequis

| Outil | Version | Où le trouver | Vérification |
|---|---|---|---|
| Java (JDK) | **21** | https://adoptium.net/temurin/releases/?version=21 (installeur `.msi`, cocher **« Set JAVA_HOME »** et **« Add to PATH »**) | `java -version` → `21.x` |
| Maven | 3.9.x | https://maven.apache.org/download.cgi (*Binary zip archive*) | `mvn -v` → `Apache Maven 3.9` **et** `Java version: 21` |
| Node.js | **22.5 ou plus récent** (22 LTS conseillé) | https://nodejs.org (installeur LTS) | `node -v` → `v22.x` |
| PostgreSQL | 17.x | https://www.enterprisedb.com/downloads/postgres-postgresql-downloads | `psql --version` |
| Keycloak | **26.7.3** (même version que le projet) | https://github.com/keycloak/keycloak/releases/download/26.7.3/keycloak-26.7.3.zip | voir B.5 |
| Mailpit *(facultatif)* | 1.x | https://github.com/axllent/mailpit/releases (`mailpit-windows-amd64.zip`) | voir B.3 |

**Installer Maven** (pas d'installeur, c'est un zip) :

```powershell
# Après avoir téléchargé apache-maven-3.9.x-bin.zip dans Téléchargements :
Expand-Archive "$HOME\Downloads\apache-maven-3.9.*-bin.zip" -DestinationPath C:\outils
$mvnBin = (Get-ChildItem C:\outils -Directory -Filter 'apache-maven-3.9*' | Select-Object -First 1).FullName + '\bin'
[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', 'User') + ";$mvnBin", 'User')
```

**Fermez et rouvrez PowerShell**, puis vérifiez les quatre outils :

```powershell
java -version
mvn -v
node -v
npm -v
```

> Si `mvn -v` affiche `Java version: 17` (ou autre chose que 21), un ancien JDK passe en premier. Corrigez `JAVA_HOME` : Paramètres Windows → « Modifier les variables d'environnement système » → `JAVA_HOME` = dossier du JDK 21 (par exemple `C:\Program Files\Eclipse Adoptium\jdk-21...`). Rouvrez ensuite le terminal.

> Si PowerShell refuse de lancer des scripts (`start.ps1`, `npm`) avec « l'exécution de scripts est désactivée sur ce système » :
> ```powershell
> Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
> ```

## B.2 Ordre de démarrage

```
1. PostgreSQL ──┐
2. Mailpit      ├──> 4. Backend ──> 5. Frontend ──> navigateur http://localhost:3000
3. Keycloak ────┘                  (6. banking-test-api, facultatif, à tout moment)
```

- Le **backend** a besoin de PostgreSQL (sinon il s'arrête au démarrage) et de Keycloak (pour vérifier les jetons et gérer les utilisateurs).
- Le **frontend** peut démarrer avant le backend, mais rien ne s'affichera tant que le backend ne répond pas.
- Mailpit est facultatif : sans lui, seuls les envois d'e-mails échouent.

## B.3 PostgreSQL et Mailpit

### PostgreSQL (une seule fois)

1. Lancer l'installeur PostgreSQL 17. Garder le port **5432**. Choisir un mot de passe pour le super-utilisateur `postgres` et le noter. *Stack Builder* est inutile, vous pouvez le décocher.
2. Ajouter `psql` au PATH de ce terminal, puis créer l'utilisateur et la base de LoadPilot. **Choisissez votre propre mot de passe** à la place de `MotDePasseBase1` :

```powershell
$env:Path += ';C:\Program Files\PostgreSQL\17\bin'
psql -U postgres -h localhost -c "CREATE ROLE loadpilot WITH LOGIN PASSWORD 'MotDePasseBase1';"
psql -U postgres -h localhost -c "CREATE DATABASE loadpilot OWNER loadpilot;"
psql -U loadpilot -h localhost -d loadpilot -c "SELECT current_user, version();"
```

`psql` demande le mot de passe de `postgres` pour les deux premières commandes, puis celui de `loadpilot` pour la troisième. La troisième doit afficher `loadpilot | PostgreSQL 17...`.

Ne créez **aucune table** : Liquibase les crée au premier démarrage du backend.

> L'installeur crée un **service Windows** (`postgresql-x64-17`) qui démarre automatiquement avec Windows : rien d'autre à lancer ensuite. Mais si Docker est aussi installé sur la machine, lisez le [Piège n° 1](#piège-n-1--un-postgresql-windows-occupe-déjà-le-port-5432).

### Mailpit (facultatif) : terminal n° 1

```powershell
Expand-Archive "$HOME\Downloads\mailpit-windows-amd64.zip" -DestinationPath C:\outils\mailpit
C:\outils\mailpit\mailpit.exe --smtp 0.0.0.0:1025 --listen 0.0.0.0:8025 --smtp-auth-accept-any --smtp-auth-allow-insecure
```

Laissez ce terminal ouvert. Les e-mails sont visibles sur http://localhost:8025.

## B.4 Préparer le fichier de variables (une seule fois)

Le backend lit sa configuration dans des **variables d'environnement**. Il ne lit **pas** de fichier `.env`. Pour ne pas les retaper à chaque fois, on les range dans un fichier `loadpilot-backend.env` **hors du dépôt**, que l'on chargera dans les terminaux de Keycloak (B.5) et du backend (B.6).

Ce fichier contient aussi le **secret du client `loadpilot-backend-admin`** : Keycloak le reçoit à l'import du realm, le backend l'utilise. C'est pourquoi il faut le créer **avant** le premier démarrage de Keycloak.

```powershell
function New-Hex([int]$n) { $b = New-Object byte[] $n; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ([BitConverter]::ToString($b) -replace '-', '').ToLower() }
$k = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($k)

$contenu = @"
DB_URL=jdbc:postgresql://localhost:5432/loadpilot
DB_USERNAME=loadpilot
DB_PASSWORD=MotDePasseBase1
KEYCLOAK_ISSUER_URI=http://localhost:8081/realms/loadpilot
KEYCLOAK_ADMIN_SERVER_URL=http://localhost:8081
KEYCLOAK_ADMIN_CLIENT_SECRET=$(New-Hex 32)
LOADPILOT_SECRETS_KEY=$([Convert]::ToBase64String($k))
"@
[IO.File]::WriteAllText("$HOME\loadpilot-backend.env", $contenu)

notepad "$HOME\loadpilot-backend.env"
```

Dans le Bloc-notes, remplacez `MotDePasseBase1` par le mot de passe de l'utilisateur `loadpilot` choisi à l'étape B.3, puis enregistrez. Les deux dernières lignes sont déjà générées : ne les modifiez pas.

> **Sauvegardez ce fichier** (gestionnaire de mots de passe). `LOADPILOT_SECRETS_KEY` ne doit **jamais** changer une fois que des applications ont été enregistrées : sinon leurs secrets deviennent illisibles.

> **Variante hybride** (PostgreSQL et Keycloak dans Docker) : pas besoin de ce fichier. Le `.env` racine de l'étape A.2 contient déjà `DB_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET` et `LOADPILOT_SECRETS_KEY`, et les autres valeurs par défaut conviennent. En B.6, chargez `..\.env` à la place de `$HOME\loadpilot-backend.env`.

## B.5 Keycloak : terminal n° 2

### Installation (une seule fois)

```powershell
# Remplacez par le chemin de VOTRE clone du dépôt
$repo = "$HOME\Documents\Test_performance_ADRIA"

Expand-Archive "$HOME\Downloads\keycloak-26.7.3.zip" -DestinationPath C:\outils
$kc = 'C:\outils\keycloak-26.7.3'

# Thème de la page de connexion LoadPilot
Copy-Item -Recurse "$repo\keycloak\themes\loadpilot" "$kc\themes\loadpilot"

# Realm à importer au premier démarrage
New-Item -ItemType Directory -Force "$kc\data\import" | Out-Null
Copy-Item "$repo\keycloak\realm-export.json" "$kc\data\import\"
```

> **Le chemin de Keycloak ne doit pas contenir d'espace ni d'accent** (`C:\outils\...` convient, `C:\Program Files\...` ou `Téléchargements\...` non).

### Premier démarrage (avec import du realm)

```powershell
cd C:\outils\keycloak-26.7.3

# Secret du client backend (KEYCLOAK_ADMIN_CLIENT_SECRET), lu dans le fichier de l'étape B.4
Get-Content "$HOME\loadpilot-backend.env" | Where-Object { $_ -match '^\s*([A-Z_][A-Z0-9_]*)=(.*)$' } | ForEach-Object { Set-Item "env:$($Matches[1])" $Matches[2].Trim() }

# Compte admin de la console Keycloak (choisissez votre mot de passe)
$env:KC_BOOTSTRAP_ADMIN_USERNAME = 'admin'
$env:KC_BOOTSTRAP_ADMIN_PASSWORD = 'AdminKeycloak1'

# Serveur e-mail du realm : sans Docker, Mailpit est sur localhost
$env:KC_SMTP_HOST = 'localhost'

bin\kc.bat start-dev --http-port=8081 --import-realm
```

Attendez `Realm 'loadpilot' imported`, puis `Listening on: http://0.0.0.0:8081`. **Laissez ce terminal ouvert.**

> **`--http-port=8081` est indispensable.** Sans lui, Keycloak prend le port 8080, celui du backend.

> **Les variables de ce bloc ne sont lues qu'à ce premier démarrage**, au moment de l'import du realm :
> - **`KEYCLOAK_ADMIN_CLIENT_SECRET` absent** : Keycloak ne signale aucune erreur, mais le secret du client devient le texte littéral `${KEYCLOAK_ADMIN_CLIENT_SECRET}`, et l'écran **Utilisateurs et rôles** sera en erreur (502). Corrigez avec les commandes `kcadm.bat` ci-dessous (« Aligner le secret »).
> - **`KC_SMTP_HOST` oublié** : le realm garde `mailpit`, un nom qui n'existe que dans Docker. Corrigez dans la console : realm `loadpilot` → **Realm settings** → **Email** → *Host* = `localhost`, *Port* = `1025`.

### Démarrages suivants

L'import ne modifie jamais un realm existant. Les démarrages suivants se font simplement ainsi :

```powershell
cd C:\outils\keycloak-26.7.3
bin\kc.bat start-dev --http-port=8081
```

Les données de Keycloak (utilisateurs, secret) sont conservées dans `C:\outils\keycloak-26.7.3\data`.

### Vérifier

Dans un autre terminal :

```powershell
(Invoke-RestMethod http://localhost:8081/realms/loadpilot/.well-known/openid-configuration).issuer
```

Résultat attendu : `http://localhost:8081/realms/loadpilot`.

### Créer le premier compte Super administrateur (une seule fois)

**Avec la console d'administration** : suivre la [méthode 2 de l'étape A.4](#méthode-2--avec-la-console-dadministration), en vous connectant avec `admin` / `AdminKeycloak1` (ou le mot de passe choisi plus haut).

**Ou en ligne de commande**, avec `kcadm.bat`, fourni avec Keycloak (remplacez l'identifiant, l'e-mail, le nom et le mot de passe) :

```powershell
cd C:\outils\keycloak-26.7.3
bin\kcadm.bat config credentials --server http://localhost:8081 --realm master --user admin --password 'AdminKeycloak1'
bin\kcadm.bat create users -r loadpilot -s username=admin.loadpilot -s email=admin@exemple.com -s firstName=Admin -s lastName=LoadPilot -s enabled=true -s emailVerified=true
bin\kcadm.bat set-password -r loadpilot --username admin.loadpilot --new-password 'Changer-Moi-2026'
bin\kcadm.bat add-roles -r loadpilot --uusername admin.loadpilot --rolename ROLE_SUPER_ADMIN
```

**Aligner le secret** (seulement si `KEYCLOAK_ADMIN_CLIENT_SECRET` n'était pas défini au premier démarrage, ou si vous l'avez changé ensuite dans `loadpilot-backend.env`), après la ligne `config credentials` ci-dessus :

```powershell
$secret = (Select-String -Path "$HOME\loadpilot-backend.env" -Pattern '^KEYCLOAK_ADMIN_CLIENT_SECRET=(.*)$').Matches[0].Groups[1].Value
$cid = (bin\kcadm.bat get clients -r loadpilot -q clientId=loadpilot-backend-admin --fields id --format csv --noquotes).Trim()
bin\kcadm.bat update "clients/$cid" -r loadpilot -s "secret=$secret"
```

## B.6 Backend : terminal n° 3

```powershell
cd $HOME\Documents\Test_performance_ADRIA\backend

# Charger les variables dans CE terminal (à refaire dans chaque nouveau terminal)
Get-Content "$HOME\loadpilot-backend.env" | Where-Object { $_ -match '^\s*([A-Z_][A-Z0-9_]*)=(.*)$' } | ForEach-Object { Set-Item "env:$($Matches[1])" $Matches[2].Trim() }

.\start.ps1
```

`start.ps1` lance `mvn spring-boot:run`, avec 4 Go de mémoire maximum et les options de certificat ou de proxy si elles sont définies (voir README, « Préparer LoadPilot pour une application cible inconnue »).

- **Premier lancement** : Maven télécharge les dépendances (3 à 10 min), puis Liquibase crée les tables.
- **C'est prêt** quand la ligne `Started BackendApplication in ... seconds` apparaît.
- Laissez ce terminal ouvert. **Ctrl+C** arrête le backend.

Vérification, dans un autre terminal :

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health    # attendu : status = UP
```

> Si `start.ps1` est bloqué par la politique d'exécution : `powershell -ExecutionPolicy Bypass -File .\start.ps1`
> Lancement sans le script, équivalent sauf pour la mémoire : `mvn spring-boot:run`

## B.7 Frontend : terminal n° 4

```powershell
cd $HOME\Documents\Test_performance_ADRIA\perftest-frontend
Copy-Item .env.example .env      # une seule fois : sans ce fichier, la connexion Keycloak ne marche pas
npm ci                           # une seule fois (et après chaque changement de package-lock.json)
npm run dev
```

C'est prêt quand Vite affiche `Local: http://localhost:3000/`. Ouvrez **http://localhost:3000** et connectez-vous avec le compte créé en B.5.

Le `.env` du frontend contient uniquement des adresses publiques (Keycloak, backend), **jamais de secret** :

```
VITE_KEYCLOAK_URL=http://localhost:8081
VITE_KEYCLOAK_REALM=loadpilot
VITE_KEYCLOAK_CLIENT_ID=loadpilot-frontend
VITE_SPRING_API_URL=http://localhost:8080
```

Après une modification de ce fichier, arrêtez (`Ctrl+C`) et relancez `npm run dev`.

## B.8 banking-test-api (facultatif) : terminal n° 5

L'application bancaire de démonstration, à tester avec LoadPilot.

```powershell
cd $HOME\Documents\Test_performance_ADRIA\banking-test-api
npm ci          # une seule fois
npm start       # http://localhost:8888
```

- Elle renvoie volontairement **7 % d'erreurs aléatoires** (409/500). Pour des résultats sans ces erreurs : `$env:ERROR_RATE = '0'; npm start`.
- **Ne jamais mettre `/api/reset` dans un scénario de charge** : cette route remet les données de démonstration à zéro.

## B.9 Vérifier que tout fonctionne

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health                                             # UP
(Invoke-RestMethod http://localhost:8081/realms/loadpilot/.well-known/openid-configuration).issuer  # http://localhost:8081/realms/loadpilot
(Invoke-WebRequest http://localhost:3000 -UseBasicParsing).StatusCode                               # 200
```

Puis, dans le navigateur, les mêmes contrôles qu'en [A.5](#a5-vérifier-que-tout-fonctionne) : connexion, tableau de bord, écran **Utilisateurs et rôles**.

## B.10 Pièges à éviter (rencontrés pendant le développement)

### Piège n° 1 : un PostgreSQL Windows occupe déjà le port 5432

**Situation :** un PostgreSQL installé sur Windows (service `postgresql-x64-17`) **et** le PostgreSQL de Docker veulent tous les deux le port 5432.

**Symptômes :**
- `docker compose up` échoue avec `port is already allocated` ; **ou pire**
- tout démarre, mais le backend affiche `password authentication failed for user "loadpilot"` ou `database "loadpilot" does not exist`, alors que le mot de passe est bon. Le backend parle au **mauvais** PostgreSQL : selon que `localhost` passe par IPv4 ou IPv6, la connexion arrive sur le serveur Windows ou sur celui de Docker.

**Diagnostic :**

```powershell
Get-Service postgresql* | Select-Object Name, Status, StartType
Get-NetTCPConnection -State Listen -LocalPort 5432 | Select-Object LocalAddress, @{n='Processus';e={(Get-Process -Id $_.OwningProcess).ProcessName}}
```

`postgres` dans la colonne *Processus* = PostgreSQL Windows. `com.docker.backend` ou `wslrelay` = PostgreSQL de Docker.

**Solution :** n'en garder **qu'un seul**. Pour utiliser celui de Docker, arrêtez le service Windows et empêchez-le de redémarrer avec Windows. **PowerShell en administrateur** :

```powershell
Stop-Service postgresql-x64-17
Set-Service postgresql-x64-17 -StartupType Manual
```

Cela ne supprime aucune donnée : `Start-Service postgresql-x64-17` le relance si besoin. *(Autre solution : changer le port de l'un des deux, puis adapter `DB_URL`.)*

### Piège n° 2 : les variables d'environnement disparaissent avec le terminal

`$env:DB_PASSWORD = '...'` ne vaut que pour **ce** terminal. Dans un nouveau terminal, le backend repart sans ses variables : `LOADPILOT_SECRETS_KEY est absente`, ou `password authentication failed` avec le mot de passe par défaut. **Rechargez le fichier de variables (ligne `Get-Content ...` de B.6) dans chaque nouveau terminal.**

### Piège n° 3 : le secret du client backend n'est lu qu'au premier démarrage de Keycloak

Le secret du client `loadpilot-backend-admin` vient de `KEYCLOAK_ADMIN_CLIENT_SECRET`, **lu une seule fois**, quand Keycloak importe le realm. Si la variable manquait à ce moment (terminal où le fichier de B.4 n'a pas été chargé), ou si elle a été modifiée ensuite, Keycloak et le backend n'ont plus le même secret. La connexion fonctionne, mais l'écran **Utilisateurs et rôles** est en erreur (502), comme la création de comptes et les invitations. Corrigez avec « Aligner le secret » (B.5, ou A.7 avec Docker).

### Piège n° 4 : l'import du realm ne s'applique qu'une fois

`--import-realm` **ignore un realm qui existe déjà**. Modifier `realm-export.json` (ou `KC_SMTP_*`) n'a aucun effet sur un Keycloak déjà initialisé : faites la modification dans la console d'administration.

### Piège n° 5 : `localhost` partout

- Ouvrir l'application via `http://127.0.0.1:3000` ou l'IP de la machine provoque « Invalid parameter: redirect_uri ».
- Un `VITE_KEYCLOAK_URL` différent de `http://localhost:8081` fait refuser tous les jetons par le backend (401), car l'émetteur du jeton ne correspond plus à `KEYCLOAK_ISSUER_URI`.

### Piège n° 6 : mauvais JDK

`mvn -v` doit afficher `Java version: 21`. Avec un JDK plus ancien, le build échoue avec `release version 21 not supported` ou `invalid target release: 21`. Corrigez `JAVA_HOME` (voir B.1).

### Piège n° 7 : Node trop ancien pour banking-test-api

banking-test-api utilise le module `node:sqlite`, disponible à partir de Node **22.5**. Avec une version plus ancienne : `No such built-in module: node:sqlite`. Mettez Node à jour.

### Piège n° 8 : Keycloak sur le port 8080

Si Keycloak est lancé sans `--http-port=8081`, il prend le port 8080. Le backend ne peut plus démarrer (`Port 8080 was already in use`) et le frontend ne trouve pas Keycloak.

### Piège n° 9 : compte bloqué

Après 10 mots de passe erronés, Keycloak bloque le compte de 1 à 15 min, et affiche même avec le bon mot de passe « Nom d'utilisateur ou mot de passe incorrect ». Débloquer : console Keycloak → **Users** → le compte → **Unlock**.

---

## Port déjà utilisé

Trouver qui occupe le port (remplacez 8080 par le port concerné) :

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8080 | Select-Object LocalPort, OwningProcess, @{n='Processus';e={(Get-Process -Id $_.OwningProcess).ProcessName}}
```

| Processus affiché | Ce que c'est | Que faire |
|---|---|---|
| `postgres` (port 5432) | PostgreSQL Windows | voir [Piège n° 1](#piège-n-1--un-postgresql-windows-occupe-déjà-le-port-5432) |
| `java` (port 8080 ou 8081) | un backend ou un Keycloak déjà lancé (ou un autre serveur Java : Tomcat, Jenkins…) | fermer le terminal correspondant, ou `Stop-Process -Id <OwningProcess>` |
| `node` (port 3000 ou 8888) | un frontend ou une banking-test-api déjà lancé | fermer le terminal correspondant, ou `Stop-Process -Id <OwningProcess>` |
| `com.docker.backend` / `wslrelay` | un conteneur Docker | `docker ps` pour le repérer, puis `docker stop <nom>` |

> Ne faites un `Stop-Process` que sur un processus que vous avez reconnu. En cas de doute, redémarrez Windows.

---

## Pour aller plus loin

- `README.md` : documentation de déploiement complète (variables, sécurité, limites, application cible derrière un proxy ou avec certificats).
- `keycloak/README.md` : thème de connexion, e-mails, réglages du realm.
- `docs/SECRETS.md` : la clé `LOADPILOT_SECRETS_KEY` en détail.
- `.env.example` : toutes les variables facultatives, commentées.
