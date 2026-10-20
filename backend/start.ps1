<#
.SYNOPSIS
    Demarre le backend LoadPilot (mvn spring-boot:run) avec les options Java optionnelles de
    l'application cible : memoire, certificats (autorite interne, certificat client), proxy sortant.

.DESCRIPTION
    Variables d'environnement lues (toutes optionnelles ; absentes ou vides = ignorees) :
      LOADPILOT_JVM_MAX_MEMORY            memoire maximale de la JVM (-Xmx), defaut 4g
      LOADPILOT_TLS_TRUSTSTORE_PATH       magasin de certificats de confiance (autorite interne)
      LOADPILOT_TLS_TRUSTSTORE_PASSWORD   son mot de passe (facultatif)
      LOADPILOT_TLS_KEYSTORE_PATH         certificat client (authentification mutuelle)
      LOADPILOT_TLS_KEYSTORE_PASSWORD     son mot de passe (obligatoire avec le chemin)
      LOADPILOT_HTTPS_PROXY_HOST          proxy sortant HTTPS (hote)
      LOADPILOT_HTTPS_PROXY_PORT          proxy sortant HTTPS (port, obligatoire avec l'hote)
      LOADPILOT_HTTPS_PROXY_USERNAME      identifiant du proxy (Basic, facultatif)
      LOADPILOT_HTTPS_PROXY_PASSWORD      son mot de passe (obligatoire avec l'identifiant)

    Les autres variables du backend (DB_PASSWORD, KEYCLOAK_ADMIN_CLIENT_SECRET,
    LOADPILOT_SECRETS_KEY, EXECUTION_TIMEOUT_SECONDS...) sont lues directement par Spring Boot :
    ce script n'y touche pas.

    Les options sont passees a la JVM par un fichier d'arguments temporaire (@fichier), supprime a
    l'arret : les mots de passe n'apparaissent ni dans la liste des processus ni dans la console.

.PARAMETER DryRun
    Affiche les options calculees (mots de passe masques) sans demarrer le backend.

.EXAMPLE
    cd backend; .\start.ps1
.EXAMPLE
    $env:LOADPILOT_HTTPS_PROXY_HOST = 'proxy.exemple.local'; $env:LOADPILOT_HTTPS_PROXY_PORT = '8080'; .\start.ps1 -DryRun
#>
param(
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
# Arguments supplementaires transmis tels quels a Maven (ex. -o pour le mode hors ligne).
$MavenArgs = $args

function Get-Setting([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ($null -eq $value -or $value.Trim() -eq '') { return $null }
    return $value.Trim()
}

function Stop-WithError([string]$Message) {
    Write-Host "Demarrage annule : $Message" -ForegroundColor Red
    exit 1
}

# Options pour la JVM (fichier d'arguments) et leur version affichable (secrets masques).
$jvmOptions = New-Object System.Collections.Generic.List[string]
$shownOptions = New-Object System.Collections.Generic.List[string]

function Add-JvmOption([string]$Option, [string]$Shown) {
    $jvmOptions.Add($Option)
    $shownOptions.Add($(if ($Shown) { $Shown } else { $Option }))
}

# Valeur entre guillemets au format des fichiers d'arguments Java (\ et " echappes).
function Format-ArgValue([string]$Value) {
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Add-SystemProperty([string]$Key, [string]$Value, [switch]$Secret) {
    $shownValue = if ($Secret) { '******' } else { $Value }
    Add-JvmOption "-D$Key=$(Format-ArgValue $Value)" "-D$Key=$shownValue"
}

# ---------------------------------------------------------------- memoire
$memory = Get-Setting 'LOADPILOT_JVM_MAX_MEMORY'
if (-not $memory) { $memory = '4g' }
if ($memory -notmatch '^\d+[kKmMgG]?$') {
    Stop-WithError "LOADPILOT_JVM_MAX_MEMORY invalide ('$memory') : attendu un nombre suivi de m ou g (ex. 4g, 6144m)."
}
Add-JvmOption "-Xmx$memory"

# ---------------------------------------------------------------- certificats
# Magasin de confiance : REMPLACE celui de Java (voir README, « Préparer LoadPilot pour une application cible inconnue »).
$trustStore = Get-Setting 'LOADPILOT_TLS_TRUSTSTORE_PATH'
$trustStorePassword = Get-Setting 'LOADPILOT_TLS_TRUSTSTORE_PASSWORD'
if ($trustStore) {
    if (-not (Test-Path -LiteralPath $trustStore -PathType Leaf)) {
        Stop-WithError "LOADPILOT_TLS_TRUSTSTORE_PATH : fichier introuvable ($trustStore)."
    }
    Add-SystemProperty 'javax.net.ssl.trustStore' (Resolve-Path -LiteralPath $trustStore).Path
    if ($trustStorePassword) { Add-SystemProperty 'javax.net.ssl.trustStorePassword' $trustStorePassword -Secret }
} elseif ($trustStorePassword) {
    Write-Host 'LOADPILOT_TLS_TRUSTSTORE_PASSWORD ignore : LOADPILOT_TLS_TRUSTSTORE_PATH est vide.' -ForegroundColor Yellow
}

$keyStore = Get-Setting 'LOADPILOT_TLS_KEYSTORE_PATH'
$keyStorePassword = Get-Setting 'LOADPILOT_TLS_KEYSTORE_PASSWORD'
if ($keyStore) {
    if (-not (Test-Path -LiteralPath $keyStore -PathType Leaf)) {
        Stop-WithError "LOADPILOT_TLS_KEYSTORE_PATH : fichier introuvable ($keyStore)."
    }
    if (-not $keyStorePassword) {
        Stop-WithError 'LOADPILOT_TLS_KEYSTORE_PASSWORD est obligatoire avec LOADPILOT_TLS_KEYSTORE_PATH (cle privee du certificat client).'
    }
    Add-SystemProperty 'javax.net.ssl.keyStore' (Resolve-Path -LiteralPath $keyStore).Path
    Add-SystemProperty 'javax.net.ssl.keyStorePassword' $keyStorePassword -Secret
} elseif ($keyStorePassword) {
    Write-Host 'LOADPILOT_TLS_KEYSTORE_PASSWORD ignore : LOADPILOT_TLS_KEYSTORE_PATH est vide.' -ForegroundColor Yellow
}

# ---------------------------------------------------------------- proxy sortant
$proxyHost = Get-Setting 'LOADPILOT_HTTPS_PROXY_HOST'
$proxyPort = Get-Setting 'LOADPILOT_HTTPS_PROXY_PORT'
if ($proxyHost -or $proxyPort) {
    if (-not $proxyHost -or -not $proxyPort) {
        Stop-WithError 'LOADPILOT_HTTPS_PROXY_HOST et LOADPILOT_HTTPS_PROXY_PORT doivent etre renseignes ensemble.'
    }
    if ($proxyPort -notmatch '^\d+$' -or [int]$proxyPort -lt 1 -or [int]$proxyPort -gt 65535) {
        Stop-WithError "LOADPILOT_HTTPS_PROXY_PORT invalide ('$proxyPort') : attendu un port entre 1 et 65535."
    }
    Add-SystemProperty 'https.proxyHost' $proxyHost
    Add-SystemProperty 'https.proxyPort' $proxyPort
}

# Proxy avec identifiant (Basic) : identifiant et mot de passe lus par le backend lui-meme dans
# l'environnement (ProxyAuthorization), jamais passes a Java en option. Java interdit par defaut
# Basic pour les tunnels HTTPS : l'option ci-dessous le reautorise, seulement dans ce cas.
$proxyUser = Get-Setting 'LOADPILOT_HTTPS_PROXY_USERNAME'
$proxyPassword = Get-Setting 'LOADPILOT_HTTPS_PROXY_PASSWORD'
if ($proxyUser -or $proxyPassword) {
    if (-not $proxyHost) {
        Stop-WithError 'LOADPILOT_HTTPS_PROXY_USERNAME / PASSWORD exigent LOADPILOT_HTTPS_PROXY_HOST et LOADPILOT_HTTPS_PROXY_PORT.'
    }
    if (-not $proxyUser -or -not $proxyPassword) {
        Stop-WithError 'LOADPILOT_HTTPS_PROXY_USERNAME et LOADPILOT_HTTPS_PROXY_PASSWORD doivent etre renseignes ensemble.'
    }
    if ($proxyUser.Contains(':')) {
        Stop-WithError "LOADPILOT_HTTPS_PROXY_USERNAME ne doit pas contenir ':' (authentification Basic)."
    }
    Add-SystemProperty 'jdk.http.auth.tunneling.disabledSchemes' ''
    $shownOptions.Add("(proxy authentifie : utilisateur $proxyUser, mot de passe ****** lu par le backend dans l'environnement)")
}

Write-Host 'Options Java du backend :'
$shownOptions | ForEach-Object { Write-Host "  $_" }
if ($DryRun) {
    Write-Host '(-DryRun : le backend n''est pas demarre.)'
    exit 0
}

# ---------------------------------------------------------------- lancement
# Fichier d'arguments dans un dossier sans espace (Maven decoupe jvmArguments sur les espaces).
$argDir = [System.IO.Path]::GetTempPath()
if ($argDir -match '\s') { $argDir = Join-Path $PSScriptRoot 'target' }
if ($argDir -match '\s') { Stop-WithError "aucun dossier sans espace disponible pour le fichier d'options temporaire." }
New-Item -ItemType Directory -Force -Path $argDir | Out-Null
$argFile = Join-Path $argDir "loadpilot-jvm-$PID.args"

Push-Location $PSScriptRoot
try {
    [System.IO.File]::WriteAllText($argFile, ($jvmOptions -join [Environment]::NewLine))
    & mvn spring-boot:run "-Dspring-boot.run.jvmArguments=@$argFile" @MavenArgs
    exit $LASTEXITCODE
} finally {
    Remove-Item -LiteralPath $argFile -Force -ErrorAction SilentlyContinue
    Pop-Location
}
