import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import messagesFr from '../../../../keycloak/themes/loadpilot/login/messages/messages_fr.properties?raw'
import loginFtl from '../../../../keycloak/themes/loadpilot/login/login.ftl?raw'
import themeJs from '../../../../keycloak/themes/loadpilot/login/resources/js/loadpilot.js?raw'
import { AuthProvider, SESSION_CHECK_INTERVAL_MS, useAuth } from '../../context/AuthContext'
import { springHttp } from '../api/httpClient'
import { TOKEN_REFRESH_MARGIN_MS, endLocalSession, getAccessToken, localExpiry, logout, onSessionEnded, redirectToLogin, startBackgroundRefresh, verifySession } from './keycloakClient'
import { LOGOUT_STORAGE_KEY, announceLogout, onLogoutAnnounced } from './sessionSync'

// Déconnexion globale : un onglet qui se déconnecte prévient les autres, et une session fermée
// ailleurs (Keycloak) est détectée par chaque onglet.

const TOKENS_KEY = 'loadpilot_kc_tokens'
const jwt = (claims: object) => `h.${btoa(JSON.stringify(claims))}.s`
const accessToken = (minutes = 5) => jwt({ sub: 'u-1', name: 'Admin Test', realm_access: { roles: ['ROLE_SUPER_ADMIN'] }, exp: Math.floor(Date.now() / 1000) + minutes * 60 })
const storeSession = (overrides: object = {}) => sessionStorage.setItem(TOKENS_KEY, JSON.stringify({
  accessToken: accessToken(), refreshToken: 'refresh-1', idToken: 'id-1', expiresAt: Date.now() + 5 * 60_000, ...overrides,
}))
const flush = () => new Promise((r) => setTimeout(r, 20))

const realLocation = window.location
let navigatedTo: string[] = []
const reload = vi.fn()

beforeEach(() => {
  sessionStorage.clear()
  navigatedTo = []
  reload.mockReset()
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { ...realLocation, origin: 'http://localhost:3000', href: 'http://localhost:3000/executions', pathname: '/executions', search: '',
      assign: () => { throw new Error('assign interdit : la page quittée resterait dans l’historique') },
      replace: (u: string) => { navigatedTo.push(u) }, reload },
  })
})

afterEach(() => {
  Object.defineProperty(window, 'location', { configurable: true, value: realLocation })
  vi.unstubAllGlobals()
  vi.useRealTimers()
  document.documentElement.style.visibility = ''
})

describe('Synchronisation entre onglets (sessionSync)', () => {
  it('BroadcastChannel : l’annonce d’un onglet est reçue par les autres, jamais par lui-même', async () => {
    const otherTab = new BroadcastChannel('loadpilot-auth')
    const received: unknown[] = []
    otherTab.onmessage = (e) => received.push(e.data)
    const listener = vi.fn()
    const off = onLogoutAnnounced(listener)

    announceLogout()
    await flush()
    expect(received).toEqual([{ type: 'logout', at: expect.any(Number) }])
    expect(listener).not.toHaveBeenCalled()

    otherTab.postMessage({ type: 'logout', at: Date.now() })
    await waitFor(() => expect(listener).toHaveBeenCalledTimes(1))
    off()
    otherTab.close()
  })

  it('solution de secours : l’événement « storage » d’un autre onglet est aussi entendu', () => {
    const listener = vi.fn()
    const off = onLogoutAnnounced(listener)
    window.dispatchEvent(new StorageEvent('storage', { key: LOGOUT_STORAGE_KEY, newValue: '{"type":"logout"}' }))
    window.dispatchEvent(new StorageEvent('storage', { key: 'autre_cle', newValue: 'x' }))
    expect(listener).toHaveBeenCalledTimes(1)
    off()
  })
})

describe('Déconnexion (keycloakClient)', () => {
  it('bouton Déconnexion : session Keycloak terminée (id_token_hint), autres onglets prévenus, jetons effacés, sans historique', async () => {
    storeSession()
    const otherTab = new BroadcastChannel('loadpilot-auth')
    const received: unknown[] = []
    otherTab.onmessage = (e) => received.push(e.data)

    logout()
    const url = new URL(navigatedTo[0])
    expect(url.pathname).toMatch(/\/realms\/loadpilot\/protocol\/openid-connect\/logout$/)
    expect(url.searchParams.get('id_token_hint')).toBe('id-1')
    expect(url.searchParams.get('post_logout_redirect_uri')).toBe('http://localhost:3000/')
    expect(sessionStorage.getItem(TOKENS_KEY)).toBeNull()
    await waitFor(() => expect(received).toHaveLength(1))
    otherTab.close()

    // Retour sur l'application puis vers la connexion : message et formulaire obligatoire.
    await redirectToLogin('/')
    const login = new URL(navigatedTo[1])
    expect(login.searchParams.get('lp_reason')).toBe('logged_out')
    expect(login.searchParams.get('prompt')).toBe('login')
    // Le motif ne sert qu'une fois : une connexion suivante ordinaire n'a pas de message.
    await redirectToLogin('/')
    expect(new URL(navigatedTo[2]).searchParams.has('lp_reason')).toBe(false)
  })

  it('fin de session locale : prévient l’application seulement si une session existait', () => {
    const listener = vi.fn()
    const off = onSessionEnded(listener)
    endLocalSession()
    expect(listener).not.toHaveBeenCalled()
    storeSession()
    endLocalSession()
    expect(listener).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem(TOKENS_KEY)).toBeNull()
    off()
  })

  it('jeton de rafraîchissement refusé (session fermée ailleurs) : fin de session ; Keycloak injoignable : rien n’est décidé', async () => {
    const listener = vi.fn()
    const off = onSessionEnded(listener)
    storeSession({ expiresAt: Date.now() - 1000 })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('réseau')))
    expect(await getAccessToken()).toBeNull()
    expect(listener).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(TOKENS_KEY)).not.toBeNull()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, json: async () => ({ error: 'invalid_grant' }) }))
    expect(await getAccessToken()).toBeNull()
    expect(listener).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem(TOKENS_KEY)).toBeNull()
    off()
  })

  it('vérification de session (userinfo) : 401 -> fin de session ; 200 -> session gardée', async () => {
    const listener = vi.fn()
    const off = onSessionEnded(listener)
    storeSession()
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    vi.stubGlobal('fetch', fetchMock)
    expect(await verifySession()).toBe(true)
    expect(fetchMock.mock.calls[0][0]).toMatch(/\/protocol\/openid-connect\/userinfo$/)
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toMatch(/^Bearer h\./)

    fetchMock.mockResolvedValue({ ok: false, status: 401 })
    expect(await verifySession()).toBe(false)
    expect(listener).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem(TOKENS_KEY)).toBeNull()
    off()
  })

  it('réponse 401 du serveur LoadPilot : la session est vérifiée auprès de Keycloak', async () => {
    storeSession()
    const fetchMock = vi.fn(async (url: string) => (String(url).includes('userinfo')
      ? { ok: false, status: 401 }
      : { ok: false, status: 401, json: async () => ({ message: 'Non authentifié' }) }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(springHttp.get('/api/scenarios')).rejects.toThrow('Non authentifié')
    await waitFor(() => expect(sessionStorage.getItem(TOKENS_KEY)).toBeNull())
    expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith('/userinfo'))).toBe(true)
  })
})

function WhoAmI() {
  const { user, isLoading } = useAuth()
  if (isLoading) return <p>chargement</p>
  return <p data-testid="who">{user ? `connecté : ${user.name}` : 'déconnecté'}</p>
}

describe('Onglet ouvert (AuthProvider)', () => {
  it('un autre onglet se déconnecte : jetons effacés et écran vidé tout de suite', async () => {
    storeSession()
    render(<AuthProvider><WhoAmI /></AuthProvider>)
    await waitFor(() => expect(screen.getByTestId('who').textContent).toBe('connecté : Admin Test'))

    const otherTab = new BroadcastChannel('loadpilot-auth')
    otherTab.postMessage({ type: 'logout', at: Date.now() })
    await waitFor(() => expect(screen.getByTestId('who').textContent).toBe('déconnecté'))
    expect(sessionStorage.getItem(TOKENS_KEY)).toBeNull()
    otherTab.close()
  })

  it('session fermée ailleurs : détectée au retour sur l’onglet (userinfo 401)', async () => {
    storeSession()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }))
    render(<AuthProvider><WhoAmI /></AuthProvider>)
    await waitFor(() => expect(screen.getByTestId('who').textContent).toBe('connecté : Admin Test'))
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    await waitFor(() => expect(screen.getByTestId('who').textContent).toBe('déconnecté'))
  })

  it('session fermée ailleurs : détectée par la vérification régulière (30 s) tant que l’onglet est affiché', async () => {
    expect(SESSION_CHECK_INTERVAL_MS).toBe(30_000)
    vi.useFakeTimers({ shouldAdvanceTime: true })
    storeSession()
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    vi.stubGlobal('fetch', fetchMock)
    render(<AuthProvider><WhoAmI /></AuthProvider>)
    await waitFor(() => expect(screen.getByTestId('who').textContent).toBe('connecté : Admin Test'))
    fetchMock.mockResolvedValue({ ok: false, status: 401 })
    await act(async () => { await vi.advanceTimersByTimeAsync(SESSION_CHECK_INTERVAL_MS) })
    await waitFor(() => expect(screen.getByTestId('who').textContent).toBe('déconnecté'))
  })

  it('« Précédent » vers une page en cache après la déconnexion : masquée et rechargée (jamais affichée)', async () => {
    render(<AuthProvider><WhoAmI /></AuthProvider>)
    const event = new Event('pageshow') as PageTransitionEvent
    Object.defineProperty(event, 'persisted', { value: true })
    window.dispatchEvent(event)
    expect(document.documentElement.style.visibility).toBe('hidden')
    expect(reload).toHaveBeenCalledTimes(1)
  })
})

describe('Page de connexion Keycloak (thème loadpilot)', () => {
  it('message « Vous avez été déconnecté. » affiché seulement au retour d’une déconnexion', () => {
    expect(messagesFr).toMatch(/^lpLoggedOut=Vous avez été déconnecté\.$/m)
    expect(loginFtl).toContain('<div id="lp-logged-out" hidden><@lp.alert type="info">${msg("lpLoggedOut")}</@lp.alert></div>')
    expect(themeJs).toContain("get('lp_reason') === 'logged_out'")
  })
})

describe('Session qui ne doit PAS expirer trop vite (horloge de Keycloak en avance, rafraîchissement)', () => {
  const tokenResponse = (claims: object = {}) => ({
    ok: true, status: 200,
    json: async () => ({ access_token: jwt({ sub: 'u-1', name: 'Admin Test', realm_access: { roles: ['ROLE_SUPER_ADMIN'] }, ...claims }),
      refresh_token: 'refresh-2', expires_in: 300 }),
  })

  it('fin de validité calculée sur l’horloge du navigateur (expires_in), pas sur « exp » écrit par Keycloak', () => {
    const now = Date.now()
    // Keycloak en avance de 45 s : son « exp » ferait croire le jeton valable 345 s au lieu de 300 s.
    const token = jwt({ sub: 'u', iat: Math.floor(now / 1000) + 45, exp: Math.floor(now / 1000) + 345 })
    expect(localExpiry(token, 300, now)).toBe(now + 300_000)
    // Sans expires_in : jamais au-delà de la durée de vie du jeton (exp - iat).
    expect(localExpiry(token, undefined, now)).toBe(now + 300_000)
    expect(TOKEN_REFRESH_MARGIN_MS).toBe(60_000)
  })

  it('userinfo 401 avec un jeton tout juste expiré : rafraîchissement réussi, session GARDÉE (plus de « Please re-authenticate »)', async () => {
    const listener = vi.fn()
    const off = onSessionEnded(listener)
    storeSession()
    let userinfoCalls = 0
    const fetchMock = vi.fn(async (url: string) => {
      if (String(url).endsWith('/userinfo')) return { ok: userinfoCalls++ > 0, status: userinfoCalls > 1 ? 200 : 401 }
      return tokenResponse()
    })
    vi.stubGlobal('fetch', fetchMock)
    expect(await verifySession()).toBe(true)
    expect(listener).not.toHaveBeenCalled()
    const stored = JSON.parse(sessionStorage.getItem(TOKENS_KEY)!)
    expect(stored.refreshToken).toBe('refresh-2')
    expect(stored.expiresAt).toBeGreaterThan(Date.now() + 290_000)
    expect(fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/token'))).toHaveLength(1)
    off()
  })

  it('session réellement terminée (rafraîchissement refusé) : connexion normale, sans prompt=login, avec le message', async () => {
    storeSession({ expiresAt: Date.now() - 1000 })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, json: async () => ({ error: 'invalid_grant' }) }))
    expect(await getAccessToken()).toBeNull()
    await redirectToLogin('/')
    const login = new URL(navigatedTo[0])
    expect(login.searchParams.get('lp_reason')).toBe('logged_out')
    expect(login.searchParams.has('prompt')).toBe(false)
  })

  it('rafraîchissement en tâche de fond : avant la fin du jeton, sans requête ni action, onglet affiché seulement', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    storeSession({ expiresAt: Date.now() + 70_000 })
    const fetchMock = vi.fn().mockResolvedValue(tokenResponse())
    vi.stubGlobal('fetch', fetchMock)
    const stop = startBackgroundRefresh(15_000)
    await vi.advanceTimersByTimeAsync(15_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/protocol\/openid-connect\/token$/)
    expect(String(fetchMock.mock.calls[0][1].body)).toContain('grant_type=refresh_token')
    await vi.waitFor(() => expect(JSON.parse(sessionStorage.getItem(TOKENS_KEY)!).refreshToken).toBe('refresh-2'))
    // Jeton neuf : plus rien à faire.
    await vi.advanceTimersByTimeAsync(30_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    // Onglet masqué : pas de rafraîchissement (la session peut expirer par inactivité, comme prévu).
    storeSession({ expiresAt: Date.now() + 10_000 })
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    await vi.advanceTimersByTimeAsync(15_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
    stop()
  })
})
