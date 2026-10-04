import { BackendApplicationResponse } from '../../types/backendContracts'
import { TestRunState } from './TestRunPanel'
import {
  DraftStep,
  FieldErrors,
  LOAD_PRESETS,
  LoadConfig,
  ScenarioInfo,
  formatSeconds,
  loadWarnings,
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
}

function LoadSummaryPanel({ info, application, steps, load, errors, showErrors, testRun, testStale, testPassed, csvRows, onChange, onGoTo }: LoadSummaryPanelProps) {
  const err = (field: string) => (showErrors ? errors[field] : undefined)
  const setField = (patch: Partial<LoadConfig>) => onChange({ ...patch, presetId: 'custom' })

  const vu = Number(load.virtualUsers) || 0
  const ramp = Number(load.rampUpSeconds) || 0
  const iterations = Number(load.iterations) || 0
  const duration = Number(load.durationSeconds) || 0
  // Sans durée, chaque utilisateur joue `iterations` passages (1 si rien n'est renseigné).
  const effectiveIterations = duration ? null : iterations || 1
  const totalRequests = effectiveIterations !== null && vu ? vu * effectiveIterations * steps.length : null
  const estimatedSeconds = duration ? ramp + duration : null
  const staggerSeconds = vu > 1 && ramp > 0 ? ramp / vu : 0
  const warnings = loadWarnings(load)
  const stopLabel = duration && iterations
    ? `${formatSeconds(duration)} ou ${iterations} itération(s)`
    : duration ? formatSeconds(duration) : `${effectiveIterations} itération(s)`

  const field = (key: keyof LoadConfig, label: string, hint: string, opts: { required?: boolean; placeholder?: string } = {}) => (
    <div className="col-12 col-sm-6">
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

  return (
    <div className="sw-load-grid">
      <div className="d-flex flex-column gap-3">
        <div>
          <div className="sw-section-label">Profil de charge</div>
          <div className="sw-preset-grid">
            {LOAD_PRESETS.map((p) => (
              <button
                key={p.id}
                type="button"
                className={`sw-preset ${load.presetId === p.id ? 'selected' : ''}`}
                onClick={() => onChange(p.values ? { presetId: p.id, ...p.values } : { presetId: p.id })}
              >
                <i className={`bi ${p.icon}`}></i>
                <span className="sw-preset-label">{p.label}</span>
                <span className="sw-preset-desc">{p.description}</span>
              </button>
            ))}
          </div>
        </div>

        <div className="pt-card">
          <div className="row g-3">
            {field('virtualUsers', 'Utilisateurs virtuels',
              'Nombre d’utilisateurs simulés en parallèle (1 à 500).', { required: true })}
            {field('thinkTimeMs', 'Think time (ms)',
              'Pause d’un utilisateur entre deux itérations (après sa dernière étape, avant de recommencer). Aucune pause entre les étapes d’une même itération.',
              { required: true })}
            {field('rampUpSeconds', 'Ramp-up (s)',
              staggerSeconds
                ? `Temps pour démarrer tous les utilisateurs progressivement : un nouvel utilisateur toutes les ${staggerSeconds.toLocaleString('fr-FR', { maximumFractionDigits: 2 })} s.`
                : 'Temps pour démarrer tous les utilisateurs progressivement (0 = tous en même temps).',
              { required: true })}
            {field('durationSeconds', 'Durée (s)',
              'Chaque utilisateur répète le scénario jusqu’à la fin de cette durée (une itération commencée va jusqu’au bout).',
              { placeholder: 'Sans limite de temps' })}
            {field('iterations', 'Itérations',
              'Nombre de passages complets du scénario par utilisateur.', { placeholder: duration ? 'Sans limite' : '1' })}
            {field('targetRps', 'Débit cible (req/s)',
              'Limite globale de requêtes par seconde, partagée par tous les utilisateurs.', { placeholder: 'Illimité' })}
            <div className="col-12">
              <div className="sw-field-hint">
                <i className="bi bi-info-circle"></i> Durée et itérations renseignées : le test s’arrête à la première limite atteinte.
                Aucune des deux : chaque utilisateur joue le scénario une seule fois.
              </div>
            </div>
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
          {totalRequests !== null && <div className="sw-summary-sub mt-2"><i className="bi bi-arrow-left-right"></i> ≈ {totalRequests.toLocaleString('fr-FR')} requêtes au total</div>}
          {estimatedSeconds !== null && estimatedSeconds > 0 && <div className="sw-summary-sub mt-2"><i className="bi bi-clock"></i> ≈ {formatSeconds(estimatedSeconds)} de test</div>}
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
