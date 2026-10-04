import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import {
  BackendApplicationResponse,
  BackendBulkActionResponse,
  BackendExecutionDeletionImpact,
  BackendExecutionDetailResponse,
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
import { ApiError } from '../services/api/httpClient'
import { useApiList } from '../hooks/useApiResource'
import Pagination from '../components/Pagination'
import SearchSelect from '../components/SearchSelect'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { isTerminal, progressOf, useExecutionMonitor } from '../context/ExecutionMonitorContext'
import './applications.css'

// ============================================================
// Page Exécutions : liste filtrée et paginée CÔTÉ SERVEUR
// (GET /api/executions/page : statut, scénario, application, recherche,
// compteurs par statut suivant les mêmes filtres). Filtres, page et
// recherche vivent dans l'URL (rechargement / partage de lien) ; tout
// changement de filtre ramène à la page 1.
//
// Sélection multiple + suppression définitive (simple ou groupée, résultat
// par exécution), relance avec suivi en direct, progression des exécutions
// en cours ou en pause directement dans le tableau.
// ============================================================

const STATUS_TABS = ['', 'QUEUED', 'RUNNING', 'PAUSED', 'SUCCESS', 'FAILED', 'CANCELLED'] as const
type StatusTab = typeof STATUS_TABS[number]
const PAGE_SIZES = [10, 20, 50]

const statusLabel: Record<BackendExecutionStatus, string> = {
  QUEUED: 'En attente',
  RUNNING: 'En cours',
  PAUSED: 'En pause',
  SUCCESS: 'Réussie',
  FAILED: 'Échouée',
  CANCELLED: 'Annulée',
}

const statusPillClass: Record<BackendExecutionStatus, string> = {
  QUEUED: 'neutral',
  RUNNING: 'info',
  PAUSED: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELLED: 'neutral',
}

/** En attente, en cours ou en pause : annulable, jamais supprimable. */
function isActive(status: BackendExecutionStatus): boolean {
  return status === 'QUEUED' || status === 'RUNNING' || status === 'PAUSED'
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 401:
        return 'Vous devez être connecté (Keycloak) pour effectuer cette action.'
      case 403:
        return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      case 404:
        return err.message.startsWith('Erreur 404') ? 'Scénario ou exécution introuvable (déjà supprimé ?).' : err.message
      case 429:
        // Limite de capacité (utilisateurs virtuels globaux / exécutions simultanées) : le backend précise laquelle.
        return `Limite de capacité LoadPilot atteinte : ${err.message}`
      default:
        // 400 (brouillon, scénario inactif…) et 409 (archivé, exécution en cours…) : message clair du backend.
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function formatDate(iso: string) {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

function formatDuration(ms: number | null): string {
  if (ms == null) return '—'
  const totalSec = Math.round(ms / 1000)
  const h = String(Math.floor(totalSec / 3600)).padStart(2, '0')
  const m = String(Math.floor((totalSec % 3600) / 60)).padStart(2, '0')
  const s = String(totalSec % 60).padStart(2, '0')
  return `${h}:${m}:${s}`
}

function plural(n: number, singular: string, pluralForm = `${singular}s`) {
  return `${n} ${n > 1 ? pluralForm : singular}`
}

// ============================================================
// Confirmation de suppression : impact réel (résultats, métriques), définitif.
// ============================================================

function ExecutionDeletionDialog({ ids, onConfirm, onClose }: {
  ids: string[]
  onConfirm: () => Promise<void>
  onClose: () => void
}) {
  const [impact, setImpact] = useState<BackendExecutionDeletionImpact | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    executionsBackendApi.deletionImpact(ids)
      .then((r) => { if (alive) setImpact(r) })
      .catch((err) => { if (alive) setLoadError(describeApiError(err, 'Impact indisponible.')) })
    return () => { alive = false }
  }, [ids])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !busy) onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [busy, onClose])

  const deletable = impact ? impact.executions - impact.active : 0
  const submit = async () => {
    setBusy(true)
    setActionError(null)
    try {
      await onConfirm()
    } catch (err) {
      setActionError(describeApiError(err, 'Suppression impossible.'))
      setBusy(false)
    }
  }

  return (
    <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1060 }}>
      <div className="modal-dialog modal-dialog-centered">
        <div className="modal-content" role="dialog" aria-modal="true" aria-labelledby="ex-del-title"
          style={{ borderRadius: 'var(--pt-radius)', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}>
          <div className="modal-body p-4">
            <h5 id="ex-del-title" style={{ fontWeight: 700 }}>
              <i className="bi bi-trash3 text-danger me-2"></i>
              Supprimer {ids.length > 1 ? `${ids.length} exécutions` : 'cette exécution'} ?
            </h5>
            {!impact && !loadError && <p className="text-muted mb-0"><span className="spinner-border spinner-border-sm me-2"></span>Calcul de l’impact…</p>}
            {loadError && <div className="pt-alert-banner danger mb-0"><i className="bi bi-exclamation-octagon"></i>{loadError}</div>}
            {impact && (
              <>
                <p className="mb-2" style={{ fontSize: '13.5px' }}>Seront supprimés <strong>définitivement</strong> :</p>
                <ul style={{ fontSize: '13.5px' }} aria-label="Impact">
                  <li><strong>{deletable}</strong> {deletable > 1 ? 'exécutions' : 'exécution'}</li>
                  <li><strong>{impact.results}</strong> {impact.results > 1 ? 'résultats de requêtes' : 'résultat de requête'}</li>
                  <li><strong>{impact.metrics}</strong> {impact.metrics > 1 ? 'métriques' : 'métrique'}</li>
                </ul>
                {impact.active > 0 && (
                  <div className="pt-alert-banner warning mb-2" role="alert" style={{ fontSize: '12.5px' }}>
                    <i className="bi bi-hourglass-split"></i>
                    {plural(impact.active, 'exécution est', 'exécutions sont')} en attente, en cours ou en pause : {impact.active > 1 ? 'elles ne seront pas supprimées' : 'elle ne sera pas supprimée'} (annulez-la d’abord).
                  </div>
                )}
                <p className="mb-0" style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
                  Cette action est irréversible : rapports et détails de ces exécutions ne seront plus consultables.
                </p>
              </>
            )}
            {actionError && <div className="pt-alert-banner danger mt-2 mb-0"><i className="bi bi-exclamation-octagon"></i>{actionError}</div>}
          </div>
          <div className="modal-footer">
            <button type="button" className="pt-btn-ghost" onClick={onClose} disabled={busy}>Annuler</button>
            <button type="button" className="pt-btn-danger" onClick={submit} disabled={busy || !impact || deletable === 0}>
              {busy ? <span className="spinner-border spinner-border-sm me-1"></span> : <i className="bi bi-trash3"></i>} Supprimer définitivement
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

// ============================================================
// Vue Spring Boot
// ============================================================

function SpringExecutions() {
  const navigate = useNavigate()
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const monitor = useExecutionMonitor()
  const [searchParams, setSearchParams] = useSearchParams()

  const isKeycloak = authProvider === 'keycloak'
  // Lancement / annulation / relance / suppression : SUPER_ADMIN et PERFORMANCE_ENGINEER (voir ExecutionController).
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  // ---------------------------------------------------------------- filtres dans l'URL
  const urlStatus = (STATUS_TABS as readonly string[]).includes(searchParams.get('status') ?? '') ? (searchParams.get('status') as StatusTab) : ''
  const urlScenario = searchParams.get('scenario') ?? ''
  const urlApp = searchParams.get('app') ?? ''
  const urlQuery = searchParams.get('q') ?? ''
  const urlPage = Math.max(1, Number(searchParams.get('page')) || 1)
  const urlSize = PAGE_SIZES.includes(Number(searchParams.get('size'))) ? Number(searchParams.get('size')) : 10
  const hasFilters = !!(urlStatus || urlScenario || urlApp || urlQuery)

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
        status: urlStatus || undefined, scenarioId: urlScenario || undefined, applicationId: urlApp || undefined,
        search: urlQuery || undefined, page: urlPage - 1, size: urlSize,
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
  }, [urlStatus, urlScenario, urlApp, urlQuery, urlPage, urlSize, updateParams])
  useEffect(() => { loadPage() }, [loadPage])

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
  useEffect(() => { setSelected(new Set()) }, [urlStatus, urlScenario, urlApp, urlQuery, urlPage, urlSize])
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

  // ---------------------------------------------------------------- actions
  const [deletion, setDeletion] = useState<string[] | null>(null)
  const [retryConfirm, setRetryConfirm] = useState<BackendExecutionHistoryResponse | null>(null)
  const [retrying, setRetrying] = useState(false)
  const [cancellingId, setCancellingId] = useState<string | null>(null)

  const reportBulk = (result: BackendBulkActionResponse) => {
    if (result.failed === 0) {
      showToast(`${plural(result.succeeded, 'exécution supprimée', 'exécutions supprimées')} définitivement.`, 'success')
      return
    }
    const failures = result.results.filter((r) => !r.success).map((r) => `${r.name ? r.name.split(' (')[0] : 'Exécution'} : ${r.message}`).join(' ; ')
    showToast(`${plural(result.succeeded, 'exécution supprimée', 'exécutions supprimées')}, ${result.failed} non supprimée(s) — ${failures}`,
      result.succeeded > 0 ? 'warning' : 'danger')
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

  // ---------------------------------------------------------------- détail (modale, inchangée)
  const [selectedExecutionDetail, setSelectedExecutionDetail] = useState<BackendExecutionDetailResponse | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  useEffect(() => {
    if (!selectedExecutionDetail || !isActive(selectedExecutionDetail.status)) return
    const interval = setInterval(() => {
      executionsBackendApi.getById(selectedExecutionDetail.id).then(setSelectedExecutionDetail).catch(() => {})
    }, 2000)
    return () => clearInterval(interval)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedExecutionDetail?.id, selectedExecutionDetail?.status])
  const openDetail = (id: string) => {
    setDetailLoading(true)
    setSelectedExecutionDetail(null)
    executionsBackendApi.getById(id)
      .then(setSelectedExecutionDetail)
      .catch((err) => showToast(describeApiError(err, "Impossible de charger le détail de l'exécution."), 'danger'))
      .finally(() => setDetailLoading(false))
  }

  const counts = data?.counts
  const totalItems = data?.totalElements ?? 0
  const totalPages = Math.max(1, data?.totalPages ?? 1)
  const startIndex = totalItems === 0 ? 0 : (urlPage - 1) * urlSize + 1
  const endIndex = Math.min((urlPage - 1) * urlSize + rows.length, totalItems)
  const colCount = canWrite ? 8 : 7

  return (
    <>
      {loadError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger les exécutions : {loadError}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => loadPage()}>Réessayer</button>
        </div>
      )}

      {/* Cartes : suivent les filtres appliqués. */}
      <div className="row g-3 mb-4">
        {[
          { label: hasFilters ? 'Total (filtré)' : 'Total exécutions', value: counts?.total, icon: 'bi-play-circle', color: 'blue' },
          { label: 'Réussies', value: counts?.success, icon: 'bi-check-circle', color: 'green' },
          { label: 'Échouées', value: counts?.failed, icon: 'bi-x-circle', color: 'red' },
          { label: 'Annulées', value: counts?.cancelled, icon: 'bi-slash-circle', color: 'orange' },
        ].map((c) => (
          <div key={c.label} className="col-6 col-xl-3">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">{c.label}</div>
                  <div className="stat-value">{c.value ?? '—'}</div>
                </div>
                <div className={`stat-icon ${c.color}`}><i className={`bi ${c.icon}`}></i></div>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Filtres */}
      <div className="d-flex align-items-center gap-2 flex-wrap mb-3">
        <div className="pt-search" style={{ width: '240px', maxWidth: '100%' }}>
          <i className="bi bi-search"></i>
          <input type="text" placeholder="Rechercher (scénario, application, id)…" value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)} aria-label="Rechercher une exécution" />
        </div>
        <SearchSelect options={scenarioOptions} value={urlScenario} onChange={(v) => updateParams({ scenario: v || null })}
          allLabel="Tous les scénarios" ariaLabel="Filtrer par scénario" />
        <select className="pt-form-control" style={{ width: 'auto', maxWidth: '100%' }} value={urlApp} aria-label="Filtrer par application"
          onChange={(e) => {
            const app = e.target.value
            // Le scénario choisi n'appartient plus à l'application : filtre scénario retiré.
            const keepScenario = !app || !urlScenario || scenarioById.get(urlScenario)?.applicationId === app
            updateParams({ app: app || null, ...(keepScenario ? {} : { scenario: null }) })
          }}>
          <option value="">Toutes les applications</option>
          {applications.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
        {hasFilters && (
          <button type="button" className="pt-btn-ghost" onClick={resetFilters}>
            <i className="bi bi-x-circle"></i> Réinitialiser les filtres
          </button>
        )}
        {canWrite && (
          <button className="pt-btn-primary ms-auto" onClick={openLaunchModal}>
            <i className="bi bi-play-fill fs-6"></i> Nouvelle exécution
          </button>
        )}
      </div>

      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex gap-2 flex-wrap p-3" style={{ borderBottom: '1px solid var(--pt-border)' }} role="group" aria-label="Filtrer par statut">
          {STATUS_TABS.map((tab) => {
            const n = !counts ? null : tab === '' ? counts.total
              : { QUEUED: counts.queued, RUNNING: counts.running, PAUSED: counts.paused, SUCCESS: counts.success, FAILED: counts.failed, CANCELLED: counts.cancelled }[tab]
            return (
              <button key={tab || 'all'} type="button" aria-pressed={urlStatus === tab}
                className={`btn btn-sm ${urlStatus === tab ? 'btn-primary' : 'btn-outline-secondary'}`}
                onClick={() => updateParams({ status: tab || null })}
                style={{ borderRadius: 'var(--pt-radius-sm)', fontWeight: 600, fontSize: '13px' }}>
                {tab === '' ? 'Toutes' : statusLabel[tab]}{n != null && !urlStatus ? ` (${n})` : ''}
              </button>
            )
          })}
        </div>

        {canWrite && selected.size > 0 && (
          <div className="ap-bulk-bar" role="region" aria-label="Actions sur la sélection">
            <strong>{selected.size} sélectionnée{selected.size > 1 ? 's' : ''}</strong>
            <div className="d-flex gap-2 flex-wrap">
              <button type="button" className="pt-btn-danger" onClick={() => setDeletion([...selected])}>
                <i className="bi bi-trash3"></i> Supprimer
              </button>
              <button type="button" className="pt-btn-ghost" onClick={() => setSelected(new Set())}>Annuler la sélection</button>
            </div>
          </div>
        )}

        {loading && !data ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement des exécutions...</p>
          </div>
        ) : (
          <div className="pt-table-wrapper">
            <table className="pt-table">
              <thead>
                <tr>
                  {canWrite && (
                    <th style={{ width: '40px' }}>
                      <input ref={selectAllRef} type="checkbox" checked={allSelected} onChange={toggleAll} aria-label="Sélectionner toutes les exécutions de la page" />
                    </th>
                  )}
                  <th>Scénario</th>
                  <th>Application</th>
                  <th>Statut</th>
                  <th>Requêtes</th>
                  <th>Durée</th>
                  <th>Démarré le</th>
                  <th style={{ textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 ? (
                  <tr>
                    <td colSpan={colCount} style={{ textAlign: 'center', padding: '2rem', color: 'var(--pt-text-muted)' }}>
                      <i className="bi bi-inbox" style={{ fontSize: '28px' }}></i>
                      <p className="mb-0 mt-1">{hasFilters ? 'Aucune exécution ne correspond à ces filtres.' : 'Aucune exécution pour l’instant. Lancez votre premier test !'}</p>
                    </td>
                  </tr>
                ) : rows.map((exec) => {
                  const active = isActive(exec.status)
                  const live = liveStatus[exec.id]
                  const progress = active && live && !isTerminal(live) ? progressOf(live) : null
                  return (
                    <tr key={exec.id} style={{ background: selected.has(exec.id) ? 'var(--pt-sidebar-item-hover)' : undefined }}>
                      {canWrite && (
                        <td>
                          <input type="checkbox" checked={selected.has(exec.id)} onChange={() => toggleOne(exec.id)}
                            aria-label={`Sélectionner l’exécution de ${exec.scenarioName} du ${formatDate(exec.startedAt)}`} />
                        </td>
                      )}
                      <td>
                        <button onClick={() => updateParams({ scenario: exec.scenarioId })} title="Filtrer sur ce scénario"
                          style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '13.5px', fontWeight: 500, color: 'var(--pt-text)', textAlign: 'left' }}>
                          {exec.scenarioName}
                        </button>
                      </td>
                      <td><span style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>{exec.applicationName}</span></td>
                      <td style={{ minWidth: '150px' }}>
                        <span className={`pt-pill ${statusPillClass[exec.status]}`}>
                          <span style={{ width: '6px', height: '6px', borderRadius: '50%', display: 'inline-block', marginRight: '5px', background: 'currentColor' }}></span>
                          {statusLabel[(live?.status ?? exec.status) as BackendExecutionStatus]}
                        </span>
                        {active && (
                          <div className="mt-1" aria-label={progress ? `Progression : ${progress.label}` : 'Progression'}>
                            <div className="em-progress" style={{ marginTop: '0.3rem', height: '6px' }}>
                              <div className={`em-progress-bar ${progress?.percent == null ? 'indeterminate' : ''} ${(live?.status ?? exec.status) === 'PAUSED' ? 'paused' : ''}`}
                                style={{ width: `${progress?.percent ?? 30}%` }}></div>
                            </div>
                            <div className="d-flex justify-content-between gap-2" style={{ fontSize: '11px', color: 'var(--pt-text-muted)' }}>
                              <span>{progress ? `${progress.percent ?? '—'} %` : '…'}</span>
                              <Link to={`/executions/live/${exec.id}`} onClick={() => monitor.track(exec.id, exec.scenarioName)}>Suivre</Link>
                            </div>
                          </div>
                        )}
                      </td>
                      <td>
                        <span style={{ fontSize: '12.5px' }}>
                          <span style={{ color: 'var(--pt-success)', fontWeight: 600 }} title="Réussies">{active && live ? live.successfulRequests : exec.successfulSteps}</span>
                          {' / '}
                          <span style={{ color: 'var(--pt-danger)', fontWeight: 600 }} title="En échec">{active && live ? live.failedRequests : exec.failedSteps}</span>
                        </span>
                      </td>
                      <td><span style={{ fontSize: '13px', fontFamily: 'monospace' }}>{formatDuration(exec.duration)}</span></td>
                      <td><span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{formatDate(exec.startedAt)}</span></td>
                      <td>
                        <div className="d-flex justify-content-end gap-1">
                          <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Voir les détails" aria-label="Voir les détails" onClick={() => openDetail(exec.id)}>
                            <i className="bi bi-eye" style={{ fontSize: '14px' }}></i>
                          </button>
                          <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Voir le rapport" aria-label="Voir le rapport" onClick={() => navigate(`/executions/report/${exec.id}`)}>
                            <i className="bi bi-file-text" style={{ fontSize: '14px' }}></i>
                          </button>
                          {canWrite && active && (
                            <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-danger)', color: 'var(--pt-danger)' }}
                              title="Annuler cette exécution" aria-label="Annuler cette exécution" disabled={cancellingId === exec.id} onClick={() => handleCancel(exec)}>
                              <i className={`bi ${cancellingId === exec.id ? 'bi-arrow-repeat pt-spin' : 'bi-slash-circle'}`} style={{ fontSize: '14px' }}></i>
                            </button>
                          )}
                          {canWrite && (
                            <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-primary)', color: 'var(--pt-primary)' }}
                              title="Relancer (crée une nouvelle exécution)" aria-label="Relancer" onClick={() => setRetryConfirm(exec)}>
                              <i className="bi bi-arrow-repeat" style={{ fontSize: '14px' }}></i>
                            </button>
                          )}
                          {canWrite && (
                            <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)', color: active ? 'var(--pt-text-light)' : 'var(--pt-danger)' }}
                              title={active ? 'Impossible de supprimer une exécution en attente, en cours ou en pause : annulez-la d’abord.' : 'Supprimer définitivement'}
                              aria-label="Supprimer" disabled={active} onClick={() => setDeletion([exec.id])}>
                              <i className="bi bi-trash3" style={{ fontSize: '14px' }}></i>
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}

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

      {/* Modale Détail Execution */}
      {(detailLoading || selectedExecutionDetail) && (
        <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1050 }}>
          <div className="modal-dialog modal-dialog-centered modal-lg">
            <div className="modal-content" style={{ borderRadius: 'var(--pt-radius)', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}>
              <div className="modal-header">
                <h5 className="modal-title d-flex align-items-center gap-2" style={{ fontSize: '16px', fontWeight: 600 }}>
                  <i className="bi bi-play-circle text-primary"></i>
                  Détail de l'exécution {selectedExecutionDetail ? `— ${selectedExecutionDetail.scenarioName}` : ''}
                </h5>
                <div className="d-flex align-items-center gap-2">
                  {selectedExecutionDetail && canWrite && isActive(selectedExecutionDetail.status) && (
                    <button className="pt-btn-outline" style={{ fontSize: '12px', padding: '0.3rem 0.7rem', color: 'var(--pt-danger)', borderColor: 'var(--pt-danger)' }}
                      disabled={cancellingId === selectedExecutionDetail.id} onClick={() => handleCancel(selectedExecutionDetail)}>
                      <i className={`bi ${cancellingId === selectedExecutionDetail.id ? 'bi-arrow-repeat pt-spin' : 'bi-slash-circle'} me-1`}></i>
                      Annuler
                    </button>
                  )}
                  <button type="button" className="btn-close" onClick={() => setSelectedExecutionDetail(null)} aria-label="Fermer"></button>
                </div>
              </div>
              <div className="modal-body p-4">
                {detailLoading ? (
                  <div className="text-center text-muted py-4"><i className="bi bi-arrow-repeat pt-spin me-1"></i>Chargement...</div>
                ) : selectedExecutionDetail && (
                  <>
                    <div className="row g-3 mb-3">
                      <div className="col-6 col-md-3">
                        <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Statut</div>
                        <span className={`pt-pill ${statusPillClass[selectedExecutionDetail.status]}`}>{statusLabel[selectedExecutionDetail.status]}</span>
                      </div>
                      <div className="col-6 col-md-3">
                        <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Durée</div>
                        <div style={{ fontSize: '13.5px', fontWeight: 600 }}>{formatDuration(selectedExecutionDetail.duration)}</div>
                      </div>
                      <div className="col-6 col-md-3">
                        <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Démarrée le</div>
                        <div style={{ fontSize: '13.5px', fontWeight: 600 }}>{formatDate(selectedExecutionDetail.startedAt)}</div>
                      </div>
                      <div className="col-6 col-md-3">
                        <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Terminée le</div>
                        <div style={{ fontSize: '13.5px', fontWeight: 600 }}>{selectedExecutionDetail.finishedAt ? formatDate(selectedExecutionDetail.finishedAt) : '—'}</div>
                      </div>
                    </div>
                    <div className="d-flex flex-wrap gap-2 mb-3">
                      <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-people me-1"></i>{selectedExecutionDetail.virtualUsers} VU</span>
                      <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-graph-up-arrow me-1"></i>Ramp-up {selectedExecutionDetail.rampUpSeconds}s</span>
                      {selectedExecutionDetail.durationSeconds != null && (
                        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-stopwatch me-1"></i>Durée {selectedExecutionDetail.durationSeconds}s</span>
                      )}
                      {selectedExecutionDetail.iterations != null && (
                        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-arrow-repeat me-1"></i>{selectedExecutionDetail.iterations} itération(s)</span>
                      )}
                    </div>
                    {selectedExecutionDetail.errorMessage && (
                      <div className="pt-alert-banner danger mb-3">
                        <i className="bi bi-exclamation-triangle-fill"></i>
                        {selectedExecutionDetail.errorMessage}
                      </div>
                    )}
                    <h6 style={{ fontSize: '14px', fontWeight: 600 }}>
                      Résultats par étape ({selectedExecutionDetail.results.length}
                      {selectedExecutionDetail.totalSteps > selectedExecutionDetail.results.length
                        ? ` sur ${selectedExecutionDetail.totalSteps} — arrêt à la première étape en échec`
                        : ''})
                    </h6>
                    <div className="pt-table-wrapper" style={{ maxHeight: '320px', overflowY: 'auto' }}>
                      <table className="pt-table" style={{ fontSize: '12.5px' }}>
                        <thead>
                          <tr><th>#</th><th>Étape</th><th>HTTP</th><th>Temps</th><th>Résultat</th></tr>
                        </thead>
                        <tbody>
                          {selectedExecutionDetail.results.map((r, idx) => (
                            <tr key={idx}>
                              <td>{idx + 1}</td>
                              <td>
                                <span style={{ padding: '0.1rem 0.4rem', borderRadius: '4px', fontSize: '10.5px', fontWeight: 700, background: 'var(--pt-primary-light)', color: 'var(--pt-primary)' }}>{r.method}</span>
                                {' '}<span style={{ fontWeight: 600 }}>{r.stepName}</span>{' '}
                                <code style={{ fontSize: '11px', color: 'var(--pt-text-muted)' }}>{r.url}</code>
                              </td>
                              <td>{r.httpStatus ?? '—'}</td>
                              <td>{r.responseTime} ms</td>
                              <td>
                                {r.success ? (
                                  <span style={{ color: 'var(--pt-success)', fontWeight: 600 }}><i className="bi bi-check-lg me-1"></i>Réussi</span>
                                ) : (
                                  <span style={{ color: 'var(--pt-danger)', fontWeight: 600 }} title={r.error ?? undefined}><i className="bi bi-x-lg me-1"></i>Échec{r.error ? ` — ${r.error}` : ''}</span>
                                )}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </>
                )}
              </div>
              <div className="modal-footer d-flex justify-content-end">
                <button className="pt-btn-outline" onClick={() => setSelectedExecutionDetail(null)}>Fermer</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

// ============================================================
// Page hôte (recherche de la barre du haut retirée : seule la recherche
// au-dessus du tableau filtre les exécutions).
// ============================================================

function Executions() {
  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Exécutions</h1>
          <p>Lancez et suivez vos exécutions de tests de charge</p>
        </div>
      </div>

      <SpringExecutions />
    </div>
  )
}

export default Executions
