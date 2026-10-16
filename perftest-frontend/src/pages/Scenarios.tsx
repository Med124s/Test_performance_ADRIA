import { formatDate } from '../utils/dateFormat'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import {
  BackendScenarioRequest,
  BackendScenarioResponse,
  BackendApplicationResponse,
  BackendStepRequest,
  BackendStepResponse,
  BackendHttpMethod,
  BackendExecutionResponse,
  BackendBulkActionResponse,
  BackendScenarioStatus,
  BackendCsvDistribution,
} from '../types/backendContracts'
import './applications.css'
import StepDrawer from './scenario-wizard/StepDrawer'
import { analyzeCsv, validateCsv } from './scenario-wizard/csvModel'
import { VariableRow, validateScenarioVariables, variablesFromJson, variablesToJson } from './scenario-wizard/variablesModel'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { stepsBackendApi } from '../services/api/stepsBackend'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { ApiError } from '../services/api/httpClient'
import { useApiList } from '../hooks/useApiResource'
import DeletionDialog, { DeletionMode } from '../components/deletion/DeletionDialog'
import Pagination from '../components/Pagination'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { useExecutionMonitor } from '../context/ExecutionMonitorContext'
import { statusLabel } from '../utils/executionStatus'
import { firstError, validateRequired, validateMaxLength, validateStepUrl, validatePairedFields, NAME_MAX_LENGTH, DESCRIPTION_MAX_LENGTH } from '../utils/validation'

// ============================================================
// Phase 18/19/20 — cette page parle au vrai backend Spring Boot pour les
// Scénarios (scenariosBackend.ts), les Steps (stepsBackend.ts) et le
// lancement d'Execution (executionsBackend.ts).
//
// P1-G — l'assistant de création JSON Server (CreateScenario/
// CreateScenarioLanding/CreateStep, "wizard" à 5 écrans) a été retiré : ses
// routes (/scenarios/new, /scenarios/create, /scenarios/create-step)
// redirigent désormais vers /scenarios (voir App.tsx). Cette page est
// maintenant le SEUL point d'entrée réel pour créer/modifier un Scénario et
// ses Steps — elle couvre déjà tout ce que Spring Boot supporte réellement
// (métadonnées, paramètres de charge virtualUsers/rampUpSeconds/
// durationSeconds/iterations/thinkTimeMs, Steps name/method/url/headers/
// body/order/expectedStatus, lancement d'Execution).
//
// P1-Q Étape B (décision produit préalable, voir rapport) — le moteur réel
// supporte désormais, PAR ÉTAPE : une assertion simple ("la réponse
// contient"), un think time/timeout/comportement de redirection spécifiques
// à cette étape (repli sur le réglage global si non renseignés) ; et, au
// niveau du Scénario : des variables ${nom} alimentées par un jeu de
// données CSV (une ligne par utilisateur virtuel, cyclique). "${baseUrl}"
// n'a besoin d'aucune variable dédiée : déjà résolu par UrlResolver côté
// backend. Restent HORS PÉRIMÈTRE (décision produit non tranchée, voir
// rapport P1-Q Étape B) : le pacing/débit cible et les variables capturées
// dynamiquement depuis une réponse précédente (chaînage d'authentification).
//
// services/api/scenarios.ts et services/api/steps.ts (JSON Server) restent
// utilisés tels quels, mais uniquement par les écrans de CONSULTATION de
// données historiques déjà présentes dans db.json (Applications, Dashboard,
// Metriques, l'onglet Legacy d'Executions, ExecutionReport/ExecutionDetail)
// — plus par aucun écran de création (voir rapport P1-G, section 15/16).
//
// P0-A — le moteur backend (HttpClientExecutionEngine, threads virtuels
// Java 21) est un vrai moteur de charge ASYNCHRONE : POST /api/executions
// répond immédiatement (202, statut QUEUED/RUNNING), la charge réelle
// (utilisateurs virtuels, ramp-up, durée/itérations — configurés ci-dessous
// sur le Scénario) s'exécute en arrière-plan. Le bouton "Exécuter" ne
// bloque donc pas jusqu'à la fin réelle du test — voir handleExecuteScenario
// et la page Exécutions (Spring Boot) pour suivre la progression réelle
// jusqu'à un statut terminal.
// ============================================================

/** Traduit une erreur RÉELLE (jamais masquée) en message utilisateur — même
 * politique que Applications.tsx (Phase 17). Le code HTTP prime toujours ;
 * 409/400 réutilisent le message backend tel quel (déjà explicite, ex:
 * "des etapes y sont encore rattachees"). */
const PAGE_SIZES = [5, 10, 25, 50]
const DEFAULT_SORT = 'createdAt,desc'
const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Plus récents' },
  { value: 'createdAt,asc', label: 'Plus anciens' },
]
const STATUS_FILTERS = [
  { value: '', label: 'Tous les statuts' },
  { value: 'ACTIVE', label: 'Actif' },
  { value: 'INACTIVE', label: 'Inactif' },
  { value: 'DRAFT', label: 'Brouillon' },
]
const STATUS_PILL: Record<BackendScenarioStatus, { className: string; icon: string; label: string; title?: string }> = {
  ACTIVE: { className: 'success', icon: 'bi-check-circle-fill', label: 'Actif' },
  INACTIVE: { className: 'neutral', icon: 'bi-pause-circle-fill', label: 'Inactif', title: 'Non exécutable ; ses planifications sont suspendues' },
  DRAFT: { className: 'warning', icon: 'bi-pencil-square', label: 'Brouillon', title: 'Brouillon : à terminer dans l’assistant (ni exécutable ni planifiable)' },
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 400:
        return `Données invalides : ${err.message}`
      case 401:
        return 'Vous devez être connecté pour effectuer cette action.'
      case 403:
        return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      case 404:
        return 'Scénario, étape ou application introuvable (il/elle a peut-être déjà été supprimé(e)).'
      case 409:
        return err.message
      case 429:
        // P0-B — limite de capacite LoadPilot atteinte (voir
        // RunningExecutionRegistry) : message backend deja explicite.
        return `Limite de capacité LoadPilot atteinte : ${err.message}`
      default:
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function Scenarios() {
  const navigate = useNavigate()
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const monitor = useExecutionMonitor()
  const [searchParams, setSearchParams] = useSearchParams()
  const appFilter = searchParams.get('app')
  const queryFilter = searchParams.get('q')
  const initialFilter = appFilter ?? queryFilter ?? ''

  // Le backend Spring Boot exige un JWT réel : en mode "mock" (par défaut),
  // aucune action d'écriture n'est proposée - la liste elle-même échouera
  // en 401 (voir scenariosError ci-dessous), affiché tel quel.
  const isKeycloak = authProvider === 'keycloak'
  // POST/PUT/DELETE /api/scenarios sont réservés à SUPER_ADMIN et
  // PERFORMANCE_ENGINEER côté backend (voir ScenarioController) — même
  // rôle pour les trois actions, contrairement à Applications où DELETE
  // est réservé à SUPER_ADMIN seul.
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))
  const canDelete = canWrite
  // Suppression DÉFINITIVE (cascade) réservée à SUPER_ADMIN ; l'archivage reste ouvert aux rédacteurs.
  const canPermanentDelete = isKeycloak && rawRoles.includes('ROLE_SUPER_ADMIN')

  // ---------------------------------------------------------------- pagination serveur
  // Page, taille, tri, recherche, statut et vue "archivés" vivent dans l'URL :
  // l'état survit au rechargement et se partage par lien (comme Applications).
  const showArchived = searchParams.get('archived') === 'true'
  const urlPage = Math.max(1, Number(searchParams.get('page')) || 1)
  const urlSize = PAGE_SIZES.includes(Number(searchParams.get('size'))) ? Number(searchParams.get('size')) : 10
  const urlSort = SORT_OPTIONS.some((o) => o.value === searchParams.get('sort')) ? (searchParams.get('sort') as string) : DEFAULT_SORT
  const urlStatus = STATUS_FILTERS.some((o) => o.value === searchParams.get('status')) ? (searchParams.get('status') as string) : ''
  const urlQuery = queryFilter ?? appFilter ?? ''
  const updateParams = useCallback((patch: Record<string, string | null>, resetPage = true) =>
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      Object.entries(patch).forEach(([k, v]) => (v === null || v === '' ? next.delete(k) : next.set(k, v)))
      if (resetPage) next.delete('page')
      return next
    }, { replace: true }), [setSearchParams])

  // Recherche avec debounce : l'URL (et donc la requête) suit la saisie après 350 ms.
  const [searchInput, setSearchInput] = useState(initialFilter)
  useEffect(() => {
    if (searchInput === urlQuery) return
    const timer = window.setTimeout(() => updateParams({ q: searchInput.trim() || null, app: null }), 350)
    return () => window.clearTimeout(timer)
  }, [searchInput, urlQuery, updateParams])

  const [pageData, setPageData] = useState<{ content: BackendScenarioResponse[]; totalElements: number; totalPages: number }>(
    { content: [], totalElements: 0, totalPages: 0 })
  const [scenariosLoading, setScenariosLoading] = useState(true)
  const [scenariosError, setScenariosError] = useState<string | null>(null)
  const scenarios = pageData.content

  const loadPage = useCallback(async () => {
    setScenariosLoading(true)
    setScenariosError(null)
    try {
      const result = await scenariosBackendApi.getPage({
        page: urlPage - 1, size: urlSize, sort: urlSort, search: urlQuery, status: urlStatus, archived: showArchived,
      })
      // Page devenue vide (ex. après une suppression) : revenir à la dernière page existante.
      if (result.content.length === 0 && urlPage > 1 && result.totalElements > 0) {
        updateParams({ page: String(Math.max(1, result.totalPages)) }, false)
        return
      }
      setPageData({ content: result.content, totalElements: result.totalElements, totalPages: result.totalPages })
    } catch (err) {
      setScenariosError(err instanceof Error ? err.message : 'Erreur de chargement')
    } finally {
      setScenariosLoading(false)
    }
  }, [urlPage, urlSize, urlSort, urlQuery, urlStatus, showArchived, updateParams])
  useEffect(() => { loadPage() }, [loadPage])

  // Compteurs globaux (toutes pages, hors archivés), recalculés après chaque action.
  const [statsVersion, setStatsVersion] = useState(0)
  const [stats, setStats] = useState<{ total: number; active: number; inactive: number; drafts: number } | null>(null)
  useEffect(() => {
    let alive = true
    const count = (status?: string) => scenariosBackendApi.getPage({ page: 0, size: 5, status }).then((r) => r.totalElements)
    Promise.all([count(), count('ACTIVE'), count('INACTIVE'), count('DRAFT')])
      .then(([total, active, inactive, drafts]) => { if (alive) setStats({ total, active, inactive, drafts }) })
      .catch(() => { if (alive) setStats(null) })
    return () => { alive = false }
  }, [statsVersion])
  const refetchScenarios = async () => {
    setStatsVersion((v) => v + 1)
    await loadPage()
  }

  const { data: applications } = useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const { data: executions, refetch: refetchExecutions } = useApiList<BackendExecutionResponse>(() => executionsBackendApi.getAll())
  // Étapes de TOUS les scénarios Spring Boot (Phase 19) — sert à afficher un
  // aperçu réel sous le nom du scénario dans la liste.
  const { data: allSteps, refetch: refetchAllSteps } = useApiList<BackendStepResponse>(() => stepsBackendApi.getAll())

  /** Brouillon à supprimer (confirmation simple : aucun historique possible pour un brouillon). */
  const [deleteDraftConfirm, setDeleteDraftConfirm] = useState<BackendScenarioResponse | null>(null)
  const [deletingDraft, setDeletingDraft] = useState(false)

  // ---------------------------------------------------------------- sélection multiple
  const [selected, setSelected] = useState<Set<string>>(new Set())
  // Vidée au changement de page, de taille, de tri, de filtre ou de recherche.
  useEffect(() => { setSelected(new Set()) }, [urlPage, urlSize, urlSort, urlQuery, urlStatus, showArchived])
  const pageIds = scenarios.map((s) => s.id)
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
  const [bulkBusy, setBulkBusy] = useState(false)
  /** Suppression/archivage groupé (la suppression unitaire garde `deleteConfirm`). */
  const [bulkDeletion, setBulkDeletion] = useState<string[] | null>(null)
  /** Scénarios réactivés qui ont des planifications suspendues : proposition de les réactiver. */

  // Modale Détail (lecture seule pour le scénario, mais gestion réelle des
  // étapes Spring Boot depuis cette même modale — voir Phase 19)
  const [selectedScenarioDetail, setSelectedScenarioDetail] = useState<BackendScenarioResponse | null>(null)
  const [showStepsInDetail, setShowStepsInDetail] = useState(false)
  const [detailSteps, setDetailSteps] = useState<BackendStepResponse[]>([])
  const [detailStepsLoading, setDetailStepsLoading] = useState(false)

  // Confirmation de suppression Scénario
  const [deleteConfirm, setDeleteConfirm] = useState<BackendScenarioResponse | null>(null)

  // Panneau latéral « Ajouter / Modifier une étape » (même éditeur que l'assistant, voir StepDrawer).
  const [stepDrawer, setStepDrawer] = useState<{ editing: BackendStepResponse | null } | null>(null)
  const [stepActionError, setStepActionError] = useState<string | null>(null)

  // Confirmation de suppression Étape
  const [deleteStepConfirm, setDeleteStepConfirm] = useState<BackendStepResponse | null>(null)
  const [deletingStep, setDeletingStep] = useState(false)

  // Étapes de chaque scénario Spring Boot, triées dans l'ordre réel (champ
  // backend `order`, jamais l'index du tableau côté frontend) — pour
  // l'aperçu affiché sous le nom du scénario dans la liste.
  const stepsByScenario = useMemo(() => {
    const map = new Map<string, BackendStepResponse[]>()
    for (const step of allSteps) {
      const list = map.get(step.scenarioId) ?? []
      list.push(step)
      map.set(step.scenarioId, list)
    }
    map.forEach((list) => list.sort((a, b) => a.order - b.order))
    return map
  }, [allSteps])

  // Dernière exécution réelle par scénario (Spring Boot, Phase 20).
  const latestExecByScenario = useMemo(() => {
    const map = new Map<string, BackendExecutionResponse>()
    for (const exec of executions) {
      const current = map.get(exec.scenarioId)
      if (!current || new Date(exec.startedAt) > new Date(current.startedAt)) {
        map.set(exec.scenarioId, exec)
      }
    }
    return map
  }, [executions])

  const [launchingScenarioId, setLaunchingScenarioId] = useState<string | null>(null)

  // Vérifie réellement que le scénario a des Steps avant de créer une
  // Execution (voir Phase 20, section 25) — le backend refuserait de toute
  // façon (409), mais on évite l'appel réseau inutile et on donne le
  // message exact demandé, sans jamais créer d'Execution inutilement.
  const handleExecuteScenario = async (scenario: BackendScenarioResponse) => {
    if (!canWrite || launchingScenarioId) return
    const stepCount = (stepsByScenario.get(scenario.id) ?? []).length
    if (stepCount === 0) {
      showToast('Ce scénario ne contient aucune étape.', 'danger')
      return
    }
    setLaunchingScenarioId(scenario.id)
    try {
      // P0-A — asynchrone : le backend répond immédiatement (202, statut
      // QUEUED/RUNNING), jamais le résultat final. On ne prétend donc plus
      // que le test est "terminé" ici — voir la page Exécutions (Spring
      // Boot) pour suivre la progression réelle jusqu'à un statut terminal.
      const execution = await executionsBackendApi.execute({ scenarioId: scenario.id })
      monitor.track(execution.id, scenario.name)
      await refetchExecutions()
      showToast(
        `Exécution de « ${scenario.name} » lancée (${scenario.virtualUsers} utilisateur(s) virtuel(s)). Suivez sa progression depuis la page Exécutions.`,
        'success'
      )
    } catch (err) {
      showToast(describeApiError(err, "Erreur lors du lancement de l'exécution."), 'danger')
    } finally {
      setLaunchingScenarioId(null)
    }
  }

  // Suppression réelle contre Spring Boot : le backend refuse lui-même
  // (409) si des Steps y sont encore rattachés (voir ScenarioServiceImpl) —
  // plus de cascade-delete des Steps depuis le frontend (c'était une
  // logique spécifique à JSON Server, qui ne cascade jamais rien).
  const confirmScenarioDeletion = async (mode: DeletionMode) => {
    if (!deleteConfirm) return
    if (mode === 'ARCHIVE') await scenariosBackendApi.archive(deleteConfirm.id)
    else await scenariosBackendApi.remove(deleteConfirm.id)
    setSelected((prev) => { const next = new Set(prev); next.delete(deleteConfirm.id); return next })
    showToast(mode === 'ARCHIVE'
      ? `Scénario « ${deleteConfirm.name} » archivé. Vous pouvez le restaurer depuis « Afficher les archivés ».`
      : `Scénario « ${deleteConfirm.name} » supprimé définitivement.`, 'success')
    setDeleteConfirm(null)
    await refetchScenarios()
  }

  const handleRestoreScenario = async (scenario: BackendScenarioResponse) => {
    try {
      await scenariosBackendApi.restore(scenario.id)
      showToast(`Scénario « ${scenario.name} » restauré.`, 'success')
      await refetchScenarios()
    } catch (err) {
      showToast(describeApiError(err, 'Erreur lors de la restauration.'), 'danger')
    }
  }

  // ---------------------------------------------------------------- actions groupées / statut

  const reportBulk = (result: BackendBulkActionResponse, verb: string) => {
    if (result.failed === 0) {
      showToast(`${result.succeeded} scénario(s) ${verb}.`, 'success')
      return
    }
    const failures = result.results.filter((r) => !r.success).map((r) => `« ${r.name ?? '?'} » : ${r.message}`).join(' ; ')
    showToast(`${result.succeeded} scénario(s) ${verb}, ${result.failed} en échec — ${failures}`, result.succeeded > 0 ? 'warning' : 'danger')
  }

  const afterMutation = async () => {
    setSelected(new Set())
    await refetchScenarios()
  }

  /** L'activation reprend d'office les planifications suspendues (côté serveur) : on l'annonce simplement. */
  const announceResumed = (activatedIds: string[]) => {
    const resumed = scenarios.filter((s) => activatedIds.includes(s.id)).reduce((n, s) => n + s.pausedSchedules, 0)
    if (resumed > 0) {
      showToast(`${resumed} planification(s) suspendue(s) reprennent leur cours (aucun lancement passé n’est rattrapé).`, 'info')
    }
  }

  const changeStatus = async (ids: string[], target: 'ACTIVE' | 'INACTIVE') => {
    setBulkBusy(true)
    try {
      if (ids.length === 1) {
        const scenario = target === 'ACTIVE' ? await scenariosBackendApi.activate(ids[0]) : await scenariosBackendApi.deactivate(ids[0])
        showToast(target === 'ACTIVE'
          ? `Scénario « ${scenario.name} » activé.`
          : `Scénario « ${scenario.name} » désactivé${scenario.pausedSchedules > 0 ? ` : ${scenario.pausedSchedules} planification(s) suspendue(s)` : ''}.`, 'success')
        if (target === 'ACTIVE') announceResumed(ids)
      } else {
        const result = await scenariosBackendApi.bulkStatus(ids, target)
        reportBulk(result, target === 'ACTIVE' ? 'activé(s)' : 'désactivé(s)')
        if (target === 'ACTIVE') announceResumed(result.results.filter((r) => r.success).map((r) => r.id))
      }
      await afterMutation()
    } catch (err) {
      showToast(describeApiError(err, 'Changement de statut impossible.'), 'danger')
    } finally {
      setBulkBusy(false)
    }
  }

  const restoreSelected = async (ids: string[]) => {
    setBulkBusy(true)
    try {
      reportBulk(await scenariosBackendApi.bulkRestore(ids), 'restauré(s)')
      await afterMutation()
    } catch (err) {
      showToast(describeApiError(err, 'Erreur lors de la restauration.'), 'danger')
    } finally {
      setBulkBusy(false)
    }
  }

  const confirmBulkDeletion = async (mode: DeletionMode) => {
    if (!bulkDeletion) return
    const result = mode === 'ARCHIVE'
      ? await scenariosBackendApi.bulkArchive(bulkDeletion)
      : await scenariosBackendApi.bulkDelete(bulkDeletion)
    reportBulk(result, mode === 'ARCHIVE' ? 'archivé(s)' : 'supprimé(s) définitivement')
    setBulkDeletion(null)
    await afterMutation()
  }

  const totalItems = pageData.totalElements
  const totalPages = Math.max(1, pageData.totalPages)
  const page = urlPage
  const pageItems = scenarios
  const startIndex = totalItems === 0 ? 0 : (page - 1) * urlSize + 1
  const endIndex = Math.min((page - 1) * urlSize + scenarios.length, totalItems)
  const setPage = (p: number) => updateParams({ page: p > 1 ? String(p) : null }, false)

  // ------------------------------------------------------------
  // Gestion des Étapes (Spring Boot, Phase 19) — depuis la modale Détail.
  // ------------------------------------------------------------

  const loadDetailSteps = (scenarioId: string) => {
    setDetailStepsLoading(true)
    stepsBackendApi.getByScenario(scenarioId)
      .then(setDetailSteps)
      .catch(() => setDetailSteps([]))
      .finally(() => setDetailStepsLoading(false))
  }

  // Ajout / modification d'une étape : panneau latéral avec l'éditeur de l'assistant (StepDrawer).
  const openAddStep = () => {
    if (!selectedScenarioDetail) return
    loadDetailSteps(selectedScenarioDetail.id)
    setStepDrawer({ editing: null })
  }

  const openEditStep = (step: BackendStepResponse) => setStepDrawer({ editing: step })

  const handleDeleteStep = async () => {
    if (!canDelete || !deleteStepConfirm || !selectedScenarioDetail) return
    setDeletingStep(true)
    setStepActionError(null)
    try {
      await stepsBackendApi.remove(deleteStepConfirm.id)
      loadDetailSteps(selectedScenarioDetail.id)
      await refetchAllSteps()
      showToast(`Étape « ${deleteStepConfirm.name} » supprimée avec succès.`, 'success')
      setDeleteStepConfirm(null)
    } catch (err) {
      const message = describeApiError(err, "Erreur lors de la suppression de l'étape.")
      setStepActionError(message)
      showToast(message, 'danger')
    } finally {
      setDeletingStep(false)
    }
  }

  return (
    <div className="pt-content">
      {/* MODAL: Detail Scénario */}
      {selectedScenarioDetail && (
        <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1050 }}>
          <div className="modal-dialog modal-dialog-centered modal-lg">
            <div className="modal-content" style={{ borderRadius: 'var(--pt-radius)', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}>
              <div className="modal-header">
                <h5 className="modal-title d-flex align-items-center gap-2" style={{ fontSize: '16px', fontWeight: 600 }}>
                  <i className="bi bi-info-circle text-primary"></i>
                  Détails du scénario : {selectedScenarioDetail.name}
                </h5>
                <button type="button" className="btn-close" onClick={() => { setSelectedScenarioDetail(null); setShowStepsInDetail(false) }}></button>
              </div>

              <div className="modal-body p-4 d-flex flex-column gap-3">
                <div className="row g-3">
                  <div className="col-12 col-md-6">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Nom du scénario</div>
                    <div style={{ fontSize: '14px', fontWeight: 600 }}>{selectedScenarioDetail.name}</div>
                  </div>
                  <div className="col-12 col-md-6">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Application liée</div>
                    <div style={{ fontSize: '14px', fontWeight: 600 }}>{selectedScenarioDetail.applicationName}</div>
                  </div>
                  <div className="col-12 col-md-6">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Dernière exécution</div>
                    <div style={{ fontSize: '13.5px', fontWeight: 500 }}>
                      {latestExecByScenario.has(selectedScenarioDetail.id)
                        ? formatDate(latestExecByScenario.get(selectedScenarioDetail.id)!.startedAt)
                        : '—'}
                    </div>
                  </div>
                  <div className="col-12 col-md-6">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Date de création</div>
                    <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{formatDate(selectedScenarioDetail.createdAt)}</div>
                  </div>
                  {selectedScenarioDetail.description && (
                    <div className="col-12">
                      <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Description</div>
                      <div style={{ fontSize: '13px', background: 'var(--pt-bg)', padding: '8px 12px', borderRadius: '6px', marginTop: '4px' }}>
                        {selectedScenarioDetail.description}
                      </div>
                    </div>
                  )}
                  <div className="col-12">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>Profil de charge</div>
                    <div className="d-flex flex-wrap gap-2">
                      <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
                        <i className="bi bi-people me-1"></i>{selectedScenarioDetail.virtualUsers} VU
                      </span>
                      <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
                        <i className="bi bi-graph-up-arrow me-1"></i>Ramp-up {selectedScenarioDetail.rampUpSeconds}s
                      </span>
                      {selectedScenarioDetail.durationSeconds != null && (
                        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
                          <i className="bi bi-stopwatch me-1"></i>Durée {selectedScenarioDetail.durationSeconds}s
                        </span>
                      )}
                      {selectedScenarioDetail.iterations != null && (
                        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
                          <i className="bi bi-arrow-repeat me-1"></i>{selectedScenarioDetail.iterations} itération(s)
                        </span>
                      )}
                      {selectedScenarioDetail.thinkTimeMs > 0 && (
                        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
                          <i className="bi bi-hourglass-split me-1"></i>Think time {selectedScenarioDetail.thinkTimeMs}ms
                        </span>
                      )}
                    </div>
                  </div>
                </div>

                <div className="mt-2">
                  <div className="d-flex justify-content-between align-items-center mb-2 flex-wrap gap-2">
                    <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                      <i className="bi bi-list-check me-1" style={{ color: 'var(--pt-primary)' }}></i>
                      Étapes configurées ({detailSteps.length})
                    </h6>
                    <div className="d-flex gap-2">
                      {canWrite && (
                        <button className="pt-btn-outline" style={{ fontSize: '12px', padding: '0.2rem 0.6rem' }} onClick={openAddStep}>
                          <i className="bi bi-plus-lg"></i> Ajouter une étape
                        </button>
                      )}
                      <button
                        className="pt-btn-outline"
                        style={{ fontSize: '12px', padding: '0.2rem 0.6rem' }}
                        onClick={() => {
                          if (!showStepsInDetail) loadDetailSteps(selectedScenarioDetail.id)
                          setShowStepsInDetail(!showStepsInDetail)
                        }}
                      >
                        <i className={`bi ${showStepsInDetail ? 'bi-chevron-up' : 'bi-eye'}`}></i>
                        {showStepsInDetail ? ' Masquer les étapes' : ' Voir les étapes'}
                      </button>
                    </div>
                  </div>

                  {showStepsInDetail && (
                    <div className="d-flex flex-column gap-2" style={{ maxHeight: '360px', overflowY: 'auto' }}>
                      {detailStepsLoading ? (
                        <div className="text-center text-muted py-2" style={{ fontSize: '13px' }}>
                          <i className="bi bi-arrow-repeat pt-spin me-1"></i>Chargement des étapes...
                        </div>
                      ) : detailSteps.length > 0 ? (
                        detailSteps.map((st) => (
                          <div key={st.id} style={{ border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '10px 12px', background: 'var(--pt-bg)' }}>
                            <div className="d-flex align-items-center gap-2 flex-wrap">
                              <span style={{ fontSize: '11px', fontWeight: 700, color: 'var(--pt-text-muted)' }}>#{st.order}</span>
                              <span style={{ padding: '0.15rem 0.4rem', borderRadius: '4px', fontSize: '11px', fontWeight: 700, background: st.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)', color: st.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)' }}>
                                {st.method}
                              </span>
                              <span style={{ fontWeight: 600, fontSize: '13px' }}>{st.name}</span>
                              <code style={{ fontSize: '11.5px', color: 'var(--pt-primary)' }}>{st.url}</code>
                              {st.expectedStatus != null && (
                                <span className="pt-pill neutral" style={{ fontSize: '10.5px' }}>Attendu: {st.expectedStatus}</span>
                              )}
                              {st.assertionBodyContains?.trim() ? (
                                <span className="pt-pill success" style={{ fontSize: '10.5px' }} title={`La réponse doit contenir : ${st.assertionBodyContains}`}>
                                  <i className="bi bi-check2"></i> Contenu vérifié
                                </span>
                              ) : (
                                <span className="pt-pill warning" style={{ fontSize: '10.5px' }} title="Seul le code HTTP est vérifié.">
                                  <i className="bi bi-shield-exclamation"></i> Sans vérification de contenu
                                </span>
                              )}
                              <div className="d-flex gap-1" style={{ marginLeft: 'auto' }}>
                                {canWrite && (
                                  <button className="topbar-icon" title="Modifier l'étape" style={{ width: '28px', height: '28px' }} onClick={() => openEditStep(st)}>
                                    <i className="bi bi-pencil" style={{ fontSize: '12px' }}></i>
                                  </button>
                                )}
                                {canDelete && (
                                  <button className="topbar-icon" title="Supprimer l'étape" style={{ width: '28px', height: '28px' }} onClick={() => setDeleteStepConfirm(st)}>
                                    <i className="bi bi-trash" style={{ fontSize: '12px', color: 'var(--pt-danger)' }}></i>
                                  </button>
                                )}
                              </div>
                            </div>
                            {(st.headers || st.body) && (
                              <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '6px' }}>
                                {st.headers && <div>Headers : <code>{st.headers}</code></div>}
                                {st.body && <div>Body : <code>{st.body}</code></div>}
                              </div>
                            )}
                          </div>
                        ))
                      ) : (
                        <div className="text-center text-muted py-2" style={{ fontSize: '13px' }}>
                          Aucune étape définie pour ce scénario.
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>

              <div className="modal-footer d-flex justify-content-end gap-2">
                <button className="pt-btn-outline" onClick={() => { setSelectedScenarioDetail(null); setShowStepsInDetail(false) }}>
                  Fermer
                </button>
                {canWrite && !selectedScenarioDetail.archived && !selectedScenarioDetail.applicationArchived && (
                  <button className="pt-btn-primary" onClick={() => navigate(selectedScenarioDetail.status === 'DRAFT'
                    ? `/scenarios/new?draft=${selectedScenarioDetail.id}`
                    : `/scenarios/${selectedScenarioDetail.id}/edit`)}>
                    <i className="bi bi-pencil"></i> {selectedScenarioDetail.status === 'DRAFT' ? 'Reprendre' : 'Modifier le scénario'}
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Page Header */}
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Scénarios</h1>
          <p>Créez, gérez et organisez vos scénarios de test</p>
        </div>
      </div>

      {scenariosError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger les scénarios : {scenariosError}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => refetchScenarios()}>
            Réessayer
          </button>
        </div>
      )}

      {/* Stat Cards */}
      <div className="row g-3 mb-4">
        {[
          { label: 'Total des scénarios', value: stats?.total, icon: 'bi-collection-play', color: 'blue' },
          { label: 'Scénarios actifs', value: stats?.active, icon: 'bi-check-circle', color: 'green' },
          { label: 'Scénarios inactifs', value: stats?.inactive, icon: 'bi-pause-circle', color: 'orange' },
          { label: 'Brouillons', value: stats?.drafts, icon: 'bi-pencil-square', color: 'purple' },
        ].map((card) => (
          <div key={card.label} className="col-6 col-md-3">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">{card.label}</div>
                  <div className="stat-value">{card.value ?? '—'}</div>
                </div>
                <div className={`stat-icon ${card.color}`}><i className={`bi ${card.icon}`}></i></div>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Search + Create */}
      <div className="d-flex align-items-center gap-3 flex-wrap mb-3">
        <div className="pt-search">
          <i className="bi bi-search"></i>
          <input type="text" placeholder="Rechercher (nom, application, description)…" value={searchInput} onChange={(e) => setSearchInput(e.target.value)} aria-label="Rechercher un scénario" />
        </div>
        <select className="pt-form-control" style={{ width: 'auto' }} value={urlStatus} onChange={(e) => updateParams({ status: e.target.value || null })} aria-label="Filtrer par statut">
          {STATUS_FILTERS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <select className="pt-form-control" style={{ width: 'auto' }} value={urlSort} onChange={(e) => updateParams({ sort: e.target.value === DEFAULT_SORT ? null : e.target.value })} aria-label="Trier">
          {SORT_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <label className="d-flex align-items-center gap-2 mb-0" style={{ fontSize: '13px', color: 'var(--pt-text-muted)', cursor: 'pointer' }}>
          <input type="checkbox" checked={showArchived} onChange={(e) => updateParams({ archived: e.target.checked ? 'true' : null })} />
          Afficher les archivés
        </label>
        {canWrite && (
          <button className="pt-btn-primary" onClick={() => navigate('/scenarios/new')}>
            <i className="bi bi-plus-lg"></i>
            Nouveau scénario
          </button>
        )}
      </div>

      {/* Table Card */}
      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
            {showArchived ? 'Scénarios archivés' : 'Liste des scénarios'} ({totalItems})
          </h6>
        </div>

        {canWrite && selected.size > 0 && (
          <div className="ap-bulk-bar" role="region" aria-label="Actions sur la sélection">
            <strong>{selected.size} sélectionné{selected.size > 1 ? 's' : ''}</strong>
            <div className="d-flex gap-2 flex-wrap">
              {!showArchived && (
                <>
                  <button type="button" className="pt-btn-outline" onClick={() => changeStatus([...selected], 'ACTIVE')} disabled={bulkBusy}>
                    <i className="bi bi-play-circle"></i> Activer
                  </button>
                  <button type="button" className="pt-btn-outline" onClick={() => changeStatus([...selected], 'INACTIVE')} disabled={bulkBusy}>
                    <i className="bi bi-pause-circle"></i> Désactiver
                  </button>
                </>
              )}
              {showArchived ? (
                <button type="button" className="pt-btn-outline" onClick={() => restoreSelected([...selected])} disabled={bulkBusy}>
                  <i className="bi bi-arrow-counterclockwise"></i> Restaurer
                </button>
              ) : (
                <button type="button" className="pt-btn-outline" onClick={() => setBulkDeletion([...selected])} disabled={bulkBusy}>
                  <i className="bi bi-archive"></i> Archiver
                </button>
              )}
              {canPermanentDelete && (
                <button type="button" className="pt-btn-danger" onClick={() => setBulkDeletion([...selected])} disabled={bulkBusy}>
                  <i className="bi bi-trash3"></i> Supprimer définitivement
                </button>
              )}
              <button type="button" className="pt-btn-ghost" onClick={() => setSelected(new Set())} disabled={bulkBusy}>
                Annuler la sélection
              </button>
            </div>
          </div>
        )}

        {scenariosLoading && scenarios.length === 0 ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement des scénarios...</p>
          </div>
        ) : (
        <div className="pt-table-wrapper">
          <table className="pt-table">
            <thead>
              <tr>
                {canWrite && (
                  <th style={{ width: '40px' }}>
                    <input ref={selectAllRef} type="checkbox" checked={allSelected} onChange={toggleAll} aria-label="Sélectionner tous les scénarios de la page" />
                  </th>
                )}
                <th>Nom</th>
                <th>Application</th>
                <th>Statut</th>
                <th>Dernière exécution</th>
                <th>Créé le</th>
                <th>Créé par</th>
                <th>Détail exécution</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {scenarios.length === 0 ? (
                <tr>
                  <td colSpan={canWrite ? 9 : 8} style={{ textAlign: 'center', padding: '2rem' }}>
                    <i className="bi bi-inbox" style={{ fontSize: '28px', color: 'var(--pt-text-muted)' }}></i>
                    <p style={{ color: 'var(--pt-text-muted)', marginTop: '0.5rem', margin: 0 }}>Aucun scénario trouvé</p>
                  </td>
                </tr>
              ) : (
                pageItems.map((scenario) => {
                  const isSelected = selected.has(scenario.id)
                  const latestExec = latestExecByScenario.get(scenario.id)
                  const scenarioSteps = stepsByScenario.get(scenario.id) ?? []
                  const stepsPreview =
                    scenarioSteps.length === 0
                      ? 'Aucune étape'
                      : scenarioSteps.map((s) => `${s.order}. ${s.method} ${s.url}`).join('  →  ')
                  const isDraft = scenario.status === 'DRAFT'
                  const isInactive = scenario.status === 'INACTIVE'
                  const visible = !scenario.archived && !scenario.applicationArchived
                  const canResume = canWrite && isDraft && visible
                  const pill = STATUS_PILL[scenario.status]

                  return (
                    <tr key={scenario.id} style={{ background: isSelected ? 'var(--pt-sidebar-item-hover)' : undefined, cursor: 'pointer' }}>
                      {canWrite && (
                        <td onClick={(e) => e.stopPropagation()}>
                          <input type="checkbox" checked={isSelected} onChange={() => toggleOne(scenario.id)} aria-label={`Sélectionner ${scenario.name}`} />
                        </td>
                      )}
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <div className="d-flex align-items-center gap-3">
                          <div style={{ width: '38px', height: '38px', borderRadius: '10px', background: 'var(--pt-primary-light)', color: 'var(--pt-primary)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '17px', flexShrink: 0 }}>
                            <i className="bi bi-collection-play"></i>
                          </div>
                          <div>
                            <span style={{ fontSize: '13.5px', fontWeight: 600, color: 'var(--pt-primary)', textDecoration: 'none', cursor: 'pointer' }}>
                              {scenario.name}
                            </span>
                            <div
                              title={stepsPreview}
                              style={{
                                fontSize: '11.5px',
                                color: 'var(--pt-text-muted)',
                                marginTop: '1px',
                                maxWidth: '320px',
                                overflow: 'hidden',
                                textOverflow: 'ellipsis',
                                whiteSpace: 'nowrap',
                                fontFamily: scenarioSteps.length > 0 ? 'monospace' : undefined,
                              }}
                            >
                              {stepsPreview}
                            </div>
                          </div>
                        </div>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span style={{ fontSize: '13px', fontWeight: 500, color: 'var(--pt-text)' }}>{scenario.applicationName}</span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span className={`pt-pill ${pill.className}`} title={pill.title}>
                          <i className={`bi ${pill.icon}`} style={{ fontSize: '11px' }}></i>
                          {pill.label}
                        </span>
                        {isInactive && scenario.pausedSchedules > 0 && (
                          <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '2px' }}>
                            {scenario.pausedSchedules} planification(s) suspendue(s)
                          </div>
                        )}
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
                          {latestExec ? formatDate(latestExec.startedAt) : '—'}
                        </span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{formatDate(scenario.createdAt)}</span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <div className="d-flex align-items-center gap-2">
                          <div style={{ width: '22px', height: '22px', borderRadius: '50%', background: 'var(--pt-primary-light)', color: 'var(--pt-primary)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '10px', fontWeight: 700, flexShrink: 0 }}>
                            {scenario.createdBy.split(' ').map((p) => p[0]).join('')}
                          </div>
                          <span style={{ fontSize: '12.5px', color: 'var(--pt-text)' }}>{scenario.createdBy}</span>
                        </div>
                      </td>
                      {/* Détail exécution : Spring Boot (Phase 20) — la page Exécutions (déjà
                          migrée) porte sa propre vraie modale de détail. */}
                      <td onClick={(e) => e.stopPropagation()}>
                        {latestExec ? (
                          <button
                            onClick={() => navigate(`/executions?q=${encodeURIComponent(scenario.name)}`)}
                            style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '12.5px', color: 'var(--pt-primary)', fontWeight: 600, textDecoration: 'underline' }}
                            title="Voir cette exécution sur la page Exécutions"
                          >
                            {statusLabel(latestExec.status)} — {formatDate(latestExec.startedAt)}
                          </button>
                        ) : (
                          <span style={{ fontSize: '12px', color: 'var(--pt-text-light)' }}>Aucune exécution</span>
                        )}
                      </td>
                      <td onClick={(e) => e.stopPropagation()}>
                        <div className="d-flex justify-content-end gap-2">
                          <button className="topbar-icon" title="Voir détails" style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }} onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                            <i className="bi bi-eye" style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}></i>
                          </button>
                          {canResume && (
                            <button
                              className="pt-btn-outline"
                              style={{ fontSize: '12px', padding: '0.2rem 0.6rem', height: '30px' }}
                              title={`Reprendre l'assistant à l'étape « ${['Informations', 'Étapes', 'Tester', 'Charge & résumé'][scenario.wizardStep ?? 0] ?? 'Informations'} »`}
                              onClick={() => navigate(`/scenarios/new?draft=${scenario.id}`)}
                            >
                              <i className="bi bi-play-circle"></i> Reprendre
                            </button>
                          )}
                          {canWrite && isDraft && (
                            <button className="topbar-icon" title="Supprimer le brouillon" aria-label={`Supprimer le brouillon ${scenario.name}`} style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-danger)', background: 'rgba(220,38,38,0.06)' }} onClick={() => setDeleteDraftConfirm(scenario)}>
                              <i className="bi bi-trash" style={{ fontSize: '13px', color: 'var(--pt-danger)' }}></i>
                            </button>
                          )}
                          {canWrite && !isDraft && visible && (
                            <button
                              className="topbar-icon"
                              title={isInactive ? 'Activer le scénario' : 'Désactiver le scénario (non exécutable, planifications suspendues)'}
                              aria-label={`${isInactive ? 'Activer' : 'Désactiver'} ${scenario.name}`}
                              disabled={bulkBusy}
                              style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}
                              onClick={() => changeStatus([scenario.id], isInactive ? 'ACTIVE' : 'INACTIVE')}
                            >
                              <i className={`bi ${isInactive ? 'bi-play-circle' : 'bi-pause-circle'}`} style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}></i>
                            </button>
                          )}
                          {canWrite && !isDraft && visible && (
                            <span title={isInactive ? 'Scénario inactif : activez-le pour l’exécuter.' : undefined}>
                            <button
                              className="topbar-icon"
                              disabled={isInactive || launchingScenarioId === scenario.id}
                              aria-label={`Exécuter ${scenario.name}`}
                              title={isInactive ? undefined : `Exécuter le scénario (${scenario.virtualUsers} VU, ramp-up ${scenario.rampUpSeconds}s${scenario.durationSeconds ? `, durée ${scenario.durationSeconds}s` : scenario.iterations ? `, ${scenario.iterations} itération(s)` : ''}) — asynchrone, suivez la progression sur la page Exécutions`}
                              style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-success)', background: 'var(--pt-success-light)', opacity: isInactive ? 0.45 : 1, cursor: isInactive ? 'not-allowed' : undefined }}
                              onClick={() => handleExecuteScenario(scenario)}
                            >
                              <i className={`bi ${launchingScenarioId === scenario.id ? 'bi-arrow-repeat pt-spin' : 'bi-play-fill'}`} style={{ fontSize: '14px', color: 'var(--pt-success)' }}></i>
                            </button>
                            </span>
                          )}
                          {canWrite && !isDraft && !scenario.archived && !scenario.applicationArchived && (
                            <button className="topbar-icon" title="Modifier" style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-primary)', background: 'var(--pt-primary-light)' }} onClick={() => navigate(`/scenarios/${scenario.id}/edit`)} aria-label={`Modifier ${scenario.name}`}>
                              <i className="bi bi-pencil" style={{ fontSize: '13px', color: 'var(--pt-primary)' }}></i>
                            </button>
                          )}
                          {canWrite && (scenario.archived || scenario.applicationArchived) && (
                            <button className="topbar-icon" title={scenario.applicationArchived ? "Restaurez d'abord l'application" : 'Restaurer'} aria-label={`Restaurer ${scenario.name}`} disabled={scenario.applicationArchived} style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-primary)', background: 'var(--pt-primary-light)' }} onClick={() => handleRestoreScenario(scenario)}>
                              <i className="bi bi-arrow-counterclockwise" style={{ fontSize: '13px', color: 'var(--pt-primary)' }}></i>
                            </button>
                          )}
                          {!isDraft && (canPermanentDelete || (canDelete && !scenario.archived && !scenario.applicationArchived)) && (
                            <button className="topbar-icon" title={scenario.archived ? 'Supprimer définitivement' : 'Supprimer / archiver'} aria-label={`Supprimer ${scenario.name}`} style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-danger)', background: 'rgba(220,38,38,0.06)' }} onClick={() => setDeleteConfirm(scenario)}>
                              <i className="bi bi-trash" style={{ fontSize: '13px', color: 'var(--pt-danger)' }}></i>
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
        )}

        <Pagination
          page={page}
          totalPages={totalPages}
          onPageChange={setPage}
          startIndex={startIndex}
          endIndex={endIndex}
          totalItems={totalItems}
          itemLabel="scénarios"
          pageSize={urlSize}
          pageSizeOptions={[5, 10, 25, 50]}
          onPageSizeChange={(size) => updateParams({ size: size === 10 ? null : String(size) })}
        />
      </div>


      {/* Suppression / archivage d'un scénario : modale d'impact */}
      {deleteConfirm && (
        <DeletionDialog
          subject={`le scénario « ${deleteConfirm.name} »`}
          confirmText={deleteConfirm.name}
          loadImpact={() => scenariosBackendApi.deletionImpact(deleteConfirm.id)}
          canPermanent={canPermanentDelete}
          archiveAvailable={!deleteConfirm.archived && !deleteConfirm.applicationArchived}
          onConfirm={confirmScenarioDeletion}
          onClose={() => setDeleteConfirm(null)}
        />
      )}

      {/* Archivage / suppression définitive groupés : impact total + confirmation par saisie. */}
      {bulkDeletion && (
        <DeletionDialog
          subject={`${bulkDeletion.length} scénario${bulkDeletion.length > 1 ? 's' : ''}`}
          confirmText="SUPPRIMER"
          loadImpact={() => scenariosBackendApi.bulkDeletionImpact(bulkDeletion)}
          canPermanent={canPermanentDelete}
          archiveAvailable={!showArchived}
          onConfirm={confirmBulkDeletion}
          onClose={() => setBulkDeletion(null)}
        />
      )}

      {/* Brouillon : suppression simple (aucune exécution possible, donc aucun historique à conserver). */}
      {deleteDraftConfirm && (
        <div className="modal fade show d-block" tabIndex={-1} role="dialog" aria-modal="true" style={{ background: 'rgba(0,0,0,0.5)', zIndex: 1050 }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content" style={{ borderRadius: 'var(--pt-radius)', background: 'var(--pt-card-bg)', color: 'var(--pt-text)' }}>
              <div className="modal-body p-4">
                <h5 style={{ fontWeight: 700, marginBottom: '8px' }}>Supprimer le brouillon ?</h5>
                <p className="mb-0" style={{ color: 'var(--pt-text-muted)', fontSize: '13.5px' }}>
                  Le brouillon « {deleteDraftConfirm.name} » et ses étapes seront supprimés définitivement.
                </p>
              </div>
              <div className="modal-footer">
                <button className="pt-btn-outline" onClick={() => setDeleteDraftConfirm(null)} disabled={deletingDraft}>Annuler</button>
                <button className="pt-btn-danger" disabled={deletingDraft} onClick={async () => {
                  setDeletingDraft(true)
                  try {
                    await scenariosBackendApi.removeDraft(deleteDraftConfirm.id)
                    showToast(`Brouillon « ${deleteDraftConfirm.name} » supprimé.`, 'success')
                    setSelected((prev) => { const next = new Set(prev); next.delete(deleteDraftConfirm.id); return next })
                    setDeleteDraftConfirm(null)
                    refetchScenarios()
                  } catch (err) {
                    showToast(describeApiError(err, 'Suppression du brouillon impossible.'), 'danger')
                  } finally {
                    setDeletingDraft(false)
                  }
                }}>
                  {deletingDraft ? 'Suppression…' : 'Supprimer'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Ajouter / modifier une étape : même éditeur que l'assistant, dans un panneau latéral. */}
      {stepDrawer && selectedScenarioDetail && (
        <StepDrawer
          scenario={selectedScenarioDetail}
          application={applications.find((a) => a.id === selectedScenarioDetail.applicationId)}
          steps={[...detailSteps].sort((a, b) => a.order - b.order)}
          editing={stepDrawer.editing}
          onClose={() => setStepDrawer(null)}
          onSaved={async (message) => {
            showToast(message, 'success')
            setStepDrawer(null)
            setShowStepsInDetail(true)
            loadDetailSteps(selectedScenarioDetail.id)
            await refetchAllSteps()
          }}
        />
      )}

      {/* Delete Step Confirmation Modal */}
      {deleteStepConfirm && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1075, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '400px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)', textAlign: 'center' }}>
            <div style={{ width: '56px', height: '56px', borderRadius: '50%', background: 'var(--pt-danger-light)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 1rem' }}>
              <i className="bi bi-trash" style={{ fontSize: '24px', color: 'var(--pt-danger)' }}></i>
            </div>
            <h5 style={{ fontWeight: 700, marginBottom: '8px' }}>Supprimer l'étape ?</h5>
            <p style={{ color: 'var(--pt-text-muted)', fontSize: '14px', marginBottom: '1.5rem' }}>
              « {deleteStepConfirm.name} » sera définitivement supprimée.
            </p>
            {stepActionError && (
              <div className="pt-alert-banner danger mb-3" style={{ textAlign: 'left' }}>
                <i className="bi bi-exclamation-triangle-fill"></i>
                {stepActionError}
              </div>
            )}
            <div className="d-flex gap-2 justify-content-center">
              <button onClick={() => { setDeleteStepConfirm(null); setStepActionError(null) }} disabled={deletingStep} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                Annuler
              </button>
              <button onClick={handleDeleteStep} disabled={deletingStep} style={{ background: 'var(--pt-danger)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                {deletingStep ? 'Suppression...' : 'Supprimer'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default Scenarios
