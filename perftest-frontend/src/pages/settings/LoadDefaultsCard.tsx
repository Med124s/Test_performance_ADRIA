import { useEffect, useId, useState } from 'react'
import { useToast } from '../../context/ToastContext'
import { settingsBackendApi } from '../../services/api/settingsBackend'
import type { BackendLoadDefaults } from '../../types/backendContracts'
import { setCachedLoadDefaults } from '../../utils/loadDefaults'
import { LOAD_FIELDS, LoadConfig, limitsOf, loadFromDefaults, validateLoad } from '../scenario-wizard/wizardModel'
import { describeSettingsError } from './settingsErrors'

// Charge par défaut (Paramètres > Système, Super administrateur) : valeurs proposées à l'étape
// « Charge & résumé » d'un nouveau scénario (preset « Personnalisé »). Mêmes libellés, aides et
// règles que cette étape et que le serveur. Les scénarios existants ne sont jamais modifiés ;
// chaque changement est inscrit au journal d'audit (avant → après).

type LoadValues = Omit<LoadConfig, 'presetId'>

const toValues = (d: BackendLoadDefaults): LoadValues => {
  const { presetId: _preset, ...values } = loadFromDefaults(d)
  return values
}
const optInt = (v: string) => (v.trim() === '' ? null : Number(v))

export default function LoadDefaultsCard() {
  const { showToast } = useToast()
  const id = useId()
  const [saved, setSaved] = useState<BackendLoadDefaults | null>(null)
  const [values, setValues] = useState<LoadValues | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    settingsBackendApi.loadDefaults()
      .then((d) => { setSaved(d); setValues(toValues(d)) })
      .catch((err) => setError(describeSettingsError(err, 'Impossible de charger la charge par défaut.')))
  }, [])

  const errors = values && saved ? validateLoad(values, limitsOf(saved)) : {}
  const valid = Object.keys(errors).length === 0
  const dirty = !!saved && !!values && JSON.stringify(values) !== JSON.stringify(toValues(saved))

  const save = async () => {
    if (!values || !valid) return
    setSaving(true)
    setError(null)
    try {
      const updated = await settingsBackendApi.updateLoadDefaults({
        virtualUsers: Number(values.virtualUsers), thinkTimeMs: Number(values.thinkTimeMs), rampUpSeconds: Number(values.rampUpSeconds),
        durationSeconds: optInt(values.durationSeconds), iterations: optInt(values.iterations), targetRps: optInt(values.targetRps),
      })
      setSaved(updated)
      setValues(toValues(updated))
      setCachedLoadDefaults(updated)
      showToast('Charge par défaut enregistrée.', 'success')
    } catch (err) {
      setError(describeSettingsError(err, 'Impossible d’enregistrer la charge par défaut.'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="pt-card au-settings mt-3 st-card" aria-labelledby={`${id}-title`}>
      <div className="au-settings-head">
        <i className="bi bi-speedometer2" aria-hidden="true"></i>
        <div>
          <h2 id={`${id}-title`}>Charge par défaut</h2>
          <p>Proposée à l’étape « Charge &amp; résumé » d’un nouveau scénario (profil « Personnalisé »). Les scénarios existants et les profils
            Smoke, Charge et Stress ne changent pas.</p>
        </div>
      </div>
      {error && <div className="pt-alert-banner danger mb-2" role="alert"><i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}</div>}
      {!values || !saved ? (!error && <p className="st-note">Chargement…</p>) : (
        <>
          {saved.problems.length > 0 && (
            <div className="pt-alert-banner warning mb-3" role="alert">
              <i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>
              <span>La charge par défaut dépasse les limites actuelles : {saved.problems.join(' ')} Corrigez-la ci-dessous.</span>
            </div>
          )}
          <div className="row g-3">
            {LOAD_FIELDS.map((f) => {
              const fieldError = errors[f.key]
              return (
                <div className="col-12 col-md-6" key={f.key}>
                  <label className="pt-form-label" htmlFor={`${id}-${f.key}`}>
                    {f.label} {f.required ? <span className="st-required" aria-hidden="true">*</span> : <span className="st-optional">optionnel</span>}
                  </label>
                  <input id={`${id}-${f.key}`} className={`pt-form-control${fieldError ? ' is-invalid' : ''}`} inputMode="numeric"
                    placeholder={f.placeholder} value={values[f.key]} aria-required={f.required || undefined}
                    aria-invalid={fieldError ? true : undefined} aria-describedby={`${id}-${f.key}-help`}
                    onChange={(e) => setValues((v) => (v ? { ...v, [f.key]: e.target.value } : v))} />
                  <div id={`${id}-${f.key}-help`} className={fieldError ? 'st-error st-field-help' : 'st-note st-field-help'}>
                    {fieldError ?? f.hint(limitsOf(saved))}
                  </div>
                </div>
              )
            })}
          </div>
          <p className="st-note st-load-rules">
            <i className="bi bi-info-circle me-1" aria-hidden="true"></i>
            Ni durée ni itérations : chaque utilisateur joue le scénario une seule fois. Durée et itérations renseignées :
            le test s’arrête à la première limite atteinte.
          </p>
          <div className="au-settings-actions">
            <button type="button" className="pt-btn-primary" onClick={save} disabled={!dirty || !valid || saving}>
              {saving ? 'Enregistrement…' : 'Enregistrer'}
            </button>
          </div>
        </>
      )}
    </section>
  )
}
