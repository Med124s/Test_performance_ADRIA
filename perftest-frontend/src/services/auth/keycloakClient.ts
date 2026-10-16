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
import { announceLogout } from './sessionSync'

const PKCE_STORAGE_KEY = 'loadpilot_pkce'
const TOKENS_STORAGE_KEY = 'loadpilot_kc_tokens'
/** Motif de retour à la connexion (gardé le temps du passage par Keycloak, dans cet onglet). */
const LOGOUT_REASON_KEY = 'loadpilot_logout_reason'
/** Paramètre lu par le thème Keycloak « loadpilot » pour afficher « Vous avez été déconnecté. » */
export const LOGOUT_REASON_PARAM = 'lp_reason'
/** logged_out : déconnexion voulue (bouton, autre onglet, « tous les appareils ») -> formulaire de
 * connexion obligatoire ; session_ended : session expirée ou fermée côté serveur (constaté par un
 * rafraîchissement refusé) -> connexion normale (Keycloak redemande le mot de passe si sa session
 * n'existe plus). Les deux affichent « Vous avez été déconnecté. ». */
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

// ------------------------------------------------------------ fin de session
// Prévient l'application (AuthContext) que la session de cet onglet est terminée :
// déconnexion dans un autre onglet, session fermée ailleurs (« tous les appareils »,
// expiration, compte désactivé). L'application efface alors l'écran et repart vers la connexion.
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

/** Chemin interne de l'application, sûr pour un retour après connexion :
 * commence par un seul « / » (jamais une autre origine, ni `//hote`), et
 * jamais `/login` (qui renverrait aussitôt vers Keycloak). */
export function safeReturnPath(path: string | null | undefined): string | null {
  if (!path || !path.startsWith('/') || path.startsWith('//') || path.startsWith('/\\')) return null
  if (path === '/login' || path.startsWith('/login?') || path.startsWith('/login#') || path.startsWith('/login/')) return null
  return path
}

/** Redirige RÉELLEMENT le navigateur vers la page de login Keycloak (le
 * mot de passe est saisi sur cette page Keycloak, jamais dans React).
 * `returnTo` : page de l'application à rouvrir une fois connecté. */
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

/** Ouvre la page « changer mon mot de passe » du système d'authentification (le mot de passe
 * n'est jamais saisi dans React) ; retour sur `returnTo` avec le résultat (voir consumeAccountActionResult). */
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
  // Après une déconnexion : message sur la page de connexion. Déconnexion voulue : toujours le
  // formulaire (prompt=login), jamais de reconnexion silencieuse sur une session en train de se
  // fermer. Session expirée : connexion normale (pas de « Please re-authenticate » si la session
  // Keycloak est en fait encore valide).
  const reason = consumeLogoutReason()
  if (reason) {
    params.set(LOGOUT_REASON_PARAM, 'logged_out')
    if (reason === 'logged_out') params.set('prompt', 'login')
  }
  // replace : la page quittée ne reste pas dans l'historique (« Précédent » n'y ramène pas).
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

/** Oublie la session locale (après « se déconnecter de tous les appareils ») puis renvoie à la connexion ;
 * les autres onglets de ce navigateur sont prévenus tout de suite. */
export async function forgetSessionAndLogin(returnTo?: string): Promise<void> {
  announceLogout()
  clearTokens()
  rememberLogoutReason('logged_out')
  await redirectToLogin(returnTo)
}

/** À appeler au montage de l'application : si l'URL contient
 * `?code=...&state=...` (retour de Keycloak), échange le code contre de
 * vrais tokens et nettoie l'URL. Ne fait rien si absent (accès direct à
 * l'application). Renvoie la page demandée avant la connexion, s'il y en a une.
 *
 * Un même retour n'est traité qu'une fois : en développement, React
 * (StrictMode) exécute deux fois l'effet de démarrage ; le second appel
 * reçoit le résultat du premier au lieu de retrouver un état PKCE déjà
 * consommé (ce qui perdait la page demandée). */
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
  if (!pkceRaw) return null // callback inattendu (état PKCE absent) : ignoré, jamais deviné

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

/** Marge (ms) prise avant l'expiration du jeton pour déclencher un renouvellement — évite qu'un
 * jeton, valide au moment de la vérification, expire pendant le trajet jusqu'au serveur. */
export const TOKEN_REFRESH_MARGIN_MS = 60_000

/**
 * Fin de validité du jeton d'accès sur l'HORLOGE DU NAVIGATEUR : heure de la demande + durée de vie
 * annoncée par Keycloak (expires_in). Jamais la date « exp » seule : elle est écrite avec l'horloge
 * de Keycloak, qui peut avancer (ex. conteneur Docker en avance de 45 s) — le jeton paraissait alors
 * valable après son expiration réelle, et la vérification de session concluait à tort à une
 * déconnexion. Sans expires_in : « exp », sans jamais dépasser la durée de vie du jeton (exp - iat).
 */
export function localExpiry(accessToken: string, expiresIn: number | undefined, requestedAt: number): number {
  if (typeof expiresIn === 'number' && expiresIn > 0) return requestedAt + expiresIn * 1000
  const claims = decodeJwtPayload<KeycloakJwtClaims & { iat?: number }>(accessToken)
  const lifetime = claims.iat ? (claims.exp - claims.iat) * 1000 : Number.POSITIVE_INFINITY
  return Math.min(claims.exp * 1000, requestedAt + lifetime)
}

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
    // Réseau indisponible : jamais de token fabriqué, l'appelant retombe
    // simplement sur "non authentifié" pour cette requête.
    return null
  }

  if (!response.ok) {
    // 400/401 : jeton de rafraîchissement refusé (session fermée ailleurs, expirée, compte
    // désactivé) -> fin de session, retour à la connexion. Autre erreur (Keycloak en panne) :
    // seulement pas de jeton pour cette requête.
    if (response.status === 400 || response.status === 401) endLocalSession('session_ended')
    else clearTokens()
    return null
  }

  const json = (await response.json()) as { access_token: string; refresh_token?: string; expires_in?: number }
  writeTokens({
    accessToken: json.access_token,
    // Keycloak ne renvoie pas toujours un nouveau refresh token (dépend de
    // la politique de rotation du realm, voir keycloak/README.md) — celui
    // déjà connu est conservé si absent, jamais mis à `null`.
    refreshToken: json.refresh_token ?? currentRefreshToken,
    idToken: readTokens()?.idToken ?? null,
    expiresAt: localExpiry(json.access_token, json.expires_in, requestedAt),
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

/** Nouveau jeton tout de suite (ex. après « Modifier » mon nom ou mon e-mail : le jeton porte
 * l'identité affichée). Renvoie false si aucun renouvellement n'a été possible. */
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

/**
 * Rafraîchissement en TÂCHE DE FOND : toutes les `intervalMs`, si l'onglet est affiché et que le
 * jeton d'accès arrive à moins de TOKEN_REFRESH_MARGIN_MS de sa fin, il est renouvelé — sans
 * attendre une requête ni une action de l'utilisateur. Onglet masqué : rien (la session Keycloak
 * peut alors expirer par inactivité, comme prévu) ; au retour, la vérification de session
 * renouvelle le jeton si la session existe encore. Renvoie la fonction d'arrêt.
 */
export function startBackgroundRefresh(intervalMs = 15_000): () => void {
  const tick = () => {
    if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
    const tokens = readTokens()
    if (tokens?.refreshToken && Date.now() >= tokens.expiresAt - TOKEN_REFRESH_MARGIN_MS) void getAccessToken()
  }
  const timer = window.setInterval(tick, intervalMs)
  return () => window.clearInterval(timer)
}

/**
 * Vérifie auprès de Keycloak que la session existe encore (userinfo) : le jeton d'accès reste
 * valable jusqu'à 5 min même après une déconnexion ailleurs, lui seul ne suffit pas. Session
 * fermée -> fin de session locale (false). Keycloak injoignable : rien n'est décidé (true).
 */
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
        // 401 : jeton d'accès refusé. Ce n'est PAS forcément une fin de session (jeton tout juste
        // expiré) : seul un rafraîchissement refusé la prouve (refreshAccessToken termine alors la
        // session). Rafraîchissement réussi : nouvelle vérification avec le nouveau jeton.
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

/** Déconnexion RÉELLE : invalide la session SSO Keycloak elle-même (pas
 * seulement l'état local) — voir Phase 16, section 11. Les autres onglets sont
 * prévenus aussitôt. Après l'appel, Keycloak redirige vers `REDIRECT_URI`, où
 * l'application (non connectée) renvoie vers la page de connexion LoadPilot,
 * avec le message « Vous avez été déconnecté. » */
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
