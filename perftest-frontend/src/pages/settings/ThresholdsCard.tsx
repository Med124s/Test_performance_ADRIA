import { useEffect, useId, useState } from 'react'
import { useToast } from '../../context/ToastContext'
import { settingsBackendApi } from '../../services/api/settingsBackend'
import type { BackendRequestDetailMode, BackendThresholdSettings } from '../../types/backendContracts'
import { setCachedThresholds, withDefaults } from '../../utils/thresholds'
import { describeSettingsError } from './settingsErrors'

// Seuils par défaut (Paramètres > Système, Super administrateur) :
//  - taux d'erreurs et P95 maximaux proposés aux NOUVEAUX scénarios (les existants ne changent pas) ;
//  - seuil de régression : même valeur pour le Dashboard, la liste des exécutions, la planification
//    et les notifications ;
//  - réglages proposés aux NOUVEAUX scénarios : frein automatique, ralentissement sur 429, détail des
//    requêtes. Écrits dans chaque scénario à sa création : les modifier ne change aucun scénario existant.
// Chaque changement est inscrit au journal d'audit (avant → après).

const isNumber = (v: string) => /^\d+([.,]\d+)?$/.test(v.trim())
const toNumber = (v: string) => Number(v.trim().replace(',', '.'))
const isInt = (v: string, min: number, max: number) => /^\d+$/.test(v.trim()) && Number(v) >= min && Number(v) <= max

export function validateThresholds(errorRate: string, p95: string, regression: string): string | null {
  if (!isNumber(errorRate) || toNumber(errorRate) > 100) return 'Taux d’erreurs maximal : entre 0 et 100 %.'
  if (p95.trim() !== '' && (!/^\d+$/.test(p95.trim()) || Number(p95) > 600000)) return 'P95 maximal : vide (aucun) ou une durée entre 1 et 600 000 ms.'
  if (!isNumber(regression) || toNumber(regression) < 1 || toNumber(regression) > 500) return 'Seuil de régression : entre 1 et 500 %.'
  return null
}

/** Réglages proposés aux nouveaux scénarios, saisis en texte. */
export interface ScenarioDefaultsForm {
  errorBrakeEnabled: boolean
  errorBrakeRate: string
  errorBrakeWindow: string
  throttleOn429: boolean
  throttleDelay: string
  throttleRetries: string
  requestDetailMode: BackendRequestDetailMode
}

/** Mêmes bornes que le serveur (ThresholdSettingsRequest, ScenarioRequest) et que l'assistant. */
export function validateScenarioDefaults(f: ScenarioDefaultsForm): string | null {
  if (!isNumber(f.errorBrakeRate) || toNumber(f.errorBrakeRate) <= 0 || toNumber(f.errorBrakeRate) > 100) {
    return 'Taux du frein automatique : supérieur à 0 et au plus 100 %.'
  }
  if (!isInt(f.errorBrakeWindow, 5, 300)) return 'Fenêtre du frein automatique : entre 5 et 300 secondes.'
  if (!isInt(f.throttleDelay, 1, 300)) return 'Attente après un 429 sans Retry-After : entre 1 et 300 secondes.'
  if (!isInt(f.throttleRetries, 0, 10)) return 'Rejeux après un 429 : entre 0 et 10.'
  return null
}

const DETAIL_MODES: { mode: BackendRequestDetailMode; label: string }[] = [
  { mode: 'FAILURES_AND_SAMPLE', label: 'Échecs + échantillon' },
  { mode: 'FAILURES', label: 'Échecs seulement' },
  { mode: 'NONE', label: 'Aucun' },
]

function toForm(t: Required<BackendThresholdSettings>): ScenarioDefaultsForm {
  return {
    errorBrakeEnabled: t.errorBrakeEnabled,
    errorBrakeRate: String(t.errorBrakeRatePercent).replace('.', ','),
    errorBrakeWindow: String(t.errorBrakeWindowSeconds),
    throttleOn429: t.throttleOn429,
    throttleDelay: String(t.throttleDefaultDelaySeconds),
    throttleRetries: String(t.throttleMaxRetries),
    requestDetailMode: t.requestDetailMode,
  }
}

export default function ThresholdsCard() {
  const { showToast } = useToast()
  const id = useId()
  const [saved, setSaved] = useState<Required<BackendThresholdSettings> | null>(null)
  const [errorRate, setErrorRate] = useState('1')
  const [p95, setP95] = useState('')
  const [regression, setRegression] = useState('20')
  const [defaults, setDefaults] = useState<ScenarioDefaultsForm | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const fill = (raw: BackendThresholdSettings) => {
    const t = withDefaults(raw)
    setSaved(t)
    setErrorRate(String(t.maxErrorRatePercent).replace('.', ','))
    setP95(t.maxP95Ms > 0 ? String(t.maxP95Ms) : '')
    setRegression(String(t.regressionPercent).replace('.', ','))
    setDefaults(toForm(t))
  }

  useEffect(() => {
    settingsBackendApi.thresholds().then(fill).catch((err) => setError(describeSettingsError(err, 'Impossible de charger les seuils.')))
  }, [])

  const setDefault = (patch: Partial<ScenarioDefaultsForm>) => setDefaults((d) => (d ? { ...d, ...patch } : d))
  const invalid = validateThresholds(errorRate, p95, regression) ?? (defaults ? validateScenarioDefaults(defaults) : null)
  const next: Required<BackendThresholdSettings> | null = invalid || !defaults ? null : {
    maxErrorRatePercent: toNumber(errorRate), maxP95Ms: p95.trim() === '' ? 0 : Number(p95), regressionPercent: toNumber(regression),
    errorBrakeEnabled: defaults.errorBrakeEnabled, errorBrakeRatePercent: toNumber(defaults.errorBrakeRate),
    errorBrakeWindowSeconds: Number(defaults.errorBrakeWindow), throttleOn429: defaults.throttleOn429,
    throttleDefaultDelaySeconds: Number(defaults.throttleDelay), throttleMaxRetries: Number(defaults.throttleRetries),
    requestDetailMode: defaults.requestDetailMode,
  }
  const dirty = !!saved && !!next && (Object.keys(next) as (keyof BackendThresholdSettings)[]).some((k) => next[k] !== saved[k])

  const save = async () => {
    if (!next) return
    setSaving(true)
    setError(null)
    try {
      const updated = await settingsBackendApi.updateThresholds(next)
      fill(updated)
      setCachedThresholds(updated)
      showToast('Seuils par défaut enregistrés.', 'success')
    } catch (err) {
      setError(describeSettingsError(err, 'Impossible d’enregistrer les seuils.'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="pt-card au-settings mt-3 st-card" aria-labelledby={`${id}-title`}>
      <div className="au-settings-head">
        <i className="bi bi-sliders" aria-hidden="true"></i>
        <div>
          <h2 id={`${id}-title`}>Seuils par défaut</h2>
          <p>Proposés à la création d’un scénario (modifiables ensuite dans chaque scénario), et seuil de régression commun à toute l’application.</p>
        </div>
      </div>
      {error && <div className="pt-alert-banner danger mb-2" role="alert"><i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}</div>}
      {!saved && !error ? <p className="st-note">Chargement…</p> : saved && defaults && (
        <div className="st-fields">
          <div className="st-field">
            <label htmlFor={`${id}-err`}>
              <strong>Taux d’erreurs maximal</strong>
              <span>Au-delà, une exécution qui va au bout est « Seuils dépassés ».</span>
            </label>
            <div className="st-unit">
              <input id={`${id}-err`} className="pt-form-control" inputMode="decimal" value={errorRate} onChange={(e) => setErrorRate(e.target.value)} />%
            </div>
          </div>
          <div className="st-field">
            <label htmlFor={`${id}-p95`}>
              <strong>P95 maximal</strong>
              <span>Temps de réponse sous lequel doivent rester 95 % des requêtes. Vide = aucun seuil.</span>
            </label>
            <div className="st-unit">
              <input id={`${id}-p95`} className="pt-form-control" inputMode="numeric" placeholder="Aucun" value={p95} onChange={(e) => setP95(e.target.value)} />ms
            </div>
          </div>
          <div className="st-field">
            <label htmlFor={`${id}-reg`}>
              <strong>Seuil de régression</strong>
              <span>Hausse du P95 par rapport à l’exécution précédente au-delà de laquelle elle est signalée : Dashboard, liste des exécutions, planification et notifications.</span>
            </label>
            <div className="st-unit">
              <input id={`${id}-reg`} className="pt-form-control" inputMode="decimal" value={regression} onChange={(e) => setRegression(e.target.value)} />%
            </div>
          </div>

          <h3 className="st-subtitle" style={{ fontSize: '14px', margin: '8px 0 0' }}>Nouveaux scénarios</h3>
          <p className="st-note" style={{ margin: 0 }}>
            Valeurs proposées dans l’assistant et enregistrées dans chaque nouveau scénario. Les scénarios existants gardent les leurs.
          </p>
          <div className="st-field">
            <label htmlFor={`${id}-brake`}>
              <strong>Arrêter le test si trop d’erreurs</strong>
              <span>Frein automatique activé par défaut, avec ce taux sur cette fenêtre glissante. Le taux est aussi celui proposé quand on l’active.</span>
            </label>
            <div className="form-check form-switch">
              <input id={`${id}-brake`} className="form-check-input" type="checkbox" role="switch" checked={defaults.errorBrakeEnabled}
                onChange={(e) => setDefault({ errorBrakeEnabled: e.target.checked })} />
            </div>
          </div>
          <div className="st-field">
            <label htmlFor={`${id}-brake-rate`}>
              <strong>Taux d’erreurs du frein</strong>
              <span>Au-delà, le test est arrêté.</span>
            </label>
            <div className="st-unit">
              <input id={`${id}-brake-rate`} className="pt-form-control" inputMode="decimal" value={defaults.errorBrakeRate}
                onChange={(e) => setDefault({ errorBrakeRate: e.target.value })} />%
            </div>
          </div>
          <div className="st-field">
            <label htmlFor={`${id}-brake-window`}>
              <strong>Fenêtre du frein</strong>
              <span>Durée glissante sur laquelle le taux est mesuré (5 à 300 s).</span>
            </label>
            <div className="st-unit">
              <input id={`${id}-brake-window`} className="pt-form-control" inputMode="numeric" value={defaults.errorBrakeWindow}
                onChange={(e) => setDefault({ errorBrakeWindow: e.target.value })} />s
            </div>
          </div>
          <div className="st-field">
            <label htmlFor={`${id}-throttle`}>
              <strong>Ralentir quand l’application demande d’attendre</strong>
              <span>Sur une réponse 429, attendre puis rejouer l’étape.</span>
            </label>
            <div className="form-check form-switch">
              <input id={`${id}-throttle`} className="form-check-input" type="checkbox" role="switch" checked={defaults.throttleOn429}
                onChange={(e) => setDefault({ throttleOn429: e.target.checked })} />
            </div>
          </div>
          <div className="st-field">
            <label htmlFor={`${id}-throttle-delay`}>
              <strong>Attente sans Retry-After</strong>
              <span>Si la réponse 429 n’indique pas de délai (1 à 300 s).</span>
            </label>
            <div className="st-unit">
              <input id={`${id}-throttle-delay`} className="pt-form-control" inputMode="numeric" value={defaults.throttleDelay}
                onChange={(e) => setDefault({ throttleDelay: e.target.value })} />s
            </div>
          </div>
          <div className="st-field">
            <label htmlFor={`${id}-throttle-retries`}>
              <strong>Rejeux de l’étape au plus</strong>
              <span>Après ce nombre de 429 consécutifs, l’étape compte comme un échec (0 à 10).</span>
            </label>
            <div className="st-unit">
              <input id={`${id}-throttle-retries`} className="pt-form-control" inputMode="numeric" value={defaults.throttleRetries}
                onChange={(e) => setDefault({ throttleRetries: e.target.value })} />
            </div>
          </div>
          <div className="st-field">
            <label htmlFor={`${id}-detail`}>
              <strong>Détail des requêtes</strong>
              <span>En-têtes et corps conservés pendant l’exécution.</span>
            </label>
            <select id={`${id}-detail`} className="pt-form-control" style={{ maxWidth: '14rem' }} value={defaults.requestDetailMode}
              onChange={(e) => setDefault({ requestDetailMode: e.target.value as BackendRequestDetailMode })}>
              {DETAIL_MODES.map((m) => <option key={m.mode} value={m.mode}>{m.label}</option>)}
            </select>
          </div>

          {invalid && <p className="st-error" role="alert">{invalid}</p>}
          <div className="au-settings-actions">
            <button type="button" className="pt-btn-primary" onClick={save} disabled={!dirty || saving}>{saving ? 'Enregistrement…' : 'Enregistrer'}</button>
          </div>
        </div>
      )}
    </section>
  )
}
