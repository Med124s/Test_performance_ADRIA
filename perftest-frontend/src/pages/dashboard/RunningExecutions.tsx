import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { BackendDashboardExecutionBrief, BackendExecutionStatusResponse } from '../../types/backendContracts'
import { executionsBackendApi } from '../../services/api/executionsBackend'
import { progressOf, useExecutionMonitor } from '../../context/ExecutionMonitorContext'

// « Exécutions en cours » : affiché seulement s'il y en a ; progression rafraîchie toutes les 3 s.

export default function RunningExecutions({ running }: { running: BackendDashboardExecutionBrief[] }) {
  const monitor = useExecutionMonitor()
  const [statuses, setStatuses] = useState<Record<string, BackendExecutionStatusResponse>>({})
  const ids = running.map((r) => r.id).join(',')

  useEffect(() => {
    if (!ids) return
    let alive = true
    const load = () => Promise.all(running.map((r) => executionsBackendApi.getStatus(r.id).catch(() => null)))
      .then((list) => {
        if (!alive) return
        const next: Record<string, BackendExecutionStatusResponse> = {}
        list.forEach((s) => { if (s) next[s.id] = s })
        setStatuses(next)
      })
    load()
    const timer = window.setInterval(load, 3000)
    return () => { alive = false; window.clearInterval(timer) }
  }, [ids]) // eslint-disable-line react-hooks/exhaustive-deps

  if (running.length === 0) return null
  return (
    <div className="pt-card mb-3 db-card" data-testid="running-block">
      <h2 className="db-title"><i className="bi bi-play-circle"></i>Exécutions en cours</h2>
      <ul className="db-list">
        {running.map((r) => {
          const p = progressOf(statuses[r.id] ?? null)
          return (
            <li key={r.id} className="db-running">
              <div className="db-running-main">
                <strong>{r.scenarioName}</strong>
                <span className="db-muted">{r.applicationName} · {p.label}</span>
                <div className="em-progress" style={{ height: '6px', marginTop: '0.35rem' }} aria-label={`Progression : ${p.percent ?? '—'} %`}>
                  <div className={`em-progress-bar ${p.percent == null ? 'indeterminate' : ''}`} style={{ width: `${p.percent ?? 30}%` }}></div>
                </div>
              </div>
              <Link to={`/executions/live/${r.id}`} className="pt-btn-outline db-follow" onClick={() => monitor.track(r.id, r.scenarioName)}>
                Suivre
              </Link>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
