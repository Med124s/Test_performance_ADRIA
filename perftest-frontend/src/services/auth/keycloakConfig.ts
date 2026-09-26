// ============================================================
// P1-O — Keycloak est la seule authentification réelle (le Mode mock a été
// retiré : voir rapport P1-O pour la preuve runtime complète — realm/
// clients/rôles réels, login/refresh/logout réels, RBAC réel validé contre
// le backend Spring Boot). Configuration lue depuis les variables
// d'environnement Vite (voir .env/.env.example) — aucune valeur en dur.
// ============================================================

/** Conservé comme simple étiquette (utilisé par de nombreuses pages comme
 * `isKeycloak` avant d'autoriser une écriture Spring Boot) — vaut toujours
 * 'keycloak' depuis le retrait du Mode mock. */
export const AUTH_PROVIDER: 'keycloak' = 'keycloak'

/** URL de base du serveur Keycloak (ex: "http://localhost:8081") — sans
 * "/realms/...". */
export const KEYCLOAK_URL: string = import.meta.env.VITE_KEYCLOAK_URL || ''

export const KEYCLOAK_REALM: string = import.meta.env.VITE_KEYCLOAK_REALM || 'loadpilot'

/** Client PUBLIC (pas de client secret côté navigateur — voir Phase 16,
 * section 1 : un client secret n'a aucune valeur de sécurité dans une SPA,
 * il serait visible dans le code source livré au navigateur). */
export const KEYCLOAK_CLIENT_ID: string = import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'loadpilot-frontend'

/** URI de retour après authentification Keycloak — la racine de l'app
 * (voir handleRedirectCallback, appelé au montage de AuthProvider). */
export const REDIRECT_URI: string = `${window.location.origin}/`

function realmBaseUrl(): string {
  return `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect`
}

export const KEYCLOAK_ENDPOINTS = {
  authorization: () => `${realmBaseUrl()}/auth`,
  token: () => `${realmBaseUrl()}/token`,
  /** "end session" standard OIDC — invalide la VRAIE session SSO Keycloak,
   * pas seulement l'état local (voir Phase 16, section 11). */
  endSession: () => `${realmBaseUrl()}/logout`,
}
