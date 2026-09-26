import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Line } from 'react-chartjs-2'
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
import { BackendApplicationResponse, BackendScenarioResponse, BackendStepResponse, BackendMetricResponse } from '../types/backendContracts'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { stepsBackendApi } from '../services/api/stepsBackend'
import { metricsBackendApi } from '../services/api/metricsBackend'
import { useApiList } from '../hooks/useApiResource'

ChartJS.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  BarElement,
  ArcElement,
  Title,
  Tooltip,
  Legend,
  Filler
)

// ============================================================
// Phase 21 — cette page parle désormais au vrai backend Spring Boot pour
// les Métriques (voir services/api/metricsBackend.ts), aux Applications/
// Scénarios/Étapes déjà migrés (Phases 17-19), et n'utilise plus JSON
// Server du tout. Il n'existait AUCUN service `metricsApi` avant cette
// phase (recherche globale confirmée) : la page calculait tout côté client
// à partir des Executions JSON Server — rien d'autre ne dépendait d'un
// quelconque service Metrics partagé, donc aucun risque de redirection
// globale à gérer ici (contrairement à Applications/Scenarios/Steps/
// Executions dans les phases précédentes).
//
// Contrat réel utilisé (voir MetricController.java) : un seul endpoint
// `GET /api/metrics`, chaque Metric portant déjà applicationId/scenarioId/
// stepId/executionId/responseTime/statusCode/throughput/errorRate/timestamp
// — TOUJOURS des UUID Spring réels, jamais une correspondance avec un id
// JSON Server. `throughput`/`errorRate` sont des
// valeurs calculées par le backend au niveau de l'EXECUTION (dupliquées sur
// chaque Metric de cette exécution, voir Metric.java) : les KPIs qui les
// utilisent sont donc moyennés par EXÉCUTION UNIQUE (dédoublonnée par
// executionId), jamais par ligne de Metric, pour ne pas sur-pondérer les
// scénarios à beaucoup d'étapes.
//
// Toutes les Metric sont chargées une fois (aucune pagination côté
// backend) puis filtrées/agrégées côté client par application/scénario/
// étape/plage de temps — un filtrage légitime puisque les données
// affichées sont déjà réellement chargées (voir rapport Phase 21, section
// Filtres). Aucun filtre par date n'existe côté backend : la plage de
// temps reste donc un filtre 100% frontend sur le champ réel `timestamp`.
//
// Champs SANS équivalent Spring, retirés honnêtement plutôt que simulés :
// utilisateurs virtuels (aucune notion de VU dans le moteur d'exécution
// synchrone, voir Phase 20), corps/headers de requête capturés, message
// d'erreur texte par étape (seul `statusCode` existe ; les échecs sont
// identifiés par la même règle par défaut que le backend — voir
// HttpClientExecutionEngine.evaluateSuccess — 200-399 = succès, sinon échec
// ou échec technique si statusCode est null).
// ============================================================

const isSuccess = (m: BackendMetricResponse) => m.statusCode != null && m.statusCode >= 200 && m.statusCode < 400

const TIME_RANGES: Record<string, number> = {
  '1h': 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
  '30d': 30 * 24 * 60 * 60 * 1000,
}

function Metriques() {
  const [timeRange, setTimeRange] = useState('24h')

  const { data: allApplications, loading: appsLoading } = useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const { data: allScenarios, loading: scenariosLoading } = useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll())
  const { data: allSteps } = useApiList<BackendStepResponse>(() => stepsBackendApi.getAll())
  const { data: allMetrics, loading: metricsLoading, error: metricsError, refetch: refetchMetrics } = useApiList<BackendMetricResponse>(() => metricsBackendApi.getAll())
  const dataLoading = appsLoading || scenariosLoading || metricsLoading

  const appById = useMemo(() => new Map(allApplications.map((a) => [a.id, a])), [allApplications])
  const scenarioById = useMemo(() => new Map(allScenarios.map((s) => [s.id, s])), [allScenarios])
  const stepById = useMemo(() => new Map(allSteps.map((s) => [s.id, s])), [allSteps])

  // Sélection en cascade : Application → Scénario → Étape, par vrais UUID
  // Spring (jamais par nom, jamais un id JSON Server).
  const [selectedApplicationId, setSelectedApplicationId] = useState<string>('all')
  const [selectedScenarioId, setSelectedScenarioId] = useState<string>('all')
  const [selectedStepId, setSelectedStepId] = useState<string>('all')

  const scenariosForSelectedApp =
    selectedApplicationId === 'all' ? allScenarios : allScenarios.filter((s) => s.applicationId === selectedApplicationId)

  const selectedScenario = selectedScenarioId !== 'all' ? scenarioById.get(selectedScenarioId) ?? null : null
  const stepsForSelectedScenario = selectedScenario ? allSteps.filter((s) => s.scenarioId === selectedScenario.id) : []
  const selectedStep = selectedStepId !== 'all' ? stepsForSelectedScenario.find((s) => s.id === selectedStepId) ?? null : null

  const scopeLevel: 'all' | 'app' | 'scenario' | 'step' = selectedStep
    ? 'step'
    : selectedScenario
    ? 'scenario'
    : selectedApplicationId !== 'all'
    ? 'app'
    : 'all'

  const handleAppChange = (value: string) => {
    setSelectedApplicationId(value)
    setSelectedScenarioId('all')
    setSelectedStepId('all')
  }
  const handleScenarioChange = (value: string) => {
    setSelectedScenarioId(value)
    setSelectedStepId('all')
    if (value !== 'all') {
      const sc = scenarioById.get(value)
      if (sc) setSelectedApplicationId(sc.applicationId)
    }
  }

  const selectedAppName = selectedApplicationId === 'all' ? 'Toutes les applications' : appById.get(selectedApplicationId)?.name ?? ''
  const scopeLabel =
    scopeLevel === 'step'
      ? `${selectedScenario?.name} → ${selectedStep?.name}`
      : scopeLevel === 'scenario'
      ? selectedScenario?.name ?? ''
      : scopeLevel === 'app'
      ? selectedAppName
      : 'Toutes les applications'

  // ---- Filtrage RÉEL : plage de temps (frontend, aucun équivalent
  // backend) puis niveau sélectionné (sur les vrais champs Metric). ----
  const now = Date.now()
  const withinRange = (m: BackendMetricResponse) => now - new Date(m.timestamp).getTime() <= TIME_RANGES[timeRange]

  const scopedMetrics = allMetrics.filter((m) => {
    if (!withinRange(m)) return false
    if (scopeLevel === 'step') return m.stepId === selectedStep!.id
    if (scopeLevel === 'scenario') return m.scenarioId === selectedScenario!.id
    if (scopeLevel === 'app') return m.applicationId === selectedApplicationId
    return true
  })

  // Exécutions uniques dans le périmètre (throughput/errorRate sont des
  // valeurs par EXÉCUTION, dupliquées sur chaque ligne Metric — dédoublonner
  // avant de moyenner évite de sur-pondérer les scénarios à N étapes).
  const uniqueExecutions = useMemo(() => {
    const map = new Map<string, BackendMetricResponse>()
    scopedMetrics.forEach((m) => { if (!map.has(m.executionId)) map.set(m.executionId, m) })
    return Array.from(map.values()).sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime())
  }, [scopedMetrics])

  const avg = (values: number[]) => (values.length > 0 ? values.reduce((s, v) => s + v, 0) / values.length : null)

  const totalReq = scopedMetrics.length
  const successCount = scopedMetrics.filter(isSuccess).length
  const errorCount = totalReq - successCount
  const avgResponseTime = avg(scopedMetrics.map((m) => m.responseTime).filter((v): v is number => v != null))
  const avgErrorRate = avg(uniqueExecutions.map((m) => m.errorRate).filter((v): v is number => v != null))
  const avgThroughput = avg(uniqueExecutions.map((m) => m.throughput).filter((v): v is number => v != null))

  const kpis = [
    { title: 'Requêtes totales (mesures)', value: totalReq.toLocaleString('fr-FR'), subtitle: `${successCount} réussies`, icon: 'bi-send-fill', color: 'green' },
    { title: 'Durée moyenne', value: avgResponseTime != null ? `${Math.round(avgResponseTime)} ms` : 'N/D', subtitle: 'temps de réponse réel', icon: 'bi-clock-history', color: 'purple' },
    { title: "Taux d'erreur moyen", value: avgErrorRate != null ? `${avgErrorRate.toFixed(2)}%` : 'N/D', subtitle: `${errorCount} échec${errorCount > 1 ? 's' : ''}`, icon: 'bi-exclamation-triangle-fill', color: 'red' },
    { title: 'Débit moyen', value: avgThroughput != null ? `${avgThroughput.toFixed(2)} req/s` : 'N/D', subtitle: `sur ${uniqueExecutions.length} exécution${uniqueExecutions.length > 1 ? 's' : ''}`, icon: 'bi-lightning-charge-fill', color: 'orange' },
  ]

  const execLabels = uniqueExecutions.map((m) =>
    new Date(m.timestamp).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' }) +
    ' ' + new Date(m.timestamp).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
  )

  const responseTimeByExecution = uniqueExecutions.map((exec) => {
    const rows = scopedMetrics.filter((m) => m.executionId === exec.executionId).map((m) => m.responseTime).filter((v): v is number => v != null)
    return rows.length > 0 ? Math.round(rows.reduce((s, v) => s + v, 0) / rows.length) : 0
  })

  const responseTimeData = {
    labels: execLabels,
    datasets: [{ label: 'Temps de réponse moyen (ms)', data: responseTimeByExecution, borderColor: '#4F46E5', borderWidth: 2, tension: 0.3, pointRadius: 3, backgroundColor: 'rgba(79,70,229,0.1)', fill: true }],
  }
  const errorRateData = {
    labels: execLabels,
    datasets: [{ label: "Taux d'erreur (%)", data: uniqueExecutions.map((m) => (m.errorRate != null ? Number(m.errorRate) : 0)), borderColor: '#EF4444', backgroundColor: 'rgba(239,68,68,0.15)', borderWidth: 2, tension: 0.3, fill: true, pointBackgroundColor: '#EF4444', pointRadius: 3 }],
  }
  const throughputData = {
    labels: execLabels,
    datasets: [{ label: 'Débit (req/s)', data: uniqueExecutions.map((m) => (m.throughput != null ? Number(m.throughput) : 0)), borderColor: '#F59E0B', backgroundColor: 'rgba(245,158,11,0.12)', borderWidth: 2, tension: 0.3, fill: true, pointBackgroundColor: '#F59E0B', pointRadius: 3 }],
  }
  const lineOptions = (unit: string) => ({
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { display: false }, tooltip: { mode: 'index' as const, intersect: false } },
    scales: {
      x: { grid: { display: false }, ticks: { color: '#9CA3AF', font: { size: 11 } } },
      y: { grid: { color: 'rgba(229,231,235,0.5)' }, ticks: { color: '#9CA3AF', font: { size: 11 }, callback: (v: any) => `${v}${unit}` }, beginAtZero: true },
    },
  })

  // ---- Tableaux (vue globale) : agrégés sur TOUTES les Metric réelles,
  // indépendamment du périmètre sélectionné — même esprit que l'ancienne
  // page, désormais sur des données Spring réelles. ----
  const reqsByApp = new Map<string, number>()
  allMetrics.forEach((m) => { if (m.applicationId) reqsByApp.set(m.applicationId, (reqsByApp.get(m.applicationId) ?? 0) + 1) })
  const totalReqsAllApps = Array.from(reqsByApp.values()).reduce((a, b) => a + b, 0)
  const appColors = ['#4F46E5', '#22C55E', '#F59E0B', '#8B5CF6', '#EC4899']
  const topApplications = Array.from(reqsByApp.entries())
    .map(([appId, reqs]) => ({ appId, name: appById.get(appId)?.name ?? 'N/D', reqs, percent: totalReqsAllApps > 0 ? Math.round((reqs / totalReqsAllApps) * 1000) / 10 : 0 }))
    .sort((a, b) => b.reqs - a.reqs)
    .slice(0, 4)
    .map((a, i) => ({ ...a, reqsLabel: a.reqs.toLocaleString('fr-FR'), color: appColors[i % appColors.length] }))

  const avgDurationForScenario = (scenarioId: string): number | null => {
    const rows = allMetrics.filter((m) => m.scenarioId === scenarioId).map((m) => m.responseTime).filter((v): v is number => v != null)
    return rows.length > 0 ? Math.round(rows.reduce((s, v) => s + v, 0) / rows.length) : null
  }
  const statusForDuration = (ms: number) => (ms > 350 ? { status: 'Inquiétant', color: 'danger' } : ms > 200 ? { status: 'Modéré', color: 'warning' } : { status: 'Optimal', color: 'success' })

  const topScenarios = allScenarios
    .map((sc) => ({ scenario: sc, avg: avgDurationForScenario(sc.id) }))
    .filter((x): x is { scenario: BackendScenarioResponse; avg: number } => x.avg !== null)
    .sort((a, b) => b.avg - a.avg)
    .slice(0, 4)
    .map(({ scenario, avg: a }) => ({ id: scenario.id, name: scenario.name, duration: `${a} ms`, ...statusForDuration(a) }))

  const scenariosOfSelectedApp = scenariosForSelectedApp.map((sc) => {
    const a = avgDurationForScenario(sc.id)
    const stepCount = allSteps.filter((s) => s.scenarioId === sc.id).length
    return a !== null ? { id: sc.id, name: sc.name, duration: `${a} ms`, stepCount, ...statusForDuration(a) } : { id: sc.id, name: sc.name, duration: '—', stepCount, status: 'Jamais exécuté', color: 'neutral' }
  })

  const stepsOfSelectedScenario = stepsForSelectedScenario.map((st) => {
    const rows = allMetrics.filter((m) => m.stepId === st.id).map((m) => m.responseTime).filter((v): v is number => v != null)
    const duration = rows.length > 0 ? Math.round(rows.reduce((s, v) => s + v, 0) / rows.length) : null
    return { ...st, duration }
  })

  // Top Erreurs : par code HTTP réel (aucun message texte n'existe côté
  // Metric — seul statusCode est disponible). Règle de succès par défaut
  // identique à celle documentée du backend (voir HttpClientExecutionEngine
  // .evaluateSuccess) : 200-399 = succès, sinon échec (null = échec
  // technique, aucune réponse reçue).
  const errorGroups = new Map<string, { code: string; count: number }>()
  allMetrics.filter((m) => !isSuccess(m)).forEach((m) => {
    const key = m.statusCode != null ? String(m.statusCode) : 'technique'
    const label = m.statusCode != null ? `HTTP ${m.statusCode}` : 'Échec technique (pas de réponse)'
    const existing = errorGroups.get(key)
    if (existing) existing.count++
    else errorGroups.set(key, { code: label, count: 1 })
  })
  const totalErrorsAll = Array.from(errorGroups.values()).reduce((s, g) => s + g.count, 0)
  const topErrors = Array.from(errorGroups.values())
    .sort((a, b) => b.count - a.count)
    .slice(0, 4)
    .map((g) => ({ ...g, countLabel: g.count.toLocaleString('fr-FR'), rate: totalErrorsAll > 0 ? `${((g.count / totalErrorsAll) * 100).toFixed(2)}%` : '0%' }))

  return (
    <div className="pt-content">
      {/* Page Header */}
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Metriques</h1>
          <p>Analysez les performances réelles de vos tests de charge (Spring Boot)</p>
        </div>
        <div className="d-flex align-items-center gap-3 flex-wrap">
          <select className="pt-form-control" style={{ width: 'auto', fontSize: '13px' }} value={timeRange} onChange={(e) => setTimeRange(e.target.value)}>
            <option value="1h">Dernière heure</option>
            <option value="24h">Dernières 24 heures</option>
            <option value="7d">7 derniers jours</option>
            <option value="30d">30 derniers jours</option>
          </select>
          <TopBar searchPlaceholder="Rechercher une métrique..." />
        </div>
      </div>

      {metricsError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger les métriques : {metricsError}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => refetchMetrics()}>Réessayer</button>
        </div>
      )}

      <div className="d-flex align-items-center gap-2 mb-2" style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-speedometer2" style={{ color: 'var(--pt-primary)' }}></i> PERFORMANCE
      </div>

      {/* Sélecteur de niveau : Application → Scénario → Étape (UUID Spring) */}
      <div className="pt-card mb-4" style={{ padding: '1rem 1.25rem' }}>
        <div className="row g-3 align-items-end">
          <div className="col-12 col-md-4">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Application</label>
            <select className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={selectedApplicationId} onChange={(e) => handleAppChange(e.target.value)}>
              <option value="all">Toutes les applications</option>
              {allApplications.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </div>
          <div className="col-12 col-md-4">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Scénario</label>
            <select className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={selectedScenarioId} onChange={(e) => handleScenarioChange(e.target.value)}>
              <option value="all">Tous les scénarios</option>
              {scenariosForSelectedApp.map((sc) => (
                <option key={sc.id} value={sc.id}>{sc.name}{selectedApplicationId === 'all' ? ` (${sc.applicationName})` : ''}</option>
              ))}
            </select>
          </div>
          <div className="col-12 col-md-4">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>Étape</label>
            <select className="pt-form-control" style={{ width: '100%', fontSize: '13px' }} value={selectedStepId} onChange={(e) => setSelectedStepId(e.target.value)} disabled={!selectedScenario}>
              <option value="all">Toutes les étapes</option>
              {stepsForSelectedScenario.map((st) => <option key={st.id} value={st.id}>{st.name}</option>)}
            </select>
          </div>
        </div>
        <div className="d-flex align-items-center gap-2 mt-3" style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
          <i className="bi bi-funnel-fill" style={{ color: 'var(--pt-primary)' }}></i>
          Statistiques affichées pour : <strong style={{ color: 'var(--pt-text)' }}>{scopeLabel}</strong>
          {dataLoading && <span className="ms-2"><i className="bi bi-arrow-repeat pt-spin"></i> Chargement...</span>}
        </div>
      </div>

      {/* 4 KPI Cards (pas d'"utilisateurs virtuels" : aucune notion de VU
          dans le moteur d'exécution Spring, synchrone et séquentiel) */}
      <div className="row g-3 mb-4">
        {kpis.map((kpi, idx) => (
          <div key={idx} className="col-12 col-sm-6 col-lg-3">
            <div className="pt-stat-card h-100">
              <div className="stat-header">
                <div>
                  <div className="stat-label">{kpi.title}</div>
                  <div className="stat-value">{kpi.value}</div>
                  <div className="stat-trend neutral">{kpi.subtitle}</div>
                </div>
                <div className={`stat-icon ${kpi.color}`}><i className={`bi ${kpi.icon}`}></i></div>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* 3 Charts — un point par exécution réelle, valeurs backend */}
      <div className="row g-3 mb-4">
        <div className="col-12 col-lg-6">
          <div className="pt-card h-100">
            <div className="d-flex justify-content-between align-items-center mb-3">
              <div>
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Temps de réponse</h6>
                <small style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Moyenne réelle par exécution (ms)</small>
              </div>
            </div>
            <div style={{ height: '260px' }}><Line data={responseTimeData} options={lineOptions(' ms')} /></div>
          </div>
        </div>
        <div className="col-12 col-lg-6">
          <div className="pt-card h-100">
            <div className="d-flex justify-content-between align-items-center mb-3">
              <div>
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Débit</h6>
                <small style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Requêtes/seconde réelles, calculées par le backend</small>
              </div>
            </div>
            <div style={{ height: '260px' }}><Line data={throughputData} options={lineOptions(' req/s')} /></div>
          </div>
        </div>
        <div className="col-12">
          <div className="pt-card">
            <div className="d-flex justify-content-between align-items-center mb-3">
              <div>
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Taux d'erreur</h6>
                <small style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Pourcentage réel d'étapes en échec par exécution</small>
              </div>
              <span className="pt-pill danger">Seuil: 5%</span>
            </div>
            <div style={{ height: '220px' }}><Line data={errorRateData} options={lineOptions('%')} /></div>
          </div>
        </div>
      </div>

      {/* Tableaux contextuels */}
      <div className="row g-3 mb-4">
        {scopeLevel === 'all' && (
          <>
            <div className="col-12 col-lg-4">
              <div className="pt-card h-100" style={{ padding: 0 }}>
                <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                  <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Top Applications par requetes</h6>
                  <small style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>Applications générant le plus de mesures réelles</small>
                </div>
                <div className="pt-table-wrapper">
                  <table className="pt-table">
                    <thead><tr><th>Application</th><th style={{ textAlign: 'right' }}>Requêtes</th><th style={{ textAlign: 'right', width: '30%' }}>Part</th></tr></thead>
                    <tbody>
                      {topApplications.map((app, i) => (
                        <tr key={i} onClick={() => handleAppChange(app.appId)} style={{ cursor: 'pointer' }} title="Voir les statistiques de cette application">
                          <td><span style={{ fontSize: '13px', fontWeight: 600 }}>{app.name}</span></td>
                          <td style={{ textAlign: 'right', fontSize: '13px' }}>{app.reqsLabel}</td>
                          <td style={{ textAlign: 'right' }}>
                            <div className="d-flex align-items-center gap-2 justify-content-end">
                              <div style={{ width: '50px', height: '6px', background: 'var(--pt-border)', borderRadius: '3px', overflow: 'hidden' }}>
                                <div style={{ width: `${app.percent}%`, height: '100%', background: app.color, borderRadius: '3px' }}></div>
                              </div>
                              <span style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)', minWidth: '35px' }}>{app.percent}%</span>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
            <div className="col-12 col-lg-4">
              <div className="pt-card h-100" style={{ padding: 0 }}>
                <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                  <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Top Scenarios par duree</h6>
                  <small style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>Scénarios les plus lents à s'exécuter</small>
                </div>
                <div className="pt-table-wrapper">
                  <table className="pt-table">
                    <thead><tr><th>Scénario</th><th style={{ textAlign: 'right' }}>Durée moy.</th><th style={{ textAlign: 'right' }}>Statut</th></tr></thead>
                    <tbody>
                      {topScenarios.map((sc, i) => (
                        <tr key={i} onClick={() => handleScenarioChange(sc.id)} style={{ cursor: 'pointer' }} title="Voir ce scénario">
                          <td><span style={{ fontSize: '13px', fontWeight: 600 }}>{sc.name}</span></td>
                          <td style={{ textAlign: 'right', fontSize: '13px', color: 'var(--pt-primary)', fontWeight: 600 }}>{sc.duration}</td>
                          <td style={{ textAlign: 'right' }}><span className={`pt-pill ${sc.color}`}>{sc.status}</span></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </>
        )}

        {scopeLevel === 'app' && (
          <div className="col-12 col-lg-8">
            <div className="pt-card h-100" style={{ padding: 0 }}>
              <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Scénarios de {selectedAppName}</h6>
                <small style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>Cliquez sur un scénario pour voir uniquement ses statistiques</small>
              </div>
              <div className="pt-table-wrapper">
                <table className="pt-table">
                  <thead><tr><th>Scénario</th><th style={{ textAlign: 'right' }}>Étapes</th><th style={{ textAlign: 'right' }}>Durée moy.</th><th style={{ textAlign: 'right' }}>Statut</th></tr></thead>
                  <tbody>
                    {scenariosOfSelectedApp.length === 0 ? (
                      <tr><td colSpan={4} className="text-center py-4 text-muted">Aucun scénario pour cette application.</td></tr>
                    ) : scenariosOfSelectedApp.map((sc, i) => (
                      <tr key={i} onClick={() => handleScenarioChange(sc.id)} style={{ cursor: 'pointer' }} title="Voir ce scénario">
                        <td><span style={{ fontSize: '13px', fontWeight: 600 }}>{sc.name}</span></td>
                        <td style={{ textAlign: 'right', fontSize: '13px', color: 'var(--pt-text-muted)' }}>{sc.stepCount}</td>
                        <td style={{ textAlign: 'right', fontSize: '13px', color: 'var(--pt-primary)', fontWeight: 600 }}>{sc.duration}</td>
                        <td style={{ textAlign: 'right' }}><span className={`pt-pill ${sc.color}`}>{sc.status}</span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {scopeLevel === 'scenario' && (
          <div className="col-12 col-lg-8">
            <div className="pt-card h-100" style={{ padding: 0 }}>
              <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Étapes de {selectedScenario?.name}</h6>
                <small style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>Cliquez sur une étape pour voir uniquement ses statistiques</small>
              </div>
              <div className="pt-table-wrapper">
                <table className="pt-table">
                  <thead><tr><th>Méthode</th><th>Étape</th><th>URL / Ressource</th><th style={{ textAlign: 'right' }}>Durée moy.</th></tr></thead>
                  <tbody>
                    {stepsOfSelectedScenario.length === 0 ? (
                      <tr><td colSpan={4} className="text-center py-4 text-muted">Aucune étape définie pour ce scénario.</td></tr>
                    ) : stepsOfSelectedScenario.map((st) => (
                      <tr key={st.id} onClick={() => setSelectedStepId(st.id)} style={{ cursor: 'pointer' }} title="Voir uniquement cette étape">
                        <td>
                          <span style={{ padding: '0.15rem 0.5rem', borderRadius: '4px', fontSize: '11px', fontWeight: 700, background: st.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)', color: st.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)' }}>{st.method}</span>
                        </td>
                        <td><span style={{ fontSize: '13px', fontWeight: 600 }}>{st.name}</span></td>
                        <td><code style={{ fontSize: '12px', color: 'var(--pt-primary)', background: 'rgba(79,70,229,0.06)', padding: '0.1rem 0.35rem', borderRadius: '4px' }}>{st.url}</code></td>
                        <td style={{ textAlign: 'right', fontSize: '13px', color: 'var(--pt-primary)', fontWeight: 600 }}>{st.duration != null ? `${st.duration} ms` : 'N/D'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {scopeLevel === 'step' && selectedStep && (
          <div className="col-12 col-lg-8">
            <div className="pt-card h-100">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '1rem' }}>Détails de l'étape sélectionnée</h6>
              <div className="row g-3">
                <div className="col-6 col-md-3">
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>Méthode</div>
                  <span style={{ padding: '0.2rem 0.6rem', borderRadius: '6px', fontSize: '12px', fontWeight: 700, background: selectedStep.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)', color: selectedStep.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)' }}>{selectedStep.method}</span>
                </div>
                <div className="col-6 col-md-9">
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>Nom de l'étape</div>
                  <div style={{ fontSize: '14px', fontWeight: 600 }}>{selectedStep.name}</div>
                </div>
                <div className="col-12">
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>URL / Ressource</div>
                  <code style={{ fontSize: '13px', color: 'var(--pt-primary)', background: 'rgba(79,70,229,0.06)', padding: '0.25rem 0.5rem', borderRadius: '4px', display: 'inline-block' }}>{selectedStep.url}</code>
                </div>
                <div className="col-12">
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>Scénario parent</div>
                  <button onClick={() => setSelectedStepId('all')} style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '13px', color: 'var(--pt-primary)', fontWeight: 600 }}>
                    <i className="bi bi-arrow-return-left me-1"></i>{selectedScenario?.name}
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        <div className="col-12 col-lg-4">
          <div className="pt-card h-100" style={{ padding: 0 }}>
            <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
              <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Top Erreurs</h6>
              <small style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>Codes HTTP ≥ 400 ou échecs techniques (sans réponse)</small>
            </div>
            <div className="pt-table-wrapper">
              <table className="pt-table">
                <thead><tr><th>Code</th><th style={{ textAlign: 'right' }}>Total</th><th style={{ textAlign: 'right' }}>Taux</th></tr></thead>
                <tbody>
                  {topErrors.length === 0 ? (
                    <tr><td colSpan={3} className="text-center py-3 text-muted">Aucune erreur réelle enregistrée.</td></tr>
                  ) : topErrors.map((err, i) => (
                    <tr key={i}>
                      <td><span className="pt-pill danger" style={{ fontWeight: 700 }}>{err.code}</span></td>
                      <td style={{ textAlign: 'right', fontSize: '13px', fontWeight: 600 }}>{err.countLabel}</td>
                      <td style={{ textAlign: 'right', fontSize: '12.5px', color: 'var(--pt-danger)' }}>{err.rate}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      {/* Journal des mesures réelles de l'étape sélectionnée */}
      {scopeLevel === 'step' && selectedStep && (
        <div className="pt-card mb-4">
          <div className="pt-card-title mb-3"><i className="bi bi-terminal me-2 text-info"></i>Mesures réelles — {selectedStep.name}</div>
          <div style={{ maxHeight: '320px', overflowY: 'auto', fontFamily: 'monospace', fontSize: '12px', background: 'var(--pt-bg)', borderRadius: '8px', padding: '0.75rem', border: '1px solid var(--pt-border)' }}>
            {scopedMetrics.length === 0 ? (
              <p className="text-muted mb-0">Aucune mesure disponible pour le moment sur cette étape.</p>
            ) : scopedMetrics.slice().reverse().map((m) => (
              <div key={m.id} className="d-flex gap-2 mb-2 pb-2 border-bottom border-light-subtle align-items-start">
                <span className="text-muted" style={{ minWidth: '90px' }}>{new Date(m.timestamp).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })}</span>
                <span className={`badge ${isSuccess(m) ? 'bg-info-subtle text-info' : 'bg-danger text-white'}`} style={{ minWidth: '45px', fontSize: '10px' }}>{isSuccess(m) ? 'INFO' : 'ERROR'}</span>
                <span className="text-dark" style={{ wordBreak: 'break-word' }}>
                  HTTP {m.statusCode ?? '—'} en {m.responseTime ?? '—'}ms — exécution <Link to="/executions" className="text-decoration-none">#{m.executionId.slice(0, 8)}</Link>
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Footer */}
      <div className="d-flex justify-content-between align-items-center flex-wrap gap-2 pt-3" style={{ borderTop: '1px solid var(--pt-border)', fontSize: '12px', color: 'var(--pt-text-muted)' }}>
        <div className="d-flex align-items-center gap-2"><i className="bi bi-clock-history"></i><span>Métriques réelles, générées automatiquement par le backend après chaque exécution</span></div>
        <div className="d-flex align-items-center gap-2"><i className="bi bi-globe"></i><span>Fuseau horaire UTC+01:00 Casablanca</span></div>
      </div>
    </div>
  )
}

export default Metriques
