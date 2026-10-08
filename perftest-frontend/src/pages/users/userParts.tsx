import { useEffect, useRef, type ReactNode } from 'react'
import type { BackendAppRole, BackendUserSummaryResponse } from '../../types/backendContracts'
import { ApiError } from '../../services/api/httpClient'
import { APP_ROLES, roleMeta } from '../../utils/appRoles'
import { avatarTone, fullDate, initialsOf, relativeTime } from '../../utils/userDisplay'

// Petits composants partagés par l'onglet Utilisateurs (tableau, panneau, fenêtres).

export function displayNameOf(user: BackendUserSummaryResponse): string {
  if (user.displayName?.trim()) return user.displayName.trim()
  const full = `${user.firstName ?? ''} ${user.lastName ?? ''}`.trim()
  return full || user.username
}

export function UserAvatar({ user, large = false }: { user: BackendUserSummaryResponse; large?: boolean }) {
  return (
    <span className={`ur-avatar ur-avatar--${avatarTone(user.id)} ${large ? 'ur-avatar--lg' : ''}`} aria-hidden="true">
      {initialsOf(displayNameOf(user))}
    </span>
  )
}

export function RoleBadge({ role }: { role: BackendAppRole | null }) {
  if (!role) return <span className="ur-role ur-role--none">Aucun rôle</span>
  const meta = roleMeta(role)
  return (
    <span className={`ur-role ur-role--${meta.tone}`}>
      <i className={`bi ${meta.icon}`} aria-hidden="true"></i><span>{meta.label}</span>
    </span>
  )
}

export function StatusLabel({ enabled }: { enabled: boolean }) {
  return (
    <span className={`ur-status ${enabled ? '' : 'off'}`}>
      <span className="ur-status-dot" aria-hidden="true"></span>{enabled ? 'Actif' : 'Désactivé'}
    </span>
  )
}

/** Date relative, date complète au survol ; « Jamais » si aucune connexion enregistrée. */
export function LastLogin({ iso }: { iso: string | null | undefined }) {
  const relative = relativeTime(iso)
  if (!relative) return <span className="ur-last never">Jamais</span>
  return <time className="ur-last" dateTime={iso ?? undefined} title={fullDate(iso) ?? undefined}>{relative}</time>
}

/** Choix du rôle : boutons radio avec une description courte de chacun. */
export function RoleOptions({ name, value, onChange, disabledRoles = [], disabledReason }: {
  name: string
  value: BackendAppRole | null
  onChange: (role: BackendAppRole) => void
  disabledRoles?: BackendAppRole[]
  disabledReason?: string
}) {
  return (
    <fieldset className="ur-role-options">
      <legend className="visually-hidden">Rôle</legend>
      {APP_ROLES.map((meta) => {
        const disabled = disabledRoles.includes(meta.role)
        const checked = value === meta.role
        return (
          <label key={meta.role} className={`ur-role-option ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''}`}
            title={disabled ? disabledReason : undefined}>
            <input type="radio" name={name} value={meta.role} checked={checked} disabled={disabled} onChange={() => onChange(meta.role)} />
            <span className={`ur-role-option-icon ur-role--${meta.tone}`} aria-hidden="true"><i className={`bi ${meta.icon}`}></i></span>
            <span className="ur-role-option-text"><strong>{meta.label}</strong><span>{meta.summary}</span></span>
          </label>
        )
      })}
    </fieldset>
  )
}

/** Fenêtre centrée (confirmation, nouvel utilisateur) : Échap pour fermer, focus initial dans la fenêtre. */
export function UserDialog({ title, titleId, onClose, busy = false, wide = false, children, footer }: {
  title: string
  titleId: string
  onClose: () => void
  busy?: boolean
  wide?: boolean
  children: ReactNode
  footer: ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const first = ref.current?.querySelector<HTMLElement>('input:not([disabled]), .ur-dialog-foot button:not([disabled])')
    first?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !busy) { e.stopPropagation(); onClose() } }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose, busy])
  return (
    <div className="ur-dialog-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget && !busy) onClose() }}>
      <div ref={ref} className={`ur-dialog ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby={titleId} aria-busy={busy}>
        <div className="ur-dialog-head">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="btn-close" aria-label="Fermer" onClick={onClose} disabled={busy}></button>
        </div>
        <div className="ur-dialog-body">{children}</div>
        <div className="ur-dialog-foot">{footer}</div>
      </div>
    </div>
  )
}

export function describeUserApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0: return err.message
      case 401: return 'Vous devez être connecté pour administrer les utilisateurs.'
      case 403: return 'Action refusée : l’administration des utilisateurs est réservée aux administrateurs.'
      case 404: return 'Utilisateur introuvable (il a peut-être été supprimé entre-temps).'
      case 400: case 409: case 502: return err.message
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}
