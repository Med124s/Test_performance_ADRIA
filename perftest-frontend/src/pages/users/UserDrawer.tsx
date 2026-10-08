import { useEffect, useRef, useState } from 'react'
import type { BackendAppRole, BackendUserSummaryResponse } from '../../types/backendContracts'
import { fullDate } from '../../utils/userDisplay'
import { LastLogin, RoleBadge, RoleOptions, StatusLabel, UserAvatar, displayNameOf } from './userParts'

// Panneau latéral d'un utilisateur : identité, choix du rôle, activité, actions du compte.
// Toutes les actions passent par une confirmation (voir UsersRoles) ; l'administrateur
// connecté ne peut ni retirer son propre rôle Administrateur ni désactiver son propre compte.

export interface UserDrawerProps {
  user: BackendUserSummaryResponse
  isSelf: boolean
  busy: boolean
  onClose: () => void
  onRequestRole: (role: BackendAppRole) => void
  onRequestReset: () => void
  onRequestStatus: (enabled: boolean) => void
}

const SELF_REASON = 'Vous ne pouvez pas retirer votre propre rôle Administrateur.'

export default function UserDrawer({ user, isSelf, busy, onClose, onRequestRole, onRequestReset, onRequestStatus }: UserDrawerProps) {
  const [pendingRole, setPendingRole] = useState<BackendAppRole | null>(user.role)
  const panelRef = useRef<HTMLDivElement>(null)
  const name = displayNameOf(user)
  const titleId = `ur-drawer-${user.id}`

  // Rôle affiché = rôle réel dès qu'il change (enregistrement, rechargement).
  useEffect(() => { setPendingRole(user.role) }, [user.id, user.role])

  useEffect(() => {
    panelRef.current?.querySelector<HTMLButtonElement>('.btn-close')?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !busy) onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose, busy])

  const lockedRoles: BackendAppRole[] = isSelf && user.role === 'SUPER_ADMIN' ? ['PERFORMANCE_ENGINEER', 'VIEWER'] : []
  const roleChanged = pendingRole !== null && pendingRole !== user.role
  const created = fullDate(user.createdAt)

  return (
    <div className="ur-drawer-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget && !busy) onClose() }}>
      <aside ref={panelRef} className="ur-drawer" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="ur-drawer-head">
          <UserAvatar user={user} large />
          <div className="ur-drawer-id">
            <h2 id={titleId}>{name}{isSelf && <span className="ur-you">vous</span>}</h2>
            <p>{user.email ?? 'Aucune adresse e-mail'}{name !== user.username && <> · {user.username}</>}</p>
            <div className="ur-drawer-badges"><RoleBadge role={user.role} /><StatusLabel enabled={user.enabled} /></div>
          </div>
          <button type="button" className="btn-close" aria-label="Fermer le panneau" onClick={onClose} disabled={busy}></button>
        </header>

        <div className="ur-drawer-body">
          <section className="ur-section" aria-labelledby={`${titleId}-role`}>
            <h3 id={`${titleId}-role`}>Rôle</h3>
            {isSelf && (
              <p className="ur-self-note"><i className="bi bi-info-circle" aria-hidden="true"></i>
                C’est votre compte : vous ne pouvez pas retirer votre rôle Administrateur ni désactiver votre compte.</p>
            )}
            <RoleOptions name={`role-${user.id}`} value={pendingRole} onChange={setPendingRole} disabledRoles={lockedRoles} disabledReason={SELF_REASON} />
            {roleChanged && (
              <div className="ur-role-save">
                <button type="button" className="pt-btn-outline" onClick={() => setPendingRole(user.role)} disabled={busy}>Annuler</button>
                <button type="button" className="pt-btn-primary" onClick={() => pendingRole && onRequestRole(pendingRole)} disabled={busy}>
                  <i className="bi bi-check-lg" aria-hidden="true"></i> Enregistrer le rôle
                </button>
              </div>
            )}
          </section>

          <section className="ur-section" aria-labelledby={`${titleId}-activity`}>
            <h3 id={`${titleId}-activity`}>Activité</h3>
            <dl className="ur-activity">
              <div><dt>Dernière connexion</dt><dd><LastLogin iso={user.lastLoginAt} /></dd></div>
              <div><dt>Exécutions lancées</dt><dd>{user.executionCount ?? 0}</dd></div>
              <div><dt>Compte créé</dt><dd>{created ? created.split(' à ')[0] : '—'}</dd></div>
            </dl>
          </section>

          <section className="ur-section" aria-labelledby={`${titleId}-actions`}>
            <h3 id={`${titleId}-actions`}>Compte</h3>
            <div className="ur-account-actions">
              <button type="button" className="pt-btn-outline" onClick={onRequestReset} disabled={busy}>
                <i className="bi bi-key" aria-hidden="true"></i> Réinitialiser le mot de passe
              </button>
              <p className="ur-hint">Définissez un nouveau mot de passe (temporaire ou non){user.email ? <>, ou envoyez un lien à {user.email}</> : null}.</p>
              {user.enabled ? (
                <button type="button" className="ur-btn-danger" onClick={() => onRequestStatus(false)} disabled={busy || isSelf}
                  title={isSelf ? 'Vous ne pouvez pas désactiver votre propre compte.' : undefined}>
                  <i className="bi bi-person-slash" aria-hidden="true"></i> Désactiver le compte
                </button>
              ) : (
                <button type="button" className="pt-btn-outline" onClick={() => onRequestStatus(true)} disabled={busy}>
                  <i className="bi bi-person-check" aria-hidden="true"></i> Réactiver le compte
                </button>
              )}
            </div>
          </section>
        </div>
      </aside>
    </div>
  )
}
