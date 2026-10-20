import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { ApiError } from '../services/api/httpClient'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { liveStatusLabel, isTerminal, progressOf, useExecutionMonitor } from '../context/ExecutionMonitorContext'
import { executionStatusMeta } from '../utils/executionStatus'
import { NoFailedSteps, OnlyFailedToggle, StepPagerFooter, useStepPaging } from '../components/StepTablePager'
import LiveResources from '../components/application-monitoring/LiveResources'
import LiveInjector from '../components/LiveInjector'

// ============================================================
// Suivi en direct d'une exécution (/executions/live/:id) : statut, progression,
// utilisateurs actifs, requêtes, succès / échecs, débit, temps moyen, compteur
// par étape, pause / reprise, annulation. Les données viennent du suivi global
// (ExecutionMonitorContext) : quitter la page puis revenir ne perd rien.
// ============================================================

/** « 1 min 05 s », « 42 s ». */
export function formatPause(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const min = Math.floor(total / 60)
  const sec = total % 60
  return min > 0 ? `${min} min ${String(sec).padStart(2, '0')} s` : `${sec} s`
}

/**
 * Message clair pour un refus d'action. Le serveur explique lui-même un refus métier
 * (exécution terminée, déjà en pause…) ; un 404 sans explication signifie que le serveur
 * ne connaît pas cette action (backend lancé avant la mise à jour) — jamais un « Erreur 404 » brut.
 */
export function describeActionError(err: unknown, action: 'cancel' | 'pause' | 'resume'): string {
  const verb = action === 'cancel' ? 'Annulation' : action === 'pause' ? 'Mise en pause' : 'Reprise'
  if (err instanceof ApiError) {
    const serverExplained = !err.message.startsWith(`Erreur ${err.status} sur`)
    if (serverExplained) return err.message
    if (err.status === 404) {
      return action === 'cancel'
        ? 'Exécution introuvable (elle a peut-être été supprimée).'
        : `${verb} indisponible : le serveur LoadPilot ne connaît pas encore cette action. Redémarrez le backend avec la dernière version.`
    }
    if (err.status === 403) return `${verb} refusée : votre rôle ne le permet pas.`
    if (err.status === 0) return err.message
  }
  return `${verb} impossible.`
}

interface LiveStepRow {
  key: string
  name: string
  method: string | null
  total: number
  ok: number
  ko: number
  avg: number | null
}

function ExecutionLive() {
  const { id = '' } = useParams()
  const { executions, track } = useExecutionMonitor()
  const { rawRoles } = useAuth()
  const { showToast } = useToast()
  const [confirmCancel, setConfirmCancel] = useState(false)
  const [busy, setBusy] = useState<null | 'cancel' | 'pause' | 'resume'>(null)
  const [now, setNow] = useState(() => Date.now())
  const canWrite = rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER')

  useEffect(() => { if (id) track(id) }, [id, track])

  const execution = executions[id]
  const status = execution?.status ?? null
  const paused = status?.status === 'PAUSED'

  // Compteur « en pause depuis » rafraîchi chaque seconde entre deux interrogations du serveur.
  useEffect(() => {
    if (!paused) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [paused])

  const live = status?.live ?? null
  const finished = isTerminal(status)
  const progress = progressOf(status)
  const total = live ? live.requests : status?.totalRequests ?? 0
  const successes = live ? live.successes : status?.successfulRequests ?? 0
  const failures = live ? live.failures : status?.failedRequests ?? 0
  const report = execution?.report ?? null
  const avg = live?.avgResponseTimeMs ?? report?.statistics.avgResponseTime ?? null
  const steps: LiveStepRow[] = live
    ? live.steps.map((s, i) => ({ key: s.stepId ?? `${i}-${s.name}`, name: s.name, method: s.method, total: s.requests, ok: s.successes, ko: s.failures, avg: s.avgResponseTimeMs }))
    : (report?.steps ?? []).map((s, i) => ({ key: s.stepId ?? `${i}-${s.stepName}`, name: s.stepName, method: s.method, total: s.total, ok: s.success, ko: s.failed, avg: s.avgResponseTime }))
  const paging = useStepPaging(steps, (s) => s.ko > 0)
  const pausedForMs = paused && status?.pausedAt ? Math.max(0, now - new Date(status.pausedAt).getTime()) : 0

  const run = async (action: 'cancel' | 'pause' | 'resume') => {
    setBusy(action)
    try {
      if (action === 'cancel') {
        await executionsBackendApi.cancel(id)
        showToast('Annulation demandée : les utilisateurs virtuels s’arrêtent.', 'success')
        setConfirmCancel(false)
      } else if (action === 'pause') {
        await executionsBackendApi.pause(id)
        setNow(Date.now())
        showToast('Exécution en pause : plus aucune nouvelle requête n’est envoyée.', 'success')
      } else {
        await executionsBackendApi.resume(id)
        showToast('Exécution reprise là où elle s’était arrêtée.', 'success')
      }
    } catch (err) {
      showToast(describeActionError(err, action), 'danger')
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="pt-content">
      <div className="d-flex align-items-start justify-content-between flex-wrap gap-3 mb-3">
        <div>
          <Link to="/executions" className="pt-btn-ghost mb-2" style={{ display: 'inline-flex', gap: '0.35rem' }}>
            <i className="bi bi-arrow-left"></i> Exécutions
          </Link>
          <h1 style={{ fontSize: '22px', fontWeight: 700, margin: 0 }}>{execution?.scenarioName || status?.scenarioName || 'Exécution'}</h1>
          <div className="em-muted mt-1" aria-live="polite">
            {status ? <span className={paused ? 'em-status-paused' : undefined}>{liveStatusLabel(status.status)}</span> : 'Connexion…'}
            {status && <> · {status.virtualUsers} utilisateur(s) · ramp-up {status.rampUpSeconds ?? 0} s
              {status.durationSeconds ? ` · durée ${status.durationSeconds} s` : ''}{status.iterations ? ` · ${status.iterations} itération(s)` : ''}</>}
          </div>
          {execution?.connectionError && <div className="sw-field-error">Connexion au serveur perdue : nouvelle tentative chaque seconde…</div>}
        </div>
        <div className="d-flex gap-2 flex-wrap">
          {!finished && canWrite && (status?.status === 'RUNNING' || paused) && (
            paused ? (
              <button type="button" className="pt-btn-primary" onClick={() => run('resume')} disabled={busy !== null}>
                {busy === 'resume' ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-play-fill"></i>} Reprendre
              </button>
            ) : (
              <button type="button" className="pt-btn-warning" onClick={() => run('pause')} disabled={busy !== null}>
                {busy === 'pause' ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-pause-fill"></i>} Pause
              </button>
            )
          )}
          {!finished && canWrite && (
            <button type="button" className="pt-btn-danger" onClick={() => setConfirmCancel(true)} disabled={!status || busy !== null}>
              <i className="bi bi-stop-circle"></i> Annuler l’exécution
            </button>
          )}
          {finished && (
            <>
              <Link className="pt-btn-outline" to={`/executions/detail/${id}`}>Voir le détail de l’exécution</Link>
              <Link className="pt-btn-primary" to={`/executions/report/${id}`}>Voir le rapport</Link>
            </>
          )}
        </div>
      </div>

      {paused && (
        <div className="em-paused-banner mb-3" role="status">
          <i className="bi bi-pause-circle-fill" aria-hidden="true"></i>
          <span><strong>En pause</strong> depuis {formatPause(pausedForMs)} : plus aucune nouvelle requête n’est envoyée.
            Le temps de pause n’est pas compté dans la durée du test.</span>
        </div>
      )}

      <div className="pt-card mb-3">
        <div className="d-flex justify-content-between flex-wrap gap-2" style={{ fontSize: '13px' }}>
          <strong>Progression</strong>
          <span className="em-muted">{progress.label}{progress.percent != null ? ` · ${progress.percent} %` : ''}</span>
        </div>
        <div className="em-progress lg" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress.percent ?? undefined}>
          <div className={`em-progress-bar ${progress.percent == null && !paused ? 'indeterminate' : ''} ${paused ? 'paused' : ''} ${finished ? ({ ok: 'ok', warn: 'warn', ko: 'ko', neutral: 'ko', active: '' } as const)[executionStatusMeta(status?.status).tone] : ''}`}
            style={{ width: `${progress.percent ?? 30}%` }}></div>
        </div>
        {finished && status?.errorMessage && status.status !== 'SUCCESS' && <div className="em-muted mt-2">{status.errorMessage}</div>}
        {status && status.pausedDurationMs > 0 && !paused && (
          <div className="em-muted mt-2">Temps passé en pause : {formatPause(status.pausedDurationMs)} (non compté dans la durée ni dans le débit).</div>
        )}
      </div>

      <div className="em-kpis mb-3">
        <div className="em-kpi"><strong>{live ? `${live.activeVirtualUsers} / ${status?.virtualUsers}` : finished ? '0' : '—'}</strong><span>utilisateurs actifs</span></div>
        <div className="em-kpi"><strong>{total}</strong><span>requêtes envoyées</span></div>
        <div className="em-kpi ok"><strong>{successes}</strong><span>succès</span></div>
        <div className={`em-kpi ${failures ? 'ko' : ''}`}><strong>{failures}</strong><span>échecs</span></div>
        <div className="em-kpi"><strong>{live ? live.throughputRps : report?.statistics.throughput != null ? Math.round(report.statistics.throughput * 10) / 10 : '—'}</strong><span>{live ? 'req/s (5 dernières s)' : 'req/s (moyenne)'}</span></div>
        <div className="em-kpi"><strong>{avg != null ? `${Math.round(avg)} ms` : '—'}</strong><span>temps de réponse moyen</span></div>
      </div>

      {!finished && <LiveInjector injector={status?.injector} />}
      <LiveResources resources={status?.resources} finished={finished} />

      <div className="pt-card" style={{ padding: 0 }}>
        <div className="p-3 em-steps-head" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Étapes{steps.length > 0 ? ` (${steps.length})` : ''}</h6>
          {steps.length > 0 && <OnlyFailedToggle paging={paging} id="live-only-failed" />}
        </div>
        {steps.length === 0 ? (
          <div className="pt-empty-state"><p>{finished ? 'Aucune requête enregistrée.' : 'En attente des premières requêtes…'}</p></div>
        ) : (
          <>
            <div className="pt-table-wrapper">
              <table className="pt-table">
                <thead>
                  <tr><th></th><th>Étape</th><th>Requêtes</th><th>Succès</th><th>Échecs</th><th>Temps moyen</th></tr>
                </thead>
                <tbody>
                  {paging.pageItems.length === 0 ? <NoFailedSteps colSpan={6} /> : paging.pageItems.map(({ item: s, number }) => {
                    const tone = s.total === 0 ? '' : s.ko === 0 ? 'ok' : s.ok === 0 ? 'ko' : 'warn'
                    return (
                      <tr key={s.key}>
                        <td><span className={`em-step-dot ${tone}`} title={tone === 'ok' ? 'Aucun échec' : tone === 'ko' ? 'Toutes en échec' : tone === 'warn' ? 'Échecs partiels' : 'Pas encore exécutée'}></span></td>
                        <td>{number}. {s.method && <span className={`sw-method-badge sw-method-${s.method.toLowerCase()}`}>{s.method}</span>} {s.name}</td>
                        <td>{s.total}</td>
                        <td style={{ color: 'var(--pt-success)' }}>{s.ok}</td>
                        <td style={{ color: s.ko ? 'var(--pt-danger)' : undefined }}>{s.ko}</td>
                        <td>{s.avg != null ? `${Math.round(s.avg)} ms` : '—'}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            <StepPagerFooter paging={paging} />
          </>
        )}
      </div>

      {confirmCancel && (
        <div className="modal fade show d-block em-backdrop" tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="em-cancel-title">
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content em-modal">
              <div className="modal-body p-4">
                <h5 id="em-cancel-title">Annuler l’exécution ?</h5>
                <p className="em-muted mb-0">
                  Les utilisateurs virtuels s’arrêtent immédiatement et l’exécution passe à « Annulé ».
                  Ses résultats partiels ne sont pas conservés (ni détail ni rapport).
                </p>
              </div>
              <div className="modal-footer">
                <button type="button" className="pt-btn-ghost" onClick={() => setConfirmCancel(false)} disabled={busy === 'cancel'}>
                  {paused ? 'Garder en pause' : 'Continuer l’exécution'}
                </button>
                <button type="button" className="pt-btn-danger" onClick={() => run('cancel')} disabled={busy === 'cancel'}>
                  {busy === 'cancel' ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-stop-circle"></i>} Annuler l’exécution
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default ExecutionLive
