import { describe, expect, it } from 'vitest'
import { EMPTY_INFO, applySuggestions, stateFromBackend, suggestedInfo } from './draftModel'
import { BackendScenarioResponse } from '../../types/backendContracts'

// Valeurs de Paramètres > Système proposées à un NOUVEAU scénario : pré-remplissage bloc par bloc,
// jamais par-dessus une valeur modifiée, jamais pour un scénario existant.

const system = {
  maxErrorRatePercent: 2.5, maxP95Ms: 800, regressionPercent: 20,
  errorBrakeEnabled: true, errorBrakeRatePercent: 35.5, errorBrakeWindowSeconds: 60,
  throttleOn429: false, throttleDefaultDelaySeconds: 12, throttleMaxRetries: 1, requestDetailMode: 'FAILURES' as const,
}

describe('valeurs proposées aux nouveaux scénarios', () => {
  it('au format du formulaire ; frein désactivé = taux vide, fenêtre gardée pour qui l’active', () => {
    expect(suggestedInfo(system)).toEqual({
      maxErrorRatePercent: '2,5', maxP95Ms: '800', errorBrakeRatePercent: '35,5', errorBrakeWindowSeconds: '60',
      throttleOn429: false, throttleDefaultDelaySeconds: '12', throttleMaxRetries: '1', requestDetailMode: 'FAILURES',
    })
    expect(suggestedInfo({ ...system, errorBrakeEnabled: false })).toMatchObject({ errorBrakeRatePercent: '', errorBrakeWindowSeconds: '60' })
  })

  it('serveur sans ces réglages (3 seuils seulement) : valeurs initiales, identiques à l’ancien formulaire', () => {
    const suggested = suggestedInfo({ maxErrorRatePercent: 1, maxP95Ms: 0, regressionPercent: 20 })
    expect(applySuggestions(EMPTY_INFO, suggested)).toEqual(EMPTY_INFO)
  })

  it('formulaire vierge : tous les blocs pré-remplis', () => {
    expect(applySuggestions(EMPTY_INFO, suggestedInfo(system))).toMatchObject({
      maxErrorRatePercent: '2,5', maxP95Ms: '800', errorBrakeRatePercent: '35,5', errorBrakeWindowSeconds: '60',
      throttleOn429: false, throttleDefaultDelaySeconds: '12', throttleMaxRetries: '1', requestDetailMode: 'FAILURES',
    })
  })

  it('un bloc déjà modifié par l’utilisateur n’est jamais écrasé (override local)', () => {
    const typed = { ...EMPTY_INFO, throttleMaxRetries: '7', requestDetailMode: 'NONE' as const }
    const next = applySuggestions(typed, suggestedInfo(system))
    // 429 et détail modifiés : gardés tels quels, bloc entier.
    expect(next).toMatchObject({ throttleOn429: true, throttleDefaultDelaySeconds: '5', throttleMaxRetries: '7', requestDetailMode: 'NONE' })
    // Blocs non touchés : pré-remplis.
    expect(next).toMatchObject({ maxErrorRatePercent: '2,5', errorBrakeRatePercent: '35,5' })
  })

  it('scénario existant : ses valeurs enregistrées, jamais celles du système', () => {
    const saved = {
      id: 'sc-1', applicationId: 'app-1', name: 'Existant', description: null, csvData: null, variables: null,
      csvDistribution: 'PER_VIRTUAL_USER', requestDetailMode: 'FAILURES_AND_SAMPLE', maxErrorRatePercent: 1, maxP95Ms: null,
      errorBrakeRatePercent: null, errorBrakeWindowSeconds: null, throttleOn429: null, throttleDefaultDelaySeconds: null, throttleMaxRetries: null,
      virtualUsers: 1, rampUpSeconds: 0, durationSeconds: null, iterations: 1, thinkTimeMs: 0, targetRps: null,
    } as unknown as BackendScenarioResponse
    const info = stateFromBackend(saved, []).info
    // NULL en base = valeurs d'origine (repli de l'execution), pas les valeurs système actuelles.
    expect(info).toMatchObject({ errorBrakeRatePercent: '', throttleOn429: true, throttleDefaultDelaySeconds: '5', throttleMaxRetries: '3',
      requestDetailMode: 'FAILURES_AND_SAMPLE' })
  })
})
