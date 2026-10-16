import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import sidebarSource from './Sidebar.tsx?raw'
import appSource from '../App.tsx?raw'
import indexHtml from '../../index.html?raw'
import loginSource from '../pages/Login.tsx?raw'
import themeLogo from '../../../keycloak/themes/loadpilot/login/lp.ftl?raw'
import themeMessagesFr from '../../../keycloak/themes/loadpilot/login/messages/messages_fr.properties?raw'
import packageJson from '../../package.json?raw'
import { ToastProvider } from '../context/ToastContext'
import { resetAppVersionCache } from '../utils/appVersion'

const auth = vi.hoisted(() => ({ roles: ['ROLE_SUPER_ADMIN'], logout: vi.fn() }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({
  authProvider: 'keycloak', rawRoles: auth.roles, logout: auth.logout,
  user: { name: 'Admin Test', initials: 'AT', role: 'Admin' },
}) }))
const exec = vi.hoisted(() => ({ getPage: vi.fn(), execute: vi.fn() }))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: exec }))
// Version renvoyée par GET /api/version (le numéro n'est jamais écrit dans le frontend).
const appInfo = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../services/api/appInfoBackend', () => ({ appInfoBackendApi: appInfo }))
vi.mock('../services/api/scenariosBackend', () => ({ scenariosBackendApi: { getAll: () => Promise.resolve([
  { id: 'sc-1', name: 'Parcours bancaire', applicationName: 'Banking', status: 'ACTIVE', archived: false, applicationArchived: false,
    virtualUsers: 10, rampUpSeconds: 10, durationSeconds: 60, iterations: null },
  { id: 'sc-2', name: 'Sans étape', applicationName: 'Banking', status: 'ACTIVE', archived: false, applicationArchived: false,
    virtualUsers: 1, rampUpSeconds: 0, durationSeconds: null, iterations: 1 },
  { id: 'sc-3', name: 'Inactif', applicationName: 'Banking', status: 'INACTIVE', archived: false, applicationArchived: false,
    virtualUsers: 1, rampUpSeconds: 0, durationSeconds: null, iterations: 1 },
]) } }))
vi.mock('../services/api/stepsBackend', () => ({ stepsBackendApi: { getAll: () => Promise.resolve([
  { id: 'st-1', scenarioId: 'sc-1' }, { id: 'st-2', scenarioId: 'sc-3' },
]) } }))

import Sidebar from './Sidebar'

const counts = (running: number) => ({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0,
  counts: { total: running, queued: 0, running, paused: 0, success: 0, failed: 0, cancelled: 0, thresholdsExceeded: 0 } })

function Location() {
  const l = useLocation()
  return <div data-testid="location">{l.pathname}{l.search}</div>
}

const toggleDarkMode = vi.fn()
const renderAt = (url = '/', darkMode = false) => render(
  <MemoryRouter initialEntries={[url]}>
    <ToastProvider>
      <Sidebar darkMode={darkMode} toggleDarkMode={toggleDarkMode} mobileOpen={false} onCloseMobile={() => {}} />
      <Routes><Route path="*" element={<Location />} /></Routes>
    </ToastProvider>
  </MemoryRouter>,
)

const nav = () => screen.getByRole('navigation')

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_SUPER_ADMIN']
  exec.getPage.mockResolvedValue(counts(0))
  resetAppVersionCache()
  appInfo.get.mockResolvedValue({ name: 'LoadPilot', version: '1.0.0' })
  try { localStorage.setItem('pt_sidebar_expanded', '1') } catch { /* ignoré */ }
})

describe('Barre latérale', () => {
  it('« LoadPilot », sections TESTS / ANALYSE (Comparer, Rapports) / ADMINISTRATION en français, sans Notifications', async () => {
    renderAt()
    expect(screen.getByText('LoadPilot')).toBeTruthy()
    const labels = within(nav()).getAllByRole('link').map((a) => a.getAttribute('aria-label'))
    expect(labels).toEqual(['Dashboard', 'Applications', 'Scénarios', 'Exécutions', 'Planification', 'Comparer', 'Rapports',
      'Utilisateurs et rôles', 'Journal d’audit', 'Paramètres'])
    expect(within(nav()).getAllByRole('group').map((g) => g.getAttribute('aria-label'))).toEqual([null, 'Tests', 'Analyse', 'Administration'])
    for (const old of ['Overview', 'Account', 'Users & Roles', 'Audit Logs', 'Settings', 'Configurations', 'Catalogue des rôles', 'Notifications', 'Reports']) {
      expect(within(nav()).queryByText(old)).toBeNull()
    }
    expect(within(nav()).getByRole('link', { name: 'Comparer' }).getAttribute('href')).toBe('/executions/compare')
    expect(within(nav()).getByRole('link', { name: 'Paramètres' }).getAttribute('href')).toBe('/parametres')
    expect(within(nav()).getByRole('link', { name: 'Rapports' }).getAttribute('href')).toBe('/rapports')
  })

  it('ADMINISTRATION visible seulement pour le Super Administrateur ; VIEWER sans « Lancer un test »', () => {
    auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
    const { unmount } = renderAt()
    expect(within(nav()).queryByRole('group', { name: 'Administration' })).toBeNull()
    expect(within(nav()).queryByRole('link', { name: 'Paramètres' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Lancer un test' })).toBeTruthy()
    unmount()
    auth.roles = ['ROLE_VIEWER']
    renderAt()
    expect(screen.queryByRole('button', { name: 'Lancer un test' })).toBeNull()
  })

  it('élément actif : Exécutions sur ses sous-pages, mais Comparer sur la comparaison', () => {
    const { unmount } = renderAt('/executions/report/ex-1')
    expect(within(nav()).getByRole('link', { name: 'Exécutions' }).getAttribute('aria-current')).toBe('page')
    unmount()
    renderAt('/executions/compare?ids=a,b')
    expect(within(nav()).getByRole('link', { name: 'Comparer' }).getAttribute('aria-current')).toBe('page')
    expect(within(nav()).getByRole('link', { name: 'Exécutions' }).getAttribute('aria-current')).toBeNull()
  })

  it('badge « Exécutions » : nombre d’exécutions en cours, masqué à 0', async () => {
    exec.getPage.mockResolvedValue({ ...counts(2), counts: { ...counts(2).counts, queued: 1 } })
    renderAt()
    const link = await within(nav()).findByRole('link', { name: 'Exécutions (3 exécutions en cours)' })
    expect(within(link).getByText('3')).toBeTruthy()
    exec.getPage.mockResolvedValue(counts(0))
  })

  it('thème : bouton icône soleil / lune avec infobulle', () => {
    const { unmount } = renderAt('/', false)
    const btn = screen.getByRole('button', { name: 'Passer en mode sombre' })
    expect(btn.getAttribute('title')).toBe('Passer en mode sombre')
    expect(btn.querySelector('.bi-moon-stars')).toBeTruthy()
    fireEvent.click(btn)
    expect(toggleDarkMode).toHaveBeenCalled()
    unmount()
    renderAt('/', true)
    expect(screen.getByRole('button', { name: 'Passer en mode clair' }).querySelector('.bi-sun')).toBeTruthy()
    expect(screen.queryByText('Mode clair')).toBeNull()
  })

  it('pied : plus de carte utilisateur (le compte est dans la barre du haut), « LoadPilot v1.0.0 · © année » non cliquable', async () => {
    renderAt()
    const aside = screen.getByRole('complementary', { name: 'Navigation principale' })
    expect(within(aside).queryByRole('button', { name: /Mon compte/ })).toBeNull()
    expect(within(aside).queryByRole('button', { name: /Déconnexion/ })).toBeNull()
    expect(within(aside).queryByText('Admin Test')).toBeNull()
    expect(within(aside).queryByText('AT')).toBeNull()
    const line = await within(aside).findByText(`LoadPilot v1.0.0 · © ${new Date().getFullYear()}`)
    expect(line.closest('.sb-version')).toBeTruthy()
    expect(line.closest('a, button')).toBeNull()
    expect(appInfo.get).toHaveBeenCalledTimes(1)
    // Pied : seulement le bouton thème et la ligne de version.
    const footer = aside.querySelector('.sb-footer') as HTMLElement
    expect(within(footer).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['Passer en mode sombre'])
  })

  it('version : replié, version courte seule ; inconnue (serveur injoignable), rien n’est affiché', async () => {
    localStorage.setItem('pt_sidebar_expanded', '0')
    const { unmount } = renderAt()
    const short = await screen.findByText('v1.0.0')
    expect(short.classList.contains('sb-when-collapsed')).toBe(true)
    expect(screen.getByText(/^LoadPilot v1\.0\.0 · ©/).classList.contains('sb-when-expanded')).toBe(true)
    unmount()
    resetAppVersionCache()
    appInfo.get.mockRejectedValue(new Error('hors ligne'))
    renderAt()
    await waitFor(() => expect(appInfo.get).toHaveBeenCalledTimes(2))
    expect(document.querySelector('.sb-version')).toBeNull()
    expect(screen.queryByText(/^v\d/)).toBeNull()
  })

  it('un seul numéro de version : lu sur le serveur, jamais écrit dans le frontend', () => {
    expect(sidebarSource).not.toMatch(/\d+\.\d+\.\d+/)
    expect(JSON.parse(packageJson).version).toBeUndefined()
  })

  it('« Lancer un test » : choix du scénario avec recherche (actifs avec étapes), puis suivi en direct', async () => {
    exec.execute.mockResolvedValue({ id: 'ex-new', scenarioName: 'Parcours bancaire', virtualUsers: 10 })
    renderAt()
    fireEvent.click(screen.getByRole('button', { name: 'Lancer un test' }))
    const dialog = await screen.findByRole('dialog', { name: /Lancer un test/ })
    const combo = await within(dialog).findByRole('combobox', { name: 'Scénario à lancer' })
    fireEvent.focus(combo)
    expect(within(dialog).getAllByRole('option').map((o) => o.textContent)).toEqual(expect.arrayContaining([expect.stringMatching(/Parcours bancaire/)]))
    expect(within(dialog).queryByRole('option', { name: /Sans étape|Inactif/ })).toBeNull()
    fireEvent.change(combo, { target: { value: 'parcours' } })
    fireEvent.mouseDown(within(dialog).getByRole('option', { name: /Parcours bancaire/ }))
    expect(within(dialog).getByLabelText('Charge du scénario').textContent).toMatch(/10\s*utilisateurs virtuels/)
    fireEvent.click(within(dialog).getByRole('button', { name: /Lancer$/ }))
    await waitFor(() => expect(exec.execute).toHaveBeenCalledWith({ scenarioId: 'sc-1' }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions/live/ex-new'))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('mode réduit : icônes seules, libellé accessible et infobulle au survol comme au focus clavier', async () => {
    localStorage.setItem('pt_sidebar_expanded', '0')
    renderAt('/scenarios')
    const link = within(nav()).getByRole('link', { name: 'Applications' })
    act(() => link.focus())
    expect(screen.getByRole('tooltip').textContent).toBe('Applications')
    act(() => link.blur())
    // Sans survol ni focus : libellé de la page active (calculé après l'affichage).
    await waitFor(() => expect(screen.getByRole('tooltip').textContent).toBe('Scénarios'))
    fireEvent.mouseEnter(within(nav()).getByRole('link', { name: 'Planification' }))
    expect(screen.getByRole('tooltip').textContent).toBe('Planification')
    expect(screen.getByRole('button', { name: 'Agrandir le menu' }).getAttribute('aria-expanded')).toBe('false')
  })
})

describe('Nom du produit et anciennes adresses', () => {
  it('« LoadPilot » partout (onglet du navigateur, page de connexion Keycloak, menu), plus aucun « Cadence »', () => {
    expect(indexHtml).toMatch(/<title>LoadPilot — Tests de performance<\/title>/)
    expect(themeLogo).toMatch(/<span class="lp-logo-name">LoadPilot<\/span>/)
    expect(themeMessagesFr).toMatch(/^lpPageTitle=Connexion · LoadPilot$/m)
    for (const src of [indexHtml, loginSource, sidebarSource, appSource, themeLogo, themeMessagesFr]) expect(src).not.toMatch(/Cadence/)
  })

  it('anciennes adresses redirigées vers les nouvelles pages ; Métriques et Historique retirées ; une seule page Rapports', () => {
    const redirects: [string, string][] = [
      ['settings', '/parametres?tab=profil'], ['settings/integrations', '/parametres?tab=integrations'],
      ['configurations', '/parametres?tab=systeme'], ['users-roles', '/utilisateurs?tab=utilisateurs'],
      ['roles-catalog', '/utilisateurs?tab=roles'], ['reports', '/rapports'],
      ['metriques/*', '/'], ['historique/*', '/executions'],
    ]
    for (const [from, to] of redirects) {
      expect(appSource).toContain(`<Route path="${from}" element={<Navigate to="${to}" replace />} />`)
    }
    expect(appSource).toMatch(/<Route path="parametres" element={<Parametres \/>} \/>/)
    expect(appSource).toMatch(/<Route path="utilisateurs" element={<UtilisateursRoles \/>} \/>/)
    expect(appSource).toMatch(/<Route path="notifications" element={<NotificationsPage \/>} \/>/)
    expect(appSource).toMatch(/<Route path="audit-logs" element={<AuditLogs \/>} \/>/)
    expect(appSource).toMatch(/<Route path="rapports" element={<Reports \/>} \/>/)
    expect(Object.keys(import.meta.glob('../pages/{Metriques,Historique,Rapports}.tsx'))).toHaveLength(0)
  })
})
