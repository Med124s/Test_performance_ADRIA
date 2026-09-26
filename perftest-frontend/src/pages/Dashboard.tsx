import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Doughnut } from 'react-chartjs-2'
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  ArcElement,
  Title,
  Tooltip,
  Legend,
  Filler,
} from 'chart.js'
import TopBar from '../components/TopBar'
import {
  BackendApplicationResponse,
  BackendScenarioResponse,
  BackendExecutionResponse,
  BackendDashboardResponse,
  BackendApplicationDashboardResponse,
} from '../types/backendContracts'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { dashboardBackendApi } from '../services/api/dashboardBackend'
import { useApiList, useApiItem } from '../hooks/useApiResource'

ChartJS.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  ArcElement,
  Title,
  Tooltip,
  Legend,
  Filler
)

// ============================================================
// Phase 22 — cette page parle désormais au vrai backend Spring Boot pour
// le Dashboard (voir services/api/dashboardBackend.ts : exactement les
// deux endpoints réels GET /api/dashboard et GET /api/dashboard/
// applications/{id}, aucune route inventée), ainsi qu'aux Applications/
// Scénarios/Exécutions déjà migrés (Phases 17/18/20, réutilisés SANS
// modification). data/dashboardStats.ts (calcul 100% client depuis JSON
// Server) reste INCHANGÉ dans le dépôt mais n'est plus utilisé par cette
// page — aucun autre module ne l'important, sa suppression n'était pas
// nécessaire et n'a pas été faite (voir rapport Phase 22, "modifier
// uniquement ce qui est nécessaire").
//
// Champs de l'ancien Dashboard SANS équivalent Spring, retirés
// honnêtement plutôt que simulés :
//   - "Utilisateurs actifs" (VU) : aucune notion de VU dans le moteur
//     d'exécution Spring, synchrone et séquentiel (voir Phase 20) —
//     remplacé par "Étapes exécutées" (executions.totalStepsExecuted),
//     un vrai champ du DashboardResponse jusqu'ici inexploité.
//   - "Durée moyenne des tests" : DashboardResponse n'expose aucune
//     moyenne de durée d'exécution — remplacé par "Taux de succès des
//     étapes" (executions.successRate), également réel et jusqu'ici
//     inexploité.
//   - "Évolution des temps de réponse (7 jours)" : aucune série
//     temporelle n'existe côté backend (seule une moyenne globale
//     instantanée, performance.averageResponseTime) — retiré, remplacé
//     par un second donut réel "Répartition des étapes" (successfulSteps/
//     failedSteps, déjà fourni par DashboardResponse), pour ne pas ajouter
//     un troisième appel réseau (Metrics) alors que le périmètre de cette
//     phase se limite explicitement aux deux endpoints Dashboard.
//
// "Exécutions récentes" et "Scénarios les plus utilisés" ne sont pas
// fournis par DashboardResponse (agrégats uniquement, jamais de liste) :
// ils sont calculés côté client à partir d'UN SEUL appel réel supplémentaire
// à executionsBackendApi.getAll() (Phase 20, déjà migré, déjà utilisé par
// Executions.tsx) — calcul déterministe et documenté, jamais une donnée
// inventée, conforme à la règle "calcul frontend autorisé si dérivé de
// données réellement reçues".
// ============================================================

// P1-C — presets réels de plage temporelle pour le Dashboard global (voir
// GET /api/dashboard?from=&to=, DashboardServiceImpl) : les bornes sont
// calculées ICI côté client (comme dateFrom/dateTo sur Historique.tsx) et
// envoyées telles quelles au backend, qui fait le vrai filtrage — jamais un
// recalcul d'agrégat côté React. "all" = pas de plage (comportement
// identique à avant P1-C, tout l'historique).
type DashboardRangePreset = 'all' | '24h' | '7d' | '30d' | 'custom'

function computeRangeBounds(preset: DashboardRangePreset, customFrom: string, customTo: string): { from?: string; to?: string } {
  const now = new Date()
  switch (preset) {
    case '24h':
      return { from: new Date(now.getTime() - 24 * 3600 * 1000).toISOString(), to: now.toISOString() }
    case '7d':
      return { from: new Date(now.getTime() - 7 * 24 * 3600 * 1000).toISOString(), to: now.toISOString() }
    case '30d':
      return { from: new Date(now.getTime() - 30 * 24 * 3600 * 1000).toISOString(), to: now.toISOString() }
    case 'custom':
      return {
        from: customFrom ? new Date(`${customFrom}T00:00:00Z`).toISOString() : undefined,
        to: customTo ? new Date(`${customTo}T23:59:59Z`).toISOString() : undefined,
      }
    default:
      return {}
  }
}

function Dashboard() {
  const navigate = useNavigate()

  const { data: allApplications, loading: appsLoading } = useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const { data: allScenarios } = useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll())
  const { data: allExecutions } = useApiList<BackendExecutionResponse>(() => executionsBackendApi.getAll())

  const [selectedAppId, setSelectedAppId] = useState<string | 'all'>('all')
  const [rangePreset, setRangePreset] = useState<DashboardRangePreset>('all')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const rangeBounds = useMemo(() => computeRangeBounds(rangePreset, customFrom, customTo), [rangePreset, customFrom, customTo])

  const {
    data: globalDashboard,
    loading: globalLoading,
    error: globalError,
    refetch: refetchGlobal,
  } = useApiItem<BackendDashboardResponse>(() => dashboardBackendApi.getGlobal(rangeBounds), [rangeBounds.from, rangeBounds.to])

  const {
    data: appDashboard,
    loading: appDashboardLoading,
    error: appDashboardError,
    refetch: refetchAppDashboard,
  } = useApiItem<BackendApplicationDashboardResponse | null>(
    // Ne jamais appeler /api/dashboard/applications/{id} avec "all" (pas un
    // UUID) — évite un appel réseau invalide inutile lorsque la vue globale
    // est sélectionnée.
    () => (selectedAppId === 'all' ? Promise.resolve(null) : dashboardBackendApi.getByApplication(selectedAppId)),
    [selectedAppId]
  )

  const isAllScope = selectedAppId === 'all'
  const dashboardLoading = appsLoading || (isAllScope ? globalLoading : appDashboardLoading)
  const dashboardError = isAllScope ? globalError : appDashboardError
  const refetchDashboard = isAllScope ? refetchGlobal : refetchAppDashboard

  // Sous-structures identiques (scenarios/executions/performance) que la
  // vue soit globale ou par application — voir DashboardResponse /
  // ApplicationDashboardResponse (mêmes types réels).
  const scenariosSummary = isAllScope ? globalDashboard?.scenarios : appDashboard?.scenarios
  const executionsSummary = isAllScope ? globalDashboard?.executions : appDashboard?.executions
  const performanceSummary = isAllScope ? globalDashboard?.performance : appDashboard?.performance
  const applicationsSummary = globalDashboard?.applications // uniquement dispo côté vue globale

  const selectedAppName =
    selectedAppId === 'all' ? 'Toutes les applications' : allApplications.find((a) => a.id === selectedAppId)?.name ?? 'Application'

  const scenarioById = useMemo(() => new Map(allScenarios.map((s) => [s.id, s])), [allScenarios])

  // "Exécutions récentes"/"Scénarios les plus utilisés" : DashboardResponse
  // n'expose aucune liste, seulement des compteurs agrégés — calculés ici
  // à partir des vraies Executions Spring (voir note en tête de fichier).
  const executionsInScope = useMemo(() => {
    if (isAllScope) return allExecutions
    return allExecutions.filter((e) => scenarioById.get(e.scenarioId)?.applicationId === selectedAppId)
  }, [allExecutions, isAllScope, scenarioById, selectedAppId])

  const recentExecutions = useMemo(
    () =>
      [...executionsInScope]
        .sort((a, b) => (a.startedAt < b.startedAt ? 1 : -1))
        .slice(0, 3)
        .map((e) => {
          const meta =
            e.status === 'SUCCESS'
              ? { label: 'Réussie', color: 'success', icon: 'bi-check-circle-fill' }
              : e.status === 'FAILED'
              ? { label: 'Échouée', color: 'danger', icon: 'bi-x-circle-fill' }
              : e.status === 'RUNNING'
              ? { label: 'En cours', color: 'info', icon: 'bi-play-circle-fill' }
              : { label: 'Annulée', color: 'neutral', icon: 'bi-slash-circle-fill' }
          const time = new Date(e.startedAt).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
          return { name: e.scenarioName, ...meta, time }
        }),
    [executionsInScope]
  )

  const topScenarios = useMemo(() => {
    const counts = new Map<string, number>()
    executionsInScope.forEach((e) => counts.set(e.scenarioName, (counts.get(e.scenarioName) ?? 0) + 1))
    const total = executionsInScope.length
    return Array.from(counts.entries())
      .map(([name, count]) => ({ name, percent: total > 0 ? Math.round((count / total) * 100) : 0 }))
      .sort((a, b) => b.percent - a.percent)
      .slice(0, 4)
  }, [executionsInScope])

  const executionStatusData = {
    labels: ['Réussies', 'En cours', 'Échouées', 'Annulées'],
    datasets: [
      {
        data: [
          executionsSummary?.successfulExecutions ?? 0,
          executionsSummary?.runningExecutions ?? 0,
          executionsSummary?.failedExecutions ?? 0,
          executionsSummary?.cancelledExecutions ?? 0,
        ],
        backgroundColor: ['#22C55E', '#3B82F6', '#EF4444', '#A3A3A3'],
        borderWidth: 0,
        cutout: '70%',
      },
    ],
  }
  const stepsBreakdownData = {
    labels: ['Étapes réussies', 'Étapes en échec'],
    datasets: [
      {
        data: [executionsSummary?.successfulSteps ?? 0, executionsSummary?.failedSteps ?? 0],
        backgroundColor: ['#22C55E', '#EF4444'],
        borderWidth: 0,
        cutout: '70%',
      },
    ],
  }
  const doughnutOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        position: 'right' as const,
        labels: { padding: 10, usePointStyle: true, pointStyle: 'circle', font: { size: 11.5 }, color: '#6B7280' },
      },
    },
  }

  const executionsTotal = executionsSummary?.totalExecutions ?? 0
  const stepsTotal = executionsSummary?.totalStepsExecuted ?? 0

  const fmt = (value: number | null | undefined, unit = ''): string => (value == null ? 'N/D' : `${value}${unit}`)

  const hasAnyData = (applicationsSummary?.totalApplications ?? 0) > 0 || executionsTotal > 0 || (scenariosSummary?.totalScenarios ?? 0) > 0

  return (
    <div className="pt-content">
      {/* Page Header */}
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Dashboard</h1>
          <p>
            Vue d'ensemble de la plateforme (Spring Boot) — <strong>{selectedAppName}</strong>
          </p>
        </div>
        <div className="d-flex align-items-center gap-2 flex-wrap">
          <select
            className="pt-form-control"
            style={{ width: 'auto', minWidth: '220px' }}
            value={selectedAppId}
            onChange={(e) => setSelectedAppId(e.target.value === 'all' ? 'all' : e.target.value)}
          >
            <option value="all">Toutes les applications</option>
            {allApplications.map((a) => (
              <option key={a.id} value={a.id}>{a.name}</option>
            ))}
          </select>
          <TopBar searchPlaceholder="" />
        </div>
      </div>

      {/* P1-C — plage temporelle réelle (filtre GET /api/dashboard?from=&to=
          côté backend, voir DashboardServiceImpl) — uniquement pertinente
          pour la vue "Toutes les applications" (le dashboard par
          application n'a pas encore de filtre temporel, voir rapport P1-C). */}
      {isAllScope && (
        <div className="pt-card mb-3 d-flex align-items-center gap-2 flex-wrap" style={{ padding: '0.65rem 1rem' }}>
          <span style={{ fontSize: '12.5px', fontWeight: 600, color: 'var(--pt-text-muted)' }}>
            <i className="bi bi-calendar-range me-1"></i>Période
          </span>
          {([
            { key: 'all', label: 'Tout' },
            { key: '24h', label: '24h' },
            { key: '7d', label: '7 jours' },
            { key: '30d', label: '30 jours' },
            { key: 'custom', label: 'Personnalisé' },
          ] as { key: DashboardRangePreset; label: string }[]).map((opt) => (
            <button
              key={opt.key}
              className={rangePreset === opt.key ? 'pt-btn-primary' : 'pt-btn-outline'}
              style={{ fontSize: '12px', padding: '4px 12px' }}
              onClick={() => setRangePreset(opt.key)}
            >
              {opt.label}
            </button>
          ))}
          {rangePreset === 'custom' && (
            <>
              <input type="date" className="pt-form-control" style={{ width: 'auto', fontSize: '12.5px' }} value={customFrom} onChange={(e) => setCustomFrom(e.target.value)} />
              <span style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>à</span>
              <input type="date" className="pt-form-control" style={{ width: 'auto', fontSize: '12.5px' }} value={customTo} onChange={(e) => setCustomTo(e.target.value)} />
            </>
          )}
        </div>
      )}

      {dashboardError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger les données du Dashboard : {dashboardError}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => refetchDashboard()}>Réessayer</button>
        </div>
      )}

      {dashboardLoading ? (
        <div className="pt-empty-state">
          <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
          <p>Chargement du Dashboard...</p>
        </div>
      ) : !dashboardError && !hasAnyData ? (
        <div className="pt-empty-state">
          <i className="bi bi-bar-chart" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
          <p>Aucune donnée disponible pour l'instant.</p>
        </div>
      ) : (
      <>
        {/* Metric Cards - Row 1 */}
        <div className="row g-3 mb-4">
          <div className="col-12 col-md-6 col-xl">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">Temps de réponse moyen</div>
                  <div className="stat-value">{fmt(performanceSummary?.averageResponseTime, ' ms')}</div>
                </div>
                <div className="stat-icon blue"><i className="bi bi-clock-history"></i></div>
              </div>
            </div>
          </div>
          <div className="col-12 col-md-6 col-xl">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">Débit moyen</div>
                  <div className="stat-value">{fmt(performanceSummary?.averageThroughput, ' req/s')}</div>
                </div>
                <div className="stat-icon green"><i className="bi bi-lightning-charge"></i></div>
              </div>
            </div>
          </div>
          <div className="col-12 col-md-6 col-xl">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">Taux d'erreurs</div>
                  <div className="stat-value">{fmt(performanceSummary?.averageErrorRate, '%')}</div>
                </div>
                <div className="stat-icon red"><i className="bi bi-exclamation-triangle"></i></div>
              </div>
            </div>
          </div>
          <div className="col-12 col-md-6 col-xl">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">Étapes exécutées</div>
                  <div className="stat-value">{stepsTotal.toLocaleString('fr-FR')}</div>
                </div>
                <div className="stat-icon orange"><i className="bi bi-list-check"></i></div>
              </div>
            </div>
          </div>
          <div className="col-12 col-md-6 col-xl">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">Tests en cours</div>
                  <div className="stat-value">{executionsSummary?.runningExecutions ?? 0}</div>
                  <div className="stat-trend neutral" title="Le moteur d'exécution backend est synchrone : une exécution n'est quasiment jamais observée RUNNING (voir Phase 20).">actuellement</div>
                </div>
                <div className="stat-icon purple"><i className="bi bi-play-circle"></i></div>
              </div>
            </div>
          </div>
        </div>

        {/* Charts Row — les deux donuts viennent intégralement de DashboardResponse */}
        <div className="row g-3 mb-4">
          <div className="col-12 col-lg-6">
            <div className="pt-card">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '1rem' }}>Statut des exécutions</h6>
              <div style={{ position: 'relative', height: '280px' }}>
                <Doughnut data={executionStatusData} options={doughnutOptions} />
                <div style={{ position: 'absolute', top: '50%', left: '38%', transform: 'translate(-50%, -50%)', textAlign: 'center' }}>
                  <div style={{ fontSize: '32px', fontWeight: 700, color: 'var(--pt-text)' }}>{executionsTotal}</div>
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Total</div>
                </div>
              </div>
            </div>
          </div>
          <div className="col-12 col-lg-6">
            <div className="pt-card">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '1rem' }}>Répartition des étapes</h6>
              <div style={{ position: 'relative', height: '280px' }}>
                <Doughnut data={stepsBreakdownData} options={doughnutOptions} />
                <div style={{ position: 'absolute', top: '50%', left: '38%', transform: 'translate(-50%, -50%)', textAlign: 'center' }}>
                  <div style={{ fontSize: '32px', fontWeight: 700, color: 'var(--pt-text)' }}>{stepsTotal}</div>
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Total</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Metric Cards - Row 2 */}
        <div className="row g-3 mb-4">
          <div className="col-6 col-md-3 col-xl">
            <div className="pt-stat-card" role="button" onClick={() => navigate('/scenarios')} style={{ cursor: 'pointer' }}>
              <div className="stat-label">Scénarios</div>
              <div className="stat-value">{scenariosSummary?.totalScenarios ?? 0}</div>
            </div>
          </div>
          <div className="col-6 col-md-3 col-xl">
            <div className="pt-stat-card" role="button" onClick={() => navigate('/applications')} style={{ cursor: 'pointer' }}>
              <div className="stat-label">Applications</div>
              <div className="stat-value">{isAllScope ? applicationsSummary?.totalApplications ?? 0 : 1}</div>
              <div className="stat-trend neutral">{isAllScope ? 'au total' : selectedAppName}</div>
            </div>
          </div>
          <div className="col-6 col-md-3 col-xl">
            <div className="pt-stat-card" role="button" onClick={() => navigate('/executions')} style={{ cursor: 'pointer' }}>
              <div className="stat-label">Exécutions</div>
              <div className="stat-value">{executionsTotal}</div>
            </div>
          </div>
          <div className="col-6 col-md-3 col-xl">
            <div className="pt-stat-card">
              <div className="stat-label">Taux de succès des étapes</div>
              <div className="stat-value" style={{ fontSize: '22px' }}>{fmt(executionsSummary?.successRate ?? null, '%')}</div>
            </div>
          </div>
        </div>

        {/* Bottom Section - 2 columns */}
        <div className="row g-3">
          <div className="col-12 col-lg-6">
            <div className="pt-card">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '1rem' }}>Exécutions récentes</h6>
              {recentExecutions.length === 0 ? (
                <p className="text-muted mb-0" style={{ fontSize: '13px' }}>Aucune exécution pour l'instant.</p>
              ) : (
                <div className="d-flex flex-column gap-3">
                  {recentExecutions.map((exec, i) => (
                    <div key={i} className="d-flex align-items-center gap-3" role="button" onClick={() => navigate('/executions')} style={{ cursor: 'pointer' }}>
                      <i className={`bi ${exec.icon}`} style={{ color: `var(--pt-${exec.color})`, fontSize: '18px' }}></i>
                      <div className="flex-grow-1">
                        <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)' }}>{exec.name}</div>
                        <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{exec.time}</div>
                      </div>
                      <span className={`pt-pill ${exec.color}`}>{exec.label}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="col-12 col-lg-6">
            <div className="pt-card">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '1rem' }}>Scénarios les plus utilisés</h6>
              {isAllScope ? (
                // P1-C — désormais un VRAI agrégat backend (nombre
                // d'exécutions/taux de succès/temps de réponse moyen sur la
                // plage sélectionnée, voir DashboardServiceImpl#buildTopScenarios)
                // — remplace le calcul par pourcentage fait ici même avant
                // P1-C (topScenarios ci-dessous, toujours utilisé pour la
                // vue par application, qui n'a pas encore cet agrégat côté backend).
                !globalDashboard?.topScenarios || globalDashboard.topScenarios.length === 0 ? (
                  <p className="text-muted mb-0" style={{ fontSize: '13px' }}>Aucune exécution pour l'instant.</p>
                ) : (
                  <div className="d-flex flex-column gap-3">
                    {globalDashboard.topScenarios.map((scenario, i) => (
                      <div key={scenario.scenarioId} role="button" onClick={() => navigate(`/scenarios?q=${encodeURIComponent(scenario.scenarioName)}`)} style={{ cursor: 'pointer' }}>
                        <div className="d-flex justify-content-between mb-1">
                          <span style={{ fontSize: '13px', fontWeight: 500, color: 'var(--pt-text)' }}>{i + 1}. {scenario.scenarioName}</span>
                          <span style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{scenario.applicationName}</span>
                        </div>
                        <div className="d-flex justify-content-between align-items-center" style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>
                          <span>{scenario.executionCount} exécution{scenario.executionCount > 1 ? 's' : ''}</span>
                          <span>{fmt(scenario.successRate, '% succès')} · {fmt(scenario.avgResponseTime, ' ms')}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                )
              ) : topScenarios.length === 0 ? (
                <p className="text-muted mb-0" style={{ fontSize: '13px' }}>Aucune exécution pour l'instant.</p>
              ) : (
                <div className="d-flex flex-column gap-3">
                  {topScenarios.map((scenario, i) => (
                    <div key={i} role="button" onClick={() => navigate(`/scenarios?q=${encodeURIComponent(scenario.name)}`)} style={{ cursor: 'pointer' }}>
                      <div className="d-flex justify-content-between mb-1">
                        <span style={{ fontSize: '13px', fontWeight: 500, color: 'var(--pt-text)' }}>{i + 1}. {scenario.name}</span>
                        <span style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>{scenario.percent}%</span>
                      </div>
                      <div style={{ height: '6px', background: 'var(--pt-border)', borderRadius: '4px', overflow: 'hidden' }}>
                        <div style={{ height: '100%', width: `${scenario.percent}%`, background: 'var(--pt-primary)', borderRadius: '4px' }}></div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </>
      )}
    </div>
  )
}

export default Dashboard
