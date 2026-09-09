import { Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { getUnreadNotificationsCount } from '../utils/notificationsStore'

interface TopBarIconsProps {
  onOpenMobileMenu: () => void
}

function TopBarIcons({ onOpenMobileMenu }: TopBarIconsProps) {
  const { user } = useAuth()
  // Recalculé à chaque montage : reflète le vrai nombre de notifications
  // non lues (store partagé), au lieu d'un badge figé à "5".
  const unreadCount = getUnreadNotificationsCount()

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

      <Link to="/notifications" className="topbar-icon" title="Notifications">
        <i className="bi bi-bell" style={{ fontSize: '18px' }}></i>
        {unreadCount > 0 && <span className="badge-dot">{unreadCount}</span>}
      </Link>
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
          <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)' }}>{user?.role || 'Admin'}</div>
        </div>
      </div>
    </div>
  )
}

export default TopBarIcons
