import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { notificationsBackendApi } from '../services/api/notificationsBackend'

const UNREAD_POLL_INTERVAL_MS = 20000

interface TopBarIconsProps {
  onOpenMobileMenu: () => void
}

function TopBarIcons({ onOpenMobileMenu }: TopBarIconsProps) {
  const { user } = useAuth()
  // P1-B — compteur RÉEL (voir NotificationController#unreadCount), même
  // source que Sidebar.tsx (chacun sondage indépendamment le backend, un
  // simple GET léger — jamais de state partagé fabriqué entre les deux).
  const [unreadCount, setUnreadCount] = useState(0)
  useEffect(() => {
    let cancelled = false
    const poll = () => {
      notificationsBackendApi.unreadCount()
        .then((res) => { if (!cancelled) setUnreadCount(res.count) })
        .catch(() => { /* silencieux */ })
    }
    poll()
    const interval = setInterval(poll, UNREAD_POLL_INTERVAL_MS)
    return () => { cancelled = true; clearInterval(interval) }
  }, [])

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
