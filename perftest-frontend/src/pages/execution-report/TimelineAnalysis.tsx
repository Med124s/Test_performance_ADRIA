import { useEffect, useMemo, useState } from 'react'
import { Bar, Line } from 'react-chartjs-2'
import {
  BarElement, CategoryScale, Chart as ChartJS, Filler, Legend, LinearScale, LineElement, Plugin, PointElement, Tooltip,
} from 'chart.js'
import { BackendExecutionTimeline } from '../../types/backendContracts'
import { executionsBackendApi } from '../../services/api/executionsBackend'
import { ApiError } from '../../services/api/httpClient'
import { ERROR_COLORS, STEP_COLORS, errorsByType, formatT, isLongRun, perSecond, slowestSteps } from './timelineModel'
import './timelineAnalysis.css'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, BarElement, Tooltip, Legend, Filler)

// ============================================================
// « Analyse dans le temps » du rapport : points clés, graphique global
// (utilisateurs actifs, débit, P95), P95 / débit par étape, erreurs par
// seconde et par type. Données calculées côté serveur
// (GET /api/executions/{id}/timeline) : jamais toutes les requêtes dans le
// navigateur. Périodes de pause grisées (courbes interrompues, pas à zéro).
// ============================================================

const INSIGHT_ICON: Record<BackendExecutionTimeline['insights'][number]['kind'], string> = {
  PLATEAU: 'bi-graph-up-arrow text-warning',
  NO_PLATEAU: 'bi-check2-circle text-success',
  DEGRADATION: 'bi-hourglass-split text-warning',
  ERRORS: 'bi-exclamation-triangle text-danger',
  INFO: 'bi-info-circle text-muted',
}

function cssVar(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/** Grise les intervalles en pause (opacité selon la part de l'intervalle passée en pause). */
const pauseShading: Plugin<'line' | 'bar'> = {
  id: 'pauseShading',
  beforeDatasetsDraw(chart, _args, options) {
    const ratios = (options as { ratios?: number[] }).ratios ?? []
    const x = chart.scales.x
    if (!x || ratios.every((r) => !r)) return
    const { ctx, chartArea } = chart
    const width = (chartArea.right - chartArea.left) / Math.max(1, ratios.length)
    ctx.save()
    ratios.forEach((r, i) => {
      if (!r) return
      ctx.fillStyle = `rgba(148, 163, 184, ${0.15 + 0.25 * r})`
      ctx.fillRect(x.getPixelForValue(i) - width / 2, chartArea.top, width, chartArea.bottom - chartArea.top)
    })
    ctx.restore()
  },
}

export default function TimelineAnalysis({ executionId }: { executionId: string }) {
  const [tl, setTl] = useState<BackendExecutionTimeline | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [showAvg, setShowAvg] = useState(false)
  const [stepMetric, setStepMetric] = useState<'p95' | 'rps'>('p95')
  const [selectedSteps, setSelectedSteps] = useState<Set<number> | null>(null)
  const [errorStep, setErrorStep] = useState<string>('')

  useEffect(() => {
    let alive = true
    executionsBackendApi.getTimeline(executionId)
      .then((r) => { if (alive) setTl(r) })
      .catch((err) => { if (alive) setError(err instanceof ApiError ? err.message : 'Analyse dans le temps indisponible.') })
    return () => { alive = false }
  }, [executionId])

  const hasData = !!tl && tl.source !== 'NONE' && Array.isArray(tl.t)
  const long = hasData ? isLongRun(tl) : false
  const labels = useMemo(() => (hasData ? tl.t.map((s) => formatT(s, long)) : []), [tl, hasData, long])
  const steps = selectedSteps ?? new Set(hasData ? tl.steps.map((_, i) => i) : [])
  const errorData = useMemo(() => (hasData ? errorsByType(tl, errorStep === '' ? null : Number(errorStep)) : null), [tl, hasData, errorStep])

  if (error) return <div className="pt-alert-banner warning mb-4"><i className="bi bi-info-circle"></i>{error}</div>
  if (!tl) return <div className="pt-card mb-4 text-muted" style={{ fontSize: '13px' }}><span className="spinner-border spinner-border-sm me-2"></span>Calcul de l’analyse dans le temps…</div>
  if (tl.source === 'NONE') return <div className="pt-alert-banner warning mb-4" role="status"><i className="bi bi-info-circle"></i>{tl.message}</div>

  const text = cssVar('--pt-text-muted', '#6b7280')
  const grid = 'rgba(148, 163, 184, 0.18)'
  const bucket = tl.bucketSeconds
  const pausePluginOptions = { ratios: tl.pausedRatio }
  const xScale = { ticks: { color: text, maxTicksLimit: 12, maxRotation: 0 }, grid: { display: false }, title: { display: true, text: bucket > 1 ? `Temps (intervalles de ${bucket} s)` : 'Temps', color: text } }
  const legend = { position: 'bottom' as const, labels: { color: text, boxWidth: 12, font: { size: 11 } } }

  // ---- 1. graphique global
  const globalData = {
    labels,
    datasets: [
      ...(tl.virtualUsersAvailable ? [{ label: 'Utilisateurs virtuels actifs', data: tl.activeVirtualUsers, yAxisID: 'yVu', stepped: true as const,
        borderColor: '#64748B', backgroundColor: 'rgba(100, 116, 139, 0.12)', fill: true, pointRadius: 0, borderWidth: 2 }] : []),
      { label: 'Débit (req/s)', data: tl.throughput, yAxisID: 'yRps', borderColor: '#4F46E5', backgroundColor: '#4F46E5', pointRadius: 0, borderWidth: 2, tension: 0.2 },
      { label: 'P95 (ms)', data: tl.p95Ms, yAxisID: 'yMs', borderColor: '#EF4444', backgroundColor: '#EF4444', pointRadius: 0, borderWidth: 2, tension: 0.2 },
      ...(showAvg ? [{ label: 'Moyenne (ms)', data: tl.avgMs, yAxisID: 'yMs', borderColor: '#F59E0B', backgroundColor: '#F59E0B', pointRadius: 0, borderWidth: 1.5, borderDash: [5, 4], tension: 0.2 }] : []),
    ],
  }
  const globalOptions = {
    responsive: true, maintainAspectRatio: false, spanGaps: false, animation: false as const,
    interaction: { mode: 'index' as const, intersect: false },
    plugins: {
      legend,
      pauseShading: pausePluginOptions,
      tooltip: {
        callbacks: {
          title: (items: { dataIndex: number }[]) => `À ${labels[items[0]?.dataIndex ?? 0]}${bucket > 1 ? ` (+${bucket} s)` : ''}`,
          footer: (items: { dataIndex: number }[]) => {
            const i = items[0]?.dataIndex ?? 0
            return [`Erreurs : ${tl.errors[i]}`, ...(tl.pausedRatio[i] > 0 ? [`En pause : ${Math.round(tl.pausedRatio[i] * 100)} % de l’intervalle`] : [])]
          },
        },
      },
    },
    scales: {
      x: xScale,
      yVu: { type: 'linear' as const, position: 'left' as const, display: tl.virtualUsersAvailable, beginAtZero: true, ticks: { color: text, precision: 0 }, grid: { color: grid }, title: { display: true, text: 'Utilisateurs', color: text } },
      yRps: { type: 'linear' as const, position: 'right' as const, beginAtZero: true, ticks: { color: '#4F46E5' }, grid: { display: false }, title: { display: true, text: 'req/s', color: '#4F46E5' } },
      yMs: { type: 'linear' as const, position: 'right' as const, beginAtZero: true, ticks: { color: '#EF4444' }, grid: { display: false }, title: { display: true, text: 'ms', color: '#EF4444' } },
    },
  }

  // ---- 2. par étape
  const stepData = {
    labels,
    datasets: tl.steps.map((s, i) => ({ s, i })).filter(({ i }) => steps.has(i)).map(({ s, i }) => ({
      label: s.name, data: stepMetric === 'p95' ? s.p95Ms : s.throughput, borderColor: STEP_COLORS[i % STEP_COLORS.length],
      backgroundColor: STEP_COLORS[i % STEP_COLORS.length], pointRadius: 0, borderWidth: 2, tension: 0.2,
    })),
  }
  const stepOptions = {
    responsive: true, maintainAspectRatio: false, spanGaps: false, animation: false as const,
    interaction: { mode: 'index' as const, intersect: false },
    plugins: { legend, pauseShading: pausePluginOptions, tooltip: { callbacks: { title: (items: { dataIndex: number }[]) => `À ${labels[items[0]?.dataIndex ?? 0]}` } } },
    scales: { x: xScale, y: { beginAtZero: true, ticks: { color: text }, grid: { color: grid }, title: { display: true, text: stepMetric === 'p95' ? 'P95 (ms)' : 'req/s', color: text } } },
  }
  const toggleStep = (i: number) => {
    const next = new Set(steps)
    if (next.has(i)) next.delete(i)
    else next.add(i)
    setSelectedSteps(next)
  }

  // ---- 3. erreurs
  const errorChart = errorData && errorData.types.length > 0 ? {
    labels,
    datasets: errorData.types.map((type, k) => ({
      label: type, data: errorData.series[k].map((c) => perSecond(c, bucket)), backgroundColor: ERROR_COLORS[k % ERROR_COLORS.length], stack: 'errors',
    })),
  } : null
  const errorOptions = {
    responsive: true, maintainAspectRatio: false, animation: false as const,
    interaction: { mode: 'index' as const, intersect: false },
    plugins: { legend, pauseShading: pausePluginOptions, tooltip: { callbacks: { title: (items: { dataIndex: number }[]) => `À ${labels[items[0]?.dataIndex ?? 0]}` } } },
    scales: { x: { ...xScale, stacked: true }, y: { stacked: true, beginAtZero: true, ticks: { color: text }, grid: { color: grid }, title: { display: true, text: 'erreurs / s', color: text } } },
  }

  return (
    <section className="ta-section mb-4" aria-labelledby="ta-title">
      <div className="d-flex align-items-center gap-2 mb-2" style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-activity" style={{ color: 'var(--pt-primary)' }}></i> <span id="ta-title">ANALYSE DANS LE TEMPS</span>
      </div>

      <div className="pt-card mb-3">
        <h5 className="ta-title"><i className="bi bi-lightbulb text-primary me-2"></i>Points clés</h5>
        <ul className="ta-insights">
          {tl.insights.map((ins, i) => (
            <li key={i}><i className={`bi ${INSIGHT_ICON[ins.kind]}`} aria-hidden="true"></i><span>{ins.text}</span></li>
          ))}
        </ul>
        <p className="ta-note mb-0">
          Indications calculées automatiquement à partir des mesures (règles simples) : à confirmer en lisant les graphiques.
          {tl.source === 'DERIVED' && tl.virtualUsersAvailable && ' Utilisateurs actifs déduits des requêtes (exécution antérieure à leur enregistrement).'}
          {!tl.virtualUsersAvailable && ' Utilisateurs virtuels actifs non disponibles pour cette exécution.'}
          {tl.pauses.length > 0 && ' Zones grisées : exécution en pause.'}
        </p>
      </div>

      <div className="pt-card mb-3">
        <div className="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-2">
          <h5 className="ta-title mb-0"><i className="bi bi-graph-up text-primary me-2"></i>Utilisateurs, débit et temps de réponse</h5>
          <label className="ta-check"><input type="checkbox" className="form-check-input m-0" checked={showAvg} onChange={(e) => setShowAvg(e.target.checked)} /> Afficher la moyenne</label>
        </div>
        <div className="ta-chart"><Line data={globalData} options={globalOptions} plugins={[pauseShading as Plugin<'line'>]} /></div>
      </div>

      <div className="pt-card mb-3">
        <div className="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-2">
          <h5 className="ta-title mb-0"><i className="bi bi-bar-chart-steps text-primary me-2"></i>Par étape dans le temps</h5>
          <div className="d-flex gap-2 flex-wrap" role="radiogroup" aria-label="Mesure par étape">
            <label className="ta-check"><input type="radio" name="ta-metric" className="form-check-input m-0" checked={stepMetric === 'p95'} onChange={() => setStepMetric('p95')} /> P95 (ms)</label>
            <label className="ta-check"><input type="radio" name="ta-metric" className="form-check-input m-0" checked={stepMetric === 'rps'} onChange={() => setStepMetric('rps')} /> Débit (req/s)</label>
          </div>
        </div>
        <div className="ta-steps" role="group" aria-label="Étapes affichées">
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => setSelectedSteps(new Set(tl.steps.map((_, i) => i)))}>Toutes</button>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => setSelectedSteps(new Set(slowestSteps(tl, 3)))}>Les 3 plus lentes</button>
          {tl.steps.map((s, i) => (
            <label key={s.key} className="ta-check">
              <input type="checkbox" className="form-check-input m-0" checked={steps.has(i)} onChange={() => toggleStep(i)} />
              <span className="ta-swatch" style={{ background: STEP_COLORS[i % STEP_COLORS.length] }} aria-hidden="true"></span>{s.name}
            </label>
          ))}
        </div>
        <div className="ta-chart"><Line data={stepData} options={stepOptions} plugins={[pauseShading as Plugin<'line'>]} /></div>
      </div>

      <div className="pt-card mb-3">
        <div className="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-2">
          <h5 className="ta-title mb-0"><i className="bi bi-exclamation-triangle text-danger me-2"></i>Erreurs dans le temps</h5>
          <select className="pt-form-control" style={{ width: 'auto' }} value={errorStep} onChange={(e) => setErrorStep(e.target.value)} aria-label="Filtrer les erreurs par étape">
            <option value="">Toutes les étapes</option>
            {tl.steps.map((s, i) => <option key={s.key} value={i}>{s.name}</option>)}
          </select>
        </div>
        {errorChart
          ? <div className="ta-chart"><Bar data={errorChart} options={errorOptions} plugins={[pauseShading as Plugin<'bar'>]} /></div>
          : <p className="text-muted mb-0" style={{ fontSize: '13px' }}>{errorStep === '' ? 'Aucune erreur pendant l’exécution.' : 'Aucune erreur sur cette étape.'}</p>}
      </div>
    </section>
  )
}
