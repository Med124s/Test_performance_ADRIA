import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import TopBar from '../components/TopBar'
import Pagination from '../components/Pagination'
import {
  BackendExecutionStatus,
  BackendApplicationResponse,
  BackendScenarioResponse,
  BackendExecutionHistoryResponse,
  ExecutionHistoryFilters,
} from '../types/backendContracts'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { ApiError } from '../services/api/httpClient'
import { useApiList } from '../hooks/useApiResource'

// ============================================================
// P1-A — Historique REEL, construit sur GET /api/executions/history
// (backend Spring Boot : pagination/filtres/tri/recherche tous executes
// cote serveur, voir ExecutionController#history) — jamais un chargement
// complet de la table suivi d'un filtrage en React (prompt P1-A, section
// 2). Remplace le placeholder "Bientot disponible" (ComingSoonPage).
//
// "Voir le rapport" navigue vers /executions/report/:id, qui reutilise
// ExecutionReport.tsx (deja reel, voir P1-A - enrichi de percentiles/
// statistiques/step-level report/export CSV) - jamais une deuxieme page
// dupliquant le meme contenu.
// ============================================================

const STATUS_OPTIONS: BackendExecutionStatus[] = ['QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED']
const PAGE_SIZE = 20

const statusLabel: Record<BackendExecutionStatus, string> = {
  QUEUED: 'En attente',
  RUNNING: 'En cours',
  SUCCESS: 'Réussie',
  FAILED: 'Échouée',
  CANCELLED: 'Annulée',
}
const statusPillClass: Record<BackendExecutionStatus, string> = {
  QUEUED: 'neutral',
  RUNNING: 'info',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELLED: 'neutral',
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 401:
        return 'Vous devez être connecté (Keycloak) pour consulter l\'historique.'
      case 403:
        return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      default:
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function formatDate(iso: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

function formatThroughput(value: number | null): string {
  if (value == null) return '—'
  return `${value} req/s`
}

function formatDuration(ms: number | null): string {
  if (ms == null) return '—'
  const totalSec = Math.round(ms / 1000)
  const h = String(Math.floor(totalSec / 3600)).padStart(2, '0')
  const m = String(Math.floor((totalSec % 3600) / 60)).padStart(2, '0')
  const s = String(totalSec % 60).padStart(2, '0')
  return `${h}:${m}:${s}`
}

type SortColumn = 'startedAt' | 'duration' | 'status'

function Historique() {
  const navigate = useNavigate()

  const { data: applications } = useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const { data: scenarios } = useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll())

  const [draft, setDraft] = useState({ status: '', scenarioId: '', applicationId: '', dateFrom: '', dateTo: '', search: '' })
  const [filters, setFilters] = useState<ExecutionHistoryFilters>({})
  const [pageIndex, setPageIndex] = useState(0)
  const [sortColumn, setSortColumn] = useState<SortColumn>('startedAt')
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('desc')

  const [data, setData] = useState<{ content: BackendExecutionHistoryResponse[]; totalElements: number; totalPages: number } | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const scenariosForFilter = useMemo(
    () => (draft.applicationId ? scenarios.filter((s) => s.applicationId === draft.applicationId) : scenarios),
    [scenarios, draft.applicationId]
  )

  const loadHistory = () => {
    setLoading(true)
    setError(null)
    executionsBackendApi
      .getHistory({ ...filters, page: pageIndex, size: PAGE_SIZE, sort: `${sortColumn},${sortDirection}` })
      .then(setData)
      .catch((err) => setError(describeApiError(err, "Impossible de charger l'historique.")))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    loadHistory()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters, pageIndex, sortColumn, sortDirection])

  const applyFilters = () => {
    const next: ExecutionHistoryFilters = {}
    if (draft.status) next.status = draft.status as BackendExecutionStatus
    if (draft.scenarioId) next.scenarioId = draft.scenarioId
    if (draft.applicationId) next.applicationId = draft.applicationId
    if (draft.dateFrom) next.dateFrom = new Date(`${draft.dateFrom}T00:00:00Z`).toISOString()
    if (draft.dateTo) next.dateTo = new Date(`${draft.dateTo}T23:59:59Z`).toISOString()
    if (draft.search.trim()) next.search = draft.search.trim()
    setPageIndex(0)
    setFilters(next)
  }

  const resetFilters = () => {
    setDraft({ status: '', scenarioId: '', applicationId: '', dateFrom: '', dateTo: '', search: '' })
    setPageIndex(0)
    setFilters({})
  }

  const toggleSort = (column: SortColumn) => {
    if (sortColumn === column) {
      setSortDirection((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortColumn(column)
      setSortDirection('desc')
    }
    setPageIndex(0)
  }

  const sortIcon = (column: SortColumn) => {
    if (sortColumn !== column) return <i className="bi bi-arrow-down-up" style={{ fontSize: '10px', opacity: 0.4 }}></i>
    return <i className={`bi ${sortDirection === 'asc' ? 'bi-arrow-up' : 'bi-arrow-down'}`} style={{ fontSize: '10px' }}></i>
  }

  const totalItems = data?.totalElements ?? 0
  const totalPages = Math.max(1, data?.totalPages ?? 1)
  const startIndex = totalItems === 0 ? 0 : pageIndex * PAGE_SIZE + 1
  const endIndex = Math.min((pageIndex + 1) * PAGE_SIZE, totalItems)
  const hasActiveFilters = Object.keys(filters).length > 0

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Historique</h1>
          <p>Consultez l'historique réel de toutes les exécutions (Spring Boot)</p>
        </div>
        <TopBar searchPlaceholder="" />
      </div>

      {/* Filtres reels — parametres de requete backend, jamais un filtrage local */}
      <div className="pt-card mb-4" style={{ padding: '1rem 1.25rem' }}>
        <div className="row g-3 align-items-end">
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Statut</label>
            <select className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draft.status} onChange={(e) => setDraft((p) => ({ ...p, status: e.target.value }))}>
              <option value="">Tous</option>
              {STATUS_OPTIONS.map((s) => <option key={s} value={s}>{statusLabel[s]}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Application</label>
            <select className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draft.applicationId} onChange={(e) => setDraft((p) => ({ ...p, applicationId: e.target.value, scenarioId: '' }))}>
              <option value="">Toutes</option>
              {applications.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Scénario</label>
            <select className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draft.scenarioId} onChange={(e) => setDraft((p) => ({ ...p, scenarioId: e.target.value }))}>
              <option value="">Tous</option>
              {scenariosForFilter.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Du</label>
            <input type="date" className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draft.dateFrom} onChange={(e) => setDraft((p) => ({ ...p, dateFrom: e.target.value }))} />
          </div>
          <div className="col-6 col-md-3 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Au</label>
            <input type="date" className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draft.dateTo} onChange={(e) => setDraft((p) => ({ ...p, dateTo: e.target.value }))} />
          </div>
          <div className="col-12 col-md-6 col-lg-2">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Recherche</label>
            <input className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={draft.search} onChange={(e) => setDraft((p) => ({ ...p, search: e.target.value }))} placeholder="Scénario, application ou id..." />
          </div>
          <div className="col-12 d-flex gap-2">
            <button className="pt-btn-primary" style={{ fontSize: '12.5px' }} onClick={applyFilters}><i className="bi bi-search me-1"></i>Rechercher</button>
            <button className="pt-btn-outline" style={{ fontSize: '12.5px' }} onClick={resetFilters}>Réinitialiser</button>
          </div>
        </div>
      </div>

      {error && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={loadHistory}>Réessayer</button>
        </div>
      )}

      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Exécutions ({totalItems})</h6>
        </div>

        {loading ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement de l'historique...</p>
          </div>
        ) : !data || data.content.length === 0 ? (
          <div className="pt-empty-state">
            <i className="bi bi-inbox" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
            <p>{hasActiveFilters ? 'Aucune exécution ne correspond aux filtres.' : 'Aucune exécution pour le moment.'}</p>
          </div>
        ) : (
          <div className="pt-table-wrapper">
            <table className="pt-table">
              <thead>
                <tr>
                  <th>Scénario</th>
                  <th>Application</th>
                  <th style={{ cursor: 'pointer', userSelect: 'none' }} onClick={() => toggleSort('status')}>Statut {sortIcon('status')}</th>
                  <th style={{ cursor: 'pointer', userSelect: 'none' }} onClick={() => toggleSort('startedAt')}>Démarrée le {sortIcon('startedAt')}</th>
                  <th style={{ cursor: 'pointer', userSelect: 'none' }} onClick={() => toggleSort('duration')}>Durée {sortIcon('duration')}</th>
                  <th>VUs</th>
                  <th>Étapes</th>
                  <th>Débit</th>
                  <th>Utilisateur</th>
                  <th style={{ textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((exec) => (
                  <tr key={exec.id}>
                    <td>
                      <button
                        onClick={() => navigate(`/scenarios?q=${encodeURIComponent(exec.scenarioName)}`)}
                        style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '13px', fontWeight: 600, color: 'var(--pt-primary)' }}
                      >
                        {exec.scenarioName}
                      </button>
                    </td>
                    <td><span style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>{exec.applicationName}</span></td>
                    <td><span className={`pt-pill ${statusPillClass[exec.status]}`} style={{ fontSize: '11px' }}>{statusLabel[exec.status]}</span></td>
                    <td><span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{formatDate(exec.startedAt)}</span></td>
                    <td><span style={{ fontSize: '13px', fontFamily: 'monospace' }}>{formatDuration(exec.duration)}</span></td>
                    <td><span style={{ fontSize: '12.5px' }}><i className="bi bi-people me-1"></i>{exec.virtualUsers}</span></td>
                    <td>
                      <span style={{ fontSize: '12.5px' }}>
                        <span style={{ color: 'var(--pt-success)', fontWeight: 600 }}>{exec.successfulSteps}</span>
                        {' / '}
                        <span style={{ color: 'var(--pt-danger)', fontWeight: 600 }}>{exec.failedSteps}</span>
                        {' / '}
                        <span style={{ color: 'var(--pt-text-muted)' }}>{exec.totalSteps}</span>
                      </span>
                    </td>
                    <td><span style={{ fontSize: '12.5px', fontFamily: 'monospace' }}>{formatThroughput(exec.throughput)}</span></td>
                    <td><span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{exec.triggeredByUsername ?? '—'}</span></td>
                    <td style={{ textAlign: 'right' }}>
                      <button
                        className="topbar-icon"
                        style={{ width: '30px', height: '30px', border: '1px solid var(--pt-border)' }}
                        title="Voir le rapport"
                        onClick={() => navigate(`/executions/report/${exec.id}`)}
                      >
                        <i className="bi bi-file-earmark-bar-graph" style={{ fontSize: '13px' }}></i>
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
          itemLabel="exécutions"
        />
      </div>
    </div>
  )
}

export default Historique
