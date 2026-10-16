import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import AccountPanel from './account/AccountPanel'
import { accountRoleLabel } from './account/accountModel'
import NotificationBell from './NotificationBell'

interface TopBarIconsProps {
  onOpenMobileMenu: () => void
}

function TopBarIcons({ onOpenMobileMenu }: TopBarIconsProps) {
  const { user, rawRoles, logout } = useAuth()
  const location = useLocation()
  const userName = user?.name || 'Utilisateur'

  // Carte utilisateur : ouvre le panneau « Mon compte » sous la carte. Fermeture au clic à
  // l'extérieur, avec Échap (focus rendu à la carte) et après une navigation.
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const buttonRef = useRef<HTMLButtonElement | null>(null)
  useEffect(() => { setOpen(false) }, [location.pathname])
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => { if (!wrapRef.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { setOpen(false); buttonRef.current?.focus() } }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey) }
  }, [open])

  return (
    <div className="pt-global-topbar">
      {/* Bouton menu : visible uniquement sur mobile/tablette, ouvre le
          tiroir de navigation. */}
      <button
        className="topbar-icon d-lg-none"
        onClick={onOpenMobileMenu}
        title="Ouvrir le menu"
        aria-label="Ouvrir le menu de navigation"
        style={{ marginRight: 'auto' }}
      >
        <i className="bi bi-list" style={{ fontSize: '20px' }}></i>
      </button>

      {/* Cloche : panneau des dernières notifications et lien vers la page complète. */}
      <NotificationBell />
      <div className="tb-account" ref={wrapRef}>
        <button ref={buttonRef} type="button" className="topbar-icon tb-account-btn" aria-haspopup="dialog" aria-expanded={open}
          aria-label={`Mon compte : ${userName}`} onClick={() => setOpen((o) => !o)}>
          <span className="tb-account-avatar" aria-hidden="true">{user?.initials || 'U'}</span>
          <span className="tb-account-text">
            <span className="tb-account-name">{userName}</span>
            <span className="tb-account-role">{accountRoleLabel(rawRoles)}</span>
          </span>
          <i className="bi bi-chevron-down tb-account-chevron" aria-hidden="true"></i>
        </button>
        {open && (
          <AccountPanel onClose={() => { setOpen(false); buttonRef.current?.focus() }}
            onLogout={() => { setOpen(false); logout() }} />
        )}
      </div>
    </div>
  )
}

export default TopBarIcons
