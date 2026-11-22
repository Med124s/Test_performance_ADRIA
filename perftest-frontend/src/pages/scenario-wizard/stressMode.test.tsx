import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import LoadSummaryPanel from './LoadSummaryPanel'
import { EMPTY_INFO, fullRequest, stateFromBackend } from './draftModel'
import { INITIAL_TEST_RUN } from './TestRunPanel'
import {
  DEFAULT_LOAD, LoadConfig, ScenarioInfo, TEST_RUN_LOAD, isStress, loadPartForRequest, loadSettingsToRequest, loadStepErrors, loadToRequest,
  newStep,
} from './wizardModel'
import { BackendScenarioResponse } from '../../types/backendContracts'

const load = (patch: Partial<LoadConfig> = {}): LoadConfig => ({ ...DEFAULT_LOAD, presetId: 'custom', ...patch })
const LIMITS = { maxVirtualUsers: 200, maxDurationSeconds: 3600, maxIterations: 10000, maxTargetRps: 500 }
const STRESS: ScenarioInfo = { ...EMPTY_INFO, applicationId: 'app', name: 'Stress', loadMode: 'STRESS' }

const panel = (info: ScenarioInfo, onLoadModeChange = vi.fn()) => render(
  <LoadSummaryPanel info={info} application={undefined} steps={[newStep()]} errors={{}} showErrors limits={LIMITS}
    load={load({ virtualUsers: '5' })} testRun={INITIAL_TEST_RUN} testStale={false} testPassed={false} csvRows={null}
    onChange={() => {}} onGoTo={() => {}} onRequestDetailModeChange={() => {}} onThresholdsChange={() => {}}
    onLoadModeChange={onLoadModeChange} />)

describe('Mode stress — aucun réglage de charge saisi', () => {
  it('nouveau scénario : mode manuel par défaut', () => {
    expect(EMPTY_INFO.loadMode).toBe('MANUAL')
    expect(isStress(EMPTY_INFO)).toBe(false)
    expect(isStress(STRESS)).toBe(true)
  })

  it('aucune erreur de charge en mode stress, même avec des réglages manuels invalides', () => {
    const invalid = load({ virtualUsers: '', thinkTimeMs: '' })
    const manual = { ...STRESS, loadMode: 'MANUAL' as const, maxErrorRatePercent: '150' }
    expect(Object.keys(loadStepErrors(manual, invalid, LIMITS))).toEqual(expect.arrayContaining(['virtualUsers', 'thinkTimeMs', 'maxErrorRatePercent']))
    expect(loadStepErrors({ ...STRESS, maxErrorRatePercent: '150' }, invalid, LIMITS)).toEqual({})
  })

  it('requête : mode envoyé, seuils, frein, 429 et variation omis en stress (inchangés côté serveur)', () => {
    expect(loadSettingsToRequest(STRESS)).toEqual({ loadMode: 'STRESS' })
    expect(loadSettingsToRequest({ ...STRESS, loadMode: 'MANUAL' })).toMatchObject({ loadMode: 'MANUAL', maxErrorRatePercent: 1, throttleOn429: true })
    // Ancien brouillon sans mode : manuel.
    expect(loadSettingsToRequest({ ...STRESS, loadMode: undefined }).loadMode).toBe('MANUAL')
  })

  it('charge envoyée en stress : réglages manuels gardés s’ils sont valides, sinon ceux du test de validation', () => {
    const valid = load({ virtualUsers: '20', rampUpSeconds: '10', durationSeconds: '60' })
    expect(loadPartForRequest(STRESS, valid)).toEqual(loadToRequest(valid))
    expect(loadPartForRequest(STRESS, load({ virtualUsers: '' }))).toEqual(TEST_RUN_LOAD)

    const request = fullRequest('id', STRESS, [], load({ virtualUsers: '' }))
    expect(request.scenario).toMatchObject({ loadMode: 'STRESS', virtualUsers: 1 })
    expect(request.scenario).not.toHaveProperty('maxErrorRatePercent')
  })

  it('scénario enregistré : le mode est relu (MANUAL si la réponse ne le contient pas)', () => {
    const base = { applicationId: 'app', name: 'S', virtualUsers: 1, rampUpSeconds: 0, thinkTimeMs: 0 } as BackendScenarioResponse
    expect(stateFromBackend({ ...base, loadMode: 'STRESS' }, []).info.loadMode).toBe('STRESS')
    expect(stateFromBackend(base, []).info.loadMode).toBe('MANUAL')
  })

  it('écran : choix du mode, puis en stress ni profil, ni champ de charge, ni seuil, mais l’explication avec les limites', () => {
    const onLoadModeChange = vi.fn()
    const { unmount } = panel({ ...STRESS, loadMode: 'MANUAL' }, onLoadModeChange)
    expect(screen.getByRole('radio', { name: /Charge configurée/ }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByLabelText(/Utilisateurs virtuels/)).toBeTruthy()
    fireEvent.click(screen.getByRole('radio', { name: /Point de rupture/ }))
    expect(onLoadModeChange).toHaveBeenCalledWith('STRESS')
    unmount()

    panel(STRESS)
    expect(screen.getByRole('radio', { name: /Point de rupture/ }).getAttribute('aria-checked')).toBe('true')
    expect(screen.queryByText('Profil de charge')).toBeNull()
    expect(screen.queryByLabelText(/Utilisateurs virtuels/)).toBeNull()
    expect(screen.queryByLabelText(/Taux d’erreurs maximal/)).toBeNull()
    expect(screen.queryByText('Frein automatique')).toBeNull()
    expect(screen.queryAllByRole('textbox')).toHaveLength(0)
    const explanation = screen.getByTestId('stress-mode')
    expect(explanation.textContent).toContain('jusqu’à 500 req/s, 200 utilisateurs virtuels ou 1 h de test')
    expect(explanation.textContent).toContain('ne se planifie pas')
    // Le détail des requêtes reste réglable.
    expect(screen.getByLabelText(/Détail des requêtes/)).toBeTruthy()
    expect(screen.getByTestId('stress-summary').textContent).toContain('Auto')
  })
})
