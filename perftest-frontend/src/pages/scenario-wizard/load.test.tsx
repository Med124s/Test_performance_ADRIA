import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import LoadSummaryPanel from './LoadSummaryPanel'
import { EMPTY_INFO } from './draftModel'
import { INITIAL_TEST_RUN } from './TestRunPanel'
import {
  DEFAULT_LOAD, LoadConfig, loadToRequest, loadWarnings, newStep, thresholdsFromScenario, thresholdsToRequest, validateLoad, validateThresholds,
} from './wizardModel'

const load = (patch: Partial<LoadConfig> = {}): LoadConfig => ({ ...DEFAULT_LOAD, presetId: 'custom', ...patch })

describe('Charge — mêmes règles que le moteur', () => {
  it('durée et itérations optionnelles et indépendantes, envoyées telles quelles', () => {
    expect(validateLoad(load({ durationSeconds: '', iterations: '' }))).toEqual({})
    expect(loadToRequest(load({ durationSeconds: '30', iterations: '5', targetRps: '' }))).toMatchObject({ durationSeconds: 30, iterations: 5, targetRps: null })
    expect(loadToRequest(load({ durationSeconds: '', iterations: '' }))).toMatchObject({ durationSeconds: null, iterations: null })
    expect(validateLoad(load({ virtualUsers: '' })).virtualUsers).toMatch(/obligatoire/)
    expect(validateLoad(load({ thinkTimeMs: '' })).thinkTimeMs).toMatch(/think time est obligatoire/)
    expect(validateLoad(load({ durationSeconds: '0' })).durationSeconds).toMatch(/au moins 1/)
  })

  it('avertissements : ramp-up > durée, au-delà de 200 utilisateurs, ni durée ni itérations', () => {
    expect(loadWarnings(load({ rampUpSeconds: '60', durationSeconds: '30' }))[0]).toMatch(/ne joueront aucune itération/)
    expect(loadWarnings(load({ virtualUsers: '250' })).join(' ')).toMatch(/200 utilisateurs/)
    expect(loadWarnings(load({ durationSeconds: '', iterations: '' })).join(' ')).toMatch(/une seule fois/)
  })

  it('libellés exacts avec aide courte, champs obligatoires marqués', () => {
    render(<LoadSummaryPanel info={EMPTY_INFO} application={undefined} steps={[newStep()]} errors={{}} showErrors
      load={load({ virtualUsers: '5', rampUpSeconds: '10', durationSeconds: '60', iterations: '', thinkTimeMs: '500' })}
      testRun={INITIAL_TEST_RUN} testStale={false} testPassed={false} csvRows={null} onChange={() => {}} onGoTo={() => {}} />)
    for (const label of ['Utilisateurs virtuels', 'Think time (ms)', 'Ramp-up (s)', 'Durée (s)', 'Itérations', 'Débit cible (req/s)']) {
      expect(screen.getByText(label, { exact: false, selector: 'label' })).toBeTruthy()
    }
    expect(screen.getAllByText('*')).toHaveLength(3)
    expect(screen.getByText(/Pause d’un utilisateur entre deux itérations/)).toBeTruthy()
    expect(screen.getByText(/un nouvel utilisateur toutes les 2 s/)).toBeTruthy()
    expect(screen.getByText(/partagée par tous les utilisateurs/)).toBeTruthy()
  })

  it('réglage « Détail des requêtes » : défaut « Échecs + échantillon », 3 choix', () => {
    const onMode = vi.fn()
    render(<LoadSummaryPanel info={EMPTY_INFO} application={undefined} steps={[newStep()]} errors={{}} showErrors
      load={load({ virtualUsers: '5' })} testRun={INITIAL_TEST_RUN} testStale={false} testPassed={false} csvRows={null}
      onChange={() => {}} onGoTo={() => {}} onRequestDetailModeChange={onMode} />)
    const select = screen.getByLabelText('Détail des requêtes') as HTMLSelectElement
    expect(select.value).toBe('FAILURES_AND_SAMPLE')
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['FAILURES_AND_SAMPLE', 'FAILURES', 'NONE'])
    fireEvent.change(select, { target: { value: 'NONE' } })
    expect(onMode).toHaveBeenCalledWith('NONE')
    expect(screen.getByText(/20 par type d’erreur et 100 par étape/)).toBeTruthy()
  })
})

describe('Seuils de réussite du scénario', () => {
  it('validation, envoi au serveur et lecture d’un scénario enregistré', () => {
    expect(validateThresholds({ maxErrorRatePercent: '1', maxP95Ms: '' })).toEqual({})
    expect(validateThresholds({ maxErrorRatePercent: '2,5', maxP95Ms: '800' })).toEqual({})
    expect(validateThresholds({ maxErrorRatePercent: '', maxP95Ms: '' }).maxErrorRatePercent).toMatch(/Indiquez le taux d’erreurs maximal/)
    expect(validateThresholds({ maxErrorRatePercent: '150', maxP95Ms: '' }).maxErrorRatePercent).toMatch(/entre 0 et 100/)
    expect(validateThresholds({ maxErrorRatePercent: '-1', maxP95Ms: '' }).maxErrorRatePercent).toMatch(/entre 0 et 100/)
    expect(validateThresholds({ maxErrorRatePercent: '1', maxP95Ms: '0' }).maxP95Ms).toMatch(/au moins 1/)
    // Ancien brouillon (champs absents) : rien à valider, rien d'envoyé (valeurs du serveur conservées).
    expect(validateThresholds({})).toEqual({})
    expect(thresholdsToRequest({})).toEqual({})

    expect(thresholdsToRequest({ maxErrorRatePercent: '2,5', maxP95Ms: '800' })).toEqual({ maxErrorRatePercent: 2.5, maxP95Ms: 800 })
    // P95 vide : 0 = « aucun seuil » pour le serveur.
    expect(thresholdsToRequest({ maxErrorRatePercent: '1', maxP95Ms: '' })).toEqual({ maxErrorRatePercent: 1, maxP95Ms: 0 })
    expect(thresholdsFromScenario({ maxErrorRatePercent: 2.5, maxP95Ms: null })).toEqual({ maxErrorRatePercent: '2,5', maxP95Ms: '' })
    expect(thresholdsFromScenario({})).toEqual({ maxErrorRatePercent: '1', maxP95Ms: '' })
  })

  it('étape « Charge & résumé » : taux d’erreurs maximal (défaut 1 %) et P95 maximal optionnel', () => {
    const onThresholds = vi.fn()
    render(<LoadSummaryPanel info={EMPTY_INFO} application={undefined} steps={[newStep()]} errors={{ maxP95Ms: 'Le P95 maximal doit être au moins 1.' }}
      showErrors load={load({ virtualUsers: '5' })} testRun={INITIAL_TEST_RUN} testStale={false} testPassed={false} csvRows={null}
      onChange={() => {}} onGoTo={() => {}} onThresholdsChange={onThresholds} />)
    expect(screen.getByText('Seuils de réussite')).toBeTruthy()
    const rate = screen.getByLabelText(/Taux d’erreurs maximal/) as HTMLInputElement
    expect(rate.value).toBe('1')
    fireEvent.change(rate, { target: { value: '2,5' } })
    expect(onThresholds).toHaveBeenCalledWith({ maxErrorRatePercent: '2,5' })
    const p95 = screen.getByLabelText(/P95 maximal/) as HTMLInputElement
    expect(p95.placeholder).toBe('Aucun seuil')
    fireEvent.change(p95, { target: { value: '500' } })
    expect(onThresholds).toHaveBeenLastCalledWith({ maxP95Ms: '500' })
    expect(screen.getByText('Le P95 maximal doit être au moins 1.')).toBeTruthy()
    expect(screen.getByText(/« Échouée » \(rouge\) est réservé/)).toBeTruthy()
  })
})