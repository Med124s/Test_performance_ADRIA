import { useState } from 'react'
import type { BackendPasswordPolicy } from '../../types/backendContracts'
import { checkPassword, generatePassword } from '../../utils/passwordPolicy'

// Mot de passe choisi par l'administrateur (création, réinitialisation) : saisie + confirmation,
// afficher / masquer, « Générer » / « Copier », règles de la politique vérifiées en direct,
// et « changer à la première connexion ». Le mot de passe ne quitte ce composant que pour
// être envoyé au serveur ; il n'est jamais conservé ailleurs.

export interface PasswordDraft {
  password: string
  confirm: string
  temporary: boolean
}

export const EMPTY_PASSWORD: PasswordDraft = { password: '', confirm: '', temporary: true }

export interface PasswordIdentity { username?: string; email?: string }

/** Message bloquant (première règle non respectée), ou null si le mot de passe peut être envoyé. */
export function passwordProblem(draft: PasswordDraft, policy: BackendPasswordPolicy | null, identity: PasswordIdentity): string | null {
  if (!draft.password) return 'Saisissez un mot de passe ou cliquez sur « Générer ».'
  const failed = checkPassword(draft.password, policy, identity).find((r) => !r.ok)
  if (failed) return `Le mot de passe ne respecte pas la règle : ${failed.label.toLowerCase()}.`
  if (draft.confirm !== draft.password) return 'Les deux mots de passe ne sont pas identiques.'
  return null
}

export default function PasswordFields({ idPrefix, draft, onChange, policy, identity, showErrors }: {
  idPrefix: string
  draft: PasswordDraft
  onChange: (draft: PasswordDraft) => void
  policy: BackendPasswordPolicy | null
  identity: PasswordIdentity
  showErrors: boolean
}) {
  const [visible, setVisible] = useState(false)
  const [copied, setCopied] = useState(false)
  const rules = checkPassword(draft.password, policy, identity)
  const mismatch = draft.confirm.length > 0 && draft.confirm !== draft.password
  const type = visible ? 'text' : 'password'

  const generate = () => {
    const password = generatePassword(policy)
    onChange({ ...draft, password, confirm: password })
    setVisible(true)
    setCopied(false)
  }
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(draft.password)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="ur-password">
      <div className="ur-field">
        <label htmlFor={`${idPrefix}-password`}>Mot de passe <span className="ur-required" aria-hidden="true">*</span></label>
        <div className="ur-password-row">
          <div className="ur-password-input">
            <input id={`${idPrefix}-password`} type={type} className={`pt-form-control ${showErrors && rules.some((r) => !r.ok) ? 'is-invalid' : ''}`}
              value={draft.password} autoComplete="new-password" spellCheck={false}
              aria-describedby={`${idPrefix}-rules`}
              onChange={(e) => { onChange({ ...draft, password: e.target.value }); setCopied(false) }} />
            <button type="button" className="ur-reveal" aria-pressed={visible} aria-controls={`${idPrefix}-password ${idPrefix}-confirm`}
              aria-label={visible ? 'Masquer le mot de passe' : 'Afficher le mot de passe'} title={visible ? 'Masquer' : 'Afficher'}
              onClick={() => setVisible((v) => !v)}>
              <i className={`bi ${visible ? 'bi-eye-slash' : 'bi-eye'}`} aria-hidden="true"></i>
            </button>
          </div>
          <button type="button" className="pt-btn-outline ur-password-btn" onClick={generate}>
            <i className="bi bi-magic" aria-hidden="true"></i> Générer
          </button>
          <button type="button" className="pt-btn-outline ur-password-btn" onClick={copy} disabled={!draft.password}>
            <i className={`bi ${copied ? 'bi-check2' : 'bi-clipboard'}`} aria-hidden="true"></i> {copied ? 'Copié' : 'Copier'}
          </button>
        </div>
        <ul className="ur-rules" id={`${idPrefix}-rules`} aria-live="polite" aria-label="Règles du mot de passe">
          {rules.map((r) => (
            <li key={r.id} className={r.ok ? 'ok' : showErrors ? 'ko' : ''}>
              <i className={`bi ${r.ok ? 'bi-check-circle-fill' : showErrors ? 'bi-x-circle-fill' : 'bi-circle'}`} aria-hidden="true"></i>
              <span>{r.label}</span><span className="visually-hidden">{r.ok ? ' : respectée' : ' : non respectée'}</span>
            </li>
          ))}
        </ul>
      </div>
      <div className="ur-field">
        <label htmlFor={`${idPrefix}-confirm`}>Confirmation <span className="ur-required" aria-hidden="true">*</span></label>
        <input id={`${idPrefix}-confirm`} type={type} className={`pt-form-control ${(mismatch || (showErrors && draft.confirm !== draft.password)) ? 'is-invalid' : ''}`}
          value={draft.confirm} autoComplete="new-password" spellCheck={false}
          onChange={(e) => onChange({ ...draft, confirm: e.target.value })} />
        {(mismatch || (showErrors && draft.password && draft.confirm !== draft.password)) && (
          <div className="ur-error">Les deux mots de passe ne sont pas identiques.</div>
        )}
      </div>
      <label className="ur-check">
        <input type="checkbox" checked={draft.temporary} onChange={(e) => onChange({ ...draft, temporary: e.target.checked })} />
        <span>L’utilisateur doit le changer à sa première connexion</span>
      </label>
    </div>
  )
}
