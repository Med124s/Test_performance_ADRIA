import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../context/ToastContext'
import { BackendExecutionHistoryResponse, BackendExecutionPageResponse } from '../types/backendContracts'

const auth = vi.hoisted(() => ({ roles: ['ROLE_PERFORMANCE_ENGINEER'] }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: auth.roles }) }))
const api = vi.hoisted(() => ({
  getPage: vi.fn(), getStatus: vi.fn(), getById: vi.fn(), retry: vi.fn(), cancel: vi.fn(), execute: vi.fn(),
  deletionImpact: vi.fn(), remove: vi.fn(), bulkDelete: vi.fn(),
}))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: api }))
vi.mock('../services/api/scenariosBackend', () => ({ scenariosBackendApi: { getAll: () => Promise.resolve([
  { id: 'sc-1', name: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking', status: 'ACTIVE', virtualUsers: 5, rampUpSeconds: 5, durationSeconds: 30, iterations: null },
  { id: 'sc-2', name: 'Connexion', applicationId: 'app-1', applicationName: 'Banking', status: 'ACTIVE', virtualUsers: 1, rampUpSeconds: 0, durationSeconds: null, iterations: 1 },
]) } }))
vi.mock('../services/api/applicationsBackend', () => ({ applicationsBackendApi: { getAll: () => Promise.resolve([{ id: 'app-1', name: 'Banking' }]) } }))
vi.mock('../services/api/stepsBackend', () => ({ stepsBackendApi: { getAll: () => Promise.resolve([]) } }))

import Executions from './Executions'

const row = (patch: Partial<BackendExecutionHistoryResponse>): BackendExecutionHistoryResponse => ({
  id: 'ex-1', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking', status: 'SUCCESS',
  startedAt: '2026-10-04T10:00:00Z', finishedAt: '2026-10-04T10:00:30Z', duration: 30_000, virtualUsers: 5, rampUpSeconds: 5,
  durationSeconds: 30, iterations: null, totalSteps: 9, successfulSteps: 200, failedSteps: 3, triggeredByUsername: 'admin', throughput: 6.7,
  ...patch,
})

const page = (content: BackendExecutionHistoryResponse[], counts: Partial<BackendExecutionPageResponse['counts']> = {}): BackendExecutionPageResponse => ({
  content, page: 0, size: 10, totalElements: content.length, totalPages: 1,
  counts: { total: content.length, queued: 0, running: 0, paused: 0, success: 0, failed: 0, cancelled: 0, ...counts },
})

function Location() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname}{location.search}</div>
}

const renderAt = (url = '/executions') => render(
  <MemoryRouter initialEntries={[url]}>
    <ToastProvider>
      <Routes>
        <Route path="/executions" element={<><Executions /><Location /></>} />
        <Route path="/executions/live/:id" element={<Location />} />
      </Routes>
    </ToastProvider>
  </MemoryRouter>,
)

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
  api.getStatus.mockResolvedValue({ id: 'ex-run', status: 'RUNNING', progressPercent: 40, durationSeconds: 30, plannedIterations: null,
    activeElapsedMs: 12_000, successfulRequests: 50, failedRequests: 1, live: { elapsedMs: 12_000, activeVirtualUsers: 5, startedVirtualUsers: 5, completedIterations: 4, requests: 51, successes: 50, failures: 1, throughputRps: 8, avgResponseTimeMs: 200, steps: [] } })
})

describe('Page Exécutions', () => {
  it('filtres côté serveur dans l’URL (statut + scénario + recherche), retour page 1, cartes = compteurs filtrés, réinitialisation', async () => {
    api.getPage.mockResolvedValue(page([row({})], { total: 12, success: 8, failed: 3, cancelled: 1 }))
    renderAt('/executions?page=2')
    expect(await screen.findByText('8')).toBeTruthy()
    expect(screen.getByText('12')).toBeTruthy()
    // Plus de champ « Rechercher une exécution… » dans l'en-tête.
    expect(screen.queryByPlaceholderText('Rechercher une exécution...')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /^Échouée/ }))
    await waitFor(() => expect(api.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'FAILED', page: 0 })))
    expect(screen.getByTestId('location').textContent).toBe('/executions?status=FAILED')

    fireEvent.focus(screen.getByRole('combobox', { name: 'Filtrer par scénario' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'Filtrer par scénario' }), { target: { value: 'conn' } })
    fireEvent.mouseDown(await screen.findByRole('option', { name: /Connexion/ }))
    await waitFor(() => expect(api.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'FAILED', scenarioId: 'sc-2' })))

    fireEvent.change(screen.getByLabelText('Rechercher une exécution'), { target: { value: 'banking' } })
    await waitFor(() => expect(api.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'FAILED', scenarioId: 'sc-2', search: 'banking' })), { timeout: 2000 })
    expect(screen.getByTestId('location').textContent).toContain('q=banking')

    fireEvent.click(screen.getByRole('button', { name: /Réinitialiser les filtres/ }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions'))
  })

  it('suppression groupée : impact, confirmation définitive, succès partiel expliqué', async () => {
    api.getPage.mockResolvedValue(page([row({ id: 'ex-1' }), row({ id: 'ex-2', status: 'FAILED' })], { success: 1, failed: 1 }))
    api.deletionImpact.mockResolvedValue({ executions: 2, results: 1200, metrics: 1200, active: 0 })
    api.bulkDelete.mockResolvedValue({ succeeded: 1, failed: 1, results: [
      { id: 'ex-1', name: 'Parcours bancaire (SUCCESS, …)', success: true, message: 'Supprimée.' },
      { id: 'ex-2', name: 'Parcours bancaire (FAILED, …)', success: false, message: 'L\'exécution termine l\'enregistrement de ses résultats : réessayez dans quelques secondes.' },
    ] })
    renderAt()
    fireEvent.click(await screen.findByLabelText('Sélectionner toutes les exécutions de la page'))
    const bar = screen.getByRole('region', { name: 'Actions sur la sélection' })
    expect(within(bar).getByText('2 sélectionnées')).toBeTruthy()
    fireEvent.click(within(bar).getByRole('button', { name: /Supprimer/ }))

    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findAllByText('1200')).toHaveLength(2)
    expect(within(dialog).getByText(/irréversible/)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: /Supprimer définitivement/ }))
    await waitFor(() => expect(api.bulkDelete).toHaveBeenCalledWith(['ex-1', 'ex-2']))
    expect(await screen.findByText(/1 exécution supprimée, 1 non supprimée/)).toBeTruthy()
  })

  it('exécution en cours : barre de progression + « Suivre », suppression impossible ; relance confirmée -> suivi en direct', async () => {
    api.getPage.mockResolvedValue(page([row({ id: 'ex-run', status: 'RUNNING', duration: null }), row({ id: 'ex-1' })], { running: 1, success: 1 }))
    api.retry.mockResolvedValue({ id: 'ex-new', scenarioName: 'Parcours bancaire', virtualUsers: 5 })
    renderAt()
    expect((await screen.findByRole('link', { name: 'Suivre' })).getAttribute('href')).toBe('/executions/live/ex-run')
    expect(await screen.findByText('40 %')).toBeTruthy()
    const deleteButtons = screen.getAllByRole('button', { name: 'Supprimer' })
    expect((deleteButtons[0] as HTMLButtonElement).disabled).toBe(true)
    expect((deleteButtons[1] as HTMLButtonElement).disabled).toBe(false)

    fireEvent.click(screen.getAllByRole('button', { name: 'Relancer' })[1])
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/nouvelle exécution/)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: /Relancer/ }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions/live/ex-new'))
    expect(api.retry).toHaveBeenCalledWith('ex-1')
  })

  it('relance refusée : message clair du serveur', async () => {
    const { ApiError } = await import('../services/api/httpClient')
    api.getPage.mockResolvedValue(page([row({})], { success: 1 }))
    api.retry.mockRejectedValue(new ApiError('Le scénario est inactif, activez-le pour l\'exécuter.', 400))
    renderAt()
    fireEvent.click(await screen.findByRole('button', { name: 'Relancer' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /Relancer/ }))
    expect(await screen.findByText('Le scénario est inactif, activez-le pour l\'exécuter.')).toBeTruthy()
  })

  it('lecteur : ni cases à cocher, ni Supprimer, ni Relancer', async () => {
    auth.roles = ['ROLE_VIEWER']
    api.getPage.mockResolvedValue(page([row({})], { success: 1 }))
    renderAt()
    expect(await screen.findByText('Parcours bancaire')).toBeTruthy()
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Supprimer' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Relancer' })).toBeNull()
  })
})
