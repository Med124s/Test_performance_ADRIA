import { useEffect, useState } from 'react'
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
import { Link, useParams } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { BackendExecutionReportResponse } from '../types/backendContracts'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { ApiError } from '../services/api/httpClient'
import { useToast } from '../context/ToastContext'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, BarElement, ArcElement, Title, Tooltip, Legend, Filler)

// ============================================================
// Phase 23 — même politique de double chaîne que ExecutionDetail.tsx : un
// id Spring Boot est toujours un UUID v4 réel, jamais un id JSON Server
// dans ce projet — voir ExecutionDetail.tsx pour la justification complète.
// ============================================================
const SPRING_UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 401:
        return 'Vous devez être connecté (Keycloak) pour consulter ce rapport.'
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

function formatDurationMs(ms: number | null): string {
  if (ms == null) return '—'
  const totalSec = Math.round(ms / 1000)
  const h = String(Math.floor(totalSec / 3600)).padStart(2, '0')
  const m = String(Math.floor((totalSec % 3600) / 60)).padStart(2, '0')
  const s = String(totalSec % 60).padStart(2, '0')
  return `${h}:${m}:${s}`
}

// ------------------------------------------------------------
// Vue Spring Boot (P1-A, remplace l'ancienne version Phase 23) — rapport
// basé uniquement sur GET /api/executions/{id}/report (voir
// ExecutionController#getReport / PerformanceStatisticsService cote
// backend) : statistiques REELLEMENT calculees, percentiles reels
// (p50/p75/p90/p95/p99 - le backend ne les calculait nulle part avant
// P1-A, voir rapport P0-B), configuration de charge reellement figee au
// lancement, agregation reelle par step, erreurs reelles. Plus aucun appel
// a executionsBackendApi.getById/metricsBackendApi.search : un seul appel
// reseau suffit desormais (evite toute divergence entre deux sources).
// ------------------------------------------------------------
function SpringExecutionReport({ id }: { id: string }) {
  const { showToast } = useToast()
  const [report, setReport] = useState<BackendExecutionReportResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [exporting, setExporting] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    executionsBackendApi.getReport(id)
      .then((r) => { if (!cancelled) setReport(r) })
      .catch((err) => setError(describeApiError(err, 'Exécution introuvable.')))
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [id])

  const handleExport = async () => {
    if (exporting) return
    setExporting(true)
    try {
      const blob = await executionsBackendApi.exportReportCsv(id)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `execution-report-${id}.csv`
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

  if (loading) {
    return (
      <div className="pt-content">
        <div className="pt-empty-state">
          <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
          <p>Chargement du rapport...</p>
        </div>
      </div>
    )
  }

  if (error || !report) {
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

  const statusMeta: Record<string, { cls: string; color: string; text: string }> = {
    QUEUED: { cls: 'neutral', color: 'var(--pt-neutral)', text: 'En attente' },
    SUCCESS: { cls: 'success', color: 'var(--pt-success)', text: 'Réussie' },
    FAILED: { cls: 'danger', color: 'var(--pt-danger)', text: 'Échouée' },
    RUNNING: { cls: 'info', color: 'var(--pt-info)', text: 'En cours' },
    CANCELLED: { cls: 'neutral', color: 'var(--pt-neutral)', text: 'Annulée' },
  }
  const meta = statusMeta[report.status]
  const stats = report.statistics
  const steps = report.steps
  const errors = report.errors
  const fmt = (v: number | null | undefined, unit = ''): string => (v == null ? 'N/D' : `${Math.round(v * 100) / 100}${unit}`)
  const fmtMs = (v: number | null | undefined): string => (v == null ? 'N/D' : `${v} ms`)

  // Chart 1 : temps de reponse par ETAPE (agrege — avg/p95/p99), pas par
  // requete individuelle : avec plusieurs utilisateurs virtuels/iterations
  // reelles (voir P0-A), une ligne "une requete = un point" deviendrait
  // illisible et perdrait le sens du "step comparison" recommande (prompt
  // P1-A, section 23) — donnees 100% reelles issues de PerformanceStatisticsService.
  const stepComparisonData = {
    labels: steps.map((s) => s.stepName),
    datasets: [
      { label: 'Moyenne (ms)', data: steps.map((s) => s.avgResponseTime ?? 0), backgroundColor: '#4F46E5', borderRadius: 4 },
      { label: 'p95 (ms)', data: steps.map((s) => s.p95 ?? 0), backgroundColor: '#F59E0B', borderRadius: 4 },
      { label: 'p99 (ms)', data: steps.map((s) => s.p99 ?? 0), backgroundColor: '#EF4444', borderRadius: 4 },
    ],
  }
  const stepComparisonOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { position: 'bottom' as const, labels: { boxWidth: 10, font: { size: 11 } } } },
    scales: { y: { beginAtZero: true, grid: { color: 'rgba(0,0,0,0.05)' } }, x: { grid: { display: false } } },
  }

  // Chart 2 : percentiles reels de l'execution entiere (p50/p75/p90/p95/p99)
  // — premiere fois que ces valeurs existent reellement dans LoadPilot
  // (voir PercentileCalculator cote backend, P1-A).
  const percentileData = {
    labels: ['p50', 'p75', 'p90', 'p95', 'p99'],
    datasets: [{
      label: 'Temps de réponse (ms)',
      data: [stats.p50 ?? 0, stats.p75 ?? 0, stats.p90 ?? 0, stats.p95 ?? 0, stats.p99 ?? 0],
      backgroundColor: ['#22C55E', '#22C55E', '#F59E0B', '#F59E0B', '#EF4444'],
      borderRadius: 6,
    }],
  }
  const percentileOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: { y: { beginAtZero: true, grid: { color: 'rgba(0,0,0,0.05)' } }, x: { grid: { display: false } } },
  }

  // Chart 3 : repartition reelle succes / erreurs (statistiques globales).
  const statusDistributionData = {
    labels: [`Réussies (${stats.successfulRequests})`, `En erreur (${stats.failedRequests})`],
    datasets: [{ data: [stats.successfulRequests, stats.failedRequests], backgroundColor: ['#22C55E', '#EF4444'], borderWidth: 2, borderColor: '#ffffff' }],
  }
  const statusDistributionOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { position: 'bottom' as const, labels: { usePointStyle: true, boxWidth: 8, font: { size: 11 } } } },
    cutout: '70%',
  }

  return (
    <div className="pt-content">
      <div className="mb-3">
        <Link to="/executions" className="text-decoration-none d-inline-flex align-items-center gap-1 text-muted small fw-semibold">
          <i className="bi bi-chevron-left"></i> Retour aux exécutions
        </Link>
      </div>

      <div className="pt-page-header">
        <div className="page-title">
          <div className="d-flex align-items-center gap-3 flex-wrap">
            <h1>Rapport exécution — {report.scenarioName}</h1>
            <span className={`pt-pill ${meta.cls}`}>
              <span style={{ width: '6px', height: '6px', borderRadius: '50%', backgroundColor: meta.color, display: 'inline-block' }}></span>
              {meta.text}
            </span>
          </div>
          <p>Exécution lancée le {new Date(report.startedAt).toLocaleString('fr-FR')} sur <strong>{report.applicationName}</strong></p>
        </div>
        <div className="d-flex align-items-center gap-2">
          <button className="pt-btn-outline" onClick={handleExport} disabled={exporting}>
            <i className={`bi ${exporting ? 'bi-arrow-repeat pt-spin' : 'bi-download'}`}></i>
            {exporting ? 'Export...' : 'Exporter CSV'}
          </button>
          <TopBar searchPlaceholder="" />
        </div>
      </div>

      {report.errorMessage && (
        <div className="pt-alert-banner danger mb-4">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {report.errorMessage}
        </div>
      )}

      <div className="pt-card mb-3 py-2 px-3">
        <div className="row g-2 align-items-center text-muted" style={{ fontSize: '13px' }}>
          <div className="col-12 col-md-auto d-flex align-items-center gap-2 me-3">
            <i className="bi bi-collection-play text-primary"></i>
            <span>Scénario: <Link to={`/scenarios?q=${encodeURIComponent(report.scenarioName)}`} className="text-dark fw-bold text-decoration-none">{report.scenarioName}</Link></span>
          </div>
        </div>
      </div>

      {/* Configuration de charge REELLEMENT figee au lancement (voir
          Execution.virtualUsers et suivants, P0-A) — jamais celle,
          potentiellement differente, du Scenario aujourd'hui. */}
      <div className="d-flex align-items-center gap-2 mb-2" style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-sliders" style={{ color: 'var(--pt-primary)' }}></i> CONFIGURATION DE CHARGE (figée au lancement)
      </div>
      <div className="d-flex flex-wrap gap-2 mb-4">
        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-people me-1"></i>{report.virtualUsers} utilisateur(s) virtuel(s)</span>
        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-graph-up-arrow me-1"></i>Ramp-up {report.rampUpSeconds}s</span>
        {report.durationSeconds != null && (
          <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-stopwatch me-1"></i>Durée cible {report.durationSeconds}s</span>
        )}
        {report.iterations != null && (
          <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-arrow-repeat me-1"></i>{report.iterations} itération(s)</span>
        )}
        {/* P1-C — utilisateur ayant réellement lancé cette exécution (voir
            P1-B, Execution.triggeredBy) — absent pour toute exécution
            antérieure à P1-B, jamais deviné. */}
        {report.triggeredByUsername && (
          <span className="pt-pill info" style={{ fontSize: '11.5px' }}><i className="bi bi-person-fill me-1"></i>Lancée par {report.triggeredByUsername}</span>
        )}
      </div>

      {/* Summary cards — champs REELS uniquement, issus de PerformanceStatisticsService */}
      <div className="row g-3 mb-4">
        <div className="col-12 col-sm-6 col-lg">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div><div className="stat-label">Statut</div><div className="stat-value" style={{ fontSize: '18px', marginTop: '4px', color: meta.color }}>{meta.text}</div></div>
              <div className="stat-icon green"><i className="bi bi-check-circle"></i></div>
            </div>
          </div>
        </div>
        <div className="col-12 col-sm-6 col-lg">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div><div className="stat-label">Durée réelle</div><div className="stat-value">{formatDurationMs(report.duration)}</div></div>
              <div className="stat-icon purple"><i className="bi bi-stopwatch"></i></div>
            </div>
          </div>
        </div>
        <div className="col-12 col-sm-6 col-lg">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Requêtes</div>
                <div className="stat-value">{stats.successfulRequests} / {stats.totalRequests}</div>
                <div className="stat-trend positive">{fmt(stats.successRate, '%')} succès</div>
              </div>
              <div className="stat-icon orange"><i className="bi bi-arrow-repeat"></i></div>
            </div>
          </div>
        </div>
        <div className="col-12 col-sm-6 col-lg">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div><div className="stat-label">Débit</div><div className="stat-value">{fmt(stats.throughput)}</div><div className="stat-trend positive">req / s</div></div>
              <div className="stat-icon blue"><i className="bi bi-speedometer"></i></div>
            </div>
          </div>
        </div>
        <div className="col-12 col-sm-6 col-lg">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div><div className="stat-label">Taux d'erreur</div><div className="stat-value">{fmt(stats.errorRate, '%')}</div></div>
              <div className="stat-icon red"><i className="bi bi-exclamation-triangle"></i></div>
            </div>
          </div>
        </div>
      </div>

      {/* Statistiques de temps de reponse — min/max/moyenne (jamais de
          valeur inventee : "N/D" si aucune requete n'a de temps mesurable). */}
      <div className="d-flex align-items-center gap-2 mb-2" style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-speedometer2" style={{ color: 'var(--pt-primary)' }}></i> TEMPS DE RÉPONSE (millisecondes)
      </div>
      <div className="row g-3 mb-4">
        {[
          { label: 'Minimum', value: fmtMs(stats.minResponseTime), icon: 'bi-arrow-down-circle', color: 'green' },
          { label: 'Moyenne', value: fmtMs(stats.avgResponseTime), icon: 'bi-lightning-charge', color: 'blue' },
          { label: 'Maximum', value: fmtMs(stats.maxResponseTime), icon: 'bi-arrow-up-circle', color: 'red' },
          { label: 'p95', value: fmtMs(stats.p95), icon: 'bi-graph-up', color: 'orange' },
          { label: 'p99', value: fmtMs(stats.p99), icon: 'bi-graph-up', color: 'purple' },
          { label: 'Écart-type', value: fmtMs(stats.stdDevResponseTime), icon: 'bi-distribute-vertical', color: 'blue' },
        ].map((kpi, idx) => (
          <div className="col-6 col-md-4 col-lg" key={idx}>
            <div className="pt-stat-card">
              <div className="stat-header">
                <span className="stat-label">{kpi.label}</span>
                <div className={`stat-icon ${kpi.color}`}><i className={`bi ${kpi.icon}`}></i></div>
              </div>
              <div className="stat-value" style={{ fontSize: '20px' }}>{kpi.value}</div>
            </div>
          </div>
        ))}
      </div>

      <div className="row g-4 mb-4">
        <div className="col-12 col-xl-6">
          <div className="pt-card h-100">
            <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2" style={{ borderColor: 'var(--pt-border)' }}>
              <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}><i className="bi bi-bar-chart-steps text-primary me-2"></i>Comparaison des étapes</h5>
              <span className="badge bg-light text-dark border">{steps.length} étape(s)</span>
            </div>
            {steps.length === 0 ? (
              <div className="text-center text-muted py-5" style={{ fontSize: '13px' }}>Aucune donnée suffisante pour ce graphique.</div>
            ) : (
              <div style={{ height: '280px' }}><Bar data={stepComparisonData} options={stepComparisonOptions} /></div>
            )}
          </div>
        </div>
        <div className="col-12 col-xl-6">
          <div className="pt-card h-100">
            <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2" style={{ borderColor: 'var(--pt-border)' }}>
              <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}><i className="bi bi-graph-up text-primary me-2"></i>Percentiles réels</h5>
            </div>
            {stats.totalRequests === 0 ? (
              <div className="text-center text-muted py-5" style={{ fontSize: '13px' }}>Aucune requête exécutée — percentiles non calculables.</div>
            ) : (
              <div style={{ height: '280px' }}><Bar data={percentileData} options={percentileOptions} /></div>
            )}
          </div>
        </div>
      </div>

      <div className="row g-4 mb-4">
        <div className="col-12 col-xl-4">
          <div className="pt-card h-100 d-flex flex-column justify-content-between">
            <div>
              <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2" style={{ borderColor: 'var(--pt-border)' }}>
                <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}><i className="bi bi-pie-chart-fill text-primary me-2"></i>Répartition des réponses</h5>
                <span className="pt-pill success">{stats.totalRequests} req</span>
              </div>
              {stats.totalRequests === 0 ? (
                <div className="text-center text-muted py-5" style={{ fontSize: '13px' }}>Aucune requête exécutée.</div>
              ) : (
                <div style={{ height: '220px', position: 'relative' }}><Doughnut data={statusDistributionData} options={statusDistributionOptions} /></div>
              )}
            </div>
            <div className="mt-3 pt-3 border-top d-flex justify-content-around text-center" style={{ borderColor: 'var(--pt-border)', fontSize: '12px' }}>
              <div><span className="text-muted d-block">Réussies</span><strong className="text-success">{stats.successfulRequests} ({fmt(stats.successRate, '%')})</strong></div>
              <div><span className="text-muted d-block">En erreur</span><strong className="text-danger">{stats.failedRequests}</strong></div>
            </div>
          </div>
        </div>

        <div className="col-12 col-xl-4">
          <div className="pt-card h-100">
            <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2" style={{ borderColor: 'var(--pt-border)' }}>
              <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}><i className="bi bi-table text-primary me-2"></i>Résultat par étape</h5>
            </div>
            <div className="pt-table-wrapper">
              <table className="pt-table">
                <thead><tr><th>Étape</th><th>Total</th><th>Moyenne</th><th>p95</th><th>Écart-type</th><th>Erreurs</th></tr></thead>
                <tbody style={{ fontSize: '12.5px' }}>
                  {steps.length === 0 ? (
                    <tr><td colSpan={6} className="text-center text-muted py-3">Aucun résultat</td></tr>
                  ) : steps.map((s) => (
                    <tr key={s.stepId}>
                      <td><span className="fw-semibold text-dark">{s.stepName}</span></td>
                      <td>{s.total}</td>
                      <td>{fmtMs(s.avgResponseTime)}</td>
                      <td>{fmtMs(s.p95)}</td>
                      <td>{fmtMs(s.stdDevResponseTime)}</td>
                      <td><span className={s.failed > 0 ? 'text-danger fw-semibold' : 'text-success'}>{s.failed}</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div className="col-12 col-xl-4">
          <div className="pt-card h-100">
            <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2" style={{ borderColor: 'var(--pt-border)' }}>
              <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}><i className="bi bi-exclamation-triangle-fill text-warning me-2"></i>Erreurs</h5>
              <span className="pt-pill warning">{errors.length} total</span>
            </div>
            <div className="pt-table-wrapper">
              <table className="pt-table">
                <thead><tr><th>Code</th><th>Étape</th><th>Message</th></tr></thead>
                <tbody style={{ fontSize: '12.5px' }}>
                  {errors.length === 0 ? (
                    <tr><td colSpan={3} className="text-center text-muted py-3">Aucune erreur</td></tr>
                  ) : errors.map((e, i) => (
                    <tr key={i}>
                      <td><span className="pt-pill danger py-0 px-2" style={{ fontSize: '11px', backgroundColor: '#FEE2E2', color: '#DC2626' }}>{e.httpStatus ?? '—'}</span></td>
                      <td>{e.stepName}</td>
                      <td><span className="text-truncate d-inline-block" style={{ maxWidth: '140px' }} title={e.error ?? undefined}>{e.error}</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function ExecutionReport() {
  const { id } = useParams()
  return <SpringExecutionReport id={id ?? ''} />
}

export default ExecutionReport
