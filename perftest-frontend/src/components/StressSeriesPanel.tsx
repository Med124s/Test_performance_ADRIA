import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { stressSeriesBackendApi } from '../services/api/stressSeriesBackend'
import { BackendStressSeriesResponse } from '../types/backendContracts'
import { executionStatusMeta } from '../utils/executionStatus'
import { CRITERION_LABELS, stressRampLabel } from '../utils/stressResult'
import {
  SERIES_STATUS_LABELS, VERDICT_TITLES, VERDICT_TONE, currentRunIndex, runOutcomeLabel, secondsUntilNextRun,
} from '../utils/stressSeries'
import { ApiError } from '../services/api/httpClient'
import { useToast } from '../context/ToastContext'
import { saveBlob } from '../utils/download'
import '../pages/execution-report/timelineAnalysis.css'

// Série d'essais de stress : essais, pause avant l'essai suivant et point de rupture consolidé (suivi, détail, rapport).

const POLL_MS = 2000

interface Props {
  seriesId: string
  /** Exécution affichée par la page (ligne mise en avant). */
  executionId?: string
  canWrite?: boolean
  /** Suivi en direct : un nouvel essai de la série vient de démarrer. */
  onRunStarted?: (executionId: string) => void
  /** Série déjà chargée (tests). */
  initial?: BackendStressSeriesResponse
}

export default function StressSeriesPanel({ seriesId, executionId, canWrite = false, onRunStarted, initial }: Props) {
  const { showToast } = useToast()
  const [series, setSeries] = useState<BackendStressSeriesResponse | null>(initial ?? null)
  const [now, setNow] = useState(() => Date.now())
  const [cancelling, setCancelling] = useState(false)
  const [exporting, setExporting] = useState(false)
  const announced = useRef<string | null>(null)
  const running = series?.status === 'RUNNING'

  useEffect(() => {
    let alive = true
    let timer: number | undefined
    const load = () => {
      stressSeriesBackendApi.get(seriesId)
        .then((s) => {
          if (!alive) return
          setSeries(s)
          if (s.status === 'RUNNING') timer = window.setTimeout(load, POLL_MS)
        })
        .catch(() => { if (alive) timer = window.setTimeout(load, POLL_MS * 2) })
    }
    if (!initial || initial.status === 'RUNNING') load()
    return () => { alive = false; window.clearTimeout(timer) }
  }, [seriesId, initial])

  // Compte à rebours de la pause, rafraîchi chaque seconde.
  useEffect(() => {
    if (!series?.nextRunAt) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [series?.nextRunAt])

  // Nouvel essai démarré : la page de suivi passe dessus.
  useEffect(() => {
    const next = series?.currentExecutionId
    if (!next || !onRunStarted || next === executionId || announced.current === next) return
    announced.current = next
    onRunStarted(next)
  }, [series?.currentExecutionId, executionId, onRunStarted])

  if (!series) return null

  const cancel = async () => {
    setCancelling(true)
    try {
      setSeries(await stressSeriesBackendApi.cancel(series.id))
      showToast('Série d’essais annulée.', 'success')
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : 'Annulation de la série impossible.', 'danger')
    } finally {
      setCancelling(false)
    }
  }

  const exportPdf = async () => {
    setExporting(true)
    try {
      const file = await stressSeriesBackendApi.exportPdf(series.id)
      saveBlob(file.blob, file.fileName)
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : 'Export PDF de la série impossible.', 'danger')
    } finally {
      setExporting(false)
    }
  }

  const finishedRuns = series.runs.filter((r) => !['QUEUED', 'RUNNING', 'PAUSED'].includes(r.status)).length
  const wait = secondsUntilNextRun(series, now)
  const summary = series.summary
  const tone = finishedRuns > 0 ? VERDICT_TONE[summary.verdict] : 'neutral'

  return (
    <div className={`pt-card mb-3 rp-stress rp-stress-${tone}`} data-testid="stress-series">
      <div className="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-2">
        <div style={{ fontSize: '14px' }}>
          <i className="bi bi-collection me-1" aria-hidden="true"></i>
          <strong>Série de {series.requestedRuns} essais</strong>
          <span className="text-muted"> · {SERIES_STATUS_LABELS[series.status]}</span>
          {running && wait == null && <span> · essai {currentRunIndex(series)}/{series.requestedRuns} en cours</span>}
          {running && wait != null && (
            <span data-testid="series-pause"> · pause : essai {currentRunIndex(series)}/{series.requestedRuns} dans {wait} s
              (le serveur reste réservé à la série)</span>
          )}
        </div>
        {!running && (
          <button type="button" className="pt-btn-outline" onClick={exportPdf} disabled={exporting}>
            {exporting ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-file-earmark-pdf"></i>} Rapport de la série (PDF)
          </button>
        )}
        {running && canWrite && (
          <button type="button" className="pt-btn-danger" onClick={cancel} disabled={cancelling}>
            {cancelling ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-stop-circle"></i>} Annuler la série
          </button>
        )}
      </div>
      {series.stopReason && <p className="text-muted mb-2" style={{ fontSize: '13px' }}>{series.stopReason}</p>}

      <div className="pt-table-wrapper mb-2">
        <table className="pt-table">
          <thead>
            <tr><th>Essai</th><th>Statut</th><th>Résultat</th><th>Utilisateurs virtuels</th><th>Critère(s)</th><th></th></tr>
          </thead>
          <tbody>
            {series.runs.map((run) => (
              <tr key={run.executionId} aria-current={run.executionId === executionId ? 'true' : undefined}
                style={run.executionId === executionId ? { fontWeight: 600 } : undefined}>
                <td>{run.index}/{series.requestedRuns}</td>
                <td><span className={`pt-pill ${executionStatusMeta(run.status).pill}`}>{executionStatusMeta(run.status).label}</span></td>
                <td>{runOutcomeLabel(run)}</td>
                <td>{run.stress?.outcome === 'BREAKPOINT' ? run.stress.virtualUsers ?? '—' : run.stress?.peakVirtualUsers ?? '—'}</td>
                <td>{run.stress?.criteria.length ? run.stress.criteria.map((c) => CRITERION_LABELS[c]).join(' ; ') : '—'}</td>
                <td>{run.executionId !== executionId && !['QUEUED', 'RUNNING', 'PAUSED'].includes(run.status)
                  ? <Link to={`/executions/report/${run.executionId}`}>Rapport</Link> : null}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {finishedRuns > 0 && (
        <div data-testid="series-summary" style={{ fontSize: '13px' }}>
          <strong>{VERDICT_TITLES[summary.verdict]}</strong>
          {running && <span className="text-muted"> (provisoire, {finishedRuns} essai{finishedRuns > 1 ? 's' : ''} terminé{finishedRuns > 1 ? 's' : ''})</span>}
          <div>{summary.message}</div>
          {summary.rampRpsPerSecond != null && <div className="text-muted">Montée : {stressRampLabel(summary.rampRpsPerSecond)}.</div>}
        </div>
      )}
    </div>
  )
}
