import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BackendDeletionImpact, BackendScenarioResponse, ScenarioPageQuery } from '../types/backendContracts'
import { ToastProvider } from '../context/ToastContext'

const auth = { authProvider: 'keycloak' as const, rawRoles: ['ROLE_SUPER_ADMIN'] }
vi.mock('../context/AuthContext', () => ({ useAuth: () => auth }))

const api = vi.hoisted(() => ({
  getPage: vi.fn(), activate: vi.fn(), deactivate: vi.fn(), bulkStatus: vi.fn(), bulkDeletionImpact: vi.fn(),
  bulkArchive: vi.fn(), bulkRestore: vi.fn(), bulkDelete: vi.fn(), bulkResumeSchedules: vi.fn(), resumeSchedules: vi.fn(),
  archive: vi.fn(), restore: vi.fn(), remove: vi.fn(), removeDraft: vi.fn(), deletionImpact: vi.fn(), update: vi.fn(), create: vi.fn(),
}))
vi.mock('../services/api/scenariosBackend', () => ({ scenariosBackendApi: api }))
vi.mock('../services/api/applicationsBackend', () => ({ applicationsBackendApi: { getAll: () => Promise.resolve([]) } }))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: { getAll: () => Promise.resolve([]), execute: vi.fn() } }))
vi.mock('../services/api/stepsBackend', () => ({ stepsBackendApi: { getAll: () => Promise.resolve([]), getByScenario: () => Promise.resolve([]) } }))

import Scenarios from './Scenarios'

const scenario = (patch: Partial<BackendScenarioResponse> = {}): BackendScenarioResponse => ({
  id: 'sc-1', applicationId: 'app-1', applicationName: 'Banking', name: 'Parcours virement', description: null, status: 'ACTIVE',
  virtualUsers: 5, rampUpSeconds: 0, durationSeconds: null, iterations: 1, thinkTimeMs: 0, csvData: null, targetRps: null,
  createdBy: 'admin-test', createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', archived: false, archivedAt: null,
  applicationArchived: false, wizardStep: null, draftState: null, pausedSchedules: 0, variables: null, csvDistribution: 'PER_VIRTUAL_USER', ...patch,
})

/** Sert une page comme le backend (filtre de statut, 12 éléments au total pour vérifier la pagination serveur). */
const serve = (list: BackendScenarioResponse[], total = list.length) =>
  api.getPage.mockImplementation((q: ScenarioPageQuery) => {
    const filtered = q.status ? list.filter((s) => s.status === q.status) : list
    return Promise.resolve({ content: filtered.slice(0, q.size), page: q.page, size: q.size, totalElements: q.status ? filtered.length : total, totalPages: Math.ceil((q.status ? filtered.length : total) / q.size) })
  })

const listCalls = () => api.getPage.mock.calls.filter(([q]) => (q as ScenarioPageQuery).sort !== undefined).map(([q]) => q as ScenarioPageQuery)

const impact: BackendDeletionImpact = { applications: 0, scenarios: 2, steps: 6, executions: 4, scheduledExecutions: 1, runningExecutions: 0, hasRunningExecution: false }

const renderPage = (url = '/scenarios') => render(
  <MemoryRouter initialEntries={[url]}>
    <ToastProvider><Scenarios /></ToastProvider>
  </MemoryRouter>,
)

const bar = () => screen.getByRole('region', { name: 'Actions sur la sélection' })

beforeEach(() => {
  vi.clearAllMocks()
  auth.rawRoles = ['ROLE_SUPER_ADMIN']
})

describe('Scénarios — pagination serveur, sélection et actions groupées', () => {
  it('charge la page côté serveur (tri « Plus récents » par défaut) et affiche les compteurs globaux', async () => {
    serve([scenario({ id: 'a', name: 'Alpha' }), scenario({ id: 'b', name: 'Beta', status: 'INACTIVE' })], 12)
    renderPage()
    await screen.findByText('Alpha')
    expect(listCalls()[0]).toMatchObject({ page: 0, size: 10, sort: 'createdAt,desc' })
    expect(screen.getByText('Liste des scénarios (12)')).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Trier'), { target: { value: 'createdAt,asc' } })
    await waitFor(() => expect(listCalls()[listCalls().length - 1]).toMatchObject({ sort: 'createdAt,asc' }))
    expect(screen.getByRole('option', { name: 'Plus récents' })).toBeTruthy()
    expect(screen.getByRole('option', { name: 'Plus anciens' })).toBeTruthy()
  })

  it('VIEWER : aucune case à cocher', async () => {
    auth.rawRoles = ['ROLE_VIEWER']
    serve([scenario()])
    renderPage()
    await screen.findByText('Parcours virement')
    expect(screen.queryAllByRole('checkbox', { name: /Sélectionner/ })).toHaveLength(0)
  })

  it('« tout sélectionner » se limite à la page, barre d’actions complète, sélection vidée au changement de filtre', async () => {
    serve([scenario({ id: 'a', name: 'Alpha' }), scenario({ id: 'b', name: 'Beta' })], 12)
    renderPage()
    await screen.findByText('Alpha')

    fireEvent.click(screen.getByLabelText('Sélectionner tous les scénarios de la page'))
    expect(within(bar()).getByText('2 sélectionnés')).toBeTruthy()
    for (const label of [/Activer/, /Désactiver/, /Archiver/, /Supprimer définitivement/, /Annuler la sélection/]) {
      expect(within(bar()).getByRole('button', { name: label })).toBeTruthy()
    }

    fireEvent.change(screen.getByLabelText('Filtrer par statut'), { target: { value: 'INACTIVE' } })
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Actions sur la sélection' })).toBeNull())
  })

  it('désactivation groupée : succès partiel avec la raison de l’échec', async () => {
    serve([scenario({ id: 'a', name: 'Alpha' }), scenario({ id: 'b', name: 'Beta' })])
    api.bulkStatus.mockResolvedValue({
      results: [
        { id: 'a', name: 'Alpha', success: true, message: 'Désactivé.' },
        { id: 'b', name: 'Beta', success: false, message: 'Une exécution est en cours : attendez sa fin ou arrêtez-la avant de désactiver le scénario.' },
      ],
      succeeded: 1, failed: 1,
    })
    renderPage()
    await screen.findByText('Alpha')
    fireEvent.click(screen.getByLabelText('Sélectionner Alpha'))
    fireEvent.click(screen.getByLabelText('Sélectionner Beta'))
    fireEvent.click(within(bar()).getByRole('button', { name: /Désactiver/ }))

    await waitFor(() => expect(api.bulkStatus).toHaveBeenCalledWith(['a', 'b'], 'INACTIVE'))
    expect(await screen.findByText(/1 scénario\(s\) désactivé\(s\), 1 en échec — « Beta » : Une exécution est en cours/)).toBeTruthy()
  })

  it('suppression définitive groupée : impact total puis saisie de confirmation', async () => {
    serve([scenario({ id: 'a', name: 'Alpha' }), scenario({ id: 'b', name: 'Beta' })])
    api.bulkDeletionImpact.mockResolvedValue(impact)
    api.bulkDelete.mockResolvedValue({ results: [], succeeded: 2, failed: 0 })
    renderPage()
    await screen.findByText('Alpha')
    fireEvent.click(screen.getByLabelText('Sélectionner tous les scénarios de la page'))
    fireEvent.click(within(bar()).getByRole('button', { name: /Supprimer définitivement/ }))

    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('exécutions')
    expect(api.bulkDeletionImpact).toHaveBeenCalledWith(['a', 'b'])
    fireEvent.click(within(dialog).getByRole('radio', { name: /Supprimer définitivement/ }))
    const confirm = within(dialog).getByRole('button', { name: 'Supprimer définitivement' })
    expect(confirm).toBeDisabled()
    fireEvent.change(within(dialog).getByLabelText(/Pour confirmer/), { target: { value: 'SUPPRIMER' } })
    fireEvent.click(confirm)
    await waitFor(() => expect(api.bulkDelete).toHaveBeenCalledWith(['a', 'b']))
  })

  it('PERFORMANCE_ENGINEER : pas de suppression définitive groupée', async () => {
    auth.rawRoles = ['ROLE_PERFORMANCE_ENGINEER']
    serve([scenario({ id: 'a', name: 'Alpha' })])
    renderPage()
    await screen.findByText('Alpha')
    fireEvent.click(screen.getByLabelText('Sélectionner Alpha'))
    expect(within(bar()).queryByRole('button', { name: /Supprimer définitivement/ })).toBeNull()
  })

  it('scénario inactif : badge, « Exécuter » désactivé, réactivation qui propose de reprendre les planifications', async () => {
    serve([scenario({ id: 'x', name: 'Nocturne', status: 'INACTIVE', pausedSchedules: 2 })])
    api.activate.mockResolvedValue(scenario({ id: 'x', name: 'Nocturne' }))
    api.bulkResumeSchedules.mockResolvedValue({ results: [{ id: 'x', name: 'Nocturne', success: true, message: '2 planification(s) réactivée(s).' }], succeeded: 1, failed: 0 })
    renderPage()
    await screen.findByText('Nocturne')

    expect(screen.getAllByText('Inactif').length).toBeGreaterThan(0)
    expect(screen.getByText('2 planification(s) suspendue(s)')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Exécuter Nocturne' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByTitle('Scénario inactif : activez-le pour l’exécuter.')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Activer Nocturne' }))
    await waitFor(() => expect(api.activate).toHaveBeenCalledWith('x'))
    expect(await screen.findByText('Réactiver les planifications ?')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Réactiver les planifications' }))
    await waitFor(() => expect(api.bulkResumeSchedules).toHaveBeenCalledWith(['x']))
  })
})
