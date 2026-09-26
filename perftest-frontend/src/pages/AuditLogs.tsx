import { useEffect, useState } from 'react'
import TopBar from '../components/TopBar'
import Pagination from '../components/Pagination'
import {
  BackendAuditLogResponse,
  BackendAuditStatsResponse,
  BackendAuditAction,
  BackendAuditModule,
  BackendAuditResult,
} from '../types/backendContracts'
import { auditBackendApi, AuditLogFilters } from '../services/api/auditBackend'
import { ApiError } from '../services/api/httpClient'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

// ============================================================
// Phase 24 — /audit-logs n'était jusqu'ici qu'un placeholder
// ("Bientôt disponible", voir ComingSoonPage) : aucune fonctionnalité ni
// service JSON Server n'existait pour ce module avant cette phase — c'est
// donc une intégration neuve, pas une redirection d'un service partagé.
//
// Contrat backend réel (voir services/api/auditBackend.ts) : `GET
// /api/audit-logs` est paginé et filtré CÔTÉ SERVEUR (userId/username
// exacts, action/module/result énumérés, from/to sur `date`) — la
// pagination et les filtres ci-dessous sont donc de vrais appels réseau à
// chaque changement, jamais un filtrage local sur une liste déjà chargée.
// `/stats` et `/export` sont également des endpoints réels (voir
// AuditLogController) : les statistiques et le CSV exporté proviennent
// entièrement du backend, jamais recalculés ni reconstruits ici.
//
// Accès réservé à SUPER_ADMIN et PERFORMANCE_ENGINEER (VIEWER exclu, 403 —
// voir @PreAuthorize sur AuditLogController) : la page vérifie ce rôle
// AVANT même d'appeler l'API pour afficher un message honnête plutôt
// qu'une erreur brute, mais le backend reste seul juge réel (défense en
// profondeur, même politique que toutes les pages migrées précédentes).
// ============================================================

const ACTIONS: BackendAuditAction[] = ['CREATE', 'READ', 'UPDATE', 'DELETE', 'TEST', 'EXECUTE', 'RETRY', 'CANCEL', 'LOGIN', 'LOGOUT']
const MODULES: BackendAuditModule[] = ['AUTH', 'USER', 'APPLICATION', 'SCENARIO', 'STEP', 'EXECUTION', 'METRIC', 'DASHBOARD', 'ADMIN', 'SCHEDULING']
const PAGE_SIZE = 20

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 401:
        return 'Vous devez être connecté (Keycloak) pour consulter les logs d\'audit.'
      case 403:
        return "Action refusée : les logs d'audit sont réservés aux rôles Administrateur et Ingénieur Performance."
      case 404:
        return 'Entrée d\'audit introuvable.'
      default:
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function formatDate(iso: string) {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

const actionPillClass: Record<BackendAuditAction, string> = {
  CREATE: 'success', READ: 'info', UPDATE: 'warning', DELETE: 'danger', TEST: 'info',
  EXECUTE: 'success', RETRY: 'warning', CANCEL: 'neutral', LOGIN: 'success', LOGOUT: 'neutral',
  // P1-B — cycle de vie d'une ScheduledExecution (module SCHEDULING).
  ENABLE: 'success', DISABLE: 'neutral', TRIGGER: 'info',
  // P1-C — traçabilité de la consultation de l'historique/des rapports.
  VIEW_HISTORY: 'neutral', EXPORT_REPORT: 'info', EXPORT_CSV: 'info',
}

function AuditLogs() {
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const isKeycloak = authProvider === 'keycloak'
  // GET /api/audit-logs est reserve a SUPER_ADMIN et PERFORMANCE_ENGINEER
  // au niveau de la classe du controleur (VIEWER exclu, 403 reel).
  const canView = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  const [filters, setFilters] = useState<AuditLogFilters>({})
  const [draftFilters, setDraftFilters] = useState<{ userId: string; username: string; action: string; module: string; result: string; from: string; to: string }>({
    userId: '', username: '', action: '', module: '', result: '', from: '', to: '',
  })
  const [pageIndex, setPageIndex] = useState(0) // 0-index, comme le backend

  const [data, setData] = useState<BackendPagedResponseState>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [stats, setStats] = useState<BackendAuditStatsResponse | null>(null)
  const [statsError, setStatsError] = useState<string | null>(null)

  const [selectedLog, setSelectedLog] = useState<BackendAuditLogResponse | null>(null)
  const [exporting, setExporting] = useState(false)

  type BackendPagedResponseState = { content: BackendAuditLogResponse[]; page: number; size: number; totalElements: number; totalPages: number } | null

  const loadLogs = () => {
    if (!canView) return
    setLoading(true)
    setError(null)
    auditBackendApi.list(filters, pageIndex, PAGE_SIZE)
      .then(setData)
      .catch((err) => setError(describeApiError(err, 'Impossible de charger les logs d\'audit.')))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    if (!canView) return
    loadLogs()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canView, filters, pageIndex])

  useEffect(() => {
    if (!canView) return
    auditBackendApi.stats()
      .then(setStats)
      .catch((err) => setStatsError(describeApiError(err, 'Impossible de charger les statistiques.')))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canView])

  const applyFilters = () => {
    const next: AuditLogFilters = {}
    if (draftFilters.userId.trim()) next.userId = draftFilters.userId.trim()
    if (draftFilters.username.trim()) next.username = draftFilters.username.trim()
    if (draftFilters.action) next.action = draftFilters.action as BackendAuditAction
    if (draftFilters.module) next.module = draftFilters.module as BackendAuditModule
    if (draftFilters.result) next.result = draftFilters.result as BackendAuditResult
    if (draftFilters.from) next.from = new Date(`${draftFilters.from}T00:00:00Z`).toISOString()
    if (draftFilters.to) next.to = new Date(`${draftFilters.to}T23:59:59Z`).toISOString()
    setPageIndex(0)
    setFilters(next)
  }

  const resetFilters = () => {
    setDraftFilters({ userId: '', username: '', action: '', module: '', result: '', from: '', to: '' })
    setPageIndex(0)
    setFilters({})
  }

  const handleExport = async () => {
    if (!canView || exporting) return
    setExporting(true)
    try {
      const blob = await auditBackendApi.exportCsv(filters)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'audit-logs.csv'
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      showToast('Export CSV généré avec succès.', 'success')
    } catch (err) {
      showToast(describeApiError(err, "Erreur lors de l'export."), 'danger')
    } finally {
      setExporting(false)
    }
  }

  if (!canView) {
    return (
      <div className="pt-content">
        <div className="pt-page-header">
          <div className="page-title">
            <h1>Audit Logs</h1>
            <p>Traquez toutes les actions effectuées sur la plateforme</p>
          </div>
        </div>
        <div className="pt-card">
          <div className="pt-empty-state" style={{ padding: '4rem 1.5rem' }}>
            <i className="bi bi-shield-lock" style={{ fontSize: '40px', color: 'var(--pt-text-light)' }}></i>
            <p style={{ fontSize: '15px', fontWeight: 600, color: 'var(--pt-text)' }}>Accès restreint</p>
            <p>Les logs d'audit sont réservés aux rôles Administrateur et Ingénieur Performance (réellement appliqué côté backend).</p>
          </div>
        </div>
      </div>
    )
  }

  const totalItems = data?.totalElements ?? 0
  const totalPages = Math.max(1, data?.totalPages ?? 1)
  const startIndex = totalItems === 0 ? 0 : pageIndex * PAGE_SIZE + 1
  const endIndex = Math.min((pageIndex + 1) * PAGE_SIZE, totalItems)

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Audit Logs</h1>
          <p>Traquez toutes les actions effectuées sur la plateforme (Spring Boot)</p>
        </div>
        <div className="d-flex align-items-center gap-2">
          <button className="pt-btn-outline" onClick={handleExport} disabled={exporting}>
            <i className={`bi ${exporting ? 'bi-arrow-repeat pt-spin' : 'bi-download'}`}></i>
            {exporting ? 'Export...' : 'Exporter CSV'}
          </button>
          <TopBar searchPlaceholder="" />
        </div>
      </div>

      {/* Statistiques réelles (GET /api/audit-logs/stats) */}
      {statsError ? (
        <div className="pt-alert-banner danger mb-3"><i className="bi bi-exclamation-triangle-fill"></i>{statsError}</div>
      ) : (
        <div className="row g-3 mb-4">
          <div className="col-12 col-sm-4">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div><div className="stat-label">Actions totales</div><div className="stat-value">{stats?.totalActions ?? '—'}</div></div>
                <div className="stat-icon blue"><i className="bi bi-list-check"></i></div>
              </div>
            </div>
          </div>
          <div className="col-12 col-sm-4">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div><div className="stat-label">Réussies</div><div className="stat-value">{stats?.successfulActions ?? '—'}</div></div>
                <div className="stat-icon green"><i className="bi bi-check-circle"></i></div>
              </div>
            </div>
          </div>
          <div className="col-12 col-sm-4">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div><div className="stat-label">Échouées</div><div className="stat-value">{stats?.failedActions ?? '—'}</div></div>
                <div className="stat-icon red"><i className="bi bi-x-circle"></i></div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Filtres réels (query params backend) */}
      <div className="pt-card mb-4" style={{ padding: '1rem 1.25rem' }}>
        <div className="row g-3 align-items-end">
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>
              Utilisateur (username exact)
            </label>
            <input className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draftFilters.username} onChange={(e) => setDraftFilters((p) => ({ ...p, username: e.target.value }))} placeholder="ex: admin-test" />
          </div>
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>
              User ID Keycloak (exact)
            </label>
            <input className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draftFilters.userId} onChange={(e) => setDraftFilters((p) => ({ ...p, userId: e.target.value }))} placeholder="sub Keycloak" />
          </div>
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Action</label>
            <select className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draftFilters.action} onChange={(e) => setDraftFilters((p) => ({ ...p, action: e.target.value }))}>
              <option value="">Toutes</option>
              {ACTIONS.map((a) => <option key={a} value={a}>{a}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Module</label>
            <select className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draftFilters.module} onChange={(e) => setDraftFilters((p) => ({ ...p, module: e.target.value }))}>
              <option value="">Tous</option>
              {MODULES.map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Résultat</label>
            <select className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draftFilters.result} onChange={(e) => setDraftFilters((p) => ({ ...p, result: e.target.value }))}>
              <option value="">Tous</option>
              <option value="SUCCESS">SUCCESS</option>
              <option value="FAILURE">FAILURE</option>
            </select>
          </div>
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Du</label>
            <input type="date" className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draftFilters.from} onChange={(e) => setDraftFilters((p) => ({ ...p, from: e.target.value }))} />
          </div>
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Au</label>
            <input type="date" className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draftFilters.to} onChange={(e) => setDraftFilters((p) => ({ ...p, to: e.target.value }))} />
          </div>
          <div className="col-12 col-lg-2 d-flex gap-2">
            <button className="pt-btn-primary" style={{ fontSize: '12.5px' }} onClick={applyFilters}><i className="bi bi-search me-1"></i>Rechercher</button>
            <button className="pt-btn-outline" style={{ fontSize: '12.5px' }} onClick={resetFilters}>Réinitialiser</button>
          </div>
        </div>
      </div>

      {error && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={loadLogs}>Réessayer</button>
        </div>
      )}

      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Journal d'audit ({totalItems})</h6>
        </div>

        {loading ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement des logs...</p>
          </div>
        ) : !data || data.content.length === 0 ? (
          <div className="pt-empty-state">
            <i className="bi bi-inbox" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
            <p>Aucune donnée disponible pour ces filtres.</p>
          </div>
        ) : (
          <div className="pt-table-wrapper">
            <table className="pt-table">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Utilisateur</th>
                  <th>Action</th>
                  <th>Module</th>
                  <th>Résultat</th>
                  <th>IP</th>
                  <th>Description</th>
                  <th style={{ textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((log) => (
                  <tr key={log.id}>
                    <td style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{formatDate(log.date)}</td>
                    <td style={{ fontSize: '13px', fontWeight: 500 }}>{log.username ?? (log.userId ? log.userId.slice(0, 8) : 'Système')}</td>
                    <td><span className={`pt-pill ${actionPillClass[log.action]}`} style={{ fontSize: '11px' }}>{log.action}</span></td>
                    <td><span className="pt-pill neutral" style={{ fontSize: '11px' }}>{log.module}</span></td>
                    <td><span className={`pt-pill ${log.result === 'SUCCESS' ? 'success' : 'danger'}`} style={{ fontSize: '11px' }}>{log.result}</span></td>
                    <td style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>{log.ip ?? '—'}</td>
                    <td style={{ fontSize: '12.5px', maxWidth: '260px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={log.description ?? undefined}>{log.description ?? '—'}</td>
                    <td style={{ textAlign: 'right' }}>
                      <button className="topbar-icon" style={{ width: '30px', height: '30px', border: '1px solid var(--pt-border)' }} title="Voir détail" onClick={() => setSelectedLog(log)}>
                        <i className="bi bi-eye" style={{ fontSize: '13px' }}></i>
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <Pagination
          page={pageIndex + 1}
          totalPages={totalPages}
          onPageChange={(p) => setPageIndex(p - 1)}
          startIndex={startIndex}
          endIndex={endIndex}
          totalItems={totalItems}
          itemLabel="entrées"
        />
      </div>

      {/* Répartitions réelles par module/action (stats backend) */}
      {stats && (
        <div className="row g-3 mt-1">
          <div className="col-12 col-lg-6">
            <div className="pt-card">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '0.75rem' }}>Répartition par module</h6>
              {Object.entries(stats.actionsByModule).length === 0 ? (
                <p className="text-muted mb-0" style={{ fontSize: '13px' }}>Aucune donnée.</p>
              ) : Object.entries(stats.actionsByModule).sort((a, b) => b[1] - a[1]).map(([mod, count]) => (
                <div key={mod} className="d-flex justify-content-between py-1" style={{ fontSize: '13px' }}>
                  <span>{mod}</span><strong>{count}</strong>
                </div>
              ))}
            </div>
          </div>
          <div className="col-12 col-lg-6">
            <div className="pt-card">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '0.75rem' }}>Répartition par action</h6>
              {Object.entries(stats.actionsByAction).length === 0 ? (
                <p className="text-muted mb-0" style={{ fontSize: '13px' }}>Aucune donnée.</p>
              ) : Object.entries(stats.actionsByAction).sort((a, b) => b[1] - a[1]).map(([act, count]) => (
                <div key={act} className="d-flex justify-content-between py-1" style={{ fontSize: '13px' }}>
                  <span>{act}</span><strong>{count}</strong>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Modale Détail — réutilise la ligne déjà chargée (identique au DTO
          détail réel GET /api/audit-logs/{id}), sans appel réseau redondant. */}
      {selectedLog && (
        <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1050 }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content" style={{ borderRadius: 'var(--pt-radius)', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}>
              <div className="modal-header">
                <h5 className="modal-title" style={{ fontSize: '16px', fontWeight: 600 }}>Détail de l'entrée d'audit</h5>
                <button type="button" className="btn-close" onClick={() => setSelectedLog(null)}></button>
              </div>
              <div className="modal-body p-4">
                <div className="row g-3">
                  <div className="col-12 col-md-6"><div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Date</div><div style={{ fontSize: '13.5px', fontWeight: 600 }}>{formatDate(selectedLog.date)}</div></div>
                  <div className="col-12 col-md-6"><div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Utilisateur</div><div style={{ fontSize: '13.5px', fontWeight: 600 }}>{selectedLog.username ?? '—'}</div></div>
                  <div className="col-12 col-md-6"><div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>User ID (Keycloak)</div><div style={{ fontSize: '12.5px', fontFamily: 'monospace' }}>{selectedLog.userId ?? '—'}</div></div>
                  <div className="col-12 col-md-6"><div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Adresse IP</div><div style={{ fontSize: '13.5px', fontWeight: 600 }}>{selectedLog.ip ?? '—'}</div></div>
                  <div className="col-6"><div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Action</div><span className={`pt-pill ${actionPillClass[selectedLog.action]}`}>{selectedLog.action}</span></div>
                  <div className="col-6"><div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Module</div><span className="pt-pill neutral">{selectedLog.module}</span></div>
                  <div className="col-6"><div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Résultat</div><span className={`pt-pill ${selectedLog.result === 'SUCCESS' ? 'success' : 'danger'}`}>{selectedLog.result}</span></div>
                  <div className="col-12"><div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Description</div><div style={{ fontSize: '13px', background: 'var(--pt-bg)', padding: '8px 12px', borderRadius: '6px', marginTop: '4px' }}>{selectedLog.description ?? '—'}</div></div>
                </div>
              </div>
              <div className="modal-footer d-flex justify-content-end">
                <button className="pt-btn-outline" onClick={() => setSelectedLog(null)}>Fermer</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default AuditLogs
