import { ReactNode, createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { stressSeriesBackendApi } from '../services/api/stressSeriesBackend'
import { refreshUnreadSoon } from '../services/notifications/unreadNotifications'
import {
  BackendExecutionReportResponse, BackendExecutionStatus, BackendExecutionStatusResponse, BackendStressSeriesResponse,
} from '../types/backendContracts'
import { TERMINAL_EXECUTION_STATUSES, executionStatusMeta } from '../utils/executionStatus'
import './executionMonitor.css'
import { stressProgressLabel } from '../utils/stressResult'
import { currentRunIndex, secondsUntilNextRun } from '../utils/stressSeries'

// Suivi en direct des exécutions lancées depuis l'interface, conservé d'une page à l'autre.
// Essai d'une série de stress : la série est suivie aussi, pour enchaîner sur l'essai suivant hors de la page de suivi.

export const TERMINAL_STATUSES: BackendExecutionStatus[] = TERMINAL_EXECUTION_STATUSES
const POLL_INTERVAL_MS = 1000
const SERIES_POLL_INTERVAL_MS = 2000
const STORAGE_KEY = 'loadpilot.monitored-executions'

export interface MonitoredExecution {
  id: string
  scenarioName: string
  status: BackendExecutionStatusResponse | null
  report: BackendExecutionReportResponse | null
  /** Modale de résultat déjà fermée par l'utilisateur. */
  acknowledged: boolean
  connectionError: string | null
}

interface ExecutionMonitorValue {
  executions: Record<string, MonitoredExecution>
  track: (id: string, scenarioName?: string) => void
  acknowledge: (id: string) => void
}

const noop: ExecutionMonitorValue = { executions: {}, track: () => {}, acknowledge: () => {} }
const ExecutionMonitorContext = createContext<ExecutionMonitorValue>(noop)

/** Hors du fournisseur (ex. tests unitaires) : version sans effet. */
export const useExecutionMonitor = () => useContext(ExecutionMonitorContext)

export const isTerminal = (s: BackendExecutionStatusResponse | null) => !!s && TERMINAL_STATUSES.includes(s.status)

/** Libellé du suivi en direct (« Terminé — succès ») ; statut inconnu : son nom. */
export const liveStatusLabel = (status: string | null | undefined) => executionStatusMeta(status).liveLabel

/** Libellé et pourcentage de la barre de progression (temps écoulé / durée, ou itérations faites / prévues). */
export function progressOf(s: BackendExecutionStatusResponse | null): { percent: number | null; label: string } {
  if (!s) return { percent: null, label: '—' }
  if (isTerminal(s)) return { percent: 100, label: liveStatusLabel(s.status) }
  if (s.status === 'QUEUED') return { percent: 0, label: 'En attente de démarrage' }
  const live = s.live
  // Tous les utilisateurs virtuels ont terminé : le backend enregistre les résultats.
  if (live && live.startedVirtualUsers > 0 && live.activeVirtualUsers === 0) {
    return { percent: 99, label: 'Finalisation des résultats…' }
  }
  // Test de stress : fin inconnue à l'avance, phase de la montée.
  if (s.stressLive) return { percent: null, label: stressProgressLabel(s.stressLive) }
  // Temps de travail (pauses exclues).
  const workedMs = s.activeElapsedMs ?? live?.elapsedMs ?? 0
  const prefix = s.status === 'PAUSED' ? 'En pause · ' : ''
  if (s.durationSeconds && live) {
    const elapsed = Math.min(s.durationSeconds, Math.floor(workedMs / 1000))
    return { percent: s.progressPercent, label: `${prefix}${elapsed} s / ${s.durationSeconds} s` }
  }
  if (s.plannedIterations && live) {
    return { percent: s.progressPercent, label: `${prefix}${live.completedIterations} / ${s.plannedIterations} itérations` }
  }
  return { percent: s.progressPercent, label: live ? `${prefix}${Math.floor(workedMs / 1000)} s écoulées` : 'Démarrage…' }
}

function readStored(): Record<string, MonitoredExecution> {
  try {
    const raw = JSON.parse(window.sessionStorage.getItem(STORAGE_KEY) ?? '[]') as { id: string; scenarioName: string }[]
    return Object.fromEntries(raw.map((e) => [e.id, { id: e.id, scenarioName: e.scenarioName, status: null, report: null, acknowledged: false, connectionError: null }]))
  } catch {
    return {}
  }
}

function writeStored(executions: Record<string, MonitoredExecution>) {
  try {
    const pending = Object.values(executions).filter((e) => !e.acknowledged).map(({ id, scenarioName }) => ({ id, scenarioName }))
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(pending))
  } catch {
    // Stockage indisponible (navigation privée…) : le suivi reste actif tant que l'onglet est ouvert.
  }
}

export function ExecutionMonitorProvider({ children }: { children: ReactNode }) {
  const [executions, setExecutions] = useState<Record<string, MonitoredExecution>>(readStored)
  const inFlight = useRef(new Set<string>())
  const location = useLocation()

  useEffect(() => { writeStored(executions) }, [executions])

  const patch = useCallback((id: string, update: Partial<MonitoredExecution>) =>
    setExecutions((prev) => (prev[id] ? { ...prev, [id]: { ...prev[id], ...update } } : prev)), [])

  const track = useCallback((id: string, scenarioName?: string) => setExecutions((prev) => (prev[id]
    ? { ...prev, [id]: { ...prev[id], scenarioName: scenarioName || prev[id].scenarioName } }
    : { ...prev, [id]: { id, scenarioName: scenarioName ?? '', status: null, report: null, acknowledged: false, connectionError: null } })), [])

  const acknowledge = useCallback((id: string) => patch(id, { acknowledged: true }), [patch])

  // Interrogation du statut (1 s) des exécutions non terminées ; rapport à la fin.
  const active = Object.values(executions).filter((e) => !isTerminal(e.status)).map((e) => e.id).join(',')
  useEffect(() => {
    if (!active) return
    const poll = () => {
      for (const id of active.split(',')) {
        if (inFlight.current.has(id)) continue
        inFlight.current.add(id)
        executionsBackendApi.getStatus(id)
          .then(async (status) => {
            patch(id, { status, connectionError: null, ...(status.scenarioName ? { scenarioName: status.scenarioName } : {}) })
            if (isTerminal(status)) {
              // La notification de fin est créée juste après : compteur de la cloche relu sans attendre.
              refreshUnreadSoon()
              const report = await executionsBackendApi.getReport(id).catch(() => null)
              patch(id, { report })
            }
          })
          .catch((err) => patch(id, { connectionError: err instanceof Error ? err.message : 'Connexion perdue' }))
          .finally(() => inFlight.current.delete(id))
      }
    }
    poll()
    const timer = window.setInterval(poll, POLL_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [active, patch])

  // Séries des essais suivis (null : série illisible) ; interrogées tant qu'elles sont en cours.
  const [series, setSeries] = useState<Record<string, BackendStressSeriesResponse | null>>({})
  const seriesInFlight = useRef(new Set<string>())
  const seriesIds = [...new Set(Object.values(executions).map((e) => e.status?.series?.id).filter((sid): sid is string => !!sid))]
  const followed = seriesIds.filter((sid) => !(sid in series) || series[sid]?.status === 'RUNNING').sort().join(',')
  useEffect(() => {
    if (!followed) return
    const poll = () => {
      for (const sid of followed.split(',')) {
        if (seriesInFlight.current.has(sid)) continue
        seriesInFlight.current.add(sid)
        stressSeriesBackendApi.get(sid)
          .then((s) => setSeries((prev) => ({ ...prev, [sid]: s })))
          .catch(() => setSeries((prev) => (sid in prev ? prev : { ...prev, [sid]: null })))
          .finally(() => seriesInFlight.current.delete(sid))
      }
    }
    poll()
    const timer = window.setInterval(poll, SERIES_POLL_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [followed])

  // Essai suivant suivi dès son lancement ; une seule fenêtre de résultat par série, pour le dernier essai.
  useEffect(() => {
    for (const s of Object.values(series)) {
      if (!s) continue
      const current = s.status === 'RUNNING' ? s.currentExecutionId : null
      if (current && !executions[current]) track(current, s.scenarioName)
      const last = s.status === 'RUNNING' ? current : s.runs[s.runs.length - 1]?.executionId
      for (const run of s.runs) {
        const e = executions[run.executionId]
        if (run.executionId !== last && e && !e.acknowledged && isTerminal(e.status)) acknowledge(run.executionId)
      }
    }
  }, [series, executions, track, acknowledge])

  // Pause entre deux essais : compte à rebours rafraîchi chaque seconde.
  const onLivePage = (id: string) => location.pathname.endsWith(`/executions/live/${id}`)
  const pausedSeries = Object.values(series).filter((s): s is BackendStressSeriesResponse => !!s && s.status === 'RUNNING' && !!s.nextRunAt
    && !s.runs.some((r) => onLivePage(r.executionId)))
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (pausedSeries.length === 0) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [pausedSeries.length])

  /** Essai d'une série encore en cours (ou pas encore lue) : sa fenêtre de résultat attend la fin de la série. */
  const awaitingSeries = (e: MonitoredExecution) => {
    const sid = e.status?.series?.id
    return !!sid && (!(sid in series) || series[sid]?.status === 'RUNNING')
  }
  const runLabel = (e: MonitoredExecution) => {
    const ref = e.status?.series
    const s = ref ? series[ref.id] : null
    return ref ? ` · essai ${ref.index}${s ? `/${s.requestedRuns}` : ''}` : ''
  }

  const running = Object.values(executions).filter((e) => !isTerminal(e.status) && !onLivePage(e.id))
  const finished = Object.values(executions).find((e) => isTerminal(e.status) && !e.acknowledged && !awaitingSeries(e))

  return (
    <ExecutionMonitorContext.Provider value={{ executions, track, acknowledge }}>
      {children}
      {(running.length > 0 || pausedSeries.length > 0) && (
        <div className="em-dock" role="region" aria-label="Exécutions en cours">
          {running.map((e) => {
            const progress = progressOf(e.status)
            return (
              <div key={e.id} className={`em-dock-item ${e.status?.status === 'PAUSED' ? 'paused' : ''}`}>
                <div className="em-dock-head">
                  {e.status?.status === 'PAUSED'
                    ? <i className="bi bi-pause-circle-fill em-paused-icon" aria-hidden="true"></i>
                    : <span className="spinner-grow spinner-grow-sm text-primary" aria-hidden="true"></span>}
                  <strong className="text-truncate">{e.scenarioName || 'Exécution'}{runLabel(e)}</strong>
                  <span className="em-dock-status">{e.status ? liveStatusLabel(e.status.status) : 'Connexion…'}</span>
                </div>
                <div className="em-progress" aria-label={`Progression : ${progress.label}`}>
                  <div className={`em-progress-bar ${progress.percent == null ? 'indeterminate' : ''} ${e.status?.status === 'PAUSED' ? 'paused' : ''}`} style={{ width: `${progress.percent ?? 30}%` }}></div>
                </div>
                <div className="em-dock-meta">
                  <span>{progress.label}</span>
                  {e.status?.live && <span>{e.status.live.requests} req · {e.status.live.throughputRps} req/s</span>}
                  <Link to={`/executions/live/${e.id}`}>Suivre</Link>
                </div>
              </div>
            )
          })}
          {pausedSeries.map((s) => {
            const lastRun = s.runs[s.runs.length - 1]
            return (
              <div key={s.id} className="em-dock-item paused" data-testid="em-series-pause">
                <div className="em-dock-head">
                  <i className="bi bi-hourglass-split em-paused-icon" aria-hidden="true"></i>
                  <strong className="text-truncate">{s.scenarioName}</strong>
                  <span className="em-dock-status">Série · pause</span>
                </div>
                <div className="em-dock-meta">
                  <span>essai {currentRunIndex(s)}/{s.requestedRuns} dans {secondsUntilNextRun(s, now)} s</span>
                  {lastRun && <Link to={`/executions/live/${lastRun.executionId}`}>Suivre</Link>}
                </div>
              </div>
            )
          })}
        </div>
      )}
      {finished && <ResultModal execution={finished} series={finished.status?.series ? series[finished.status.series.id] ?? null : null}
        onClose={() => acknowledge(finished.id)} />}
    </ExecutionMonitorContext.Provider>
  )
}

function ResultModal({ execution, series, onClose }: {
  execution: MonitoredExecution
  /** Dernier essai d'une série terminée : résultat consolidé. */
  series: BackendStressSeriesResponse | null
  onClose: () => void
}) {
  const status = execution.status!
  const stats = execution.report?.statistics
  const statusTone = executionStatusMeta(status.status).tone
  const tone = statusTone === 'active' ? 'neutral' : statusTone
  const fmt = (v: number | null | undefined, unit: string) => (v == null ? '—' : `${Math.round(v * 10) / 10}${unit}`)
  return (
    <div className="modal fade show d-block em-backdrop" tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="em-result-title">
      <div className="modal-dialog modal-dialog-centered">
        <div className="modal-content em-modal">
          <div className="modal-body p-4">
            <div className={`em-result-badge ${tone}`}>
              <i className={`bi ${tone === 'ok' ? 'bi-check-circle-fill' : tone === 'ko' ? 'bi-x-circle-fill' : tone === 'warn' ? 'bi-exclamation-triangle-fill' : 'bi-slash-circle'}`}></i>
              {status.status}
            </div>
            <h5 id="em-result-title" className="mt-2 mb-1">{execution.scenarioName || 'Exécution'} : {liveStatusLabel(status.status).toLowerCase()}</h5>
            {status.errorMessage && status.status !== 'SUCCESS' && <p className="em-muted mb-2">{status.errorMessage}</p>}
            <div className="em-result-grid">
              <div><strong>{stats ? stats.totalRequests : status.totalRequests}</strong><span>requêtes</span></div>
              <div><strong>{fmt(stats?.successRate, ' %')}</strong><span>taux de succès</span></div>
              <div><strong>{fmt(stats?.avgResponseTime, ' ms')}</strong><span>temps moyen</span></div>
              <div><strong>{fmt(stats?.p95, ' ms')}</strong><span>P95</span></div>
            </div>
            {!execution.report && <p className="em-muted mt-2 mb-0"><span className="spinner-border spinner-border-sm me-1"></span>Calcul des statistiques…</p>}
            {series?.summary && (
              <p className="mt-3 mb-0" data-testid="em-series-summary">
                <strong>Série de {series.requestedRuns} essais :</strong> {series.summary.message}
              </p>
            )}
          </div>
          <div className="modal-footer flex-wrap gap-2">
            <button type="button" className="pt-btn-ghost" onClick={onClose}>Fermer</button>
            <Link className="pt-btn-outline" to={`/executions/detail/${execution.id}`} onClick={onClose}>Voir le détail de l’exécution</Link>
            <Link className="pt-btn-primary" to={`/executions/report/${execution.id}`} onClick={onClose}>Voir le rapport</Link>
          </div>
        </div>
      </div>
    </div>
  )
}
