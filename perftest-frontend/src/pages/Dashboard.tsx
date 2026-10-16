import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { BackendApplicationResponse, BackendDashboardOverview } from '../types/backendContracts'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { dashboardBackendApi } from '../services/api/dashboardBackend'
import { useApiItem, useApiList } from '../hooks/useApiResource'
import { formatMs, formatNumber, formatPercent } from '../utils/numberFormat'
import { RANGE_OPTIONS, RangePreset, executionStatusMeta, formatDateTime, rangeBounds } from './dashboard/dashboardModel'
import LatestByScenarioTable from './dashboard/LatestByScenarioTable'
import PerformanceTrend from './dashboard/PerformanceTrend'
import RunningExecutions from './dashboard/RunningExecutions'
import './dashboard/dashboard.css'

// ============================================================
// Dashboard : répond à « Y a-t-il un problème en ce moment ? » (À surveiller, en cours),
// « Les performances s'améliorent-elles ? » (dernière exécution par scénario comparée à la
// précédente, évolution) et « Que faire maintenant ? » (liens de chaque point).
// Tout vient de GET /api/dashboard/overview (calculé côté serveur, filtré par application et
// période) ; rien n'est agrégé dans le navigateur.
// ============================================================

function Dashboard() {
  const { data: applications } = useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const [appId, setAppId] = useState('')
  const [preset, setPreset] = useState<RangePreset>('all')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const range = useMemo(() => rangeBounds(preset, customFrom, customTo), [preset, customFrom, customTo])

  const { data, loading, error, refetch } = useApiItem<BackendDashboardOverview>(
    () => dashboardBackendApi.getOverview({ applicationId: appId || undefined, ...range }), [appId, range.from, range.to])

  // Exécutions en cours : le Dashboard se met à jour tout seul toutes les 10 s (tableau paginé compris :
  // « refresh » change seulement à ce moment-là, un changement de filtre le relit déjà de lui-même).
  const hasRunning = (data?.running.length ?? 0) > 0
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    if (!hasRunning) return
    const timer = window.setInterval(() => { refetch(); setRefresh((n) => n + 1) }, 10_000)
    return () => window.clearInterval(timer)
  }, [hasRunning, refetch])

  const appName = appId ? applications.find((a) => a.id === appId)?.name ?? 'Application' : 'Toutes les applications'

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Dashboard</h1>
          <p>Vue d’ensemble des tests de performance — <strong>{appName}</strong></p>
        </div>
        <div className="d-flex align-items-center gap-2 flex-wrap">
          <TopBar searchPlaceholder="" />
        </div>
      </div>

      {/* Filtres : appliqués à tout le Dashboard (exécutions lancées dans la période). */}
      <div className="pt-card mb-3 db-filters" role="group" aria-label="Filtres du Dashboard">
        <select className="pt-form-control db-select" value={appId} onChange={(e) => setAppId(e.target.value)} aria-label="Application">
          <option value="">Toutes les applications</option>
          {applications.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
        <span className="db-filter-label"><i className="bi bi-calendar-range me-1"></i>Période</span>
        <div className="db-segmented" role="radiogroup" aria-label="Période">
          {RANGE_OPTIONS.map((o) => (
            <button key={o.key} type="button" role="radio" aria-checked={preset === o.key} className={preset === o.key ? 'active' : ''}
              onClick={() => setPreset(o.key)}>{o.label}</button>
          ))}
        </div>
        {preset === 'custom' && (
          <span className="d-inline-flex align-items-center gap-2">
            <input type="date" className="pt-form-control db-date" value={customFrom} onChange={(e) => setCustomFrom(e.target.value)} aria-label="Du" />
            <span className="db-muted">au</span>
            <input type="date" className="pt-form-control db-date" value={customTo} onChange={(e) => setCustomTo(e.target.value)} aria-label="Au" />
          </span>
        )}
      </div>

      {error && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>Impossible de charger le Dashboard : {error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => refetch()}>Réessayer</button>
        </div>
      )}

      {loading && !data ? (
        <div className="pt-empty-state"><i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i><p>Chargement du Dashboard…</p></div>
      ) : data && (
        <>
          {/* ---- cartes du haut */}
          <div className="db-kpis mb-2">
            <Kpi label="P95" value={formatMs(data.kpis.p95Ms)} hint="95 % des requêtes de la période ont répondu en moins de ce temps." icon="bi-speedometer2" />
            <Kpi label="Taux d’erreurs" value={formatPercent(data.kpis.errorRate)} hint="Requêtes en échec / requêtes envoyées sur la période." icon="bi-exclamation-triangle"
              tone={data.kpis.errorRate != null && data.kpis.errorRate > 0 ? 'bad' : undefined} />
            <Kpi label="Requêtes envoyées" value={formatNumber(data.kpis.requests, 0)} icon="bi-arrow-left-right" />
            <Kpi label="Exécutions" value={formatNumber(data.kpis.executions, 0)} hint="Lancées sur la période." icon="bi-play-btn" />
            <Kpi label="En cours" value={formatNumber(data.kpis.running, 0)} icon="bi-activity" tone={data.kpis.running > 0 ? 'info' : undefined} />
          </div>
          <div className="db-badges mb-3" aria-label="Exécutions par statut">
            <span className="pt-pill success">Réussies · {formatNumber(data.statusCounts.success, 0)}</span>
            <span className="pt-pill warning">Seuils dépassés · {formatNumber(data.statusCounts.thresholdsExceeded ?? 0, 0)}</span>
            <span className="pt-pill danger">Échouées · {formatNumber(data.statusCounts.failed, 0)}</span>
            <span className="pt-pill neutral">Annulées · {formatNumber(data.statusCounts.cancelled, 0)}</span>
            <span className="pt-pill info">En cours · {formatNumber(data.statusCounts.running, 0)}</span>
          </div>

          <RunningExecutions running={data.running} />

          {/* ---- À surveiller */}
          <div className="pt-card mb-3 db-card" data-testid="watch-block">
            <h2 className="db-title"><i className="bi bi-binoculars"></i>À surveiller</h2>
            {data.watchItems.length === 0 ? (
              <p className="db-ok mb-0"><i className="bi bi-check-circle-fill me-2"></i>✓ Aucun point d’attention</p>
            ) : (
              <ul className="db-list">
                {data.watchItems.map((w, i) => (
                  <li key={i} className={`db-watch ${w.severity}`}>
                    <i className={`bi ${w.severity === 'danger' ? 'bi-x-octagon-fill' : 'bi-exclamation-triangle-fill'}`} aria-hidden="true"></i>
                    <div className="db-watch-main">
                      <strong>{w.title}</strong>
                      {w.detail && <span className="db-muted">{w.detail}</span>}
                    </div>
                    <Link to={w.link} className="db-watch-link">{w.link.startsWith('/executions/report') ? 'Voir le rapport' : 'Ouvrir'} <i className="bi bi-arrow-right"></i></Link>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* ---- Dernière exécution par scénario (paginé côté serveur) */}
          <LatestByScenarioTable applicationId={appId || undefined} range={range} refreshKey={refresh} />

          {/* ---- Évolution / Étapes les plus lentes */}
          <div className="row g-3 mb-3">
            <div className="col-12 col-xl-7"><PerformanceTrend scenarios={data.scenarios} range={range} /></div>
            <div className="col-12 col-xl-5">
              <div className="pt-card h-100 db-card">
                <h2 className="db-title"><i className="bi bi-hourglass-split"></i>Étapes les plus lentes</h2>
                {data.slowestSteps.length === 0 ? (
                  <p className="db-empty">Aucune étape mesurée sur la période.</p>
                ) : (
                  <table className="db-mini-table" data-testid="slowest-steps">
                    <thead><tr><th>Étape</th><th className="text-end">P95</th><th className="text-end">Requêtes</th></tr></thead>
                    <tbody>
                      {data.slowestSteps.map((s, i) => (
                        <tr key={i}>
                          <td>
                            <Link to={`/executions/report/${s.executionId}`} className="fw-semibold">{s.method && <span className="db-method">{s.method}</span>}{s.stepName}</Link>
                            <div className="db-muted">{s.scenarioName}</div>
                          </td>
                          <td className="text-end">{formatMs(s.p95Ms)}</td>
                          <td className="text-end">{formatNumber(s.requests, 0)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <p className="db-note mb-0 mt-2">Dans la dernière exécution terminée de chaque scénario de la période.</p>
              </div>
            </div>
          </div>

          {/* ---- Récentes / Planifiées */}
          <div className="row g-3 mb-3">
            <div className="col-12 col-xl-7">
              <div className="pt-card h-100 db-card">
                <div className="db-card-head">
                  <h2 className="db-title mb-0"><i className="bi bi-clock-history"></i>Exécutions récentes</h2>
                  <Link to="/executions" className="db-watch-link">Toutes les exécutions <i className="bi bi-arrow-right"></i></Link>
                </div>
                {data.recent.length === 0 ? <p className="db-empty">Aucune exécution sur la période.</p> : (
                  <ul className="db-list">
                    {data.recent.map((e) => (
                      <li key={e.id} className="db-recent">
                        <Link to={e.status === 'RUNNING' || e.status === 'PAUSED' || e.status === 'QUEUED' ? `/executions/live/${e.id}` : `/executions/report/${e.id}`}
                          className="fw-semibold">{e.scenarioName}</Link>
                        <span className="db-muted">{e.applicationName} · {formatDateTime(e.startedAt)}</span>
                        <span className={`pt-pill ${executionStatusMeta(e.status).pill}`}>{executionStatusMeta(e.status).label}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
            <div className="col-12 col-xl-5">
              <div className="pt-card h-100 db-card">
                <div className="db-card-head">
                  <h2 className="db-title mb-0"><i className="bi bi-calendar-event"></i>Prochaines exécutions planifiées</h2>
                  <Link to="/planification" className="db-watch-link">Planification <i className="bi bi-arrow-right"></i></Link>
                </div>
                {data.upcoming.length === 0 ? <p className="db-empty">Aucune exécution planifiée à venir.</p> : (
                  <ul className="db-list">
                    {data.upcoming.map((u) => (
                      <li key={u.id} className="db-recent">
                        <span className="fw-semibold">{u.scenarioName}</span>
                        <span className="db-muted">{u.name}</span>
                        <span>{formatDateTime(u.nextRunAt)}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

function Kpi({ label, value, hint, icon, tone }: { label: string; value: string; hint?: string; icon: string; tone?: 'bad' | 'info' }) {
  return (
    <div className={`db-kpi ${tone ?? ''}`} title={hint}>
      <span className="db-kpi-label"><i className={`bi ${icon}`} aria-hidden="true"></i>{label}</span>
      <strong className="db-kpi-value">{value}</strong>
    </div>
  )
}

export default Dashboard
