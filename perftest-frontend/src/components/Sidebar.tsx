import { useEffect, useRef, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { getUnreadNotificationsCount } from '../utils/notificationsStore'

interface SidebarProps {
  darkMode: boolean
  toggleDarkMode: () => void
  /** Tiroir mobile/tablette ouvert ou non (contrôlé par MainLayout). */
  mobileOpen: boolean
  onCloseMobile: () => void
}

interface NavItem {
  path: string
  icon: string
  label: string
  badge?: number
}

// Section "OVERVIEW" : le cœur du flux de travail de test de performance.
const overviewItems: NavItem[] = [
  { path: '/', icon: 'bi-grid-1x2-fill', label: 'Dashboard' },
  { path: '/applications', icon: 'bi-globe2', label: 'Applications' },
  { path: '/scenarios', icon: 'bi-collection-play', label: 'Scénarios' },
  { path: '/executions', icon: 'bi-play-circle', label: 'Exécutions' },
  { path: '/metriques', icon: 'bi-graph-up', label: 'Métriques' },
  { path: '/rapports', icon: 'bi-file-earmark-bar-graph', label: 'Rapports' },
  { path: '/historique', icon: 'bi-clock-history', label: 'Historique' },
  { path: '/notifications', icon: 'bi-bell', label: 'Notifications' },
]

// Section "ACCOUNT" : administration & réglages.
const accountItems: NavItem[] = [
  { path: '/users-roles', icon: 'bi-people', label: 'Users & Roles' },
  { path: '/roles-catalog', icon: 'bi-shield-lock', label: 'Catalogue des rôles' },
  { path: '/audit-logs', icon: 'bi-shield-check', label: 'Audit Logs' },
  { path: '/configurations', icon: 'bi-gear', label: 'Configurations' },
  { path: '/settings', icon: 'bi-sliders', label: 'Settings' },
]

const allItems = [...overviewItems, ...accountItems]

interface FloatingLabel {
  top: number
  text: string
}

function Sidebar({ darkMode, toggleDarkMode, mobileOpen, onCloseMobile }: SidebarProps) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  // Desktop : icônes + libellés (déplié) ou icônes seules (replié) — la
  // préférence est mémorisée. Sur mobile/tablette, ce réglage est ignoré :
  // le tiroir affiche toujours le menu complet une fois ouvert.
  const [isExpanded, setIsExpanded] = useState(
    () => localStorage.getItem('pt_sidebar_expanded') !== '0'
  )
  const toggleExpanded = () => {
    setIsExpanded((prev) => {
      const next = !prev
      localStorage.setItem('pt_sidebar_expanded', next ? '1' : '0')
      return next
    })
  }

  // Étiquette flottante (mode replié uniquement) : navigue ET affiche son
  // libellé pour la page active ; le survol affiche aussi un aperçu.
  const itemRefs = useRef<Record<string, HTMLElement | null>>({})
  const [hoveredLabel, setHoveredLabel] = useState<FloatingLabel | null>(null)
  const [activeLabel, setActiveLabel] = useState<FloatingLabel | null>(null)

  const computeActiveLabel = () => {
    if (isExpanded) { setActiveLabel(null); return }
    const active = allItems.find((item) =>
      item.path === '/' ? location.pathname === '/' : location.pathname.startsWith(item.path)
    )
    if (active) {
      const el = itemRefs.current[active.path]
      if (el) {
        const rect = el.getBoundingClientRect()
        setActiveLabel({ top: rect.top + rect.height / 2, text: active.label })
        return
      }
    }
    setActiveLabel(null)
  }

  useEffect(() => {
    const id = requestAnimationFrame(computeActiveLabel)
    return () => cancelAnimationFrame(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname, isExpanded])

  // Ferme le tiroir mobile automatiquement après une navigation.
  useEffect(() => {
    onCloseMobile()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname])

  const unreadCount = getUnreadNotificationsCount()
  const navOverviewItems = overviewItems.map((item) =>
    item.path === '/notifications' && unreadCount > 0 ? { ...item, badge: unreadCount } : item
  )

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  const showHover = (path: string, label: string) => (e: React.MouseEvent<HTMLElement>) => {
    if (isExpanded) return
    itemRefs.current[path] = e.currentTarget
    const rect = e.currentTarget.getBoundingClientRect()
    setHoveredLabel({ top: rect.top + rect.height / 2, text: label })
  }
  const hideHover = () => setHoveredLabel(null)

  const renderNavLink = (item: NavItem) => (
    <NavLink
      key={item.path}
      ref={(el) => { itemRefs.current[item.path] = el }}
      to={item.path}
      className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
      end={item.path === '/'}
      onMouseEnter={showHover(item.path, item.label)}
      onMouseLeave={hideHover}
      onClick={hideHover}
    >
      <i className={`bi ${item.icon}`}></i>
      <span className="pt-sidebar-label-text">{item.label}</span>
      {item.badge ? (
        <span className="badge" style={{ background: 'var(--pt-primary)' }}>{item.badge}</span>
      ) : null}
    </NavLink>
  )

  // En mode replié : le survol prime sur le libellé "actif" permanent.
  const floatingLabel = !isExpanded ? (hoveredLabel || activeLabel) : null

  return (
    <>
      {/* Fond semi-transparent (mobile/tablette uniquement) : referme le
          tiroir au clic en dehors. */}
      {mobileOpen && (
        <div className="pt-nav-backdrop" onClick={onCloseMobile} aria-hidden="true"></div>
      )}

      <aside className={`pt-sidebar ${isExpanded ? 'expanded' : 'collapsed'} ${mobileOpen ? 'mobile-open' : ''}`}>
        <div className="pt-sidebar-brand">
          <button
            className="brand-icon"
            style={{ border: 'none', cursor: 'pointer' }}
            onClick={() => navigate('/')}
            title="Aller au Dashboard"
            aria-label="Aller au Dashboard"
          >
            <i className="bi bi-speedometer2"></i>
          </button>
          {isExpanded && (
            <div className="brand-text">
              <h5>Cadence</h5>
              <small>Performance Testing Platform</small>
            </div>
          )}
          <button
            className="pt-sidebar-collapse-btn d-none d-lg-flex"
            onClick={toggleExpanded}
            title={isExpanded ? 'Replier le menu' : 'Déplier le menu'}
            aria-label={isExpanded ? 'Replier le menu' : 'Déplier le menu'}
          >
            <i className={`bi ${isExpanded ? 'bi-chevron-left' : 'bi-chevron-right'}`}></i>
          </button>
          <button
            className="pt-sidebar-collapse-btn d-lg-none"
            onClick={onCloseMobile}
            title="Fermer le menu"
            aria-label="Fermer le menu"
          >
            <i className="bi bi-x-lg"></i>
          </button>
        </div>

        {/* CTA Lancer un Test */}
        <div style={{ padding: '0 12px', marginBottom: '8px' }}>
          <NavLink
            to="/executions"
            onMouseEnter={showHover('/executions-cta', 'Lancer un Test')}
            onMouseLeave={hideHover}
            onClick={hideHover}
            style={({ isActive }) => ({
              display: 'flex',
              alignItems: 'center',
              justifyContent: isExpanded ? 'flex-start' : 'center',
              gap: '10px',
              background: isActive ? 'var(--pt-primary-dark)' : 'var(--pt-primary)',
              color: 'white',
              padding: isExpanded ? '10px 14px' : '10px',
              borderRadius: 'var(--pt-radius-sm)',
              fontWeight: 600,
              fontSize: '13.5px',
              textDecoration: 'none',
              boxShadow: '0 2px 8px rgba(79,70,229,0.35)',
              transition: 'background 0.15s',
            })}
          >
            <i className="bi bi-lightning-charge-fill"></i>
            {isExpanded && <span>Lancer un Test</span>}
          </NavLink>
        </div>

        <nav className="pt-sidebar-nav">
          {isExpanded && <div className="pt-sidebar-section-label">Overview</div>}
          {navOverviewItems.map(renderNavLink)}
          <div className="pt-sidebar-divider"></div>
          {isExpanded && <div className="pt-sidebar-section-label">Account</div>}
          {accountItems.map(renderNavLink)}
        </nav>

        <div className="pt-sidebar-footer">
          <button
            className="nav-link pt-sidebar-logout-link"
            onClick={handleLogout}
            onMouseEnter={showHover('/logout', 'Déconnexion')}
            onMouseLeave={hideHover}
          >
            <i className="bi bi-box-arrow-right"></i>
            <span className="pt-sidebar-label-text">Déconnexion</span>
          </button>

          <div
            className={`user-profile ${!isExpanded ? 'collapsed' : ''}`}
            onMouseEnter={showHover('/user', user?.name || 'Admin User')}
            onMouseLeave={hideHover}
          >
            <div className="user-avatar">{user?.initials || 'AU'}</div>
            {isExpanded && (
              <div className="user-info">
                <h6>{user?.name || 'Admin User'}</h6>
                <small>{user?.role || 'Admin'}</small>
              </div>
            )}
          </div>

          <div className={`mode-toggle ${!isExpanded ? 'collapsed' : ''}`}>
            {isExpanded && (
              <span><i className="bi bi-sun me-2"></i>Mode clair</span>
            )}
            <label
              className="pt-toggle"
              onMouseEnter={showHover('/theme', darkMode ? 'Mode sombre' : 'Mode clair')}
              onMouseLeave={hideHover}
            >
              <input type="checkbox" checked={darkMode} onChange={toggleDarkMode} />
              <span className="toggle-slider"></span>
            </label>
          </div>
        </div>

        {floatingLabel && (
          <div className="pt-sidebar-floating-label" style={{ top: floatingLabel.top }}>
            {floatingLabel.text}
          </div>
        )}
      </aside>
    </>
  )
}

export default Sidebar
