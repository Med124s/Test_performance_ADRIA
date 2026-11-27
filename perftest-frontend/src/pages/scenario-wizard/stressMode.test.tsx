import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import LoadSummaryPanel from './LoadSummaryPanel'
import { EMPTY_INFO, fullRequest, stateFromBackend } from './draftModel'
import { INITIAL_TEST_RUN } from './TestRunPanel'
import {
  DEFAULT_LOAD, LoadConfig, ScenarioInfo, TEST_RUN_LOAD, isStress, loadPartForRequest, loadSettingsToRequest, loadStepErrors, loadToRequest,
  newStep, stressPrecisionRps, stressSecondsToReach,
} from './wizardModel'
import { BackendScenarioResponse } from '../../types/backendContracts'

const load = (patch: Partial<LoadConfig> = {}): LoadConfig => ({ ...DEFAULT_LOAD, presetId: 'custom', ...patch })
const LIMITS = { maxVirtualUsers: 200, maxDurationSeconds: 3600, maxIterations: 10000, maxTargetRps: 500 }
const STRESS: ScenarioInfo = { ...EMPTY_INFO, applicationId: 'app', name: 'Stress', loadMode: 'STRESS' }

const panel = (info: ScenarioInfo, onLoadModeChange = vi.fn(), onStressRampChange = vi.fn()) => render(
  <LoadSummaryPanel info={info} application={undefined} steps={[newStep()]} errors={loadStepErrors(info, load({ virtualUsers: '5' }), LIMITS)}
    showErrors limits={LIMITS}
    load={load({ virtualUsers: '5' })} testRun={INITIAL_TEST_RUN} testStale={false} testPassed={false} csvRows={null}
    onChange={() => {}} onGoTo={() => {}} onRequestDetailModeChange={() => {}} onThresholdsChange={() => {}}
    onLoadModeChange={onLoadModeChange} onStressRampChange={onStressRampChange} />)

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
    expect(loadSettingsToRequest(STRESS)).toEqual({ loadMode: 'STRESS', stressRampStep: 1 })
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

describe('Mode stress — vitesse de montée, seul réglage optionnel', () => {
  it('vide = vitesse par défaut, sinon entier de 1 à 50 ; toujours envoyée en stress, jamais en manuel', () => {
    expect(loadStepErrors(STRESS, load(), LIMITS)).toEqual({})
    expect(loadSettingsToRequest({ ...STRESS, stressRampStep: '20' })).toEqual({ loadMode: 'STRESS', stressRampStep: 20 })
    for (const bad of ['0', '51', '2,5', 'vite']) {
      expect(loadStepErrors({ ...STRESS, stressRampStep: bad }, load(), LIMITS)).toHaveProperty('stressRampStep')
    }
    expect(loadSettingsToRequest({ ...STRESS, loadMode: 'MANUAL', stressRampStep: '20' })).not.toHaveProperty('stressRampStep')
    expect(fullRequest('id', { ...STRESS, stressRampStep: ' ' }, [], load()).scenario).toMatchObject({ stressRampStep: 1 })
  })

  it('scénario enregistré : vitesse relue, vide si c’est la vitesse par défaut', () => {
    const base = { applicationId: 'app', name: 'S', virtualUsers: 1, rampUpSeconds: 0, thinkTimeMs: 0, loadMode: 'STRESS' } as BackendScenarioResponse
    expect(stateFromBackend({ ...base, stressRampStep: 20 }, []).info.stressRampStep).toBe('20')
    expect(stateFromBackend({ ...base, stressRampStep: 1 }, []).info.stressRampStep).toBe('')
    expect(stateFromBackend(base, []).info.stressRampStep).toBe('')
  })

  it('précision et durée pour atteindre le débit maximal', () => {
    expect(stressPrecisionRps(1)).toBe(5)
    expect(stressPrecisionRps(20)).toBe(100)
    // 40 s d'échauffement et de référence, puis (500 − 2) / 20 = 25 s de montée.
    expect(stressSecondsToReach(500, 20)).toBe(65)
    expect(stressSecondsToReach(500, 1)).toBe(538)
  })

  it('écran : réglage avancé replié, puis champ, effet de la vitesse et résumé', () => {
    const onStressRampChange = vi.fn()
    const { unmount } = panel(STRESS, vi.fn(), onStressRampChange)
    expect(screen.queryByLabelText(/Vitesse de montée/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Réglage avancé : vitesse de montée/ }))
    fireEvent.change(screen.getByLabelText(/Vitesse de montée/), { target: { value: '20' } })
    expect(onStressRampChange).toHaveBeenCalledWith('20')
    unmount()

    panel({ ...STRESS, stressRampStep: '20' })
    expect(screen.getByLabelText(/Vitesse de montée/)).toBeTruthy()
    expect(screen.getByTestId('stress-ramp-effect').textContent?.replace(/[  ]/g, ' ')).toContain('± 100 req/s près')
    expect(screen.getByTestId('stress-mode').textContent).toContain('+20 req/s chaque seconde')
    expect(screen.getByTestId('stress-summary').textContent).toContain('+20 req/s')
  })

  it('écran : vitesse hors bornes signalée', () => {
    panel({ ...STRESS, stressRampStep: '80' })
    expect(screen.getByText('La vitesse de montée ne doit pas dépasser 50.')).toBeTruthy()
  })
})
