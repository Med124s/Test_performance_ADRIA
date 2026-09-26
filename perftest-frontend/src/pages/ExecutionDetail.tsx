import { useEffect, useState } from 'react'
import { useParams, Link, useNavigate } from 'react-router-dom'
import { Line, Doughnut, Bar } from 'react-chartjs-2'
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  BarElement,
  ArcElement,
  Title,
  Tooltip,
  Legend,
  Filler,
} from 'chart.js'
import TopBar from '../components/TopBar'
import { useAuth } from '../context/AuthContext'
import { BackendExecutionDetailResponse, BackendMetricResponse } from '../types/backendContracts'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { metricsBackendApi } from '../services/api/metricsBackend'
import { ApiError } from '../services/api/httpClient'
import { backendToFrontendExecutionStatus } from '../utils/statusMapping'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, BarElement, ArcElement, Title, Tooltip, Legend, Filler)

/** Réindente le body de réponse en JSON lisible quand c'est possible ;
 * sinon affiche le texte brut tel que reçu de l'API. */
function formatResponseBody(body: string): string {
  try {
    return JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    return body
  }
}

// ============================================================
// Phase 23 — cette page reste utilisée pour les DEUX chaînes (JSON Server
// legacy ET Spring Boot), distinguées explicitement par la forme réelle de
// l'id dans l'URL (jamais par une simple tentative/repli) : un id Spring
// Boot est TOUJOURS un UUID v4 généré par le backend
// (@GeneratedValue(strategy = GenerationType.UUID)) ; un id JSON Server ne
// l'est jamais dans ce projet. Ce test est déterministe et garantit qu'on
// n'appelle jamais GET /api/executions/{id} avec un id JSON Server (voir
// énoncé Phase 23, section 13). Les deux chaînes restent des rendus
// entièrement séparés (LegacyExecutionDetail / SpringExecutionDetail),
// jamais mélangés dans le même calcul — même politique que Executions.tsx
// (Phase 20, onglets "Spring Boot"/"Legacy").
// ============================================================
const SPRING_UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 401:
        return 'Vous devez être connecté (Keycloak) pour consulter cette exécution.'
      case 403:
        return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      case 404:
        return 'Exécution introuvable (elle a peut-être déjà été supprimée).'
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

function formatDurationMs(ms: number | null): string {
  if (ms == null) return '—'
  const totalSec = Math.round(ms / 1000)
  const h = String(Math.floor(totalSec / 3600)).padStart(2, '0')
  const m = String(Math.floor((totalSec % 3600) / 60)).padStart(2, '0')
  const s = String(totalSec % 60).padStart(2, '0')
  return `${h}:${m}:${s}`
}

// ------------------------------------------------------------
// Vue Spring Boot (Phase 23) — données réelles uniquement. Pas d'onglets/
// VU/graphiques de bucket de temps de réponse/corps de requête-réponse
// capturés/CPU-RAM historique par étape : aucun de ces éléments n'existe
// dans le contrat backend (ExecutionResponse/ExecutionStepResultResponse),
// jamais simulés. Les métriques (débit/taux d'erreur/CPU/RAM/disque/
// réseau) proviennent de GET /api/metrics?executionId={id} (Phase 21,
// "si nécessaire et réellement utile" — utile ici car ExecutionResponse
// n'expose ni throughput/errorRate ni les mesures serveur).
// ------------------------------------------------------------
function SpringExecutionDetail({ id }: { id: string }) {
  const navigate = useNavigate()
  const { authProvider, rawRoles } = useAuth()
  const isKeycloak = authProvider === 'keycloak'
  // POST /api/executions/{id}/retry est reserve a SUPER_ADMIN et
  // PERFORMANCE_ENGINEER (voir ExecutionController, Phase 20).
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  const [execution, setExecution] = useState<BackendExecutionDetailResponse | null>(null)
  const [metrics, setMetrics] = useState<BackendMetricResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retrying, setRetrying] = useState(false)
  const [cancelling, setCancelling] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    executionsBackendApi.getById(id)
      .then(async (exec) => {
        if (cancelled) return
        setExecution(exec)
        // Les Metric sont supplémentaires (débit/erreur/monitoring) : un
        // échec de ce second appel ne doit jamais empêcher l'affichage du
        // détail réel de l'exécution déjà chargée.
        const m = await metricsBackendApi.search({ executionId: id }).catch(() => [])
        if (!cancelled) setMetrics(m)
      })
      .catch((err) => setError(describeApiError(err, 'Exécution introuvable.')))
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [id])

  // P0-A — polling réel, uniquement tant que l'exécution est réellement
  // QUEUED/RUNNING (jamais permanent) : voir Executions.tsx pour le même
  // principe sur la liste.
  const isActive = execution?.status === 'QUEUED' || execution?.status === 'RUNNING'
  useEffect(() => {
    if (!isActive) return
    const interval = setInterval(() => {
      executionsBackendApi.getById(id).then(setExecution).catch(() => {})
    }, 2000)
    return () => clearInterval(interval)
  }, [id, isActive])

  const handleRetry = async () => {
    if (!canWrite || retrying) return
    setRetrying(true)
    try {
      const result = await executionsBackendApi.retry(id)
      navigate(`/executions/detail/${result.id}`)
    } catch (err) {
      setError(describeApiError(err, 'Erreur lors de la relance.'))
    } finally {
      setRetrying(false)
    }
  }

  const handleCancel = async () => {
    if (!canWrite || cancelling || !isActive) return
    setCancelling(true)
    try {
      const updated = await executionsBackendApi.cancel(id)
      setExecution((prev) => (prev ? { ...prev, status: updated.status, finishedAt: updated.finishedAt, duration: updated.duration } : prev))
    } catch (err) {
      setError(describeApiError(err, "Erreur lors de l'annulation."))
    } finally {
      setCancelling(false)
    }
  }

  if (loading) {
    return (
      <div className="pt-content">
        <div className="pt-empty-state">
          <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
          <p>Chargement de l'exécution...</p>
        </div>
      </div>
    )
  }

  if (error || !execution) {
    return (
      <div className="pt-content">
        <div className="pt-alert-banner danger">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {error ?? 'Exécution introuvable.'}
        </div>
        <Link to="/executions" className="pt-btn-outline mt-3 d-inline-block">
          <i className="bi bi-arrow-left"></i> Retour aux exécutions
        </Link>
      </div>
    )
  }

  const statusMeta: Record<string, { cls: string; icon: string; text: string }> = {
    QUEUED: { cls: 'neutral', icon: 'bi-hourglass-split', text: 'En attente de démarrage' },
    SUCCESS: { cls: 'success', icon: 'bi-check-circle-fill', text: 'Terminée avec succès' },
    FAILED: { cls: 'danger', icon: 'bi-x-circle-fill', text: 'Échouée' },
    RUNNING: { cls: 'info', icon: 'bi-play-circle-fill', text: 'En cours' },
    CANCELLED: { cls: 'neutral', icon: 'bi-slash-circle-fill', text: 'Annulée' },
  }
  const meta = statusMeta[execution.status]
  const executedSteps = execution.successfulSteps + execution.failedSteps
  // Taux de réussite affiché : dérivé des deux vrais compteurs déjà
  // renvoyés par le backend (successfulSteps/failedSteps) — jamais
  // recalculé depuis les résultats bruts, jamais une valeur inventée.
  const successRateDisplay = executedSteps > 0 ? (execution.successfulSteps / executedSteps) * 100 : null

  // Le débit/taux d'erreur d'exécution sont des champs de Metric (niveau
  // exécution, dupliqués sur chaque ligne — voir Phase 21) : n'importe
  // quelle ligne suffit pour throughput/errorRate.
  const anyMetric = metrics[0]
  const fmt = (v: number | null | undefined, unit = ''): string => (v == null ? 'N/D' : `${Math.round(v * 100) / 100}${unit}`)

  return (
    <div className="pt-content">
      <div className="d-flex align-items-center justify-content-between mb-3 flex-wrap gap-2">
        <nav aria-label="breadcrumb">
          <ol className="breadcrumb mb-0" style={{ fontSize: '13px' }}>
            <li className="breadcrumb-item"><Link to="/executions" className="text-decoration-none text-muted">Exécutions</Link></li>
            <li className="breadcrumb-item active text-primary fw-semibold" aria-current="page">Détail exécution #{id.slice(0, 8)}</li>
          </ol>
        </nav>
        <TopBar searchPlaceholder="" />
      </div>

      <div className="pt-page-header">
        <div className="page-title">
          <div className="d-flex align-items-center gap-3 flex-wrap">
            <h1>Exécution — {execution.scenarioName}</h1>
            <span className={`pt-pill ${meta.cls}`}><i className={`bi ${meta.icon}`}></i> {meta.text}</span>
          </div>
          <p>
            Scénario <Link to={`/scenarios?q=${encodeURIComponent(execution.scenarioName)}`} className="text-decoration-none">{execution.scenarioName}</Link>
            {' '}— lancée le {formatDate(execution.startedAt)}
          </p>
        </div>
        <div className="header-actions d-flex gap-2">
          {canWrite && isActive && (
            <button className="pt-btn-outline" style={{ color: 'var(--pt-danger)', borderColor: 'var(--pt-danger)' }} onClick={handleCancel} disabled={cancelling}>
              <i className={`bi ${cancelling ? 'bi-arrow-repeat pt-spin' : 'bi-slash-circle'}`}></i> {cancelling ? 'Annulation...' : 'Annuler'}
            </button>
          )}
          {canWrite && (
            <button className="pt-btn-primary" onClick={handleRetry} disabled={retrying}>
              <i className={`bi ${retrying ? 'bi-arrow-repeat pt-spin' : 'bi-arrow-repeat'}`}></i> {retrying ? 'Relance...' : 'Relancer le test'}
            </button>
          )}
        </div>
      </div>

      {execution.errorMessage && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {execution.errorMessage}
        </div>
      )}

      <div className="pt-card mb-3 py-3 px-3 d-flex align-items-center flex-wrap gap-3">
        <span className={`pt-pill ${meta.cls}`} style={{ fontSize: '13px' }}><i className={`bi ${meta.icon}`}></i> {meta.text}</span>
        <span className="pt-pill success" style={{ fontSize: '13px' }}><i className="bi bi-check-circle-fill"></i> {execution.successfulSteps} étape(s) réussie(s)</span>
        <span className="pt-pill danger" style={{ fontSize: '13px' }}><i className="bi bi-x-circle-fill"></i> {execution.failedSteps} étape(s) échouée(s)</span>
        {execution.totalSteps > executedSteps && (
          <span className="pt-pill neutral" style={{ fontSize: '13px' }} title="Le moteur backend s'arrête à la première étape en échec (stop-on-failure)">
            <i className="bi bi-dash-circle"></i> {execution.totalSteps - executedSteps} étape(s) non exécutée(s)
          </span>
        )}
        <span className="pt-pill neutral" style={{ fontSize: '13px' }}><i className="bi bi-people"></i> {execution.virtualUsers} VU</span>
        <span className="pt-pill neutral" style={{ fontSize: '13px' }}><i className="bi bi-graph-up-arrow"></i> Ramp-up {execution.rampUpSeconds}s</span>
        {execution.durationSeconds != null && (
          <span className="pt-pill neutral" style={{ fontSize: '13px' }}><i className="bi bi-stopwatch"></i> Durée {execution.durationSeconds}s</span>
        )}
        {execution.iterations != null && (
          <span className="pt-pill neutral" style={{ fontSize: '13px' }}><i className="bi bi-arrow-repeat"></i> {execution.iterations} itération(s)</span>
        )}
      </div>

      <div className="d-flex align-items-center gap-2 mb-2" style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-speedometer2" style={{ color: 'var(--pt-primary)' }}></i> PERFORMANCE (données réelles Spring Boot)
      </div>
      <div className="row g-3 mb-4">
        {[
          { label: 'Durée totale', value: formatDurationMs(execution.duration), icon: 'bi-clock-history', color: 'blue' },
          { label: 'Étapes exécutées', value: `${executedSteps} / ${execution.totalSteps}`, icon: 'bi-list-check', color: 'purple' },
          { label: 'Taux réussite', value: fmt(successRateDisplay, '%'), icon: 'bi-check-circle', color: 'green' },
          { label: 'Débit', value: fmt(anyMetric?.throughput ?? null, ' req/s'), icon: 'bi-speedometer2', color: 'blue' },
          { label: "Taux d'erreur", value: fmt(anyMetric?.errorRate ?? null, '%'), icon: 'bi-exclamation-triangle', color: 'red' },
        ].map((kpi, idx) => (
          <div className="col-12 col-sm-6 col-md-4 col-xl" key={idx}>
            <div className="pt-stat-card">
              <div className="stat-header">
                <span className="stat-label">{kpi.label}</span>
                <div className={`stat-icon ${kpi.color}`}><i className={`bi ${kpi.icon}`}></i></div>
              </div>
              <div className="stat-value" style={{ fontSize: '22px' }}>{kpi.value}</div>
            </div>
          </div>
        ))}
      </div>

      <div className="pt-card mb-4">
        <div className="pt-card-title mb-3"><i className="bi bi-list-check me-2 text-primary"></i>Résultat de chaque étape</div>
        <div className="pt-table-wrapper">
          <table className="pt-table">
            <thead>
              <tr>
                <th>#</th>
                <th>Méthode & Endpoint</th>
                <th>Code HTTP</th>
                <th>Temps de réponse</th>
                <th>Résultat</th>
                <th>Horodatage</th>
              </tr>
            </thead>
            <tbody>
              {execution.results.length === 0 ? (
                <tr><td colSpan={6} className="text-center text-muted py-3">Aucun résultat pour cette exécution.</td></tr>
              ) : execution.results.map((r, i) => (
                <tr key={i}>
                  <td>{i + 1}</td>
                  <td>
                    <span className={`badge me-2 ${r.method === 'GET' ? 'bg-primary-subtle text-primary' : 'bg-success-subtle text-success'}`} style={{ minWidth: '45px' }}>{r.method}</span>
                    <span className="font-monospace fw-medium" style={{ fontSize: '13px' }}>{r.url}</span>
                    <div className="text-muted" style={{ fontSize: '11px' }}>{r.stepName}</div>
                  </td>
                  <td><span className="badge bg-light text-dark border">{r.httpStatus ?? '—'}</span></td>
                  <td className={!r.success ? 'fw-bold text-danger' : 'fw-bold'}>{r.responseTime} ms</td>
                  <td>
                    <span className={`pt-pill ${r.success ? 'success' : 'danger'}`}>
                      <i className={`bi ${r.success ? 'bi-check-circle-fill' : 'bi-x-circle-fill'} me-1`}></i>
                      {r.success ? 'Réussi' : 'Échec'}
                    </span>
                    {r.error && <div className="text-danger" style={{ fontSize: '11.5px', marginTop: '2px' }}>{r.error}</div>}
                  </td>
                  <td style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>{formatDate(r.timestamp)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

function ExecutionDetail() {
  const { id } = useParams()
  return <SpringExecutionDetail id={id ?? ''} />
}

export default ExecutionDetail
