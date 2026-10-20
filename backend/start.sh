#!/usr/bin/env bash
# ============================================================
# Demarrage du backend LoadPilot (image Docker : java -jar app.jar) avec les options Java
# optionnelles de l'application cible. Meme logique que start.ps1 (lancement local Windows).
#
# Variables d'environnement lues (toutes optionnelles ; absentes ou vides = ignorees) :
#   LOADPILOT_JVM_MAX_MEMORY            memoire maximale de la JVM (-Xmx), defaut 4g
#   LOADPILOT_TLS_TRUSTSTORE_PATH       magasin de certificats de confiance (autorite interne)
#   LOADPILOT_TLS_TRUSTSTORE_PASSWORD   son mot de passe (facultatif)
#   LOADPILOT_TLS_KEYSTORE_PATH         certificat client (authentification mutuelle)
#   LOADPILOT_TLS_KEYSTORE_PASSWORD     son mot de passe (obligatoire avec le chemin)
#   LOADPILOT_HTTPS_PROXY_HOST          proxy sortant HTTPS (hote)
#   LOADPILOT_HTTPS_PROXY_PORT          proxy sortant HTTPS (port, obligatoire avec l'hote)
#   LOADPILOT_HTTPS_PROXY_USERNAME      identifiant du proxy (Basic, facultatif)
#   LOADPILOT_HTTPS_PROXY_PASSWORD      son mot de passe (obligatoire avec l'identifiant)
#
# Les autres variables (DB_PASSWORD, EXECUTION_TIMEOUT_SECONDS...) sont lues par Spring Boot.
# Usage : start.sh [--dry-run] [arguments de l'application]
# ============================================================
set -euo pipefail

JAR="${LOADPILOT_JAR:-/app/app.jar}"
DRY_RUN=false
if [ "${1:-}" = "--dry-run" ]; then DRY_RUN=true; shift; fi

fail() { echo "Demarrage annule : $1" >&2; exit 1; }
setting() { local v="${!1:-}"; v="${v#"${v%%[![:space:]]*}"}"; v="${v%"${v##*[![:space:]]}"}"; printf '%s' "$v"; }

opts=()
shown=()
add() { opts+=("$1"); shown+=("${2:-$1}"); }
prop() { add "-D$1=$2" "-D$1=$([ "${3:-}" = secret ] && echo '******' || echo "$2")"; }

# Docker Compose transmet une variable non renseignee comme une chaine vide : Spring Boot doit alors
# appliquer sa valeur par defaut (application.yml), pas lire "" (EXECUTION_TIMEOUT_SECONDS est un nombre).
if [ -z "$(setting EXECUTION_TIMEOUT_SECONDS)" ]; then unset EXECUTION_TIMEOUT_SECONDS; fi

# ---------------------------------------------------------------- memoire
memory="$(setting LOADPILOT_JVM_MAX_MEMORY)"
memory="${memory:-4g}"
[[ "$memory" =~ ^[0-9]+[kKmMgG]?$ ]] || fail "LOADPILOT_JVM_MAX_MEMORY invalide ('$memory') : attendu un nombre suivi de m ou g (ex. 4g, 6144m)."
add "-Xmx$memory"

# ---------------------------------------------------------------- certificats
trust="$(setting LOADPILOT_TLS_TRUSTSTORE_PATH)"
trust_pw="$(setting LOADPILOT_TLS_TRUSTSTORE_PASSWORD)"
if [ -n "$trust" ]; then
  [ -f "$trust" ] || fail "LOADPILOT_TLS_TRUSTSTORE_PATH : fichier introuvable ($trust)."
  prop javax.net.ssl.trustStore "$trust"
  [ -z "$trust_pw" ] || prop javax.net.ssl.trustStorePassword "$trust_pw" secret
elif [ -n "$trust_pw" ]; then
  echo "LOADPILOT_TLS_TRUSTSTORE_PASSWORD ignore : LOADPILOT_TLS_TRUSTSTORE_PATH est vide." >&2
fi

key="$(setting LOADPILOT_TLS_KEYSTORE_PATH)"
key_pw="$(setting LOADPILOT_TLS_KEYSTORE_PASSWORD)"
if [ -n "$key" ]; then
  [ -f "$key" ] || fail "LOADPILOT_TLS_KEYSTORE_PATH : fichier introuvable ($key)."
  [ -n "$key_pw" ] || fail "LOADPILOT_TLS_KEYSTORE_PASSWORD est obligatoire avec LOADPILOT_TLS_KEYSTORE_PATH (cle privee du certificat client)."
  prop javax.net.ssl.keyStore "$key"
  prop javax.net.ssl.keyStorePassword "$key_pw" secret
elif [ -n "$key_pw" ]; then
  echo "LOADPILOT_TLS_KEYSTORE_PASSWORD ignore : LOADPILOT_TLS_KEYSTORE_PATH est vide." >&2
fi

# ---------------------------------------------------------------- proxy sortant
proxy_host="$(setting LOADPILOT_HTTPS_PROXY_HOST)"
proxy_port="$(setting LOADPILOT_HTTPS_PROXY_PORT)"
if [ -n "$proxy_host" ] || [ -n "$proxy_port" ]; then
  [ -n "$proxy_host" ] && [ -n "$proxy_port" ] || fail "LOADPILOT_HTTPS_PROXY_HOST et LOADPILOT_HTTPS_PROXY_PORT doivent etre renseignes ensemble."
  [[ "$proxy_port" =~ ^[0-9]+$ ]] && [ "$proxy_port" -ge 1 ] && [ "$proxy_port" -le 65535 ] \
    || fail "LOADPILOT_HTTPS_PROXY_PORT invalide ('$proxy_port') : attendu un port entre 1 et 65535."
  prop https.proxyHost "$proxy_host"
  prop https.proxyPort "$proxy_port"
fi

# Proxy avec identifiant (Basic) : identifiant et mot de passe lus par le backend lui-meme dans
# l'environnement (ProxyAuthorization), jamais passes a Java en option. Java interdit par defaut Basic
# pour les tunnels HTTPS : l'option ci-dessous le reautorise, seulement dans ce cas.
proxy_user="$(setting LOADPILOT_HTTPS_PROXY_USERNAME)"
proxy_pw="$(setting LOADPILOT_HTTPS_PROXY_PASSWORD)"
if [ -n "$proxy_user" ] || [ -n "$proxy_pw" ]; then
  [ -n "$proxy_host" ] || fail "LOADPILOT_HTTPS_PROXY_USERNAME / PASSWORD exigent LOADPILOT_HTTPS_PROXY_HOST et LOADPILOT_HTTPS_PROXY_PORT."
  [ -n "$proxy_user" ] && [ -n "$proxy_pw" ] || fail "LOADPILOT_HTTPS_PROXY_USERNAME et LOADPILOT_HTTPS_PROXY_PASSWORD doivent etre renseignes ensemble."
  [[ "$proxy_user" != *:* ]] || fail "LOADPILOT_HTTPS_PROXY_USERNAME ne doit pas contenir ':' (authentification Basic)."
  prop jdk.http.auth.tunneling.disabledSchemes ""
  shown+=("(proxy authentifie : utilisateur $proxy_user, mot de passe ****** lu par le backend dans l'environnement)")
fi

echo "Options Java du backend :" >&2
for o in "${shown[@]}"; do echo "  $o" >&2; done
if [ "$DRY_RUN" = true ]; then echo "(--dry-run : le backend n'est pas demarre.)" >&2; exit 0; fi

# Valeurs transmises en arguments separes (les espaces ou caracteres speciaux d'un chemin ou d'un
# mot de passe sont conserves tels quels). exec : java devient le processus principal du conteneur.
exec java "${opts[@]}" -jar "$JAR" "$@"
