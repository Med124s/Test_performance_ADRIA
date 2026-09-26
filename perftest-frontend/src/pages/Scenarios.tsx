import React, { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import {
  BackendScenarioRequest,
  BackendScenarioResponse,
  BackendApplicationResponse,
  BackendStepRequest,
  BackendStepResponse,
  BackendHttpMethod,
  BackendExecutionResponse,
} from '../types/backendContracts'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { stepsBackendApi } from '../services/api/stepsBackend'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { ApiError } from '../services/api/httpClient'
import { useApiList } from '../hooks/useApiResource'
import { usePagination } from '../hooks/usePagination'
import Pagination from '../components/Pagination'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { backendToFrontendActiveStatus, backendToFrontendExecutionStatus } from '../utils/statusMapping'
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

const emptyForm = {
  name: '',
  applicationId: '',
  description: '',
  // P0-A — paramètres de charge réels (voir LoadTestSpec côté backend) :
  // chaînes contrôlées par le formulaire, converties en nombres (ou null
  // pour durationSeconds/iterations, tous deux optionnels) dans buildPayload.
  virtualUsers: '1',
  rampUpSeconds: '0',
  durationSeconds: '',
  iterations: '',
  thinkTimeMs: '0',
  // P1-Q Étape B — données CSV optionnelles (variables ${nom} pour le
  // moteur, voir Scenario.csvData/CsvDataSource côté backend). Chaîne vide
  // = aucune donnée (comportement historique inchangé).
  csvData: '',
  // Master prompt final (Lot A) — débit cible optionnel (requêtes/s), voir
  // Scenario.targetRps/PacingGate côté backend. Chaîne vide = aucun pacing.
  targetRps: '',
}

const STEP_METHODS: BackendHttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

const emptyStepForm = {
  name: '', method: 'GET' as BackendHttpMethod, url: '', headers: '', body: '', order: '1', expectedStatus: '',
  // P1-Q Étape B — options par étape, toutes optionnelles (chaîne vide =
  // absente/null envoyé au backend, comportement historique inchangé).
  thinkTimeMs: '', timeoutSeconds: '', followRedirects: '' as '' | 'true' | 'false', assertionBodyContains: '',
  // Master prompt final (Lot B) — capture de variable dynamique depuis la
  // réponse de cette étape (voir Step.captureVariableName/captureJsonPath).
  captureVariableName: '', captureJsonPath: '',
}

/** Traduit une erreur RÉELLE (jamais masquée) en message utilisateur — même
 * politique que Applications.tsx (Phase 17). Le code HTTP prime toujours ;
 * 409/400 réutilisent le message backend tel quel (déjà explicite, ex:
 * "des etapes y sont encore rattachees"). */
function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 400:
        return `Données invalides : ${err.message}`
      case 401:
        return 'Vous devez être connecté (Keycloak) pour effectuer cette action.'
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
  const [searchParams] = useSearchParams()
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

  const { data: scenarios, loading: scenariosLoading, error: scenariosError, refetch: refetchScenarios } =
    useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll())
  const { data: applications } = useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const { data: executions, refetch: refetchExecutions } = useApiList<BackendExecutionResponse>(() => executionsBackendApi.getAll())
  // Étapes de TOUS les scénarios Spring Boot (Phase 19) — sert à afficher un
  // aperçu réel sous le nom du scénario dans la liste.
  const { data: allSteps, refetch: refetchAllSteps } = useApiList<BackendStepResponse>(() => stepsBackendApi.getAll())

  const [searchTerm, setSearchTerm] = useState(initialFilter)
  const [selectedStatus, setSelectedStatus] = useState<'Tous' | 'ACTIVE' | 'INACTIVE'>('Tous')
  const [selectedIds, setSelectedIds] = useState<string[]>([])

  // Modale Détail (lecture seule pour le scénario, mais gestion réelle des
  // étapes Spring Boot depuis cette même modale — voir Phase 19)
  const [selectedScenarioDetail, setSelectedScenarioDetail] = useState<BackendScenarioResponse | null>(null)
  const [showStepsInDetail, setShowStepsInDetail] = useState(false)
  const [detailSteps, setDetailSteps] = useState<BackendStepResponse[]>([])
  const [detailStepsLoading, setDetailStepsLoading] = useState(false)

  // Modale Créer / Modifier Scénario (Spring Boot, métadonnées uniquement)
  const [showModal, setShowModal] = useState(false)
  const [editingScenario, setEditingScenario] = useState<BackendScenarioResponse | null>(null)
  const [form, setForm] = useState(emptyForm)
  const [touched, setTouched] = useState<{
    name?: boolean
    applicationId?: boolean
    virtualUsers?: boolean
    rampUpSeconds?: boolean
    durationSeconds?: boolean
    iterations?: boolean
    thinkTimeMs?: boolean
  }>({})
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  // Confirmation de suppression Scénario
  const [deleteConfirm, setDeleteConfirm] = useState<BackendScenarioResponse | null>(null)
  const [deleting, setDeleting] = useState(false)

  // Modale Créer / Modifier une Étape (Spring Boot) — accessible depuis la
  // modale Détail d'un scénario. Champs alignés sur StepRequest
  // (name/method/url/headers/body/order/expectedStatus + options
  // d'exécution par étape ajoutées en P1-Q Étape B : thinkTimeMs/
  // timeoutSeconds/followRedirects/assertionBodyContains). "headers" reste
  // un texte libre (jamais une structure de liste) — même choix qu'à
  // l'origine, aucune structure enrichie ajoutée pour ce champ précis.
  const [showStepModal, setShowStepModal] = useState(false)
  const [editingStep, setEditingStep] = useState<BackendStepResponse | null>(null)
  const [stepForm, setStepForm] = useState(emptyStepForm)
  const [stepTouched, setStepTouched] = useState<{ name?: boolean; url?: boolean; order?: boolean; expectedStatus?: boolean }>({})
  const [savingStep, setSavingStep] = useState(false)
  const [stepActionError, setStepActionError] = useState<string | null>(null)

  // Confirmation de suppression Étape
  const [deleteStepConfirm, setDeleteStepConfirm] = useState<BackendStepResponse | null>(null)
  const [deletingStep, setDeletingStep] = useState(false)

  const nameError = firstError(
    validateRequired(form.name, 'Le nom du scénario'),
    validateMaxLength(form.name, NAME_MAX_LENGTH, 'Le nom du scénario')
  )
  const descriptionError = validateMaxLength(form.description, DESCRIPTION_MAX_LENGTH, 'La description')

  // P0-A — validation des paramètres de charge, mêmes bornes que
  // ScenarioRequest côté backend (@Min/@Max) : jamais de valeur envoyée que
  // le backend rejetterait de toute façon en 400.
  const virtualUsersNum = Number(form.virtualUsers)
  const virtualUsersError = !form.virtualUsers.trim()
    ? 'Le nombre d\'utilisateurs virtuels est obligatoire.'
    : !Number.isInteger(virtualUsersNum) || virtualUsersNum < 1 || virtualUsersNum > 500
    ? 'Doit être un entier entre 1 et 500.'
    : null
  const rampUpNum = Number(form.rampUpSeconds)
  const rampUpError = !form.rampUpSeconds.trim()
    ? 'Le ramp-up est obligatoire (0 = démarrage immédiat de tous les utilisateurs).'
    : !Number.isInteger(rampUpNum) || rampUpNum < 0
    ? 'Doit être un entier positif ou nul.'
    : null
  const durationNum = form.durationSeconds.trim() ? Number(form.durationSeconds) : null
  const durationError =
    durationNum !== null && (!Number.isInteger(durationNum) || durationNum < 1)
      ? 'Doit être un entier d\'au moins 1 seconde.'
      : null
  const iterationsNum = form.iterations.trim() ? Number(form.iterations) : null
  const iterationsError =
    iterationsNum !== null && (!Number.isInteger(iterationsNum) || iterationsNum < 1)
      ? 'Doit être un entier d\'au moins 1.'
      : null
  const thinkTimeNum = Number(form.thinkTimeMs)
  const thinkTimeError = !form.thinkTimeMs.trim()
    ? 'Le think time est obligatoire (0 = aucune pause entre itérations).'
    : !Number.isInteger(thinkTimeNum) || thinkTimeNum < 0
    ? 'Doit être un entier positif ou nul.'
    : null

  // P1-Q Étape B — données CSV optionnelles, même borne que ScenarioRequest
  // côté backend (@Size(max = 50000)) : jamais une valeur que le backend
  // rejetterait de toute façon en 400.
  const csvDataError = form.csvData.length > 50000 ? 'Les données CSV ne doivent pas dépasser 50000 caractères.' : null

  // Master prompt final (Lot A) — débit cible optionnel, même borne que
  // ScenarioRequest côté backend (@Min(1)) : 0 et les valeurs négatives
  // sont explicitement rejetés (sens invalide pour un débit cible).
  const targetRpsNum = form.targetRps.trim() ? Number(form.targetRps) : null
  const targetRpsError =
    targetRpsNum !== null && (!Number.isInteger(targetRpsNum) || targetRpsNum < 1)
      ? 'Le débit cible (requêtes/s) doit être un entier d\'au moins 1 si renseigné.'
      : null

  const isFormValid =
    !nameError && !descriptionError && !!form.applicationId &&
    !virtualUsersError && !rampUpError && !durationError && !iterationsError && !thinkTimeError && !csvDataError &&
    !targetRpsError

  const stepNameError = validateRequired(stepForm.name, "Le nom de l'étape")
  const stepUrlError = firstError(validateRequired(stepForm.url, 'La ressource'), validateStepUrl(stepForm.url))
  const stepOrderNum = Number(stepForm.order)
  const stepOrderError = !stepForm.order.trim()
    ? "L'ordre est obligatoire."
    : !Number.isInteger(stepOrderNum) || stepOrderNum <= 0
    ? "L'ordre doit être un entier strictement positif."
    : null
  const stepExpectedStatusNum = stepForm.expectedStatus.trim() ? Number(stepForm.expectedStatus) : null
  const stepExpectedStatusError =
    stepExpectedStatusNum !== null && (!Number.isInteger(stepExpectedStatusNum) || stepExpectedStatusNum < 100 || stepExpectedStatusNum > 599)
      ? 'Le code de statut attendu doit être compris entre 100 et 599.'
      : null
  // P1-Q Étape B — options par étape, mêmes bornes que StepRequest côté
  // backend (@Min/@Size) : jamais une valeur que le backend rejetterait de
  // toute façon en 400. Toutes optionnelles (chaîne vide = absente).
  const stepThinkTimeNum = stepForm.thinkTimeMs.trim() ? Number(stepForm.thinkTimeMs) : null
  const stepThinkTimeError =
    stepThinkTimeNum !== null && (!Number.isInteger(stepThinkTimeNum) || stepThinkTimeNum < 0)
      ? 'Doit être un entier positif ou nul.'
      : null
  const stepTimeoutNum = stepForm.timeoutSeconds.trim() ? Number(stepForm.timeoutSeconds) : null
  const stepTimeoutError =
    stepTimeoutNum !== null && (!Number.isInteger(stepTimeoutNum) || stepTimeoutNum < 1)
      ? 'Doit être un entier d\'au moins 1 seconde.'
      : null
  const stepAssertionError =
    stepForm.assertionBodyContains.length > 500 ? "L'assertion ne doit pas dépasser 500 caractères." : null
  // Master prompt final (Lot B) — capture de variable dynamique : les deux
  // champs sont liés (l'un sans l'autre n'a aucun effet côté moteur, voir
  // Step.captureVariableName/captureJsonPath) — on le signale explicitement
  // plutôt que de laisser un champ orphelin silencieusement ignoré.
  const stepCaptureNameError =
    stepForm.captureVariableName.length > 255 ? 'Le nom de variable ne doit pas dépasser 255 caractères.' : null
  const stepCaptureJsonPathError =
    stepForm.captureJsonPath.length > 500 ? 'Le chemin de capture ne doit pas dépasser 500 caractères.' : null
  const stepCaptureIncompleteError = validatePairedFields(
    stepForm.captureVariableName, stepForm.captureJsonPath, 'Le nom de variable et le chemin de capture'
  )
  const isStepFormValid = !stepNameError && !stepUrlError && !stepOrderError && !stepExpectedStatusError &&
    !stepThinkTimeError && !stepTimeoutError && !stepAssertionError &&
    !stepCaptureNameError && !stepCaptureJsonPathError && !stepCaptureIncompleteError

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
      await executionsBackendApi.execute({ scenarioId: scenario.id })
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

  const formatDate = (iso: string) => {
    const d = new Date(iso)
    if (isNaN(d.getTime())) return iso
    return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
  }

  const openAdd = () => {
    setEditingScenario(null)
    setForm(emptyForm)
    setTouched({})
    setActionError(null)
    setShowModal(true)
  }

  const openEdit = (scenario: BackendScenarioResponse) => {
    setEditingScenario(scenario)
    setForm({
      name: scenario.name,
      applicationId: scenario.applicationId,
      description: scenario.description ?? '',
      virtualUsers: String(scenario.virtualUsers),
      rampUpSeconds: String(scenario.rampUpSeconds),
      durationSeconds: scenario.durationSeconds != null ? String(scenario.durationSeconds) : '',
      iterations: scenario.iterations != null ? String(scenario.iterations) : '',
      thinkTimeMs: String(scenario.thinkTimeMs),
      csvData: scenario.csvData ?? '',
      targetRps: scenario.targetRps != null ? String(scenario.targetRps) : '',
    })
    setTouched({})
    setActionError(null)
    setShowModal(true)
  }

  const closeModal = () => {
    setShowModal(false)
  }

  const buildPayload = (): BackendScenarioRequest => ({
    applicationId: form.applicationId,
    name: form.name.trim(),
    description: form.description.trim() || null,
    virtualUsers: virtualUsersNum,
    rampUpSeconds: rampUpNum,
    durationSeconds: durationNum,
    iterations: iterationsNum,
    thinkTimeMs: thinkTimeNum,
    csvData: form.csvData.trim() || null,
    targetRps: targetRpsNum,
  })

  const handleSubmit = async () => {
    // Filet de sécurité (en plus des boutons masqués/fieldset désactivé) :
    // le backend revalide de toute façon (403).
    if (!canWrite) return
    setTouched({ name: true, applicationId: true, virtualUsers: true, rampUpSeconds: true, durationSeconds: true, iterations: true, thinkTimeMs: true })
    if (!isFormValid || saving) return
    setSaving(true)
    setActionError(null)
    try {
      if (editingScenario) {
        const updated = await scenariosBackendApi.update(editingScenario.id, buildPayload())
        showToast(`Scénario « ${updated.name} » modifié avec succès.`, 'success')
      } else {
        const created = await scenariosBackendApi.create(buildPayload())
        showToast(`Scénario « ${created.name} » créé avec succès.`, 'success')
      }
      await refetchScenarios()
      setShowModal(false)
    } catch (err) {
      const message = describeApiError(err, "Erreur lors de l'enregistrement.")
      setActionError(message)
      showToast(message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  // Suppression réelle contre Spring Boot : le backend refuse lui-même
  // (409) si des Steps y sont encore rattachés (voir ScenarioServiceImpl) —
  // plus de cascade-delete des Steps depuis le frontend (c'était une
  // logique spécifique à JSON Server, qui ne cascade jamais rien).
  const handleDeleteScenario = async () => {
    if (!canDelete || !deleteConfirm) return
    setDeleting(true)
    setActionError(null)
    try {
      await scenariosBackendApi.remove(deleteConfirm.id)
      await refetchScenarios()
      setSelectedIds((prev) => prev.filter((id) => id !== deleteConfirm.id))
      showToast(`Scénario « ${deleteConfirm.name} » supprimé avec succès.`, 'success')
      setDeleteConfirm(null)
    } catch (err) {
      const message = describeApiError(err, 'Erreur lors de la suppression.')
      setActionError(message)
      showToast(message, 'danger')
    } finally {
      setDeleting(false)
    }
  }

  const handleSelectAll = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSelectedIds(e.target.checked ? filteredScenarios.map((s) => s.id) : [])
  }

  const handleSelectOne = (id: string) => {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]))
  }

  const filteredScenarios = scenarios.filter((scenario) => {
    const matchesSearch =
      scenario.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      scenario.applicationName.toLowerCase().includes(searchTerm.toLowerCase())
    const matchesStatus = selectedStatus === 'Tous' || scenario.status === selectedStatus
    return matchesSearch && matchesStatus
  })

  const { page, setPage, totalPages, pageItems, startIndex, endIndex, totalItems } = usePagination(filteredScenarios, 10)

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

  const openAddStep = () => {
    if (!selectedScenarioDetail) return
    const maxOrder = detailSteps.reduce((max, s) => Math.max(max, s.order), 0)
    setEditingStep(null)
    setStepForm({ ...emptyStepForm, order: String(maxOrder + 1) })
    setStepTouched({})
    setStepActionError(null)
    setShowStepModal(true)
  }

  const openEditStep = (step: BackendStepResponse) => {
    setEditingStep(step)
    setStepForm({
      name: step.name,
      method: step.method,
      url: step.url,
      headers: step.headers ?? '',
      body: step.body ?? '',
      order: String(step.order),
      expectedStatus: step.expectedStatus != null ? String(step.expectedStatus) : '',
      thinkTimeMs: step.thinkTimeMs != null ? String(step.thinkTimeMs) : '',
      timeoutSeconds: step.timeoutSeconds != null ? String(step.timeoutSeconds) : '',
      followRedirects: step.followRedirects === true ? 'true' : step.followRedirects === false ? 'false' : '',
      assertionBodyContains: step.assertionBodyContains ?? '',
      captureVariableName: step.captureVariableName ?? '',
      captureJsonPath: step.captureJsonPath ?? '',
    })
    setStepTouched({})
    setStepActionError(null)
    setShowStepModal(true)
  }

  const closeStepModal = () => {
    setShowStepModal(false)
  }

  const buildStepPayload = (): BackendStepRequest => ({
    scenarioId: selectedScenarioDetail!.id,
    name: stepForm.name.trim(),
    method: stepForm.method,
    url: stepForm.url.trim(),
    headers: stepForm.headers.trim() || null,
    body: stepForm.body.trim() || null,
    order: stepOrderNum,
    expectedStatus: stepExpectedStatusNum,
    thinkTimeMs: stepThinkTimeNum,
    timeoutSeconds: stepTimeoutNum,
    followRedirects: stepForm.followRedirects === '' ? null : stepForm.followRedirects === 'true',
    assertionBodyContains: stepForm.assertionBodyContains.trim() || null,
    captureVariableName: stepForm.captureVariableName.trim() || null,
    captureJsonPath: stepForm.captureJsonPath.trim() || null,
  })

  // Chaque étape est créée/modifiée par son PROPRE appel PUT/POST vers
  // /api/steps — il n'existe côté backend aucun endpoint transactionnel
  // "Scénario + Steps" (voir Phase 19, section 15) : cette opération est
  // donc volontairement unitaire et explicite (un clic = une étape), jamais
  // une soumission groupée qui pourrait échouer partiellement sans le dire.
  const handleSubmitStep = async () => {
    if (!canWrite || !selectedScenarioDetail) return
    setStepTouched({ name: true, url: true, order: true, expectedStatus: true })
    if (!isStepFormValid || savingStep) return
    setSavingStep(true)
    setStepActionError(null)
    try {
      if (editingStep) {
        await stepsBackendApi.update(editingStep.id, buildStepPayload())
        showToast('Étape modifiée avec succès.', 'success')
      } else {
        await stepsBackendApi.create(buildStepPayload())
        showToast('Étape créée avec succès.', 'success')
      }
      loadDetailSteps(selectedScenarioDetail.id)
      await refetchAllSteps()
      setShowStepModal(false)
    } catch (err) {
      const message = describeApiError(err, "Erreur lors de l'enregistrement de l'étape.")
      setStepActionError(message)
      showToast(message, 'danger')
    } finally {
      setSavingStep(false)
    }
  }

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

              <div className="modal-footer d-flex justify-content-end">
                <button className="pt-btn-outline" onClick={() => { setSelectedScenarioDetail(null); setShowStepsInDetail(false) }}>
                  Fermer
                </button>
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
        <div className="col-6 col-md-4">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Total des scénarios</div>
                <div className="stat-value">{scenariosLoading ? '—' : scenarios.length}</div>
              </div>
              <div className="stat-icon blue"><i className="bi bi-collection-play"></i></div>
            </div>
          </div>
        </div>
        <div className="col-6 col-md-4">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Scénarios actifs</div>
                <div className="stat-value">{scenariosLoading ? '—' : scenarios.filter((s) => s.status === 'ACTIVE').length}</div>
              </div>
              <div className="stat-icon green"><i className="bi bi-check-circle"></i></div>
            </div>
          </div>
        </div>
        <div className="col-6 col-md-4">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Scénarios inactifs</div>
                <div className="stat-value">{scenariosLoading ? '—' : scenarios.filter((s) => s.status === 'INACTIVE').length}</div>
              </div>
              <div className="stat-icon purple"><i className="bi bi-pause-circle"></i></div>
            </div>
          </div>
        </div>
      </div>

      {/* Search + Create */}
      <div className="d-flex align-items-center gap-3 flex-wrap mb-3">
        <div className="pt-search">
          <i className="bi bi-search"></i>
          <input type="text" placeholder="Rechercher par nom ou application..." value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)} />
        </div>
        {canWrite && (
          <button className="pt-btn-primary" onClick={openAdd}>
            <i className="bi bi-plus-lg"></i>
            + Nouveau scénario
          </button>
        )}
      </div>

      {/* Table Card */}
      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <div className="d-flex align-items-center gap-3">
            <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Liste des scénarios</h6>
            <span className="pt-pill neutral" style={{ fontSize: '11px' }}>
              {filteredScenarios.length} sur {scenarios.length}
            </span>
          </div>
          <div className="d-flex align-items-center gap-2 flex-wrap">
            <select
              className="pt-form-control"
              style={{ width: 'auto', fontSize: '12.5px' }}
              value={selectedStatus}
              onChange={(e) => setSelectedStatus(e.target.value as 'Tous' | 'ACTIVE' | 'INACTIVE')}
            >
              <option value="Tous">Tous les statuts</option>
              <option value="ACTIVE">Actif</option>
              <option value="INACTIVE">Inactif</option>
            </select>
          </div>
        </div>

        {scenariosLoading ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement des scénarios...</p>
          </div>
        ) : (
        <div className="pt-table-wrapper">
          <table className="pt-table">
            <thead>
              <tr>
                <th style={{ width: '40px' }}>
                  <input type="checkbox" checked={filteredScenarios.length > 0 && selectedIds.length === filteredScenarios.length} onChange={handleSelectAll} />
                </th>
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
              {filteredScenarios.length === 0 ? (
                <tr>
                  <td colSpan={9} style={{ textAlign: 'center', padding: '2rem' }}>
                    <i className="bi bi-inbox" style={{ fontSize: '28px', color: 'var(--pt-text-muted)' }}></i>
                    <p style={{ color: 'var(--pt-text-muted)', marginTop: '0.5rem', margin: 0 }}>Aucun scénario trouvé</p>
                  </td>
                </tr>
              ) : (
                pageItems.map((scenario) => {
                  const isSelected = selectedIds.includes(scenario.id)
                  const latestExec = latestExecByScenario.get(scenario.id)
                  const scenarioSteps = stepsByScenario.get(scenario.id) ?? []
                  const stepsPreview =
                    scenarioSteps.length === 0
                      ? 'Aucune étape'
                      : scenarioSteps.map((s) => `${s.order}. ${s.method} ${s.url}`).join('  →  ')
                  const displayStatus = backendToFrontendActiveStatus(scenario.status)

                  return (
                    <tr key={scenario.id} style={{ background: isSelected ? 'var(--pt-sidebar-item-hover)' : undefined, cursor: 'pointer' }}>
                      <td onClick={(e) => e.stopPropagation()}>
                        <input type="checkbox" checked={isSelected} onChange={() => handleSelectOne(scenario.id)} />
                      </td>
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
                        <span className={`pt-pill ${displayStatus === 'Actif' ? 'success' : 'neutral'}`}>
                          <i className={`bi ${displayStatus === 'Actif' ? 'bi-check-circle-fill' : 'bi-pause-circle-fill'}`} style={{ fontSize: '11px' }}></i>
                          {displayStatus}
                        </span>
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
                            {backendToFrontendExecutionStatus(latestExec.status)} — {formatDate(latestExec.startedAt)}
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
                          {canWrite && (
                            <button
                              className="topbar-icon"
                              disabled={launchingScenarioId === scenario.id}
                              title={`Exécuter le scénario (${scenario.virtualUsers} VU, ramp-up ${scenario.rampUpSeconds}s${scenario.durationSeconds ? `, durée ${scenario.durationSeconds}s` : scenario.iterations ? `, ${scenario.iterations} itération(s)` : ''}) — asynchrone, suivez la progression sur la page Exécutions`}
                              style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-success)', background: 'var(--pt-success-light)' }}
                              onClick={() => handleExecuteScenario(scenario)}
                            >
                              <i className={`bi ${launchingScenarioId === scenario.id ? 'bi-arrow-repeat pt-spin' : 'bi-play-fill'}`} style={{ fontSize: '14px', color: 'var(--pt-success)' }}></i>
                            </button>
                          )}
                          {canWrite && (
                            <button className="topbar-icon" title="Modifier" style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-primary)', background: 'var(--pt-primary-light)' }} onClick={() => openEdit(scenario)}>
                              <i className="bi bi-pencil" style={{ fontSize: '13px', color: 'var(--pt-primary)' }}></i>
                            </button>
                          )}
                          {canDelete && (
                            <button className="topbar-icon" title="Supprimer" disabled={deleting} style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-danger)', background: 'rgba(220,38,38,0.06)' }} onClick={() => setDeleteConfirm(scenario)}>
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
        />
      </div>

      {/* Add/Edit Modal — Spring Boot, métadonnées uniquement (sans étapes) */}
      {showModal && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '520px', maxWidth: '95vw', maxHeight: '90vh', overflowY: 'auto', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            <div className="d-flex justify-content-between align-items-center mb-4">
              <h5 style={{ fontWeight: 700, margin: 0 }}>
                {editingScenario ? 'Modifier le scénario' : 'Nouveau scénario'}
              </h5>
              <button onClick={closeModal} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)' }}>
                <i className="bi bi-x"></i>
              </button>
            </div>

            {actionError && (
              <div className="pt-alert-banner danger mb-3">
                <i className="bi bi-exclamation-triangle-fill"></i>
                {actionError}
              </div>
            )}

            {!editingScenario && (
              <div className="pt-alert-banner mb-3" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', color: 'var(--pt-text-muted)' }}>
                <i className="bi bi-info-circle-fill"></i>
                Ce scénario sera créé sans étape (les étapes restent gérées séparément, voir Limitations Phase 18).
              </div>
            )}

            <fieldset disabled={!canWrite} className="row g-3" style={{ border: 'none', padding: 0, margin: 0 }}>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Nom du scénario *</label>
                <input
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: touched.name && nameError ? 'var(--pt-danger)' : undefined }}
                  placeholder="ex: Parcours achat complet"
                  value={form.name}
                  onChange={e => setForm(p => ({ ...p, name: e.target.value }))}
                  onBlur={() => setTouched(t => ({ ...t, name: true }))}
                />
                {touched.name && nameError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{nameError}</div>
                )}
              </div>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Application *</label>
                <select
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: touched.applicationId && !form.applicationId ? 'var(--pt-danger)' : undefined }}
                  value={form.applicationId}
                  disabled={!canWrite || !!editingScenario}
                  title={editingScenario ? "L'application d'un scénario ne peut plus être changée une fois créé." : undefined}
                  onChange={e => setForm(p => ({ ...p, applicationId: e.target.value }))}
                  onBlur={() => setTouched(t => ({ ...t, applicationId: true }))}
                >
                  <option value="" disabled>Sélectionner une application…</option>
                  {applications.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
                </select>
                {touched.applicationId && !form.applicationId && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>Veuillez sélectionner une application.</div>
                )}
              </div>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Description <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span>
                </label>
                <textarea
                  className="pt-form-control"
                  rows={2}
                  style={{ width: '100%' }}
                  value={form.description}
                  onChange={e => setForm(p => ({ ...p, description: e.target.value }))}
                ></textarea>
                {descriptionError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{descriptionError}</div>
                )}
              </div>

              {/* P0-A — paramètres réels du moteur de charge (voir LoadTestSpec) :
                  chaque champ ci-dessous est effectivement utilisé par
                  HttpClientExecutionEngine, jamais décoratif. */}
              <div className="col-12">
                <hr style={{ margin: '4px 0 12px', borderColor: 'var(--pt-border)' }} />
                <div style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)', marginBottom: '10px' }}>
                  <i className="bi bi-speedometer2 me-1" style={{ color: 'var(--pt-primary)' }}></i>
                  PARAMÈTRES DE CHARGE
                </div>
              </div>
              <div className="col-6 col-md-3">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Utilisateurs virtuels *</label>
                <input
                  type="number" min={1} max={500}
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: touched.virtualUsers && virtualUsersError ? 'var(--pt-danger)' : undefined }}
                  value={form.virtualUsers}
                  onChange={e => setForm(p => ({ ...p, virtualUsers: e.target.value }))}
                  onBlur={() => setTouched(t => ({ ...t, virtualUsers: true }))}
                />
                {touched.virtualUsers && virtualUsersError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{virtualUsersError}</div>
                )}
              </div>
              <div className="col-6 col-md-3">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Ramp-up (s) *
                </label>
                <input
                  type="number" min={0}
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: touched.rampUpSeconds && rampUpError ? 'var(--pt-danger)' : undefined }}
                  value={form.rampUpSeconds}
                  onChange={e => setForm(p => ({ ...p, rampUpSeconds: e.target.value }))}
                  onBlur={() => setTouched(t => ({ ...t, rampUpSeconds: true }))}
                />
                {touched.rampUpSeconds && rampUpError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{rampUpError}</div>
                )}
              </div>
              <div className="col-6 col-md-3">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Durée (s) <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span>
                </label>
                <input
                  type="number" min={1}
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: touched.durationSeconds && durationError ? 'var(--pt-danger)' : undefined }}
                  placeholder="ex: 60"
                  value={form.durationSeconds}
                  onChange={e => setForm(p => ({ ...p, durationSeconds: e.target.value }))}
                  onBlur={() => setTouched(t => ({ ...t, durationSeconds: true }))}
                />
                {touched.durationSeconds && durationError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{durationError}</div>
                )}
              </div>
              <div className="col-6 col-md-3">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Itérations <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span>
                </label>
                <input
                  type="number" min={1}
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: touched.iterations && iterationsError ? 'var(--pt-danger)' : undefined }}
                  placeholder="ex: 10"
                  value={form.iterations}
                  onChange={e => setForm(p => ({ ...p, iterations: e.target.value }))}
                  onBlur={() => setTouched(t => ({ ...t, iterations: true }))}
                />
                {touched.iterations && iterationsError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{iterationsError}</div>
                )}
              </div>
              {form.durationSeconds.trim() && form.iterations.trim() && (
                <div className="col-12">
                  <div className="pt-alert-banner" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', color: 'var(--pt-text-muted)', fontSize: '11.5px' }}>
                    <i className="bi bi-info-circle-fill"></i>
                    Durée et itérations sont toutes deux configurées : chaque utilisateur virtuel s'arrête à la première condition atteinte (durée écoulée ou nombre d'itérations atteint).
                  </div>
                </div>
              )}
              <div className="col-6 col-md-3">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Think time (ms) *
                </label>
                <input
                  type="number" min={0}
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: touched.thinkTimeMs && thinkTimeError ? 'var(--pt-danger)' : undefined }}
                  value={form.thinkTimeMs}
                  onChange={e => setForm(p => ({ ...p, thinkTimeMs: e.target.value }))}
                  onBlur={() => setTouched(t => ({ ...t, thinkTimeMs: true }))}
                />
                {touched.thinkTimeMs && thinkTimeError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{thinkTimeError}</div>
                )}
              </div>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Données CSV (optionnel)
                </label>
                <textarea
                  className="pt-form-control" rows={3}
                  placeholder={'username,password\nuser1,pass1\nuser2,pass2'}
                  style={{ width: '100%', fontFamily: 'monospace', fontSize: '12.5px', borderColor: csvDataError ? 'var(--pt-danger)' : undefined }}
                  value={form.csvData}
                  onChange={e => setForm(p => ({ ...p, csvData: e.target.value }))}
                />
                {csvDataError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{csvDataError}</div>
                )}
                <div style={{ color: 'var(--pt-text-muted)', fontSize: '11px', marginTop: '4px' }}>
                  Première ligne = noms de variables. Une ligne distribuée par utilisateur virtuel (cyclique si moins de lignes que de VUs) — utilisables dans les étapes via <code>{'${nomColonne}'}</code>.
                </div>
              </div>
              <div className="col-6 col-md-3">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Débit cible (req/s)
                </label>
                <input
                  type="number" min={1}
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: targetRpsError ? 'var(--pt-danger)' : undefined }}
                  placeholder="aucun pacing si vide"
                  value={form.targetRps}
                  onChange={e => setForm(p => ({ ...p, targetRps: e.target.value }))}
                />
                {targetRpsError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{targetRpsError}</div>
                )}
                <div style={{ color: 'var(--pt-text-muted)', fontSize: '11px', marginTop: '4px' }}>
                  Débit approximatif partagé entre tous les utilisateurs virtuels — ne remplace pas leur nombre, le ramp-up ni le think time.
                </div>
              </div>
            </fieldset>

            <div className="d-flex gap-2 justify-content-end mt-4">
              <button onClick={closeModal} disabled={saving} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                Annuler
              </button>
              {canWrite && (
                <button onClick={handleSubmit} disabled={!isFormValid || saving} style={{ background: !isFormValid ? '#93C5FD' : 'var(--pt-primary)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: !isFormValid ? 'not-allowed' : 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                  {saving ? <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Enregistrement...</> : <><i className="bi bi-check2 me-2"></i>Enregistrer</>}
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Delete Confirmation Modal */}
      {deleteConfirm && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '400px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)', textAlign: 'center' }}>
            <div style={{ width: '56px', height: '56px', borderRadius: '50%', background: 'var(--pt-danger-light)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 1rem' }}>
              <i className="bi bi-trash" style={{ fontSize: '24px', color: 'var(--pt-danger)' }}></i>
            </div>
            <h5 style={{ fontWeight: 700, marginBottom: '8px' }}>Supprimer le scénario ?</h5>
            <p style={{ color: 'var(--pt-text-muted)', fontSize: '14px', marginBottom: '1.5rem' }}>
              « {deleteConfirm.name} » sera définitivement supprimé. Refusé si des étapes y sont encore rattachées.
            </p>
            {actionError && (
              <div className="pt-alert-banner danger mb-3" style={{ textAlign: 'left' }}>
                <i className="bi bi-exclamation-triangle-fill"></i>
                {actionError}
              </div>
            )}
            <div className="d-flex gap-2 justify-content-center">
              <button onClick={() => { setDeleteConfirm(null); setActionError(null) }} disabled={deleting} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                Annuler
              </button>
              <button onClick={handleDeleteScenario} disabled={deleting} style={{ background: 'var(--pt-danger)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                {deleting ? 'Suppression...' : 'Supprimer'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Add/Edit Step Modal — Spring Boot (Phase 19), champs limités au contrat réel StepRequest */}
      {showStepModal && selectedScenarioDetail && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1070, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '520px', maxWidth: '95vw', maxHeight: '90vh', overflowY: 'auto', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            <div className="d-flex justify-content-between align-items-center mb-4">
              <h5 style={{ fontWeight: 700, margin: 0 }}>
                {editingStep ? "Modifier l'étape" : 'Nouvelle étape'}
              </h5>
              <button onClick={closeStepModal} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)' }}>
                <i className="bi bi-x"></i>
              </button>
            </div>

            {stepActionError && (
              <div className="pt-alert-banner danger mb-3">
                <i className="bi bi-exclamation-triangle-fill"></i>
                {stepActionError}
              </div>
            )}

            <fieldset disabled={!canWrite} className="row g-3" style={{ border: 'none', padding: 0, margin: 0 }}>
              <div className="col-12 col-md-4">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Méthode *</label>
                <select
                  className="pt-form-control"
                  style={{ width: '100%' }}
                  value={stepForm.method}
                  onChange={e => setStepForm(p => ({ ...p, method: e.target.value as BackendHttpMethod }))}
                >
                  {STEP_METHODS.map(m => <option key={m} value={m}>{m}</option>)}
                </select>
              </div>
              <div className="col-12 col-md-8">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Nom de l'étape *</label>
                <input
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: stepTouched.name && stepNameError ? 'var(--pt-danger)' : undefined }}
                  placeholder="ex: Login utilisateur"
                  value={stepForm.name}
                  onChange={e => setStepForm(p => ({ ...p, name: e.target.value }))}
                  onBlur={() => setStepTouched(t => ({ ...t, name: true }))}
                />
                {stepTouched.name && stepNameError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{stepNameError}</div>
                )}
              </div>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>URL / Ressource *</label>
                <input
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: stepTouched.url && stepUrlError ? 'var(--pt-danger)' : undefined }}
                  placeholder="/api/auth/login"
                  value={stepForm.url}
                  onChange={e => setStepForm(p => ({ ...p, url: e.target.value }))}
                  onBlur={() => setStepTouched(t => ({ ...t, url: true }))}
                />
                {stepTouched.url && stepUrlError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{stepUrlError}</div>
                )}
              </div>
              <div className="col-6 col-md-4">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Ordre *</label>
                <input
                  type="number"
                  min={1}
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: stepTouched.order && stepOrderError ? 'var(--pt-danger)' : undefined }}
                  value={stepForm.order}
                  onChange={e => setStepForm(p => ({ ...p, order: e.target.value }))}
                  onBlur={() => setStepTouched(t => ({ ...t, order: true }))}
                />
                {stepTouched.order && stepOrderError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{stepOrderError}</div>
                )}
              </div>
              <div className="col-6 col-md-8">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Code de statut attendu <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span>
                </label>
                <input
                  type="number"
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: stepTouched.expectedStatus && stepExpectedStatusError ? 'var(--pt-danger)' : undefined }}
                  placeholder="ex: 200"
                  value={stepForm.expectedStatus}
                  onChange={e => setStepForm(p => ({ ...p, expectedStatus: e.target.value }))}
                  onBlur={() => setStepTouched(t => ({ ...t, expectedStatus: true }))}
                />
                {stepTouched.expectedStatus && stepExpectedStatusError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{stepExpectedStatusError}</div>
                )}
              </div>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Headers <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel — texte libre (le backend ne structure pas les headers)</span>
                </label>
                <textarea
                  className="pt-form-control"
                  rows={2}
                  style={{ width: '100%' }}
                  placeholder={'Content-Type: application/json'}
                  value={stepForm.headers}
                  onChange={e => setStepForm(p => ({ ...p, headers: e.target.value }))}
                ></textarea>
              </div>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Body <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel — texte libre</span>
                </label>
                <textarea
                  className="pt-form-control"
                  rows={2}
                  style={{ width: '100%' }}
                  value={stepForm.body}
                  onChange={e => setStepForm(p => ({ ...p, body: e.target.value }))}
                ></textarea>
              </div>
              <div className="col-12">
                <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--pt-text-muted)', margin: '8px 0 4px' }}>
                  Options d'exécution (optionnelles — comportement global inchangé si non renseignées)
                </div>
              </div>
              <div className="col-6 col-md-3">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Think time étape (ms)
                </label>
                <input
                  type="number" min={0}
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: stepThinkTimeError ? 'var(--pt-danger)' : undefined }}
                  placeholder="global si vide"
                  value={stepForm.thinkTimeMs}
                  onChange={e => setStepForm(p => ({ ...p, thinkTimeMs: e.target.value }))}
                />
                {stepThinkTimeError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{stepThinkTimeError}</div>
                )}
              </div>
              <div className="col-6 col-md-3">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Timeout étape (s)
                </label>
                <input
                  type="number" min={1}
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: stepTimeoutError ? 'var(--pt-danger)' : undefined }}
                  placeholder="global si vide"
                  value={stepForm.timeoutSeconds}
                  onChange={e => setStepForm(p => ({ ...p, timeoutSeconds: e.target.value }))}
                />
                {stepTimeoutError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{stepTimeoutError}</div>
                )}
              </div>
              <div className="col-6 col-md-3">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Suivre les redirections
                </label>
                <select
                  className="pt-form-control"
                  style={{ width: '100%' }}
                  value={stepForm.followRedirects}
                  onChange={e => setStepForm(p => ({ ...p, followRedirects: e.target.value as '' | 'true' | 'false' }))}
                >
                  <option value="">Global (par défaut)</option>
                  <option value="true">Oui</option>
                  <option value="false">Non</option>
                </select>
              </div>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Assertion — la réponse doit contenir <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span>
                </label>
                <input
                  type="text"
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: stepAssertionError ? 'var(--pt-danger)' : undefined }}
                  placeholder={'ex: "status":"ok"'}
                  value={stepForm.assertionBodyContains}
                  onChange={e => setStepForm(p => ({ ...p, assertionBodyContains: e.target.value }))}
                />
                {stepAssertionError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{stepAssertionError}</div>
                )}
              </div>
              <div className="col-6">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Capturer une variable — nom <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span>
                </label>
                <input
                  type="text"
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: (stepCaptureNameError || stepCaptureIncompleteError) ? 'var(--pt-danger)' : undefined }}
                  placeholder="ex: token"
                  value={stepForm.captureVariableName}
                  onChange={e => setStepForm(p => ({ ...p, captureVariableName: e.target.value }))}
                />
                {stepCaptureNameError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{stepCaptureNameError}</div>
                )}
              </div>
              <div className="col-6">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Capturer une variable — chemin JSON
                </label>
                <input
                  type="text"
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: (stepCaptureJsonPathError || stepCaptureIncompleteError) ? 'var(--pt-danger)' : undefined }}
                  placeholder={'ex: $.token ou data.token'}
                  value={stepForm.captureJsonPath}
                  onChange={e => setStepForm(p => ({ ...p, captureJsonPath: e.target.value }))}
                />
                {stepCaptureJsonPathError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{stepCaptureJsonPathError}</div>
                )}
              </div>
              {stepCaptureIncompleteError && (
                <div className="col-12">
                  <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px' }}>{stepCaptureIncompleteError}</div>
                </div>
              )}
              <div className="col-12">
                <div style={{ color: 'var(--pt-text-muted)', fontSize: '11px' }}>
                  La valeur capturée est disponible dans les étapes suivantes de ce scénario via <code>{'${nomDeVariable}'}</code> — isolée par utilisateur virtuel (jamais partagée entre eux).
                </div>
              </div>
            </fieldset>

            <div className="d-flex gap-2 justify-content-end mt-4">
              <button onClick={closeStepModal} disabled={savingStep} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                Annuler
              </button>
              {canWrite && (
                <button onClick={handleSubmitStep} disabled={!isStepFormValid || savingStep} style={{ background: !isStepFormValid ? '#93C5FD' : 'var(--pt-primary)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: !isStepFormValid ? 'not-allowed' : 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                  {savingStep ? <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Enregistrement...</> : <><i className="bi bi-check2 me-2"></i>Enregistrer</>}
                </button>
              )}
            </div>
          </div>
        </div>
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
