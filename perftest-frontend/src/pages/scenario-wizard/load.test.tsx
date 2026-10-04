import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import LoadSummaryPanel from './LoadSummaryPanel'
import { EMPTY_INFO } from './draftModel'
import { INITIAL_TEST_RUN } from './TestRunPanel'
import { DEFAULT_LOAD, LoadConfig, loadToRequest, loadWarnings, newStep, validateLoad } from './wizardModel'

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
