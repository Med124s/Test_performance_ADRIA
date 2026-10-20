import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import LoadSummaryPanel from './LoadSummaryPanel'
import { EMPTY_INFO } from './draftModel'
import { INITIAL_TEST_RUN } from './TestRunPanel'
import {
  DEFAULT_LOAD, LOAD_PRESETS, LoadConfig, estimateLoad, loadToRequest, loadWarnings, measuredRequestMs, newStep, presetBlockedReason,
  thresholdsFromScenario, thresholdsToRequest, validateLoad, validateThresholds, validateErrorBrake, errorBrakeToRequest, errorBrakeFromScenario, validateThrottle, throttleToRequest, throttleFromScenario, validateThinkVariation, thinkVariationToRequest, thinkVariationFromScenario,
} from './wizardModel'

const load = (patch: Partial<LoadConfig> = {}): LoadConfig => ({ ...DEFAULT_LOAD, presetId: 'custom', ...patch })
/** Limites lues depuis le serveur (valeurs par défaut des plafonds, total à 200). */
const LIMITS = { maxVirtualUsers: 200, maxDurationSeconds: 3600, maxIterations: 10000, maxTargetRps: 500 }

describe('Charge — mêmes règles que le moteur', () => {
  it('durée et itérations optionnelles et indépendantes, envoyées telles quelles', () => {
    expect(validateLoad(load({ durationSeconds: '', iterations: '' }))).toEqual({})
    expect(loadToRequest(load({ durationSeconds: '30', iterations: '5', targetRps: '' }))).toMatchObject({ durationSeconds: 30, iterations: 5, targetRps: null })
    expect(loadToRequest(load({ durationSeconds: '', iterations: '' }))).toMatchObject({ durationSeconds: null, iterations: null })
    expect(validateLoad(load({ virtualUsers: '' })).virtualUsers).toMatch(/obligatoire/)
    expect(validateLoad(load({ thinkTimeMs: '' })).thinkTimeMs).toMatch(/think time est obligatoire/)
    expect(validateLoad(load({ durationSeconds: '0' })).durationSeconds).toMatch(/au moins 1/)
  })

  it('règles bloquantes identiques au serveur : limite applicable, ramp-up inférieur à la durée', () => {
    // Limite d'un test sur ce serveur (Paramètres > Système) : message identique au serveur.
    expect(validateLoad(load({ virtualUsers: '201' }), LIMITS).virtualUsers)
      .toBe('Le nombre d’utilisateurs ne doit pas dépasser 200 (limite d’un test sur ce serveur).')
    expect(validateLoad(load({ virtualUsers: '200' }), LIMITS).virtualUsers).toBeUndefined()
    expect(validateLoad(load({ virtualUsers: '0' }), LIMITS).virtualUsers).toBe('Le nombre d’utilisateurs doit être au moins 1.')
    // Ramp-up : strictement inférieur à la durée quand une durée est fixée ; libre sans durée.
    expect(validateLoad(load({ rampUpSeconds: '60', durationSeconds: '60' })).rampUpSeconds).toBe('Le ramp-up doit être plus court que la durée.')
    expect(validateLoad(load({ rampUpSeconds: '59', durationSeconds: '60' })).rampUpSeconds).toBeUndefined()
    expect(validateLoad(load({ rampUpSeconds: '120', durationSeconds: '' })).rampUpSeconds).toBeUndefined()
    expect(validateLoad(load({ targetRps: '0' })).targetRps).toBe('Le débit cible doit être au moins 1.')
    // Nouvelles limites (mêmes messages que le serveur).
    expect(validateLoad(load({ durationSeconds: '3601' }), LIMITS).durationSeconds).toBe('La durée ne doit pas dépasser 3 600 s (limite d’un test sur ce serveur).')
    expect(validateLoad(load({ iterations: '10001' }), LIMITS).iterations).toBe('Le nombre d’itérations ne doit pas dépasser 10 000 (limite d’un test sur ce serveur).')
    expect(validateLoad(load({ targetRps: '501' }), LIMITS).targetRps).toBe('Le débit cible ne doit pas dépasser 500 req/s (limite d’un test sur ce serveur).')
    expect(validateLoad(load({ thinkTimeMs: '60001' })).thinkTimeMs).toBe('Le think time ne doit pas dépasser 60 000 ms.')
    expect(validateLoad(load({ rampUpSeconds: '3601', durationSeconds: '' }), LIMITS).rampUpSeconds).toBe('Le ramp-up ne doit pas dépasser 3 600 s (durée maximale d’un test).')
    expect(validateLoad(load({ durationSeconds: '3600', iterations: '10000', targetRps: '500', thinkTimeMs: '60000', rampUpSeconds: '3599' }), LIMITS)).toEqual({})
    // Seul avertissement restant (non bloquant) : un seul passage.
    expect(loadWarnings(load({ durationSeconds: '', iterations: '' }))).toEqual(['Ni durée ni itérations : chaque utilisateur jouera le scénario une seule fois.'])
    expect(loadWarnings(load({ virtualUsers: '250', rampUpSeconds: '60', durationSeconds: '30' }))).toEqual([])
  })

  it('libellés exacts avec aide courte, champs obligatoires marqués', () => {
    render(<LoadSummaryPanel info={EMPTY_INFO} application={undefined} steps={[newStep()]} errors={{}} showErrors
      load={load({ virtualUsers: '5', rampUpSeconds: '10', durationSeconds: '60', iterations: '', thinkTimeMs: '500' })}
      testRun={INITIAL_TEST_RUN} testStale={false} testPassed={false} csvRows={null} onChange={() => {}} onGoTo={() => {}} />)
    for (const label of ['Utilisateurs virtuels', 'Think time (ms)', 'Ramp-up (s)', 'Durée (s)', 'Itérations', 'Débit cible (req/s)']) {
      expect(screen.getByText(label, { exact: false, selector: 'label' })).toBeTruthy()
    }
    expect(screen.getAllByText('*')).toHaveLength(3)
    expect(screen.getByText('Pause d’un utilisateur entre deux itérations : 0 à 60 000 ms.')).toBeTruthy()
    expect(screen.getByText(/Un nouvel utilisateur toutes les 2 s/)).toBeTruthy()
    expect(screen.getByText(/tous utilisateurs confondus/)).toBeTruthy()
  })

  it('aide « Utilisateurs virtuels » : limite réelle du serveur', () => {
    render(<LoadSummaryPanel info={EMPTY_INFO} application={undefined} steps={[newStep()]} errors={{}} showErrors limits={LIMITS}
      load={load({ virtualUsers: '5' })} testRun={INITIAL_TEST_RUN} testStale={false} testPassed={false} csvRows={null} onChange={() => {}} onGoTo={() => {}} />)
    // Plage autorisée sous chaque champ, lue depuis le serveur.
    expect(screen.getByText('Nombre d’utilisateurs simulés en parallèle (1 à 200).')).toBeTruthy()
    expect(screen.getByText('Pause d’un utilisateur entre deux itérations : 0 à 60 000 ms.')).toBeTruthy()
    expect(screen.getByText('Démarrage progressif des utilisateurs : 0 à 3 600 s, plus court que la durée.')).toBeTruthy()
    expect(screen.getByText('1 à 3 600 s ; vide = jusqu’à la fin des itérations.')).toBeTruthy()
    expect(screen.getByText('Passages complets du scénario par utilisateur : 1 à 10 000.')).toBeTruthy()
    expect(screen.getByText('Requêtes par seconde, tous utilisateurs confondus : jusqu’à 500 req/s.')).toBeTruthy()
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
    // Long texte remplacé par une infobulle d'une phrase.
    expect(screen.queryByText(/20 par type d’erreur et 100 par étape/)).toBeNull()
    expect(screen.getByRole('img', { name: 'Requêtes conservées pour le détail de l’exécution, secrets masqués.' })).toBeTruthy()
  })
})

describe('Charge — estimation, avertissements, profils et limite du serveur', () => {
  it('estimation avec durée : la durée compte depuis le début (ramp-up compris), comme le moteur', () => {
    // 10 utilisateurs, un toutes les 1 s, 2 étapes à 500 ms : 1 s par itération, test de 60 s.
    const e = estimateLoad(load({ virtualUsers: '10', rampUpSeconds: '10', durationSeconds: '60', iterations: '', thinkTimeMs: '0' }), 2, 500)!
    expect(e.measured).toBe(true)
    expect(e.requests).toBe(1110)
    expect(e.totalSeconds).toBe(60)
    // Débit cible : jamais plus de 5 req/s sur 60 s.
    expect(estimateLoad(load({ virtualUsers: '10', rampUpSeconds: '10', durationSeconds: '60', iterations: '', targetRps: '5' }), 2, 500)!.requests).toBe(300)
  })

  it('estimation sans durée : itérations, think time, débit cible ; temps supposé sans test de validation', () => {
    const e = estimateLoad(load({ virtualUsers: '4', rampUpSeconds: '0', durationSeconds: '', iterations: '3', thinkTimeMs: '1000' }), 2, null)!
    expect(e.measured).toBe(false)
    expect(e.requestMs).toBe(300)
    expect(e.requests).toBe(24)
    expect(e.totalSeconds).toBeCloseTo(3 * 0.6 + 2 * 1)
    expect(estimateLoad(load({ virtualUsers: '10', durationSeconds: '', iterations: '100', targetRps: '5' }), 1, 100)!.totalSeconds).toBe(200)
    expect(estimateLoad(load({ virtualUsers: '' }), 2, null)).toBeNull()
    expect(measuredRequestMs([{ response: { durationMs: 100 } }, { response: null }, { response: { durationMs: 300 } }])).toBe(200)
  })

  it('avertissements : ramp-up long sans durée, plus d’une heure', () => {
    const longRamp = load({ virtualUsers: '100', rampUpSeconds: '1000', durationSeconds: '', iterations: '1' })
    expect(loadWarnings(longRamp, estimateLoad(longRamp, 2, null))).toEqual([
      'Le dernier utilisateur démarrera après 16 min 30 s, alors qu’un utilisateur termine en ≈ 1 s : peu d’utilisateurs seront actifs en même temps. Réduisez le ramp-up ou fixez une durée.'])
    const long = load({ virtualUsers: '5', rampUpSeconds: '10', durationSeconds: '4000', iterations: '' })
    expect(loadWarnings(long, estimateLoad(long, 1, 200))).toEqual(['Durée estimée supérieure à 1 heure (≈ 1 h 7 min).'])
    const fine = load({ virtualUsers: '10', rampUpSeconds: '10', durationSeconds: '60', iterations: '' })
    expect(loadWarnings(fine, estimateLoad(fine, 2, 500))).toEqual([])
  })

  it('profils au-delà de la limite du serveur : désactivés avec la raison', () => {
    const stress = LOAD_PRESETS.find((p) => p.id === 'stress')!
    expect(presetBlockedReason(stress, LIMITS)).toBeNull()
    expect(presetBlockedReason(stress, { ...LIMITS, maxVirtualUsers: 30 })).toBe('Dépasse la limite du serveur : 30 utilisateurs')
    expect(presetBlockedReason(stress, { ...LIMITS, maxDurationSeconds: 90 })).toBe('Dépasse la limite du serveur : 90 s de durée')
    expect(presetBlockedReason(LOAD_PRESETS.find((p) => p.id === 'custom')!, { ...LIMITS, maxVirtualUsers: 30 })).toBeNull()
    const onChange = vi.fn()
    render(<LoadSummaryPanel info={EMPTY_INFO} application={undefined} steps={[newStep()]} errors={{}} showErrors limits={{ ...LIMITS, maxVirtualUsers: 30 }}
      load={load({ virtualUsers: '5' })} testRun={INITIAL_TEST_RUN} testStale={false} testPassed={false} csvRows={null} onChange={onChange} onGoTo={() => {}} />)
    const button = screen.getByRole('button', { name: /Stress — Dépasse la limite du serveur : 30 utilisateurs/ }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    expect(button.title).toBe('Dépasse la limite du serveur : 30 utilisateurs')
    fireEvent.click(button)
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByText(/« Stress » dépasse les limites du serveur/)).toBeTruthy()
    expect((screen.getByRole('button', { name: /Charge/ }) as HTMLButtonElement).disabled).toBe(false)
  })

  it('estimation affichée sous les champs, en précisant que c’est une estimation', () => {
    render(<LoadSummaryPanel info={EMPTY_INFO} application={undefined} steps={[newStep(), newStep()]} errors={{}} showErrors limits={LIMITS}
      load={load({ virtualUsers: '10', rampUpSeconds: '10', durationSeconds: '60', iterations: '', thinkTimeMs: '0' })}
      testRun={INITIAL_TEST_RUN} testStale={false} testPassed={false} csvRows={null} onChange={() => {}} onGoTo={() => {}} />)
    const estimate = screen.getByTestId('load-estimate')
    expect(estimate.textContent).toMatch(/Estimation \(approximative\) : durée totale ≈ 1 min · ≈ [\d\s\u202f]+ requêtes\./)
    expect(estimate.textContent).toMatch(/en supposant 300 ms par requête : lancez le test de validation pour l’affiner/)
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
    expect(screen.queryByText(/« Échouée » \(rouge\) est réservé/)).toBeNull()
    expect(screen.getByRole('img', { name: '« Échouée » est réservé à un test qui n’a pas pu se dérouler.' })).toBeTruthy()
  })
})
describe('Frein automatique sur taux d’erreurs', () => {
  it('désactivé par défaut ; validation, envoi au serveur et lecture d’un scénario enregistré', () => {
    expect(EMPTY_INFO.errorBrakeRatePercent).toBe('')
    expect(validateErrorBrake(EMPTY_INFO)).toEqual({})
    expect(errorBrakeToRequest(EMPTY_INFO)).toEqual({ errorBrakeRatePercent: 0 })
    // Ancien brouillon (champs absents) : rien d'envoyé, réglage du serveur conservé.
    expect(errorBrakeToRequest({})).toEqual({})
    expect(validateErrorBrake({})).toEqual({})

    expect(validateErrorBrake({ errorBrakeRatePercent: '20', errorBrakeWindowSeconds: '30' })).toEqual({})
    expect(validateErrorBrake({ errorBrakeRatePercent: '0', errorBrakeWindowSeconds: '30' }).errorBrakeRatePercent).toMatch(/supérieur à 0/)
    expect(validateErrorBrake({ errorBrakeRatePercent: '150', errorBrakeWindowSeconds: '30' }).errorBrakeRatePercent).toMatch(/au plus 100/)
    expect(validateErrorBrake({ errorBrakeRatePercent: '20', errorBrakeWindowSeconds: '2' }).errorBrakeWindowSeconds).toMatch(/au moins 5/)
    expect(validateErrorBrake({ errorBrakeRatePercent: '20', errorBrakeWindowSeconds: '301' }).errorBrakeWindowSeconds).toMatch(/ne doit pas dépasser 300/)
    expect(validateErrorBrake({ errorBrakeRatePercent: '20', errorBrakeWindowSeconds: '' }).errorBrakeWindowSeconds).toMatch(/obligatoire/)

    expect(errorBrakeToRequest({ errorBrakeRatePercent: '12,5', errorBrakeWindowSeconds: '60' })).toEqual({ errorBrakeRatePercent: 12.5, errorBrakeWindowSeconds: 60 })
    expect(errorBrakeFromScenario({ errorBrakeRatePercent: 12.5, errorBrakeWindowSeconds: 60 })).toEqual({ errorBrakeRatePercent: '12,5', errorBrakeWindowSeconds: '60' })
    expect(errorBrakeFromScenario({})).toEqual({ errorBrakeRatePercent: '', errorBrakeWindowSeconds: '30' })
  })

  it('étape « Charge & résumé » : interrupteur éteint par défaut, valeurs proposées à l’activation', () => {
    const onThresholds = vi.fn()
    const props = { application: undefined, steps: [newStep()], errors: {}, showErrors: true, load: load({ virtualUsers: '5' }),
      testRun: INITIAL_TEST_RUN, testStale: false, testPassed: false, csvRows: null, onChange: () => {}, onGoTo: () => {}, onThresholdsChange: onThresholds }
    const { unmount } = render(<LoadSummaryPanel info={EMPTY_INFO} {...props} />)
    const toggle = screen.getByRole('switch', { name: /Arrêter le test si trop d’erreurs/ }) as HTMLInputElement
    expect(toggle.checked).toBe(false)
    expect(screen.queryByLabelText(/Taux d’erreurs au-delà duquel arrêter/)).toBeNull()
    fireEvent.click(toggle)
    expect(onThresholds).toHaveBeenLastCalledWith({ errorBrakeRatePercent: '20', errorBrakeWindowSeconds: '30' })
    unmount()

    render(<LoadSummaryPanel info={{ ...EMPTY_INFO, errorBrakeRatePercent: '20', errorBrakeWindowSeconds: '30' }} {...props} />)
    expect((screen.getByRole('switch', { name: /Arrêter le test si trop d’erreurs/ }) as HTMLInputElement).checked).toBe(true)
    fireEvent.change(screen.getByLabelText(/Sur les dernières/), { target: { value: '60' } })
    expect(onThresholds).toHaveBeenLastCalledWith({ errorBrakeWindowSeconds: '60' })
    fireEvent.click(screen.getByRole('switch', { name: /Arrêter le test si trop d’erreurs/ }))
    expect(onThresholds).toHaveBeenLastCalledWith({ errorBrakeRatePercent: '' })
  })
})

describe('Ralentissement sur 429', () => {
  it('actif par défaut ; validation, envoi au serveur et lecture d’un scénario enregistré', () => {
    expect(EMPTY_INFO).toMatchObject({ throttleOn429: true, throttleDefaultDelaySeconds: '5', throttleMaxRetries: '3' })
    expect(validateThrottle(EMPTY_INFO)).toEqual({})
    expect(throttleToRequest(EMPTY_INFO)).toEqual({ throttleOn429: true, throttleDefaultDelaySeconds: 5, throttleMaxRetries: 3 })
    // Ancien brouillon : rien d'envoyé.
    expect(throttleToRequest({})).toEqual({})

    expect(validateThrottle({ throttleOn429: true, throttleDefaultDelaySeconds: '0', throttleMaxRetries: '3' }).throttleDefaultDelaySeconds).toMatch(/au moins 1/)
    expect(validateThrottle({ throttleOn429: true, throttleDefaultDelaySeconds: '301', throttleMaxRetries: '3' }).throttleDefaultDelaySeconds).toMatch(/ne doit pas dépasser 300/)
    expect(validateThrottle({ throttleOn429: true, throttleDefaultDelaySeconds: '5', throttleMaxRetries: '11' }).throttleMaxRetries).toMatch(/ne doit pas dépasser 10/)
    expect(validateThrottle({ throttleOn429: true, throttleDefaultDelaySeconds: '5', throttleMaxRetries: '0' })).toEqual({})
    // Désactivé : les autres champs ne sont pas jugés.
    expect(validateThrottle({ throttleOn429: false, throttleDefaultDelaySeconds: 'x', throttleMaxRetries: '' })).toEqual({})
    expect(throttleToRequest({ throttleOn429: false, throttleDefaultDelaySeconds: '5', throttleMaxRetries: '3' })).toMatchObject({ throttleOn429: false })

    expect(throttleFromScenario({})).toEqual({ throttleOn429: true, throttleDefaultDelaySeconds: '5', throttleMaxRetries: '3' })
    expect(throttleFromScenario({ throttleOn429: false, throttleDefaultDelaySeconds: 10, throttleMaxRetries: 0 }))
      .toEqual({ throttleOn429: false, throttleDefaultDelaySeconds: '10', throttleMaxRetries: '0' })
  })

  it('étape « Charge & résumé » : interrupteur activé par défaut, désactivation expliquée', () => {
    const onThresholds = vi.fn()
    const props = { application: undefined, steps: [newStep()], errors: {}, showErrors: true, load: load({ virtualUsers: '5' }),
      testRun: INITIAL_TEST_RUN, testStale: false, testPassed: false, csvRows: null, onChange: () => {}, onGoTo: () => {}, onThresholdsChange: onThresholds }
    const { unmount } = render(<LoadSummaryPanel info={EMPTY_INFO} {...props} />)
    const toggle = screen.getByRole('switch', { name: /Ralentir quand l’application demande d’attendre/ }) as HTMLInputElement
    expect(toggle.checked).toBe(true)
    expect((screen.getByLabelText(/Attente sans Retry-After/) as HTMLInputElement).value).toBe('5')
    fireEvent.change(screen.getByLabelText(/Rejeux de l’étape au plus/), { target: { value: '1' } })
    expect(onThresholds).toHaveBeenLastCalledWith({ throttleMaxRetries: '1' })
    fireEvent.click(toggle)
    expect(onThresholds).toHaveBeenLastCalledWith({ throttleOn429: false })
    unmount()

    render(<LoadSummaryPanel info={{ ...EMPTY_INFO, throttleOn429: false }} {...props} />)
    expect(screen.queryByLabelText(/Attente sans Retry-After/)).toBeNull()
    expect(screen.getByText(/les 429 sont ignorés et la charge continue au même débit/)).toBeTruthy()
  })
})

describe('Débit cible conseillé au-delà d’un seuil d’utilisateurs', () => {
  const WITH_THRESHOLD = { ...LIMITS, targetRpsAdvisedAboveVirtualUsers: 50 }
  const warning = 'Plus de 50 utilisateurs sans débit cible : le débit n’est pas borné. '
    + 'Fixez un débit cible pour maîtriser la charge envoyée et la mémoire de LoadPilot.'

  it('sous le seuil sans débit cible : rien ; au-dessus sans débit cible : avertissement ; avec débit cible : rien', () => {
    expect(loadWarnings(load({ virtualUsers: '50', targetRps: '', durationSeconds: '60' }), null, WITH_THRESHOLD)).toEqual([])
    expect(loadWarnings(load({ virtualUsers: '51', targetRps: '', durationSeconds: '60' }), null, WITH_THRESHOLD)).toEqual([warning])
    expect(loadWarnings(load({ virtualUsers: '120', targetRps: '80', durationSeconds: '60' }), null, WITH_THRESHOLD)).toEqual([])
    // Jamais bloquant : aucune erreur de validation pour autant.
    expect(validateLoad(load({ virtualUsers: '51', targetRps: '', durationSeconds: '60' }), WITH_THRESHOLD)).toEqual({})
    // Seuil 0 ou inconnu (serveur antérieur) : jamais d'avertissement.
    expect(loadWarnings(load({ virtualUsers: '150', targetRps: '', durationSeconds: '60' }), null, { ...LIMITS, targetRpsAdvisedAboveVirtualUsers: 0 })).toEqual([])
    expect(loadWarnings(load({ virtualUsers: '150', targetRps: '', durationSeconds: '60' }), null, LIMITS)).toEqual([])
  })
})

describe('Variation aléatoire du think time', () => {
  it('fixe par défaut ; validation, envoi au serveur et lecture d’un scénario enregistré', () => {
    expect(EMPTY_INFO.thinkTimeVariationPercent).toBe('')
    expect(validateThinkVariation(EMPTY_INFO)).toEqual({})
    expect(thinkVariationToRequest(EMPTY_INFO)).toEqual({ thinkTimeVariationPercent: 0 })
    expect(thinkVariationToRequest({})).toEqual({})
    expect(thinkVariationToRequest({ thinkTimeVariationPercent: '20' })).toEqual({ thinkTimeVariationPercent: 20 })
    expect(validateThinkVariation({ thinkTimeVariationPercent: '101' }).thinkTimeVariationPercent).toMatch(/ne doit pas dépasser 100/)
    expect(validateThinkVariation({ thinkTimeVariationPercent: '-1' }).thinkTimeVariationPercent).toBeDefined()
    expect(thinkVariationFromScenario({ thinkTimeVariationPercent: 20 })).toEqual({ thinkTimeVariationPercent: '20' })
    expect(thinkVariationFromScenario({})).toEqual({ thinkTimeVariationPercent: '' })
  })

  it('étape « Charge & résumé » : champ optionnel, « Fixe » par défaut', () => {
    const onThresholds = vi.fn()
    render(<LoadSummaryPanel info={EMPTY_INFO} application={undefined} steps={[newStep()]} errors={{}} showErrors
      load={load({ virtualUsers: '5' })} testRun={INITIAL_TEST_RUN} testStale={false} testPassed={false} csvRows={null}
      onChange={() => {}} onGoTo={() => {}} onThresholdsChange={onThresholds} />)
    const input = screen.getByLabelText(/Variation du think time/) as HTMLInputElement
    expect(input.placeholder).toBe('Fixe')
    fireEvent.change(input, { target: { value: '20' } })
    expect(onThresholds).toHaveBeenLastCalledWith({ thinkTimeVariationPercent: '20' })
  })
})
