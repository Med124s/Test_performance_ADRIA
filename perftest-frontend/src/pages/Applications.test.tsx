import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationPageQuery, BackendApplicationResponse, BackendApplicationTestResponse, BackendDeletionImpact } from '../types/backendContracts'
import { ToastProvider } from '../context/ToastContext'

// ------------------------------------------------------------------ mocks
const auth = { authProvider: 'keycloak' as const, rawRoles: ['ROLE_SUPER_ADMIN'] }
vi.mock('../context/AuthContext', () => ({ useAuth: () => auth }))

const api = vi.hoisted(() => ({
  getAll: vi.fn(),
  getPage: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  test: vi.fn(),
  remove: vi.fn(),
  deletionImpact: vi.fn(),
  bulkDeletionImpact: vi.fn(),
  archive: vi.fn(),
  restore: vi.fn(),
  bulkArchive: vi.fn(),
  bulkDelete: vi.fn(),
}))
vi.mock('../services/api/applicationsBackend', () => ({ applicationsBackendApi: api }))
vi.mock('../services/api/scenariosBackend', () => ({ scenariosBackendApi: { getAll: () => Promise.resolve([]) } }))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: { getAll: () => Promise.resolve([]) } }))

import Applications from './Applications'

const app = (patch: Partial<BackendApplicationResponse> = {}): BackendApplicationResponse => ({
  id: 'app-1',
  name: 'Banking',
  description: null,
  url: 'http://localhost:8888',
  status: null,
  statusMessage: null,
  statusCheckedAt: null,
  createdBy: 'admin-test',
  createdAt: '2026-09-30T10:00:00Z',
  updatedAt: '2026-09-30T10:00:00Z',
  archived: false,
  archivedAt: null,
  auth: {
    type: 'BEARER_TOKEN', apiKeyName: null, apiKeyLocation: null, username: null, loginPath: null, tokenJsonPath: null,
    tokenHeaderName: null, tokenPrefix: null, verificationPath: '/api/account/balance', tokenConfigured: true, apiKeyValueConfigured: false,
    passwordConfigured: false, loginBodyConfigured: false,
  },
  ...patch,
})

const result = (status: BackendApplicationTestResponse['status'], message: string): BackendApplicationTestResponse =>
  ({ status, httpStatus: status === 'CONNECTED' ? 200 : 401, responseTimeMs: 5, message })

/** Sert une liste paginée comme le backend (filtre de status, découpage par page). */
const serveApps = (list: BackendApplicationResponse[]) =>
  api.getPage.mockImplementation((q: ApplicationPageQuery) => {
    const filtered = q.status ? list.filter((a) => (q.status === 'UNTESTED' ? a.status === null : a.status === q.status)) : list
    const start = q.page * q.size
    return Promise.resolve({
      content: filtered.slice(start, start + q.size), page: q.page, size: q.size,
      totalElements: filtered.length, totalPages: Math.ceil(filtered.length / q.size),
    })
  })

/** Appels de la LISTE (toujours triée) — les compteurs globaux n'envoient pas de tri. */
const listCalls = () => api.getPage.mock.calls.filter(([q]) => (q as ApplicationPageQuery).sort !== undefined)

const impact = (patch: Partial<BackendDeletionImpact> = {}): BackendDeletionImpact => ({
  applications: 1, scenarios: 2, steps: 5, executions: 7, scheduledExecutions: 0, runningExecutions: 0, hasRunningExecution: false, ...patch,
})

const renderPage = (url = '/') =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <ToastProvider>
        <Applications />
      </ToastProvider>
    </MemoryRouter>
  )

const fillNewApplication = () => {
  fireEvent.change(screen.getByPlaceholderText('ex: Mon Application Web'), { target: { value: 'Banking' } })
  fireEvent.change(screen.getByPlaceholderText('ex: https://app.monsite.com'), { target: { value: 'http://localhost:8888' } })
  fireEvent.click(screen.getByRole('radio', { name: /Jeton Bearer/ }))
  fireEvent.change(screen.getByPlaceholderText(/TEST-TOKEN-123/), { target: { value: 'bad-token' } })
  fireEvent.change(screen.getByLabelText(/Endpoint de vérification/), { target: { value: '/api/account/balance' } })
}

beforeEach(() => {
  vi.clearAllMocks()
  auth.rawRoles = ['ROLE_SUPER_ADMIN']
})

describe('Applications — enregistrer et tester', () => {
  it('échec : reste ouverte, affiche le résultat, puis corrige par un PUT (jamais de doublon) jusqu’à CONNECTED', async () => {
    serveApps([])
    api.create.mockResolvedValue(app())
    api.test.mockResolvedValueOnce(result('FAILED', 'Identifiants refusés (401).'))
    renderPage()
    await screen.findByText(/Aucune application/)

    fireEvent.click(screen.getByRole('button', { name: /Ajouter une application/ }))
    fillNewApplication()
    const started = Date.now()
    fireEvent.click(screen.getByRole('button', { name: /Tester et ajouter/ }))

    // Chargement visible, avec les étapes, modale verrouillée.
    expect(await screen.findByText("Enregistrement…", { selector: '.aa-step span' })).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('button', { name: 'Fermer' })).toBeDisabled()

    const failed = await screen.findByText('Identifiants refusés (401).', { selector: '.aa-result-message' }, { timeout: 3000 })
    expect(Date.now() - started).toBeGreaterThanOrEqual(1200)
    expect(failed.closest('.aa-result')).toHaveClass('ko')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(api.create).toHaveBeenCalledTimes(1)

    // Correction : le bouton devient "Enregistrer et tester" et fait un PUT.
    api.update.mockResolvedValue(app())
    api.test.mockResolvedValueOnce(result('CONNECTED', 'Application joignable (HTTP 200).'))
    fireEvent.change(screen.getByPlaceholderText(/enregistré — laisser vide pour conserver/), { target: { value: 'TEST-TOKEN-123' } })
    fireEvent.click(screen.getByRole('button', { name: /Enregistrer et tester/ }))

    await screen.findByText('Application joignable (HTTP 200).', { selector: '.aa-result-message' }, { timeout: 3000 })
    expect(api.create).toHaveBeenCalledTimes(1)
    expect(api.update).toHaveBeenCalledTimes(1)
    expect(api.update.mock.calls[0][1].auth).toEqual({ type: 'BEARER_TOKEN', token: 'TEST-TOKEN-123', verificationPath: '/api/account/balance' })

    // Succès : la modale se ferme après une courte confirmation ; la liste
    // est à jour sans rechargement.
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), { timeout: 3000 })
    expect(listCalls()).toHaveLength(1)
    const row = screen.getByText('Banking').closest('tr')!
    expect(within(row).getByText('Connectée')).toBeInTheDocument()
    expect(within(row).getByText('Application joignable (HTTP 200).')).toBeInTheDocument()
  }, 15000)

  it('modification : secret vide conservé (non envoyé) et bouton "Enregistrer et tester"', async () => {
    serveApps([app({ status: 'FAILED', statusMessage: 'Authentification refusée (401).' })])
    api.update.mockResolvedValue(app())
    api.test.mockResolvedValue(result('CONNECTED', 'Application joignable (HTTP 200).'))
    renderPage()

    fireEvent.click(await screen.findByTitle('Modifier'))
    fireEvent.change(screen.getByPlaceholderText('ex: https://app.monsite.com'), { target: { value: 'http://localhost:8889' } })
    fireEvent.click(screen.getByRole('button', { name: /Enregistrer et tester/ }))

    await screen.findByText('Application joignable (HTTP 200).', { selector: '.aa-result-message' }, { timeout: 3000 })
    expect(api.update.mock.calls[0][1].auth).toEqual({ type: 'BEARER_TOKEN', token: null, verificationPath: '/api/account/balance' })
  }, 10000)

  it('erreur d’enregistrement (400) : pas de test lancé, modale ouverte avec le message', async () => {
    serveApps([])
    const { ApiError } = await import('../services/api/httpClient')
    api.create.mockRejectedValue(new ApiError('Le corps doit être un JSON valide.', 400))
    renderPage()
    await screen.findByText(/Aucune application/)

    fireEvent.click(screen.getByRole('button', { name: /Ajouter une application/ }))
    fillNewApplication()
    fireEvent.click(screen.getByRole('button', { name: /Tester et ajouter/ }))

    expect(await screen.findByText(/Le corps doit être un JSON valide/, {}, { timeout: 3000 })).toBeInTheDocument()
    expect(api.test).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  }, 10000)
})

describe('Applications — liste', () => {
  it('Retester met à jour le badge et le message sans recharger la liste', async () => {
    serveApps([app({ status: 'FAILED', statusMessage: 'Ancien échec.' })])
    api.test.mockResolvedValue(result('CONNECTED', 'Application joignable (HTTP 200).'))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Retester Banking' }))
    expect(await screen.findByText("Vérification de l'authentification…")).toBeInTheDocument()

    await screen.findByText('Application joignable (HTTP 200).', {}, { timeout: 3000 })
    const row = screen.getByText('Banking').closest('tr')!
    expect(within(row).getByText('Connectée')).toBeInTheDocument()
    expect(listCalls()).toHaveLength(1)
  }, 10000)

  it('VIEWER : ni Modifier, ni Retester, ni Supprimer', async () => {
    auth.rawRoles = ['ROLE_VIEWER']
    serveApps([app()])
    renderPage()
    await screen.findByText('Banking')

    expect(screen.queryByTitle('Modifier')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retester Banking' })).not.toBeInTheDocument()
    expect(screen.queryByTitle('Supprimer')).not.toBeInTheDocument()
  })

  it('PERFORMANCE_ENGINEER : Modifier, Retester et Archiver, mais jamais de suppression définitive', async () => {
    auth.rawRoles = ['ROLE_PERFORMANCE_ENGINEER']
    serveApps([app()])
    api.deletionImpact.mockResolvedValue(impact())
    renderPage()
    await screen.findByText('Banking')

    expect(screen.getByTitle('Modifier')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retester Banking' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Supprimer Banking' }))
    await screen.findByText('Réservé au rôle Administrateur.')
    expect(screen.getByRole('radio', { name: /Supprimer définitivement/ })).toBeDisabled()
  })

  it('Échap ne ferme pas la modale pendant le chargement', async () => {
    serveApps([])
    api.create.mockResolvedValue(app())
    let resolveTest: (r: BackendApplicationTestResponse) => void = () => {}
    api.test.mockReturnValue(new Promise((resolve) => { resolveTest = resolve }))
    renderPage()
    await screen.findByText(/Aucune application/)

    fireEvent.click(screen.getByRole('button', { name: /Ajouter une application/ }))
    fillNewApplication()
    fireEvent.click(screen.getByRole('button', { name: /Tester et ajouter/ }))
    await screen.findByText("Vérification de l'authentification…", { selector: '.aa-step span' })

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    await act(async () => resolveTest(result('FAILED', 'Erreur.')))
    await screen.findByText('Erreur.', { selector: '.aa-result-message' }, { timeout: 3000 })
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  }, 10000)
})

describe('Applications — suppression, sélection et pagination', () => {
  it('sélection multiple : barre d’actions, impact total, archivage groupé puis sélection vidée', async () => {
    serveApps([app({ id: 'a1', name: 'Alpha' }), app({ id: 'a2', name: 'Beta' }), app({ id: 'a3', name: 'Gamma' })])
    api.bulkDeletionImpact.mockResolvedValue(impact({ applications: 3 }))
    api.bulkArchive.mockResolvedValue({ results: [], succeeded: 3, failed: 0 })
    renderPage()
    await screen.findByText('Alpha')

    fireEvent.click(screen.getByLabelText('Sélectionner toutes les applications de la page'))
    expect(screen.getByText('3 sélectionnées')).toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('region', { name: 'Actions sur la sélection' })).getByRole('button', { name: /Archiver/ }))

    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('applications')
    expect(api.bulkDeletionImpact).toHaveBeenCalledWith(['a1', 'a2', 'a3'])
    fireEvent.click(within(dialog).getByRole('button', { name: 'Archiver' }))

    await waitFor(() => expect(api.bulkArchive).toHaveBeenCalledWith(['a1', 'a2', 'a3'], 'ARCHIVE'))
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Actions sur la sélection' })).not.toBeInTheDocument())
  })

  it('suppression définitive : saisie du nom obligatoire avant de confirmer', async () => {
    serveApps([app()])
    api.deletionImpact.mockResolvedValue(impact())
    api.remove.mockResolvedValue(undefined)
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Supprimer Banking' }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('exécutions')
    fireEvent.click(within(dialog).getByRole('radio', { name: /Supprimer définitivement/ }))
    const confirm = within(dialog).getByRole('button', { name: 'Supprimer définitivement' })
    expect(confirm).toBeDisabled()
    fireEvent.change(within(dialog).getByLabelText(/Pour confirmer/), { target: { value: 'Banking' } })
    expect(confirm).toBeEnabled()
    fireEvent.click(confirm)

    await waitFor(() => expect(api.remove).toHaveBeenCalledWith('app-1'))
  })

  it('exécution en cours : message clair et aucune confirmation possible', async () => {
    serveApps([app()])
    api.deletionImpact.mockResolvedValue(impact({ runningExecutions: 1, hasRunningExecution: true }))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Supprimer Banking' }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText(/arrêtez-la avant de supprimer/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Archiver' })).toBeDisabled()
  })

  it('VIEWER : aucune case à cocher', async () => {
    auth.rawRoles = ['ROLE_VIEWER']
    serveApps([app()])
    renderPage()
    await screen.findByText('Banking')
    expect(screen.queryByRole('checkbox', { name: /Sélectionner/ })).not.toBeInTheDocument()
  })

  it('état dans l’URL : page et taille relues au chargement, libellé « Affichage de X à Y sur Z » exact', async () => {
    serveApps(Array.from({ length: 12 }, (_, i) => app({ id: `a${i}`, name: `App ${String(i).padStart(2, '0')}` })))
    renderPage('/?page=2&size=5')

    expect(await screen.findByText('Affichage de 6 à 10 sur 12 applications')).toBeInTheDocument()
    expect(listCalls()[0][0]).toMatchObject({ page: 1, size: 5 })
    expect(screen.getByRole('button', { name: '2' })).toHaveAttribute('aria-current', 'page')
  })

  it('page devenue vide : retour automatique à la dernière page existante', async () => {
    serveApps(Array.from({ length: 6 }, (_, i) => app({ id: `a${i}`, name: `App ${i}` })))
    renderPage('/?page=3&size=5')

    expect(await screen.findByText('Affichage de 6 à 6 sur 6 applications')).toBeInTheDocument()
    expect(listCalls().map(([q]) => (q as ApplicationPageQuery).page)).toEqual([2, 1])
  })
})
