// ============================================================
// Phase 16 — Client OIDC "Authorization Code + PKCE" pour Keycloak.
//
// Implémentation manuelle (fetch natif + PKCE.ts), volontairement SANS la
// librairie `keycloak-js` : évite d'ajouter une dépendance npm (et le risque
// d'installation réseau associé) pour un flux standard que PKCE.ts +
// quelques appels fetch suffisent à couvrir correctement. Le protocole
// suivi est le VRAI flux OAuth2/OIDC "Authorization Code" que Keycloak
// implémente réellement — rien n'est simulé ici, mais AUCUNE partie de ce
// fichier n'a pu être testée bout-en-bout faute d'un serveur Keycloak
// réellement disponible dans cet environnement (voir rapport Phase 16).
//
// Ne fait JAMAIS transiter de mot de passe : le navigateur est redirigé
// vers la vraie page de login Keycloak (redirectToLogin), qui seule voit le
// mot de passe de l'utilisateur — jamais ce code.
// ============================================================

import { generateCodeChallenge, generateCodeVerifier, generateState } from './pkce'
import { KEYCLOAK_CLIENT_ID, KEYCLOAK_ENDPOINTS, REDIRECT_URI } from './keycloakConfig'

const PKCE_STORAGE_KEY = 'loadpilot_pkce'
const TOKENS_STORAGE_KEY = 'loadpilot_kc_tokens'

interface StoredTokens {
  accessToken: string
  refreshToken: string | null
  idToken: string | null
  /** Timestamp epoch (ms) d'expiration réelle de l'access_token (voir "exp" du JWT). */
  expiresAt: number
}

interface KeycloakJwtClaims {
  sub: string
  preferred_username?: string
  name?: string
  email?: string
  realm_access?: { roles?: string[] }
  exp: number
}

/**
 * Stockage en `sessionStorage` (effacé à la fermeture de l'onglet), jamais
 * `localStorage` ni `db.json` — limite les tokens réels dans le temps.
 * LIMITE HONNÊTE : comme tout stockage accessible en JavaScript, ceci reste
 * exposé à un XSS ; une solution "sans stockage JS" (cookie httpOnly géré
 * par un backend-for-frontend) réduirait davantage ce risque mais
 * nécessiterait un composant serveur supplémentaire, hors périmètre de
 * cette phase (voir rapport Phase 16, section Limitations).
 */
function readTokens(): StoredTokens | null {
  try {
    const raw = sessionStorage.getItem(TOKENS_STORAGE_KEY)
    return raw ? (JSON.parse(raw) as StoredTokens) : null
  } catch {
    return null
  }
}

function writeTokens(tokens: StoredTokens) {
  sessionStorage.setItem(TOKENS_STORAGE_KEY, JSON.stringify(tokens))
}

function clearTokens() {
  sessionStorage.removeItem(TOKENS_STORAGE_KEY)
}

/** Décode le payload d'un JWT SANS vérifier sa signature — usage UI
 * uniquement (afficher l'identité/rôles). La VRAIE vérification
 * cryptographique du JWT est faite par le backend Spring Boot (Resource
 * Server) à chaque appel API, jamais ici. */
function decodeJwtPayload<T>(token: string): T {
  const payloadBase64Url = token.split('.')[1]
  const payloadBase64 = payloadBase64Url.replace(/-/g, '+').replace(/_/g, '/')
  const json = decodeURIComponent(
    atob(payloadBase64)
      .split('')
      .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
      .join('')
  )
  return JSON.parse(json) as T
}

/** Redirige RÉELLEMENT le navigateur vers la page de login Keycloak (le
 * mot de passe est saisi sur cette page Keycloak, jamais dans React). */
export async function redirectToLogin(): Promise<void> {
  const verifier = generateCodeVerifier()
  const challenge = await generateCodeChallenge(verifier)
  const state = generateState()
  sessionStorage.setItem(PKCE_STORAGE_KEY, JSON.stringify({ verifier, state }))

  const params = new URLSearchParams({
    client_id: KEYCLOAK_CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: 'code',
    scope: 'openid profile email',
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state,
  })
  window.location.assign(`${KEYCLOAK_ENDPOINTS.authorization()}?${params.toString()}`)
}

/** À appeler au montage de l'application : si l'URL contient
 * `?code=...&state=...` (retour de Keycloak), échange le code contre de
 * vrais tokens et nettoie l'URL. Ne fait rien si absent (cas normal en
 * mode mock ou hors flux de connexion). */
export async function handleRedirectCallback(): Promise<void> {
  const url = new URL(window.location.href)
  const code = url.searchParams.get('code')
  const state = url.searchParams.get('state')
  if (!code || !state) return

  const pkceRaw = sessionStorage.getItem(PKCE_STORAGE_KEY)
  sessionStorage.removeItem(PKCE_STORAGE_KEY)
  if (!pkceRaw) return // callback inattendu (état PKCE absent) : ignoré, jamais deviné

  const { verifier, state: expectedState } = JSON.parse(pkceRaw) as { verifier: string; state: string }
  if (state !== expectedState) {
    throw new Error('État OAuth2 invalide (protection CSRF) — connexion refusée.')
  }

  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: KEYCLOAK_CLIENT_ID,
    code,
    redirect_uri: REDIRECT_URI,
    code_verifier: verifier,
  })

  const response = await fetch(KEYCLOAK_ENDPOINTS.token(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  })
  if (!response.ok) {
    throw new Error(`Échange du code d'autorisation Keycloak refusé (HTTP ${response.status}).`)
  }
  const json = (await response.json()) as {
    access_token: string
    refresh_token?: string
    id_token?: string
  }

  const claims = decodeJwtPayload<KeycloakJwtClaims>(json.access_token)
  writeTokens({
    accessToken: json.access_token,
    refreshToken: json.refresh_token ?? null,
    idToken: json.id_token ?? null,
    expiresAt: claims.exp * 1000,
  })

  // Nettoie ?code=&state= de l'URL visible sans recharger la page.
  url.searchParams.delete('code')
  url.searchParams.delete('state')
  window.history.replaceState({}, document.title, url.pathname + url.search)
}

/** Marge (ms) prise avant l'expiration réelle du JWT (voir "exp") pour
 * déclencher un renouvellement — évite qu'un token, valide au moment de la
 * vérification, expire pendant le court trajet jusqu'au backend. */
const TOKEN_REFRESH_MARGIN_MS = 30_000

/** Une seule opération de renouvellement à la fois : si plusieurs appels
 * API arrivent au même instant sur un token expiré/proche expiration, ils
 * partagent tous la même requête de refresh au lieu d'en déclencher chacun
 * une (ce qui invaliderait les refresh tokens à usage unique et ferait
 * échouer tous les appels sauf le premier). */
let refreshInFlight: Promise<string | null> | null = null

/** Échange RÉELLEMENT le refresh token contre un nouveau couple de tokens
 * via le grant OAuth2 standard `refresh_token` (jamais un contournement :
 * pas de mot de passe, pas de client_secret — le client reste public). Si
 * Keycloak refuse (refresh token expiré/révoqué), nettoie la session pour
 * laisser l'application redemander une authentification réelle plutôt que
 * de rester dans un état incohérent. Jamais de token fabriqué. */
async function refreshAccessToken(currentRefreshToken: string): Promise<string | null> {
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    client_id: KEYCLOAK_CLIENT_ID,
    refresh_token: currentRefreshToken,
  })

  let response: Response
  try {
    response = await fetch(KEYCLOAK_ENDPOINTS.token(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    })
  } catch {
    // Réseau indisponible : jamais de token fabriqué, l'appelant retombe
    // simplement sur "non authentifié" pour cette requête.
    return null
  }

  if (!response.ok) {
    clearTokens()
    return null
  }

  const json = (await response.json()) as { access_token: string; refresh_token?: string }
  const claims = decodeJwtPayload<KeycloakJwtClaims>(json.access_token)
  writeTokens({
    accessToken: json.access_token,
    // Keycloak ne renvoie pas toujours un nouveau refresh token (dépend de
    // la politique de rotation du realm, voir keycloak/README.md) — celui
    // déjà connu est conservé si absent, jamais mis à `null`.
    refreshToken: json.refresh_token ?? currentRefreshToken,
    idToken: readTokens()?.idToken ?? null,
    expiresAt: claims.exp * 1000,
  })
  return json.access_token
}

/** Access token courant, valide et prêt à l'emploi — `null` si aucune
 * session, ou si l'access token est expiré/proche expiration ET qu'aucun
 * refresh token n'est disponible ou que le refresh a réellement échoué.
 * Renouvelle automatiquement via `refresh_token` quand nécessaire (voir
 * TOKEN_REFRESH_MARGIN_MS) — jamais de token périmé renvoyé silencieusement
 * (voir httpClient.ts, qui n'ajoute alors aucun header Authorization plutôt
 * que d'en envoyer un invalide). */
export async function getAccessToken(): Promise<string | null> {
  const tokens = readTokens()
  if (!tokens) return null

  if (Date.now() < tokens.expiresAt - TOKEN_REFRESH_MARGIN_MS) {
    return tokens.accessToken
  }

  if (!tokens.refreshToken) {
    return null
  }

  if (!refreshInFlight) {
    refreshInFlight = refreshAccessToken(tokens.refreshToken).finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

export async function getCurrentClaims(): Promise<KeycloakJwtClaims | null> {
  const token = await getAccessToken()
  if (!token) return null
  try {
    return decodeJwtPayload<KeycloakJwtClaims>(token)
  } catch {
    return null
  }
}

export async function isAuthenticated(): Promise<boolean> {
  return (await getAccessToken()) !== null
}

/** Déconnexion RÉELLE : invalide la session SSO Keycloak elle-même (pas
 * seulement l'état local) — voir Phase 16, section 11. Après l'appel,
 * Keycloak redirige vers `REDIRECT_URI`. */
export function logout(): void {
  const tokens = readTokens()
  clearTokens()

  const params = new URLSearchParams({ client_id: KEYCLOAK_CLIENT_ID, post_logout_redirect_uri: REDIRECT_URI })
  if (tokens?.idToken) {
    params.set('id_token_hint', tokens.idToken)
  }
  window.location.assign(`${KEYCLOAK_ENDPOINTS.endSession()}?${params.toString()}`)
}
