import { FormEvent, useId, useState } from 'react'
import { useAuth } from '../../context/AuthContext'
import { useToast } from '../../context/ToastContext'
import { ApiError } from '../../services/api/httpClient'
import { profileBackendApi } from '../../services/api/profileBackend'
import type { BackendProfileResponse } from '../../types/backendContracts'
import { IdentityValues, validateIdentity } from './accountModel'

// « Modifier » (Super administrateur seulement) : prénom, nom et e-mail du compte connecté.
// Jamais le nom d'utilisateur, le rôle ni l'état du compte. Le serveur applique la même règle
// (403 pour les autres rôles) et journalise chaque modification avant → après.

interface Props {
  profile: BackendProfileResponse
  onSaved: (profile: BackendProfileResponse) => void
  onCancel: () => void
  /** Version compacte, dans le panneau de la barre latérale. */
  compact?: boolean
}

export default function IdentityForm({ profile, onSaved, onCancel, compact = false }: Props) {
  const id = useId()
  const { refreshUser } = useAuth()
  const { showToast } = useToast()
  const [values, setValues] = useState<IdentityValues>({
    firstName: profile.firstName ?? '',
    lastName: profile.lastName ?? '',
    email: profile.email ?? '',
  })
  const [touched, setTouched] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const errors = validateIdentity(values)
  const shown = touched ? errors : {}
  const unchanged = values.firstName.trim() === (profile.firstName ?? '') && values.lastName.trim() === (profile.lastName ?? '')
    && values.email.trim().toLowerCase() === (profile.email ?? '').toLowerCase()

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (Object.keys(errors).length > 0) return
    if (unchanged) { onCancel(); return }
    setSaving(true)
    setError(null)
    try {
      const updated = await profileBackendApi.updateIdentity({
        firstName: values.firstName.trim(), lastName: values.lastName.trim(), email: values.email.trim(),
      })
      // Le jeton porte le nom affiché : nouveau jeton pour mettre la barre latérale à jour
      // (sans effet sur l'enregistrement, déjà fait, s'il échoue).
      try {
        await refreshUser()
      } catch {
        // nom mis à jour au prochain renouvellement du jeton
      }
      showToast('Votre compte a été mis à jour.', 'success')
      onSaved(updated)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Impossible d’enregistrer vos informations.')
      setSaving(false)
    }
  }

  const field = (key: keyof IdentityValues, label: string, type = 'text', autoComplete?: string) => (
    <div className="acc-field">
      <label htmlFor={`${id}-${key}`}>{label}</label>
      <input id={`${id}-${key}`} type={type} className={`pt-form-control${shown[key] ? ' is-invalid' : ''}`} value={values[key]}
        autoComplete={autoComplete} aria-invalid={shown[key] ? true : undefined}
        aria-describedby={shown[key] ? `${id}-${key}-error` : undefined}
        onChange={(e) => setValues((v) => ({ ...v, [key]: e.target.value }))} />
      {shown[key] && <p className="acc-field-error" id={`${id}-${key}-error`}>{shown[key]}</p>}
    </div>
  )

  return (
    <form className={`acc-form${compact ? ' compact' : ''}`} onSubmit={submit} noValidate aria-label="Modifier mon nom et mon e-mail">
      {error && <div className="pt-alert-banner danger" role="alert"><i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}</div>}
      <div className="acc-form-row">
        {field('firstName', 'Prénom', 'text', 'given-name')}
        {field('lastName', 'Nom', 'text', 'family-name')}
      </div>
      {field('email', 'E-mail', 'email', 'email')}
      <p className="acc-form-note">Nom d’utilisateur et rôle : non modifiables.</p>
      <div className="acc-form-actions">
        <button type="button" className="pt-btn-ghost" onClick={onCancel} disabled={saving}>Annuler</button>
        <button type="submit" className="pt-btn-primary" disabled={saving}>{saving ? 'Enregistrement…' : 'Enregistrer'}</button>
      </div>
    </form>
  )
}
