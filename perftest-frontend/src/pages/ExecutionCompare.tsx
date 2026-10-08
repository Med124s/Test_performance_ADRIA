import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Line } from 'react-chartjs-2'
import { CategoryScale, Chart as ChartJS, Legend, LinearScale, LineElement, PointElement, Tooltip } from 'chart.js'
import TopBar from '../components/TopBar'
import { BackendComparisonColumn, BackendExecutionComparison } from '../types/backendContracts'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { ApiError } from '../services/api/httpClient'
import { formatMs, formatNumber, formatPercent, formatRps } from '../utils/numberFormat'
import { Direction, bestWorst, plateauReading } from './execution-compare/compareModel'
import { executionStatusMeta, formatDateTime } from './dashboard/dashboardModel'
import './execution-compare/compare.css'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend)

// ============================================================
// « Comparer des exécutions » (/executions/compare?ids=a,b,c) : une colonne par exécution, mêmes
// valeurs que leurs rapports (calcul serveur), meilleure / moins bonne valeur mises en évidence,
// débit / P95 / CPU selon la charge, P95 de chaque étape.
// ============================================================

type Row = { label: string; hint?: string; direction: Direction; value: (c: BackendComparisonColumn) => number | null; format: (c: BackendComparisonColumn) => string }

const duration = (ms: number | null) => (ms == null ? '—' : ms >= 60_000 ? `${Math.floor(ms / 60_000)} min ${String(Math.round((ms % 60_000) / 1000)).padStart(2, '0')} s` : `${formatNumber(ms / 1000, 1)} s`)

const ROWS: Row[] = [
  { label: 'Utilisateurs virtuels', direction: 'neutral', value: (c) => c.virtualUsers, format: (c) => formatNumber(c.virtualUsers, 0) },
  { label: 'Ramp-up', direction: 'neutral', value: (c) => c.rampUpSeconds, format: (c) => (c.rampUpSeconds == null ? '—' : `${c.rampUpSeconds} s`) },
  { label: 'Durée', hint: 'Durée de travail réelle, pauses exclues', direction: 'neutral', value: (c) => c.activeDurationMs, format: (c) => duration(c.activeDurationMs) },
  { label: 'Requêtes', direction: 'neutral', value: (c) => c.requests, format: (c) => formatNumber(c.requests, 0) },
  { label: 'Débit', direction: 'higher', value: (c) => c.throughput, format: (c) => formatRps(c.throughput) },
  { label: 'P95', direction: 'lower', value: (c) => c.p95Ms, format: (c) => formatMs(c.p95Ms) },
  { label: 'p99', direction: 'lower', value: (c) => c.p99Ms, format: (c) => formatMs(c.p99Ms) },
  { label: 'Taux d’erreurs', direction: 'lower', value: (c) => c.errorRate, format: (c) => formatPercent(c.errorRate) },
  { label: 'CPU max de l’application', hint: 'Processus de l’application testée (si monitoring), en % d’un cœur', direction: 'lower', value: (c) => c.cpuMaxPercent, format: (c) => (c.cpuMaxPercent == null ? '—' : formatPercent(c.cpuMaxPercent)) },
  { label: 'RAM max de l’application', hint: 'Processus de l’application testée (si monitoring)', direction: 'lower', value: (c) => c.memoryMaxMb, format: (c) => (c.memoryMaxMb == null ? '—' : `${formatNumber(c.memoryMaxMb, 0)} Mo`) },
]

export default function ExecutionCompare() {
  const [params] = useSearchParams()
  const ids = useMemo(() => (params.get('ids') ?? '').split(',').map((s) => s.trim()).filter(Boolean), [params])
  const [data, setData] = useState<BackendExecutionComparison | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    setData(null)
    setError(null)
    // Moins de 2 exécutions (ex. ouverture depuis le menu « Comparer ») : écran d'accueil, pas une erreur.
    if (ids.length < 2) return
    executionsBackendApi.compare(ids)
      .then((d) => { if (alive) setData(d) })
      .catch((e) => { if (alive) setError(e instanceof ApiError ? e.message : 'Comparaison impossible.') })
    return () => { alive = false }
  }, [ids])

  const cols = data?.executions ?? []
  const byLoad = [...cols].sort((a, b) => (a.virtualUsers ?? 0) - (b.virtualUsers ?? 0) || a.startedAt.localeCompare(b.startedAt))
  const hasCpu = byLoad.some((c) => c.cpuMaxPercent != null)
  const muted = 'rgba(148, 163, 184, 0.9)'
  const chartData = {
    labels: byLoad.map((c) => `${formatNumber(c.virtualUsers, 0)} VU`),
    datasets: [
      { label: 'Débit (req/s)', data: byLoad.map((c) => c.throughput), yAxisID: 'yRps', borderColor: '#4F46E5', backgroundColor: '#4F46E5', pointRadius: 5, tension: 0.15 },
      { label: 'P95 (ms)', data: byLoad.map((c) => c.p95Ms), yAxisID: 'yMs', borderColor: '#EF4444', backgroundColor: '#EF4444', pointRadius: 5, tension: 0.15 },
      ...(hasCpu ? [{ label: 'CPU max de l’application (%)', data: byLoad.map((c) => c.cpuMaxPercent), yAxisID: 'yCpu', borderColor: '#0EA5E9', backgroundColor: '#0EA5E9', pointRadius: 5, borderDash: [5, 4], tension: 0.15 }] : []),
    ],
  }
  const chartOptions = {
    responsive: true, maintainAspectRatio: false, animation: false as const,
    interaction: { mode: 'index' as const, intersect: false },
    plugins: { legend: { position: 'bottom' as const, labels: { boxWidth: 12, font: { size: 11 }, color: muted } } },
    scales: {
      x: { ticks: { color: muted }, grid: { display: false }, title: { display: true, text: 'Utilisateurs virtuels', color: muted } },
      yRps: { type: 'linear' as const, position: 'left' as const, beginAtZero: true, ticks: { color: '#4F46E5' }, title: { display: true, text: 'req/s', color: '#4F46E5' }, grid: { color: 'rgba(148,163,184,0.15)' } },
      yMs: { type: 'linear' as const, position: 'right' as const, beginAtZero: true, ticks: { color: '#EF4444' }, title: { display: true, text: 'ms', color: '#EF4444' }, grid: { display: false } },
      ...(hasCpu ? { yCpu: { type: 'linear' as const, position: 'right' as const, beginAtZero: true, ticks: { color: '#0EA5E9' }, title: { display: true, text: 'CPU %', color: '#0EA5E9' }, grid: { display: false } } } : {}),
    },
  }
  const reading = plateauReading(cols)

  return (
    <div className="pt-content">
      <div className="mb-3">
        <Link to="/executions" className="text-decoration-none d-inline-flex align-items-center gap-1 text-muted small fw-semibold">
          <i className="bi bi-chevron-left"></i> Retour aux exécutions
        </Link>
      </div>
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Comparer des exécutions</h1>
          <p>{cols.length > 0 ? `${cols.length} exécutions, de la plus ancienne à la plus récente` : 'Comparaison côte à côte'}</p>
        </div>
        <div className="d-flex align-items-center gap-2"><TopBar searchPlaceholder="" /></div>
      </div>

      {error && <div className="pt-alert-banner warning mb-3" role="status"><i className="bi bi-info-circle"></i>{error}</div>}
      {ids.length < 2 && (
        <div className="pt-card" style={{ textAlign: 'center', padding: '2.5rem 1.5rem' }}>
          <i className="bi bi-layout-three-columns" style={{ fontSize: 34, color: 'var(--pt-primary)' }} aria-hidden="true"></i>
          <h2 style={{ fontSize: 17, fontWeight: 700, margin: '0.75rem 0 0.4rem' }}>Choisissez les exécutions à comparer</h2>
          <p style={{ color: 'var(--pt-text-muted)', fontSize: 13.5, maxWidth: 520, margin: '0 auto 1rem' }}>
            Dans la page Exécutions, cochez de 2 à 4 exécutions terminées puis cliquez sur « Comparer » : P95, débit, erreurs,
            CPU et étapes s’affichent côte à côte, de la plus petite à la plus grande charge.
          </p>
          <Link to="/executions" className="pt-btn-primary" style={{ textDecoration: 'none' }}>
            <i className="bi bi-check2-square" aria-hidden="true"></i> Choisir dans Exécutions
          </Link>
        </div>
      )}
      {ids.length >= 2 && !data && !error && <div className="pt-empty-state"><i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i><p>Chargement de la comparaison…</p></div>}

      {data && (
        <>
          {data.warning && (
            <div className="pt-alert-banner warning mb-3" role="alert" data-testid="scenario-warning"><i className="bi bi-exclamation-triangle"></i>{data.warning}</div>
          )}

          <div className="pt-card mb-3" style={{ padding: 0 }}>
            <div className="pt-table-wrapper">
              <table className="pt-table cp-table" data-testid="compare-table">
                <thead>
                  <tr>
                    <th></th>
                    {cols.map((c) => (
                      <th key={c.executionId}>
                        <Link to={`/executions/report/${c.executionId}`} className="cp-col-title">{c.scenarioName}</Link>
                        <div className="cp-muted">{formatDateTime(c.startedAt)}</div>
                        <span className={`pt-pill ${executionStatusMeta(c.status).pill}`}>{executionStatusMeta(c.status).label}</span>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {ROWS.map((row) => {
                    const { best, worst } = bestWorst(cols.map(row.value), row.direction)
                    return (
                      <tr key={row.label}>
                        <th scope="row" title={row.hint}>{row.label}</th>
                        {cols.map((c, i) => (
                          <td key={c.executionId} className={i === best ? 'cp-best' : i === worst ? 'cp-worst' : undefined}
                            title={i === best ? 'Meilleure valeur' : i === worst ? 'Moins bonne valeur' : undefined}>
                            {row.format(c)}
                            {i === best && <i className="bi bi-arrow-up-circle-fill ms-1" aria-label="meilleure"></i>}
                            {i === worst && <i className="bi bi-arrow-down-circle-fill ms-1" aria-label="moins bonne"></i>}
                          </td>
                        ))}
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            <p className="cp-note">
              <span className="cp-best cp-legend">vert</span> meilleure valeur · <span className="cp-worst cp-legend">rouge</span> moins bonne.
              Débit : plus haut = mieux ; temps, erreurs, CPU et RAM : plus bas = mieux. Valeurs identiques à celles des rapports.
            </p>
          </div>

          <div className="row g-3 mb-3">
            <div className="col-12 col-xl-6">
              <div className="pt-card h-100">
                <h2 className="cp-title"><i className="bi bi-graph-up"></i>Débit, P95 et CPU selon la charge</h2>
                <div className="cp-chart" data-testid="load-chart"><Line data={chartData} options={chartOptions} /></div>
                {reading && <p className="cp-reading mb-0" data-testid="plateau-reading"><i className="bi bi-lightbulb me-1"></i>{reading}</p>}
              </div>
            </div>
            <div className="col-12 col-xl-6">
              <div className="pt-card h-100" style={{ padding: 0 }}>
                <h2 className="cp-title" style={{ padding: '1rem 1rem 0' }}><i className="bi bi-list-ol"></i>P95 par étape</h2>
                {data.steps.length === 0 ? <p className="cp-muted" style={{ padding: '0 1rem 1rem' }}>Aucune étape mesurée.</p> : (
                  <div className="pt-table-wrapper">
                    <table className="pt-table cp-table" data-testid="steps-table">
                      <thead><tr><th>Étape</th>{cols.map((c) => <th key={c.executionId} className="text-end">{formatNumber(c.virtualUsers, 0)} VU<div className="cp-muted">{formatDateTime(c.startedAt)}</div></th>)}</tr></thead>
                      <tbody>
                        {data.steps.map((s) => {
                          const { best, worst } = bestWorst(s.p95Ms, 'lower')
                          return (
                            <tr key={`${s.method} ${s.name}`}>
                              <th scope="row">{s.method && <span className="cp-method">{s.method}</span>}{s.name}</th>
                              {s.p95Ms.map((v, i) => (
                                <td key={i} className={`text-end ${i === best ? 'cp-best' : i === worst ? 'cp-worst' : ''}`}>{v == null ? '—' : formatMs(v)}</td>
                              ))}
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
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
