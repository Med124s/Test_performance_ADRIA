import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { RouterProvider, createMemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../../context/ToastContext'
import { BackendApplicationResponse, BackendScenarioResponse, BackendTestRunEvent } from '../../types/backendContracts'

vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: ['ROLE_PERFORMANCE_ENGINEER'] }) }))

const app: BackendApplicationResponse = {
  id: 'app-1', name: 'Banking', description: null, url: 'http://localhost:8888', status: 'CONNECTED', statusMessage: null,
  statusCheckedAt: null, createdBy: 'admin-test', createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z',
  archived: false, archivedAt: null, auth: null,
} as unknown as BackendApplicationResponse

const scenarios = vi.hoisted(() => ({
  create: vi.fn(), update: vi.fn(), getById: vi.fn(), publish: vi.fn(), removeDraft: vi.fn(), testRunStream: vi.fn(),
}))
const steps = vi.hoisted(() => ({ create: vi.fn(), update: vi.fn(), remove: vi.fn(), getByScenario: vi.fn(), testRun: vi.fn() }))
vi.mock('../../services/api/applicationsBackend', () => ({ applicationsBackendApi: { getAll: () => Promise.resolve([app]) } }))
vi.mock('../../services/api/scenariosBackend', () => ({ scenariosBackendApi: scenarios }))
vi.mock('../../services/api/stepsBackend', () => ({ stepsBackendApi: steps }))
vi.mock('../../services/api/executionsBackend', () => ({ executionsBackendApi: { execute: vi.fn() } }))
const settingsApi = vi.hoisted(() => ({
  loadLimits: vi.fn(() => Promise.resolve({ maxVirtualUsers: 30, maxDurationSeconds: 3600, maxIterations: 10000, maxTargetRps: 500 })),
  thresholds: vi.fn(() => Promise.resolve({ maxErrorRatePercent: 2.5, maxP95Ms: 800, regressionPercent: 20 })),
}))
vi.mock('../../services/api/settingsBackend', () => ({ settingsBackendApi: settingsApi }))

import ScenarioWizard from './ScenarioWizard'
import { resetLoadLimitsCache } from '../../utils/loadLimits'
import { resetThresholdsCache } from '../../utils/thresholds'

function renderWizard(entry = '/scenarios/new') {
  const router = createMemoryRouter([
    { path: '/scenarios/new', element: <ToastProvider><ScenarioWizard /></ToastProvider> },
    { path: '/scenarios', element: <div>Liste des scénarios</div> },
  ], { initialEntries: [entry] })
  render(<RouterProvider router={router} />)
  return router
}

const nextButton = () => screen.getByRole('button', { name: /Suivant/ }) as HTMLButtonElement

async function fillInfo() {
  await screen.findByRole('option', { name: /Banking/ })
  fireEvent.change(screen.getByLabelText('Application à tester'), { target: { value: 'app-1' } })
  fireEvent.change(screen.getByLabelText('Nom du scénario'), { target: { value: 'Parcours virement' } })
}

const draftScenario = (patch: Partial<BackendScenarioResponse> = {}): BackendScenarioResponse => ({
  id: 'sc-1', applicationId: 'app-1', applicationName: 'Banking', name: 'Brouillon virement', description: null, status: 'DRAFT',
  virtualUsers: 1, rampUpSeconds: 0, durationSeconds: null, iterations: 1, thinkTimeMs: 0, csvData: 'amount\n150', targetRps: null,
  createdBy: 'engineer-test', createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', archived: false, archivedAt: null,
  applicationArchived: false, wizardStep: null, draftState: null, pausedSchedules: 0, variables: null, csvDistribution: 'PER_VIRTUAL_USER', requestDetailMode: 'FAILURES_AND_SAMPLE', ...patch,
})

beforeEach(() => {
  vi.clearAllMocks()
  resetLoadLimitsCache()
  scenarios.create.mockImplementation((body) => Promise.resolve(draftScenario({ id: 'sc-9', name: body.name })))
  scenarios.update.mockImplementation((id, body) => Promise.resolve(draftScenario({ id, name: body.name })))
  scenarios.removeDraft.mockResolvedValue(undefined)
  steps.create.mockResolvedValue({ id: 'st-1' })
  steps.getByScenario.mockResolvedValue([])
})

describe('Assistant « Nouveau scénario »', () => {
  it('garde « Suivant » désactivé sans message sous le bouton ; erreurs sur les champs touchés uniquement', async () => {
    renderWizard()
    await screen.findByRole('option', { name: /Banking/ })
    expect(nextButton().disabled).toBe(true)
    expect(screen.queryByText(/Pour continuer/)).toBeNull()
    expect(screen.queryByText('Donnez un nom au scénario.')).toBeNull()
    expect(nextButton().getAttribute('title')).toBeNull()

    // Champ touché puis laissé vide : l'erreur apparaît sur le champ.
    fireEvent.blur(screen.getByLabelText('Nom du scénario'))
    expect(screen.getByText('Donnez un nom au scénario.')).toBeTruthy()
    expect(screen.queryByText('Choisissez l’application à tester.')).toBeNull()

    await fillInfo()
    expect(screen.queryByText('Donnez un nom au scénario.')).toBeNull()
    expect(nextButton().disabled).toBe(false)
    fireEvent.click(nextButton())

    // Étapes : l'étape vide n'est pas complète, mais aucune erreur tant qu'elle n'est pas touchée.
    expect(await screen.findByLabelText('Nom de l’étape')).toBeTruthy()
    expect(nextButton().disabled).toBe(true)
    expect(screen.queryByText('Indiquez le chemin ou l’URL à appeler.')).toBeNull()
    fireEvent.change(screen.getByLabelText('Nom de l’étape'), { target: { value: 'Solde' } })
    expect(screen.getByText('Indiquez le chemin ou l’URL à appeler.')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Chemin ou URL'), { target: { value: '/api/account/balance' } })
    expect(nextButton().disabled).toBe(false)
  })

  it('quitte directement quand rien n’a été saisi', async () => {
    const router = renderWizard()
    await screen.findByRole('option', { name: /Banking/ })
    fireEvent.click(screen.getByRole('button', { name: /Scénarios/ }))
    expect(await screen.findByText('Liste des scénarios')).toBeTruthy()
    expect(router.state.location.pathname).toBe('/scenarios')
  })

  it('demande confirmation avant de quitter, puis enregistre un brouillon avec l’étape atteinte', async () => {
    const router = renderWizard()
    await fillInfo()

    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: /Scénarios/ }))
    expect(await screen.findByText('Quitter la création du scénario ?')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Continuer l’édition' }))
    await waitFor(() => expect(screen.queryByText('Quitter la création du scénario ?')).toBeNull())
    expect(router.state.location.pathname).toBe('/scenarios/new')

    fireEvent.click(screen.getByRole('button', { name: /Scénarios/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(dialog.querySelector('.pt-btn-primary') as HTMLElement)

    expect(await screen.findByText('Liste des scénarios')).toBeTruthy()
    const body = scenarios.create.mock.calls[0][0]
    expect(body).toMatchObject({ applicationId: 'app-1', name: 'Parcours virement', draft: true, wizardStep: 0 })
    expect(JSON.parse(body.draftState)).toMatchObject({ version: 1, info: { name: 'Parcours virement' } })
  })

  it('« Quitter sans enregistrer » supprime le scénario créé automatiquement par le test', async () => {
    scenarios.testRunStream.mockImplementation(async (_id: string, onEvent: (e: BackendTestRunEvent) => void) => {
      onEvent({ type: 'done', result: { scenarioId: 'sc-9', applicationUrl: app.url, success: true, totalSteps: 1, executedSteps: 1, passedSteps: 1, totalDurationMs: 40, timedOut: false, auth: { type: 'NONE', success: true, message: 'Aucune authentification', durationMs: 0 }, steps: [], variables: {} } })
    })
    renderWizard()
    await fillInfo()
    fireEvent.click(nextButton())
    fireEvent.change(await screen.findByLabelText('Nom de l’étape'), { target: { value: 'Santé' } })
    fireEvent.change(screen.getByLabelText('Chemin ou URL'), { target: { value: '/health' } })
    fireEvent.click(nextButton())

    expect(nextButton().disabled).toBe(true)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Lancer le test/ })) })
    await waitFor(() => expect(nextButton().disabled).toBe(false))
    expect(scenarios.create.mock.calls[0][0]).toMatchObject({ draft: true })

    fireEvent.click(screen.getByRole('button', { name: /Scénarios/ }))
    expect(await screen.findByText(/il sera supprimé/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Quitter sans enregistrer' }))
    expect(await screen.findByText('Liste des scénarios')).toBeTruthy()
    expect(scenarios.removeDraft).toHaveBeenCalledWith('sc-9')
  })

  it('nouveau scénario : charge du profil « Smoke » (plus de « Charge par défaut »), limites du serveur lues pour la validation', async () => {
    renderWizard()
    await fillInfo()
    await waitFor(() => expect(settingsApi.loadLimits).toHaveBeenCalled())
    expect(settingsApi).not.toHaveProperty('loadDefaults')
    fireEvent.click(screen.getByRole('button', { name: /Scénarios/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(dialog.querySelector('.pt-btn-primary') as HTMLElement)
    expect(await screen.findByText('Liste des scénarios')).toBeTruthy()
    const draft = JSON.parse(scenarios.create.mock.calls[0][0].draftState)
    expect(draft.load).toEqual({
      presetId: 'smoke', virtualUsers: '1', rampUpSeconds: '0', durationSeconds: '', iterations: '1', thinkTimeMs: '0', targetRps: '' })
    // Seuils de réussite toujours pré-remplis avec « Seuils par défaut ».
    expect(draft.info).toMatchObject({ maxErrorRatePercent: '2,5', maxP95Ms: '800' })
  })

  it('nouveau scénario : frein, 429 et détail des requêtes pré-remplis avec Paramètres > Système, modifiables', async () => {
    resetThresholdsCache()
    settingsApi.thresholds.mockResolvedValueOnce({
      maxErrorRatePercent: 2.5, maxP95Ms: 800, regressionPercent: 20, errorBrakeEnabled: true, errorBrakeRatePercent: 35,
      errorBrakeWindowSeconds: 60, throttleOn429: false, throttleDefaultDelaySeconds: 12, throttleMaxRetries: 1, requestDetailMode: 'FAILURES',
    } as never)
    renderWizard()
    await fillInfo()
    await waitFor(() => expect(settingsApi.thresholds).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /Scénarios/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(dialog.querySelector('.pt-btn-primary') as HTMLElement)
    expect(await screen.findByText('Liste des scénarios')).toBeTruthy()
    const request = scenarios.create.mock.calls[0][0]
    expect(JSON.parse(request.draftState).info).toMatchObject({
      maxErrorRatePercent: '2,5', errorBrakeRatePercent: '35', errorBrakeWindowSeconds: '60', throttleOn429: false,
      throttleDefaultDelaySeconds: '12', throttleMaxRetries: '1', requestDetailMode: 'FAILURES',
    })
    // Envoyés tels quels au serveur : enregistrés dans le scénario (override possible avant).
    expect(request).toMatchObject({ errorBrakeRatePercent: 35, errorBrakeWindowSeconds: 60, throttleOn429: false, requestDetailMode: 'FAILURES' })
    resetThresholdsCache()
  })

  it('« Reprendre » rouvre le brouillon à la bonne étape avec ses données', async () => {
    const state = {
      version: 1,
      info: { applicationId: 'app-1', name: 'Brouillon virement', description: '', csvData: '' },
      steps: [{ uid: 'u1', backendId: null, name: 'Virement national', method: 'POST', url: '/api/transfers/national', headers: [], body: '{"amount": ${amount}}', expectedStatus: '201', assertionBodyContains: '', captureVariableName: '', captureJsonPath: '', thinkTimeMs: '', timeoutSeconds: '', followRedirects: '' }],
      load: { presetId: 'smoke', virtualUsers: '1', rampUpSeconds: '0', durationSeconds: '', iterations: '1', thinkTimeMs: '0', targetRps: '' },
      maxReached: 1,
      csvFileName: 'donnees.csv',
      testedSignature: null,
    }
    scenarios.getById.mockResolvedValue(draftScenario({ wizardStep: 1, draftState: JSON.stringify(state) }))

    renderWizard('/scenarios/new?draft=sc-1')
    expect(await screen.findByText('Reprendre le brouillon')).toBeTruthy()
    expect(screen.getAllByText('Virement national').length).toBeGreaterThan(0)
    expect(screen.getByText('donnees.csv')).toBeTruthy()
    // Le CSV valide vient de scenario.csv_data ; ${amount} y est défini.
    expect(screen.queryByText(/absente du CSV/)).toBeNull()
    expect(nextButton().disabled).toBe(false)
    // Brouillon existant : sa charge est reprise telle quelle.
    await waitFor(() => expect(settingsApi.loadLimits).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /Enregistrer comme brouillon/ }))
    await waitFor(() => expect(scenarios.update).toHaveBeenCalled())
    expect(JSON.parse(scenarios.update.mock.calls[scenarios.update.mock.calls.length - 1][1].draftState).load).toMatchObject({ presetId: 'smoke', virtualUsers: '1' })
  })
})
