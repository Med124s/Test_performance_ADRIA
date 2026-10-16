import { formatDate } from '../utils/dateFormat'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import TopBar from '../components/TopBar'
import Pagination from '../components/Pagination'
import { notificationsBackendApi } from '../services/api/notificationsBackend'
import {
  noteAllNotificationsRead, noteNotificationsRead, onNotificationsChanged, refreshUnread, useUnreadNotifications,
} from '../services/notifications/unreadNotifications'
import { BackendNotificationResponse, BackendNotificationType, BackendPagedResponse } from '../types/backendContracts'
import { ApiError } from '../services/api/httpClient'

// ============================================================
// Page « Toutes mes notifications » : notifications PERSISTÉES côté backend, générées
// uniquement depuis de vrais événements (fin d'exécution, planification, régression).
// Pagination côté serveur (20 par page, 20 / 50 / 100) ; filtres lues / non lues et type,
// combinés à la pagination et gardés dans l'URL. Marquer lu (bouton, ouverture, « Tout
// marquer comme lu », suppression d'une non lue) fait baisser la cloche tout de suite.
// ============================================================

export const PAGE_SIZES = [20, 50, 100]
const DEFAULT_SIZE = 20

/** Types regroupés comme l'utilisateur les pense (un échec de lancement planifié est un échec). */
export const TYPE_FILTERS: { key: string; label: string; types: BackendNotificationType[] }[] = [
  { key: 'reussies', label: 'Exécutions réussies', types: ['EXECUTION_SUCCESS'] },
  { key: 'seuils', label: 'Seuils dépassés', types: ['EXECUTION_THRESHOLDS_EXCEEDED'] },
  { key: 'echecs', label: 'Échecs', types: ['EXECUTION_FAILED', 'SCHEDULE_FAILED'] },
  { key: 'annulees', label: 'Annulées', types: ['EXECUTION_CANCELLED'] },
  { key: 'regressions', label: 'Régressions', types: ['SCHEDULE_REGRESSION'] },
  { key: 'planifications', label: 'Planifications lancées', types: ['SCHEDULE_TRIGGERED'] },
]
const READ_FILTERS = [
  { key: 'toutes', label: 'Toutes' },
  { key: 'non-lues', label: 'Non lues' },
  { key: 'lues', label: 'Lues' },
] as const
type ReadFilter = typeof READ_FILTERS[number]['key']

const typeIcon: Record<BackendNotificationType, string> = {
  EXECUTION_SUCCESS: 'bi-check-circle-fill',
  EXECUTION_THRESHOLDS_EXCEEDED: 'bi-exclamation-triangle-fill',
  EXECUTION_FAILED: 'bi-x-circle-fill',
  EXECUTION_CANCELLED: 'bi-slash-circle-fill',
  SCHEDULE_TRIGGERED: 'bi-calendar-check-fill',
  SCHEDULE_FAILED: 'bi-calendar-x-fill',
  SCHEDULE_REGRESSION: 'bi-graph-up-arrow',
}
const typeColorVar: Record<BackendNotificationType, string> = {
  EXECUTION_SUCCESS: 'var(--pt-success)',
  EXECUTION_THRESHOLDS_EXCEEDED: 'var(--pt-warning)',
  EXECUTION_FAILED: 'var(--pt-danger)',
  EXECUTION_CANCELLED: 'var(--pt-text-muted)',
  SCHEDULE_TRIGGERED: 'var(--pt-primary)',
  SCHEDULE_FAILED: 'var(--pt-danger)',
  SCHEDULE_REGRESSION: 'var(--pt-warning)',
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0: return err.message
      case 401: return 'Vous devez être connecté pour consulter vos notifications.'
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function relatedLink(n: BackendNotificationResponse): string | null {
  if (n.relatedExecutionId) return `/executions/report/${n.relatedExecutionId}`
  if (n.relatedScheduleId) return '/planification'
  return null
}

/** État de la liste lu dans l'URL (valeurs inconnues : valeurs par défaut). */
export function readListParams(params: URLSearchParams) {
  const read = (READ_FILTERS.find((f) => f.key === params.get('lu'))?.key ?? 'toutes') as ReadFilter
  const type = TYPE_FILTERS.find((f) => f.key === params.get('type'))?.key ?? ''
  const sizeParam = Number(params.get('taille'))
  const size = PAGE_SIZES.includes(sizeParam) ? sizeParam : DEFAULT_SIZE
  const pageParam = Math.floor(Number(params.get('page')))
  const page = Number.isFinite(pageParam) && pageParam >= 1 ? pageParam : 1
  return { read, type, size, page }
}

function NotificationsPage() {
  const [params, setParams] = useSearchParams()
  const { read, type, size, page } = readListParams(params)
  const { count: unreadTotal } = useUnreadNotifications()
  const [data, setData] = useState<BackendPagedResponse<BackendNotificationResponse> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const requestId = useRef(0)

  /** Change un ou plusieurs réglages de la liste (URL) ; un changement de filtre revient en page 1. */
  const update = (changes: { read?: ReadFilter; type?: string; size?: number; page?: number }) => {
    const next = { read, type, size, page: 1, ...changes }
    const p = new URLSearchParams()
    if (next.read !== 'toutes') p.set('lu', next.read)
    if (next.type) p.set('type', next.type)
    if (next.size !== DEFAULT_SIZE) p.set('taille', String(next.size))
    if (next.page > 1) p.set('page', String(next.page))
    setParams(p)
  }

  const load = useCallback((quiet = false) => {
    const id = ++requestId.current
    if (!quiet) setLoading(true)
    setError(null)
    notificationsBackendApi.list({
      read: read === 'non-lues' ? false : read === 'lues' ? true : undefined,
      types: TYPE_FILTERS.find((f) => f.key === type)?.types,
      page: page - 1,
      size,
    })
      .then((res) => {
        if (id !== requestId.current) return
        // Page au-delà de la dernière (ex. après suppressions) : on va sur la dernière page.
        if (res.content.length === 0 && res.totalPages > 0 && page > res.totalPages) {
          setParams((p) => { const n = new URLSearchParams(p); n.set('page', String(res.totalPages)); return n }, { replace: true })
          return
        }
        setData(res)
      })
      .catch((err) => { if (id === requestId.current) setError(describeApiError(err, 'Impossible de charger les notifications.')) })
      .finally(() => { if (id === requestId.current) setLoading(false) })
  }, [read, type, size, page, setParams])

  useEffect(() => { load() }, [load])
  // Nouvelle notification ou changement fait dans un autre onglet : liste relue sans tout effacer.
  useEffect(() => onNotificationsChanged(() => load(true)), [load])

  const setRead = (id: string, updated: Partial<BackendNotificationResponse>) =>
    setData((prev) => prev && { ...prev, content: prev.content.map((n) => (n.id === id ? { ...n, ...updated } : n)) })

  const handleMarkRead = async (n: BackendNotificationResponse) => {
    if (n.read) return
    setBusyId(n.id)
    noteNotificationsRead(1)
    setRead(n.id, { read: true })
    try {
      const updated = await notificationsBackendApi.markRead(n.id)
      setRead(n.id, updated)
    } catch (err) {
      setRead(n.id, { read: false })
      void refreshUnread()
      setError(describeApiError(err, 'Impossible de marquer cette notification comme lue.'))
    } finally {
      setBusyId(null)
    }
  }

  /** Ouvrir une notification non lue la marque lue (sans attendre le serveur pour la cloche). */
  const openNotification = (n: BackendNotificationResponse) => {
    if (n.read) return
    noteNotificationsRead(1)
    notificationsBackendApi.markRead(n.id).catch(() => { void refreshUnread() })
  }

  const handleMarkAllRead = async () => {
    noteAllNotificationsRead()
    setData((prev) => prev && { ...prev, content: prev.content.map((n) => ({ ...n, read: true })) })
    try {
      await notificationsBackendApi.markAllRead()
      load(true)
    } catch (err) {
      void refreshUnread()
      load(true)
      setError(describeApiError(err, 'Impossible de marquer toutes les notifications comme lues.'))
    }
  }

  const handleDelete = async (n: BackendNotificationResponse) => {
    setBusyId(n.id)
    try {
      await notificationsBackendApi.delete(n.id)
      if (!n.read) noteNotificationsRead(1)
      load(true)
    } catch (err) {
      setError(describeApiError(err, 'Impossible de supprimer cette notification.'))
    } finally {
      setBusyId(null)
    }
  }

  const notifications = data?.content ?? []
  const total = data?.totalElements ?? 0
  const start = total === 0 ? 0 : (page - 1) * size + 1
  const end = Math.min(total, (page - 1) * size + notifications.length)
  const filtered = read !== 'toutes' || type !== ''

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
        <div className="pt-alert-banner danger mb-3" role="alert">
          <i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>
          {error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => load()}>Réessayer</button>
        </div>
      )}

      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <div className="d-flex gap-2 flex-wrap align-items-center">
            <div className="d-flex gap-2" role="group" aria-label="Statut de lecture">
              {READ_FILTERS.map((f) => (
                <button key={f.key} type="button" className={`pt-btn-outline ${read === f.key ? 'active' : ''}`} style={{ fontSize: '12.5px' }}
                  aria-pressed={read === f.key} onClick={() => update({ read: f.key })}>{f.label}</button>
              ))}
            </div>
            <select className="pt-form-control" style={{ width: 'auto', fontSize: '12.5px' }} aria-label="Type de notification"
              value={type} onChange={(e) => update({ type: e.target.value })}>
              <option value="">Tous les types</option>
              {TYPE_FILTERS.map((f) => <option key={f.key} value={f.key}>{f.label}</option>)}
            </select>
          </div>
          <button className="pt-btn-outline" style={{ fontSize: '12.5px' }} onClick={handleMarkAllRead} disabled={!unreadTotal}>
            <i className="bi bi-check2-all me-1" aria-hidden="true"></i>Tout marquer comme lu
          </button>
        </div>

        {loading && !data ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }} aria-hidden="true"></i>
            <p>Chargement des notifications...</p>
          </div>
        ) : notifications.length === 0 ? (
          <div className="pt-empty-state">
            <i className="bi bi-bell-slash" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }} aria-hidden="true"></i>
            <p>{filtered ? 'Aucune notification ne correspond à ces filtres.'
              : "Aucune notification pour le moment — elles apparaissent ici dès qu'une exécution ou une planification se termine."}</p>
          </div>
        ) : (
          <ul className="list-unstyled mb-0" aria-label="Notifications" style={{ opacity: loading ? 0.6 : 1 }}>
            {notifications.map((n) => {
              const link = relatedLink(n)
              const content = (
                <div className="d-flex align-items-start gap-3 p-3" style={{ borderBottom: '1px solid var(--pt-border)', background: n.read ? 'transparent' : 'var(--pt-primary-light)' }}>
                  <i className={`bi ${typeIcon[n.type] ?? 'bi-bell-fill'}`} style={{ fontSize: '18px', color: typeColorVar[n.type] ?? 'var(--pt-text-muted)', marginTop: '2px' }} aria-hidden="true"></i>
                  {/* minWidth 0 + coupure des mots longs : les boutons restent dans la carte, même sur téléphone. */}
                  <div className="flex-grow-1" style={{ minWidth: 0, overflowWrap: 'anywhere' }}>
                    <div className="d-flex align-items-center gap-2 flex-wrap">
                      <span style={{ fontSize: '13.5px', fontWeight: n.read ? 500 : 700 }}>{n.title}</span>
                      {!n.read && <span className="pt-pill info" style={{ fontSize: '10px' }}>Nouveau</span>}
                    </div>
                    {n.message && <div style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', marginTop: '2px' }}>{n.message}</div>}
                    <div style={{ fontSize: '11.5px', color: 'var(--pt-text-light)', marginTop: '4px' }}>{formatDate(n.createdAt)}</div>
                  </div>
                  <div className="d-flex gap-1 flex-shrink-0" onClick={(e) => { e.preventDefault(); e.stopPropagation() }}>
                    {!n.read && (
                      <button type="button" className="topbar-icon" style={{ width: '28px', height: '28px', border: '1px solid var(--pt-border)' }}
                        title="Marquer comme lue" aria-label={`Marquer comme lue : ${n.title}`} disabled={busyId === n.id} onClick={() => handleMarkRead(n)}>
                        <i className="bi bi-check2" style={{ fontSize: '13px' }} aria-hidden="true"></i>
                      </button>
                    )}
                    <button type="button" className="topbar-icon" style={{ width: '28px', height: '28px', border: '1px solid var(--pt-border)' }}
                      title="Supprimer" aria-label={`Supprimer : ${n.title}`} disabled={busyId === n.id} onClick={() => handleDelete(n)}>
                      <i className="bi bi-trash" style={{ fontSize: '13px' }} aria-hidden="true"></i>
                    </button>
                  </div>
                </div>
              )
              return (
                <li key={n.id}>
                  {link ? (
                    <Link to={link} style={{ textDecoration: 'none', color: 'inherit', display: 'block' }} onClick={() => openNotification(n)}>{content}</Link>
                  ) : (
                    <div role="button" tabIndex={0} style={{ cursor: n.read ? 'default' : 'pointer' }} onClick={() => openNotification(n)}
                      onKeyDown={(e) => { if (e.key === 'Enter') openNotification(n) }}>{content}</div>
                  )}
                </li>
              )
            })}
          </ul>
        )}

        {data && total > 0 && (
          <Pagination page={page} totalPages={Math.max(1, data.totalPages)} onPageChange={(p) => update({ page: p })}
            startIndex={start} endIndex={end} totalItems={total} itemLabel={`notification${total > 1 ? 's' : ''}`}
            pageSize={size} pageSizeOptions={PAGE_SIZES} onPageSizeChange={(s) => update({ size: s })} />
        )}
      </div>
    </div>
  )
}

export default NotificationsPage
