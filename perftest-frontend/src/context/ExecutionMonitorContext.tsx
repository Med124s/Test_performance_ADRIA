import { ReactNode, createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { BackendExecutionReportResponse, BackendExecutionStatus, BackendExecutionStatusResponse } from '../types/backendContracts'
import { TERMINAL_EXECUTION_STATUSES, executionStatusMeta } from '../utils/executionStatus'
import './executionMonitor.css'

// ============================================================
// Suivi en direct des exécutions lancées depuis l'interface.
//
// Monté dans MainLayout : il survit aux changements de page (rien n'est
// perdu en naviguant), et la liste des exécutions suivies est conservée en
// sessionStorage (rechargement de l'onglet). Interrogation de
// GET /api/executions/{id}/status toutes les secondes (compteurs en mémoire
// du moteur côté backend, aucune lecture lourde), puis à la fin : rapport
// (P95…) et modale de résultat.
// ============================================================

export const TERMINAL_STATUSES: BackendExecutionStatus[] = TERMINAL_EXECUTION_STATUSES
const POLL_INTERVAL_MS = 1000
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
  // Tous les utilisateurs ont terminé : le backend enregistre les résultats et calcule les métriques.
  if (live && live.startedVirtualUsers > 0 && live.activeVirtualUsers === 0) {
    return { percent: 99, label: 'Finalisation des résultats…' }
  }
  // Temps de travail réel (pauses exclues) : la progression ne bouge pas pendant une pause.
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

  const running = Object.values(executions).filter((e) => !isTerminal(e.status) && !location.pathname.endsWith(`/executions/live/${e.id}`))
  const finished = Object.values(executions).find((e) => isTerminal(e.status) && !e.acknowledged)

  return (
    <ExecutionMonitorContext.Provider value={{ executions, track, acknowledge }}>
      {children}
      {running.length > 0 && (
        <div className="em-dock" role="region" aria-label="Exécutions en cours">
          {running.map((e) => {
            const progress = progressOf(e.status)
            return (
              <div key={e.id} className={`em-dock-item ${e.status?.status === 'PAUSED' ? 'paused' : ''}`}>
                <div className="em-dock-head">
                  {e.status?.status === 'PAUSED'
                    ? <i className="bi bi-pause-circle-fill em-paused-icon" aria-hidden="true"></i>
                    : <span className="spinner-grow spinner-grow-sm text-primary" aria-hidden="true"></span>}
                  <strong className="text-truncate">{e.scenarioName || 'Exécution'}</strong>
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
        </div>
      )}
      {finished && <ResultModal execution={finished} onClose={() => acknowledge(finished.id)} />}
    </ExecutionMonitorContext.Provider>
  )
}

function ResultModal({ execution, onClose }: { execution: MonitoredExecution; onClose: () => void }) {
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
