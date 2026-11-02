// Configuration Keycloak (variables VITE_KEYCLOAK_*).

/** Fournisseur d'authentification de l'application. */
export const AUTH_PROVIDER: 'keycloak' = 'keycloak'

/** URL du serveur Keycloak (ex. "http://localhost:8081"), sans "/realms/...". */
export const KEYCLOAK_URL: string = import.meta.env.VITE_KEYCLOAK_URL || ''

export const KEYCLOAK_REALM: string = import.meta.env.VITE_KEYCLOAK_REALM || 'loadpilot'

/** Client public (pas de client secret dans le navigateur). */
export const KEYCLOAK_CLIENT_ID: string = import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'loadpilot-frontend'

/** Retour après connexion : la racine de l'application. */
export const REDIRECT_URI: string = `${window.location.origin}/`

function realmBaseUrl(): string {
  return `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect`
}

export const KEYCLOAK_ENDPOINTS = {
  authorization: () => `${realmBaseUrl()}/auth`,
  token: () => `${realmBaseUrl()}/token`,
  /** Déconnexion OIDC : ferme la session Keycloak. */
  endSession: () => `${realmBaseUrl()}/logout`,
  /** Répond 401 dès que la session Keycloak n'existe plus (déconnexion ailleurs, compte désactivé…). */
  userinfo: () => `${realmBaseUrl()}/userinfo`,
}
