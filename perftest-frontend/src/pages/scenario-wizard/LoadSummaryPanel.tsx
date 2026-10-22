import { useState } from 'react'
import { BackendApplicationResponse, BackendRequestDetailMode } from '../../types/backendContracts'
import { TestRunState } from './TestRunPanel'
import { useThresholds } from '../../utils/thresholds'
import {
  DraftStep,
  ERROR_BRAKE_DEFAULT_RATE,
  ERROR_BRAKE_MIN_REQUESTS,
  ERROR_BRAKE_WINDOW,
  FieldErrors,
  THROTTLE_429,
  LOAD_FIELDS,
  LOAD_PRESETS,
  LoadConfig,
  LoadLimits,
  formatInt,
  ScenarioInfo,
  estimateLoad,
  formatApproxSeconds,
  formatSeconds,
  loadWarnings,
  measuredRequestMs,
  presetBlockedReason,
} from './wizardModel'

interface LoadSummaryPanelProps {
  info: ScenarioInfo
  application: BackendApplicationResponse | undefined
  steps: DraftStep[]
  load: LoadConfig
  errors: FieldErrors
  showErrors: boolean
  testRun: TestRunState
  testStale: boolean
  /** Test complet réussi sur la version actuelle (aussi vrai pour un brouillon repris déjà testé). */
  testPassed: boolean
  /** Lignes de données du CSV valide (null sans CSV) : information sur la réutilisation cyclique. */
  csvRows: number | null
  onChange: (patch: Partial<LoadConfig>) => void
  onGoTo: (wizardStep: number) => void
  /** Réglage « Détail des requêtes » (enregistré avec les informations du scénario). */
  onRequestDetailModeChange?: (mode: BackendRequestDetailMode) => void
  /** Seuils de réussite (enregistrés avec les informations du scénario). */
  onThresholdsChange?: (patch: Partial<Pick<ScenarioInfo, 'maxErrorRatePercent' | 'maxP95Ms' | 'errorBrakeRatePercent' | 'errorBrakeWindowSeconds'
    | 'throttleOn429' | 'throttleDefaultDelaySeconds' | 'throttleMaxRetries' | 'thinkTimeVariationPercent'>>) => void
  /** Limites de charge en vigueur, lues depuis le serveur (null : inconnues). */
  limits?: LoadLimits | null
}

/** Icône ⓘ avec une explication courte, au survol et au clavier. */
function InfoTip({ text }: { text: string }) {
  return (
    <span className="sw-infotip" tabIndex={0} role="img" aria-label={text} title={text}>
      <i className="bi bi-info-circle" aria-hidden="true"></i>
    </span>
  )
}

function LoadSummaryPanel({ info, application, steps, load, errors, showErrors, testRun, testStale, testPassed, csvRows, onChange, onGoTo, onRequestDetailModeChange, onThresholdsChange, limits }: LoadSummaryPanelProps) {
  const err = (field: string) => (showErrors ? errors[field] : undefined)
  const setField = (patch: Partial<LoadConfig>) => onChange({ ...patch, presetId: 'custom' })

  const vu = Number(load.virtualUsers) || 0
  const ramp = Number(load.rampUpSeconds) || 0
  const iterations = Number(load.iterations) || 0
  const duration = Number(load.durationSeconds) || 0
  // Sans durée, chaque utilisateur joue `iterations` passages (1 si rien n'est renseigné).
  const effectiveIterations = duration ? null : iterations || 1
  const staggerSeconds = vu > 1 && ramp > 0 ? ramp / vu : 0
  // Estimation (durée totale, requêtes) : temps de réponse du test de validation s'il a été fait.
  const measured = measuredRequestMs(steps.map((s) => testRun.resultsByUid[s.uid]).filter(Boolean))
  const estimate = estimateLoad(load, steps.length, measured)
  const warnings = loadWarnings(load, estimate, limits)
  const blockedPresets = LOAD_PRESETS.filter((p) => presetBlockedReason(p, limits))
  const stopLabel = duration && iterations
    ? `${formatApproxSeconds(duration)} ou ${iterations} itération(s)`
    : duration ? formatApproxSeconds(duration) : `${effectiveIterations} itération(s)`

  const field = (key: keyof LoadConfig, label: string, hint: string, opts: { required?: boolean; placeholder?: string } = {}) => (
    <div className="col-12 col-sm-6" key={key}>
      <label className="pt-form-label" htmlFor={`load-${key}`}>
        {label} {opts.required ? <span className="sw-required" aria-hidden="true">*</span> : <span className="sw-optional">optionnel</span>}
      </label>
      <input id={`load-${key}`} className={`pt-form-control ${err(key) ? 'is-invalid' : ''}`} inputMode="numeric"
        placeholder={opts.placeholder} value={load[key]} onChange={(e) => setField({ [key]: e.target.value })}
        aria-required={opts.required || undefined} />
      {err(key) ? <div className="sw-field-error">{err(key)}</div> : <div className="sw-field-hint">{hint}</div>}
    </div>
  )

  const testedOk = testPassed
  // Interrupteur actif = taux renseigné ; reste ouvert si on vide le champ pour le ressaisir (vide = désactivé).
  const [brakeSwitch, setBrakeSwitch] = useState<boolean | null>(null)
  const brakeOn = brakeSwitch ?? !!info.errorBrakeRatePercent?.trim()
  const systemDefaults = useThresholds()
  const suggestedBrakeRate = systemDefaults.errorBrakeRatePercent != null
    ? String(systemDefaults.errorBrakeRatePercent).replace('.', ',') : ERROR_BRAKE_DEFAULT_RATE
  const throttleOn = info.throttleOn429 !== false

  return (
    <div className="sw-load-grid">
      <div className="d-flex flex-column gap-3">
        <div>
          <div className="sw-section-label">Profil de charge</div>
          <div className="sw-preset-grid">
            {LOAD_PRESETS.map((p) => {
              const blocked = presetBlockedReason(p, limits)
              return (
                <button
                  key={p.id}
                  type="button"
                  className={`sw-preset ${load.presetId === p.id ? 'selected' : ''}`}
                  disabled={!!blocked}
                  title={blocked ?? undefined}
                  aria-label={blocked ? `${p.label} — ${blocked}` : undefined}
                  onClick={() => onChange(p.values ? { presetId: p.id, ...p.values } : { presetId: p.id })}
                >
                  <i className={`bi ${p.icon}`}></i>
                  <span className="sw-preset-label">{p.label}</span>
                  <span className="sw-preset-desc">{blocked ?? p.description}</span>
                </button>
              )
            })}
          </div>
          {blockedPresets.length > 0 && (
            <div className="sw-field-hint mt-2">
              <i className="bi bi-info-circle"></i> {blockedPresets.map((p) => `« ${p.label} »`).join(' et ')}{' '}
              {blockedPresets.length > 1 ? 'dépassent' : 'dépasse'} les limites du serveur : choisissez « Personnalisé » pour régler la charge.
            </div>
          )}
        </div>

        <div className="pt-card">
          <div className="row g-3">
            {LOAD_FIELDS.map((f) => field(f.key, f.label,
              f.key === 'rampUpSeconds' && staggerSeconds
                ? `${f.hint(limits)} Un nouvel utilisateur toutes les ${staggerSeconds.toLocaleString('fr-FR', { maximumFractionDigits: 2 })} s.`
                : f.hint(limits),
              { required: f.required, placeholder: f.key === 'iterations' ? (duration ? 'Sans limite' : '1') : f.placeholder }))}
            {onThresholdsChange && (
              <div className="col-12 col-sm-6">
                <label className="pt-form-label" htmlFor="load-think-variation">
                  Variation du think time (± %) <span className="sw-optional">optionnel</span>
                  <InfoTip text="Chaque pause est tirée au hasard autour du think time (ex. 20 % : de 0,8 à 1,2 fois), pour des utilisateurs moins synchronisés. La moyenne ne change pas." />
                </label>
                <input id="load-think-variation" className={`pt-form-control ${errors.thinkTimeVariationPercent ? 'is-invalid' : ''}`} inputMode="numeric"
                  placeholder="Fixe" value={info.thinkTimeVariationPercent ?? ''} onChange={(e) => onThresholdsChange({ thinkTimeVariationPercent: e.target.value })} />
                {errors.thinkTimeVariationPercent
                  ? <div className="sw-field-error">{errors.thinkTimeVariationPercent}</div>
                  : <div className="sw-field-hint">0 à 100 ; vide = think time fixe. S’applique aussi aux pauses des étapes.</div>}
              </div>
            )}
            <div className="col-12">
              <div className="sw-field-hint">
                <i className="bi bi-info-circle"></i> Durée et itérations renseignées : le test s’arrête à la première limite atteinte.
                Aucune des deux : chaque utilisateur joue le scénario une seule fois.
              </div>
              {estimate && (
                <div className="sw-estimate" data-testid="load-estimate">
                  <i className="bi bi-calculator"></i>
                  <span>
                    <strong>Estimation</strong> (approximative) : durée totale ≈ {formatApproxSeconds(estimate.totalSeconds)} · ≈ {estimate.requests.toLocaleString('fr-FR')} requêtes.
                    <span className="d-block sw-estimate-basis">
                      {estimate.measured
                        ? `Calculée avec le temps de réponse moyen du test de validation (${estimate.requestMs.toLocaleString('fr-FR')} ms) ; les temps réels sous charge peuvent être plus longs.`
                        : `Calculée en supposant ${estimate.requestMs.toLocaleString('fr-FR')} ms par requête : lancez le test de validation pour l’affiner.`}
                    </span>
                  </span>
                </div>
              )}
            </div>
            {onThresholdsChange && (
              <div className="col-12">
                <div className="sw-section-label mt-1">Seuils de réussite</div>
                <div className="row g-3">
                  <div className="col-12 col-sm-6">
                    <label className="pt-form-label" htmlFor="load-max-error-rate">
                      Taux d’erreurs maximal (%) <span className="sw-required" aria-hidden="true">*</span>
                      <InfoTip text="« Échouée » est réservé à un test qui n’a pas pu se dérouler." />
                    </label>
                    <input id="load-max-error-rate" className={`pt-form-control ${errors.maxErrorRatePercent ? 'is-invalid' : ''}`} inputMode="decimal"
                      value={info.maxErrorRatePercent ?? '1'} onChange={(e) => onThresholdsChange({ maxErrorRatePercent: e.target.value })} aria-required />
                    {errors.maxErrorRatePercent
                      ? <div className="sw-field-error">{errors.maxErrorRatePercent}</div>
                      : <div className="sw-field-hint">Au-delà, l’exécution est « Seuils dépassés » (orange) au lieu de « Réussie ». Par défaut 1 %.</div>}
                  </div>
                  <div className="col-12 col-sm-6">
                    <label className="pt-form-label" htmlFor="load-max-p95">
                      P95 maximal (ms) <span className="sw-optional">optionnel</span>
                    </label>
                    <input id="load-max-p95" className={`pt-form-control ${errors.maxP95Ms ? 'is-invalid' : ''}`} inputMode="numeric"
                      placeholder="Aucun seuil" value={info.maxP95Ms ?? ''} onChange={(e) => onThresholdsChange({ maxP95Ms: e.target.value })} />
                    {errors.maxP95Ms
                      ? <div className="sw-field-error">{errors.maxP95Ms}</div>
                      : <div className="sw-field-hint">95 % des requêtes doivent répondre en moins de ce temps (toutes étapes confondues).</div>}
                  </div>
                </div>
                <div className="sw-section-label mt-3">Frein automatique</div>
                <div className="form-check form-switch">
                  <input className="form-check-input" type="checkbox" role="switch" id="load-error-brake"
                    checked={brakeOn}
                    onChange={(e) => {
                      setBrakeSwitch(e.target.checked)
                      // Activation : taux proposé par Paramètres > Système (20 % tant qu'il n'est pas chargé).
                      onThresholdsChange(e.target.checked
                        ? { errorBrakeRatePercent: suggestedBrakeRate, errorBrakeWindowSeconds: info.errorBrakeWindowSeconds || String(ERROR_BRAKE_WINDOW.default) }
                        : { errorBrakeRatePercent: '' })
                    }} />
                  <label className="form-check-label" htmlFor="load-error-brake">
                    Arrêter le test si trop d’erreurs{' '}
                    <InfoTip text="Protège l’application testée : le test est arrêté comme une annulation, avec un message distinct. Désactivé par défaut." />
                  </label>
                </div>
                {brakeOn && (
                  <div className="row g-3 mt-0">
                    <div className="col-12 col-sm-6">
                      <label className="pt-form-label" htmlFor="load-error-brake-rate">
                        Taux d’erreurs au-delà duquel arrêter (%) <span className="sw-required" aria-hidden="true">*</span>
                      </label>
                      <input id="load-error-brake-rate" className={`pt-form-control ${errors.errorBrakeRatePercent ? 'is-invalid' : ''}`} inputMode="decimal"
                        value={info.errorBrakeRatePercent ?? ''} onChange={(e) => onThresholdsChange({ errorBrakeRatePercent: e.target.value })} aria-required />
                      {errors.errorBrakeRatePercent
                        ? <div className="sw-field-error">{errors.errorBrakeRatePercent}</div>
                        : <div className="sw-field-hint">{info.errorBrakeRatePercent?.trim()
                          ? `Vérifié en continu pendant le test, à partir de ${ERROR_BRAKE_MIN_REQUESTS} requêtes dans la fenêtre.`
                          : 'Vide : frein désactivé.'}</div>}
                    </div>
                    <div className="col-12 col-sm-6">
                      <label className="pt-form-label" htmlFor="load-error-brake-window">
                        Sur les dernières (secondes) <span className="sw-required" aria-hidden="true">*</span>
                      </label>
                      <input id="load-error-brake-window" className={`pt-form-control ${errors.errorBrakeWindowSeconds ? 'is-invalid' : ''}`} inputMode="numeric"
                        value={info.errorBrakeWindowSeconds ?? ''} onChange={(e) => onThresholdsChange({ errorBrakeWindowSeconds: e.target.value })} aria-required />
                      {errors.errorBrakeWindowSeconds
                        ? <div className="sw-field-error">{errors.errorBrakeWindowSeconds}</div>
                        : <div className="sw-field-hint">Fenêtre glissante, de {ERROR_BRAKE_WINDOW.min} à {ERROR_BRAKE_WINDOW.max} s. Par défaut {ERROR_BRAKE_WINDOW.default} s.</div>}
                    </div>
                  </div>
                )}
                <div className="sw-section-label mt-3">Réponses 429 (Too Many Requests)</div>
                <div className="form-check form-switch">
                  <input className="form-check-input" type="checkbox" role="switch" id="load-throttle-429"
                    checked={throttleOn}
                    onChange={(e) => onThresholdsChange({ throttleOn429: e.target.checked })} />
                  <label className="form-check-label" htmlFor="load-throttle-429">
                    Ralentir quand l’application demande d’attendre{' '}
                    <InfoTip text="Sur un 429, l’utilisateur virtuel attend le délai demandé (en-tête Retry-After) puis rejoue l’étape. Les autres utilisateurs ne sont pas ralentis. Activé par défaut." />
                  </label>
                </div>
                {throttleOn ? (
                  <div className="row g-3 mt-0">
                    <div className="col-12 col-sm-6">
                      <label className="pt-form-label" htmlFor="load-throttle-delay">
                        Attente sans Retry-After (secondes) <span className="sw-required" aria-hidden="true">*</span>
                      </label>
                      <input id="load-throttle-delay" className={`pt-form-control ${errors.throttleDefaultDelaySeconds ? 'is-invalid' : ''}`} inputMode="numeric"
                        value={info.throttleDefaultDelaySeconds ?? ''} onChange={(e) => onThresholdsChange({ throttleDefaultDelaySeconds: e.target.value })} aria-required />
                      {errors.throttleDefaultDelaySeconds
                        ? <div className="sw-field-error">{errors.throttleDefaultDelaySeconds}</div>
                        : <div className="sw-field-hint">Si la réponse n’indique pas de délai. De 1 à {THROTTLE_429.maxDelaySeconds} s, par défaut {THROTTLE_429.defaultDelaySeconds} s.</div>}
                    </div>
                    <div className="col-12 col-sm-6">
                      <label className="pt-form-label" htmlFor="load-throttle-retries">
                        Rejeux de l’étape au plus <span className="sw-required" aria-hidden="true">*</span>
                      </label>
                      <input id="load-throttle-retries" className={`pt-form-control ${errors.throttleMaxRetries ? 'is-invalid' : ''}`} inputMode="numeric"
                        value={info.throttleMaxRetries ?? ''} onChange={(e) => onThresholdsChange({ throttleMaxRetries: e.target.value })} aria-required />
                      {errors.throttleMaxRetries
                        ? <div className="sw-field-error">{errors.throttleMaxRetries}</div>
                        : <div className="sw-field-hint">De 0 à {THROTTLE_429.maxRetries}, par défaut {THROTTLE_429.defaultMaxRetries}. Ensuite, l’étape échoue comme d’habitude.</div>}
                    </div>
                    <div className="col-12">
                      <div className="sw-field-hint">
                        <i className="bi bi-info-circle"></i> Un 429 reste compté comme une erreur dans le résultat final, mais pas dans le frein automatique : LoadPilot ralentit déjà.
                      </div>
                    </div>
                  </div>
                ) : (
                  <div className="sw-field-hint">
                    <i className="bi bi-exclamation-triangle"></i> Désactivé : les 429 sont ignorés et la charge continue au même débit (test de résilience). Ils comptent alors comme des erreurs, y compris pour le frein automatique.
                  </div>
                )}
              </div>
            )}
            {onRequestDetailModeChange && (
              <div className="col-12">
                <label className="pt-form-label" htmlFor="load-request-detail">
                  Détail des requêtes <InfoTip text="Requêtes conservées pour le détail de l’exécution, secrets masqués." />
                </label>
                <select id="load-request-detail" className="pt-form-control" value={info.requestDetailMode ?? 'FAILURES_AND_SAMPLE'}
                  onChange={(e) => onRequestDetailModeChange(e.target.value as BackendRequestDetailMode)}>
                  <option value="FAILURES_AND_SAMPLE">Échecs + échantillon (recommandé)</option>
                  <option value="FAILURES">Échecs seulement</option>
                  <option value="NONE">Aucun</option>
                </select>
              </div>
            )}
          </div>
          {warnings.length > 0 && (
            <div className="sw-inline-alert warn mt-3">
              <i className="bi bi-exclamation-triangle"></i>
              <span>{warnings.map((w) => <span key={w} className="d-block">{w}</span>)}</span>
            </div>
          )}
        </div>
      </div>

      <aside className="sw-summary">
        <div className="sw-summary-head">
          <div className="sw-summary-kicker">Résumé</div>
          <div className="sw-summary-title">{info.name || 'Sans nom'}</div>
          {info.description && <div className="sw-summary-desc">{info.description}</div>}
        </div>

        <div className="sw-summary-block">
          <div className="sw-summary-row">
            <span><i className="bi bi-hdd-network"></i> Application</span>
            <button type="button" className="sw-link" onClick={() => onGoTo(0)}>Modifier</button>
          </div>
          <div className="sw-summary-value">{application?.name ?? '—'}</div>
          <div className="sw-summary-sub">{application?.url}</div>
        </div>

        <div className="sw-summary-block">
          <div className="sw-summary-row">
            <span><i className="bi bi-list-ol"></i> {steps.length} étape(s)</span>
            <button type="button" className="sw-link" onClick={() => onGoTo(1)}>Modifier</button>
          </div>
          <ol className="sw-summary-steps">
            {steps.map((s, i) => {
              const r = testRun.resultsByUid[s.uid]
              return (
                <li key={s.uid}>
                  <span className={`sw-method-badge sw-method-${s.method.toLowerCase()}`}>{s.method}</span>
                  <span className="text-truncate">{s.name || `Étape ${i + 1}`}</span>
                  {r && !testStale && <i className={`bi ${r.success ? 'bi-check-circle-fill text-success' : 'bi-x-circle-fill text-danger'} ms-auto`}></i>}
                </li>
              )
            })}
          </ol>
        </div>

        <div className="sw-summary-block">
          <div className="sw-summary-row"><span><i className="bi bi-speedometer2"></i> Charge</span></div>
          <div className="sw-summary-stats">
            <div><strong>{vu || '—'}</strong><span>utilisateurs</span></div>
            <div><strong>{stopLabel}</strong><span>arrêt</span></div>
            <div><strong>{ramp ? formatSeconds(ramp) : '0 s'}</strong><span>ramp-up</span></div>
          </div>
          {estimate && <div className="sw-summary-sub mt-2"><i className="bi bi-arrow-left-right"></i> ≈ {estimate.requests.toLocaleString('fr-FR')} requêtes au total (estimation)</div>}
          {estimate && estimate.totalSeconds >= 1 && <div className="sw-summary-sub mt-2"><i className="bi bi-clock"></i> ≈ {formatApproxSeconds(estimate.totalSeconds)} de test (estimation)</div>}
          {csvRows !== null && vu > csvRows && (
            <div className="sw-summary-sub mt-2">
              <i className="bi bi-arrow-repeat"></i> CSV : {csvRows} ligne(s) pour {vu} utilisateurs — réutilisation cyclique (une ligne par utilisateur).
            </div>
          )}
        </div>

        <div className={`sw-summary-verdict ${testedOk ? 'ok' : 'warn'}`}>
          {testedOk ? (
            <><i className="bi bi-patch-check-fill"></i> Test de validation réussi</>
          ) : (
            <>
              <i className="bi bi-exclamation-triangle-fill"></i>
              <span>
                {testRun.phase === 'idle' ? 'Scénario non testé.' : testStale ? 'Modifié depuis le dernier test.' : 'Le dernier test comporte des erreurs.'}{' '}
                <button type="button" className="sw-link" onClick={() => onGoTo(2)}>Tester</button>
              </span>
            </>
          )}
        </div>
      </aside>
    </div>
  )
}

export default LoadSummaryPanel
