import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Line } from 'react-chartjs-2'
import { CategoryScale, Chart as ChartJS, Legend, LinearScale, LineElement, PointElement, Tooltip } from 'chart.js'
import { scheduledExecutionsBackendApi } from '../../services/api/scheduledExecutionsBackend'
import { scenariosBackendApi } from '../../services/api/scenariosBackend'
import { ApiError } from '../../services/api/httpClient'
import {
  BackendScenarioResponse, BackendScheduleHistoryItem, BackendScheduledExecutionResponse,
} from '../../types/backendContracts'
import { useAuth } from '../../context/AuthContext'
import { useToast } from '../../context/ToastContext'
import { formatMs, formatNumber, formatPercent, formatRps } from '../../utils/numberFormat'
import { statusLabel, statusPill, formatHumanDuration, relativeTime } from '../executions/executionsModel'
import ScheduleFormModal from './ScheduleFormModal'
import { OUTCOME_LABEL, STATE_LABEL, STATE_PILL, describeSchedule, formatInZone } from './scheduleModel'
import '../applications.css'
import '../executions/executions.css'
import './scheduling.css'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend)

// ============================================================
// Historique d'une planification : toutes les exécutions qu'elle a
// lancées (date, statut, P95, erreurs, débit, lien vers le rapport) et les
// lancements ignorés ou manqués avec leur raison ; petit graphique du P95
// et du taux d'erreurs d'une exécution à l'autre.
// ============================================================

function describeError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 404) return 'Cette planification n’existe plus.'
    if (err.status === 429) return 'Capacité insuffisante : trop de tests en cours. Réessayez plus tard.'
    return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
  }
  return err instanceof Error ? err.message : fallback
}

export default function ScheduleHistory() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { showToast } = useToast()
  const { authProvider, rawRoles } = useAuth()
  const canWrite = authProvider === 'keycloak' && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  const [schedule, setSchedule] = useState<BackendScheduledExecutionResponse | null>(null)
  const [items, setItems] = useState<BackendScheduleHistoryItem[] | null>(null)
  const [truncated, setTruncated] = useState(false)
  const [scenarios, setScenarios] = useState<BackendScenarioResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState(false)

  const load = useCallback(async (silent = false) => {
    try {
      const [s, h] = await Promise.all([scheduledExecutionsBackendApi.getById(id), scheduledExecutionsBackendApi.history(id, 200)])
      setSchedule(s)
      setItems(h.items)
      setTruncated(h.truncated)
      setError(null)
    } catch (err) {
      if (!silent) setError(describeError(err, 'Historique indisponible.'))
    }
  }, [id])

  useEffect(() => { load() }, [load])
  // Une exécution lancée par la planification passe de « En cours » à terminée : mise à jour discrète.
  useEffect(() => {
    const timer = window.setInterval(() => load(true), 15_000)
    return () => window.clearInterval(timer)
  }, [load])
  useEffect(() => {
    if (canWrite) scenariosBackendApi.getAll().then(setScenarios).catch(() => setScenarios([]))
  }, [canWrite])

  const runs = useMemo(() => (items ?? []).filter((i) => i.kind === 'EXECUTION'), [items])
  const notRun = (items?.length ?? 0) - runs.length
  // Graphique : exécutions terminées, de la plus ancienne à la plus récente.
  const chartPoints = useMemo(() => runs.filter((r) => r.p95Ms != null && r.status && !['QUEUED', 'RUNNING', 'PAUSED'].includes(r.status)).reverse(), [runs])

  const runNow = async () => {
    if (!schedule) return
    setBusy(true)
    try {
      setSchedule(await scheduledExecutionsBackendApi.runNow(schedule.id))
      showToast('Exécution lancée : elle apparaît dans l’historique.', 'success')
      load(true)
    } catch (err) {
      showToast(describeError(err, 'Lancement impossible.'), 'danger')
    } finally {
      setBusy(false)
    }
  }

  if (error) {
    return (
      <div className="pt-content">
        <Link to="/planification" className="ex-muted"><i className="bi bi-arrow-left"></i> Planification</Link>
        <div className="pt-alert-banner danger mt-3" role="alert"><i className="bi bi-exclamation-triangle-fill"></i>{error}</div>
      </div>
    )
  }

  const tz = schedule?.timezone ?? 'UTC'
  const muted = 'rgba(148, 163, 184, 0.9)'
  const chartData = {
    labels: chartPoints.map((p) => formatInZone(p.at, tz).replace(/^\S+ /, '')),
    datasets: [
      { label: 'P95 (ms)', data: chartPoints.map((p) => p.p95Ms), yAxisID: 'yMs', borderColor: '#4F46E5', backgroundColor: '#4F46E5', pointRadius: 4, pointHoverRadius: 6, tension: 0.2 },
      { label: 'Taux d’erreurs (%)', data: chartPoints.map((p) => p.errorRatePercent), yAxisID: 'yErr', borderColor: '#EF4444', backgroundColor: '#EF4444', pointRadius: 4, pointHoverRadius: 6, borderDash: [5, 4], tension: 0.2 },
    ],
  }
  const chartOptions = {
    responsive: true, maintainAspectRatio: false, animation: false as const,
    interaction: { mode: 'index' as const, intersect: false },
    onClick: (_e: unknown, elements: { index: number }[]) => {
      const p = elements[0] ? chartPoints[elements[0].index] : null
      if (p?.executionId) navigate(`/executions/report/${p.executionId}`)
    },
    plugins: {
      legend: { position: 'bottom' as const, labels: { boxWidth: 12, font: { size: 11 }, color: muted } },
      tooltip: {
        callbacks: {
          label: (ctx: { datasetIndex: number; parsed: { y: number | null } }) => (ctx.datasetIndex === 0
            ? ` P95 : ${formatMs(ctx.parsed.y)}` : ` Erreurs : ${formatPercent(ctx.parsed.y)}`),
        },
      },
    },
    scales: {
      x: { ticks: { color: muted, font: { size: 10 }, maxRotation: 0, autoSkip: true }, grid: { display: false } },
      yMs: { position: 'left' as const, beginAtZero: true, ticks: { color: muted, font: { size: 10 } }, title: { display: true, text: 'P95 (ms)', color: muted, font: { size: 10 } } },
      yErr: { position: 'right' as const, beginAtZero: true, suggestedMax: 5, grid: { drawOnChartArea: false }, ticks: { color: muted, font: { size: 10 } }, title: { display: true, text: 'Erreurs (%)', color: muted, font: { size: 10 } } },
    },
  }

  return (
    <div className="pt-content">
      <Link to="/planification" className="ex-muted"><i className="bi bi-arrow-left"></i> Planification</Link>

      <div className="pt-page-header ex-header pl-history-head mt-2">
        <div className="page-title">
          <h1>{schedule ? `Historique — ${schedule.name}` : 'Historique'}</h1>
          {schedule && (
            <div className="pl-meta">
              <span>Scénario <strong>{schedule.scenarioName}</strong> · {schedule.applicationName}</span>
              <span>{describeSchedule(schedule)} <span className="ex-muted">({schedule.timezone})</span></span>
              <span>
                <span className={`pt-pill ${STATE_PILL[schedule.state]} ex-pill`} title={schedule.stateReason ?? undefined}>{STATE_LABEL[schedule.state]}</span>
              </span>
              {schedule.nextRunAt && schedule.state === 'ACTIVE' && (
                <span title={formatInZone(schedule.nextRunAt, tz)}>Prochaine : <strong>{relativeTime(schedule.nextRunAt)}</strong></span>
              )}
              <span>Notifications : <strong>{schedule.notifyMode === 'ALWAYS' ? 'à chaque fin' : 'échec ou régression'}</strong></span>
            </div>
          )}
        </div>
        {schedule && canWrite && (
          <div className="d-flex gap-2 flex-wrap">
            <button type="button" className="pt-btn-outline" onClick={() => setEditing(true)}><i className="bi bi-pencil"></i> Modifier</button>
            <button type="button" className="pt-btn-primary" onClick={runNow} disabled={busy || schedule.state === 'SUSPENDED'}
              title={schedule.state === 'SUSPENDED' ? schedule.stateReason ?? undefined : undefined}>
              {busy ? <span className="spinner-border spinner-border-sm me-1"></span> : <i className="bi bi-play-fill"></i>} Lancer maintenant
            </button>
          </div>
        )}
      </div>

      {items != null && chartPoints.length >= 2 && (
        <div className="pt-card mb-3">
          <h2 style={{ fontSize: 14, fontWeight: 700, margin: '0 0 0.6rem' }}>P95 et taux d’erreurs d’une exécution à l’autre</h2>
          <div className="pl-chart"><Line data={chartData} options={chartOptions} aria-label="Évolution du P95 et du taux d’erreurs" /></div>
        </div>
      )}

      <div className="pt-card ex-card">
        <div className="d-flex justify-content-between align-items-center px-3 py-2 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <strong style={{ fontSize: 14 }}>Lancements</strong>
          {items != null && (
            <span className="ex-muted">
              {runs.length} exécution{runs.length > 1 ? 's' : ''}{notRun > 0 ? ` · ${notRun} ignorée${notRun > 1 ? 's' : ''} ou manquée${notRun > 1 ? 's' : ''}` : ''}
              {truncated ? ' · 200 plus récents' : ''}
            </span>
          )}
        </div>
        <div className="ex-table-wrap">
          <table className="ex-table pl-table">
            <thead>
              <tr>
                <th>Date</th>
                <th>Résultat</th>
                <th className="num">P95</th>
                <th className="num">Erreurs</th>
                <th className="num">Débit</th>
                <th className="num">Requêtes</th>
                <th className="num">Durée</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {items == null && Array.from({ length: 4 }, (_, i) => (
                <tr key={i} className="ex-skeleton-row">{Array.from({ length: 8 }, (__, j) => <td key={j}><div className="ex-skeleton"></div></td>)}</tr>
              ))}
              {items != null && items.length === 0 && (
                <tr className="ex-empty-row">
                  <td colSpan={8}>
                    <div className="ex-empty">
                      <i className="bi bi-hourglass"></i>
                      <p>Aucun lancement pour l’instant.{schedule?.nextRunAt && schedule.state === 'ACTIVE' ? ` Premier lancement ${relativeTime(schedule.nextRunAt)}.` : ''}</p>
                    </div>
                  </td>
                </tr>
              )}
              {(items ?? []).map((item, i) => item.kind === 'EXECUTION' ? (
                <tr key={item.executionId ?? i} className="ex-row ex-row-link" data-testid="history-row"
                  onClick={(e) => { if (!(e.target as HTMLElement).closest('a') && item.executionId) navigate(`/executions/report/${item.executionId}`) }}>
                  <td title={formatInZone(item.at, tz)}>{formatInZone(item.at, tz)}</td>
                  <td>{item.status && <span className={`pt-pill ${statusPill(item.status)} ex-pill`}>{statusLabel(item.status)}</span>}</td>
                  <td className="num">{formatMs(item.p95Ms)}</td>
                  <td className="num">
                    {item.errorRatePercent == null ? '—'
                      : <span className={`ex-rate ${item.errorRatePercent > 0 ? 'bad' : ''}`} style={{ marginLeft: 0 }}>{formatPercent(item.errorRatePercent)}</span>}
                  </td>
                  <td className="num">{formatRps(item.throughputRps)}</td>
                  <td className="num">{item.totalRequests == null ? '—' : formatNumber(item.totalRequests, 0)}</td>
                  <td className="num">{formatHumanDuration(item.durationMs)}</td>
                  <td>{item.executionId && <Link to={`/executions/report/${item.executionId}`}>Rapport</Link>}</td>
                </tr>
              ) : (
                <tr key={`ev-${i}`} className="pl-row-event" data-testid="history-row">
                  <td>{formatInZone(item.at, tz)}</td>
                  <td><span className="pt-pill warning ex-pill">{item.outcome ? OUTCOME_LABEL[item.outcome] : 'Non lancée'}</span></td>
                  <td colSpan={5} className="pl-reason">{item.reason}</td>
                  <td></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {editing && schedule && (
        <ScheduleFormModal schedule={schedule} scenarios={scenarios} onClose={() => setEditing(false)}
          onSaved={(saved) => { setEditing(false); setSchedule(saved); showToast(`Planification « ${saved.name} » enregistrée.`, 'success') }} />
      )}
    </div>
  )
}
