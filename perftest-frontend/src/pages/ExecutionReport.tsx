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
import { NoFailedSteps, OnlyFailedToggle, StepPagerFooter, useStepPaging } from '../components/StepTablePager'
import { formatPause } from './ExecutionLive'
import TimelineAnalysis from './execution-report/TimelineAnalysis'

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
  // Hook appelé avant les retours anticipés (chargement / erreur) : ordre des hooks stable.
  const paging = useStepPaging(report?.steps ?? [], (s) => s.failed > 0)

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

  // Icône de la carte « Statut » : croix rouge si échouée, coche verte si réussie…
  const statusMeta: Record<string, { cls: string; color: string; text: string; icon: string; iconColor: string }> = {
    QUEUED: { cls: 'neutral', color: 'var(--pt-neutral)', text: 'En attente', icon: 'bi-hourglass-split', iconColor: 'blue' },
    SUCCESS: { cls: 'success', color: 'var(--pt-success)', text: 'Réussie', icon: 'bi-check-circle', iconColor: 'green' },
    FAILED: { cls: 'danger', color: 'var(--pt-danger)', text: 'Échouée', icon: 'bi-x-circle', iconColor: 'red' },
    RUNNING: { cls: 'info', color: 'var(--pt-info)', text: 'En cours', icon: 'bi-play-circle', iconColor: 'blue' },
    PAUSED: { cls: 'warning', color: 'var(--pt-warning)', text: 'En pause', icon: 'bi-pause-circle', iconColor: 'orange' },
    CANCELLED: { cls: 'neutral', color: 'var(--pt-neutral)', text: 'Annulée', icon: 'bi-slash-circle', iconColor: 'blue' },
  }
  const meta = statusMeta[report.status]
  const stats = report.statistics
  const steps = report.steps
  // Erreurs regroupées (code + étape + message) ; repli sur la liste brute pour un ancien serveur.
  const errorGroups = report.errorGroups ?? report.errors.map((e) => ({ httpStatus: e.httpStatus, stepName: e.stepName, method: e.method, error: e.error, count: 1, firstAt: e.timestamp }))
  const errorTotal = errorGroups.reduce((n, g) => n + g.count, 0)
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
        {report.pausedDurationMs > 0 && (
          <span className="pt-pill warning" style={{ fontSize: '11.5px' }}><i className="bi bi-pause-circle me-1"></i>En pause pendant {formatPause(report.pausedDurationMs)}</span>
        )}
      </div>

      {/* Summary cards — champs REELS uniquement, issus de PerformanceStatisticsService */}
      <div className="row g-3 mb-4">
        <div className="col" style={{ flex: '1 1 180px' }}>
          <div className="pt-stat-card">
            <div className="stat-header">
              <div><div className="stat-label">Statut</div><div className="stat-value" style={{ fontSize: '18px', marginTop: '4px', color: meta.color }}>{meta.text}</div></div>
              <div className={`stat-icon ${meta.iconColor}`}><i className={`bi ${meta.icon}`} aria-hidden="true"></i></div>
            </div>
          </div>
        </div>
        <div className="col" style={{ flex: '1 1 180px' }}>
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Durée réelle</div>
                <div className="stat-value">{formatDurationMs(report.duration)}</div>
                {report.pausedDurationMs > 0 && <div className="stat-trend" style={{ color: 'var(--pt-warning)' }}>dont {formatPause(report.pausedDurationMs)} en pause</div>}
              </div>
              <div className="stat-icon purple"><i className="bi bi-stopwatch"></i></div>
            </div>
          </div>
        </div>
        <div className="col" style={{ flex: '1 1 180px' }}>
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
        <div className="col" style={{ flex: '1 1 180px' }}>
          <div className="pt-stat-card">
            <div className="stat-header">
              <div><div className="stat-label">Débit</div><div className="stat-value">{fmt(stats.throughput)}</div><div className="stat-trend positive">{report.pausedDurationMs > 0 ? 'req / s (hors pause)' : 'req / s'}</div></div>
              <div className="stat-icon blue"><i className="bi bi-speedometer"></i></div>
            </div>
          </div>
        </div>
        <div className="col" style={{ flex: '1 1 180px' }}>
          <div className="pt-stat-card">
            <div className="stat-header">
              <div><div className="stat-label">Taux d'erreur</div><div className="stat-value">{fmt(stats.errorRate, '%')}</div></div>
              <div className="stat-icon red"><i className="bi bi-exclamation-triangle"></i></div>
            </div>
          </div>
        </div>
      </div>

      {/* Analyse dans le temps : points clés, utilisateurs / débit / P95, par étape, erreurs. */}
      <TimelineAnalysis executionId={id} />

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
        ].map((kpi, idx) => (
          // Colonnes qui s'élargissent pour remplir chaque ligne : jamais de trou, quelle que soit la largeur.
          <div className="col" style={{ flex: '1 1 150px' }} key={idx}>
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
        <div className="col-12">
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
      </div>

      {/* Résultat par étape : pleine largeur, 10 étapes par page au-delà de 10. */}
      <div className="pt-card mb-4" style={{ padding: 0 }}>
        <div className="d-flex align-items-center justify-content-between flex-wrap gap-2 p-3 border-bottom" style={{ borderColor: 'var(--pt-border)' }}>
          <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}><i className="bi bi-table text-primary me-2"></i>Résultat par étape</h5>
          {steps.length > 0 && <OnlyFailedToggle paging={paging} id="report-only-failed" />}
        </div>
        <div className="pt-table-wrapper">
          <table className="pt-table">
            <thead>
              <tr>
                <th>Étape</th><th className="text-end">Total</th><th className="text-end">Minimum</th><th className="text-end">Moyenne</th>
                <th className="text-end">Maximum</th><th className="text-end">p95</th><th className="text-end">p99</th><th className="text-end">Erreurs</th>
              </tr>
            </thead>
            <tbody style={{ fontSize: '12.5px' }}>
              {steps.length === 0 ? (
                <tr><td colSpan={8} className="text-center text-muted py-3">Aucun résultat</td></tr>
              ) : paging.pageItems.length === 0 ? <NoFailedSteps colSpan={8} /> : paging.pageItems.map(({ item: s, number }) => (
                <tr key={s.stepId ?? `${number}-${s.stepName}`}>
                  <td><span className="fw-semibold text-dark">{number}. {s.stepName}</span></td>
                  <td className="text-end">{s.total}</td>
                  <td className="text-end">{fmtMs(s.minResponseTime)}</td>
                  <td className="text-end">{fmtMs(s.avgResponseTime)}</td>
                  <td className="text-end">{fmtMs(s.maxResponseTime)}</td>
                  <td className="text-end">{fmtMs(s.p95)}</td>
                  <td className="text-end">{fmtMs(s.p99)}</td>
                  <td className="text-end"><span className={s.failed > 0 ? 'text-danger fw-semibold' : 'text-success'}>{s.failed}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <StepPagerFooter paging={paging} />
      </div>

      <div className="row g-4 mb-4">
        <div className="col-12 col-xl-6">
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

        <div className="col-12 col-xl-6">
          <div className="pt-card h-100">
            <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2" style={{ borderColor: 'var(--pt-border)' }}>
              <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}><i className="bi bi-exclamation-triangle-fill text-warning me-2"></i>Erreurs</h5>
              <span className="pt-pill warning">{errorTotal} au total · {errorGroups.length} type(s)</span>
            </div>
            {/* Récapitulatif des erreurs : une ligne par code + étape + message, avec occurrences et première apparition. */}
            <div className="pt-table-wrapper">
              <table className="pt-table">
                <thead><tr><th>Code</th><th>Étape</th><th>Message</th><th className="text-end">Occurrences</th><th>Première</th></tr></thead>
                <tbody style={{ fontSize: '12.5px' }}>
                  {errorGroups.length === 0 ? (
                    <tr><td colSpan={5} className="text-center text-muted py-3">Aucune erreur</td></tr>
                  ) : errorGroups.map((e, i) => (
                    <tr key={i}>
                      <td><span className="pt-pill danger py-0 px-2" style={{ fontSize: '11px' }}>{e.httpStatus ?? '—'}</span></td>
                      <td>{e.stepName}</td>
                      <td><span className="text-truncate d-inline-block" style={{ maxWidth: '220px' }} title={e.error ?? undefined}>{e.error}</span></td>
                      <td className="text-end fw-semibold">{e.count}</td>
                      <td style={{ whiteSpace: 'nowrap' }}>{e.firstAt ? new Date(e.firstAt).toLocaleTimeString('fr-FR') : '—'}</td>
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
