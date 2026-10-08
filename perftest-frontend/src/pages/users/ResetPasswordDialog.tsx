import { FormEvent, useState } from 'react'
import type { BackendUserSummaryResponse } from '../../types/backendContracts'
import { usersBackendApi } from '../../services/api/usersBackend'
import { UserDialog, describeUserApiError, displayNameOf } from './userParts'
import PasswordFields, { EMPTY_PASSWORD, PasswordDraft, passwordProblem } from './PasswordFields'
import { CredentialMode, CredentialModes } from './CreateUserModal'
import { usePasswordPolicy } from './usePasswordPolicy'

// « Réinitialiser le mot de passe » : nouveau mot de passe saisi / généré ici (temporaire ou non),
// ou lien envoyé par e-mail. Le mot de passe n'est jamais conservé ni renvoyé par LoadPilot.

export default function ResetPasswordDialog({ user, onClose, onDone }: {
  user: BackendUserSummaryResponse
  onClose: () => void
  /** Message de confirmation à afficher. */
  onDone: (message: string) => void
}) {
  const policy = usePasswordPolicy()
  const name = displayNameOf(user)
  const emailDisabledReason = !user.email ? 'Aucune adresse e-mail pour ce compte.'
    : !user.enabled ? 'Compte désactivé : réactivez-le pour envoyer un lien.' : null
  const [mode, setMode] = useState<CredentialMode>('password')
  const [draft, setDraft] = useState<PasswordDraft>(EMPTY_PASSWORD)
  const [attempted, setAttempted] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const problem = mode === 'password' ? passwordProblem(draft, policy, { username: user.username, email: user.email ?? undefined }) : null

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setAttempted(true)
    if (problem || saving) return
    setSaving(true)
    setError(null)
    try {
      if (mode === 'password') {
        await usersBackendApi.setPassword(user.id, draft.password, draft.temporary)
        setDraft(EMPTY_PASSWORD)
        onDone(draft.temporary
          ? `Nouveau mot de passe défini pour ${name}. Il devra le changer à sa prochaine connexion.`
          : `Nouveau mot de passe défini pour ${name}.`)
      } else {
        await usersBackendApi.resetPassword(user.id)
        onDone(`Lien de réinitialisation envoyé à ${user.email}.`)
      }
    } catch (err) {
      setError(describeUserApiError(err, 'La réinitialisation du mot de passe a échoué.'))
      setSaving(false)
    }
  }

  return (
    <UserDialog title="Réinitialiser le mot de passe" titleId="ur-reset-title" onClose={onClose} busy={saving} wide
      footer={<>
        <button type="button" className="pt-btn-outline" onClick={onClose} disabled={saving}>Annuler</button>
        <button type="submit" form="ur-reset-form" className="pt-btn-primary" disabled={saving}>
          {saving && <i className="bi bi-arrow-repeat pt-spin me-1" aria-hidden="true"></i>}
          {mode === 'password' ? 'Enregistrer le mot de passe' : 'Envoyer le lien'}
        </button>
      </>}>
      <form id="ur-reset-form" onSubmit={submit} noValidate style={{ display: 'contents' }}>
        <p>Compte de <strong>{name}</strong> ({user.username}).</p>
        {error && <div className="pt-alert-banner danger" role="alert"><i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}</div>}
        <CredentialModes name="ur-reset-mode" value={mode} onChange={setMode} emailDisabledReason={emailDisabledReason}
          passwordLabel="Saisir ou générer un mot de passe" />
        {mode === 'password' ? (
          <>
            <PasswordFields idPrefix="ur-reset" draft={draft} onChange={setDraft} policy={policy}
              identity={{ username: user.username, email: user.email ?? undefined }} showErrors={attempted} />
            {attempted && problem && <div className="ur-error" role="alert">{problem}</div>}
            <p className="ur-hint">L’ancien mot de passe ne fonctionnera plus. Communiquez le nouveau à la personne par un canal sûr.</p>
          </>
        ) : (
          <p className="ur-info"><i className="bi bi-envelope-paper" aria-hidden="true"></i>
            <span>{user.email} recevra un lien pour choisir un nouveau mot de passe (valable 12 h). L’ancien reste valable tant que le nouveau n’a pas été choisi.</span></p>
        )}
      </form>
    </UserDialog>
  )
}
