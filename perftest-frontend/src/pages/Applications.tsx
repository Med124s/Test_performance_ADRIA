import { formatDate } from '../utils/dateFormat'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { BackendApplicationRequest, BackendApplicationResponse, BackendApplicationStatus, BackendApplicationTestResponse, BackendScenarioResponse, BackendExecutionResponse } from '../types/backendContracts'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { ApiError } from '../services/api/httpClient'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { useApiList } from '../hooks/useApiResource'
import Pagination from '../components/Pagination'
import { firstError, validateRequired, validateMaxLength, validateAbsoluteUrl, NAME_MAX_LENGTH, DESCRIPTION_MAX_LENGTH } from '../utils/validation'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import './applications.css'
import ApplicationAuthFields, {
  AuthForm,
  EMPTY_AUTH_FORM,
  authFormFromResponse,
  authFormToRequest,
  authTypeLabel,
  validateAuthForm,
} from '../components/application-auth/ApplicationAuthFields'
import AuthTestProgress, { PHASE_LABELS, TestPhase, phasesFor, testPhasesFor } from '../components/application-auth/AuthTestProgress'
import DeletionDialog, { DeletionMode } from '../components/deletion/DeletionDialog'
import MonitoringFields from '../components/application-monitoring/MonitoringFields'
import NetworkFields from '../components/application-network/NetworkFields'
import FormTabs from '../components/FormTabs'
import {
  EMPTY_NETWORK_FORM, NetworkForm, PROXY_MODE_LABELS, networkFormFromResponse, networkFormToRequest, validateNetworkForm,
} from '../components/application-network/networkModel'
import {
  EMPTY_MONITORING_FORM, MONITORING_TYPE_LABELS, MonitoringForm, monitoringFormFromResponse, monitoringFormToRequest, validateMonitoringForm,
} from '../components/application-monitoring/monitoringModel'

// ============================================================
// Phase 17 — ce module (et lui seul) parle désormais au vrai backend Spring
// Boot (voir services/api/applicationsBackend.ts), plus à JSON Server.
//
// Champs volontairement ABSENTS du formulaire par rapport à l'ancienne
// version (type/authMethod/token/username/password/clientId/clientSecret/
// icon/color) : aucun n'existe dans le contrat réel du backend
// (ApplicationRequest/ApplicationResponse, voir types/backendContracts.ts)
// — les garder aurait signifié les saisir sans jamais les persister nulle
// part, ce qui aurait été trompeur. `description` est en revanche un champ
// réel du backend, ajouté ici.
//
// P1-I — "Scénarios"/"Dernière exécution" utilisent désormais les VRAIS
// Scénarios/Exécutions Spring Boot (scenariosBackendApi/executionsBackendApi,
// déjà réels et déjà utilisés par Scenarios.tsx), plus JSON Server.
//
// Endpoint dashboard PAR APPLICATION déjà existant côté backend
// (GET /api/dashboard/applications/{id} -> ApplicationDashboardResponse,
// déjà câblé côté frontend : dashboardBackendApi.getByApplication)
// délibérément NON utilisé ici, pour deux raisons prouvées (voir rapport
// P1-I) : (1) son DTO ne contient AUCUN champ date — seulement des
// COMPTEURS agrégés (ScenariosSummaryResponse/ExecutionsSummaryResponse),
// impossible d'en dériver "la dernière exécution" ; (2) l'appeler une fois
// par ligne affichée créerait un vrai N+1 (aucun endpoint de liste groupée
// n'existe : GET /api/dashboard ne couvre que la plateforme entière).
//
// Solution retenue : scenariosBackendApi.getAll()/executionsBackendApi.getAll()
// (un seul appel chacun, quel que soit le nombre d'Applications affichées),
// jointes côté client par scenario.applicationId puis execution.scenarioId
// — même principe que Scenarios.tsx (stepsByScenario/latestExecByScenario).
// ============================================================

const emptyForm = {
  name: '',
  description: '',
  url: '',
}

type StatusKey = BackendApplicationStatus | 'UNTESTED'

/** Onglets de la modale d'application, dans l'ordre d'affichage. */
const APP_TABS = ['general', 'auth', 'network', 'monitoring'] as const
type AppTab = typeof APP_TABS[number]

const PAGE_SIZES = [5, 10, 25, 50]
const DEFAULT_SORT = 'createdAt,desc'
const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Plus récentes' },
  { value: 'createdAt,asc', label: 'Plus anciennes' },
]
const STATUS_FILTERS = [
  { value: '', label: 'Tous les statuts' },
  { value: 'CONNECTED', label: 'Connectées' },
  { value: 'FAILED', label: 'Échec' },
  { value: 'ERROR', label: 'Erreur' },
  { value: 'UNTESTED', label: 'Non testées' },
]

const statusPillStyle: Record<StatusKey, { bg: string; color: string; icon: string; label: string }> = {
  CONNECTED: { bg: 'var(--pt-success-light)', color: 'var(--pt-success)', icon: 'bi-wifi', label: 'Connectée' },
  FAILED: { bg: 'var(--pt-danger-light)', color: 'var(--pt-danger)', icon: 'bi-wifi-off', label: 'Échec' },
  ERROR: { bg: 'var(--pt-danger-light)', color: 'var(--pt-danger)', icon: 'bi-exclamation-triangle', label: 'Erreur' },
  UNTESTED: { bg: 'var(--pt-bg)', color: 'var(--pt-text-muted)', icon: 'bi-question-circle', label: 'Non testée' },
}

function statusKey(status: BackendApplicationStatus | null): StatusKey {
  return status ?? 'UNTESTED'
}

/** Traduit une erreur RÉELLE (jamais masquée, voir Phase 17 section 17) en
 * message utilisateur. Le code HTTP prime toujours ; 409/400 réutilisent le
 * message backend tel quel (déjà explicite, ex: "des scenarios sont encore
 * rattaches"). */
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
        return 'Application introuvable (elle a peut-être déjà été supprimée).'
      case 409:
        return err.message
      default:
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function Applications() {
  const navigate = useNavigate()
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()

  // Le backend Spring Boot exige un JWT réel (voir SecurityConfig) : sans
  // session Keycloak active, aucun token n'existe donc aucune action
  // d'écriture n'est proposée - la liste elle-même échouera en 401 (voir
  // appsError ci-dessous), affiché tel quel, jamais masqué.
  const isKeycloak = authProvider === 'keycloak'
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))
  const canDelete = isKeycloak && rawRoles.includes('ROLE_SUPER_ADMIN')

  // ---------------------------------------------------------------- pagination serveur
  // Page, taille, tri, recherche, filtre de status et vue "archivées" vivent
  // dans l'URL : l'état survit au rechargement et se partage par lien.
  const [searchParams, setSearchParams] = useSearchParams()
  const urlPage = Math.max(1, Number(searchParams.get('page')) || 1)
  const urlSize = PAGE_SIZES.includes(Number(searchParams.get('size'))) ? Number(searchParams.get('size')) : 10
  const urlSort = SORT_OPTIONS.some((o) => o.value === searchParams.get('sort')) ? (searchParams.get('sort') as string) : DEFAULT_SORT
  const urlQuery = searchParams.get('q') ?? ''
  const urlStatus = STATUS_FILTERS.some((o) => o.value === searchParams.get('status')) ? (searchParams.get('status') as string) : ''
  const showArchived = searchParams.get('archived') === 'true'

  /** Met à jour l'URL ; tout changement de critère revient à la page 1. */
  const updateParams = useCallback((patch: Record<string, string | null>, resetPage = true) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      Object.entries(patch).forEach(([k, v]) => (v === null || v === '' ? next.delete(k) : next.set(k, v)))
      if (resetPage) next.delete('page')
      return next
    }, { replace: true })
  }, [setSearchParams])

  // Recherche avec debounce : l'URL (et donc la requête) suit la saisie après 350 ms.
  const [searchInput, setSearchInput] = useState(urlQuery)
  useEffect(() => {
    if (searchInput === urlQuery) return
    const timer = window.setTimeout(() => updateParams({ q: searchInput.trim() || null }), 350)
    return () => window.clearTimeout(timer)
  }, [searchInput, urlQuery, updateParams])

  const [pageData, setPageData] = useState<{ content: BackendApplicationResponse[]; totalElements: number; totalPages: number }>(
    { content: [], totalElements: 0, totalPages: 0 })
  const [appsLoading, setAppsLoading] = useState(true)
  const [appsError, setAppsError] = useState<string | null>(null)
  const apps = pageData.content
  const setApps = (update: (prev: BackendApplicationResponse[]) => BackendApplicationResponse[]) =>
    setPageData((prev) => ({ ...prev, content: update(prev.content) }))

  const loadPage = useCallback(async () => {
    setAppsLoading(true)
    setAppsError(null)
    try {
      const result = await applicationsBackendApi.getPage({
        page: urlPage - 1, size: urlSize, sort: urlSort, search: urlQuery, status: urlStatus, archived: showArchived,
      })
      // Page devenue vide (ex. après une suppression) : revenir à la dernière page existante.
      if (result.content.length === 0 && urlPage > 1 && result.totalElements > 0) {
        updateParams({ page: String(Math.max(1, result.totalPages)) }, false)
        return
      }
      setPageData({ content: result.content, totalElements: result.totalElements, totalPages: result.totalPages })
    } catch (err) {
      setAppsError(err instanceof Error ? err.message : 'Erreur de chargement')
    } finally {
      setAppsLoading(false)
    }
  }, [urlPage, urlSize, urlSort, urlQuery, urlStatus, showArchived, updateParams])
  useEffect(() => { loadPage() }, [loadPage])
  const refetchApps = loadPage

  // Compteurs globaux (toutes pages), recalculés après chaque action.
  const [statsVersion, setStatsVersion] = useState(0)
  const refreshStats = () => setStatsVersion((v) => v + 1)
  const [stats, setStats] = useState<{ total: number; connected: number; notConnected: number } | null>(null)
  useEffect(() => {
    let alive = true
    const count = (status?: string) => applicationsBackendApi.getPage({ page: 0, size: 5, status }).then((r) => r.totalElements)
    Promise.all([count(), count('CONNECTED'), count('FAILED'), count('ERROR')])
      .then(([total, connected, failed, error]) => { if (alive) setStats({ total, connected, notConnected: failed + error }) })
      .catch(() => { if (alive) setStats(null) })
    return () => { alive = false }
  }, [statsVersion])

  // ---------------------------------------------------------------- sélection multiple
  const [selected, setSelected] = useState<Set<string>>(new Set())
  // Vidée au changement de page, de taille, de tri, de filtre ou de recherche.
  useEffect(() => { setSelected(new Set()) }, [urlPage, urlSize, urlSort, urlQuery, urlStatus, showArchived])
  const pageIds = apps.map((a) => a.id)
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

  // ---------------------------------------------------------------- suppression / archivage
  const [deletion, setDeletion] = useState<
    | { kind: 'single'; app: BackendApplicationResponse }
    | { kind: 'bulk'; ids: string[] }
    | null
  >(null)
  const [bulkBusy, setBulkBusy] = useState(false)

  const afterMutation = async () => {
    setSelected(new Set())
    refreshStats()
    await loadPage()
  }

  const confirmDeletion = async (mode: DeletionMode) => {
    if (!deletion) return
    if (deletion.kind === 'single') {
      const app = deletion.app
      if (mode === 'ARCHIVE') await applicationsBackendApi.archive(app.id)
      else await applicationsBackendApi.remove(app.id)
      showToast(mode === 'ARCHIVE'
        ? `Application « ${app.name} » archivée. Vous pouvez la restaurer depuis « Afficher les archivées ».`
        : `Application « ${app.name} » supprimée définitivement.`, 'success')
    } else {
      const result = mode === 'ARCHIVE'
        ? await applicationsBackendApi.bulkArchive(deletion.ids, 'ARCHIVE')
        : await applicationsBackendApi.bulkDelete(deletion.ids, 'PERMANENT')
      reportBulk(result, mode === 'ARCHIVE' ? 'archivée(s)' : 'supprimée(s) définitivement')
    }
    setDeletion(null)
    await afterMutation()
  }

  const reportBulk = (result: { succeeded: number; failed: number; results: { name: string | null; success: boolean; message: string }[] }, verb: string) => {
    if (result.failed === 0) {
      showToast(`${result.succeeded} application(s) ${verb}.`, 'success')
      return
    }
    const failures = result.results.filter((r) => !r.success).map((r) => `« ${r.name ?? '?'} » : ${r.message}`).join(' ; ')
    showToast(`${result.succeeded} application(s) ${verb}, ${result.failed} en échec — ${failures}`, result.succeeded > 0 ? 'warning' : 'danger')
  }

  const restoreApps = async (ids: string[]) => {
    setBulkBusy(true)
    try {
      if (ids.length === 1) {
        const restored = await applicationsBackendApi.restore(ids[0])
        showToast(`Application « ${restored.name} » restaurée.`, 'success')
      } else {
        reportBulk(await applicationsBackendApi.bulkArchive(ids, 'RESTORE'), 'restaurée(s)')
      }
      await afterMutation()
    } catch (err) {
      showToast(describeApiError(err, 'Erreur lors de la restauration.'), 'danger')
    } finally {
      setBulkBusy(false)
    }
  }

  const retestSelected = async () => {
    setBulkBusy(true)
    try {
      for (const app of apps.filter((a) => selected.has(a.id))) {
        await handleRetest(app)
      }
      refreshStats()
    } finally {
      setBulkBusy(false)
    }
  }

  const { data: scenarios, loading: scenariosLoading, error: scenariosError } =
    useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll(showArchived), [showArchived])
  const { data: executions, loading: executionsLoading, error: executionsError } =
    useApiList<BackendExecutionResponse>(() => executionsBackendApi.getAll())

  const [showModal, setShowModal] = useState(false)
  const [editingApp, setEditingApp] = useState<BackendApplicationResponse | null>(null)
  const [form, setForm] = useState(emptyForm)
  // Flux "enregistrer puis tester" de la modale (Tester et ajouter /
  // Enregistrer et tester) : phase courante, étapes affichées, résultat.
  const [flow, setFlow] = useState<{
    running: boolean
    phase: TestPhase | null
    phases: TestPhase[]
    result: BackendApplicationTestResponse | null
  }>({ running: false, phase: null, phases: [], result: null })
  // Retester depuis la liste : phase en cours par application.
  const [rowTests, setRowTests] = useState<Record<string, TestPhase>>({})
  const [flashId, setFlashId] = useState<string | null>(null)
  const closeTimer = useRef<number | null>(null)
  const [isViewOnly, setIsViewOnly] = useState(false)
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [touched, setTouched] = useState<{ name?: boolean; url?: boolean }>({})
  const [authForm, setAuthForm] = useState<AuthForm>(EMPTY_AUTH_FORM)
  const [showAuthErrors, setShowAuthErrors] = useState(false)
  const [monitoringForm, setMonitoringForm] = useState<MonitoringForm>(EMPTY_MONITORING_FORM)
  const [networkForm, setNetworkForm] = useState<NetworkForm>(EMPTY_NETWORK_FORM)
  /** Onglet affiché dans la modale (toujours « Général » à l'ouverture). */
  const [activeTab, setActiveTab] = useState<AppTab>('general')

  const nameError = firstError(
    validateRequired(form.name, "Le nom de l'application"),
    validateMaxLength(form.name, NAME_MAX_LENGTH, "Le nom de l'application")
  )
  const urlError = firstError(validateRequired(form.url, "L'URL"), validateAbsoluteUrl(form.url))
  const descriptionError = validateMaxLength(form.description, DESCRIPTION_MAX_LENGTH, 'La description')
  const authErrors = validateAuthForm(authForm, editingApp?.auth)
  const monitoringErrors = validateMonitoringForm(monitoringForm, editingApp?.monitoring)
  const networkErrors = validateNetworkForm(networkForm)
  const isFormValid = !nameError && !urlError && !descriptionError && Object.keys(authErrors).length === 0
    && Object.keys(monitoringErrors).length === 0 && Object.keys(networkErrors).length === 0
  // Onglets : point d'erreur (après une tentative d'enregistrement, ou champ déjà touché pour « Général »),
  // sinon point « configuré » si le réglage n'est pas la valeur par défaut — visible sans ouvrir l'onglet.
  const tabErrors: Record<AppTab, boolean> = {
    general: (showAuthErrors || !!touched.name || !!touched.url) && (!!nameError || !!urlError || !!descriptionError),
    auth: showAuthErrors && Object.keys(authErrors).length > 0,
    network: showAuthErrors && Object.keys(networkErrors).length > 0,
    monitoring: showAuthErrors && Object.keys(monitoringErrors).length > 0,
  }
  const tabConfigured: Record<AppTab, boolean> = {
    general: false,
    auth: authForm.type !== 'NONE',
    network: networkForm.proxyMode !== 'INHERIT',
    monitoring: monitoringForm.type !== 'NONE' || monitoringForm.withoutProxy,
  }
  const tabMarkers = Object.fromEntries(APP_TABS.map((t) => [t, tabErrors[t] ? 'error' : tabConfigured[t] ? 'configured' : null])) as
    Record<AppTab, 'error' | 'configured' | null>

  const totalItems = pageData.totalElements
  const totalPages = Math.max(1, pageData.totalPages)
  const startIndex = totalItems === 0 ? 0 : (urlPage - 1) * urlSize + 1
  const endIndex = Math.min(urlPage * urlSize, totalItems)
  const hasFilters = !!(urlQuery || urlStatus)

  // P1-I — Dernière exécution + nombre de scénarios RÉELS (Spring Boot) par
  // application. Execution n'a pas de colonne applicationId directe (voir
  // entity.Execution : uniquement une FK vers Scenario) — la résolution
  // passe donc par scenario.applicationId, exactement comme Scenarios.tsx.
  const scenarioAppById = useMemo(() => {
    const map = new Map<string, string>()
    for (const s of scenarios) map.set(s.id, s.applicationId)
    return map
  }, [scenarios])

  const latestExecutionByApp = useMemo(() => {
    const map = new Map<string, BackendExecutionResponse>()
    for (const exec of executions) {
      const applicationId = scenarioAppById.get(exec.scenarioId)
      if (!applicationId) continue
      const current = map.get(applicationId)
      if (!current || new Date(exec.startedAt) > new Date(current.startedAt)) {
        map.set(applicationId, exec)
      }
    }
    return map
  }, [executions, scenarioAppById])

  const scenarioCountByApp = (applicationId: string) =>
    scenarios.filter((s) => s.applicationId === applicationId).length

  /** Durée minimale d'affichage du chargement (visible même si la réponse
   * est instantanée) — n'allonge jamais une réponse plus lente. */
  const MIN_LOADING_MS = 1300
  const waitMinimum = async (startedAt: number) => {
    const remaining = MIN_LOADING_MS - (Date.now() - startedAt)
    if (remaining > 0) await new Promise((resolve) => window.setTimeout(resolve, remaining))
  }

  const clearCloseTimer = () => {
    if (closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current)
      closeTimer.current = null
    }
  }
  useEffect(() => clearCloseTimer, [])

  /** Met à jour une application dans la liste sans recharger la page. */
  const upsertApp = (app: BackendApplicationResponse) => {
    setApps((prev) => (prev.some((a) => a.id === app.id) ? prev.map((a) => (a.id === app.id ? app : a)) : [app, ...prev]))
  }
  const applyTestResult = (id: string, result: BackendApplicationTestResponse) => {
    setApps((prev) => prev.map((a) => (a.id === id
      ? { ...a, status: result.status, statusMessage: result.message, statusCheckedAt: new Date().toISOString() }
      : a)))
    setFlashId(id)
    window.setTimeout(() => setFlashId((current) => (current === id ? null : current)), 1300)
  }

  /** Test réel (POST /{id}/test). Étapes affichées selon le type (voir
   * testPhasesFor) : pour la connexion auto, le backend effectue d'abord le
   * login ; pour Bearer / clé API / Basic, il vérifie les identifiants sur
   * l'endpoint protégé. */
  const runConnectivityTest = async (app: BackendApplicationResponse, onPhase: (phase: TestPhase) => void) => {
    const phases = testPhasesFor(app.auth?.type)
    onPhase(phases[0])
    const timer = phases.length > 1 ? window.setTimeout(() => onPhase(phases[1]), 450) : null
    try {
      return await applicationsBackendApi.test(app.id)
    } finally {
      if (timer !== null) window.clearTimeout(timer)
    }
  }

  const resetFlow = () => setFlow({ running: false, phase: null, phases: [], result: null })

  const openAdd = () => {
    clearCloseTimer()
    setEditingApp(null)
    setForm(emptyForm)
    setAuthForm(EMPTY_AUTH_FORM)
    setMonitoringForm(EMPTY_MONITORING_FORM)
    setNetworkForm(EMPTY_NETWORK_FORM)
    setActiveTab('general')
    setShowAuthErrors(false)
    resetFlow()
    setActionError(null)
    setTouched({})
    setIsViewOnly(false)
    setShowModal(true)
  }

  const openEdit = (app: BackendApplicationResponse) => {
    clearCloseTimer()
    setEditingApp(app)
    setForm({
      name: app.name,
      description: app.description || '',
      url: app.url,
    })
    setAuthForm(authFormFromResponse(app.auth))
    setMonitoringForm(monitoringFormFromResponse(app.monitoring))
    setNetworkForm(networkFormFromResponse(app.network))
    setActiveTab('general')
    setShowAuthErrors(false)
    resetFlow()
    setActionError(null)
    setTouched({})
    setIsViewOnly(false)
    setShowModal(true)
  }

  /** Mode "Consulter" : lecture seule pure, jamais un formulaire. */
  const openView = (app: BackendApplicationResponse) => {
    clearCloseTimer()
    setEditingApp(app)
    setActionError(null)
    resetFlow()
    setIsViewOnly(true)
    setShowModal(true)
  }

  const closeModal = () => {
    // Jamais pendant un enregistrement/test en cours.
    if (flow.running) return
    clearCloseTimer()
    setShowModal(false)
    resetFlow()
    setIsViewOnly(false)
  }

  // Échap ferme la modale, sauf pendant le chargement.
  useEffect(() => {
    if (!showModal) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !flow.running) closeModal()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showModal, flow.running])

  const buildPayload = (): BackendApplicationRequest => ({
    name: form.name.trim(),
    description: form.description.trim() || null,
    url: form.url.trim(),
    auth: authFormToRequest(authForm),
    monitoring: monitoringFormToRequest(monitoringForm),
    network: networkFormToRequest(networkForm),
  })

  // "Tester et ajouter" (création) ou "Enregistrer et tester" (modification) :
  // POST/PUT puis test réel. Après un premier enregistrement, la modale
  // passe en modification : un nouvel essai fait un PUT (jamais de doublon).
  // Succès -> fermeture après une courte confirmation ; échec -> la modale
  // reste ouverte pour corriger directement.
  const handleSaveAndTest = async () => {
    if (!canWrite || flow.running) return
    setTouched({ name: true, url: true })
    setShowAuthErrors(true)
    if (!isFormValid) {
      // Champ invalide dans un autre onglet : on l'affiche, sinon le refus resterait inexpliqué.
      const invalid: Record<AppTab, boolean> = {
        general: !!nameError || !!urlError || !!descriptionError,
        auth: Object.keys(authErrors).length > 0,
        network: Object.keys(networkErrors).length > 0,
        monitoring: Object.keys(monitoringErrors).length > 0,
      }
      if (!invalid[activeTab]) setActiveTab(APP_TABS.find((t) => invalid[t]) ?? activeTab)
      return
    }

    const startedAt = Date.now()
    const phases = phasesFor(authForm.type)
    clearCloseTimer()
    setActionError(null)
    setFlow({ running: true, phase: 'save', phases, result: null })

    let saved: BackendApplicationResponse
    try {
      saved = editingApp
        ? await applicationsBackendApi.update(editingApp.id, buildPayload())
        : await applicationsBackendApi.create(buildPayload())
    } catch (err) {
      await waitMinimum(startedAt)
      setFlow({ running: false, phase: null, phases, result: null })
      setActionError(describeApiError(err, "Erreur lors de l'enregistrement."))
      return
    }
    const created = !editingApp
    setEditingApp(saved)
    upsertApp(saved)
    refreshStats()

    try {
      const result = await runConnectivityTest(saved, (phase) => setFlow((f) => ({ ...f, phase })))
      await waitMinimum(startedAt)
      applyTestResult(saved.id, result)
      setEditingApp({ ...saved, status: result.status, statusMessage: result.message, statusCheckedAt: new Date().toISOString() })
      setFlow({ running: false, phase: null, phases, result })
      if (result.status === 'CONNECTED') {
        showToast(`Application « ${saved.name} » ${created ? 'ajoutée' : 'enregistrée'} et connectée.`, 'success')
        closeTimer.current = window.setTimeout(() => {
          closeTimer.current = null
          setShowModal(false)
          resetFlow()
        }, 1400)
      }
    } catch (err) {
      await waitMinimum(startedAt)
      setFlow({ running: false, phase: null, phases, result: null })
      setActionError(`Application enregistrée, mais le test n'a pas pu être lancé : ${describeApiError(err, 'erreur inconnue.')}`)
    }
  }

  // "Retester" depuis la liste, sans ouvrir la modale.
  const handleRetest = async (app: BackendApplicationResponse) => {
    if (!canWrite || rowTests[app.id]) return
    const startedAt = Date.now()
    const setPhase = (phase: TestPhase) => setRowTests((prev) => ({ ...prev, [app.id]: phase }))
    try {
      const result = await runConnectivityTest(app, setPhase)
      await waitMinimum(startedAt)
      applyTestResult(app.id, result)
      showToast(`« ${app.name} » : ${statusPillStyle[result.status].label} — ${result.message}`, result.status === 'CONNECTED' ? 'success' : 'warning')
    } catch (err) {
      await waitMinimum(startedAt)
      showToast(describeApiError(err, 'Erreur lors du test.'), 'danger')
    } finally {
      setRowTests((prev) => {
        const next = { ...prev }
        delete next[app.id]
        return next
      })
    }
  }

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Applications</h1>
          <p>Gérez vos applications à tester</p>
        </div>
      </div>

      {(scenariosError || executionsError) && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger {scenariosError && executionsError ? 'les scénarios et exécutions' : scenariosError ? 'les scénarios' : 'les exécutions'} réel(le)s ({scenariosError || executionsError}) — les colonnes "Scénarios"/"Dernière exécution" ci-dessous peuvent être incomplètes.
        </div>
      )}

      {appsError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger les applications : {appsError}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => refetchApps()}>
            Réessayer
          </button>
        </div>
      )}

      <div className="row g-3 mb-4">
        {[
          { label: 'Total applications', value: stats?.total, icon: 'bi-globe2', color: 'blue' },
          { label: 'Connectées', value: stats?.connected, icon: 'bi-wifi', color: 'green' },
          { label: 'Non connectées', value: stats?.notConnected, icon: 'bi-wifi-off', color: 'purple' },
        ].map((card, i) => (
          <div key={i} className="col-6 col-md-4">
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

      <div className="d-flex align-items-center gap-3 flex-wrap mb-3">
        <div className="pt-search">
          <i className="bi bi-search"></i>
          <input type="text" placeholder="Rechercher (nom, URL, description)…" value={searchInput} onChange={e => setSearchInput(e.target.value)} aria-label="Rechercher une application" />
        </div>
        <select className="pt-form-control" style={{ width: 'auto' }} value={urlStatus} onChange={(e) => updateParams({ status: e.target.value || null })} aria-label="Filtrer par statut">
          {STATUS_FILTERS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <select className="pt-form-control" style={{ width: 'auto' }} value={urlSort} onChange={(e) => updateParams({ sort: e.target.value === DEFAULT_SORT ? null : e.target.value })} aria-label="Trier">
          {SORT_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <label className="d-flex align-items-center gap-2 mb-0" style={{ fontSize: '13px', color: 'var(--pt-text-muted)', cursor: 'pointer' }}>
          <input type="checkbox" checked={showArchived} onChange={(e) => updateParams({ archived: e.target.checked ? 'true' : null })} />
          Afficher les archivées
        </label>
        {canWrite && (
          <button className="pt-btn-primary" onClick={openAdd}>
            <i className="bi bi-plus-lg"></i>
            Ajouter une application
          </button>
        )}
      </div>

      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
            {showArchived ? 'Applications archivées' : 'Liste des applications'} ({totalItems})
          </h6>
        </div>

        {canWrite && selected.size > 0 && (
          <div className="ap-bulk-bar" role="region" aria-label="Actions sur la sélection">
            <strong>{selected.size} sélectionnée{selected.size > 1 ? 's' : ''}</strong>
            <div className="d-flex gap-2 flex-wrap">
              {!showArchived && (
                <button type="button" className="pt-btn-outline" onClick={retestSelected} disabled={bulkBusy}>
                  <i className="bi bi-arrow-repeat"></i> Retester
                </button>
              )}
              {showArchived ? (
                <button type="button" className="pt-btn-outline" onClick={() => restoreApps([...selected])} disabled={bulkBusy}>
                  <i className="bi bi-arrow-counterclockwise"></i> Restaurer
                </button>
              ) : (
                <button type="button" className="pt-btn-outline" onClick={() => setDeletion({ kind: 'bulk', ids: [...selected] })} disabled={bulkBusy}>
                  <i className="bi bi-archive"></i> Archiver
                </button>
              )}
              {canDelete && (
                <button type="button" className="pt-btn-danger" onClick={() => setDeletion({ kind: 'bulk', ids: [...selected] })} disabled={bulkBusy}>
                  <i className="bi bi-trash3"></i> Supprimer définitivement
                </button>
              )}
              <button type="button" className="pt-btn-ghost" onClick={() => setSelected(new Set())} disabled={bulkBusy}>
                Annuler la sélection
              </button>
            </div>
          </div>
        )}

        {appsLoading && apps.length === 0 ? (
          <div className="ap-skeleton" role="status" aria-label="Chargement des applications">
            {Array.from({ length: Math.min(urlSize, 5) }).map((_, i) => <div key={i} className="ap-skeleton-row"></div>)}
          </div>
        ) : apps.length === 0 ? (
          <div className="pt-empty-state">
            <i className={`bi ${showArchived ? 'bi-archive' : 'bi-globe2'}`} style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
            <p>
              {hasFilters
                ? 'Aucune application ne correspond à ces critères.'
                : showArchived ? 'Aucune application archivée.' : "Aucune application n'a encore été créée."}
            </p>
            {hasFilters && (
              <button type="button" className="pt-btn-outline" onClick={() => { setSearchInput(''); updateParams({ q: null, status: null }) }}>
                Effacer les filtres
              </button>
            )}
          </div>
        ) : (
        <div className="pt-table-wrapper" aria-busy={appsLoading} style={{ opacity: appsLoading ? 0.6 : 1, transition: 'opacity 0.15s' }}>
          <table className="pt-table">
            <thead>
              <tr>
                {canWrite && (
                  <th style={{ width: '40px' }}>
                    <input ref={selectAllRef} type="checkbox" checked={allSelected} onChange={toggleAll} aria-label="Sélectionner toutes les applications de la page" />
                  </th>
                )}
                <th>Nom</th>
                <th>URL</th>
                <th>Description</th>
                <th>Statut</th>
                <th>Scénarios</th>
                <th>Dernière exécution</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {apps.map((app) => {
                const latestExec = latestExecutionByApp.get(app.id) ?? null
                const pill = statusPillStyle[statusKey(app.status)]
                return (
                <tr key={app.id} className={flashId === app.id ? 'aa-row-flash' : undefined}>
                  {canWrite && (
                    <td>
                      <input type="checkbox" checked={selected.has(app.id)} onChange={() => toggleOne(app.id)} aria-label={`Sélectionner ${app.name}`} />
                    </td>
                  )}
                  <td>
                    <div className="d-flex align-items-center gap-3">
                      <div style={{ width: '40px', height: '40px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '18px', flexShrink: 0, background: 'var(--pt-primary-light)', color: 'var(--pt-primary)' }}>
                        <i className="bi bi-globe2"></i>
                      </div>
                      <span style={{ fontSize: '13.5px', fontWeight: 600, color: 'var(--pt-text)' }}>{app.name}</span>
                      {app.auth && app.auth.type !== 'NONE' && (
                        <i className="bi bi-shield-lock ms-2" style={{ fontSize: '12px', color: 'var(--pt-success)' }} title={`Authentification : ${authTypeLabel(app.auth.type)}`}></i>
                      )}
                    </div>
                  </td>
                  <td>
                    <a href={app.url} target="_blank" rel="noreferrer" style={{ fontSize: '13px', color: 'var(--pt-primary)' }}>
                      {app.url}
                    </a>
                  </td>
                  <td><span style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>{app.description || '—'}</span></td>
                  <td aria-busy={!!rowTests[app.id]}>
                    {rowTests[app.id] ? (
                      <span className="pt-pill" role="status" style={{ background: 'var(--pt-primary-light)', color: 'var(--pt-primary)', fontSize: '11.5px' }}>
                        <span className="aa-inline-spinner" aria-hidden="true"></span>
                        {PHASE_LABELS[rowTests[app.id]]}
                      </span>
                    ) : (
                      <>
                        <span
                          className="pt-pill"
                          style={{ background: pill.bg, color: pill.color, fontSize: '11.5px' }}
                          title={app.status ? `Résultat du dernier test réel : ${app.status}` : "Aucun test valide (jamais testée ou modifiée depuis)"}
                        >
                          <i className={`bi ${pill.icon}`} style={{ fontSize: '10px' }}></i>
                          {pill.label}
                        </span>
                        {app.statusMessage && (
                          <span className="aa-status-message" title={app.statusMessage}>{app.statusMessage}</span>
                        )}
                      </>
                    )}
                  </td>
                  <td>
                    {scenariosLoading ? (
                      <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}></i>
                    ) : (
                      <button
                        onClick={() => navigate(`/scenarios?app=${encodeURIComponent(app.name)}`)}
                        title="Voir les scénarios de cette application"
                        style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer' }}
                      >
                        <span className="pt-pill neutral">
                          <i className="bi bi-diagram-3 me-1" style={{ fontSize: '11px' }}></i>
                          {scenarioCountByApp(app.id)} scénario{scenarioCountByApp(app.id) > 1 ? 's' : ''}
                        </span>
                      </button>
                    )}
                  </td>
                  <td>
                    {executionsLoading ? (
                      <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}></i>
                    ) : latestExec ? (
                      <button
                        onClick={() => navigate(`/executions/detail/${latestExec.id}`)}
                        title="Voir le détail de cette exécution"
                        style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '13px', color: 'var(--pt-text-muted)', textDecoration: 'none' }}
                      >
                        {formatDate(latestExec.startedAt)}
                      </button>
                    ) : (
                      <span style={{ fontSize: '13px', color: 'var(--pt-text-light)' }}>—</span>
                    )}
                  </td>
                  <td>
                    <div className="d-flex justify-content-end gap-1">
                      <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Voir" onClick={() => openView(app)}>
                        <i className="bi bi-eye" style={{ fontSize: '14px' }}></i>
                      </button>
                      {canWrite && app.archived && (
                        <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Restaurer" aria-label={`Restaurer ${app.name}`} onClick={() => restoreApps([app.id])} disabled={bulkBusy}>
                          <i className="bi bi-arrow-counterclockwise" style={{ fontSize: '14px' }}></i>
                        </button>
                      )}
                      {canWrite && !app.archived && (
                        <button
                          className="topbar-icon"
                          style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }}
                          title="Retester la connectivité"
                          aria-label={`Retester ${app.name}`}
                          onClick={() => handleRetest(app)}
                          disabled={!!rowTests[app.id]}
                        >
                          {rowTests[app.id]
                            ? <span className="aa-inline-spinner" aria-hidden="true"></span>
                            : <i className="bi bi-arrow-repeat" style={{ fontSize: '14px' }}></i>}
                        </button>
                      )}
                      {canWrite && !app.archived && (
                        <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Modifier" onClick={() => openEdit(app)}>
                          <i className="bi bi-pencil" style={{ fontSize: '14px' }}></i>
                        </button>
                      )}
                      {(canDelete || (canWrite && !app.archived)) && (
                        <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)', color: 'var(--pt-danger)' }} title={app.archived ? 'Supprimer définitivement' : 'Supprimer / archiver'} aria-label={`Supprimer ${app.name}`} onClick={() => setDeletion({ kind: 'single', app })}>
                          <i className="bi bi-trash" style={{ fontSize: '14px' }}></i>
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              )})}
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
          itemLabel="applications"
          pageSize={urlSize}
          pageSizeOptions={PAGE_SIZES}
          onPageSizeChange={(size) => updateParams({ size: size === 10 ? null : String(size) })}
        />
      </div>

      {/* Add/Edit Modal */}
      {showModal && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div
            role="dialog"
            aria-modal="true"
            aria-busy={flow.running}
            aria-labelledby="app-modal-title"
            style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '620px', maxWidth: '95vw', maxHeight: '90vh', overflowY: 'auto', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}
          >
            <div className="d-flex justify-content-between align-items-center mb-4">
              <h5 id="app-modal-title" style={{ fontWeight: 700, margin: 0 }}>
                {isViewOnly ? `Détails — ${editingApp?.name ?? ''}` : editingApp ? 'Modifier l\'application' : 'Ajouter une application'}
              </h5>
              <button onClick={closeModal} disabled={flow.running} aria-label="Fermer" style={{ background: 'none', border: 'none', fontSize: '20px', cursor: flow.running ? 'not-allowed' : 'pointer', color: 'var(--pt-text-muted)' }}>
                <i className="bi bi-x"></i>
              </button>
            </div>

            {actionError && (
              <div className="pt-alert-banner danger mb-3">
                <i className="bi bi-exclamation-triangle-fill"></i>
                {actionError}
              </div>
            )}

            {!isViewOnly && (flow.running || flow.result) && (
              <div className="mb-3">
                <AuthTestProgress phases={flow.phases} current={flow.phase} result={flow.result} />
                {flow.result && flow.result.status !== 'CONNECTED' && (
                  <div style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', marginTop: '8px' }}>
                    <i className="bi bi-pencil me-1"></i>L'application est enregistrée : corrigez les champs ci-dessous puis cliquez sur « Enregistrer et tester ».
                  </div>
                )}
              </div>
            )}

            {isViewOnly && editingApp ? (
              // Mode Consulter : contenu d'information pur, jamais un formulaire.
              <div className="row g-3">
                <div className="col-12">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Nom de l'application</div>
                  <div style={{ fontSize: '14px', fontWeight: 600 }}>{editingApp.name}</div>
                </div>
                <div className="col-12">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>URL</div>
                  <a href={editingApp.url} target="_blank" rel="noreferrer" style={{ fontSize: '13.5px', fontWeight: 500, color: 'var(--pt-primary)', wordBreak: 'break-all' }}>
                    {editingApp.url} <i className="bi bi-box-arrow-up-right ms-1" style={{ fontSize: '11px' }}></i>
                  </a>
                </div>
                {editingApp.description && (
                  <div className="col-12">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Description</div>
                    <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{editingApp.description}</div>
                  </div>
                )}
                <div className="col-6">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Statut du dernier test</div>
                  {(() => {
                    const pill = statusPillStyle[statusKey(editingApp.status)]
                    return (
                      <span className="pt-pill" style={{ background: pill.bg, color: pill.color, fontSize: '11.5px' }}>
                        <i className={`bi ${pill.icon}`} style={{ fontSize: '10px' }}></i> {pill.label}
                      </span>
                    )
                  })()}
                </div>
                <div className="col-6">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Authentification</div>
                  <div style={{ fontSize: '13.5px', fontWeight: 500 }}>
                    <i className={`bi ${editingApp.auth?.type && editingApp.auth.type !== 'NONE' ? 'bi-shield-lock text-success' : 'bi-unlock'} me-1`}></i>
                    {authTypeLabel(editingApp.auth?.type)}
                    {editingApp.auth?.type === 'LOGIN_TOKEN' && editingApp.auth.loginPath && (
                      <span style={{ color: 'var(--pt-text-muted)' }}> — POST {editingApp.auth.loginPath}</span>
                    )}
                    {editingApp.auth?.type === 'API_KEY' && editingApp.auth.apiKeyName && (
                      <span style={{ color: 'var(--pt-text-muted)' }}> — {editingApp.auth.apiKeyLocation === 'QUERY' ? `?${editingApp.auth.apiKeyName}=` : editingApp.auth.apiKeyName}</span>
                    )}
                    {editingApp.auth?.type === 'BASIC' && editingApp.auth.username && (
                      <span style={{ color: 'var(--pt-text-muted)' }}> — {editingApp.auth.username}</span>
                    )}
                  </div>
                </div>
                <div className="col-12">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Monitoring (CPU / RAM de l’application)</div>
                  <div style={{ fontSize: '13.5px', fontWeight: 500 }}>
                    <i className="bi bi-cpu me-1"></i>{MONITORING_TYPE_LABELS[editingApp.monitoring?.type ?? 'NONE']}
                    {editingApp.monitoring && editingApp.monitoring.type !== 'NONE' && (
                      <span style={{ color: 'var(--pt-text-muted)' }}> — {editingApp.monitoring.url}, toutes les {editingApp.monitoring.intervalSeconds} s</span>
                    )}
                  </div>
                </div>
                <div className="col-12">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Réseau</div>
                  <div style={{ fontSize: '13.5px', fontWeight: 500 }}>
                    <i className="bi bi-diagram-3 me-1"></i>{PROXY_MODE_LABELS[editingApp.network?.proxyMode ?? 'INHERIT']}
                    {editingApp.network?.proxyMode === 'CUSTOM' && (
                      <span style={{ color: 'var(--pt-text-muted)' }}> — {editingApp.network.proxyHost}:{editingApp.network.proxyPort}
                        {editingApp.network.proxyUsername ? `, identifiant ${editingApp.network.proxyUsername}` : ''}</span>
                    )}
                  </div>
                </div>
                {editingApp.statusMessage && (
                  <div className="col-12">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>
                      Résultat{editingApp.statusCheckedAt ? ` (${formatDate(editingApp.statusCheckedAt)})` : ''}
                    </div>
                    <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{editingApp.statusMessage}</div>
                  </div>
                )}
                <div className="col-12">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Date de création</div>
                  <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{formatDate(editingApp.createdAt)}</div>
                </div>
              </div>
            ) : (
            <fieldset disabled={!canWrite || flow.running} style={{ border: 'none', padding: 0, margin: 0 }}>
              <FormTabs label="Sections de l’application" active={activeTab} onChange={(id) => setActiveTab(id as AppTab)} tabs={[
                {
                  id: 'general', label: 'Général', icon: 'bi-app', marker: tabMarkers.general,
                  content: (
                    <div className="row g-3">
                      <div className="col-12">
                        <label htmlFor="app-name" style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Nom de l'application *</label>
                        <input
                          id="app-name"
                          className="pt-form-control"
                          style={{ width: '100%', borderColor: touched.name && nameError ? 'var(--pt-danger)' : undefined }}
                          placeholder="ex: Mon Application Web"
                          value={form.name}
                          onChange={e => setForm(p => ({...p, name: e.target.value}))}
                          onBlur={() => setTouched(t => ({ ...t, name: true }))}
                        />
                        {touched.name && nameError && (
                          <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{nameError}</div>
                        )}
                      </div>
                      <div className="col-12">
                        <label htmlFor="app-url" style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>URL *</label>
                        <input
                          id="app-url"
                          className="pt-form-control"
                          style={{ width: '100%', borderColor: touched.url && urlError ? 'var(--pt-danger)' : undefined }}
                          placeholder="ex: https://app.monsite.com"
                          value={form.url}
                          onChange={e => setForm(p => ({...p, url: e.target.value}))}
                          onBlur={() => setTouched(t => ({ ...t, url: true }))}
                        />
                        {touched.url && urlError && (
                          <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{urlError}</div>
                        )}
                      </div>
                      <div className="col-12">
                        <label htmlFor="app-description" style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                          Description <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span>
                        </label>
                        <input
                          id="app-description"
                          className="pt-form-control"
                          style={{ width: '100%' }}
                          placeholder="ex: Application de test"
                          value={form.description}
                          onChange={e => setForm(p => ({...p, description: e.target.value}))}
                        />
                        {descriptionError && (
                          <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{descriptionError}</div>
                        )}
                      </div>
                    </div>
                  ),
                },
                {
                  id: 'auth', label: 'Authentification', icon: 'bi-shield-lock', marker: tabMarkers.auth,
                  content: (
                    <ApplicationAuthFields
                      value={authForm}
                      onChange={setAuthForm}
                      errors={authErrors}
                      showErrors={showAuthErrors}
                      existing={editingApp?.auth}
                      baseUrl={form.url}
                    />
                  ),
                },
                {
                  id: 'network', label: 'Réseau', icon: 'bi-diagram-3', marker: tabMarkers.network,
                  content: (
                    <NetworkFields
                      value={networkForm}
                      onChange={setNetworkForm}
                      errors={networkErrors}
                      showErrors={showAuthErrors}
                      existing={editingApp?.network}
                    />
                  ),
                },
                {
                  id: 'monitoring', label: 'Monitoring', icon: 'bi-cpu', marker: tabMarkers.monitoring,
                  content: (
                    <MonitoringFields
                      value={monitoringForm}
                      onChange={setMonitoringForm}
                      errors={monitoringErrors}
                      showErrors={showAuthErrors}
                      existing={editingApp?.monitoring}
                      appUrl={form.url}
                      applicationId={editingApp?.id}
                      network={networkFormToRequest(networkForm)}
                    />
                  ),
                },
              ]} />
            </fieldset>
            )}

            <div className="d-flex gap-2 justify-content-end mt-4">
              {isViewOnly ? (
                <button onClick={closeModal} className="pt-btn-outline" style={{ padding: '8px 20px' }}>
                  Fermer
                </button>
              ) : (
                <>
                  <button onClick={closeModal} disabled={flow.running} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: flow.running ? 'not-allowed' : 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                    {flow.result?.status === 'CONNECTED' ? 'Fermer' : 'Annuler'}
                  </button>
                  {canWrite && (
                    <button
                      onClick={handleSaveAndTest}
                      disabled={flow.running || flow.result?.status === 'CONNECTED'}
                      aria-busy={flow.running}
                      style={{
                        background: flow.result?.status === 'CONNECTED' ? 'var(--pt-success)' : 'var(--pt-primary)',
                        opacity: flow.running ? 0.75 : 1,
                        color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px',
                        cursor: flow.running ? 'wait' : 'pointer',
                        fontSize: '13.5px', fontWeight: 600, minWidth: '200px',
                      }}
                    >
                      {flow.running ? (
                        <><span className="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>{flow.phase ? PHASE_LABELS[flow.phase] : 'En cours…'}</>
                      ) : flow.result?.status === 'CONNECTED' ? (
                        <><i className="bi bi-check-circle-fill me-2"></i>Connectée</>
                      ) : editingApp ? (
                        <><i className="bi bi-arrow-repeat me-2"></i>Enregistrer et tester</>
                      ) : (
                        <><i className="bi bi-wifi me-2"></i>Tester et ajouter</>
                      )}
                    </button>
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Suppression / archivage : modale d'impact */}
      {deletion && (
        <DeletionDialog
          subject={deletion.kind === 'single' ? `l'application « ${deletion.app.name} »` : `${deletion.ids.length} application${deletion.ids.length > 1 ? 's' : ''}`}
          confirmText={deletion.kind === 'single' ? deletion.app.name : 'SUPPRIMER'}
          loadImpact={() => deletion.kind === 'single'
            ? applicationsBackendApi.deletionImpact(deletion.app.id)
            : applicationsBackendApi.bulkDeletionImpact(deletion.ids)}
          canPermanent={canDelete}
          archiveAvailable={deletion.kind === 'single' ? !deletion.app.archived : !showArchived}
          onConfirm={confirmDeletion}
          onClose={() => setDeletion(null)}
        />
      )}
    </div>
  )
}

export default Applications
