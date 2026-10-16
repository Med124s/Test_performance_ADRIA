import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { notificationsBackendApi } from '../services/api/notificationsBackend'
import {
  noteAllNotificationsRead, noteNotificationsRead, refreshUnread, startUnreadPolling, useUnreadNotifications,
} from '../services/notifications/unreadNotifications'
import { BackendNotificationResponse } from '../types/backendContracts'
import { relativeTime } from '../pages/executions/executionsModel'

// ============================================================
// Cloche du header : seul accès aux notifications depuis la navigation.
// Compteur des non lues (partagé avec la page Notifications et les autres onglets, voir
// services/notifications/unreadNotifications), panneau des 5 dernières, « Tout marquer comme
// lu » et « Voir toutes les notifications ». Marquer lu fait baisser le compteur tout de suite.
// Nouvelle notification : la cloche bouge et le badge grossit, UNE fois par arrivée.
// ============================================================

const PANEL_SIZE = 5

function linkFor(n: BackendNotificationResponse): string {
  if (n.relatedExecutionId) return `/executions/report/${n.relatedExecutionId}`
  if (n.relatedScheduleId) return `/planification/${n.relatedScheduleId}`
  return '/notifications'
}

export default function NotificationBell() {
  const navigate = useNavigate()
  const { count, arrivals } = useUnreadNotifications()
  const unread = count ?? 0
  const [open, setOpen] = useState(false)
  const [items, setItems] = useState<BackendNotificationResponse[] | null>(null)
  const [error, setError] = useState(false)
  const root = useRef<HTMLDivElement | null>(null)
  const button = useRef<HTMLButtonElement | null>(null)

  useEffect(() => startUnreadPolling(), [])

  // Panneau ouvert : liste relue à l'ouverture et à chaque nouvelle notification.
  useEffect(() => {
    if (!open) return
    let alive = true
    setError(false)
    notificationsBackendApi.list({ page: 0, size: PANEL_SIZE })
      .then((r) => { if (alive) setItems(r.content) })
      .catch(() => { if (alive) setError(true) })
    return () => { alive = false }
  }, [open, arrivals])

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { setOpen(false); button.current?.focus() } }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey) }
  }, [open])

  const openItem = (n: BackendNotificationResponse) => {
    setOpen(false)
    if (!n.read) {
      noteNotificationsRead(1)
      notificationsBackendApi.markRead(n.id).catch(() => { void refreshUnread() })
    }
    navigate(linkFor(n))
  }

  const markAll = () => {
    noteAllNotificationsRead()
    setItems((list) => list?.map((n) => ({ ...n, read: true })) ?? list)
    notificationsBackendApi.markAllRead().catch(() => { void refreshUnread() })
  }

  const animate = arrivals > 0
  return (
    <div className="nb-root" ref={root}>
      <button ref={button} type="button" className="topbar-icon" aria-haspopup="dialog" aria-expanded={open}
        aria-label={unread > 0 ? `Notifications : ${unread} non lue${unread > 1 ? 's' : ''}` : 'Notifications'}
        title="Notifications" onClick={() => setOpen((o) => !o)}>
        {/* key = nombre d'arrivées : l'élément est recréé à chaque nouvelle notification, l'animation se joue une fois. */}
        <i key={`bell-${arrivals}`} className={`bi bi-bell nb-bell${animate ? ' ring' : ''}`} style={{ fontSize: '18px' }} aria-hidden="true"></i>
        {unread > 0 && (
          <span key={`badge-${arrivals}`} className={`badge-dot nb-badge${animate ? ' pop' : ''}`} aria-hidden="true">
            <span className="nb-badge-num">{unread > 99 ? '99+' : unread}</span>
          </span>
        )}
      </button>
      <span className="visually-hidden" role="status">
        {animate ? `Nouvelle notification : ${unread} non lue${unread > 1 ? 's' : ''}` : ''}
      </span>
      {open && (
        <div className="nb-panel" role="dialog" aria-label="Dernières notifications">
          <div className="nb-head">
            <strong>Notifications</strong>
            {unread > 0 && <button type="button" className="nb-link" onClick={markAll}>Tout marquer comme lu</button>}
          </div>
          <div className="nb-list">
            {error && <p className="nb-empty">Notifications indisponibles pour le moment.</p>}
            {!error && items == null && <p className="nb-empty">Chargement…</p>}
            {!error && items?.length === 0 && <p className="nb-empty">Aucune notification.</p>}
            {items?.map((n) => (
              <button key={n.id} type="button" className={`nb-item ${n.read ? '' : 'unread'}`} onClick={() => openItem(n)}>
                {!n.read && <span className="nb-dot" aria-label="non lue"></span>}
                <span className="nb-title">{n.title}</span>
                <span className="nb-time">{relativeTime(n.createdAt)}</span>
              </button>
            ))}
          </div>
          <Link to="/notifications" className="nb-all" onClick={() => setOpen(false)}>
            Voir toutes les notifications <i className="bi bi-arrow-right" aria-hidden="true"></i>
          </Link>
        </div>
      )}
    </div>
  )
}
