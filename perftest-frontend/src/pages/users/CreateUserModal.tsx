import { FormEvent, useState } from 'react'
import type { BackendAppRole, BackendUserCreatedResponse } from '../../types/backendContracts'
import { usersBackendApi } from '../../services/api/usersBackend'
import { RoleOptions, UserDialog, describeUserApiError } from './userParts'
import PasswordFields, { EMPTY_PASSWORD, PasswordDraft, passwordProblem } from './PasswordFields'
import { usePasswordPolicy } from './usePasswordPolicy'

// « Ajouter un utilisateur » : le compte est créé avec son rôle, et le mot de passe est
// - soit défini tout de suite par l'administrateur (par défaut ; temporaire ou non),
// - soit choisi par la personne via un lien reçu par e-mail.
// Le mot de passe n'est envoyé qu'au serveur, qui le transmet au service d'authentification
// sans le conserver ni le journaliser.

const USERNAME_PATTERN = /^[a-zA-Z0-9._-]{3,50}$/
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export type CredentialMode = 'password' | 'email'

export default function CreateUserModal({ onClose, onCreated }: {
  onClose: () => void
  onCreated: (result: BackendUserCreatedResponse) => void
}) {
  const policy = usePasswordPolicy()
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [role, setRole] = useState<BackendAppRole>('VIEWER')
  const [mode, setMode] = useState<CredentialMode>('password')
  const [password, setPassword] = useState<PasswordDraft>(EMPTY_PASSWORD)
  const [attempted, setAttempted] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const usernameError = !username.trim() ? 'Le nom d’utilisateur est obligatoire.'
    : !USERNAME_PATTERN.test(username.trim()) ? '3 à 50 caractères : lettres, chiffres, points, tirets.' : null
  const emailError = email.trim() && !EMAIL_PATTERN.test(email.trim()) ? 'L’e-mail n’est pas valide.'
    : mode === 'email' && !email.trim() ? 'L’e-mail est obligatoire pour envoyer le lien.' : null
  const passwordError = mode === 'password' ? passwordProblem(password, policy, { username, email }) : null

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setAttempted(true)
    if (usernameError || emailError || passwordError || saving) return
    setSaving(true)
    setError(null)
    try {
      const created = await usersBackendApi.create({
        username: username.trim(), email: email.trim() || null, firstName: firstName.trim() || null, lastName: lastName.trim() || null, role,
        ...(mode === 'password' ? { password: password.password, temporaryPassword: password.temporary } : {}),
      })
      setPassword(EMPTY_PASSWORD)
      onCreated(created)
    } catch (err) {
      setError(describeUserApiError(err, 'Création de l’utilisateur impossible.'))
      setSaving(false)
    }
  }

  return (
    <UserDialog title="Ajouter un utilisateur" titleId="ur-create-title" onClose={onClose} busy={saving} wide
      footer={<>
        <button type="button" className="pt-btn-outline" onClick={onClose} disabled={saving}>Annuler</button>
        <button type="submit" form="ur-create-form" className="pt-btn-primary" disabled={saving}>
          {saving ? <><i className="bi bi-arrow-repeat pt-spin" aria-hidden="true"></i> Création…</>
            : mode === 'password' ? <><i className="bi bi-person-plus" aria-hidden="true"></i> Créer l’utilisateur</>
              : <><i className="bi bi-send" aria-hidden="true"></i> Créer et envoyer le lien</>}
        </button>
      </>}>
      <form id="ur-create-form" onSubmit={submit} noValidate style={{ display: 'contents' }}>
        {error && <div className="pt-alert-banner danger" role="alert"><i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}</div>}
        <div className="ur-grid-2">
          <div className="ur-field">
            <label htmlFor="ur-new-username">Nom d’utilisateur <span className="ur-required" aria-hidden="true">*</span></label>
            <input id="ur-new-username" className={`pt-form-control ${attempted && usernameError ? 'is-invalid' : ''}`} value={username}
              onChange={(e) => setUsername(e.target.value)} autoComplete="off" aria-invalid={attempted && !!usernameError} aria-describedby="ur-new-username-err" />
            {attempted && usernameError && <div className="ur-error" id="ur-new-username-err">{usernameError}</div>}
          </div>
          <div className="ur-field">
            <label htmlFor="ur-new-email">E-mail {mode === 'email' && <span className="ur-required" aria-hidden="true">*</span>}</label>
            <input id="ur-new-email" type="email" className={`pt-form-control ${attempted && emailError ? 'is-invalid' : ''}`} value={email}
              onChange={(e) => setEmail(e.target.value)} autoComplete="off" aria-invalid={attempted && !!emailError} aria-describedby="ur-new-email-err" />
            {attempted && emailError && <div className="ur-error" id="ur-new-email-err">{emailError}</div>}
          </div>
          <div className="ur-field">
            <label htmlFor="ur-new-first">Prénom</label>
            <input id="ur-new-first" className="pt-form-control" value={firstName} onChange={(e) => setFirstName(e.target.value)} autoComplete="off" />
          </div>
          <div className="ur-field">
            <label htmlFor="ur-new-last">Nom</label>
            <input id="ur-new-last" className="pt-form-control" value={lastName} onChange={(e) => setLastName(e.target.value)} autoComplete="off" />
          </div>
        </div>
        <div className="ur-field">
          {/* Nom accessible : légende (masquée) du groupe de boutons radio. */}
          <div className="ur-field-label" aria-hidden="true">Rôle</div>
          <RoleOptions name="ur-new-role" value={role} onChange={setRole} />
        </div>

        <div className="ur-field">
          <div className="ur-field-label" aria-hidden="true">Mot de passe</div>
          <CredentialModes name="ur-new-mode" value={mode} onChange={setMode} />
        </div>
        {mode === 'password' ? (
          <>
            <PasswordFields idPrefix="ur-new" draft={password} onChange={setPassword} policy={policy} identity={{ username, email }} showErrors={attempted} />
            {attempted && passwordError && <div className="ur-error" role="alert">{passwordError}</div>}
          </>
        ) : (
          <p className="ur-info"><i className="bi bi-envelope-paper" aria-hidden="true"></i>
            <span>La personne reçoit un e-mail avec un lien pour choisir son mot de passe (valable 12 h).</span></p>
        )}
      </form>
    </UserDialog>
  )
}

/** Deux façons de fixer le mot de passe (création et réinitialisation). */
export function CredentialModes({ name, value, onChange, emailDisabledReason, passwordLabel = 'Définir le mot de passe maintenant' }: {
  name: string
  value: CredentialMode
  onChange: (mode: CredentialMode) => void
  emailDisabledReason?: string | null
  passwordLabel?: string
}) {
  return (
    <fieldset className="ur-modes">
      <legend className="visually-hidden">Mot de passe</legend>
      <label className={`ur-mode ${value === 'password' ? 'checked' : ''}`}>
        <input type="radio" name={name} checked={value === 'password'} onChange={() => onChange('password')} />
        <span><strong>{passwordLabel}</strong><span>Saisi ou généré ici, à communiquer à la personne.</span></span>
      </label>
      <label className={`ur-mode ${value === 'email' ? 'checked' : ''} ${emailDisabledReason ? 'disabled' : ''}`} title={emailDisabledReason ?? undefined}>
        <input type="radio" name={name} checked={value === 'email'} disabled={!!emailDisabledReason} onChange={() => onChange('email')} />
        <span><strong>Envoyer un lien par e-mail</strong><span>{emailDisabledReason ?? 'La personne choisit elle-même son mot de passe.'}</span></span>
      </label>
    </fieldset>
  )
}
