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
import { Application, Execution, Scenario, Step, StepResult } from '../types'
import { executionsApi } from '../services/api/executions'
import { scenariosApi } from '../services/api/scenarios'
import { useAuth } from '../context/AuthContext'
import { applicationsApi } from '../services/api/applications'
import { stepsApi } from '../services/api/steps'
import { setExecutionPrefill } from '../utils/executionBridge'
import { computeMetricsByStep, computePerformanceMetrics, durationToSeconds } from '../utils/metrics'

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

function ExecutionDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { canEdit } = useAuth()
  const [activeTab, setActiveTab] = useState('Vue ensemble')
  // Vue "un VU à la fois" pour la chronologie/résultat/logs — avec
  // plusieurs VUs, la même étape (ex. "login") revient une fois par VU dans
  // une liste plate, vite illisible au-delà de quelques VUs (voir
  // LaunchScenarioModal, même problème déjà résolu pour l'exécution en
  // direct). Les métriques agrégées (KPIs, Métriques par étape, graphiques)
  // restent, elles, toujours calculées sur TOUS les VUs.
  const [selectedVu, setSelectedVu] = useState<number | 'all'>('all')

  const [execution, setExecution] = useState<Execution | null>(null)
  const [scenario, setScenario] = useState<Scenario | null>(null)
  const [application, setApplication] = useState<Application | null>(null)
  const [steps, setSteps] = useState<Step[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Récupère la VRAIE exécution correspondant à l'ID de la route sur JSON
  // Server, ainsi que son scénario, son application et ses étapes réelles —
  // chaque exécution affiche donc ses propres données, jamais les mêmes.
  useEffect(() => {
    if (!id) return
    let cancelled = false
    setLoading(true)
    setError(null)
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

  // Dès qu'une exécution multi-VUs est chargée, on démarre sur le VU 1
  // plutôt que "Tous" — c'est justement le mode "tous les VUs mélangés"
  // qui rend la chronologie illisible avec plusieurs utilisateurs virtuels.
  // Une seule exécution (mono-VU) reste sur "Tous" (revient au même).
  useEffect(() => {
    if (!execution) return
    const vus = new Set<number>()
    execution.stepResults.forEach((r) => { if (r.vu !== undefined) vus.add(r.vu) })
    setSelectedVu(vus.size > 1 ? Math.min(...vus) : 'all')
  }, [execution])

  const statusBadge = (() => {
    switch (execution?.status) {
      case 'Réussie': return { cls: 'success', icon: 'bi-check-circle-fill', text: 'Terminée avec succès' }
      case 'Avec erreurs': return { cls: 'warning', icon: 'bi-exclamation-triangle-fill', text: 'Terminée avec erreurs' }
      case 'Échouée': return { cls: 'danger', icon: 'bi-x-circle-fill', text: 'Échouée' }
      case 'En cours': return { cls: 'info', icon: 'bi-play-circle-fill', text: 'En cours' }
      case 'Suspendue': return { cls: 'neutral', icon: 'bi-pause-circle-fill', text: 'Suspendue' }
      case 'Annulée': return { cls: 'neutral', icon: 'bi-slash-circle-fill', text: 'Annulée' }
      default: return { cls: 'neutral', icon: 'bi-question-circle-fill', text: 'Statut inconnu' }
    }
  })()

  const handleRelaunch = () => {
    if (!canEdit) return
    if (scenario && application) {
      setExecutionPrefill({ scenario: scenario.name, application: application.name })
    }
    navigate('/executions')
  }

  const stepResults = execution?.stepResults ?? []

  // VUs réellement présents dans cette exécution, pour le sélecteur —
  // seules la chronologie, le tableau "Résultat de chaque étape" et les
  // logs sont filtrés par VU ; les KPIs/Métriques par étape/graphiques
  // restent toujours calculés sur l'ensemble des VUs.
  const vuNumbers = Array.from(new Set(stepResults.filter((r) => r.vu !== undefined).map((r) => r.vu!))).sort((a, b) => a - b)
  const vuFilteredResults = selectedVu === 'all' ? stepResults : stepResults.filter((r) => r.vu === selectedVu)
  const vuStatusFor = (vu: number): 'error' | 'success' => {
    const forVu = stepResults.filter((r) => r.vu === vu)
    return forVu.some((r) => r.status === 'error') ? 'error' : 'success'
  }

  // Étapes réellement envoyées — exclut les 'skipped' (Étape active
  // décochée) des graphiques de temps de réponse, où un 0ms serait trompeur
  // (laisserait croire à une requête ultra-rapide plutôt qu'à une absence
  // de requête).
  const sentStepResults = stepResults.filter((r) => r.status !== 'skipped')
  const stepById = new Map(steps.map((s) => [String(s.id), s]))
  const totalDurationSec = execution ? durationToSeconds(execution.duration) : 0
  const perf = computePerformanceMetrics(stepResults, totalDurationSec)
  const totalReq = perf.totalRequests
  const successCount = perf.successCount
  const errorCount = perf.errorCount
  const successRate = perf.successRate
  const avgResponseTime = perf.avgResponseTime

  // Métriques calculées séparément pour CHAQUE étape (jamais les métriques
  // globales du scénario recopiées) — voir computeMetricsByStep.
  const stepMetrics = computeMetricsByStep(sentStepResults, totalDurationSec)

  const kpis = [
    { label: 'Durée totale', value: execution?.duration ?? '—', icon: 'bi-clock-history', color: 'blue' },
    { label: 'Utilisateurs virtuels', value: execution?.users ?? '—', icon: 'bi-people', color: 'purple' },
    { label: 'Requêtes totales', value: String(totalReq), icon: 'bi-arrow-repeat', color: 'blue' },
    { label: 'Taux réussite', value: `${successRate.toFixed(1)}%`, icon: 'bi-check-circle', color: 'green' },
    { label: 'Taux erreur', value: `${perf.errorRate.toFixed(1)}%`, icon: 'bi-exclamation-triangle', color: 'red' },
    { label: 'Moyenne', value: `${avgResponseTime} ms`, icon: 'bi-lightning-charge', color: 'orange' },
    { label: 'Min', value: `${perf.minResponseTime} ms`, icon: 'bi-arrow-down-circle', color: 'green' },
    { label: 'Max', value: `${perf.maxResponseTime} ms`, icon: 'bi-arrow-up-circle', color: 'red' },
    { label: 'Débit', value: `${perf.throughput.toFixed(2)} req/s`, icon: 'bi-speedometer2', color: 'blue' },
  ]

  // Line Chart : temps de réponse RÉEL, étape par étape (dans l'ordre
  // d'exécution) — remplace toute télémétrie CPU/RAM fictive.
  const lineChartData = {
    labels: sentStepResults.map((r) => stepById.get(String(r.stepId))?.name ?? r.stepId),
    datasets: [
      {
        label: 'Temps de réponse (ms)',
        data: sentStepResults.map((r) => r.responseTimeMs ?? 0),
        borderColor: '#4F46E5',
        backgroundColor: 'rgba(79, 70, 229, 0.1)',
        fill: true,
        tension: 0.3,
      },
    ],
  }
  const lineChartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: {
      x: { grid: { display: false } },
      y: { title: { display: true, text: 'ms' }, grid: { color: 'rgba(0,0,0,0.05)' } },
    },
  }

  // Doughnut Chart : répartition réelle succès/erreurs
  const doughnutData = {
    labels: [`Succès (${successCount})`, `Erreurs (${errorCount})`],
    datasets: [{ data: [successCount, errorCount], backgroundColor: ['#22C55E', '#EF4444'], hoverOffset: 4, borderWidth: 0 }],
  }
  const doughnutOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { position: 'bottom' as const, labels: { boxWidth: 12 } } },
    cutout: '70%',
  }

  // Bar Chart : répartition réelle des temps de réponse par tranche
  const buckets = [
    { label: '< 100ms', min: 0, max: 100 },
    { label: '100-300ms', min: 100, max: 300 },
    { label: '300-600ms', min: 300, max: 600 },
    { label: '600ms-1s', min: 600, max: 1000 },
    { label: '> 1s', min: 1000, max: Infinity },
  ]
  const barChartData = {
    labels: buckets.map((b) => b.label),
    datasets: [{
      label: 'Nombre de requêtes',
      data: buckets.map((b) => sentStepResults.filter((r) => (r.responseTimeMs ?? 0) >= b.min && (r.responseTimeMs ?? 0) < b.max).length),
      backgroundColor: '#4F46E5',
      borderRadius: 6,
    }],
  }
  const barChartOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: { x: { grid: { display: false } }, y: { grid: { color: 'rgba(0,0,0,0.05)' }, ticks: { precision: 0 } } },
  }

  // Chronologie & logs : construits à partir des vrais résultats d'étapes,
  // filtrés au VU sélectionné (voir vuFilteredResults). Chaque VU ne rejoue
  // le scénario qu'une seule fois (voir useScenarioLauncher) — le tag [VU N]
  // en mode "Tous" suffit donc à identifier chaque ligne sans ambiguïté.
  const vuLabel = (r: StepResult) => (selectedVu === 'all' && r.vu !== undefined ? ` [VU ${r.vu + 1}]` : '')
  const timelineEvents = vuFilteredResults.map((r) => {
    const step = stepById.get(String(r.stepId))
    return {
      title: `${step?.name ?? r.stepId}${vuLabel(r)}`,
      desc: r.status === 'success'
        ? `${r.request?.method} ${r.request?.url} → ${r.httpStatus} (${r.responseTimeMs} ms)`
        : r.status === 'skipped'
        ? 'Étape ignorée (marquée inactive) — aucune requête envoyée.'
        : (r.error ?? `Échec sur ${r.request?.method} ${r.request?.url}`),
      type: r.status === 'success' ? 'success' : r.status === 'skipped' ? 'skipped' : 'warning',
    }
  })

  const recentLogs = vuFilteredResults.map((r, i) => {
    const step = stepById.get(String(r.stepId))
    return {
      time: `#${i + 1}`,
      level: r.status === 'success' ? 'INFO' : r.status === 'skipped' ? 'SKIP' : 'ERROR',
      message: r.status === 'success'
        ? `${r.request?.method} ${r.request?.url} → ${r.httpStatus} ${r.response?.statusText} en ${r.responseTimeMs}ms (${step?.name}${vuLabel(r)})`
        : r.status === 'skipped'
        ? `Étape ignorée (inactive) — ${step?.name}${vuLabel(r)}`
        : `${r.error} — ${r.request?.method} ${r.request?.url}${vuLabel(r)}`,
    }
  }).reverse()

  const tabs = ['Vue ensemble', 'Graphiques', 'Étapes & Requêtes', 'Logs']

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

  return (
    <div className="pt-content">
      <div className="d-flex align-items-center justify-content-between mb-3 flex-wrap gap-2">
        <nav aria-label="breadcrumb">
          <ol className="breadcrumb mb-0" style={{ fontSize: '13px' }}>
            <li className="breadcrumb-item">
              <Link to="/executions" className="text-decoration-none text-muted">Exécutions</Link>
            </li>
            <li className="breadcrumb-item active text-primary fw-semibold" aria-current="page">
              Détail exécution #{id}
            </li>
          </ol>
        </nav>
        <TopBar searchPlaceholder="Rechercher dans l'exécution..." />
      </div>

      <div className="pt-page-header">
        <div className="page-title">
          <div className="d-flex align-items-center gap-3 flex-wrap">
            <h1>Exécution #{id} — {application?.name ?? 'Application inconnue'}</h1>
            <span className={`pt-pill ${statusBadge.cls}`}>
              <i className={`bi ${statusBadge.icon}`}></i> {statusBadge.text}
            </span>
          </div>
          <p>
            Scénario <Link to={`/scenarios?q=${encodeURIComponent(scenario?.name ?? '')}`} className="text-decoration-none">{scenario?.name ?? '—'}</Link>
            {' '}— lancée le {new Date(execution.startedAt).toLocaleString('fr-FR')}
          </p>
        </div>
        <div className="header-actions">
          {canEdit && (
            <button className="pt-btn-primary" onClick={handleRelaunch}>
              <i className="bi bi-arrow-repeat"></i> Relancer le test
            </button>
          )}
        </div>
      </div>

      {/* Résumé global : statut du scénario + nombre d'étapes réussies/échouées,
          visible d'un coup d'œil avant le détail KPI/étape par étape. */}
      <div className="pt-card mb-3 py-3 px-3 d-flex align-items-center flex-wrap gap-3">
        <span className={`pt-pill ${statusBadge.cls}`} style={{ fontSize: '13px' }}>
          <i className={`bi ${statusBadge.icon}`}></i> Scénario {statusBadge.text}
        </span>
        <span className="pt-pill success" style={{ fontSize: '13px' }}>
          <i className="bi bi-check-circle-fill"></i> {successCount} étape(s) réussie(s)
        </span>
        <span className="pt-pill danger" style={{ fontSize: '13px' }}>
          <i className="bi bi-x-circle-fill"></i> {errorCount} étape(s) échouée(s)
        </span>
      </div>

      <div className="d-flex align-items-center gap-2 mb-2" style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-speedometer2" style={{ color: 'var(--pt-primary)' }}></i> PERFORMANCE
      </div>
      <div className="row g-3 mb-4">
        {kpis.map((kpi, idx) => (
          <div className="col-12 col-sm-6 col-md-4 col-xl-2" key={idx}>
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

      <div className="row g-3 mb-4">
        <div className="col-12 col-lg-6">
          <div className="pt-card h-100">
            <div className="pt-card-title d-flex align-items-center justify-content-between">
              <span><i className="bi bi-globe2 me-2 text-primary"></i>Application testée</span>
            </div>
            <div className="mt-3">
              <div className="d-flex justify-content-between py-2 border-bottom">
                <span className="text-muted">Nom</span>
                <span className="fw-semibold">{application?.name ?? '—'}</span>
              </div>
              <div className="d-flex justify-content-between py-2 border-bottom">
                <span className="text-muted">URL cible</span>
                <a href={application?.url} target="_blank" rel="noreferrer" className="text-primary text-decoration-none fw-semibold">
                  {application?.url} <i className="bi bi-box-arrow-up-right ms-1" style={{ fontSize: '11px' }}></i>
                </a>
              </div>
              <div className="d-flex justify-content-between py-2 border-bottom">
                <span className="text-muted">Type</span>
                <span className="fw-semibold">{application?.type ?? '—'}</span>
              </div>
              <div className="d-flex justify-content-between py-2">
                <span className="text-muted">Authentification</span>
                <span className="fw-semibold">{application?.authMethod ?? '—'}</span>
              </div>
            </div>
          </div>
        </div>

        <div className="col-12 col-lg-6">
          <div className="pt-card h-100">
            <div className="pt-card-title d-flex align-items-center justify-content-between">
              <span><i className="bi bi-diagram-3 me-2 text-primary"></i>Paramètres du scénario</span>
            </div>
            <div className="mt-3">
              <div className="d-flex justify-content-between py-2 border-bottom">
                <span className="text-muted">Scénario</span>
                <span className="fw-semibold">{scenario?.name ?? '—'}</span>
              </div>
              <div className="d-flex justify-content-between py-2 border-bottom">
                <span className="text-muted">Description</span>
                <span className="fw-semibold text-end" style={{ maxWidth: '60%' }}>{scenario?.description ?? '—'}</span>
              </div>
              <div className="d-flex justify-content-between py-2 border-bottom">
                <span className="text-muted">Utilisateurs virtuels</span>
                <span className="fw-semibold">{execution.users}</span>
              </div>
              <div className="d-flex justify-content-between py-2">
                <span className="text-muted">Nombre d'étapes</span>
                <span className="fw-semibold">{steps.length}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {vuNumbers.length > 1 && (
        <div className="pt-card mb-3 py-2 px-3">
          <div className="d-flex align-items-center gap-2 flex-wrap">
            <span style={{ fontSize: '11px', color: 'var(--pt-text-muted)', fontWeight: 700, textTransform: 'uppercase' }}>
              Chronologie / Résultats / Logs — filtrer par VU
            </span>
            <button
              onClick={() => setSelectedVu('all')}
              style={{
                padding: '3px 10px', borderRadius: '20px', fontSize: '11.5px', fontWeight: 600, cursor: 'pointer',
                border: `1px solid ${selectedVu === 'all' ? 'var(--pt-primary)' : 'var(--pt-border)'}`,
                background: selectedVu === 'all' ? 'var(--pt-primary-light)' : 'var(--pt-card-bg)',
                color: selectedVu === 'all' ? 'var(--pt-primary)' : 'var(--pt-text-muted)',
              }}
            >
              Tous
            </button>
            {vuNumbers.map((vu) => (
              <button
                key={vu}
                onClick={() => setSelectedVu(vu)}
                title={`Utilisateur virtuel ${vu + 1}`}
                style={{
                  display: 'flex', alignItems: 'center', gap: '5px',
                  padding: '3px 10px', borderRadius: '20px', fontSize: '11.5px', fontWeight: 600, cursor: 'pointer',
                  border: `1px solid ${vu === selectedVu ? 'var(--pt-primary)' : 'var(--pt-border)'}`,
                  background: vu === selectedVu ? 'var(--pt-primary-light)' : 'var(--pt-card-bg)',
                  color: vu === selectedVu ? 'var(--pt-primary)' : 'var(--pt-text-muted)',
                }}
              >
                <span style={{ width: '7px', height: '7px', borderRadius: '50%', background: vuStatusFor(vu) === 'error' ? 'var(--pt-danger)' : 'var(--pt-success)', flexShrink: 0 }}></span>
                VU {vu + 1}
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="pt-card mb-4">
        <div className="pt-card-title mb-3">
          <i className="bi bi-hourglass-split me-2 text-primary"></i>
          Chronologie des étapes exécutées
        </div>
        {timelineEvents.length === 0 ? (
          <p className="text-muted mb-0" style={{ fontSize: '13px' }}>Aucune étape exécutée pour le moment.</p>
        ) : (
          <div style={{ position: 'relative', paddingLeft: '26px' }}>
            <div style={{ position: 'absolute', left: '8px', top: '6px', bottom: '6px', width: '2px', background: 'var(--pt-border)' }}></div>
            {timelineEvents.map((evt, idx) => {
              const dotColor = evt.type === 'success' ? 'var(--pt-success)' : evt.type === 'skipped' ? 'var(--pt-text-light)' : 'var(--pt-warning)'
              const dotIcon = evt.type === 'success' ? 'bi-check' : evt.type === 'skipped' ? 'bi-dash' : 'bi-exclamation'
              return (
                <div key={idx} className="d-flex align-items-baseline flex-wrap" style={{ position: 'relative', gap: '6px 10px', padding: '5px 0' }}>
                  <span style={{
                    position: 'absolute', left: '-26px', top: '6px', width: '17px', height: '17px', borderRadius: '50%',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    background: dotColor, color: 'white', fontSize: '10px', flexShrink: 0,
                  }}>
                    <i className={`bi ${dotIcon}`}></i>
                  </span>
                  <span className="fw-semibold" style={{ fontSize: '12.5px' }}>{evt.title}</span>
                  <span className="text-muted font-monospace" style={{ fontSize: '11px', wordBreak: 'break-word' }}>{evt.desc}</span>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* Métriques par étape — chaque étape a ses propres métriques réelles
          (calculées uniquement sur ses propres résultats), jamais les
          métriques globales du scénario recopiées pour chaque ligne. */}
      <div className="pt-card mb-4">
        <div className="pt-card-title mb-3">
          <i className="bi bi-list-ol me-2 text-primary"></i>
          Métriques par étape
        </div>
        {stepMetrics.length === 0 ? (
          <p className="text-muted mb-0" style={{ fontSize: '13px' }}>Aucune requête envoyée pour le moment.</p>
        ) : (
          <div className="pt-table-wrapper">
            <table className="pt-table">
              <thead>
                <tr>
                  <th>Étape</th>
                  <th>Requêtes</th>
                  <th>Taux réussite</th>
                  <th>Moyenne</th>
                  <th>Débit</th>
                  <th>CPU moyen</th>
                  <th>RAM moyenne</th>
                </tr>
              </thead>
              <tbody>
                {stepMetrics.map(({ stepId, metrics: m, avgCpu, avgRam }) => (
                  <tr key={stepId}>
                    <td className="fw-semibold">{stepById.get(stepId)?.name ?? stepId}</td>
                    <td>{m.totalRequests}</td>
                    <td>
                      <span className={`pt-pill ${m.successRate === 100 ? 'success' : m.successRate > 0 ? 'warning' : 'danger'}`}>
                        {m.successRate.toFixed(1)}%
                      </span>
                    </td>
                    <td>{m.avgResponseTime} ms</td>
                    <td>{m.throughput.toFixed(2)} req/s</td>
                    <td>{avgCpu != null ? `${avgCpu}%` : '—'}</td>
                    <td>{avgRam != null ? `${avgRam}%` : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="border-bottom mb-4">
        <ul className="nav nav-tabs border-0" style={{ gap: '0.5rem' }}>
          {tabs.map((tab) => (
            <li className="nav-item" key={tab}>
              <button
                className={`nav-link border-0 fw-medium px-3 py-2 ${activeTab === tab ? 'active fw-semibold' : 'text-muted'}`}
                style={{ background: 'transparent', borderBottom: activeTab === tab ? '3px solid var(--pt-primary)' : 'none', borderRadius: 0, fontSize: '13.5px', color: activeTab === tab ? 'var(--pt-primary)' : undefined }}
                onClick={() => setActiveTab(tab)}
              >
                {tab}
              </button>
            </li>
          ))}
        </ul>
      </div>

      {(activeTab === 'Vue ensemble' || activeTab === 'Graphiques') && (
        <div className="row g-3 mb-4">
          <div className="col-12 col-xl-6">
            <div className="pt-card h-100">
              <div className="pt-card-title d-flex justify-content-between align-items-center mb-3">
                <span><i className="bi bi-graph-up me-2 text-primary"></i>Temps de réponse par étape</span>
              </div>
              <div style={{ height: '280px' }}><Line data={lineChartData} options={lineChartOptions} /></div>
            </div>
          </div>
          <div className="col-12 col-md-6 col-xl-3">
            <div className="pt-card h-100">
              <div className="pt-card-title mb-3"><i className="bi bi-pie-chart me-2 text-success"></i>Taux de réussite</div>
              <div style={{ height: '240px', position: 'relative' }}><Doughnut data={doughnutData} options={doughnutOptions} /></div>
              <div className="text-center mt-2">
                <div className="fw-bold text-success" style={{ fontSize: '18px' }}>{successRate.toFixed(1)}% Succès</div>
                <small className="text-muted">{successCount} / {totalReq} requêtes</small>
              </div>
            </div>
          </div>
          <div className="col-12 col-md-6 col-xl-3">
            <div className="pt-card h-100">
              <div className="pt-card-title mb-3"><i className="bi bi-bar-chart me-2 text-primary"></i>Répartition des temps de réponse</div>
              <div style={{ height: '280px' }}><Bar data={barChartData} options={barChartOptions} /></div>
            </div>
          </div>
        </div>
      )}

      {(activeTab === 'Vue ensemble' || activeTab === 'Étapes & Requêtes') && (
        <div className="row g-3">
          <div className="col-12">
            <div className="pt-card">
              <div className="d-flex align-items-center justify-content-between mb-3">
                <div className="pt-card-title mb-0"><i className="bi bi-list-check me-2 text-primary"></i>Résultat de chaque étape</div>
              </div>
              <div className="pt-table-wrapper">
                <table className="pt-table">
                  <thead>
                    <tr>
                      <th>Méthode & Endpoint</th>
                      <th>Temps de réponse</th>
                      <th>Code HTTP</th>
                      <th>Statut</th>
                      <th>Détail requête / réponse</th>
                    </tr>
                  </thead>
                  <tbody>
                    {vuFilteredResults.length === 0 ? (
                      <tr><td colSpan={5} className="text-center text-muted py-3">Aucun résultat pour cette exécution.</td></tr>
                    ) : vuFilteredResults.map((r, i) => {
                      const step = stepById.get(String(r.stepId))
                      if (r.status === 'skipped') {
                        return (
                          <tr key={i} style={{ opacity: 0.65 }}>
                            <td>
                              <span className="font-monospace fw-medium" style={{ fontSize: '13px' }}>{step?.name ?? r.stepId}</span>
                              {selectedVu === 'all' && r.vu !== undefined && <span className="pt-pill neutral ms-2" style={{ fontSize: '10px' }}>VU {r.vu + 1}</span>}
                              <div className="text-muted" style={{ fontSize: '11px' }}>Étape inactive — aucune requête envoyée</div>
                            </td>
                            <td>—</td>
                            <td>—</td>
                            <td><span className="pt-pill neutral">Ignorée</span></td>
                            <td></td>
                          </tr>
                        )
                      }
                      return (
                        <tr key={i}>
                          <td>
                            <span className={`badge me-2 ${r.request?.method === 'GET' ? 'bg-primary-subtle text-primary' : 'bg-success-subtle text-success'}`} style={{ minWidth: '45px' }}>
                              {r.request?.method}
                            </span>
                            <span className="font-monospace fw-medium" style={{ fontSize: '13px' }}>{r.request?.url}</span>
                            {selectedVu === 'all' && r.vu !== undefined && <span className="pt-pill neutral ms-2" style={{ fontSize: '10px' }}>VU {r.vu + 1}</span>}
                            <div className="text-muted" style={{ fontSize: '11px' }}>{step?.name}</div>
                          </td>
                          <td className={r.status === 'error' ? 'fw-bold text-danger' : 'fw-bold'}>{r.responseTimeMs} ms</td>
                          <td><span className="badge bg-light text-dark border">{r.httpStatus}</span></td>
                          <td>
                            <span className={`pt-pill ${r.status === 'success' ? 'success' : 'danger'}`}>
                              <i className={`bi ${r.status === 'success' ? 'bi-check-circle-fill' : 'bi-x-circle-fill'} me-1`}></i>
                              {r.status === 'success' ? 'Réussi' : 'Échec'}
                            </span>
                          </td>
                          <td>
                            {r.request?.body && (
                              <details>
                                <summary style={{ cursor: 'pointer', fontSize: '11.5px', color: 'var(--pt-primary)' }}>Body requête</summary>
                                <pre style={{ fontSize: '10.5px', background: 'var(--pt-bg)', padding: '4px 6px', borderRadius: '4px', marginTop: '4px' }}>{r.request.body}</pre>
                              </details>
                            )}
                            {r.response && (
                              <details>
                                <summary style={{ cursor: 'pointer', fontSize: '11.5px', color: 'var(--pt-primary)' }}>Réponse</summary>
                                <div style={{ fontSize: '10.5px', background: 'var(--pt-bg)', padding: '6px 8px', borderRadius: '4px', marginTop: '4px' }}>
                                  <div style={{ marginBottom: '4px' }}>
                                    <strong>Code HTTP :</strong> {r.httpStatus} {r.response.statusText}
                                  </div>
                                  <div style={{ marginBottom: '4px' }}>
                                    <strong>Temps de réponse :</strong> {r.responseTimeMs} ms
                                  </div>
                                  {r.response.headers && Object.keys(r.response.headers).length > 0 && (
                                    <div style={{ marginBottom: '4px' }}>
                                      <strong>Response Headers :</strong>
                                      <pre style={{ margin: '2px 0 0', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                                        {Object.entries(r.response.headers).map(([k, v]) => `${k}: ${v}`).join('\n')}
                                      </pre>
                                    </div>
                                  )}
                                  {r.response.body && (
                                    <div>
                                      <strong>Response Body :</strong>
                                      <pre style={{ margin: '2px 0 0', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                                        {formatResponseBody(r.response.body)}
                                      </pre>
                                    </div>
                                  )}
                                </div>
                              </details>
                            )}
                            {r.error && <div className="text-danger" style={{ fontSize: '11.5px' }}>{r.error}</div>}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      )}

      {(activeTab === 'Vue ensemble' || activeTab === 'Logs') && (
        <div className="row g-3 mt-1">
          <div className="col-12">
            <div className="pt-card">
              <div className="pt-card-title mb-3"><i className="bi bi-terminal me-2 text-info"></i>Logs de l'exécution</div>
              <div style={{ maxHeight: '320px', overflowY: 'auto', fontFamily: 'monospace', fontSize: '12px', background: 'var(--pt-bg)', borderRadius: '8px', padding: '0.75rem', border: '1px solid var(--pt-border)' }}>
                {recentLogs.length === 0 ? (
                  <p className="text-muted mb-0">Aucun log disponible.</p>
                ) : recentLogs.map((log, idx) => (
                  <div key={idx} className="d-flex gap-2 mb-2 pb-2 border-bottom border-light-subtle align-items-start">
                    <span className="text-muted" style={{ minWidth: '30px' }}>{log.time}</span>
                    <span className={`badge ${log.level === 'ERROR' ? 'bg-danger text-white' : log.level === 'SKIP' ? 'bg-secondary-subtle text-secondary' : 'bg-info-subtle text-info'}`} style={{ minWidth: '45px', fontSize: '10px' }}>{log.level}</span>
                    <span className="text-dark" style={{ wordBreak: 'break-word' }}>{log.message}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default ExecutionDetail
