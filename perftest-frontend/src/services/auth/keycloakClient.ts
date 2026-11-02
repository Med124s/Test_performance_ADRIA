// Connexion Keycloak (OpenID Connect, Authorization Code + PKCE) : redirection vers la page de connexion, échange du code contre les jetons, rafraîchissement et déconnexion.

import { generateCodeChallenge, generateCodeVerifier, generateState } from './pkce'
import { KEYCLOAK_CLIENT_ID, KEYCLOAK_ENDPOINTS, REDIRECT_URI } from './keycloakConfig'
import { announceLogout } from './sessionSync'

const PKCE_STORAGE_KEY = 'loadpilot_pkce'
const TOKENS_STORAGE_KEY = 'loadpilot_kc_tokens'
/** Motif de retour à la connexion (gardé le temps du passage par Keycloak, dans cet onglet). */
const LOGOUT_REASON_KEY = 'loadpilot_logout_reason'
/** Paramètre lu par le thème Keycloak « loadpilot » pour afficher « Vous avez été déconnecté. » */
export const LOGOUT_REASON_PARAM = 'lp_reason'
/** logged_out : déconnexion voulue -> formulaire de connexion obligatoire ; session_ended : session expirée ou fermée côté serveur. */
export type LogoutReason = 'logged_out' | 'session_ended'

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

/** Jetons de l'onglet, gardés en sessionStorage. */
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

/** Une session est-elle ouverte dans cet onglet (jetons présents, même expirés) ? */
export function hasStoredSession(): boolean {
  return readTokens() !== null
}

function rememberLogoutReason(reason: LogoutReason) {
  try {
    sessionStorage.setItem(LOGOUT_REASON_KEY, reason)
  } catch {
    // stockage indisponible : simplement pas de message sur la page de connexion
  }
}

function consumeLogoutReason(): LogoutReason | null {
  try {
    const reason = sessionStorage.getItem(LOGOUT_REASON_KEY)
    sessionStorage.removeItem(LOGOUT_REASON_KEY)
    return reason === 'logged_out' || reason === 'session_ended' ? reason : null
  } catch {
    return null
  }
}

// ---- Fin de session ----
type SessionEndedListener = () => void
const sessionEndedListeners = new Set<SessionEndedListener>()

export function onSessionEnded(listener: SessionEndedListener): () => void {
  sessionEndedListeners.add(listener)
  return () => sessionEndedListeners.delete(listener)
}

/** Termine la session de CET onglet seulement (jetons effacés, motif gardé pour la page de connexion). */
export function endLocalSession(reason: LogoutReason = 'logged_out'): void {
  const hadSession = readTokens() !== null
  clearTokens()
  rememberLogoutReason(reason)
  if (hadSession) sessionEndedListeners.forEach((listener) => listener())
}

/** Décode le payload d'un JWT sans vérifier sa signature (affichage uniquement ; le backend vérifie le jeton). */
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

/** Chemin interne sûr pour le retour après connexion : commence par un seul "/" et n'est pas /login. */
export function safeReturnPath(path: string | null | undefined): string | null {
  if (!path || !path.startsWith('/') || path.startsWith('//') || path.startsWith('/\\')) return null
  if (path === '/login' || path.startsWith('/login?') || path.startsWith('/login#') || path.startsWith('/login/')) return null
  return path
}

/** Redirige vers la page de connexion Keycloak ; `returnTo` : page à rouvrir une fois connecté. */
export async function redirectToLogin(returnTo?: string): Promise<void> {
  await redirectToAuthorization(returnTo)
}

/** Action du compte faite sur une page du système d'authentification (thème LoadPilot). */
export type AccountAction = 'UPDATE_PASSWORD'
/** Résultat rapporté au retour : réussie, annulée par l'utilisateur, ou erreur. */
export interface AccountActionResult {
  action: AccountAction
  status: 'success' | 'cancelled' | 'error'
}
const ACTION_RESULT_KEY = 'loadpilot_kc_action_result'

/** Ouvre la page Keycloak "changer mon mot de passe" ; retour sur `returnTo` avec le résultat. */
export async function redirectToAccountAction(action: AccountAction, returnTo: string): Promise<void> {
  await redirectToAuthorization(returnTo, action)
}

async function redirectToAuthorization(returnTo?: string, action?: AccountAction): Promise<void> {
  const verifier = generateCodeVerifier()
  const challenge = await generateCodeChallenge(verifier)
  const state = generateState()
  sessionStorage.setItem(PKCE_STORAGE_KEY, JSON.stringify({ verifier, state, returnTo: safeReturnPath(returnTo), action: action ?? null }))

  const params = new URLSearchParams({
    client_id: KEYCLOAK_CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: 'code',
    scope: 'openid profile email',
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state,
  })
  if (action) params.set('kc_action', action)
  // Déconnexion voulue : formulaire de connexion obligatoire (prompt=login).
  const reason = consumeLogoutReason()
  if (reason) {
    params.set(LOGOUT_REASON_PARAM, 'logged_out')
    if (reason === 'logged_out') params.set('prompt', 'login')
  }
  // replace : la page quittée ne reste pas dans l'historique.
  window.location.replace(`${KEYCLOAK_ENDPOINTS.authorization()}?${params.toString()}`)
}

/** Résultat de la dernière action du compte (lu une seule fois), ou null. */
export function consumeAccountActionResult(): AccountActionResult | null {
  try {
    const raw = sessionStorage.getItem(ACTION_RESULT_KEY)
    if (!raw) return null
    sessionStorage.removeItem(ACTION_RESULT_KEY)
    return JSON.parse(raw) as AccountActionResult
  } catch {
    return null
  }
}

/** Oublie la session locale puis renvoie à la connexion ; les autres onglets sont prévenus. */
export async function forgetSessionAndLogin(returnTo?: string): Promise<void> {
  announceLogout()
  clearTokens()
  rememberLogoutReason('logged_out')
  await redirectToLogin(returnTo)
}

/** Retour de Keycloak (?code=&state=) : échange le code contre les jetons et nettoie l'URL. Renvoie la page à rouvrir. */
let callbackInFlight: { href: string; result: Promise<string | null> } | null = null

export function handleRedirectCallback(): Promise<string | null> {
  const href = window.location.href
  if (callbackInFlight?.href !== href) {
    callbackInFlight = { href, result: processRedirectCallback() }
  }
  return callbackInFlight.result
}

async function processRedirectCallback(): Promise<string | null> {
  const url = new URL(window.location.href)
  const code = url.searchParams.get('code')
  const state = url.searchParams.get('state')
  if (!code || !state) return null

  const pkceRaw = sessionStorage.getItem(PKCE_STORAGE_KEY)
  sessionStorage.removeItem(PKCE_STORAGE_KEY)
  if (!pkceRaw) return null

  const { verifier, state: expectedState, returnTo, action } = JSON.parse(pkceRaw) as {
    verifier: string; state: string; returnTo?: string | null; action?: AccountAction | null
  }
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
    throw new Error(`Échange du code d'autorisation refusé (HTTP ${response.status}).`)
  }
  const requestedAt = Date.now()
  const json = (await response.json()) as {
    access_token: string
    refresh_token?: string
    id_token?: string
    expires_in?: number
  }

  writeTokens({
    accessToken: json.access_token,
    refreshToken: json.refresh_token ?? null,
    idToken: json.id_token ?? null,
    expiresAt: localExpiry(json.access_token, json.expires_in, requestedAt),
  })

  // Action du compte (ex. changement de mot de passe) : son résultat est gardé pour la page de retour.
  if (action) {
    const raw = url.searchParams.get('kc_action_status')
    const status = raw === 'success' || raw === 'cancelled' ? raw : 'error'
    try {
      sessionStorage.setItem(ACTION_RESULT_KEY, JSON.stringify({ action, status } satisfies AccountActionResult))
    } catch {
      // stockage indisponible : simplement pas de message au retour
    }
  }

  // Nettoie ?code=&state= de l'URL visible sans recharger la page.
  url.searchParams.delete('kc_action_status')
  url.searchParams.delete('code')
  url.searchParams.delete('state')
  url.searchParams.delete('session_state')
  url.searchParams.delete('iss')
  window.history.replaceState({}, document.title, url.pathname + url.search)
  return safeReturnPath(returnTo)
}

/** Marge (ms) avant l'expiration du jeton pour le renouveler. */
export const TOKEN_REFRESH_MARGIN_MS = 60_000

/** Fin de validité du jeton selon l'horloge du navigateur : heure de la demande + expires_in. */
export function localExpiry(accessToken: string, expiresIn: number | undefined, requestedAt: number): number {
  if (typeof expiresIn === 'number' && expiresIn > 0) return requestedAt + expiresIn * 1000
  const claims = decodeJwtPayload<KeycloakJwtClaims & { iat?: number }>(accessToken)
  const lifetime = claims.iat ? (claims.exp - claims.iat) * 1000 : Number.POSITIVE_INFINITY
  return Math.min(claims.exp * 1000, requestedAt + lifetime)
}

/** Renouvellement en cours, partagé par les appels simultanés. */
let refreshInFlight: Promise<string | null> | null = null

/** Échange le refresh token contre de nouveaux jetons ; null si Keycloak refuse. */
async function refreshAccessToken(currentRefreshToken: string): Promise<string | null> {
  const requestedAt = Date.now()
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
    return null
  }

  if (!response.ok) {
    // 400/401 : jeton de rafraîchissement refusé -> fin de session.
    if (response.status === 400 || response.status === 401) endLocalSession('session_ended')
    else clearTokens()
    return null
  }

  const json = (await response.json()) as { access_token: string; refresh_token?: string; expires_in?: number }
  writeTokens({
    accessToken: json.access_token,
    // Sans nouveau refresh token, l'ancien est conservé.
    refreshToken: json.refresh_token ?? currentRefreshToken,
    idToken: readTokens()?.idToken ?? null,
    expiresAt: localExpiry(json.access_token, json.expires_in, requestedAt),
  })
  return json.access_token
}

/** Jeton d'accès valide, renouvelé si besoin ; null sans session ou si le renouvellement échoue. */
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

/** Renouvelle le jeton tout de suite ; false si impossible. */
export async function refreshTokensNow(): Promise<boolean> {
  const tokens = readTokens()
  if (!tokens?.refreshToken) return false
  if (!refreshInFlight) {
    refreshInFlight = refreshAccessToken(tokens.refreshToken).finally(() => {
      refreshInFlight = null
    })
  }
  return (await refreshInFlight) !== null
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

let verifyInFlight: Promise<boolean> | null = null

/** userinfo accepte-t-il ce jeton ? (false seulement sur 401 / 403 ; erreur réseau : exception). */
async function userinfoAccepted(token: string): Promise<boolean> {
  const response = await fetch(KEYCLOAK_ENDPOINTS.userinfo(), { headers: { Authorization: `Bearer ${token}` } })
  return !(response.status === 401 || response.status === 403)
}

/** Rafraîchissement en tâche de fond : toutes les `intervalMs`, si l'onglet est affiché et que le jeton arrive à expiration. */
export function startBackgroundRefresh(intervalMs = 15_000): () => void {
  const tick = () => {
    if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
    const tokens = readTokens()
    if (tokens?.refreshToken && Date.now() >= tokens.expiresAt - TOKEN_REFRESH_MARGIN_MS) void getAccessToken()
  }
  const timer = window.setInterval(tick, intervalMs)
  return () => window.clearInterval(timer)
}

/** Vérifie auprès de Keycloak (userinfo) que la session existe encore. */
export function verifySession(): Promise<boolean> {
  if (!verifyInFlight) {
    verifyInFlight = (async () => {
      if (!readTokens()) return false
      const token = await getAccessToken()
      if (!token) {
        // Rafraîchissement refusé (déjà signalé) ou impossible : la session n'est plus utilisable.
        if (readTokens()) return true
        return false
      }
      try {
        if (await userinfoAccepted(token)) return true
        // 401 : jeton refusé ; seul un rafraîchissement refusé prouve la fin de session.
        if (!(await refreshTokensNow())) return readTokens() !== null
        const fresh = readTokens()?.accessToken
        if (fresh && !(await userinfoAccepted(fresh))) {
          endLocalSession('session_ended')
          return false
        }
      } catch {
        // réseau indisponible : on réessaiera à la prochaine vérification
      }
      return true
    })().finally(() => {
      verifyInFlight = null
    })
  }
  return verifyInFlight
}

/** Déconnexion : ferme la session Keycloak et prévient les autres onglets. */
export function logout(): void {
  const tokens = readTokens()
  announceLogout()
  clearTokens()
  rememberLogoutReason('logged_out')

  const params = new URLSearchParams({ client_id: KEYCLOAK_CLIENT_ID, post_logout_redirect_uri: REDIRECT_URI })
  if (tokens?.idToken) {
    params.set('id_token_hint', tokens.idToken)
  }
  window.location.replace(`${KEYCLOAK_ENDPOINTS.endSession()}?${params.toString()}`)
}
