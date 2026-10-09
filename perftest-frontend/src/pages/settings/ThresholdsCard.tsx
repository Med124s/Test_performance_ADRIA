import { useEffect, useId, useState } from 'react'
import { useToast } from '../../context/ToastContext'
import { settingsBackendApi } from '../../services/api/settingsBackend'
import type { BackendThresholdSettings } from '../../types/backendContracts'
import { setCachedThresholds } from '../../utils/thresholds'
import { describeSettingsError } from './settingsErrors'

// Seuils par défaut (Paramètres > Système, Super administrateur) :
//  - taux d'erreurs et P95 maximaux proposés aux NOUVEAUX scénarios (les existants ne changent pas) ;
//  - seuil de régression : même valeur pour le Dashboard, la liste des exécutions, la planification
//    et les notifications. Chaque changement est inscrit au journal d'audit (avant → après).

const isNumber = (v: string) => /^\d+([.,]\d+)?$/.test(v.trim())
const toNumber = (v: string) => Number(v.trim().replace(',', '.'))

export function validateThresholds(errorRate: string, p95: string, regression: string): string | null {
  if (!isNumber(errorRate) || toNumber(errorRate) > 100) return 'Taux d’erreurs maximal : entre 0 et 100 %.'
  if (p95.trim() !== '' && (!/^\d+$/.test(p95.trim()) || Number(p95) > 600000)) return 'P95 maximal : vide (aucun) ou une durée entre 1 et 600 000 ms.'
  if (!isNumber(regression) || toNumber(regression) < 1 || toNumber(regression) > 500) return 'Seuil de régression : entre 1 et 500 %.'
  return null
}

export default function ThresholdsCard() {
  const { showToast } = useToast()
  const id = useId()
  const [saved, setSaved] = useState<BackendThresholdSettings | null>(null)
  const [errorRate, setErrorRate] = useState('1')
  const [p95, setP95] = useState('')
  const [regression, setRegression] = useState('20')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const fill = (t: BackendThresholdSettings) => {
    setSaved(t)
    setErrorRate(String(t.maxErrorRatePercent).replace('.', ','))
    setP95(t.maxP95Ms > 0 ? String(t.maxP95Ms) : '')
    setRegression(String(t.regressionPercent).replace('.', ','))
  }

  useEffect(() => {
    settingsBackendApi.thresholds().then(fill).catch((err) => setError(describeSettingsError(err, 'Impossible de charger les seuils.')))
  }, [])

  const invalid = validateThresholds(errorRate, p95, regression)
  const next: BackendThresholdSettings | null = invalid ? null
    : { maxErrorRatePercent: toNumber(errorRate), maxP95Ms: p95.trim() === '' ? 0 : Number(p95), regressionPercent: toNumber(regression) }
  const dirty = !!saved && !!next && (next.maxErrorRatePercent !== saved.maxErrorRatePercent || next.maxP95Ms !== saved.maxP95Ms
    || next.regressionPercent !== saved.regressionPercent)

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
      {!saved && !error ? <p className="st-note">Chargement…</p> : saved && (
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
          {invalid && <p className="st-error" role="alert">{invalid}</p>}
          <div className="au-settings-actions">
            <button type="button" className="pt-btn-primary" onClick={save} disabled={!dirty || saving}>{saving ? 'Enregistrement…' : 'Enregistrer'}</button>
          </div>
        </div>
      )}
    </section>
  )
}
