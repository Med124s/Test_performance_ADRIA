import { useRegressionThreshold } from '../utils/thresholds'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { scheduledExecutionsBackendApi } from '../services/api/scheduledExecutionsBackend'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { ApiError } from '../services/api/httpClient'
import {
  BackendScenarioResponse, BackendScheduleState, BackendScheduledExecutionResponse,
} from '../types/backendContracts'
import Pagination from '../components/Pagination'
import SearchSelect from '../components/SearchSelect'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { formatMs } from '../utils/numberFormat'
import RowActionsMenu, { RowAction } from './executions/RowActionsMenu'
import { statusLabel, statusPill, fullDate, relativeTime } from './executions/executionsModel'
import ScheduleFormModal from './scheduling/ScheduleFormModal'
import {
  OUTCOME_LABEL, STATES, STATE_LABEL, STATE_PILL, describeSchedule, formatInZone, p95Change,
} from './scheduling/scheduleModel'
import './applications.css'
import './executions/executions.css'
import './scheduling/scheduling.css'

// ============================================================
// Planification : lancer des scénarios automatiquement (une fois ou de
// façon récurrente). Le serveur calcule l'état (Active, En pause,
// Suspendue, Terminée, Expirée), la dernière exécution et son écart de
// P95 ; cette page filtre (état, scénario, recherche), pagine et agit.
// SUPER_ADMIN et PERFORMANCE_ENGINEER gèrent ; VIEWER consulte.
// ============================================================

const PAGE_SIZES = [10, 20, 50]
const REFRESH_MS = 30_000

function describeError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 0) return err.message
    if (err.status === 403) return 'Action refusée : votre rôle ne permet pas de gérer les planifications.'
    if (err.status === 429) return 'Capacité insuffisante : trop de tests en cours. Réessayez quand un test sera terminé.'
    return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
  }
  return err instanceof Error ? err.message : fallback
}

export default function ScheduledExecutions() {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const { authProvider, rawRoles } = useAuth()
  const canWrite = authProvider === 'keycloak' && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  const [searchParams, setSearchParams] = useSearchParams()
  const rawState = searchParams.get('state') ?? ''
  const urlState = (STATES as string[]).includes(rawState) ? (rawState as BackendScheduleState) : ''
  const urlScenario = searchParams.get('scenario') ?? ''
  const urlQuery = searchParams.get('q') ?? ''
  const urlPage = Math.max(1, Number(searchParams.get('page')) || 1)
  const urlSize = PAGE_SIZES.includes(Number(searchParams.get('size'))) ? Number(searchParams.get('size')) : 10

  const [schedules, setSchedules] = useState<BackendScheduledExecutionResponse[] | null>(null)
  const [scenarios, setScenarios] = useState<BackendScenarioResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [form, setForm] = useState<{ schedule: BackendScheduledExecutionResponse | null } | null>(null)
  const [toDelete, setToDelete] = useState<BackendScheduledExecutionResponse | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [searchText, setSearchText] = useState(urlQuery)
  const [, setTick] = useState(0)

  const updateParams = useCallback((patch: Record<string, string | null>, resetPage = true) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      for (const [k, v] of Object.entries(patch)) {
        if (v == null || v === '') next.delete(k)
        else next.set(k, v)
      }
      if (resetPage) next.delete('page')
      return next
    }, { replace: true })
  }, [setSearchParams])

  const load = useCallback(async (silent = false) => {
    try {
      const [list, scen] = await Promise.all([scheduledExecutionsBackendApi.list(), scenariosBackendApi.getAll()])
      setSchedules(list)
      setScenarios(scen)
      setError(null)
    } catch (err) {
      if (!silent) setError(describeError(err, 'Impossible de charger les planifications.'))
    }
  }, [])

  useEffect(() => { load() }, [load])
  // Rafraîchissement discret : prochaines exécutions, dernières exécutions et dates relatives restent à jour.
  useEffect(() => {
    const timer = window.setInterval(() => { load(true); setTick((t) => t + 1) }, REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [load])

  // Recherche : appliquée après une courte pause de frappe.
  useEffect(() => { setSearchText(urlQuery) }, [urlQuery])
  useEffect(() => {
    if (searchText === urlQuery) return
    const timer = window.setTimeout(() => updateParams({ q: searchText.trim() || null }), 300)
    return () => window.clearTimeout(timer)
  }, [searchText, urlQuery, updateParams])

  const scenarioOptions = useMemo(() => {
    const ids = new Set((schedules ?? []).map((s) => s.scenarioId))
    return scenarios.filter((s) => ids.has(s.id)).map((s) => ({ value: s.id, label: s.name, hint: s.applicationName }))
  }, [schedules, scenarios])

  // Filtres hors état (les compteurs des onglets les respectent), puis état.
  const baseRows = useMemo(() => {
    const q = urlQuery.toLowerCase()
    return (schedules ?? []).filter((s) => (!urlScenario || s.scenarioId === urlScenario)
      && (!q || s.name.toLowerCase().includes(q) || s.scenarioName.toLowerCase().includes(q) || s.applicationName.toLowerCase().includes(q)))
  }, [schedules, urlScenario, urlQuery])
  const counts = useMemo(() => {
    const c: Record<string, number> = { '': baseRows.length }
    for (const st of STATES) c[st] = 0
    for (const s of baseRows) c[s.state] += 1
    return c
  }, [baseRows])
  const rows = useMemo(() => (urlState ? baseRows.filter((s) => s.state === urlState) : baseRows), [baseRows, urlState])

  const totalPages = Math.max(1, Math.ceil(rows.length / urlSize))
  const page = Math.min(urlPage, totalPages)
  const pageRows = rows.slice((page - 1) * urlSize, page * urlSize)
  const hasFilters = !!(urlState || urlScenario || urlQuery)
  const resetFilters = () => { setSearchText(''); updateParams({ state: null, scenario: null, q: null }) }

  const replaceRow = (saved: BackendScheduledExecutionResponse) =>
    setSchedules((list) => (list ? list.map((s) => (s.id === saved.id ? saved : s)) : list))

  const act = async (s: BackendScheduledExecutionResponse, run: () => Promise<BackendScheduledExecutionResponse>, done: string) => {
    setBusyId(s.id)
    try {
      replaceRow(await run())
      showToast(done, 'success')
    } catch (err) {
      showToast(describeError(err, 'Action impossible.'), 'danger')
      load(true)
    } finally {
      setBusyId(null)
    }
  }

  const runNow = (s: BackendScheduledExecutionResponse) =>
    act(s, () => scheduledExecutionsBackendApi.runNow(s.id), `« ${s.name} » : exécution lancée. Elle apparaît dans Exécutions et dans l’historique.`)
  const togglePause = (s: BackendScheduledExecutionResponse) => (s.enabled && s.state !== 'SUSPENDED'
    ? act(s, () => scheduledExecutionsBackendApi.disable(s.id), `« ${s.name} » mise en pause.`)
    : act(s, () => scheduledExecutionsBackendApi.enable(s.id), `« ${s.name} » reprise.`))

  const confirmDelete = async () => {
    if (!toDelete) return
    setDeleting(true)
    try {
      await scheduledExecutionsBackendApi.delete(toDelete.id)
      setSchedules((list) => (list ? list.filter((s) => s.id !== toDelete.id) : list))
      showToast(`Planification « ${toDelete.name} » supprimée.`, 'success')
      setToDelete(null)
    } catch (err) {
      showToast(describeError(err, 'Suppression impossible.'), 'danger')
    } finally {
      setDeleting(false)
    }
  }

  const openCreate = () => setForm({ schedule: null })

  return (
    <div className="pt-content">
      <div className="pt-page-header ex-header">
        <div className="page-title">
          <h1>Planification</h1>
          <p>Lancez vos scénarios automatiquement, par exemple chaque nuit.</p>
        </div>
        {canWrite && (schedules?.length ?? 0) > 0 && (
          <button type="button" className="pt-btn-primary ex-launch" onClick={openCreate}>
            <i className="bi bi-plus-lg"></i> Nouvelle planification
          </button>
        )}
      </div>

      {error && (
        <div className="pt-alert-banner danger mb-3" role="alert">
          <i className="bi bi-exclamation-triangle-fill"></i>{error}
          <button type="button" className="pt-btn-outline ms-auto" onClick={() => load()}>Réessayer</button>
        </div>
      )}

      {schedules != null && schedules.length === 0 && !error ? (
        <div className="pt-card">
          <div className="pl-empty">
            <i className="bi bi-calendar2-week pl-empty-icon"></i>
            <h2>Aucune planification</h2>
            <p>Planifiez un scénario pour le lancer automatiquement : une seule fois, chaque nuit, les jours ouvrés…</p>
            {canWrite ? (
              <button type="button" className="pt-btn-primary mt-2" onClick={openCreate}>
                <i className="bi bi-plus-lg"></i> Créer une planification
              </button>
            ) : (
              <p className="pl-sub">Seuls les administrateurs et les ingénieurs performance peuvent en créer.</p>
            )}
          </div>
        </div>
      ) : (
        <>
          <div className="pl-filters">
            <div className="pt-search pl-filter-search">
              <i className="bi bi-search"></i>
              <input type="search" placeholder="Rechercher (nom, scénario, application)…" value={searchText}
                aria-label="Rechercher une planification" onChange={(e) => setSearchText(e.target.value)} />
            </div>
            <div className="pl-filter-scenario">
              <SearchSelect options={scenarioOptions} value={urlScenario} onChange={(v) => updateParams({ scenario: v || null })}
                allLabel="Tous les scénarios" ariaLabel="Filtrer par scénario" width="100%" />
            </div>
            {hasFilters && (
              <button type="button" className="pt-btn-ghost ex-reset" onClick={resetFilters}>
                <i className="bi bi-x-circle"></i> Réinitialiser
              </button>
            )}
          </div>

          <div className="pt-card ex-card">
            <div className="ex-tabs" role="tablist" aria-label="Filtrer par état">
              {(['', ...STATES] as const).map((st) => {
                const n = counts[st] ?? 0
                const selected = urlState === st
                return (
                  <button key={st || 'all'} type="button" role="tab" aria-selected={selected}
                    className={`ex-tab ${selected ? 'active' : ''} ${n === 0 && !selected ? 'empty' : ''}`}
                    onClick={() => updateParams({ state: st || null })}>
                    {st === '' ? 'Toutes' : STATE_LABEL[st]}
                    <span className="ex-tab-count">{n}</span>
                  </button>
                )
              })}
            </div>

            <div className="ex-table-wrap">
              <table className="ex-table pl-table">
                <thead>
                  <tr>
                    <th>Nom</th>
                    <th>Scénario</th>
                    <th>Fréquence</th>
                    <th>Prochaine exécution</th>
                    <th>Dernière exécution</th>
                    <th>État</th>
                    <th className="ex-col-actions">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {schedules == null && !error && Array.from({ length: 4 }, (_, i) => (
                    <tr key={i} className="ex-skeleton-row">
                      {Array.from({ length: 7 }, (__, j) => <td key={j}><div className="ex-skeleton"></div></td>)}
                    </tr>
                  ))}
                  {schedules != null && pageRows.length === 0 && (
                    <tr className="ex-empty-row">
                      <td colSpan={7}>
                        <div className="ex-empty">
                          <i className="bi bi-funnel"></i>
                          <p>Aucune planification ne correspond à ces filtres.</p>
                          <button type="button" className="pt-btn-outline" onClick={resetFilters}>Réinitialiser les filtres</button>
                        </div>
                      </td>
                    </tr>
                  )}
                  {pageRows.map((s) => (
                    <ScheduleRow key={s.id} s={s} canWrite={canWrite} busy={busyId === s.id}
                      onRunNow={() => runNow(s)} onTogglePause={() => togglePause(s)}
                      onEdit={() => setForm({ schedule: s })} onHistory={() => navigate(`/planification/${s.id}`)}
                      onDelete={() => setToDelete(s)} />
                  ))}
                </tbody>
              </table>
            </div>

            {rows.length > 0 && (
              <Pagination page={page} totalPages={totalPages}
                onPageChange={(p) => updateParams({ page: p > 1 ? String(p) : null }, false)}
                startIndex={(page - 1) * urlSize + 1} endIndex={Math.min(page * urlSize, rows.length)} totalItems={rows.length}
                itemLabel="planifications" pageSize={urlSize} pageSizeOptions={PAGE_SIZES}
                onPageSizeChange={(size) => updateParams({ size: size === 10 ? null : String(size) })} />
            )}
          </div>
        </>
      )}

      {form && (
        <ScheduleFormModal schedule={form.schedule} scenarios={scenarios} onClose={() => setForm(null)}
          onSaved={(saved, created) => {
            setForm(null)
            setSchedules((list) => (created ? [saved, ...(list ?? [])] : (list ?? []).map((s) => (s.id === saved.id ? saved : s))))
            showToast(created ? `Planification « ${saved.name} » créée.` : `Planification « ${saved.name} » enregistrée.`, 'success')
          }} />
      )}

      {toDelete && (
        <div className="pl-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget && !deleting) setToDelete(null) }}>
          <div className="pl-modal" role="dialog" aria-modal="true" aria-labelledby="pl-del-title" style={{ width: 460, marginTop: '10vh' }}>
            <div className="pl-modal-body">
              <h2 id="pl-del-title" style={{ fontSize: 17, fontWeight: 700, margin: 0 }}>
                <i className="bi bi-trash text-danger me-2"></i>Supprimer « {toDelete.name} » ?
              </h2>
              <p className="mb-0" style={{ fontSize: 13.5 }}>
                Plus aucun lancement automatique ne sera fait. Les exécutions déjà lancées restent consultables dans Exécutions et Rapports.
              </p>
            </div>
            <div className="pl-modal-foot">
              <button type="button" className="pt-btn-ghost" onClick={() => setToDelete(null)} disabled={deleting}>Annuler</button>
              <button type="button" className="pt-btn-danger" onClick={confirmDelete} disabled={deleting}>
                {deleting && <span className="spinner-border spinner-border-sm me-1"></span>}Supprimer
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

interface RowProps {
  s: BackendScheduledExecutionResponse
  canWrite: boolean
  busy: boolean
  onRunNow: () => void
  onTogglePause: () => void
  onEdit: () => void
  onHistory: () => void
  onDelete: () => void
}

function ScheduleRow({ s, canWrite, busy, onRunNow, onTogglePause, onEdit, onHistory, onDelete }: RowProps) {
  const suspended = s.state === 'SUSPENDED'
  const finished = s.state === 'COMPLETED' || s.state === 'EXPIRED'
  const paused = s.state === 'PAUSED'
  const regressionThreshold = useRegressionThreshold()
  const change = p95Change(s.lastRun?.p95ChangePercent, regressionThreshold)

  // « Voir le détail » (page de la planification et son historique) est une icône, visible de tous.
  const actions: RowAction[] = canWrite ? [
    { label: 'Modifier', icon: 'bi-pencil', onSelect: onEdit },
    { label: 'Historique', icon: 'bi-clock-history', onSelect: onHistory },
    { label: 'Supprimer', icon: 'bi-trash', onSelect: onDelete, danger: true },
  ] : [{ label: 'Historique', icon: 'bi-clock-history', onSelect: onHistory }]

  return (
    // Ligne non cliquable : le détail et les autres actions sont dans la colonne « Actions ».
    <tr className="ex-row" data-testid="schedule-row">
      <td>
        <span className="pl-name" title={s.name}>{s.name}</span>
        {s.createdByUsername && <span className="pl-sub">par {s.createdByUsername}</span>}
      </td>
      <td className="pl-cell" data-label="Scénario">
        <span className="pl-name" title={s.scenarioName}>{s.scenarioName}</span>
        <span className="pl-sub">{s.applicationName}</span>
      </td>
      <td className="pl-freq">
        {describeSchedule(s)}
        {s.timezone && <span className="pl-sub">{s.timezone}</span>}
      </td>
      <td className="pl-cell" data-label="Prochaine :">
        {s.nextRunAt && s.state === 'ACTIVE' ? (
          <span className="pl-next" title={`${formatInZone(s.nextRunAt, s.timezone)} (${s.timezone})`}>{relativeTime(s.nextRunAt)}</span>
        ) : <span className="ex-muted">—</span>}
      </td>
      <td className="pl-cell" data-label="Dernière :">
        {s.lastEvent ? (
          <span className="pl-event" title={`${s.lastEvent.reason} (prévue le ${formatInZone(s.lastEvent.plannedAt, s.timezone)})`}>
            <i className="bi bi-exclamation-circle me-1"></i>{OUTCOME_LABEL[s.lastEvent.outcome]} · {relativeTime(s.lastEvent.plannedAt)}
          </span>
        ) : s.lastRun ? (
          <span className="pl-last" title={s.lastRun.startedAt ? `Lancée ${fullDate(s.lastRun.startedAt)}` : undefined}>
            <Link to={`/executions/report/${s.lastRun.executionId}`} className={`pt-pill ${statusPill(s.lastRun.status)} ex-pill`}>
              {statusLabel(s.lastRun.status)}
            </Link>
            {s.lastRun.p95Ms != null && <span className="num">P95 {formatMs(s.lastRun.p95Ms)}</span>}
            {change && (
              <span className={`pl-delta ${change.tone}`}
                title={`Par rapport à l’exécution précédente (P95 ${formatMs(s.lastRun.previousP95Ms)})`}>{change.text}</span>
            )}
          </span>
        ) : <span className="ex-muted">Jamais lancée</span>}
      </td>
      <td>
        <span className={`pt-pill ${STATE_PILL[s.state]} ex-pill pl-state`} title={s.stateReason ?? undefined}>{STATE_LABEL[s.state]}</span>
      </td>
      <td className="ex-col-actions">
        <div className="ex-actions">
          <button type="button" className="ex-icon-btn" title="Voir le détail (historique des lancements)" aria-label={`Voir le détail de ${s.name}`}
            onClick={onHistory}>
            <i className="bi bi-eye" aria-hidden="true"></i>
          </button>
          {canWrite && (
            <button type="button" className="ex-icon-btn" aria-label={`Lancer maintenant ${s.name}`}
              title={suspended ? s.stateReason ?? 'Scénario indisponible' : 'Lancer maintenant'} disabled={busy || suspended} onClick={onRunNow}>
              <i className="bi bi-play-fill"></i>
            </button>
          )}
          {canWrite && !finished && (
            <button type="button" className="ex-icon-btn" aria-label={`${paused || suspended ? 'Reprendre' : 'Mettre en pause'} ${s.name}`}
              title={suspended ? s.stateReason ?? 'Reprend quand le scénario est disponible' : paused ? 'Reprendre' : 'Mettre en pause'}
              disabled={busy || suspended} onClick={onTogglePause}>
              <i className={`bi ${paused || suspended ? 'bi-play-circle' : 'bi-pause-fill'}`}></i>
            </button>
          )}
          <RowActionsMenu label={`Plus d’actions pour ${s.name}`} actions={actions} />
        </div>
      </td>
    </tr>
  )
}
