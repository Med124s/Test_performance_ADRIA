import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendAuditLogResponse } from '../../types/backendContracts'

const auth = vi.hoisted(() => ({ roles: ['ROLE_SUPER_ADMIN'] }))
vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: auth.roles, user: { id: 'u-admin' } }) }))
const api = vi.hoisted(() => ({ list: vi.fn(), actors: vi.fn(), exportCsv: vi.fn(), settings: vi.fn(), updateSettings: vi.fn(), getById: vi.fn() }))
vi.mock('../../services/api/auditBackend', () => ({ auditBackendApi: api, SYSTEM_ACTOR: '__system' }))
const toast = vi.hoisted(() => ({ showToast: vi.fn() }))
vi.mock('../../context/ToastContext', () => ({ useToast: () => toast }))
vi.mock('../../components/TopBar', () => ({ default: () => null }))

import AuditLogs from '../AuditLogs'
import AuditSettingsCard from './AuditSettingsCard'
import { browserLabel, targetLink } from './AuditDetailPanel'
import { parseFrenchDate } from './auditDates'
import pageSource from '../AuditLogs.tsx?raw'
import panelSource from './AuditDetailPanel.tsx?raw'

const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString()

function entry(over: Partial<BackendAuditLogResponse>): BackendAuditLogResponse {
  return {
    id: 'e1', date: minutesAgo(3), actorType: 'USER', userId: 'u-admin', username: 'admin-test', actorSource: null,
    action: 'UPDATE', actionLabel: 'Modification', module: 'APPLICATION', category: 'APPLICATIONS', categoryLabel: 'Applications',
    result: 'SUCCESS', resultLabel: 'Réussi', summary: 'Modification de l’application « Banking »', detail: null,
    targetType: 'APPLICATION', targetId: 'app-1', targetLabel: 'Banking', targetExists: true,
    changes: [{ label: 'URL de base', before: 'http://old', after: 'http://new' }, { label: 'Nom', before: 'Bank', after: 'Banking' }],
    ip: '127.0.0.1', ipLabel: 'localhost', userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/129.0 Safari/537.36',
    legacy: false, description: null, ...over,
  }
}

const ROWS: BackendAuditLogResponse[] = [
  entry({}),
  entry({ id: 'e2', actorType: 'SYSTEM', userId: null, username: null, actorSource: 'Planification : Test nocturne', action: 'FINISH',
    actionLabel: 'Fin d’exécution', module: 'EXECUTION', category: 'EXECUTIONS', categoryLabel: 'Exécutions', summary: 'Fin d’exécution « Parcours du 07/10 à 14:32 »',
    detail: 'Exécution terminée : Réussie', targetType: 'EXECUTION', targetId: 'ex-9', targetLabel: 'Parcours du 07/10 à 14:32', targetExists: false, changes: [] }),
  entry({ id: 'e3', username: 'bob', userId: null, action: 'LOGIN_FAILED', actionLabel: 'Échec de connexion', module: 'SECURITY', category: 'SECURITY',
    categoryLabel: 'Sécurité', result: 'FAILURE', resultLabel: 'Échec', summary: 'Échec de connexion', detail: 'Mot de passe incorrect (nom saisi : bob)',
    actorSource: 'Authentification', targetType: null, targetId: null, targetLabel: null, targetExists: false, changes: [], ip: '10.0.0.8', ipLabel: '10.0.0.8' }),
]

let location = ''
function LocationProbe() {
  const loc = useLocation()
  location = loc.pathname + loc.search
  return null
}

const renderPage = (url = '/audit-logs') => render(
  <MemoryRouter initialEntries={[url]}><AuditLogs /><LocationProbe /></MemoryRouter>,
)
const lastFilters = () => api.list.mock.calls[api.list.mock.calls.length - 1][0]

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_SUPER_ADMIN']
  api.list.mockResolvedValue({ content: ROWS, page: 0, size: 20, totalElements: 3, totalPages: 1 })
  api.actors.mockResolvedValue(['admin-test', 'bob'])
  api.settings.mockResolvedValue({ logReads: false, retentionDays: 365 })
})

describe('Journal d’audit — page', () => {
  it('titre, sous-titre, pas de cartes de statistiques ni de répartitions', async () => {
    const { container } = renderPage()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Journal d’audit')
    expect(screen.getByText('Qui a fait quoi, et quand')).toBeTruthy()
    await screen.findByText('Modification de l’application « Banking »')
    expect(container.textContent).not.toMatch(/Total des actions|Actions réussies|Répartition|par module/i)
    expect(pageSource).not.toMatch(/getStats|\/stats/)
  })

  it('tableau : date relative (complète au survol), avatar ou « Système » + source, phrase + avant → après, ✓ / ✕', async () => {
    renderPage()
    await screen.findByText('Modification de l’application « Banking »')
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Date', 'Utilisateur', 'Action', 'Résultat', 'Actions'])
    const [first, system, failed] = screen.getAllByRole('row').slice(1)
    const time = first.querySelector('time')!
    expect(time.textContent).toMatch(/il y a 3 min/)
    expect(time.getAttribute('title')).toMatch(/\d{4}/)
    expect(within(first).getByText('AT')).toBeTruthy()
    expect(within(first).getByText('URL de base : http://old → http://new (+1 autre)')).toBeTruthy()
    expect(within(first).getByRole('link', { name: 'Modification de l’application « Banking »' }).getAttribute('href')).toBe('/applications')
    expect(within(first).getByText('Réussi').closest('.au-result')?.className).toContain('ok')

    expect(within(system).getByText('Système')).toBeTruthy()
    expect(within(system).getByText('Planification : Test nocturne')).toBeTruthy()
    // L'exécution n'existe plus : pas de lien.
    expect(within(system).queryByRole('link')).toBeNull()
    expect(within(system).getByText('Exécution terminée : Réussie')).toBeTruthy()

    expect(within(failed).getByText('Échec').closest('.au-result')?.className).toContain('ko')
    expect(within(failed).getByText('Mot de passe incorrect (nom saisi : bob)')).toBeTruthy()
  })

  it('filtres côté serveur, gardés dans l’URL, avec « Réinitialiser »', async () => {
    renderPage()
    await screen.findByText('Modification de l’application « Banking »')
    expect(screen.queryByRole('button', { name: /Réinitialiser/ })).toBeNull()
    expect(lastFilters()).toEqual({ q: undefined, actor: undefined, category: undefined, result: undefined })

    fireEvent.change(screen.getByRole('combobox', { name: 'Catégorie' }), { target: { value: 'SECURITY' } })
    await waitFor(() => expect(lastFilters().category).toBe('SECURITY'))
    expect(location).toContain('categorie=SECURITY')

    fireEvent.change(screen.getByRole('combobox', { name: 'Résultat' }), { target: { value: 'FAILURE' } })
    await waitFor(() => expect(lastFilters().result).toBe('FAILURE'))

    const users = screen.getByRole('combobox', { name: 'Utilisateur' })
    await waitFor(() => expect(within(users).getByRole('option', { name: 'bob' })).toBeTruthy())
    expect(within(users).getByRole('option', { name: /Système/ })).toBeTruthy()
    fireEvent.change(users, { target: { value: '__system' } })
    await waitFor(() => expect(lastFilters().actor).toBe('__system'))

    fireEvent.change(screen.getByRole('combobox', { name: 'Période' }), { target: { value: '7j' } })
    await waitFor(() => expect(lastFilters().from).toBeTruthy())
    const from = new Date(lastFilters().from).getTime()
    expect(Math.abs(Date.now() - 7 * 24 * 3_600_000 - from)).toBeLessThan(60_000)

    fireEvent.click(screen.getByRole('button', { name: /Réinitialiser$/ }))
    await waitFor(() => expect(lastFilters()).toEqual({ q: undefined, actor: undefined, category: undefined, result: undefined }))
    expect(location).toBe('/audit-logs')
  })

  it('recherche libre envoyée au serveur après la frappe', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      renderPage()
      await screen.findByText('Modification de l’application « Banking »')
      fireEvent.change(screen.getByRole('searchbox', { name: 'Rechercher dans le journal' }), { target: { value: ' Banking ' } })
      await act(async () => { vi.advanceTimersByTime(350) })
      await waitFor(() => expect(lastFilters().q).toBe('Banking'))
      expect(location).toContain('q=Banking')
    } finally {
      vi.useRealTimers()
    }
  })

  it('les filtres de l’URL sont appliqués au chargement (lien partageable)', async () => {
    renderPage('/audit-logs?categorie=USERS&resultat=SUCCESS&periode=perso&du=01/10/2026&au=07/10/2026&page=2')
    await waitFor(() => expect(api.list).toHaveBeenCalled())
    const [filters, page] = api.list.mock.calls[0]
    expect(filters.category).toBe('USERS')
    expect(filters.result).toBe('SUCCESS')
    expect(new Date(filters.from).getDate()).toBe(1)
    expect(new Date(filters.to).getDate()).toBe(7)
    expect(new Date(filters.to).getHours()).toBe(23)
    expect(page).toBe(1)
    expect((screen.getByRole('textbox', { name: 'Du (jj/mm/aaaa)' }) as HTMLInputElement).value).toBe('01/10/2026')
  })

  it('période personnalisée : une date invalide est signalée et n’est pas envoyée', async () => {
    renderPage('/audit-logs?periode=perso')
    await screen.findByText('Modification de l’application « Banking »')
    const calls = api.list.mock.calls.length
    fireEvent.change(screen.getByRole('textbox', { name: 'Du (jj/mm/aaaa)' }), { target: { value: '31/02/2026' } })
    expect(await screen.findByText('Date attendue au format jj/mm/aaaa.')).toBeTruthy()
    expect(api.list.mock.calls.length).toBe(calls)
  })

  it('panneau de détail (œil) : avant / après, IP « localhost », navigateur, identifiant ; aucune action de modification', async () => {
    renderPage()
    await screen.findByText('Modification de l’application « Banking »')
    fireEvent.click(screen.getByRole('button', { name: 'Voir le détail : Modification de l’application « Banking »' }))
    const dialog = await screen.findByRole('dialog')
    expect(location).toContain('entree=e1')
    expect(within(dialog).getByText('localhost')).toBeTruthy()
    expect(within(dialog).getByText('Chrome sur Windows')).toBeTruthy()
    expect(within(dialog).getByText('e1')).toBeTruthy()
    const changes = within(dialog).getByRole('table')
    expect(within(changes).getByRole('rowheader', { name: 'URL de base' })).toBeTruthy()
    expect(within(changes).getByText('http://old')).toBeTruthy()
    expect(within(changes).getByText('http://new')).toBeTruthy()
    const buttons = within(dialog).getAllByRole('button').map((b) => b.getAttribute('aria-label') ?? b.textContent)
    expect(buttons).toEqual(['Fermer'])
    fireEvent.keyDown(window, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(location).not.toContain('entree=')
  })

  it('export CSV avec les filtres en cours', async () => {
    api.exportCsv.mockResolvedValue(new Blob(['x'], { type: 'text/csv' }))
    const createUrl = vi.fn(() => 'blob:x')
    Object.assign(URL, { createObjectURL: createUrl, revokeObjectURL: vi.fn() })
    renderPage('/audit-logs?categorie=SECURITY')
    await screen.findByText('Modification de l’application « Banking »')
    fireEvent.click(screen.getByRole('button', { name: /Exporter en CSV/ }))
    await waitFor(() => expect(api.exportCsv).toHaveBeenCalled())
    expect(api.exportCsv.mock.calls[0][0].category).toBe('SECURITY')
    await waitFor(() => expect(toast.showToast).toHaveBeenCalledWith(expect.stringContaining('Export CSV'), 'success'))
  })

  it('réservé au Super administrateur : l’ingénieur voit « Accès restreint » sans appel au serveur', () => {
    auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
    renderPage()
    expect(screen.getByText('Accès restreint')).toBeTruthy()
    expect(screen.getByText('Le journal d’audit est réservé au Super administrateur.')).toBeTruthy()
    expect(api.list).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /Exporter/ })).toBeNull()
  })

  it('journal en lecture seule : aucune suppression ni modification proposée', () => {
    for (const source of [pageSource, panelSource]) {
      const apiCalls = [...source.matchAll(/auditBackendApi\.(\w+)/g)].map((m) => m[1])
      expect(apiCalls.every((name) => ['list', 'actors', 'exportCsv'].includes(name))).toBe(true)
      expect(source).not.toMatch(/Supprimer|Modifier l/)
    }
  })
})

describe('Journal d’audit — utilitaires', () => {
  it('ancienne entrée : le texte d’origine (non traduit) n’apparaît pas dans le tableau', async () => {
    api.list.mockResolvedValue({ content: [entry({ id: 'old', legacy: true, changes: [], summary: 'Création', detail: 'Application created: Banking' })],
      page: 0, size: 20, totalElements: 1, totalPages: 1 })
    renderPage()
    await screen.findByText('Création')
    expect(screen.queryByText('Application created: Banking')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Voir le détail : Création' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Texte d’origine')).toBeTruthy()
    expect(within(dialog).getByText('Application created: Banking')).toBeTruthy()
  })

  it('liens vers les objets concernés', () => {
    expect(targetLink({ targetType: 'SCENARIO', targetId: 's1' })).toBe('/scenarios/s1/edit')
    expect(targetLink({ targetType: 'EXECUTION', targetId: 'x1' })).toBe('/executions/report/x1')
    expect(targetLink({ targetType: 'SCHEDULE', targetId: 'p1' })).toBe('/planification/p1')
    expect(targetLink({ targetType: 'USER', targetId: 'u1' })).toBe('/utilisateurs?tab=utilisateurs&user=u1')
    expect(targetLink({ targetType: 'SETTINGS', targetId: 'audit' })).toBe('/parametres?tab=systeme')
    expect(targetLink({ targetType: null, targetId: null })).toBeNull()
  })

  it('dates jj/mm/aaaa', () => {
    expect(parseFrenchDate('07/10/2026')?.getMonth()).toBe(9)
    expect(parseFrenchDate('7/1/2026')?.getDate()).toBe(7)
    expect(parseFrenchDate('31/02/2026')).toBeNull()
    expect(parseFrenchDate('2026-10-07')).toBeNull()
    expect(parseFrenchDate('07/10/2026', true)?.getHours()).toBe(23)
  })

  it('navigateur lisible', () => {
    expect(browserLabel('Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/129.0 Safari/537.36 Edg/129.0')).toBe('Edge sur Windows')
    expect(browserLabel('Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0')).toBe('Firefox sur Linux')
    expect(browserLabel(null)).toBeNull()
  })
})

describe('Paramètres > Système — Journal d’audit', () => {
  const renderCard = () => render(<MemoryRouter><AuditSettingsCard /></MemoryRouter>)

  it('consultations désactivées par défaut, conservation 365 jours ; enregistrement', async () => {
    api.updateSettings.mockResolvedValue({ logReads: true, retentionDays: 180 })
    renderCard()
    const toggle = await screen.findByRole('switch', { name: /Journaliser aussi les consultations/ }) as HTMLInputElement
    expect(toggle.checked).toBe(false)
    const retention = screen.getByRole('textbox', { name: /Durée de conservation/ }) as HTMLInputElement
    expect(retention.value).toBe('365')
    const save = screen.getByRole('button', { name: 'Enregistrer' }) as HTMLButtonElement
    expect(save.disabled).toBe(true)
    fireEvent.click(toggle)
    fireEvent.change(retention, { target: { value: '180' } })
    fireEvent.click(save)
    await waitFor(() => expect(api.updateSettings).toHaveBeenCalledWith({ logReads: true, retentionDays: 180 }))
    expect(toast.showToast).toHaveBeenCalledWith('Réglages du journal d’audit enregistrés.', 'success')
  })

  it('durée hors limites refusée', async () => {
    renderCard()
    const retention = await screen.findByRole('textbox', { name: /Durée de conservation/ })
    fireEvent.change(retention, { target: { value: '10' } })
    expect(screen.getByText('Indiquez un nombre de jours entre 30 et 3650.')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Enregistrer' }) as HTMLButtonElement).disabled).toBe(true)
  })
})
