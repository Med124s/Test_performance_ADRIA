import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { notificationsBackendApi } from '../services/api/notificationsBackend'
import { BackendNotificationResponse, BackendNotificationType } from '../types/backendContracts'
import { ApiError } from '../services/api/httpClient'

// ============================================================
// P1-B — Page RÉELLE (remplace l'ancien ComingSoonPage) : notifications
// PERSISTÉES côté backend, générées UNIQUEMENT depuis de vrais événements
// métier (fin d'Execution, déclenchement/échec de ScheduledExecution — voir
// NotificationServiceImpl et ses appelants). Aucun mock, aucun
// localStorage, aucune notification de démonstration.
// ============================================================

const PAGE_SIZE = 20

const typeIcon: Record<BackendNotificationType, string> = {
  EXECUTION_SUCCESS: 'bi-check-circle-fill',
  EXECUTION_FAILED: 'bi-x-circle-fill',
  EXECUTION_CANCELLED: 'bi-slash-circle-fill',
  SCHEDULE_TRIGGERED: 'bi-calendar-check-fill',
  SCHEDULE_FAILED: 'bi-calendar-x-fill',
}
const typeColorVar: Record<BackendNotificationType, string> = {
  EXECUTION_SUCCESS: 'var(--pt-success)',
  EXECUTION_FAILED: 'var(--pt-danger)',
  EXECUTION_CANCELLED: 'var(--pt-text-muted)',
  SCHEDULE_TRIGGERED: 'var(--pt-primary)',
  SCHEDULE_FAILED: 'var(--pt-danger)',
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0: return err.message
      case 401: return 'Vous devez être connecté (Keycloak) pour consulter vos notifications.'
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

function relatedLink(n: BackendNotificationResponse): string | null {
  if (n.relatedExecutionId) return `/executions/report/${n.relatedExecutionId}`
  if (n.relatedScheduleId) return '/planification'
  return null
}

type Filter = 'all' | 'unread'

function NotificationsPage() {
  const [filter, setFilter] = useState<Filter>('all')
  const [notifications, setNotifications] = useState<BackendNotificationResponse[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const load = (f: Filter) => {
    setLoading(true)
    setError(null)
    notificationsBackendApi.list({ read: f === 'unread' ? false : undefined, page: 0, size: PAGE_SIZE })
      .then((res) => setNotifications(res.content))
      .catch((err) => setError(describeApiError(err, 'Impossible de charger les notifications.')))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load(filter) }, [filter])

  const handleMarkRead = async (id: string) => {
    setBusyId(id)
    try {
      const updated = await notificationsBackendApi.markRead(id)
      setNotifications((prev) => prev?.map((n) => (n.id === id ? updated : n)) ?? prev)
    } catch (err) {
      setError(describeApiError(err, 'Impossible de marquer cette notification comme lue.'))
    } finally {
      setBusyId(null)
    }
  }

  const handleMarkAllRead = async () => {
    try {
      await notificationsBackendApi.markAllRead()
      load(filter)
    } catch (err) {
      setError(describeApiError(err, 'Impossible de marquer toutes les notifications comme lues.'))
    }
  }

  const handleDelete = async (id: string) => {
    setBusyId(id)
    try {
      await notificationsBackendApi.delete(id)
      setNotifications((prev) => prev?.filter((n) => n.id !== id) ?? prev)
    } catch (err) {
      setError(describeApiError(err, 'Impossible de supprimer cette notification.'))
    } finally {
      setBusyId(null)
    }
  }

  const unreadVisible = notifications?.some((n) => !n.read) ?? false

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Notifications</h1>
          <p>Événements réels de vos exécutions et planifications</p>
        </div>
        <TopBar searchPlaceholder="" />
      </div>

      {error && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => load(filter)}>Réessayer</button>
        </div>
      )}

      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <div className="d-flex gap-2">
            <button className={`pt-btn-outline ${filter === 'all' ? 'active' : ''}`} style={{ fontSize: '12.5px' }} onClick={() => setFilter('all')}>Toutes</button>
            <button className={`pt-btn-outline ${filter === 'unread' ? 'active' : ''}`} style={{ fontSize: '12.5px' }} onClick={() => setFilter('unread')}>Non lues</button>
          </div>
          <button className="pt-btn-outline" style={{ fontSize: '12.5px' }} onClick={handleMarkAllRead} disabled={!unreadVisible}>
            <i className="bi bi-check2-all me-1"></i>Tout marquer comme lu
          </button>
        </div>

        {loading ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement des notifications...</p>
          </div>
        ) : !notifications || notifications.length === 0 ? (
          <div className="pt-empty-state">
            <i className="bi bi-bell-slash" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
            <p>{filter === 'unread' ? 'Aucune notification non lue.' : "Aucune notification pour le moment — elles apparaissent ici dès qu'une exécution ou une planification se termine."}</p>
          </div>
        ) : (
          <div>
            {notifications.map((n) => {
              const link = relatedLink(n)
              const content = (
                <div className="d-flex align-items-start gap-3 p-3" style={{ borderBottom: '1px solid var(--pt-border)', background: n.read ? 'transparent' : 'var(--pt-primary-light)' }}>
                  <i className={`bi ${typeIcon[n.type]}`} style={{ fontSize: '18px', color: typeColorVar[n.type], marginTop: '2px' }}></i>
                  <div className="flex-grow-1">
                    <div className="d-flex align-items-center gap-2">
                      <span style={{ fontSize: '13.5px', fontWeight: n.read ? 500 : 700 }}>{n.title}</span>
                      {!n.read && <span className="pt-pill info" style={{ fontSize: '10px' }}>Nouveau</span>}
                    </div>
                    {n.message && <div style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', marginTop: '2px' }}>{n.message}</div>}
                    <div style={{ fontSize: '11.5px', color: 'var(--pt-text-light)', marginTop: '4px' }}>{formatDate(n.createdAt)}</div>
                  </div>
                  <div className="d-flex gap-1" onClick={(e) => e.preventDefault()}>
                    {!n.read && (
                      <button className="topbar-icon" style={{ width: '28px', height: '28px', border: '1px solid var(--pt-border)' }}
                        title="Marquer comme lue" disabled={busyId === n.id} onClick={() => handleMarkRead(n.id)}>
                        <i className="bi bi-check2" style={{ fontSize: '13px' }}></i>
                      </button>
                    )}
                    <button className="topbar-icon" style={{ width: '28px', height: '28px', border: '1px solid var(--pt-border)' }}
                      title="Supprimer" disabled={busyId === n.id} onClick={() => handleDelete(n.id)}>
                      <i className="bi bi-trash" style={{ fontSize: '13px' }}></i>
                    </button>
                  </div>
                </div>
              )
              return link ? (
                <Link key={n.id} to={link} style={{ textDecoration: 'none', color: 'inherit', display: 'block' }}>{content}</Link>
              ) : (
                <div key={n.id}>{content}</div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}

export default NotificationsPage
