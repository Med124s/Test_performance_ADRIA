import { useAuth } from '../context/AuthContext'
import { roleLabelFromRawRoles } from '../utils/appRoles'
import NotificationBell from './NotificationBell'

interface TopBarIconsProps {
  onOpenMobileMenu: () => void
}

function TopBarIcons({ onOpenMobileMenu }: TopBarIconsProps) {
  const { user, rawRoles } = useAuth()

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
      <div className="topbar-icon" style={{ gap: '0.5rem', padding: '0 0.5rem', width: 'auto' }}>
        <div
          style={{
            width: '32px',
            height: '32px',
            borderRadius: '50%',
            background: 'var(--pt-primary)',
            color: 'white',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: '12px',
            fontWeight: 600,
          }}
        >
          {user?.initials || 'AU'}
        </div>
        <div style={{ lineHeight: 1.2, textAlign: 'left' }}>
          <div style={{ fontSize: '12.5px', fontWeight: 600, color: 'var(--pt-text)' }}>{user?.name || 'Admin User'}</div>
          <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)' }}>{roleLabelFromRawRoles(rawRoles)}</div>
        </div>
      </div>
    </div>
  )
}

export default TopBarIcons
