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
import { Application, Execution, Scenario, Step } from '../types'
import { executionsApi } from '../services/api/executions'
import { scenariosApi } from '../services/api/scenarios'
import { applicationsApi } from '../services/api/applications'
import { stepsApi } from '../services/api/steps'
import { computePerformanceMetrics, durationToSeconds } from '../utils/metrics'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, BarElement, ArcElement, Title, Tooltip, Legend, Filler)

function ExecutionReport() {
  const { id } = useParams()

  const [execution, setExecution] = useState<Execution | null>(null)
  const [scenario, setScenario] = useState<Scenario | null>(null)
  const [application, setApplication] = useState<Application | null>(null)
  const [steps, setSteps] = useState<Step[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Même exécution réelle que ExecutionDetail (identifiée par le même ID) —
  // garantit la cohérence Scénario → Exécution → Détail → Rapport.
  useEffect(() => {
    if (!id) return
    let cancelled = false
    setLoading(true)
    executionsApi.getById(id)
      .then(async (exec) => {
        if (cancelled) return
        setExecution(exec)
        const [sc, app, stepList] = await Promise.all([
          scenariosApi.getById(exec.scenarioId).catch(() => null),
          applicationsApi.getById(exec.applicationId).catch(() => null),
          stepsApi.getByScenario(exec.scenarioId).catch(() => []),
        ])
        if (cancelled) return
        setScenario(sc)
        setApplication(app)
        setSteps(stepList)
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Exécution introuvable'))
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [id])

  const statusMeta = (() => {
    switch (execution?.status) {
      case 'Réussie': return { cls: 'success', color: 'var(--pt-success)', text: 'Réussie' }
      case 'Avec erreurs': return { cls: 'warning', color: 'var(--pt-warning)', text: 'Avec erreurs' }
      case 'Échouée': return { cls: 'danger', color: 'var(--pt-danger)', text: 'Échouée' }
      case 'En cours': return { cls: 'info', color: 'var(--pt-info)', text: 'En cours' }
      case 'Suspendue': return { cls: 'neutral', color: 'var(--pt-neutral)', text: 'Suspendue' }
      case 'Annulée': return { cls: 'neutral', color: 'var(--pt-neutral)', text: 'Annulée' }
      default: return { cls: 'neutral', color: 'var(--pt-neutral)', text: 'Inconnue' }
    }
  })()

  const stepResults = execution?.stepResults ?? []
  const stepById = new Map(steps.map((s) => [String(s.id), s]))
  const durationSeconds = execution ? durationToSeconds(execution.duration) : 0
  const perf = computePerformanceMetrics(stepResults, durationSeconds)
  const totalReq = perf.totalRequests
  const successCount = perf.successCount
  const errorResults = stepResults.filter((r) => r.status === 'error')
  const successRate = perf.successRate
  const avgResponseTime = perf.avgResponseTime
  const throughput = perf.throughput

  // Chart 1 : temps de réponse réel par étape (dans l'ordre d'exécution).
  const responseTimeData = {
    labels: stepResults.map((r) => stepById.get(String(r.stepId))?.name ?? r.stepId),
    datasets: [{
      label: 'Temps de réponse (ms)',
      data: stepResults.map((r) => r.responseTimeMs ?? 0),
      borderColor: '#4F46E5',
      backgroundColor: 'rgba(79, 70, 229, 0.12)',
      borderWidth: 2.5,
      tension: 0.3,
      fill: true,
      pointBackgroundColor: '#4F46E5',
      pointRadius: 4,
    }],
  }
  const responseTimeOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { display: false }, tooltip: { mode: 'index' as const, intersect: false } },
    scales: { y: { beginAtZero: true, grid: { color: 'rgba(0,0,0,0.05)' } }, x: { grid: { display: false } } },
  }

  // Chart 2 : répartition réelle des codes HTTP retournés.
  const httpCodeCounts = new Map<number, number>()
  stepResults.forEach((r) => {
    if (r.httpStatus) httpCodeCounts.set(r.httpStatus, (httpCodeCounts.get(r.httpStatus) ?? 0) + 1)
  })
  const httpCodeEntries = Array.from(httpCodeCounts.entries()).sort((a, b) => a[0] - b[0])
  const httpCodeChartData = {
    labels: httpCodeEntries.map(([code]) => `HTTP ${code}`),
    datasets: [{
      label: 'Nombre de requêtes',
      data: httpCodeEntries.map(([, count]) => count),
      backgroundColor: httpCodeEntries.map(([code]) => (code < 400 ? '#22C55E' : code < 500 ? '#F59E0B' : '#EF4444')),
      borderRadius: 6,
    }],
  }
  const httpCodeChartOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: { x: { grid: { display: false } }, y: { grid: { color: 'rgba(0,0,0,0.05)' }, ticks: { precision: 0 } } },
  }

  // Chart 3 : répartition réelle succès / erreurs.
  const statusDistributionData = {
    labels: [`Réussies (${successCount})`, `En erreur (${errorResults.length})`],
    datasets: [{ data: [successCount, errorResults.length], backgroundColor: ['#22C55E', '#EF4444'], borderWidth: 2, borderColor: '#ffffff' }],
  }
  const statusDistributionOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { position: 'bottom' as const, labels: { usePointStyle: true, boxWidth: 8, font: { size: 11 } } } },
    cutout: '70%',
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

  return (
    <div className="pt-content">
      <div className="mb-3">
        <Link to="/executions" className="text-decoration-none d-inline-flex align-items-center gap-1 text-muted small fw-semibold">
          <i className="bi bi-chevron-left"></i>
          Retour aux exécutions
        </Link>
      </div>

      <div className="pt-page-header">
        <div className="page-title">
          <div className="d-flex align-items-center gap-3 flex-wrap">
            <h1>Rapport exécution #{id}</h1>
            <span className={`pt-pill ${statusMeta.cls}`}>
              <span style={{ width: '6px', height: '6px', borderRadius: '50%', backgroundColor: statusMeta.color, display: 'inline-block' }}></span>
              {statusMeta.text}
            </span>
          </div>
          <p>Exécution lancée le {new Date(execution.startedAt).toLocaleString('fr-FR')}</p>
        </div>
        <div className="d-flex align-items-center gap-2">
          <button className="pt-btn-outline" onClick={() => alert('Exportation du rapport au format PDF...')}>
            <i className="bi bi-file-earmark-pdf text-danger"></i>
            Export PDF
          </button>
          <button className="pt-btn-primary" onClick={() => alert(`Téléchargement du rapport d'exécution #${id}...`)}>
            <i className="bi bi-download"></i>
            Télécharger le rapport
          </button>
          <TopBar searchPlaceholder="" />
        </div>
      </div>

      {/* Metadata Badges Bar */}
      <div className="pt-card mb-4 py-2 px-3">
        <div className="row g-2 align-items-center text-muted" style={{ fontSize: '13px' }}>
          <div className="col-12 col-md-auto d-flex align-items-center gap-2 me-3">
            <i className="bi bi-collection-play text-primary"></i>
            <span>Scénario: <Link to={`/scenarios?q=${encodeURIComponent(scenario?.name ?? '')}`} className="text-dark fw-bold text-decoration-none">{scenario?.name ?? '—'}</Link></span>
          </div>
          <div className="col-12 col-md-auto d-flex align-items-center gap-2 me-3">
            <i className="bi bi-globe2 text-primary"></i>
            <span>Application: <Link to={`/scenarios?app=${encodeURIComponent(application?.name ?? '')}`} className="text-dark fw-bold text-decoration-none">{application?.name ?? '—'}</Link></span>
          </div>
        </div>
      </div>

      {/* 5 Summary Cards — toutes calculées depuis les vraies données */}
      <div className="row g-3 mb-4">
        <div className="col-12 col-sm-6 col-lg">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Utilisateurs virtuels</div>
                <div className="stat-value">{execution.users.replace(' VUs', '')}</div>
              </div>
              <div className="stat-icon blue"><i className="bi bi-people"></i></div>
            </div>
          </div>
        </div>
        <div className="col-12 col-sm-6 col-lg">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Statut</div>
                <div className="stat-value" style={{ fontSize: '18px', marginTop: '4px', color: statusMeta.color }}>{statusMeta.text}</div>
              </div>
              <div className="stat-icon green"><i className="bi bi-check-circle"></i></div>
            </div>
          </div>
        </div>
        <div className="col-12 col-sm-6 col-lg">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Durée</div>
                <div className="stat-value">{execution.duration}</div>
                <div className="stat-trend neutral">{durationSeconds} secondes</div>
              </div>
              <div className="stat-icon purple"><i className="bi bi-stopwatch"></i></div>
            </div>
          </div>
        </div>
        <div className="col-12 col-sm-6 col-lg">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Requêtes</div>
                <div className="stat-value">{totalReq}</div>
                <div className="stat-trend positive">{successRate.toFixed(1)}% succès</div>
              </div>
              <div className="stat-icon orange"><i className="bi bi-arrow-repeat"></i></div>
            </div>
          </div>
        </div>
        <div className="col-12 col-sm-6 col-lg">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Débit</div>
                <div className="stat-value">{throughput.toFixed(2)}</div>
                <div className="stat-trend positive">req / s</div>
              </div>
              <div className="stat-icon blue"><i className="bi bi-speedometer"></i></div>
            </div>
          </div>
        </div>
      </div>

      {/* 2 Charts Grid */}
      <div className="row g-4 mb-4">
        <div className="col-12 col-xl-6">
          <div className="pt-card h-100">
            <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2" style={{ borderColor: 'var(--pt-border)' }}>
              <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}>
                <i className="bi bi-clock-history text-primary me-2"></i>
                Temps de réponse par étape
              </h5>
              <span className="badge bg-light text-dark border">{totalReq} requêtes réelles</span>
            </div>
            <div style={{ height: '280px' }}>
              <Line data={responseTimeData} options={responseTimeOptions} />
            </div>
          </div>
        </div>

        <div className="col-12 col-xl-6">
          <div className="pt-card h-100">
            <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2" style={{ borderColor: 'var(--pt-border)' }}>
              <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}>
                <i className="bi bi-bar-chart text-primary me-2"></i>
                Codes HTTP retournés
              </h5>
            </div>
            <div style={{ height: '280px' }}>
              <Bar data={httpCodeChartData} options={httpCodeChartOptions} />
            </div>
          </div>
        </div>
      </div>

      {/* Bottom 3 Columns Layout */}
      <div className="row g-4 mb-4">
        <div className="col-12 col-xl-4">
          <div className="pt-card h-100 d-flex flex-column justify-content-between">
            <div>
              <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2" style={{ borderColor: 'var(--pt-border)' }}>
                <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}>
                  <i className="bi bi-pie-chart-fill text-primary me-2"></i>
                  Répartition des réponses
                </h5>
                <span className="pt-pill success">{totalReq} req</span>
              </div>
              <div style={{ height: '220px', position: 'relative' }}>
                <Doughnut data={statusDistributionData} options={statusDistributionOptions} />
              </div>
            </div>
            <div className="mt-3 pt-3 border-top d-flex justify-content-around text-center" style={{ borderColor: 'var(--pt-border)', fontSize: '12px' }}>
              <div>
                <span className="text-muted d-block">Réussies</span>
                <strong className="text-success">{successCount} ({successRate.toFixed(1)}%)</strong>
              </div>
              <div>
                <span className="text-muted d-block">En erreur</span>
                <strong className="text-danger">{errorResults.length} ({(100 - successRate).toFixed(1)}%)</strong>
              </div>
            </div>
          </div>
        </div>

        <div className="col-12 col-xl-4">
          <div className="pt-card h-100">
            <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2" style={{ borderColor: 'var(--pt-border)' }}>
              <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}>
                <i className="bi bi-table text-primary me-2"></i>
                Résultat par étape
              </h5>
            </div>
            <div className="pt-table-wrapper">
              <table className="pt-table">
                <thead>
                  <tr><th>Étape</th><th>Temps</th><th>Code</th></tr>
                </thead>
                <tbody style={{ fontSize: '12.5px' }}>
                  {stepResults.length === 0 ? (
                    <tr><td colSpan={3} className="text-center text-muted py-3">Aucun résultat</td></tr>
                  ) : stepResults.map((r, i) => (
                    <tr key={i}>
                      <td><span className="fw-semibold text-dark">{stepById.get(String(r.stepId))?.name ?? r.stepId}</span></td>
                      <td>{r.responseTimeMs}ms</td>
                      <td><span className={r.status === 'success' ? 'text-success' : 'text-danger'}>{r.httpStatus}</span></td>
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
              <h5 className="m-0 fw-bold" style={{ fontSize: '15px', color: 'var(--pt-text)' }}>
                <i className="bi bi-exclamation-triangle-fill text-warning me-2"></i>
                Erreurs
              </h5>
              <span className="pt-pill warning">{errorResults.length} total</span>
            </div>
            <div className="pt-table-wrapper">
              <table className="pt-table">
                <thead>
                  <tr><th>Code</th><th>Étape</th><th>Message</th></tr>
                </thead>
                <tbody style={{ fontSize: '12.5px' }}>
                  {errorResults.length === 0 ? (
                    <tr><td colSpan={3} className="text-center text-muted py-3">Aucune erreur 🎉</td></tr>
                  ) : errorResults.map((r, i) => (
                    <tr key={i}>
                      <td><span className="pt-pill danger py-0 px-2" style={{ fontSize: '11px', backgroundColor: '#FEE2E2', color: '#DC2626' }}>{r.httpStatus}</span></td>
                      <td>{stepById.get(String(r.stepId))?.name ?? r.stepId}</td>
                      <td>
                        <span className="text-truncate d-inline-block" style={{ maxWidth: '140px' }} title={r.error}>
                          {r.error}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      {/* Footer export banner */}
      <div className="pt-card d-flex align-items-center justify-content-between flex-wrap gap-3 py-3">
        <div className="d-flex align-items-center gap-3">
          <div className="stat-icon green" style={{ width: '40px', height: '40px', fontSize: '18px' }}>
            <i className="bi bi-file-earmark-check"></i>
          </div>
          <div>
            <div className="fw-bold" style={{ fontSize: '14px', color: 'var(--pt-text)' }}>
              Rapport d'exécution certifié et prêt pour export
            </div>
            <div className="text-muted" style={{ fontSize: '12px' }}>
              Conservez une copie archivée de vos métriques de performance.
            </div>
          </div>
        </div>
        <div className="d-flex align-items-center gap-2">
          <button className="pt-btn-primary" onClick={() => alert(`Téléchargement du rapport d'exécution #${id}...`)}>
            <i className="bi bi-download"></i>
            Télécharger le rapport
          </button>
        </div>
      </div>
    </div>
  )
}

export default ExecutionReport
