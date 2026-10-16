import { MouseEvent as ReactMouseEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import {
  BackendApplicationResponse, BackendExecutionHistoryResponse, BackendExecutionPageResponse, BackendScenarioResponse,
} from '../types/backendContracts'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { useApiList } from '../hooks/useApiResource'
import Pagination from '../components/Pagination'
import SearchSelect from '../components/SearchSelect'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { formatMs, formatRps } from '../utils/numberFormat'
import { saveBlob } from '../utils/download'
import { Period, fullDate, periodBounds, relativeTime, requestsOf, statusLabel, statusPill } from './executions/executionsModel'
import RowActionsMenu, { RowAction } from './executions/RowActionsMenu'
import FrDateInput from './executions/FrDateInput'
import ExecutionDeletionDialog, { bulkDeletionMessage, describeExecutionError as describeApiError } from './executions/ExecutionDeletionDialog'
import './applications.css'
import './executions/executions.css'
import './reports/reports.css'

// ============================================================
// Page Rapports : consulter et partager les résultats des tests TERMINÉS
// (Réussie, Seuils dépassés, Échouée, Annulée). Liste paginée côté serveur
// (GET /api/executions/page?finished=true), filtres, page et tri dans l'URL.
// Actions : voir le rapport, Exporter Excel (par défaut), exporter en PDF, et
// « ⋯ » : CSV des étapes ou des 4 tableaux (.zip), Conserver, Supprimer (mêmes droits que partout).
// Sélection de 2 à 4 rapports : Comparer.
// ============================================================

export const RESULTS = ['SUCCESS', 'THRESHOLDS_EXCEEDED', 'FAILED', 'CANCELLED'] as const
type Result = typeof RESULTS[number]
export const REPORT_PAGE_SIZES = [10, 25, 50]
export const REPORT_PERIODS: { value: Period; label: string }[] = [
  { value: '7d', label: '7 derniers jours' },
  { value: '30d', label: '30 derniers jours' },
  { value: '90d', label: '3 derniers mois' },
  { value: '', label: 'Toutes les dates' },
  { value: 'custom', label: 'Personnalisée' },
]
const DAY_RE = /^\d{4}-\d{2}-\d{2}$/

function Reports() {
  const navigate = useNavigate()
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const [searchParams, setSearchParams] = useSearchParams()
  // Conserver et Supprimer : mêmes règles que la page Exécutions (Super administrateur, Ingénieur performance).
  const canWrite = authProvider === 'keycloak' && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  // ---------------------------------------------------------------- filtres dans l'URL
  const rawResult = searchParams.get('result') ?? ''
  const urlResult: Result | '' = (RESULTS as readonly string[]).includes(rawResult) ? (rawResult as Result) : ''
  const urlScenario = searchParams.get('scenario') ?? ''
  const urlApp = searchParams.get('app') ?? ''
  const urlQuery = searchParams.get('q') ?? ''
  const urlPage = Math.max(1, Number(searchParams.get('page')) || 1)
  const urlSize = REPORT_PAGE_SIZES.includes(Number(searchParams.get('size'))) ? Number(searchParams.get('size')) : 10
  const urlFrom = DAY_RE.test(searchParams.get('from') ?? '') ? (searchParams.get('from') as string) : ''
  const urlTo = DAY_RE.test(searchParams.get('to') ?? '') ? (searchParams.get('to') as string) : ''
  const rawPeriod = searchParams.get('period') ?? ''
  const urlPeriod: Period = REPORT_PERIODS.some((p) => p.value === rawPeriod && rawPeriod !== '') ? (rawPeriod as Period)
    : urlFrom || urlTo ? 'custom' : ''
  const urlSortAsc = searchParams.get('sort') === 'startedAt,asc'
  const hasFilters = !!(urlResult || urlScenario || urlApp || urlQuery || urlPeriod)

  const updateParams = useCallback((patch: Record<string, string | null>, resetPage = true) =>
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      Object.entries(patch).forEach(([k, v]) => (v === null || v === '' ? next.delete(k) : next.set(k, v)))
      if (resetPage) next.delete('page')
      return next
    }, { replace: true }), [setSearchParams])

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

  // ---------------------------------------------------------------- données
  const [data, setData] = useState<BackendExecutionPageResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const loadPage = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      const result = await executionsBackendApi.getPage({
        finished: true, status: urlResult || undefined, scenarioId: urlScenario || undefined, applicationId: urlApp || undefined,
        search: urlQuery || undefined, page: urlPage - 1, size: urlSize, ...periodBounds(urlPeriod, urlFrom, urlTo),
        sort: urlSortAsc ? 'startedAt,asc' : 'startedAt,desc',
      })
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
  }, [urlResult, urlScenario, urlApp, urlQuery, urlPage, urlSize, urlPeriod, urlFrom, urlTo, urlSortAsc, updateParams])
  useEffect(() => { loadPage() }, [loadPage])

  const { data: scenarios } = useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll())
  const { data: applications } = useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const scenarioById = useMemo(() => new Map(scenarios.map((s) => [s.id, s])), [scenarios])
  const scenarioOptions = useMemo(() => scenarios
    .filter((s) => !urlApp || s.applicationId === urlApp)
    .map((s) => ({ value: s.id, label: s.name, hint: s.applicationName }))
    .sort((a, b) => a.label.localeCompare(b.label, 'fr')), [scenarios, urlApp])

  const rows = data?.content ?? []
  const counts = data?.counts
  const resultCount = (r: Result): number | null => !counts ? null
    : { SUCCESS: counts.success, THRESHOLDS_EXCEEDED: counts.thresholdsExceeded ?? 0, FAILED: counts.failed, CANCELLED: counts.cancelled }[r]

  // ---------------------------------------------------------------- sélection (comparaison)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  useEffect(() => { setSelected(new Set()) }, [urlResult, urlScenario, urlApp, urlQuery, urlPage, urlSize, urlPeriod, urlFrom, urlTo])
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
  const compareBlocked = selected.size < 2 ? 'Sélectionnez de 2 à 4 rapports.' : selected.size > 4 ? 'Comparaison limitée à 4 rapports.' : null

  // ---------------------------------------------------------------- actions
  // Exports (fichiers générés par le serveur, journalisés) : Excel par défaut, PDF, CSV (étapes) ou les 4 tableaux en .zip.
  const [exporting, setExporting] = useState<string | null>(null)
  const EXPORTS = {
    xlsx: { label: 'Excel', run: executionsBackendApi.exportReportXlsx },
    pdf: { label: 'PDF', run: executionsBackendApi.exportReportPdf },
    csv: { label: 'CSV (étapes)', run: (id: string) => executionsBackendApi.exportReportCsv(id) },
    zip: { label: 'CSV (4 tableaux, .zip)', run: executionsBackendApi.exportReportCsvZip },
  } as const
  const download = async (exec: BackendExecutionHistoryResponse, kind: keyof typeof EXPORTS) => {
    if (exporting) return
    setExporting(`${exec.id}:${kind}`)
    try {
      const { blob, fileName } = await EXPORTS[kind].run(exec.id)
      saveBlob(blob, fileName)
      showToast(`Rapport ${EXPORTS[kind].label} de « ${exec.scenarioName} » téléchargé.`, 'success')
    } catch (err) {
      showToast(describeApiError(err, `L’export ${EXPORTS[kind].label} a échoué.`), 'danger')
    } finally {
      setExporting(null)
    }
  }
  const busy = (exec: BackendExecutionHistoryResponse, kind: string) => exporting === `${exec.id}:${kind}`
  const handleKeep = async (exec: BackendExecutionHistoryResponse) => {
    try {
      const retention = await executionsBackendApi.setKeep(exec.id, !exec.keepResults)
      setData((prev) => (prev ? { ...prev, content: prev.content.map((r) => (r.id === exec.id ? { ...r, keepResults: retention.keep } : r)) } : prev))
      showToast(retention.keep ? `Rapport de ${exec.scenarioName} conservé : ses résultats détaillés ne seront jamais supprimés.`
        : `Conservation retirée : les résultats détaillés de ${exec.scenarioName} suivront la durée de conservation.`, 'success')
    } catch (err) {
      showToast(describeApiError(err, 'Impossible de modifier la conservation.'), 'danger')
    }
  }
  const [deletion, setDeletion] = useState<string[] | null>(null)
  const confirmDeletion = async () => {
    if (!deletion) return
    if (deletion.length === 1) {
      await executionsBackendApi.remove(deletion[0])
      showToast('Rapport supprimé définitivement.', 'success')
    } else {
      const { message, type } = bulkDeletionMessage(await executionsBackendApi.bulkDelete(deletion))
      showToast(message, type)
    }
    setDeletion(null)
    setSelected(new Set())
    await loadPage(true)
  }

  const rowActions = (exec: BackendExecutionHistoryResponse): RowAction[] => [
    { label: 'Exporter CSV (étapes)', icon: 'bi-filetype-csv', onSelect: () => download(exec, 'csv') },
    { label: 'CSV : les 4 tableaux (.zip)', icon: 'bi-file-earmark-zip', onSelect: () => download(exec, 'zip') },
    ...(canWrite && !exec.resultsPurgedAt ? [{ label: exec.keepResults ? 'Ne plus conserver' : 'Conserver', icon: exec.keepResults ? 'bi-bookmark-x' : 'bi-bookmark',
      onSelect: () => handleKeep(exec) }] : []),
    ...(canWrite ? [{ label: 'Supprimer', icon: 'bi-trash3', danger: true, onSelect: () => setDeletion([exec.id]) }] : []),
  ]

  const onRowClick = (e: ReactMouseEvent, exec: BackendExecutionHistoryResponse) => {
    if ((e.target as HTMLElement).closest('input, button, a, [role="menu"]')) return
    navigate(`/executions/report/${exec.id}`)
  }

  const totalItems = data?.totalElements ?? 0
  const totalPages = Math.max(1, data?.totalPages ?? 1)
  const startIndex = totalItems === 0 ? 0 : (urlPage - 1) * urlSize + 1
  const endIndex = Math.min((urlPage - 1) * urlSize + rows.length, totalItems)

  return (
    <div className="pt-content ex-page rp-page">
      <div className="pt-page-header ex-header">
        <div className="page-title">
          <h1>Rapports</h1>
          <p>Consultez et partagez les résultats des tests terminés</p>
        </div>
      </div>

      {loadError && (
        <div className="pt-alert-banner danger mb-3" role="alert">
          <i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>
          Impossible de charger les rapports : {loadError}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => loadPage()}>Réessayer</button>
        </div>
      )}

      <div className="ex-filters" role="search">
        <div className="pt-search ex-filter-search">
          <i className="bi bi-search" aria-hidden="true"></i>
          <input type="text" placeholder="Rechercher (scénario, application, id)…" value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)} aria-label="Rechercher un rapport" />
        </div>
        <div className="ex-filter-scenario">
          <SearchSelect options={scenarioOptions} value={urlScenario} onChange={(v) => updateParams({ scenario: v || null })}
            allLabel="Tous les scénarios" ariaLabel="Filtrer par scénario" width="100%" />
        </div>
        <select className="pt-form-control ex-filter-select" value={urlApp} aria-label="Filtrer par application"
          onChange={(e) => {
            const app = e.target.value
            const keepScenario = !app || !urlScenario || scenarioById.get(urlScenario)?.applicationId === app
            updateParams({ app: app || null, ...(keepScenario ? {} : { scenario: null }) })
          }}>
          <option value="">Toutes les applications</option>
          {applications.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
        <select className="pt-form-control ex-filter-select" value={urlResult} aria-label="Filtrer par résultat"
          onChange={(e) => updateParams({ result: e.target.value || null })}>
          <option value="">Tous les résultats</option>
          {RESULTS.map((r) => {
            const n = resultCount(r)
            return <option key={r} value={r}>{statusLabel(r)}{n != null && !urlResult ? ` (${n})` : ''}</option>
          })}
        </select>
        <select className="pt-form-control ex-filter-select" value={urlPeriod} aria-label="Période"
          onChange={(e) => {
            const period = e.target.value as Period
            updateParams(period === 'custom' ? { period: 'custom' } : { period: period || null, from: null, to: null })
          }}>
          {REPORT_PERIODS.map((p) => <option key={p.value || 'all'} value={p.value}>{p.label}</option>)}
        </select>
        {urlPeriod === 'custom' && (
          <span className="ex-custom-dates" role="group" aria-label="Période personnalisée">
            <FrDateInput value={urlFrom} onChange={(v) => updateParams({ from: v || null })} label="Lancés à partir du" />
            <span className="ex-muted">au</span>
            <FrDateInput value={urlTo} onChange={(v) => updateParams({ to: v || null })} label="Lancés jusqu’au" />
          </span>
        )}
        {hasFilters && (
          <button type="button" className="pt-btn-ghost ex-reset" onClick={resetFilters}>
            <i className="bi bi-x-circle" aria-hidden="true"></i> Réinitialiser
          </button>
        )}
      </div>

      <div className="pt-card ex-card">
        {selected.size > 0 && (
          <div className="ap-bulk-bar ex-bulk" role="region" aria-label="Actions sur la sélection">
            <strong>{selected.size} rapport{selected.size > 1 ? 's' : ''} sélectionné{selected.size > 1 ? 's' : ''}</strong>
            <div className="d-flex gap-2 flex-wrap">
              <button type="button" className="pt-btn-primary" disabled={!!compareBlocked} title={compareBlocked ?? 'Comparer ces rapports côte à côte'}
                onClick={() => navigate(`/executions/compare?ids=${[...selected].join(',')}`)}>
                <i className="bi bi-layout-three-columns" aria-hidden="true"></i> Comparer
              </button>
              <button type="button" className="pt-btn-ghost" onClick={() => setSelected(new Set())}>Annuler la sélection</button>
            </div>
          </div>
        )}

        <div className="ex-table-wrap">
          <table className="ex-table rp-table">
            <thead>
              <tr>
                <th className="ex-col-check">
                  <input ref={selectAllRef} type="checkbox" className="form-check-input" checked={allSelected} onChange={toggleAll}
                    aria-label="Sélectionner tous les rapports de la page" disabled={rows.length === 0} />
                </th>
                <th>Scénario</th>
                <th>Résultat</th>
                <th className="num" title="Utilisateurs virtuels">VU</th>
                <th className="num" title="95 % des requêtes ont répondu en moins de ce temps">P95</th>
                <th className="num">Taux d’erreurs</th>
                <th className="num" title="Requêtes par seconde">Débit</th>
                <th className={`ex-th-sort sorted`} aria-sort={urlSortAsc ? 'ascending' : 'descending'}>
                  <button type="button" className="ex-sort" onClick={() => updateParams({ sort: urlSortAsc ? null : 'startedAt,asc' })}
                    aria-label={urlSortAsc ? 'Trier par date : plus récents d’abord' : 'Trier par date : plus anciens d’abord'}>
                    Date <i className={`bi ${urlSortAsc ? 'bi-arrow-up' : 'bi-arrow-down'} ex-sort-icon`} aria-hidden="true"></i>
                  </button>
                </th>
                <th className="ex-col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading && !data ? (
                Array.from({ length: Math.min(urlSize, 6) }).map((_, i) => (
                  <tr key={`sk-${i}`} className="ex-skeleton-row" aria-hidden="true"><td colSpan={9}><div className="ex-skeleton"></div></td></tr>
                ))
              ) : rows.length === 0 ? (
                <tr className="ex-empty-row">
                  <td colSpan={9}>
                    <div className="ex-empty" role="status">
                      <i className="bi bi-file-earmark-bar-graph" aria-hidden="true"></i>
                      <p>{hasFilters ? 'Aucun rapport ne correspond à ces filtres.' : 'Aucun rapport pour l’instant : un rapport apparaît ici à la fin de chaque exécution.'}</p>
                      {hasFilters && <button type="button" className="pt-btn-outline" onClick={resetFilters}>Réinitialiser les filtres</button>}
                    </div>
                  </td>
                </tr>
              ) : rows.map((exec) => {
                const req = requestsOf(exec)
                return (
                  <tr key={exec.id} className={`ex-row ${selected.has(exec.id) ? 'selected' : ''}`} onClick={(e) => onRowClick(e, exec)} data-testid="report-row">
                    <td className="ex-col-check">
                      <input type="checkbox" className="form-check-input" checked={selected.has(exec.id)} onChange={() => toggleOne(exec.id)}
                        aria-label={`Sélectionner le rapport de ${exec.scenarioName} du ${fullDate(exec.startedAt)}`} />
                    </td>
                    <td data-label="Scénario" className="ex-scenario">
                      <span className="ex-scenario-name" title={exec.scenarioName}>{exec.scenarioName}</span>
                      <span className="ex-scenario-app">
                        {exec.applicationName}
                        {exec.keepResults && <span className="ex-retention" title="Conservé : résultats détaillés jamais supprimés">
                          <i className="bi bi-bookmark-check-fill" aria-hidden="true"></i> Conservé</span>}
                        {exec.resultsPurgedAt && <span className="ex-retention muted" title={`Détail des requêtes supprimé le ${fullDate(exec.resultsPurgedAt)} ; résumé conservé`}>
                          <i className="bi bi-archive" aria-hidden="true"></i> Résumé seul</span>}
                      </span>
                    </td>
                    <td data-label="Résultat" className="ex-status">
                      <span className={`pt-pill ${statusPill(exec.status)} ex-pill`}><span className="ex-dot" aria-hidden="true"></span>{statusLabel(exec.status)}</span>
                      {exec.statusReason && <span className="ex-status-reason" title={exec.statusReason}>{exec.statusReason}</span>}
                    </td>
                    <td data-label="VU" className="num">{exec.virtualUsers}</td>
                    <td data-label="P95" className="num">{formatMs(exec.p95Ms ?? null)}</td>
                    <td data-label="Taux d’erreurs" className="num">
                      {req.errorRate == null ? <span className="ex-muted">—</span>
                        : <span className={`rp-rate ${req.failed > 0 ? 'bad' : ''}`} title={`${req.failed} requête(s) en échec sur ${req.total}`}>
                          {`${req.errorRate.toLocaleString('fr-FR', { maximumFractionDigits: 2 })} %`}</span>}
                    </td>
                    <td data-label="Débit" className="num">{exec.throughput == null ? '—' : formatRps(exec.throughput)}</td>
                    <td data-label="Date" className="ex-started" title={`${fullDate(exec.startedAt)}${exec.triggeredByUsername ? ` — lancé par ${exec.triggeredByUsername}` : ''}`}>
                      {relativeTime(exec.startedAt)}
                    </td>
                    <td className="ex-col-actions">
                      <div className="ex-actions">
                        <button type="button" className="ex-icon-btn" title="Voir le rapport" aria-label={`Voir le rapport de ${exec.scenarioName}`}
                          onClick={() => navigate(`/executions/report/${exec.id}`)}>
                          <i className="bi bi-file-earmark-bar-graph" aria-hidden="true"></i>
                        </button>
                        <button type="button" className="ex-icon-btn rp-excel" title="Exporter Excel" aria-label={`Exporter Excel le rapport de ${exec.scenarioName}`}
                          onClick={() => download(exec, 'xlsx')} disabled={busy(exec, 'xlsx')} aria-busy={busy(exec, 'xlsx') || undefined}>
                          <i className={`bi ${busy(exec, 'xlsx') ? 'bi-arrow-repeat pt-spin' : 'bi-file-earmark-excel'}`} aria-hidden="true"></i>
                        </button>
                        <button type="button" className="ex-icon-btn" title="Exporter en PDF" aria-label={`Exporter en PDF le rapport de ${exec.scenarioName}`}
                          onClick={() => download(exec, 'pdf')} disabled={busy(exec, 'pdf')} aria-busy={busy(exec, 'pdf') || undefined}>
                          <i className={`bi ${busy(exec, 'pdf') ? 'bi-arrow-repeat pt-spin' : 'bi-file-earmark-pdf'}`} aria-hidden="true"></i>
                        </button>
                        <RowActionsMenu actions={rowActions(exec)} label={`Plus d’actions pour le rapport de ${exec.scenarioName}`} />
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
          itemLabel="rapports"
          pageSize={urlSize}
          pageSizeOptions={REPORT_PAGE_SIZES}
          onPageSizeChange={(size) => updateParams({ size: size === 10 ? null : String(size) })}
        />
      </div>

      {deletion && <ExecutionDeletionDialog ids={deletion} onConfirm={confirmDeletion} onClose={() => setDeletion(null)} />}
    </div>
  )
}

export default Reports
