import { formatDate } from '../utils/dateFormat'
import { useRegressionThreshold } from '../utils/thresholds'
import { MouseEvent as ReactMouseEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import {
  BackendApplicationResponse,
  BackendBulkActionResponse,
  BackendExecutionHistoryResponse,
  BackendExecutionPageResponse,
  BackendExecutionRequest,
  BackendExecutionStatus,
  BackendExecutionStatusResponse,
  BackendScenarioResponse,
  BackendStepResponse,
} from '../types/backendContracts'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { stepsBackendApi } from '../services/api/stepsBackend'
import { useApiList } from '../hooks/useApiResource'
import Pagination from '../components/Pagination'
import SearchSelect from '../components/SearchSelect'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { isTerminal, progressOf, useExecutionMonitor } from '../context/ExecutionMonitorContext'
import { MONITORING_SCOPE_TEXT, formatCpu } from '../components/application-monitoring/monitoringModel'
import { formatMs, formatRps } from '../utils/numberFormat'
import {
  PERIOD_OPTIONS, Period, statusLabel, statusPill, formatHumanDuration, formatRequests, fullDate, periodBounds, relativeTime, requestsOf,
  cpuLevel, p95Delta,
} from './executions/executionsModel'
import RowActionsMenu, { RowAction } from './executions/RowActionsMenu'
import FrDateInput from './executions/FrDateInput'
import ExecutionDeletionDialog, { bulkDeletionMessage, describeExecutionError as describeApiError } from './executions/ExecutionDeletionDialog'
import './applications.css'
import './executions/executions.css'

// ============================================================
// Page Exécutions : liste filtrée, triée et paginée CÔTÉ SERVEUR
// (GET /api/executions/page : statut, scénario, application, période,
// recherche, tri, compteurs par statut suivant les mêmes filtres). Filtres,
// tri, page et recherche vivent dans l'URL (rechargement / partage de
// lien) ; tout changement de filtre ou de tri ramène à la page 1.
//
// Sélection multiple (comparaison, suppression), relance avec suivi en
// direct, annulation, progression des exécutions en cours dans le tableau.
// ============================================================

const STATUS_TABS = ['', 'QUEUED', 'RUNNING', 'PAUSED', 'SUCCESS', 'THRESHOLDS_EXCEEDED', 'FAILED', 'CANCELLED'] as const
type StatusTab = typeof STATUS_TABS[number]
const PAGE_SIZES = [10, 20, 50]

/** Tri serveur (liste blanche du backend) : statut, date de lancement, durée. */
export type SortColumn = 'startedAt' | 'status' | 'duration'
const SORT_COLUMNS: SortColumn[] = ['startedAt', 'status', 'duration']
export const DEFAULT_SORT: { column: SortColumn; direction: 'asc' | 'desc' } = { column: 'startedAt', direction: 'desc' }

/** « status,asc » → { column, direction } ; valeur inconnue → tri par défaut (plus récentes d'abord). */
export function parseSort(raw: string | null): { column: SortColumn; direction: 'asc' | 'desc' } {
  const [column, direction] = (raw ?? '').split(',')
  if (!SORT_COLUMNS.includes(column as SortColumn)) return DEFAULT_SORT
  return { column: column as SortColumn, direction: direction === 'asc' ? 'asc' : 'desc' }
}

const DAY_RE = /^\d{4}-\d{2}-\d{2}$/
const PERIODS: Period[] = ['', '24h', '7d', '30d', 'custom']

/** En attente, en cours ou en pause : annulable, jamais supprimable. */
function isActive(status: BackendExecutionStatus): boolean {
  return status === 'QUEUED' || status === 'RUNNING' || status === 'PAUSED'
}

// ============================================================
// Page
// ============================================================

function Executions() {
  const navigate = useNavigate()
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const regressionThreshold = useRegressionThreshold()
  const monitor = useExecutionMonitor()
  const [searchParams, setSearchParams] = useSearchParams()

  const isKeycloak = authProvider === 'keycloak'
  // Lancement / annulation / relance / suppression : SUPER_ADMIN et PERFORMANCE_ENGINEER (voir ExecutionController).
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  // ---------------------------------------------------------------- filtres dans l'URL
  const rawStatus = searchParams.get('status') ?? ''
  const urlStatus: StatusTab = (STATUS_TABS as readonly string[]).includes(rawStatus) ? (rawStatus as StatusTab) : ''
  const urlScenario = searchParams.get('scenario') ?? ''
  const urlApp = searchParams.get('app') ?? ''
  const urlQuery = searchParams.get('q') ?? ''
  const urlPage = Math.max(1, Number(searchParams.get('page')) || 1)
  const urlSize = PAGE_SIZES.includes(Number(searchParams.get('size'))) ? Number(searchParams.get('size')) : 10
  // Période : glissante (24 h, 7 j, 30 j) ou personnalisée (journées entières, heure locale).
  const urlFrom = DAY_RE.test(searchParams.get('from') ?? '') ? (searchParams.get('from') as string) : ''
  const urlTo = DAY_RE.test(searchParams.get('to') ?? '') ? (searchParams.get('to') as string) : ''
  const rawPeriod = searchParams.get('period') ?? ''
  const urlPeriod: Period = PERIODS.includes(rawPeriod as Period) && rawPeriod !== '' ? (rawPeriod as Period) : urlFrom || urlTo ? 'custom' : ''
  const urlSort = parseSort(searchParams.get('sort'))
  const hasFilters = !!(urlStatus || urlScenario || urlApp || urlQuery || urlPeriod)

  const updateParams = useCallback((patch: Record<string, string | null>, resetPage = true) =>
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      Object.entries(patch).forEach(([k, v]) => (v === null || v === '' ? next.delete(k) : next.set(k, v)))
      if (resetPage) next.delete('page')
      return next
    }, { replace: true }), [setSearchParams])

  // Recherche avec debounce (350 ms) : l'URL, donc la requête, suit la saisie.
  const [searchInput, setSearchInput] = useState(urlQuery)
  useEffect(() => { setSearchInput(urlQuery) }, [urlQuery])
  useEffect(() => {
    if (searchInput.trim() === urlQuery) return
    const timer = window.setTimeout(() => updateParams({ q: searchInput.trim() || null }), 350)
    return () => window.clearTimeout(timer)
  }, [searchInput, urlQuery, updateParams])

  const resetFilters = () => {
    setSearchInput('')
    setSearchParams((prev) => {
      const next = new URLSearchParams()
      if (prev.get('size')) next.set('size', prev.get('size') as string)
      if (prev.get('sort')) next.set('sort', prev.get('sort') as string)
      return next
    }, { replace: true })
  }

  const changePeriod = (period: Period) => updateParams(period === 'custom'
    ? { period: 'custom' }
    : { period: period || null, from: null, to: null })

  // ---------------------------------------------------------------- données
  const [data, setData] = useState<BackendExecutionPageResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const loadPage = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      const result = await executionsBackendApi.getPage({
        status: urlStatus || undefined, scenarioId: urlScenario || undefined, applicationId: urlApp || undefined,
        search: urlQuery || undefined, page: urlPage - 1, size: urlSize, ...periodBounds(urlPeriod, urlFrom, urlTo),
        sort: `${urlSort.column},${urlSort.direction}`,
      })
      // Page devenue vide (ex. après une suppression) : revenir à la dernière page existante.
      if (result.content.length === 0 && urlPage > 1 && result.totalElements > 0) {
        updateParams({ page: String(Math.max(1, result.totalPages)) }, false)
        return
      }
      setData(result)
      setLoadError(null)
    } catch (err) {
      setLoadError(describeApiError(err, 'Erreur de chargement'))
    } finally {
      if (!silent) setLoading(false)
    }
  }, [urlStatus, urlScenario, urlApp, urlQuery, urlPage, urlSize, urlPeriod, urlFrom, urlTo, urlSort.column, urlSort.direction, updateParams])
  useEffect(() => { loadPage() }, [loadPage])

  /** Tri par colonne (statut, date, durée) : clic = décroissant, second clic = croissant ; retour à la page 1. */
  const toggleSort = (column: SortColumn) => {
    const direction = urlSort.column === column && urlSort.direction === 'desc' ? 'asc' : 'desc'
    updateParams({ sort: column === DEFAULT_SORT.column && direction === DEFAULT_SORT.direction ? null : `${column},${direction}` })
  }
  const sortHeader = (column: SortColumn, label: string, className = '') => {
    const active = urlSort.column === column
    return (
      <th className={`ex-th-sort ${active ? 'sorted' : ''} ${className}`} aria-sort={active ? (urlSort.direction === 'asc' ? 'ascending' : 'descending') : 'none'}>
        <button type="button" className="ex-sort" onClick={() => toggleSort(column)} aria-label={`Trier par ${label.toLowerCase()}`}>
          {label}
          <i className={`bi ${!active ? 'bi-arrow-down-up' : urlSort.direction === 'asc' ? 'bi-arrow-up' : 'bi-arrow-down'} ex-sort-icon`} aria-hidden="true"></i>
        </button>
      </th>
    )
  }

  const rows = data?.content ?? []
  const activeIds = rows.filter((e) => isActive(e.status)).map((e) => e.id).join(',')

  // Rafraîchissement automatique uniquement tant qu'une exécution affichée est active.
  useEffect(() => {
    if (!activeIds) return
    const timer = window.setInterval(() => { loadPage(true) }, 3000)
    return () => window.clearInterval(timer)
  }, [activeIds, loadPage])

  // Progression en direct des lignes actives (statut léger, toutes les 2 s).
  const [liveStatus, setLiveStatus] = useState<Record<string, BackendExecutionStatusResponse>>({})
  useEffect(() => {
    if (!activeIds) return
    const poll = () => {
      for (const id of activeIds.split(',')) {
        executionsBackendApi.getStatus(id).then((s) => setLiveStatus((prev) => ({ ...prev, [id]: s }))).catch(() => {})
      }
    }
    poll()
    const timer = window.setInterval(poll, 2000)
    return () => window.clearInterval(timer)
  }, [activeIds])

  const { data: scenarios } = useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll())
  const { data: applications } = useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const { data: allSteps } = useApiList<BackendStepResponse>(() => stepsBackendApi.getAll())

  const scenarioById = useMemo(() => new Map(scenarios.map((s) => [s.id, s])), [scenarios])
  const stepCountByScenario = useMemo(() => {
    const map = new Map<string, number>()
    for (const step of allSteps) map.set(step.scenarioId, (map.get(step.scenarioId) ?? 0) + 1)
    return map
  }, [allSteps])
  const scenarioOptions = useMemo(() => scenarios
    .filter((s) => !urlApp || s.applicationId === urlApp)
    .map((s) => ({ value: s.id, label: s.name, hint: s.applicationName }))
    .sort((a, b) => a.label.localeCompare(b.label, 'fr')), [scenarios, urlApp])

  // ---------------------------------------------------------------- sélection multiple
  const [selected, setSelected] = useState<Set<string>>(new Set())
  useEffect(() => { setSelected(new Set()) }, [urlStatus, urlScenario, urlApp, urlQuery, urlPage, urlSize, urlPeriod, urlFrom, urlTo])
  const pageIds = rows.map((e) => e.id)
  const allSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id))
  const someSelected = !allSelected && pageIds.some((id) => selected.has(id))
  const selectAllRef = useRef<HTMLInputElement | null>(null)
  useEffect(() => { if (selectAllRef.current) selectAllRef.current.indeterminate = someSelected }, [someSelected])
  const toggleOne = (id: string) => setSelected((prev) => {
    const next = new Set(prev)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(pageIds))
  // Comparaison : 2 à 4 exécutions terminées (sinon le bouton explique pourquoi il est désactivé).
  const compareBlocked = selected.size < 2 ? 'Sélectionnez au moins 2 exécutions terminées.'
    : selected.size > 4 ? 'Comparaison limitée à 4 exécutions.'
    : rows.some((e) => selected.has(e.id) && isActive(e.status)) ? 'Seules les exécutions terminées peuvent être comparées.'
    : null

  // ---------------------------------------------------------------- actions
  const [deletion, setDeletion] = useState<string[] | null>(null)
  const [retryConfirm, setRetryConfirm] = useState<BackendExecutionHistoryResponse | null>(null)
  const [retrying, setRetrying] = useState(false)
  const [cancellingId, setCancellingId] = useState<string | null>(null)

  const reportBulk = (result: BackendBulkActionResponse) => {
    const { message, type } = bulkDeletionMessage(result)
    showToast(message, type)
  }

  const confirmDeletion = async () => {
    if (!deletion) return
    if (deletion.length === 1) {
      await executionsBackendApi.remove(deletion[0])
      showToast('Exécution supprimée définitivement.', 'success')
    } else {
      reportBulk(await executionsBackendApi.bulkDelete(deletion))
    }
    setDeletion(null)
    setSelected(new Set())
    await loadPage(true)
  }

  const confirmRetry = async () => {
    if (!retryConfirm || retrying) return
    setRetrying(true)
    try {
      const result = await executionsBackendApi.retry(retryConfirm.id)
      monitor.track(result.id, result.scenarioName)
      setRetryConfirm(null)
      showToast(`Nouvelle exécution de « ${result.scenarioName} » lancée (${result.virtualUsers} utilisateur(s) virtuel(s)).`, 'success')
      navigate(`/executions/live/${result.id}`)
    } catch (err) {
      showToast(describeApiError(err, 'Erreur lors de la relance.'), 'danger')
    } finally {
      setRetrying(false)
    }
  }

  // Option « Conserver » : résultats détaillés jamais supprimés par la purge automatique.
  const handleKeep = async (exec: BackendExecutionHistoryResponse) => {
    try {
      const retention = await executionsBackendApi.setKeep(exec.id, !exec.keepResults)
      setData((prev) => (prev ? { ...prev, content: prev.content.map((r) => (r.id === exec.id ? { ...r, keepResults: retention.keep } : r)) } : prev))
      showToast(retention.keep ? `Exécution de ${exec.scenarioName} conservée : ses résultats détaillés ne seront jamais supprimés.`
        : `Conservation retirée : les résultats détaillés de ${exec.scenarioName} suivront la durée de conservation.`, 'success')
    } catch (err) {
      showToast(describeApiError(err, 'Impossible de modifier la conservation.'), 'danger')
    }
  }

  const handleCancel = async (exec: { id: string; scenarioName: string; status: BackendExecutionStatus }) => {
    if (!canWrite || cancellingId || !isActive(exec.status)) return
    setCancellingId(exec.id)
    try {
      await executionsBackendApi.cancel(exec.id)
      await loadPage(true)
      showToast(`Annulation de « ${exec.scenarioName} » demandée.`, 'success')
    } catch (err) {
      showToast(describeApiError(err, "Erreur lors de l'annulation."), 'danger')
    } finally {
      setCancellingId(null)
    }
  }

  // ---------------------------------------------------------------- nouvelle exécution (inchangé)
  const [showLaunchModal, setShowLaunchModal] = useState(false)
  const [selectedScenarioId, setSelectedScenarioId] = useState('')
  const [launching, setLaunching] = useState(false)
  const [launchError, setLaunchError] = useState<string | null>(null)
  const launchableScenarios = scenarios.filter((s) => s.status === 'ACTIVE' && (stepCountByScenario.get(s.id) ?? 0) > 0)

  const openLaunchModal = () => {
    if (!canWrite) return
    setSelectedScenarioId('')
    setLaunchError(null)
    setShowLaunchModal(true)
  }
  const closeLaunchModal = () => { if (!launching) setShowLaunchModal(false) }

  const handleLaunch = async () => {
    if (!canWrite || !selectedScenarioId || launching) return
    if ((stepCountByScenario.get(selectedScenarioId) ?? 0) === 0) {
      setLaunchError('Ce scénario ne contient aucune étape.')
      return
    }
    setLaunching(true)
    setLaunchError(null)
    try {
      const payload: BackendExecutionRequest = { scenarioId: selectedScenarioId }
      const result = await executionsBackendApi.execute(payload)
      monitor.track(result.id, result.scenarioName)
      await loadPage(true)
      setShowLaunchModal(false)
      showToast(`Exécution de « ${result.scenarioName} » lancée (${result.virtualUsers} utilisateur(s) virtuel(s)).`, 'success')
    } catch (err) {
      const message = describeApiError(err, "Erreur lors du lancement de l'exécution.")
      setLaunchError(message)
      showToast(message, 'danger')
    } finally {
      setLaunching(false)
    }
  }

  // Clic sur une ligne : rapport (exécution terminée) ou suivi en direct (exécution active).
  const openRow = (exec: BackendExecutionHistoryResponse) => {
    if (isActive(exec.status)) {
      monitor.track(exec.id, exec.scenarioName)
      navigate(`/executions/live/${exec.id}`)
    } else {
      navigate(`/executions/report/${exec.id}`)
    }
  }
  /** Les clics sur la case à cocher, les liens et les boutons d'action n'ouvrent pas la ligne. */
  const onRowClick = (e: ReactMouseEvent, exec: BackendExecutionHistoryResponse) => {
    if ((e.target as HTMLElement).closest('input, button, a, [role="menu"]')) return
    openRow(exec)
  }

  const counts = data?.counts
  const totalItems = data?.totalElements ?? 0
  const totalPages = Math.max(1, data?.totalPages ?? 1)
  const startIndex = totalItems === 0 ? 0 : (urlPage - 1) * urlSize + 1
  const endIndex = Math.min((urlPage - 1) * urlSize + rows.length, totalItems)
  const tabCount = (tab: StatusTab): number | null => !counts ? null : tab === '' ? counts.total
    : { QUEUED: counts.queued, RUNNING: counts.running, PAUSED: counts.paused, SUCCESS: counts.success,
      THRESHOLDS_EXCEEDED: counts.thresholdsExceeded ?? 0, FAILED: counts.failed, CANCELLED: counts.cancelled }[tab]

  const rowActions = (exec: BackendExecutionHistoryResponse): RowAction[] => {
    if (!canWrite) return []
    const active = isActive(exec.status)
    return [
      { label: 'Relancer', icon: 'bi-arrow-repeat', onSelect: () => setRetryConfirm(exec) },
      ...(active ? [{ label: cancellingId === exec.id ? 'Annulation…' : 'Annuler l’exécution', icon: 'bi-slash-circle', onSelect: () => handleCancel(exec),
        disabled: cancellingId === exec.id }] : []),
      ...(exec.resultsPurgedAt ? [] : [{ label: exec.keepResults ? 'Ne plus conserver' : 'Conserver', icon: exec.keepResults ? 'bi-bookmark-x' : 'bi-bookmark',
        onSelect: () => handleKeep(exec) }]),
      { label: 'Supprimer', icon: 'bi-trash3', danger: true, onSelect: () => setDeletion([exec.id]), disabled: active,
        reason: 'Impossible de supprimer une exécution en attente, en cours ou en pause : annulez-la d’abord.' },
    ]
  }

  return (
    <div className="pt-content ex-page">
      {/* ---- en-tête : titre à gauche, action principale à droite */}
      <div className="pt-page-header ex-header">
        <div className="page-title">
          <h1>Exécutions</h1>
          <p>Lancez, suivez et comparez vos tests de charge</p>
        </div>
        {canWrite && (
          <button className="pt-btn-primary ex-launch" onClick={openLaunchModal}>
            <i className="bi bi-play-fill"></i> Nouvelle exécution
          </button>
        )}
      </div>

      {loadError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger les exécutions : {loadError}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => loadPage()}>Réessayer</button>
        </div>
      )}

      {/* ---- barre de filtres : une ligne sur ordinateur, plusieurs sur mobile */}
      <div className="ex-filters" role="search">
        <div className="pt-search ex-filter-search">
          <i className="bi bi-search"></i>
          <input type="text" placeholder="Rechercher (scénario, application, id)…" value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)} aria-label="Rechercher une exécution" />
        </div>
        <div className="ex-filter-scenario">
          <SearchSelect options={scenarioOptions} value={urlScenario} onChange={(v) => updateParams({ scenario: v || null })}
            allLabel="Tous les scénarios" ariaLabel="Filtrer par scénario" width="100%" />
        </div>
        <select className="pt-form-control ex-filter-select" value={urlApp} aria-label="Filtrer par application"
          onChange={(e) => {
            const app = e.target.value
            // Le scénario choisi n'appartient plus à l'application : filtre scénario retiré.
            const keepScenario = !app || !urlScenario || scenarioById.get(urlScenario)?.applicationId === app
            updateParams({ app: app || null, ...(keepScenario ? {} : { scenario: null }) })
          }}>
          <option value="">Toutes les applications</option>
          {applications.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
        <select className="pt-form-control ex-filter-select" value={urlPeriod} aria-label="Période" onChange={(e) => changePeriod(e.target.value as Period)}>
          {PERIOD_OPTIONS.map((p) => <option key={p.value || 'all'} value={p.value}>{p.value === '' ? 'Toutes les périodes' : p.label}</option>)}
        </select>
        {urlPeriod === 'custom' && (
          <span className="ex-custom-dates" role="group" aria-label="Période personnalisée">
            <FrDateInput value={urlFrom} onChange={(v) => updateParams({ from: v || null })} label="Lancées à partir du" />
            <span className="ex-muted">au</span>
            <FrDateInput value={urlTo} onChange={(v) => updateParams({ to: v || null })} label="Lancées jusqu’au" />
          </span>
        )}
        {hasFilters && (
          <button type="button" className="pt-btn-ghost ex-reset" onClick={resetFilters}>
            <i className="bi bi-x-circle"></i> Réinitialiser
          </button>
        )}
      </div>

      <div className="pt-card ex-card">
        {/* ---- onglets de statut, collés au tableau */}
        <div className="ex-tabs" role="tablist" aria-label="Filtrer par statut">
          {STATUS_TABS.map((tab) => {
            const n = tabCount(tab)
            const selectedTab = urlStatus === tab
            return (
              <button key={tab || 'all'} type="button" role="tab" aria-selected={selectedTab} aria-pressed={selectedTab}
                className={`ex-tab ${selectedTab ? 'active' : ''} ${n === 0 && !selectedTab ? 'empty' : ''}`}
                onClick={() => updateParams({ status: tab || null })}>
                {tab === '' ? 'Toutes' : statusLabel(tab)}
                {n != null && n > 0 && <span className="ex-tab-count">{n}</span>}
              </button>
            )
          })}
        </div>

        {selected.size > 0 && (
          <div className="ap-bulk-bar ex-bulk" role="region" aria-label="Actions sur la sélection">
            <strong>{selected.size} sélectionnée{selected.size > 1 ? 's' : ''}</strong>
            <div className="d-flex gap-2 flex-wrap">
              <button type="button" className="pt-btn-primary" disabled={!!compareBlocked} title={compareBlocked ?? 'Comparer ces exécutions côte à côte'}
                onClick={() => navigate(`/executions/compare?ids=${[...selected].join(',')}`)}>
                <i className="bi bi-layout-three-columns"></i> Comparer
              </button>
              {canWrite && (
                <button type="button" className="pt-btn-danger" onClick={() => setDeletion([...selected])}>
                  <i className="bi bi-trash3"></i> Supprimer
                </button>
              )}
              <button type="button" className="pt-btn-ghost" onClick={() => setSelected(new Set())}>Annuler la sélection</button>
            </div>
          </div>
        )}

        <div className="ex-table-wrap">
          <table className="ex-table">
            <thead>
              <tr>
                <th className="ex-col-check">
                  <input ref={selectAllRef} type="checkbox" className="form-check-input" checked={allSelected} onChange={toggleAll}
                    aria-label="Sélectionner toutes les exécutions de la page" disabled={rows.length === 0} />
                </th>
                <th>Scénario</th>
                {sortHeader('status', 'Statut')}
                <th className="num" title="Utilisateurs virtuels">VU</th>
                <th className="num" title="Requêtes envoyées · taux d’erreur">Requêtes</th>
                <th className="num" title="Requêtes par seconde">Débit</th>
                <th className="num" title="95 % des requêtes ont répondu en moins de ce temps">P95</th>
                <th className="num" title={MONITORING_SCOPE_TEXT}>CPU app.</th>
                {sortHeader('duration', 'Durée', 'num')}
                {sortHeader('startedAt', 'Démarrée')}
                <th className="ex-col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading && !data ? (
                Array.from({ length: Math.min(urlSize, 6) }).map((_, i) => (
                  <tr key={`sk-${i}`} className="ex-skeleton-row" aria-hidden="true">
                    <td colSpan={11}><div className="ex-skeleton"></div></td>
                  </tr>
                ))
              ) : rows.length === 0 ? (
                <tr className="ex-empty-row">
                  <td colSpan={11}>
                    <div className="ex-empty" role="status">
                      <i className="bi bi-inbox"></i>
                      <p>{hasFilters ? 'Aucune exécution ne correspond à ces filtres.' : 'Aucune exécution pour l’instant. Lancez votre premier test !'}</p>
                      {hasFilters && <button type="button" className="pt-btn-outline" onClick={resetFilters}>Réinitialiser les filtres</button>}
                    </div>
                  </td>
                </tr>
              ) : rows.map((exec) => {
                const active = isActive(exec.status)
                const live = liveStatus[exec.id]
                const status = (live?.status ?? exec.status) as BackendExecutionStatus
                const progress = active && live && !isTerminal(live) ? progressOf(live) : null
                const req = requestsOf(exec, active && live ? { ok: live.successfulRequests, ko: live.failedRequests } : null)
                const reqText = formatRequests(req.total, req.errorRate)
                return (
                  <tr key={exec.id} className={`ex-row ${selected.has(exec.id) ? 'selected' : ''}`} onClick={(e) => onRowClick(e, exec)}
                    data-testid="execution-row">
                    <td className="ex-col-check">
                      <input type="checkbox" className="form-check-input" checked={selected.has(exec.id)} onChange={() => toggleOne(exec.id)}
                        aria-label={`Sélectionner l’exécution de ${exec.scenarioName} du ${formatDate(exec.startedAt)}`} />
                    </td>
                    <td data-label="Scénario" className="ex-scenario">
                      <span className="ex-scenario-name" title={exec.scenarioName}>{exec.scenarioName}</span>
                      <span className="ex-scenario-app">
                        {exec.applicationName}
                        {exec.keepResults && <span className="ex-retention" title="Conservée : résultats détaillés jamais supprimés">
                          <i className="bi bi-bookmark-check-fill" aria-hidden="true"></i> Conservée</span>}
                        {exec.resultsPurgedAt && <span className="ex-retention muted" title="Détail des requêtes supprimé, résumé conservé">
                          <i className="bi bi-archive" aria-hidden="true"></i> Résumé seul</span>}
                      </span>
                    </td>
                    <td data-label="Statut" className="ex-status">
                      <span className={`pt-pill ${statusPill(status)} ex-pill`}><span className="ex-dot" aria-hidden="true"></span>{statusLabel(status)}</span>
                      {!active && exec.statusReason && <span className="ex-status-reason" title={exec.statusReason}>{exec.statusReason}</span>}
                      {active && (
                        <div className="ex-progress" aria-label={progress ? `Progression : ${progress.label}` : 'Progression'}>
                          <div className="em-progress ex-progress-bar">
                            <div className={`em-progress-bar ${progress?.percent == null ? 'indeterminate' : ''} ${status === 'PAUSED' ? 'paused' : ''}`}
                              style={{ width: `${progress?.percent ?? 30}%` }}></div>
                          </div>
                          <span className="ex-muted">{progress ? `${progress.percent ?? '—'} %` : '…'}</span>
                          <Link to={`/executions/live/${exec.id}`} onClick={() => monitor.track(exec.id, exec.scenarioName)}>Suivre</Link>
                        </div>
                      )}
                    </td>
                    <td data-label="VU" className="num">{exec.virtualUsers}</td>
                    <td data-label="Requêtes" className="num ex-requests">
                      {reqText.total}
                      {reqText.rate && <span className={`ex-rate ${req.failed > 0 ? 'bad' : ''}`} title={`${req.failed} en échec`}>{reqText.rate}</span>}
                    </td>
                    <td data-label="Débit" className="num">{exec.throughput == null ? '—' : formatRps(exec.throughput)}</td>
                    <td data-label="P95" className="num" data-testid="p95-cell">
                      {(() => {
                        const delta = p95Delta(exec.p95Ms, exec.previousP95Ms, regressionThreshold)
                        const title = exec.previousP95Ms != null && exec.previousStartedAt
                          ? `Exécution précédente du scénario : P95 ${formatMs(exec.previousP95Ms)}, le ${formatDate(exec.previousStartedAt)}`
                          : 'Pas d’exécution précédente terminée pour ce scénario.'
                        return (
                          <span className="ex-p95">
                            <span>{formatMs(exec.p95Ms ?? null)}</span>
                            {!active && <span className={`ex-delta ${delta.tone}`} title={title}>{delta.text}</span>}
                          </span>
                        )
                      })()}
                    </td>
                    <td data-label="CPU app." className="num" data-testid="cpu-cell"
                      title={exec.cpuMaxPercent != null ? `CPU du processus testé (% d’un cœur) : moyenne ${formatCpu(exec.cpuAvgPercent)}, maximum ${formatCpu(exec.cpuMaxPercent)}` : 'Aucune mesure : monitoring non configuré pour cette application (ou indisponible).'}>
                      {exec.cpuMaxPercent != null ? (
                        <span className="ex-cpu">
                          <span>{formatCpu(Math.round(exec.cpuMaxPercent))}</span>
                          <span className="ex-cpu-track" aria-hidden="true">
                            <span className={`ex-cpu-bar ${cpuLevel(exec.cpuMaxPercent)}`} style={{ width: `${Math.min(100, Math.max(2, exec.cpuMaxPercent))}%` }}></span>
                          </span>
                        </span>
                      ) : <span className="ex-muted">—</span>}
                    </td>
                    <td data-label="Durée" className="num">{formatHumanDuration(exec.duration)}</td>
                    <td data-label="Démarrée" className="ex-started"
                      title={`${fullDate(exec.startedAt)}${exec.triggeredByUsername ? ` — lancée par ${exec.triggeredByUsername}` : ''}`}>
                      {relativeTime(exec.startedAt)}
                    </td>
                    <td className="ex-col-actions">
                      <div className="ex-actions">
                        <button type="button" className="ex-icon-btn" title="Voir le détail (requêtes par étape)" aria-label="Voir les détails"
                          onClick={() => navigate(`/executions/detail/${exec.id}`)}>
                          <i className="bi bi-eye" aria-hidden="true"></i>
                        </button>
                        <button type="button" className="ex-icon-btn" title="Voir le rapport" aria-label="Voir le rapport"
                          onClick={() => navigate(`/executions/report/${exec.id}`)}>
                          <i className="bi bi-file-earmark-bar-graph" aria-hidden="true"></i>
                        </button>
                        <RowActionsMenu actions={rowActions(exec)} label={`Plus d’actions pour ${exec.scenarioName}`} />
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>

        <Pagination
          page={urlPage}
          totalPages={totalPages}
          onPageChange={(p) => updateParams({ page: p > 1 ? String(p) : null }, false)}
          startIndex={startIndex}
          endIndex={endIndex}
          totalItems={totalItems}
          itemLabel="exécutions"
          pageSize={urlSize}
          pageSizeOptions={PAGE_SIZES}
          onPageSizeChange={(size) => updateParams({ size: size === 10 ? null : String(size) })}
        />
      </div>

      {deletion && <ExecutionDeletionDialog ids={deletion} onConfirm={confirmDeletion} onClose={() => setDeletion(null)} />}

      {/* Relance : confirmation, puis suivi en direct de la NOUVELLE exécution. */}
      {retryConfirm && (
        <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1060 }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content" role="dialog" aria-modal="true" aria-labelledby="ex-retry-title"
              style={{ borderRadius: 'var(--pt-radius)', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}>
              <div className="modal-body p-4">
                <h5 id="ex-retry-title" style={{ fontWeight: 700 }}><i className="bi bi-arrow-repeat text-primary me-2"></i>Relancer « {retryConfirm.scenarioName} » ?</h5>
                <p className="mb-2" style={{ fontSize: '13.5px' }}>
                  Une <strong>nouvelle exécution</strong> démarre avec la configuration actuelle du scénario
                  {(() => {
                    const s = scenarioById.get(retryConfirm.scenarioId)
                    return s ? ` (${s.virtualUsers} utilisateur(s), ramp-up ${s.rampUpSeconds} s${s.durationSeconds != null ? `, ${s.durationSeconds} s` : ''}${s.iterations != null ? `, ${s.iterations} itération(s)` : ''})` : ''
                  })()}. L’exécution du {formatDate(retryConfirm.startedAt)} n’est pas modifiée.
                </p>
                <p className="mb-0" style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>Le suivi en direct s’ouvre juste après le lancement.</p>
              </div>
              <div className="modal-footer">
                <button type="button" className="pt-btn-ghost" onClick={() => setRetryConfirm(null)} disabled={retrying}>Annuler</button>
                <button type="button" className="pt-btn-primary" onClick={confirmRetry} disabled={retrying}>
                  {retrying ? <span className="spinner-border spinner-border-sm me-1"></span> : <i className="bi bi-play-fill"></i>} Relancer
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Modale Nouvelle exécution */}
      {showLaunchModal && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '480px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            <div className="d-flex justify-content-between align-items-center mb-4">
              <h5 style={{ fontWeight: 700, margin: 0 }}>Nouvelle exécution</h5>
              <button onClick={closeLaunchModal} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)' }} aria-label="Fermer">
                <i className="bi bi-x"></i>
              </button>
            </div>

            <div className="pt-alert-banner mb-3" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', color: 'var(--pt-text-muted)', fontSize: '12.5px' }}>
              <i className="bi bi-info-circle-fill"></i>
              Le test démarre en arrière-plan (statut En attente puis En cours) avec les paramètres de charge réels du scénario (utilisateurs virtuels, ramp-up, durée/itérations — configurables depuis la page Scénarios). Suivez sa progression ici jusqu'au résultat final.
            </div>

            {launchError && (
              <div className="pt-alert-banner danger mb-3">
                <i className="bi bi-exclamation-triangle-fill"></i>
                {launchError}
              </div>
            )}

            <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Scénario *</label>
            <select className="pt-form-control" style={{ width: '100%' }} value={selectedScenarioId} disabled={launching} onChange={e => setSelectedScenarioId(e.target.value)}>
              <option value="" disabled>Sélectionner un scénario…</option>
              {launchableScenarios.map(s => (
                <option key={s.id} value={s.id}>{s.name} ({s.applicationName})</option>
              ))}
            </select>
            {launchableScenarios.length === 0 && (
              <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>
                Aucun scénario ne possède encore d'étape — ajoutez-en depuis la page Scénarios.
              </div>
            )}
            {selectedScenarioId && (() => {
              const s = scenarioById.get(selectedScenarioId)
              if (!s) return null
              return (
                <div className="d-flex flex-wrap gap-2 mt-2">
                  <span className="pt-pill neutral" style={{ fontSize: '11px' }}><i className="bi bi-people me-1"></i>{s.virtualUsers} VU</span>
                  <span className="pt-pill neutral" style={{ fontSize: '11px' }}><i className="bi bi-graph-up-arrow me-1"></i>Ramp-up {s.rampUpSeconds}s</span>
                  {s.durationSeconds != null && <span className="pt-pill neutral" style={{ fontSize: '11px' }}><i className="bi bi-stopwatch me-1"></i>Durée {s.durationSeconds}s</span>}
                  {s.iterations != null && <span className="pt-pill neutral" style={{ fontSize: '11px' }}><i className="bi bi-arrow-repeat me-1"></i>{s.iterations} itération(s)</span>}
                </div>
              )
            })()}

            <div className="d-flex gap-2 justify-content-end mt-4">
              <button onClick={closeLaunchModal} disabled={launching} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                Annuler
              </button>
              <button
                onClick={handleLaunch}
                disabled={!selectedScenarioId || launching}
                style={{ background: !selectedScenarioId ? '#93C5FD' : 'var(--pt-success)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: !selectedScenarioId ? 'not-allowed' : 'pointer', fontSize: '13.5px', fontWeight: 600, minWidth: '140px' }}
              >
                {launching ? <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Exécution...</> : <><i className="bi bi-play-fill me-2"></i>Lancer</>}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default Executions
