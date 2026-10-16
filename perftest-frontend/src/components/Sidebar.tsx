import { FocusEvent, MouseEvent, useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { useAppVersion } from '../utils/appVersion'
import LaunchTestModal from './LaunchTestModal'
import './navigation.css'

// ============================================================
// Barre latérale de LoadPilot, en français :
//   Dashboard (seul)
//   TESTS : Applications, Scénarios, Exécutions (badge : en cours), Planification
//   ANALYSE : Comparer, Rapports (résultats des tests terminés)
//   ADMINISTRATION (Super Administrateur) : Utilisateurs et rôles, Journal d'audit, Paramètres
// « Lancer un test » ouvre le choix du scénario puis le suivi en direct ;
// thème clair / sombre par un bouton icône ; en bas, « LoadPilot v<version> · © <année> » (discret,
// non cliquable). Mode réduit : icônes seules avec infobulle (survol ET focus clavier), élément
// actif marqué d'une barre. Le compte (« Mon compte », déconnexion) et les notifications sont dans
// la barre du haut.
// ============================================================

/** Rafraîchissement du badge « en cours » d'Exécutions (une requête légère). */
const RUNNING_POLL_INTERVAL_MS = 15000

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
  /** Actif aussi pour ces sous-chemins (ex. /executions/report/…) ; jamais pour `except`. */
  match?: (pathname: string) => boolean
  badge?: number
  badgeLabel?: string
}

interface NavSection {
  id: string
  label: string | null
  items: NavItem[]
}

interface FloatingLabel {
  top: number
  text: string
}

const isUnder = (base: string) => (p: string) => p === base || p.startsWith(base + '/')

function Sidebar({ darkMode, toggleDarkMode, mobileOpen, onCloseMobile }: SidebarProps) {
  const { authProvider, rawRoles } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const isSuperAdmin = authProvider === 'keycloak' && rawRoles.includes('ROLE_SUPER_ADMIN')
  const canLaunch = authProvider === 'keycloak' && (isSuperAdmin || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  // Desktop : déplié (icônes + libellés) ou replié (icônes seules), préférence mémorisée.
  // Sur mobile/tablette, le tiroir affiche toujours le menu complet.
  const [isExpanded, setIsExpanded] = useState(() => {
    try { return localStorage.getItem('pt_sidebar_expanded') !== '0' } catch { return true }
  })
  const toggleExpanded = () => {
    setIsExpanded((prev) => {
      const next = !prev
      try { localStorage.setItem('pt_sidebar_expanded', next ? '1' : '0') } catch { /* préférence non mémorisée */ }
      return next
    })
  }

  // Exécutions en cours (en attente, en cours, en pause) : badge sur « Exécutions », masqué à 0.
  const [running, setRunning] = useState(0)
  useEffect(() => {
    let cancelled = false
    const poll = () => {
      executionsBackendApi.getPage({ page: 0, size: 5 })
        .then((r) => { if (!cancelled) setRunning((r.counts.queued ?? 0) + (r.counts.running ?? 0) + (r.counts.paused ?? 0)) })
        .catch(() => { /* badge non critique */ })
    }
    poll()
    const timer = setInterval(poll, RUNNING_POLL_INTERVAL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [location.pathname])

  const sections: NavSection[] = [
    { id: 'home', label: null, items: [{ path: '/', icon: 'bi-grid-1x2-fill', label: 'Dashboard', match: (p) => p === '/' }] },
    {
      id: 'tests', label: 'Tests', items: [
        { path: '/applications', icon: 'bi-globe2', label: 'Applications' },
        { path: '/scenarios', icon: 'bi-collection-play', label: 'Scénarios' },
        {
          path: '/executions', icon: 'bi-play-circle', label: 'Exécutions',
          match: (p) => isUnder('/executions')(p) && !isUnder('/executions/compare')(p),
          badge: running > 0 ? running : undefined, badgeLabel: `${running} exécution${running > 1 ? 's' : ''} en cours`,
        },
        { path: '/planification', icon: 'bi-calendar-week', label: 'Planification' },
      ],
    },
    { id: 'analyse', label: 'Analyse', items: [
      { path: '/executions/compare', icon: 'bi-layout-three-columns', label: 'Comparer' },
      { path: '/rapports', icon: 'bi-file-earmark-bar-graph', label: 'Rapports' },
    ] },
    ...(isSuperAdmin ? [{
      id: 'admin', label: 'Administration', items: [
        { path: '/utilisateurs', icon: 'bi-people', label: 'Utilisateurs et rôles' },
        { path: '/audit-logs', icon: 'bi-shield-check', label: 'Journal d’audit' },
        { path: '/parametres', icon: 'bi-gear', label: 'Paramètres' },
      ],
    }] : []),
  ]
  const allItems = sections.flatMap((s) => s.items)
  const isActive = (item: NavItem) => (item.match ?? isUnder(item.path))(location.pathname)

  // Infobulle flottante (mode replié) : au survol et au focus clavier ; libellé de la page active sinon.
  const itemRefs = useRef<Record<string, HTMLElement | null>>({})
  const [hoveredLabel, setHoveredLabel] = useState<FloatingLabel | null>(null)
  const [activeLabel, setActiveLabel] = useState<FloatingLabel | null>(null)
  const labelAt = (el: HTMLElement, text: string): FloatingLabel => {
    const rect = el.getBoundingClientRect()
    return { top: rect.top + rect.height / 2, text }
  }
  useEffect(() => {
    const id = requestAnimationFrame(() => {
      if (isExpanded) { setActiveLabel(null); return }
      const active = allItems.find(isActive)
      const el = active ? itemRefs.current[active.path] : null
      setActiveLabel(active && el ? labelAt(el, active.label) : null)
    })
    return () => cancelAnimationFrame(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname, isExpanded])
  const showLabel = (text: string) => (e: MouseEvent<HTMLElement> | FocusEvent<HTMLElement>) => {
    if (!isExpanded) setHoveredLabel(labelAt(e.currentTarget, text))
  }
  const hideLabel = () => setHoveredLabel(null)
  const floatingLabel = !isExpanded ? (hoveredLabel || activeLabel) : null

  // Ferme le tiroir mobile après une navigation.
  useEffect(() => {
    onCloseMobile()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname])

  const [launchOpen, setLaunchOpen] = useState(false)

  // Version lue sur le serveur (seule définition : backend/pom.xml).
  const version = useAppVersion()
  const year = new Date().getFullYear()

  const renderNavLink = (item: NavItem) => {
    const active = isActive(item)
    return (
      <Link
        key={item.path}
        ref={(el) => { itemRefs.current[item.path] = el }}
        to={item.path}
        className={`nav-link ${active ? 'active' : ''}`}
        aria-current={active ? 'page' : undefined}
        aria-label={item.badge ? `${item.label} (${item.badgeLabel})` : item.label}
        onMouseEnter={showLabel(item.label)}
        onMouseLeave={hideLabel}
        onFocus={showLabel(item.label)}
        onBlur={hideLabel}
        onClick={hideLabel}
      >
        <i className={`bi ${item.icon}`} aria-hidden="true"></i>
        <span className="pt-sidebar-label-text">{item.label}</span>
        {item.badge ? <span className="badge sb-badge" title={item.badgeLabel} aria-hidden="true">{item.badge}</span> : null}
      </Link>
    )
  }

  const themeLabel = darkMode ? 'Passer en mode clair' : 'Passer en mode sombre'

  return (
    <>
      {/* Fond semi-transparent (mobile/tablette) : referme le tiroir au clic en dehors. */}
      {mobileOpen && <div className="pt-nav-backdrop" onClick={onCloseMobile} aria-hidden="true"></div>}

      <aside className={`pt-sidebar ${isExpanded ? 'expanded' : 'collapsed'} ${mobileOpen ? 'mobile-open' : ''}`} aria-label="Navigation principale">
        <div className="pt-sidebar-brand">
          <button className="brand-icon" style={{ border: 'none', cursor: 'pointer' }} onClick={() => navigate('/')}
            title="LoadPilot — aller au Dashboard" aria-label="LoadPilot — aller au Dashboard">
            <i className="bi bi-speedometer2" aria-hidden="true"></i>
          </button>
          <div className="brand-text sb-when-expanded">
            <h5>LoadPilot</h5>
            <small>Tests de performance</small>
          </div>
          <button className="pt-sidebar-collapse-btn d-none d-lg-flex" onClick={toggleExpanded}
            title={isExpanded ? 'Réduire le menu' : 'Agrandir le menu'} aria-label={isExpanded ? 'Réduire le menu' : 'Agrandir le menu'}
            aria-expanded={isExpanded}>
            <i className={`bi ${isExpanded ? 'bi-chevron-left' : 'bi-chevron-right'}`} aria-hidden="true"></i>
          </button>
          <button className="pt-sidebar-collapse-btn d-lg-none" onClick={onCloseMobile} title="Fermer le menu" aria-label="Fermer le menu">
            <i className="bi bi-x-lg" aria-hidden="true"></i>
          </button>
        </div>

        {/* Lancer un test : choix du scénario puis suivi en direct (rôles autorisés à lancer). */}
        {canLaunch && (
          <div className="sb-cta-wrap">
            <button type="button" className="sb-cta" onClick={() => { hideLabel(); setLaunchOpen(true) }}
              onMouseEnter={showLabel('Lancer un test')} onMouseLeave={hideLabel} onFocus={showLabel('Lancer un test')} onBlur={hideLabel}
              aria-label="Lancer un test" aria-haspopup="dialog">
              <i className="bi bi-lightning-charge-fill" aria-hidden="true"></i>
              <span className="pt-sidebar-label-text">Lancer un test</span>
            </button>
          </div>
        )}

        <nav className="pt-sidebar-nav">
          {sections.map((section, i) => (
            <div key={section.id} role="group" aria-label={section.label ?? undefined}>
              {i > 1 && <div className="pt-sidebar-divider sb-when-collapsed" aria-hidden="true"></div>}
              {section.label && <div className="pt-sidebar-section-label sb-when-expanded" aria-hidden="true">{section.label}</div>}
              {section.items.map(renderNavLink)}
            </div>
          ))}
        </nav>

        <div className="pt-sidebar-footer sb-footer">
          <button type="button" className="sb-theme-btn" onClick={toggleDarkMode} title={themeLabel} aria-label={themeLabel}
            onMouseEnter={showLabel(themeLabel)} onMouseLeave={hideLabel} onFocus={showLabel(themeLabel)} onBlur={hideLabel}>
            <i className={`bi ${darkMode ? 'bi-sun' : 'bi-moon-stars'}`} aria-hidden="true"></i>
          </button>
          {/* Nom · version · année (informatif, non cliquable). Replié : version courte seule. */}
          {version && (
            <p className="sb-version">
              <span className="sb-when-expanded">LoadPilot v{version} · © {year}</span>
              <span className="sb-when-collapsed" title={`LoadPilot v${version}`}>v{version}</span>
            </p>
          )}
        </div>

        {floatingLabel && (
          <div className="pt-sidebar-floating-label" style={{ top: floatingLabel.top }} role="tooltip">
            {floatingLabel.text}
          </div>
        )}
      </aside>

      {launchOpen && <LaunchTestModal onClose={() => setLaunchOpen(false)} />}
    </>
  )
}

export default Sidebar
