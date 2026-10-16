import { inUserZone } from '../../utils/userTimezone'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Line } from 'react-chartjs-2'
import { CategoryScale, Chart as ChartJS, Legend, LinearScale, LineElement, PointElement, Tooltip } from 'chart.js'
import { BackendDashboardScenarioOption, BackendScenarioTrend } from '../../types/backendContracts'
import { dashboardBackendApi } from '../../services/api/dashboardBackend'
import { ApiError } from '../../services/api/httpClient'
import { formatMs, formatNumber, formatPercent } from '../../utils/numberFormat'
import { executionStatusMeta, formatDateTime } from './dashboardModel'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend)

// « Évolution des performances » : P95 et taux d'erreurs de chaque exécution terminée du scénario
// choisi sur la période ; clic sur un point = rapport de l'exécution.

export default function PerformanceTrend({ scenarios, range }: { scenarios: BackendDashboardScenarioOption[]; range: { from?: string; to?: string } }) {
  const navigate = useNavigate()
  const [scenarioId, setScenarioId] = useState<string>('')
  const [stepName, setStepName] = useState<string>('')
  const [trend, setTrend] = useState<BackendScenarioTrend | null>(null)
  const [error, setError] = useState<string | null>(null)
  const selected = scenarios.some((s) => s.scenarioId === scenarioId) ? scenarioId : scenarios[0]?.scenarioId ?? ''

  useEffect(() => {
    if (!selected) return
    let alive = true
    setError(null)
    dashboardBackendApi.getScenarioTrend(selected, range, stepName || undefined)
      .then((t) => { if (alive) setTrend(t) })
      .catch((e) => { if (alive) setError(e instanceof ApiError ? e.message : 'Évolution indisponible.') })
    return () => { alive = false }
  }, [selected, range.from, range.to, stepName]) // eslint-disable-line react-hooks/exhaustive-deps

  const points = trend && trend.scenarioId === selected ? trend.points : []
  const stepOptions = trend && trend.scenarioId === selected ? trend.steps ?? [] : []
  // Filtre par étape : P95 et taux d'erreurs de cette étape dans chaque exécution.
  const byStep = !!stepName && trend?.stepName === stepName
  const p95Of = (p: (typeof points)[number]) => (byStep ? p.stepP95Ms ?? null : p.p95Ms)
  const errOf = (p: (typeof points)[number]) => (byStep ? p.stepErrorRate ?? null : p.errorRate)
  const labels = points.map((p) => new Date(p.startedAt).toLocaleString('fr-FR', inUserZone({ day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })))
  const data = {
    labels,
    datasets: [
      { label: byStep ? `P95 de « ${stepName} » (ms)` : 'P95 (ms)', data: points.map(p95Of), yAxisID: 'yMs', borderColor: '#4F46E5', backgroundColor: '#4F46E5', pointRadius: 4, pointHoverRadius: 6, tension: 0.2 },
      { label: byStep ? `Erreurs de « ${stepName} » (%)` : 'Taux d’erreurs (%)', data: points.map(errOf), yAxisID: 'yErr', borderColor: '#EF4444', backgroundColor: '#EF4444', pointRadius: 4, pointHoverRadius: 6, borderDash: [5, 4], tension: 0.2 },
    ],
  }
  const muted = 'rgba(148, 163, 184, 0.9)'
  const options = {
    responsive: true, maintainAspectRatio: false, animation: false as const,
    interaction: { mode: 'index' as const, intersect: false },
    onClick: (_e: unknown, elements: { index: number }[]) => {
      const p = elements[0] ? points[elements[0].index] : null
      if (p) navigate(`/executions/report/${p.executionId}`)
    },
    plugins: {
      legend: { position: 'bottom' as const, labels: { boxWidth: 12, font: { size: 11 }, color: muted } },
      tooltip: {
        callbacks: {
          title: (items: { dataIndex: number }[]) => formatDateTime(points[items[0]?.dataIndex ?? 0]?.startedAt),
          label: () => '',
          afterBody: (items: { dataIndex: number }[]) => {
            const p = points[items[0]?.dataIndex ?? 0]
            if (!p) return []
            return [`Statut : ${executionStatusMeta(p.status).label}`,
              ...(byStep ? [`Étape : ${stepName} (${formatNumber(p.stepRequests ?? null, 0)} requêtes)`] : []),
              `P95 : ${formatMs(p95Of(p))}`, `Erreurs : ${formatPercent(errOf(p))}`,
              `Utilisateurs virtuels : ${formatNumber(p.virtualUsers, 0)}`, 'Clic : ouvrir le rapport']
          },
        },
      },
    },
    scales: {
      x: { ticks: { color: muted, maxRotation: 0, autoSkip: true, maxTicksLimit: 8 }, grid: { display: false } },
      yMs: { type: 'linear' as const, position: 'left' as const, beginAtZero: true, ticks: { color: '#4F46E5' }, title: { display: true, text: 'P95 (ms)', color: '#4F46E5' }, grid: { color: 'rgba(148,163,184,0.15)' } },
      yErr: { type: 'linear' as const, position: 'right' as const, beginAtZero: true, suggestedMax: 10, ticks: { color: '#EF4444' }, title: { display: true, text: 'Erreurs (%)', color: '#EF4444' }, grid: { display: false } },
    },
  }

  return (
    <div className="pt-card h-100 db-card">
      <div className="db-card-head">
        <h2 className="db-title"><i className="bi bi-graph-up"></i>Évolution des performances</h2>
        {scenarios.length > 0 && (
          <span className="d-inline-flex gap-2 flex-wrap">
            <select className="pt-form-control db-select" value={selected} onChange={(e) => { setScenarioId(e.target.value); setStepName('') }} aria-label="Scénario affiché">
              {scenarios.map((s) => <option key={s.scenarioId} value={s.scenarioId}>{s.scenarioName}</option>)}
            </select>
            <select className="pt-form-control db-select" value={stepName} onChange={(e) => setStepName(e.target.value)} aria-label="Étape affichée"
              disabled={stepOptions.length === 0}>
              <option value="">Toutes les étapes</option>
              {stepOptions.map((s) => <option key={s.name} value={s.name}>{s.method ? `${s.method} ` : ''}{s.name}</option>)}
            </select>
          </span>
        )}
      </div>
      {scenarios.length === 0 ? (
        <p className="db-empty">Aucune exécution sur la période.</p>
      ) : error ? (
        <p className="db-empty">{error}</p>
      ) : !trend || trend.scenarioId !== selected ? (
        <p className="db-empty">Chargement…</p>
      ) : points.length < 2 ? (
        <p className="db-empty" role="status">
          {points.length === 0 ? 'Aucune exécution terminée' : 'Une seule exécution terminée'} de ce scénario sur la période :
          il en faut au moins deux pour voir une évolution.
        </p>
      ) : (
        <>
          <div className="db-chart" data-testid="trend-chart"><Line data={data} options={options} /></div>
          <p className="db-note mb-0">Une exécution terminée par point, de la plus ancienne à la plus récente. Cliquez sur un point pour ouvrir son rapport.</p>
        </>
      )}
    </div>
  )
}
