import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const auth = vi.hoisted(() => ({
  state: { isAuthenticated: false, isLoading: false, returnTo: null as string | null, authError: null as string | null },
  loginWithKeycloak: vi.fn(),
  clearReturnTo: vi.fn(),
}))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({
  ...auth.state, loginWithKeycloak: auth.loginWithKeycloak, clearReturnTo: auth.clearReturnTo,
}) }))

import ProtectedRoute from './ProtectedRoute'
import Login from '../pages/Login'
import { handleRedirectCallback, redirectToLogin, safeReturnPath } from '../services/auth/keycloakClient'

function Location() {
  const l = useLocation()
  return <div data-testid="location">{l.pathname}{l.search}{l.hash}</div>
}

const renderApp = (url: string) => render(
  <MemoryRouter initialEntries={[url]}>
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="*" element={<ProtectedRoute><div>PAGE PROTÉGÉE</div></ProtectedRoute>} />
    </Routes>
    <Routes><Route path="*" element={<Location />} /></Routes>
  </MemoryRouter>,
)

beforeEach(() => {
  vi.clearAllMocks()
  auth.state = { isAuthenticated: false, isLoading: false, returnTo: null, authError: null }
})

describe('Connexion sans page intermédiaire', () => {
  it('utilisateur non connecté : envoyé directement vers Keycloak avec la page demandée, aucun champ mot de passe dans React', async () => {
    const { container } = renderApp('/executions/report/ex-1?tab=erreurs#top')
    await waitFor(() => expect(auth.loginWithKeycloak).toHaveBeenCalledTimes(1))
    expect(auth.loginWithKeycloak).toHaveBeenCalledWith('/executions/report/ex-1?tab=erreurs#top')
    expect(screen.getByRole('status').textContent).toMatch(/Redirection vers la page de connexion/)
    expect(screen.queryByText('PAGE PROTÉGÉE')).toBeNull()
    expect(container.querySelector('input')).toBeNull()
  })

  it('pendant le traitement du retour Keycloak : rien n’est décidé (pas de redirection)', () => {
    auth.state.isLoading = true
    renderApp('/scenarios')
    expect(auth.loginWithKeycloak).not.toHaveBeenCalled()
    expect(screen.queryByText('PAGE PROTÉGÉE')).toBeNull()
  })

  it('après la connexion : retour sur la page demandée, une seule fois', async () => {
    auth.state = { isAuthenticated: true, isLoading: false, returnTo: '/planification?view=history', authError: null }
    renderApp('/')
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/planification?view=history'))
    expect(auth.clearReturnTo).toHaveBeenCalled()
    expect(auth.loginWithKeycloak).not.toHaveBeenCalled()
  })

  it('échec du retour Keycloak : pas de boucle de redirection, bouton « Réessayer la connexion »', () => {
    auth.state.authError = 'Échange du code refusé'
    renderApp('/executions')
    expect(auth.loginWithKeycloak).not.toHaveBeenCalled()
    expect(screen.getByRole('alert').textContent).toMatch(/Connexion impossible/)
    fireEvent.click(screen.getByRole('button', { name: /Réessayer la connexion/ }))
    expect(auth.loginWithKeycloak).toHaveBeenCalledWith('/executions')
  })

  it('ancienne adresse /login : redirige vers la connexion (non connecté) ou vers l’application (connecté)', async () => {
    const { unmount } = renderApp('/login')
    await waitFor(() => expect(auth.loginWithKeycloak).toHaveBeenCalledWith('/'))
    unmount()
    auth.state.isAuthenticated = true
    renderApp('/login')
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/'))
    expect(screen.getByText('PAGE PROTÉGÉE')).toBeTruthy()
  })
})

describe('Page de retour après connexion (keycloakClient)', () => {
  it('safeReturnPath : seulement un chemin interne, jamais /login ni une autre origine', () => {
    expect(safeReturnPath('/executions?x=1#a')).toBe('/executions?x=1#a')
    for (const bad of [null, undefined, '', 'executions', 'https://evil.example/', '//evil.example/x', '/\\evil.example', '/login', '/login?x=1']) {
      expect(safeReturnPath(bad)).toBeNull()
    }
  })

  describe('aller-retour Keycloak', () => {
    const realLocation = window.location
    let assigned = ''

    beforeEach(() => {
      sessionStorage.clear()
      assigned = ''
      Object.defineProperty(window, 'location', {
        configurable: true,
        // replace : la page de connexion remplace l'entrée d'historique (« Précédent » n'y ramène pas).
        value: { ...realLocation, origin: 'http://localhost:3000', href: 'http://localhost:3000/', assign: () => { throw new Error('assign interdit') }, replace: (u: string) => { assigned = u } },
      })
    })

    afterEach(() => {
      Object.defineProperty(window, 'location', { configurable: true, value: realLocation })
      vi.unstubAllGlobals()
    })

    it('la page demandée est mémorisée avec l’état PKCE puis rendue après l’échange du code', async () => {
      await redirectToLogin('/scenarios/sc-1/edit')
      const authUrl = new URL(assigned)
      expect(authUrl.pathname).toMatch(/\/realms\/loadpilot\/protocol\/openid-connect\/auth$/)
      expect(authUrl.searchParams.get('code_challenge_method')).toBe('S256')
      expect(authUrl.searchParams.has('password')).toBe(false)
      const state = authUrl.searchParams.get('state')!

      const payload = btoa(JSON.stringify({ sub: 'u1', exp: Math.floor(Date.now() / 1000) + 300 }))
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ access_token: `h.${payload}.s` }) }))
      window.location.href = `http://localhost:3000/?state=${state}&session_state=abc&iss=x&code=c0de`
      const replace = vi.spyOn(window.history, 'replaceState').mockImplementation(() => {})

      // Deux appels (effet exécuté deux fois par React en développement) : un seul échange, même résultat.
      const [first, second] = await Promise.all([handleRedirectCallback(), handleRedirectCallback()])
      expect(first).toBe('/scenarios/sc-1/edit')
      expect(second).toBe('/scenarios/sc-1/edit')
      expect(fetch).toHaveBeenCalledTimes(1)
      expect(replace).toHaveBeenCalledWith({}, expect.any(String), '/')
      replace.mockRestore()
    })

    it('une page de retour non sûre n’est jamais conservée', async () => {
      await redirectToLogin('https://evil.example/')
      expect(JSON.parse(sessionStorage.getItem('loadpilot_pkce')!).returnTo).toBeNull()
    })
  })
})
