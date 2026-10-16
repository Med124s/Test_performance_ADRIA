import { KeyboardEvent, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { profileBackendApi } from '../../services/api/profileBackend'
import { redirectToAccountAction } from '../../services/auth/keycloakClient'
import type { BackendProfileResponse } from '../../types/backendContracts'
import { fullDate, relativeTime } from '../../utils/userDisplay'
import { accountRoleLabel, browserTimezone } from './accountModel'
import IdentityForm from './IdentityForm'
import './account.css'

// ============================================================
// Panneau « Mon compte » (clic sur la carte utilisateur de la barre du haut, affiché sous la carte) :
// identité, rôle, statut, dernière connexion, fuseau horaire ; liens Mon profil,
// Changer mon mot de passe, Déconnexion. « Modifier » (prénom, nom, e-mail) pour le
// Super administrateur seulement. Fermeture : clic à l'extérieur ou Échap (géré par
// la carte qui l'ouvre) ; Tab reste dans le panneau, flèches haut / bas entre les actions.
// ============================================================

interface Props {
  onClose: () => void
  onLogout: () => void
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])'

export default function AccountPanel({ onClose, onLogout }: Props) {
  const { user, rawRoles } = useAuth()
  const navigate = useNavigate()
  const panelRef = useRef<HTMLDivElement | null>(null)
  const [profile, setProfile] = useState<BackendProfileResponse | null>(null)
  const [lastLogin, setLastLogin] = useState<string | null | undefined>(undefined)
  const [editing, setEditing] = useState(false)
  const [loadError, setLoadError] = useState(false)
  const [opening, setOpening] = useState(false)

  useEffect(() => {
    let cancelled = false
    profileBackendApi.get().then((p) => { if (!cancelled) setProfile(p) }).catch(() => { if (!cancelled) setLoadError(true) })
    profileBackendApi.security().then((s) => { if (!cancelled) setLastLogin(s.lastLoginAt) }).catch(() => { if (!cancelled) setLastLogin(null) })
    return () => { cancelled = true }
  }, [])

  // Le focus entre dans le panneau à l'ouverture.
  useEffect(() => {
    panelRef.current?.querySelector<HTMLElement>('.acc-action, .acc-edit-btn')?.focus()
  }, [profile === null])

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const panel = panelRef.current
    if (!panel) return
    if (e.key === 'Tab') {
      // Tab et Maj+Tab restent dans le panneau tant qu'il est ouvert.
      const items = [...panel.querySelectorAll<HTMLElement>(FOCUSABLE)]
      if (items.length === 0) return
      const first = items[0]
      const last = items[items.length - 1]
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus() }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus() }
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      const actions = [...panel.querySelectorAll<HTMLElement>('.acc-action')]
      const index = actions.indexOf(document.activeElement as HTMLElement)
      if (index < 0) return
      e.preventDefault()
      actions[(index + (e.key === 'ArrowDown' ? 1 : actions.length - 1)) % actions.length].focus()
    }
  }

  const changePassword = async () => {
    setOpening(true)
    try {
      await redirectToAccountAction('UPDATE_PASSWORD', '/parametres?tab=profil')
    } catch {
      setOpening(false)
    }
  }

  const name = profile?.name || user?.name || 'Utilisateur'
  const roleLabel = profile?.appRoleLabel ?? accountRoleLabel(rawRoles)
  const timezone = profile?.timezone ?? null

  return (
    <div ref={panelRef} className="acc-panel" role="dialog" aria-modal="false" aria-labelledby="acc-title" onKeyDown={onKeyDown}>
      <h2 id="acc-title" className="visually-hidden">Mon compte</h2>
      <div className="acc-head">
        <span className="acc-avatar" aria-hidden="true">{user?.initials || 'U'}</span>
        <div className="acc-identity">
          <strong className="acc-name">{name}</strong>
          <span className="acc-username">@{profile?.username ?? '…'}</span>
          <span className="acc-email">{profile?.email || user?.email || '—'}</span>
        </div>
      </div>

      {editing && profile ? (
        <IdentityForm compact profile={profile} onCancel={() => setEditing(false)}
          onSaved={(p) => { setProfile(p); setEditing(false) }} />
      ) : (
        <>
          <dl className="acc-facts">
            <div><dt>Rôle</dt><dd><span className="acc-role">{roleLabel}</span></dd></div>
            <div><dt>Statut</dt><dd><span className="acc-status"><i className="bi bi-circle-fill" aria-hidden="true"></i> Actif</span></dd></div>
            <div>
              <dt>Dernière connexion</dt>
              <dd>
                {lastLogin === undefined ? '…'
                  : lastLogin ? <time dateTime={lastLogin} title={fullDate(lastLogin) ?? undefined}>{relativeTime(lastLogin)}</time>
                  : 'Inconnue'}
              </dd>
            </div>
            <div>
              <dt>Fuseau horaire</dt>
              <dd>{timezone ?? <>{browserTimezone()} <span className="acc-muted">(navigateur)</span></>}</dd>
            </div>
          </dl>
          {loadError && <p className="acc-muted acc-note" role="status">Informations du compte indisponibles pour le moment.</p>}
          {profile?.canEditIdentity && (
            <button type="button" className="acc-edit-btn" onClick={() => setEditing(true)}>
              <i className="bi bi-pencil" aria-hidden="true"></i> Modifier
            </button>
          )}
        </>
      )}

      <div className="acc-actions" role="group" aria-label="Actions du compte">
        <button type="button" className="acc-action" onClick={() => { onClose(); navigate('/parametres?tab=profil') }}>
          <i className="bi bi-person-circle" aria-hidden="true"></i> Mon profil
        </button>
        <button type="button" className="acc-action" onClick={changePassword} disabled={opening}>
          <i className="bi bi-key" aria-hidden="true"></i> {opening ? 'Ouverture…' : 'Changer mon mot de passe'}
        </button>
        <button type="button" className="acc-action danger" onClick={onLogout}>
          <i className="bi bi-box-arrow-right" aria-hidden="true"></i> Déconnexion
        </button>
      </div>
    </div>
  )
}
