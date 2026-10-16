import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { RouterProvider, createMemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../../context/ToastContext'
import {
  BackendApplicationResponse,
  BackendScenarioResponse,
  BackendStepResponse,
  BackendTestRunEvent,
  BackendTestRunResponse,
} from '../../types/backendContracts'
import { ApiError } from '../../services/api/httpClient'

vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: ['ROLE_PERFORMANCE_ENGINEER'] }) }))

const app = { id: 'app-1', name: 'Banking', url: 'http://localhost:8888', status: 'CONNECTED', auth: null } as unknown as BackendApplicationResponse
const scenarios = vi.hoisted(() => ({ getById: vi.fn(), updateFull: vi.fn(), testRunDraftStream: vi.fn(), testRunDraft: vi.fn(), testRunStream: vi.fn(), create: vi.fn(), update: vi.fn() }))
const steps = vi.hoisted(() => ({ getByScenario: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() }))
vi.mock('../../services/api/applicationsBackend', () => ({ applicationsBackendApi: { getAll: () => Promise.resolve([app]) } }))
vi.mock('../../services/api/scenariosBackend', () => ({ scenariosBackendApi: scenarios }))
vi.mock('../../services/api/stepsBackend', () => ({ stepsBackendApi: steps }))
vi.mock('../../services/api/executionsBackend', () => ({ executionsBackendApi: { execute: vi.fn() } }))
// Limites du serveur lues par l'assistant (plages et validation de « Charge & résumé »).
vi.mock('../../services/api/settingsBackend', () => ({ settingsBackendApi: {
  loadLimits: () => Promise.resolve({ maxVirtualUsers: 200, maxDurationSeconds: 3600, maxIterations: 10000, maxTargetRps: 500 }),
  thresholds: () => Promise.resolve({ maxErrorRatePercent: 1, maxP95Ms: 0, regressionPercent: 20 }),
} }))

import ScenarioWizard from './ScenarioWizard'
import StepDrawer from './StepDrawer'
import TestRunPanel, { INITIAL_TEST_RUN } from './TestRunPanel'

const scenario = (patch: Partial<BackendScenarioResponse> = {}): BackendScenarioResponse => ({
  id: 'sc-1', applicationId: 'app-1', applicationName: 'Banking', name: 'Parcours virement', description: 'Virement puis solde', status: 'ACTIVE',
  virtualUsers: 5, rampUpSeconds: 5, durationSeconds: 30, iterations: null, thinkTimeMs: 500, csvData: 'amount\n150', targetRps: null,
  createdBy: 'engineer-test', createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', archived: false, archivedAt: null,
  applicationArchived: false, wizardStep: null, draftState: null, pausedSchedules: 0, variables: '{"currency":"MAD"}', csvDistribution: 'PER_VIRTUAL_USER', requestDetailMode: 'FAILURES_AND_SAMPLE', ...patch,
})

const step = (id: string, order: number, name: string, url: string): BackendStepResponse => ({
  id, scenarioId: 'sc-1', scenarioName: 'Parcours virement', name, method: 'POST', url, headers: '{"Content-Type":"application/json"}',
  body: '{"amount": ${amount}}', order, expectedStatus: 201, thinkTimeMs: null, timeoutSeconds: null, followRedirects: null,
  assertionBodyContains: '"status":"completed"', captureVariableName: order === 1 ? 'transferId' : null, captureJsonPath: order === 1 ? '$.transferId' : null,
  pathParams: null, queryParams: null, status: 'ACTIVE', createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z',
} as BackendStepResponse)

function renderEdit() {
  const router = createMemoryRouter([
    { path: '/scenarios/:id/edit', element: <ToastProvider><ScenarioWizard mode="edit" /></ToastProvider> },
    { path: '/scenarios', element: <div>Liste des scénarios</div> },
  ], { initialEntries: ['/scenarios/sc-1/edit'] })
  render(<RouterProvider router={router} />)
  return router
}

const button = (name: RegExp | string) => screen.getByRole('button', { name }) as HTMLButtonElement

beforeEach(() => {
  vi.clearAllMocks()
  scenarios.getById.mockResolvedValue(scenario())
  steps.getByScenario.mockResolvedValue([step('st-2', 2, 'Modifier', '/api/transfers/:transferId'), step('st-1', 1, 'Virement', '/api/transfers/national')])
})

describe('Page « Modifier le scénario »', () => {
  it('charge toutes les données, navigation libre, badge « Modifié » et enregistrement complet', async () => {
    scenarios.updateFull.mockImplementation((_id, body) => Promise.resolve({ scenario: scenario({ name: body.scenario.name }),
      steps: [step('st-1', 1, 'Virement', '/api/transfers/national'), step('st-2', 2, 'Modifier', '/api/transfers/:transferId')] }))
    renderEdit()

    expect(await screen.findByText('Modifier le scénario')).toBeTruthy()
    expect((screen.getByLabelText('Nom du scénario') as HTMLInputElement).value).toBe('Parcours virement')
    expect(button(/^.*Enregistrer$/).disabled).toBe(true)

    // Navigation libre : directement vers « Charge & résumé », valeurs enregistrées pré-remplies.
    fireEvent.click(screen.getByRole('button', { name: /Charge & résumé/ }))
    expect((screen.getByLabelText(/Utilisateurs virtuels/) as HTMLInputElement).value).toBe('5')
    expect((screen.getByLabelText(/Durée \(s\)/) as HTMLInputElement).value).toBe('30')

    // Étapes dans l'ordre, avec corps et capture.
    fireEvent.click(screen.getByRole('button', { name: /Étapes/ }))
    const cards = screen.getAllByText(/Virement|Modifier/, { selector: '.sw-step-name' })
    expect(cards.map((c) => c.textContent)).toEqual(['Virement', 'Modifier'])

    fireEvent.click(screen.getByRole('button', { name: /Informations/ }))
    fireEvent.change(screen.getByLabelText('Nom du scénario'), { target: { value: 'Parcours virement v2' } })
    expect(within(screen.getByRole('button', { name: /Informations/ })).getByText('Modifié')).toBeTruthy()
    expect(screen.getByText('Modifications non enregistrées')).toBeTruthy()

    fireEvent.click(button(/^.*Enregistrer$/))
    await waitFor(() => expect(scenarios.updateFull).toHaveBeenCalled())
    const [id, body] = scenarios.updateFull.mock.calls[0]
    expect(id).toBe('sc-1')
    expect(body.scenario).toMatchObject({ name: 'Parcours virement v2', virtualUsers: 5, durationSeconds: 30, variables: '{"currency":"MAD"}' })
    expect(body.steps.map((s: { id: string; step: { order: number; url: string } }) => [s.id, s.step.order, s.step.url]))
      .toEqual([['st-1', 1, '/api/transfers/national'], ['st-2', 2, '/api/transfers/:transferId']])
    await waitFor(() => expect(button(/^.*Enregistrer$/).disabled).toBe(true))
    expect(screen.queryByText('Modifié')).toBeNull()
  })

  it('seuils de réussite : pré-remplis, une modification active « Enregistrer » et part au serveur', async () => {
    scenarios.getById.mockResolvedValue(scenario({ maxErrorRatePercent: 1, maxP95Ms: null }))
    scenarios.updateFull.mockImplementation((_id, body) => Promise.resolve({ scenario: scenario({ maxErrorRatePercent: body.scenario.maxErrorRatePercent }),
      steps: [step('st-1', 1, 'Virement', '/api/transfers/national'), step('st-2', 2, 'Modifier', '/api/transfers/:transferId')] }))
    renderEdit()
    expect(await screen.findByText('Modifier le scénario')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Charge & résumé/ }))
    const rate = screen.getByLabelText(/Taux d’erreurs maximal/) as HTMLInputElement
    expect(rate.value).toBe('1')
    expect(button(/^.*Enregistrer$/).disabled).toBe(true)

    fireEvent.change(rate, { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText(/P95 maximal/), { target: { value: '800' } })
    expect(within(screen.getByRole('button', { name: /Charge & résumé/ })).getByText('Modifié')).toBeTruthy()
    expect(button(/^.*Enregistrer$/).disabled).toBe(false)
    fireEvent.click(button(/^.*Enregistrer$/))
    await waitFor(() => expect(scenarios.updateFull).toHaveBeenCalled())
    expect(scenarios.updateFull.mock.calls[0][1].scenario).toMatchObject({ maxErrorRatePercent: 5, maxP95Ms: 800 })
  })

  it('« Tester » essaie la version affichée sans l’enregistrer', async () => {
    scenarios.testRunDraftStream.mockImplementation(async (_id: string, _draft: unknown, onEvent: (e: BackendTestRunEvent) => void) => {
      onEvent({ type: 'done', result: { success: true, steps: [], variables: {}, auth: { type: 'NONE', success: true, message: 'ok', durationMs: 0 }, totalDurationMs: 10, timedOut: false } as unknown as BackendTestRunResponse })
    })
    renderEdit()
    await screen.findByText('Modifier le scénario')
    fireEvent.change(screen.getByLabelText('Nom du scénario'), { target: { value: 'Non enregistré' } })
    fireEvent.click(screen.getByTitle(/Tester la version affichée/))
    await waitFor(() => expect(scenarios.testRunDraftStream).toHaveBeenCalled())
    expect(scenarios.testRunDraftStream.mock.calls[0][1].scenario.name).toBe('Non enregistré')
    expect(scenarios.updateFull).not.toHaveBeenCalled()
    expect(scenarios.update).not.toHaveBeenCalled()
  })

  it('confirmation avant de quitter avec des modifications non enregistrées', async () => {
    const router = renderEdit()
    await screen.findByText('Modifier le scénario')
    fireEvent.change(screen.getByLabelText('Nom du scénario'), { target: { value: 'Changé' } })
    fireEvent.click(button(/Annuler/))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Quitter sans enregistrer ?')).toBeTruthy()
    expect(within(dialog).getByText(/« Informations »/)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Quitter sans enregistrer' }))
    expect(await screen.findByText('Liste des scénarios')).toBeTruthy()
    expect(router.state.location.pathname).toBe('/scenarios')
    expect(scenarios.updateFull).not.toHaveBeenCalled()
  })

  it('message clair si le scénario n’existe pas', async () => {
    scenarios.getById.mockRejectedValue(new ApiError('introuvable', 404))
    renderEdit()
    expect(await screen.findByText('Scénario introuvable')).toBeTruthy()
    expect(screen.getByText('Ce scénario n’existe pas ou a été supprimé.')).toBeTruthy()
  })
})

describe('Panneau « Ajouter une étape » (Détails du scénario)', () => {
  const saved = [step('st-1', 1, 'Virement', '/api/transfers/national')]

  it('éditeur de l’assistant, test de la version affichée puis ajout en fin de scénario', async () => {
    scenarios.testRunDraft.mockResolvedValue({
      success: true, auth: { type: 'NONE', success: true, message: 'ok', durationMs: 0 }, variables: {}, steps: [
        { index: 1, name: 'Virement', status: 'PASSED', success: true, request: null, response: null, checks: null, urlWarning: null, warnings: [], error: null, errorKind: null, stepId: 'st-1' },
        { index: 2, name: 'Supprimer', status: 'PASSED', success: true, error: null, errorKind: null, stepId: null, urlWarning: null, warnings: [], checks: null,
          request: { method: 'DELETE', url: 'http://localhost:8888/api/transfers/TRF002', headers: {}, body: null, followRedirects: true, timeoutSeconds: 10 },
          response: { status: 200, statusText: 'OK', durationMs: 12, sizeBytes: 40, contentType: 'application/json', headers: {}, body: '{"status":"deleted"}', truncated: false, json: true } },
      ],
    } as unknown as BackendTestRunResponse)
    steps.create.mockResolvedValue({ id: 'st-9' })
    const onSaved = vi.fn()
    render(<StepDrawer scenario={scenario()} application={app} steps={saved} editing={null} onClose={() => {}} onSaved={onSaved} />)

    expect(screen.getByRole('dialog', { name: 'Ajouter une étape' })).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Nom de l’étape'), { target: { value: 'Supprimer' } })
    fireEvent.change(screen.getByLabelText('Méthode HTTP'), { target: { value: 'DELETE' } })
    fireEvent.change(screen.getByLabelText('Chemin ou URL'), { target: { value: '/api/transfers/:transferId' } })
    fireEvent.click(screen.getByRole('tab', { name: /Paramètres/ }))
    expect(screen.getByText('Capture (étape 1)')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: /Tester cette étape/ }))
    await waitFor(() => expect(scenarios.testRunDraft).toHaveBeenCalled())
    const [, draft, upTo] = scenarios.testRunDraft.mock.calls[0]
    expect(upTo).toBe(2)
    expect(draft.steps[1].step).toMatchObject({ name: 'Supprimer', method: 'DELETE', url: '/api/transfers/:transferId' })
    expect(await screen.findByText('200 OK')).toBeTruthy()
    expect(screen.getByText(/1 étape\(s\) précédente\(s\) exécutée\(s\)/)).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: /Ajouter l’étape/ }))
    await waitFor(() => expect(steps.create).toHaveBeenCalled())
    expect(steps.create.mock.calls[0][0]).toMatchObject({ scenarioId: 'sc-1', name: 'Supprimer', order: 2, pathParams: '{"transferId":"${transferId}"}' })
    expect(onSaved).toHaveBeenCalledWith('Étape « Supprimer » ajoutée.')
  })

  it('même composant pour modifier une étape existante', () => {
    render(<StepDrawer scenario={scenario()} application={app} steps={saved} editing={saved[0]} onClose={() => {}} onSaved={() => {}} />)
    expect(screen.getByRole('dialog', { name: 'Modifier l’étape' })).toBeTruthy()
    expect((screen.getByLabelText('Nom de l’étape') as HTMLInputElement).value).toBe('Virement')
    expect(screen.getByRole('button', { name: /Enregistrer l’étape/ })).toBeTruthy()
  })
})

describe('Tester : message de connexion', () => {
  const props = { steps: [], baseUrl: '', stale: false, onRun: () => {}, onRunStep: () => {}, onEditStep: () => {}, onUseRelativeUrl: () => {} }
  it('masqué en cas de succès, affiché en cas d’échec', () => {
    const { rerender } = render(<TestRunPanel {...props} run={{ ...INITIAL_TEST_RUN, phase: 'done', auth: { type: 'LOGIN_TOKEN', success: true, message: 'Connexion réussie : jeton obtenu', durationMs: 5 } }} />)
    expect(screen.queryByText(/Connexion réussie/)).toBeNull()
    rerender(<TestRunPanel {...props} run={{ ...INITIAL_TEST_RUN, phase: 'done', auth: { type: 'LOGIN_TOKEN', success: false, message: 'Authentification échouée : login refusé (401).', durationMs: 5 } }} />)
    expect(screen.getByText('Authentification échouée : login refusé (401).')).toBeTruthy()
  })
})
