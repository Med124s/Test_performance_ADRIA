# Chiffrement des secrets en base

Les secrets des **applications testées** sont chiffrés dans PostgreSQL (AES-256-GCM) :

| Colonne | Contenu |
|---|---|
| `application.auth_config` | jeton fixe, clé API, mot de passe Basic, corps de la connexion automatique |
| `application.monitoring_secret` | jeton ou valeur d'en-tête du monitoring |

Les valeurs sont stockées sous la forme `enc:v1:<Base64>`. Elles sont déchiffrées uniquement en mémoire, par le backend, et ne sont jamais renvoyées par l'API.

## La clé : `LOADPILOT_SECRETS_KEY`

- **32 octets aléatoires, encodés en Base64** (clé AES-256).
- **Obligatoire** : sans elle, ou si elle est invalide, le backend **refuse de démarrer**, avec un message clair.
- Fournie **uniquement** par une variable d'environnement : jamais dans Git, `application.yml`, le code ou les journaux.
- **À ne jamais perdre** : sans la même clé, les secrets déjà enregistrés deviennent illisibles. Il faudrait alors ressaisir l'authentification et le monitoring de chaque application.

### Générer une clé

```powershell
# PowerShell (Windows)
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

```bash
# Linux / macOS / Git Bash
openssl rand -base64 32
```

### La configurer

- **Docker Compose** : ajouter la ligne `LOADPILOT_SECRETS_KEY=<clé>` dans le fichier `.env` à la racine. Ce fichier est ignoré par Git, voir `.env.example`.
- **Backend lancé à la main** (`mvn spring-boot:run`) : définir la variable dans le terminal **avant** le lancement.

  ```powershell
  $env:LOADPILOT_SECRETS_KEY = '<clé>'
  cd backend; mvn spring-boot:run
  ```

- **Production** : utiliser le gestionnaire de secrets de l'hébergement (variable d'environnement du service). La garder au même endroit que les autres secrets (`DB_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET`), avec une sauvegarde.

## Premier démarrage avec cette version

La migration Liquibase `033-encrypt-application-secrets` :
1. élargit les deux colonnes ;
2. **chiffre les secrets déjà enregistrés en clair**, avec la clé fournie.

Les valeurs vides ou nulles restent telles quelles. La migration ne s'exécute qu'une fois, et ne touche jamais une valeur déjà chiffrée.

## Changer de clé (rotation)

La version actuelle ne prend en charge **qu'une seule clé à la fois**. Pour en changer, il faut que chaque secret soit enregistré de nouveau avec la nouvelle clé (ressaisie de l'authentification et du monitoring de chaque application). Une rotation automatique, avec plusieurs clés valides, est une évolution possible.
