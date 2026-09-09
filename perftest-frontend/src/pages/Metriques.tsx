import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Line, Bar } from 'react-chartjs-2'
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
import { Application, Scenario, Step, Execution, StepResult } from '../types'
import { applicationsApi } from '../services/api/applications'
import { scenariosApi } from '../services/api/scenarios'
import { stepsApi } from '../services/api/steps'
import { executionsApi } from '../services/api/executions'
import { useApiList } from '../hooks/useApiResource'
import { useAuth } from '../context/AuthContext'
import { computePerformanceMetrics, durationToSeconds } from '../utils/metrics'

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

function Metriques() {
  const { canEdit } = useAuth()
  const [timeRange, setTimeRange] = useState('24h')

  // Données réelles depuis JSON Server — aucune métrique n'est plus
  // générée aléatoirement : tout est calculé à partir des vraies
  // exécutions et de leurs vrais résultats d'étapes (stepResults).
  const { data: allApplicationsRaw, loading: appsLoading } = useApiList<Application>(() => applicationsApi.getAll())
  const { data: allScenarios, loading: scenariosLoading } = useApiList<Scenario>(() => scenariosApi.getAll())
  const { data: allSteps } = useApiList<Step>(() => stepsApi.getAll())
  const { data: allExecutions, loading: executionsLoading } = useApiList<Execution>(() => executionsApi.getAll())
  const dataLoading = appsLoading || scenariosLoading || executionsLoading

  const allApplications = allApplicationsRaw.filter((a) => a.status === 'Actif')
  const appById = useMemo(() => new Map(allApplicationsRaw.map((a) => [String(a.id), a])), [allApplicationsRaw])
  const scenarioById = useMemo(() => new Map(allScenarios.map((s) => [String(s.id), s])), [allScenarios])
  const stepById = useMemo(() => new Map(allSteps.map((s) => [String(s.id), s])), [allSteps])

  // Sélection en cascade : Application → Scénario → Étape, par vrais IDs
  // (jamais par nom). Chaque niveau filtre les statistiques de la page.
  const [selectedApplicationId, setSelectedApplicationId] = useState<string>('all')
  const [selectedScenarioId, setSelectedScenarioId] = useState<string>('all')
  const [selectedStepId, setSelectedStepId] = useState<string>('all')

  const scenariosForSelectedApp =
    selectedApplicationId === 'all'
      ? allScenarios
      : allScenarios.filter((s) => String(s.applicationId) === String(selectedApplicationId))

  const selectedScenario =
    selectedScenarioId !== 'all' ? scenarioById.get(selectedScenarioId) ?? null : null

  const stepsForSelectedScenario = selectedScenario
    ? allSteps.filter((s) => String(s.scenarioId) === String(selectedScenario.id))
    : []

  const selectedStep =
    selectedStepId !== 'all' ? stepsForSelectedScenario.find((s) => String(s.id) === String(selectedStepId)) ?? null : null

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
    // Sélectionner un scénario directement (sans être passé par son
    // application) aligne aussi le niveau "Application" par cohérence.
    if (value !== 'all') {
      const sc = scenarioById.get(value)
      if (sc) setSelectedApplicationId(String(sc.applicationId))
    }
  }

  const selectedAppName =
    selectedApplicationId === 'all' ? 'Toutes les applications' : appById.get(selectedApplicationId)?.name ?? ''

  const scopeLabel =
    scopeLevel === 'step'
      ? `${selectedScenario?.name} → ${selectedStep?.name}`
      : scopeLevel === 'scenario'
      ? selectedScenario?.name ?? ''
      : scopeLevel === 'app'
      ? selectedAppName
      : 'Toutes les applications'

  // ---- Agrégation RÉELLE des résultats selon le niveau sélectionné ----
  const matchingExecutions = allExecutions.filter((e) => {
    if (scopeLevel === 'scenario' || scopeLevel === 'step') return String(e.scenarioId) === String(selectedScenario!.id)
    if (scopeLevel === 'app') return String(e.applicationId) === String(selectedApplicationId)
    return true
  }).sort((a, b) => (a.startedAt < b.startedAt ? -1 : 1))

  const matchingResults: { result: StepResult; execution: Execution }[] = []
  matchingExecutions.forEach((e) => {
    e.stepResults.forEach((r) => {
      if (scopeLevel === 'step' && String(r.stepId) !== String(selectedStep!.id)) return
      matchingResults.push({ result: r, execution: e })
    })
  })

  // Consommation serveur réelle de l'étape sélectionnée — moyenne des
  // relevés CPU/RAM capturés après chaque requête réelle de cette étape
  // (voir StepResult.serverMetrics, alimenté par useScenarioLauncher).
  // `null` tant qu'aucun relevé n'existe, jamais une valeur inventée.
  const stepServerSamples = matchingResults
    .map((r) => r.result.serverMetrics)
    .filter((m): m is { cpu: number; ram: number; capturedAt: string } => !!m)
  const stepAvgCpu = stepServerSamples.length > 0
    ? Math.round(stepServerSamples.reduce((s, m) => s + m.cpu, 0) / stepServerSamples.length)
    : null
  const stepAvgRam = stepServerSamples.length > 0
    ? Math.round(stepServerSamples.reduce((s, m) => s + m.ram, 0) / stepServerSamples.length)
    : null

  const totalDurationSec = matchingExecutions.reduce((sum, e) => sum + durationToSeconds(e.duration), 0)
  const perf = computePerformanceMetrics(matchingResults.map((r) => r.result), totalDurationSec)
  const totalReq = perf.totalRequests
  const successCount = perf.successCount
  const errorCount = perf.errorCount
  const errorRateVal = perf.errorRate
  const avgDurationVal = perf.avgResponseTime
  const throughputVal = perf.throughput
  const maxVUsVal = matchingExecutions.length > 0
    ? Math.max(...matchingExecutions.map((e) => parseInt(e.users) || 0))
    : 0

  const kpis = [
    {
      title: 'Utilisateurs max simultanés',
      value: maxVUsVal.toLocaleString('fr-FR'),
      subtitle: `sur ${matchingExecutions.length} exécution${matchingExecutions.length > 1 ? 's' : ''}`,
      icon: 'bi-people-fill',
      color: 'blue',
    },
    {
      title: 'Requêtes totales',
      value: totalReq.toLocaleString('fr-FR'),
      subtitle: `${successCount} réussies`,
      icon: 'bi-send-fill',
      color: 'green',
    },
    {
      title: 'Durée moyenne',
      value: `${avgDurationVal} ms`,
      subtitle: 'temps de réponse',
      icon: 'bi-clock-history',
      color: 'purple',
    },
    {
      title: 'Taux erreur',
      value: `${errorRateVal.toFixed(2)}%`,
      subtitle: `${errorCount} erreur${errorCount > 1 ? 's' : ''}`,
      icon: 'bi-exclamation-triangle-fill',
      color: 'red',
    },
    {
      title: 'Débit moyen',
      value: `${throughputVal.toFixed(2)} req/s`,
      subtitle: 'requêtes / seconde',
      icon: 'bi-lightning-charge-fill',
      color: 'orange',
    },
  ]

  // Chart 1: Utilisateurs virtuels — une valeur réelle par exécution.
  const execLabels = matchingExecutions.map((e) =>
    new Date(e.startedAt).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' }) +
    ' ' + new Date(e.startedAt).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
  )
  const vusData = {
    labels: execLabels,
    datasets: [{
      label: 'Utilisateurs virtuels (VUs)',
      data: matchingExecutions.map((e) => parseInt(e.users) || 0),
      borderColor: '#4F46E5',
      backgroundColor: 'rgba(79, 70, 229, 0.12)',
      borderWidth: 2,
      tension: 0.3,
      fill: true,
      pointBackgroundColor: '#4F46E5',
      pointRadius: 3,
    }],
  }
  const vusOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { display: false }, tooltip: { mode: 'index' as const, intersect: false } },
    scales: {
      x: { grid: { display: false }, ticks: { color: '#9CA3AF', font: { size: 11 } } },
      y: { grid: { color: 'rgba(229, 231, 235, 0.5)' }, ticks: { color: '#9CA3AF', font: { size: 11 } }, beginAtZero: true },
    },
  }

  // Chart 2: Temps de réponse moyen — réel, par exécution.
  const responseTimeData = {
    labels: execLabels,
    datasets: [{
      label: 'Temps de réponse moyen (ms)',
      data: matchingExecutions.map((e) => {
        const results = e.stepResults.filter((r) => scopeLevel !== 'step' || String(r.stepId) === String(selectedStep!.id))
        return results.length > 0 ? Math.round(results.reduce((s, r) => s + (r.responseTimeMs ?? 0), 0) / results.length) : 0
      }),
      borderColor: '#4F46E5',
      borderWidth: 2, tension: 0.3, pointRadius: 3,
    }],
  }
  const responseTimeOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { display: false }, tooltip: { mode: 'index' as const, intersect: false } },
    scales: {
      x: { grid: { display: false }, ticks: { color: '#9CA3AF', font: { size: 11 } } },
      y: { grid: { color: 'rgba(229, 231, 235, 0.5)' }, ticks: { color: '#9CA3AF', font: { size: 11 }, callback: (val: any) => `${val} ms` }, beginAtZero: true },
    },
  }

  // Chart 3: Taux d'erreur réel, par exécution.
  const errorRateData = {
    labels: execLabels,
    datasets: [{
      label: "Taux d'erreur (%)",
      data: matchingExecutions.map((e) => {
        const results = e.stepResults.filter((r) => scopeLevel !== 'step' || String(r.stepId) === String(selectedStep!.id))
        const errs = results.filter((r) => r.status === 'error').length
        return results.length > 0 ? Math.round((errs / results.length) * 10000) / 100 : 0
      }),
      borderColor: '#EF4444',
      backgroundColor: 'rgba(239, 68, 68, 0.15)',
      borderWidth: 2, tension: 0.3, fill: true, pointBackgroundColor: '#EF4444', pointRadius: 3,
    }],
  }
  const errorRateOptions = {
    responsive: true, maintainAspectRatio: false,
    plugins: { legend: { display: false }, tooltip: { callbacks: { label: (context: any) => ` Taux d'erreur: ${context.raw}%` } } },
    scales: {
      x: { grid: { display: false }, ticks: { color: '#9CA3AF', font: { size: 11 } } },
      y: { grid: { color: 'rgba(229, 231, 235, 0.5)' }, ticks: { color: '#9CA3AF', font: { size: 11 }, callback: (val: any) => `${val}%` }, beginAtZero: true },
    },
  }

  // Table 1 (vue globale) : Top Applications par requêtes réelles.
  const reqsByApp = new Map<string, number>()
  allExecutions.forEach((e) => {
    const key = String(e.applicationId)
    reqsByApp.set(key, (reqsByApp.get(key) ?? 0) + e.stepResults.length)
  })
  const totalReqsAllApps = Array.from(reqsByApp.values()).reduce((a, b) => a + b, 0)
  const appColors = ['#4F46E5', '#22C55E', '#F59E0B', '#8B5CF6', '#EC4899']
  const topApplications = Array.from(reqsByApp.entries())
    .map(([appId, reqs]) => ({ appId, name: appById.get(appId)?.name ?? appId, reqs, percent: totalReqsAllApps > 0 ? Math.round((reqs / totalReqsAllApps) * 1000) / 10 : 0 }))
    .sort((a, b) => b.reqs - a.reqs)
    .slice(0, 4)
    .map((a, i) => ({ ...a, reqs: a.reqs.toLocaleString('fr-FR'), color: appColors[i % appColors.length] }))

  // Durée moyenne réelle par scénario, à partir de toutes ses exécutions.
  const avgDurationForScenario = (scenarioId: string): number | null => {
    const results = allExecutions.filter((e) => String(e.scenarioId) === String(scenarioId)).flatMap((e) => e.stepResults)
    if (results.length === 0) return null
    return Math.round(results.reduce((s, r) => s + (r.responseTimeMs ?? 0), 0) / results.length)
  }
  const statusForDuration = (ms: number) => (ms > 350 ? { status: 'Inquiétant', color: 'danger' } : ms > 200 ? { status: 'Modéré', color: 'warning' } : { status: 'Optimal', color: 'success' })

  // Table 2 (vue globale) : Top Scénarios par durée réelle.
  const topScenarios = allScenarios
    .map((sc) => ({ scenario: sc, avg: avgDurationForScenario(sc.id) }))
    .filter((x): x is { scenario: Scenario; avg: number } => x.avg !== null)
    .sort((a, b) => b.avg - a.avg)
    .slice(0, 4)
    .map(({ scenario, avg }) => ({ id: scenario.id, name: scenario.name, duration: `${avg} ms`, ...statusForDuration(avg) }))

  // Table 2 (vue "Application") : scénarios de cette appli, durée réelle.
  const scenariosOfSelectedApp = scenariosForSelectedApp.map((sc) => {
    const avg = avgDurationForScenario(sc.id)
    const stepCount = allSteps.filter((s) => String(s.scenarioId) === String(sc.id)).length
    return avg !== null
      ? { id: sc.id, name: sc.name, duration: `${avg} ms`, stepCount, ...statusForDuration(avg) }
      : { id: sc.id, name: sc.name, duration: '—', stepCount, status: 'Jamais exécuté', color: 'neutral' }
  })

  // Table 2 (vue "Scénario") : étapes réelles, durée moyenne réelle par étape.
  const stepsOfSelectedScenario = stepsForSelectedScenario.map((st) => {
    const results = allExecutions.filter((e) => String(e.scenarioId) === String(selectedScenario?.id))
      .flatMap((e) => e.stepResults)
      .filter((r) => String(r.stepId) === String(st.id))
    const duration = results.length > 0
      ? Math.round(results.reduce((s, r) => s + (r.responseTimeMs ?? 0), 0) / results.length)
      : 0
    return { ...st, duration }
  })

  // Table 3 : Top Erreurs réelles, regroupées par code HTTP + message.
  const errorGroups = new Map<string, { code: string; name: string; count: number }>()
  allExecutions.forEach((e) => {
    e.stepResults.filter((r) => r.status === 'error').forEach((r) => {
      const key = `${r.httpStatus}-${r.error ?? ''}`
      const existing = errorGroups.get(key)
      if (existing) existing.count++
      else errorGroups.set(key, { code: String(r.httpStatus ?? '—'), name: r.error ?? 'Erreur inconnue', count: 1 })
    })
  })
  const totalErrorsAll = Array.from(errorGroups.values()).reduce((s, g) => s + g.count, 0)
  const topErrors = Array.from(errorGroups.values())
    .sort((a, b) => b.count - a.count)
    .slice(0, 4)
    .map((g) => ({ ...g, count: g.count.toLocaleString('fr-FR'), rate: totalErrorsAll > 0 ? `${((g.count / totalErrorsAll) * 100).toFixed(2)}%` : '0%' }))


  return (
    <div className="pt-content">
      {/* Page Header */}
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Metriques</h1>
          <p>Analysez les performances de vos tests de charge</p>
        </div>
        <div className="d-flex align-items-center gap-3 flex-wrap">
          <select
            className="pt-form-control"
            style={{ width: 'auto', fontSize: '13px' }}
            value={timeRange}
            onChange={(e) => setTimeRange(e.target.value)}
          >
            <option value="1h">Dernière heure</option>
            <option value="24h">Dernières 24 heures</option>
            <option value="7d">7 derniers jours</option>
            <option value="30d">30 derniers jours</option>
          </select>
          <TopBar searchPlaceholder="Rechercher une métrique..." />
        </div>
      </div>

      <div className="d-flex align-items-center gap-2 mb-2" style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-speedometer2" style={{ color: 'var(--pt-primary)' }}></i> PERFORMANCE
      </div>

      {/* Sélecteur de niveau : Application → Scénario → Étape */}
      <div className="pt-card mb-4" style={{ padding: '1rem 1.25rem' }}>
        <div className="row g-3 align-items-end">
          <div className="col-12 col-md-4">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>
              Application
            </label>
            <select
              className="pt-form-control"
              style={{ width: '100%', fontSize: '13px' }}
              value={selectedApplicationId}
              onChange={(e) => handleAppChange(e.target.value)}
            >
              <option value="all">Toutes les applications</option>
              {allApplications.map((a) => (
                <option key={a.id} value={a.id}>{a.name}</option>
              ))}
            </select>
          </div>
          <div className="col-12 col-md-4">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>
              Scénario
            </label>
            <select
              className="pt-form-control"
              style={{ width: '100%', fontSize: '13px' }}
              value={selectedScenarioId}
              onChange={(e) => handleScenarioChange(e.target.value)}
            >
              <option value="all">Tous les scénarios</option>
              {scenariosForSelectedApp.map((sc) => (
                <option key={sc.id} value={sc.id}>
                  {sc.name}{selectedApplicationId === 'all' ? ` (${appById.get(String(sc.applicationId))?.name ?? ''})` : ''}
                </option>
              ))}
            </select>
          </div>
          <div className="col-12 col-md-4">
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', marginBottom: '6px', display: 'block' }}>
              Étape
            </label>
            <select
              className="pt-form-control"
              style={{ width: '100%', fontSize: '13px' }}
              value={selectedStepId}
              onChange={(e) => setSelectedStepId(e.target.value)}
              disabled={!selectedScenario}
            >
              <option value="all">Toutes les étapes</option>
              {stepsForSelectedScenario.map((st) => (
                <option key={st.id} value={st.id}>{st.name}</option>
              ))}
            </select>
          </div>
        </div>
        <div className="d-flex align-items-center gap-2 mt-3" style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
          <i className="bi bi-funnel-fill" style={{ color: 'var(--pt-primary)' }}></i>
          Statistiques affichées pour : <strong style={{ color: 'var(--pt-text)' }}>{scopeLabel}</strong>
          {dataLoading && <span className="ms-2"><i className="bi bi-arrow-repeat pt-spin"></i> Chargement...</span>}
        </div>
      </div>

      {/* 5 KPI Cards */}
      <div className="row g-3 mb-4">
        {kpis.map((kpi, idx) => (
          <div key={idx} className="col-12 col-sm-6 col-lg col-xl">
            <div className="pt-stat-card h-100">
              <div className="stat-header">
                <div>
                  <div className="stat-label">{kpi.title}</div>
                  <div className="stat-value">{kpi.value}</div>
                  <div className="stat-trend neutral">{kpi.subtitle}</div>
                </div>
                <div className={`stat-icon ${kpi.color}`}>
                  <i className={`bi ${kpi.icon}`}></i>
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* 3 Charts */}
      <div className="row g-3 mb-4">
        {/* Chart 1: Utilisateurs simultanés */}
        <div className="col-12 col-lg-6">
          <div className="pt-card h-100">
            <div className="d-flex justify-content-between align-items-center mb-3">
              <div>
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                  Utilisateurs simultanés
                </h6>
                <small style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>
                  Nombre de VUs virtuels actifs sur la période
                </small>
              </div>
              <span className="pt-pill info">Temps réel</span>
            </div>
            <div style={{ height: '260px' }}>
              <Line data={vusData} options={vusOptions} />
            </div>
          </div>
        </div>

        {/* Chart 2: Temps de réponse */}
        <div className="col-12 col-lg-6">
          <div className="pt-card h-100">
            <div className="d-flex justify-content-between align-items-center mb-3">
              <div>
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                  Temps de réponse
                </h6>
                <small style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>
                  Moyenne par exécution (ms)
                </small>
              </div>
            </div>
            <div style={{ height: '260px' }}>
              <Line data={responseTimeData} options={responseTimeOptions} />
            </div>
          </div>
        </div>

        {/* Chart 3: Taux d'erreur */}
        <div className="col-12">
          <div className="pt-card">
            <div className="d-flex justify-content-between align-items-center mb-3">
              <div>
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                  Taux derreur
                </h6>
                <small style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>
                  Pourcentage de requêtes en erreur au cours du temps
                </small>
              </div>
              <span className="pt-pill danger">Seuil: 5%</span>
            </div>
            <div style={{ height: '220px' }}>
              <Line data={errorRateData} options={errorRateOptions} />
            </div>
          </div>
        </div>
      </div>

      {/* Tableaux contextuels : leur contenu s'adapte au niveau consulté */}
      <div className="row g-3 mb-4">
        {scopeLevel === 'all' && (
          <>
            {/* Table 1: Top Applications par requetes */}
            <div className="col-12 col-lg-4">
              <div className="pt-card h-100" style={{ padding: 0 }}>
                <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                  <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                    Top Applications par requetes
                  </h6>
                  <small style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>
                    Applications générant le plus de trafic
                  </small>
                </div>
                <div className="pt-table-wrapper">
                  <table className="pt-table">
                    <thead>
                      <tr>
                        <th>Application</th>
                        <th style={{ textAlign: 'right' }}>Requêtes</th>
                        <th style={{ textAlign: 'right', width: '30%' }}>Part</th>
                      </tr>
                    </thead>
                    <tbody>
                      {topApplications.map((app, i) => (
                        <tr
                          key={i}
                          onClick={() => handleAppChange(app.appId)}
                          style={{ cursor: 'pointer' }}
                          title="Voir les statistiques de cette application"
                        >
                          <td>
                            <span style={{ fontSize: '13px', fontWeight: 600 }}>{app.name}</span>
                          </td>
                          <td style={{ textAlign: 'right', fontSize: '13px' }}>{app.reqs}</td>
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

            {/* Table 2: Top Scenarios par duree (vue globale) */}
            <div className="col-12 col-lg-4">
              <div className="pt-card h-100" style={{ padding: 0 }}>
                <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                  <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                    Top Scenarios par duree
                  </h6>
                  <small style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>
                    Scénarios les plus lents à s'exécuter
                  </small>
                </div>
                <div className="pt-table-wrapper">
                  <table className="pt-table">
                    <thead>
                      <tr>
                        <th>Scénario</th>
                        <th style={{ textAlign: 'right' }}>Durée moy.</th>
                        <th style={{ textAlign: 'right' }}>Statut</th>
                      </tr>
                    </thead>
                    <tbody>
                      {topScenarios.map((sc, i) => (
                        <tr
                          key={i}
                          onClick={() => handleScenarioChange(sc.id)}
                          style={{ cursor: 'pointer' }}
                          title="Voir ce scénario"
                        >
                          <td>
                            <span style={{ fontSize: '13px', fontWeight: 600 }}>{sc.name}</span>
                          </td>
                          <td style={{ textAlign: 'right', fontSize: '13px', color: 'var(--pt-primary)', fontWeight: 600 }}>
                            {sc.duration}
                          </td>
                          <td style={{ textAlign: 'right' }}>
                            <span className={`pt-pill ${sc.color}`}>
                              {sc.status}
                            </span>
                          </td>
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
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                  Scénarios de {selectedAppName}
                </h6>
                <small style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>
                  Cliquez sur un scénario pour voir uniquement ses statistiques
                </small>
              </div>
              <div className="pt-table-wrapper">
                <table className="pt-table">
                  <thead>
                    <tr>
                      <th>Scénario</th>
                      <th style={{ textAlign: 'right' }}>Étapes</th>
                      <th style={{ textAlign: 'right' }}>Durée moy.</th>
                      <th style={{ textAlign: 'right' }}>Statut</th>
                    </tr>
                  </thead>
                  <tbody>
                    {scenariosOfSelectedApp.length === 0 ? (
                      <tr>
                        <td colSpan={4} className="text-center py-4 text-muted">
                          Aucun scénario pour cette application.
                        </td>
                      </tr>
                    ) : (
                      scenariosOfSelectedApp.map((sc, i) => (
                        <tr
                          key={i}
                          onClick={() => handleScenarioChange(sc.id)}
                          style={{ cursor: 'pointer' }}
                          title="Voir ce scénario"
                        >
                          <td><span style={{ fontSize: '13px', fontWeight: 600 }}>{sc.name}</span></td>
                          <td style={{ textAlign: 'right', fontSize: '13px', color: 'var(--pt-text-muted)' }}>{sc.stepCount}</td>
                          <td style={{ textAlign: 'right', fontSize: '13px', color: 'var(--pt-primary)', fontWeight: 600 }}>{sc.duration}</td>
                          <td style={{ textAlign: 'right' }}>
                            <span className={`pt-pill ${sc.color}`}>{sc.status}</span>
                          </td>
                        </tr>
                      ))
                    )}
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
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                  Étapes de {selectedScenario?.name}
                </h6>
                <small style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>
                  Cliquez sur une étape pour voir uniquement ses statistiques
                </small>
              </div>
              <div className="pt-table-wrapper">
                <table className="pt-table">
                  <thead>
                    <tr>
                      <th>Méthode</th>
                      <th>Étape</th>
                      <th>URL / Ressource</th>
                      <th style={{ textAlign: 'right' }}>Durée moy.</th>
                    </tr>
                  </thead>
                  <tbody>
                    {stepsOfSelectedScenario.length === 0 ? (
                      <tr>
                        <td colSpan={4} className="text-center py-4 text-muted">
                          Aucune étape définie pour ce scénario.
                        </td>
                      </tr>
                    ) : (
                      stepsOfSelectedScenario.map((st) => (
                        <tr
                          key={st.id}
                          onClick={() => setSelectedStepId(String(st.id))}
                          style={{ cursor: 'pointer' }}
                          title="Voir uniquement cette étape"
                        >
                          <td>
                            <span
                              style={{
                                padding: '0.15rem 0.5rem', borderRadius: '4px', fontSize: '11px', fontWeight: 700,
                                background: st.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)',
                                color: st.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)',
                              }}
                            >
                              {st.method}
                            </span>
                          </td>
                          <td><span style={{ fontSize: '13px', fontWeight: 600 }}>{st.name}</span></td>
                          <td>
                            <code style={{ fontSize: '12px', color: 'var(--pt-primary)', background: 'rgba(79,70,229,0.06)', padding: '0.1rem 0.35rem', borderRadius: '4px' }}>
                              {st.url}
                            </code>
                          </td>
                          <td style={{ textAlign: 'right', fontSize: '13px', color: 'var(--pt-primary)', fontWeight: 600 }}>{st.duration} ms</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {scopeLevel === 'step' && selectedStep && (
          <div className="col-12 col-lg-8">
            <div className="pt-card h-100">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '1rem' }}>
                Détails de l'étape sélectionnée
              </h6>
              <div className="row g-3">
                <div className="col-6 col-md-3">
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>Méthode</div>
                  <span
                    style={{
                      padding: '0.2rem 0.6rem', borderRadius: '6px', fontSize: '12px', fontWeight: 700,
                      background: selectedStep.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)',
                      color: selectedStep.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)',
                    }}
                  >
                    {selectedStep.method}
                  </span>
                </div>
                <div className="col-6 col-md-9">
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>Nom de l'étape</div>
                  <div style={{ fontSize: '14px', fontWeight: 600 }}>{selectedStep.name}</div>
                </div>
                <div className="col-12">
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>URL / Ressource</div>
                  <code style={{ fontSize: '13px', color: 'var(--pt-primary)', background: 'rgba(79,70,229,0.06)', padding: '0.25rem 0.5rem', borderRadius: '4px', display: 'inline-block' }}>
                    {selectedStep.url}
                  </code>
                </div>
                <div className="col-12">
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>Scénario parent</div>
                  <button
                    onClick={() => setSelectedStepId('all')}
                    style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '13px', color: 'var(--pt-primary)', fontWeight: 600 }}
                  >
                    <i className="bi bi-arrow-return-left me-1"></i>{selectedScenario?.name}
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {scopeLevel === 'step' && selectedStep && (
          <div className="col-12 col-lg-4">
            <div className="pt-card h-100">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '1rem' }}>
                <i className="bi bi-cpu me-2 text-primary"></i>Consommation serveur
              </h6>
              {stepAvgCpu == null ? (
                <p className="text-muted mb-0" style={{ fontSize: '12.5px' }}>
                  <i className="bi bi-info-circle me-1"></i>
                  Aucun relevé CPU/RAM pour cette étape — vérifie que l'application a une URL de monitoring configurée et qu'un test a tourné depuis.
                </p>
              ) : (
                <div className="row g-3">
                  <div className="col-6">
                    <div className="pt-stat-card" style={{ padding: '0.75rem' }}>
                      <div className="stat-header">
                        <span className="stat-label">CPU moyen</span>
                        <div className="stat-icon orange"><i className="bi bi-cpu"></i></div>
                      </div>
                      <div className="stat-value" style={{ fontSize: '20px', color: stepAvgCpu > 85 ? 'var(--pt-danger)' : stepAvgCpu > 60 ? 'var(--pt-warning)' : 'var(--pt-success)' }}>
                        {stepAvgCpu}%
                      </div>
                    </div>
                  </div>
                  <div className="col-6">
                    <div className="pt-stat-card" style={{ padding: '0.75rem' }}>
                      <div className="stat-header">
                        <span className="stat-label">RAM moyenne</span>
                        <div className="stat-icon purple"><i className="bi bi-memory"></i></div>
                      </div>
                      <div className="stat-value" style={{ fontSize: '20px', color: (stepAvgRam ?? 0) > 85 ? 'var(--pt-danger)' : (stepAvgRam ?? 0) > 60 ? 'var(--pt-warning)' : 'var(--pt-success)' }}>
                        {stepAvgRam}%
                      </div>
                    </div>
                  </div>
                  <div className="col-12">
                    <span className="pt-pill neutral" style={{ fontSize: '10.5px' }}>
                      <i className="bi bi-broadcast me-1"></i>{stepServerSamples.length} relevé{stepServerSamples.length > 1 ? 's' : ''}
                    </span>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Top Erreurs : reste affiché à tous les niveaux */}
        <div className={scopeLevel === 'all' ? 'col-12 col-lg-4' : 'col-12 col-lg-4'}>
          <div className="pt-card h-100" style={{ padding: 0 }}>
            <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
              <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                Top Erreurs
              </h6>
              <small style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>
                Principaux codes d'erreur rencontrés
              </small>
            </div>
            <div className="pt-table-wrapper">
              <table className="pt-table">
                <thead>
                  <tr>
                    <th>Code & Description</th>
                    <th style={{ textAlign: 'right' }}>Total</th>
                    <th style={{ textAlign: 'right' }}>Taux</th>
                  </tr>
                </thead>
                <tbody>
                  {topErrors.map((err, i) => (
                    <tr key={i}>
                      <td>
                        <div className="d-flex align-items-center gap-2">
                          <span className="pt-pill danger" style={{ fontWeight: 700 }}>
                            {err.code}
                          </span>
                          <span style={{ fontSize: '12.5px', color: 'var(--pt-text)' }}>{err.name}</span>
                        </div>
                      </td>
                      <td style={{ textAlign: 'right', fontSize: '13px', fontWeight: 600 }}>{err.count}</td>
                      <td style={{ textAlign: 'right', fontSize: '12.5px', color: 'var(--pt-danger)' }}>{err.rate}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      {/* Chronologie & Logs de l'étape sélectionnée — même esprit que Détail
          exécution (voir ExecutionDetail.tsx), mais agrégé sur TOUTES les
          exécutions réelles de cette étape plutôt qu'une seule. */}
      {scopeLevel === 'step' && selectedStep && (
        <>
          <div className="pt-card mb-3">
            <div className="pt-card-title mb-3">
              <i className="bi bi-hourglass-split me-2 text-primary"></i>
              Chronologie des requêtes réelles — {selectedStep.name}
            </div>
            {matchingResults.length === 0 ? (
              <p className="text-muted mb-0" style={{ fontSize: '13px' }}>Aucune requête envoyée pour le moment sur cette étape.</p>
            ) : (
              <div style={{ position: 'relative', paddingLeft: '26px', maxHeight: '320px', overflowY: 'auto' }}>
                <div style={{ position: 'absolute', left: '8px', top: '6px', bottom: '6px', width: '2px', background: 'var(--pt-border)' }}></div>
                {matchingResults.slice().reverse().map(({ result: r, execution: e }, idx) => {
                  const type = r.status === 'success' ? 'success' : r.status === 'skipped' ? 'skipped' : 'warning'
                  const dotColor = type === 'success' ? 'var(--pt-success)' : type === 'skipped' ? 'var(--pt-text-light)' : 'var(--pt-warning)'
                  const dotIcon = type === 'success' ? 'bi-check' : type === 'skipped' ? 'bi-dash' : 'bi-exclamation'
                  return (
                    <div key={idx} className="d-flex align-items-baseline flex-wrap" style={{ position: 'relative', gap: '6px 10px', padding: '5px 0' }}>
                      <span style={{
                        position: 'absolute', left: '-26px', top: '6px', width: '17px', height: '17px', borderRadius: '50%',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        background: dotColor, color: 'white', fontSize: '10px', flexShrink: 0,
                      }}>
                        <i className={`bi ${dotIcon}`}></i>
                      </span>
                      <Link to={`/executions/detail/${e.id}`} className="fw-semibold text-decoration-none" style={{ fontSize: '12.5px' }}>
                        Exécution #{e.id}{r.vu !== undefined ? ` · VU ${r.vu + 1}` : ''}
                      </Link>
                      <span className="text-muted font-monospace" style={{ fontSize: '11px', wordBreak: 'break-word' }}>
                        {r.status === 'success'
                          ? `${r.request?.method} ${r.request?.url} → ${r.httpStatus} (${r.responseTimeMs} ms)`
                          : r.status === 'skipped'
                          ? 'Étape ignorée — aucune requête envoyée.'
                          : (r.error ?? `Échec sur ${r.request?.method} ${r.request?.url}`)}
                      </span>
                      <span className="text-muted" style={{ fontSize: '10.5px', marginLeft: 'auto' }}>
                        {new Date(e.startedAt).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })}
                      </span>
                    </div>
                  )
                })}
              </div>
            )}
          </div>

          <div className="pt-card mb-4">
            <div className="pt-card-title mb-3"><i className="bi bi-terminal me-2 text-info"></i>Logs — {selectedStep.name}</div>
            <div style={{ maxHeight: '260px', overflowY: 'auto', fontFamily: 'monospace', fontSize: '12px', background: 'var(--pt-bg)', borderRadius: '8px', padding: '0.75rem', border: '1px solid var(--pt-border)' }}>
              {matchingResults.length === 0 ? (
                <p className="text-muted mb-0">Aucun log disponible.</p>
              ) : matchingResults.slice().reverse().map(({ result: r, execution: e }, idx) => {
                const level = r.status === 'success' ? 'INFO' : r.status === 'skipped' ? 'SKIP' : 'ERROR'
                return (
                  <div key={idx} className="d-flex gap-2 mb-2 pb-2 border-bottom border-light-subtle align-items-start">
                    <span className="text-muted" style={{ minWidth: '80px' }}>#{e.id}{r.vu !== undefined ? `.VU${r.vu + 1}` : ''}</span>
                    <span className={`badge ${level === 'ERROR' ? 'bg-danger text-white' : level === 'SKIP' ? 'bg-secondary-subtle text-secondary' : 'bg-info-subtle text-info'}`} style={{ minWidth: '45px', fontSize: '10px' }}>{level}</span>
                    <span className="text-dark" style={{ wordBreak: 'break-word' }}>
                      {r.status === 'success'
                        ? `${r.request?.method} ${r.request?.url} → ${r.httpStatus} en ${r.responseTimeMs}ms`
                        : r.status === 'skipped'
                        ? 'Étape ignorée (inactive)'
                        : `${r.error} — ${r.request?.method} ${r.request?.url}`}
                    </span>
                  </div>
                )
              })}
            </div>
          </div>
        </>
      )}

      {/* Footer */}
      <div
        className="d-flex justify-content-between align-items-center flex-wrap gap-2 pt-3"
        style={{ borderTop: '1px solid var(--pt-border)', fontSize: '12px', color: 'var(--pt-text-muted)' }}
      >
        <div className="d-flex align-items-center gap-2">
          <i className="bi bi-clock-history"></i>
          <span>Metriques mises a jour toutes les 5 minutes</span>
        </div>
        <div className="d-flex align-items-center gap-2">
          <i className="bi bi-globe"></i>
          <span>Fuseau horaire UTC+01:00 Casablanca</span>
        </div>
      </div>
    </div>
  )
}

export default Metriques
