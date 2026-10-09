import { loadThresholds } from '../../utils/thresholds'
import { loadLoadDefaults } from '../../utils/loadDefaults'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { BlockerFunction, Link, useBlocker, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  BackendApplicationResponse,
  BackendScenarioRequest,
  BackendTestRunResponse,
} from '../../types/backendContracts'
import { applicationsBackendApi } from '../../services/api/applicationsBackend'
import { scenariosBackendApi } from '../../services/api/scenariosBackend'
import { stepsBackendApi } from '../../services/api/stepsBackend'
import { executionsBackendApi } from '../../services/api/executionsBackend'
import { ApiError } from '../../services/api/httpClient'
import { useApiList } from '../../hooks/useApiResource'
import { useAuth } from '../../context/AuthContext'
import { useToast } from '../../context/ToastContext'
import { useExecutionMonitor } from '../../context/ExecutionMonitorContext'
import StepEditor from './StepEditor'
import { authTypeLabel } from '../../components/application-auth/ApplicationAuthFields'
import TestRunPanel, { INITIAL_TEST_RUN, TestRunState } from './TestRunPanel'
import LoadSummaryPanel from './LoadSummaryPanel'
import CsvPanel from './CsvPanel'
import ScenarioVariablesPanel from './ScenarioVariablesPanel'
import { availableVariables, laterCaptures, validateScenarioVariables, variablesToJson } from './variablesModel'
import { analyzeCsv } from './csvModel'
import { EMPTY_INFO, defaultDraftName, fullRequest, isBlankStep, parseDraft, serializeDraft, stateFromBackend, workSnapshot } from './draftModel'
import {
  DEFAULT_LOAD,
  loadFromDefaults,
  limitsOf,
  DraftStep,
  LoadConfig,
  LoadLimits,
  ScenarioInfo,
  TEST_RUN_LOAD,
  describeApiError,
  draftSignature,
  duplicateStep,
  hasContentCheck,
  hasErrors,
  loadToRequest,
  newStep,
  stepToRequest,
  validateCsv,
  validateInfo,
  validateLoad,
  validateThresholds,
  thresholdsToRequest,
  validateStep,
} from './wizardModel'
import './scenarioWizard.css'

// ============================================================
// Assistant "Nouveau scénario" (route /scenarios/new, reprise d'un brouillon :
// /scenarios/new?draft=<id>) — 4 étapes : Informations → Étapes → Tester →
// Charge & résumé.
//
// « Suivant » n'est actif que si l'étape courante est complète (mêmes règles
// que le backend) ; la liste de ce qui manque est affichée sous le bouton.
//
// Tout scénario enregistré par l'assistant est un BROUILLON (DRAFT) jusqu'à
// « Enregistrer » (POST /publish, validation complète côté backend). Quitter
// avec du travail non enregistré (menu, bouton retour, fermeture d'onglet)
// demande confirmation : brouillon, abandon (le scénario créé automatiquement
// par « Tester » est alors supprimé) ou reprise de l'édition.
//
// Le test de validation est un test détaillé (POST /api/scenarios/{id}/test-run :
// 1 utilisateur virtuel, 1 passage, aucune Execution dans l'historique).
// ============================================================

const WIZARD_STEPS = [
  { title: 'Informations', subtitle: 'Application et nom', icon: 'bi-info-circle' },
  { title: 'Étapes', subtitle: 'Requêtes HTTP', icon: 'bi-list-ol' },
  { title: 'Tester', subtitle: 'Vérification réelle', icon: 'bi-play-circle' },
  { title: 'Charge & résumé', subtitle: 'Profil et lancement', icon: 'bi-speedometer2' },
]

/** Empreintes par section (page de modification : badge « Modifié »). Index = section de l'assistant. */
function sectionSnapshots(info: ScenarioInfo, steps: DraftStep[], load: LoadConfig): string[] {
  const { presetId, ...loadValues } = load
  return [
    JSON.stringify([info.applicationId, info.name.trim(), info.description.trim()]),
    JSON.stringify([steps.map(({ uid, backendId, ...rest }) => rest), info.csvData, info.csvDistribution, info.variables]),
    '',
    // Étape « Charge & résumé » : charge, mais aussi détail des requêtes et seuils de réussite (sinon un seuil
    // modifié ne compterait pas comme modification et « Enregistrer » resterait désactivé).
    JSON.stringify([loadValues, info.requestDetailMode ?? null, info.maxErrorRatePercent ?? null, info.maxP95Ms ?? null]),
  ]
}

function ScenarioWizard({ mode = 'create' }: { mode?: 'create' | 'edit' }) {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { id: routeId } = useParams()
  /** Page de modification (/scenarios/:id/edit) : données chargées, navigation libre, enregistrement complet. */
  const isEdit = mode === 'edit' && !!routeId
  const draftId = isEdit ? null : searchParams.get('draft')
  const { rawRoles } = useAuth()
  const { showToast } = useToast()
  const monitor = useExecutionMonitor()
  const canWrite = rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER')

  const { data: applications, loading: appsLoading, error: appsError } =
    useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())

  const initialInfo: ScenarioInfo = { ...EMPTY_INFO, applicationId: searchParams.get('app') ?? '' }
  const [current, setCurrent] = useState(0)
  const [maxReached, setMaxReached] = useState(0)

  const [info, setInfo] = useState<ScenarioInfo>(initialInfo)
  /** Champs « Informations » déjà touchés : leurs erreurs ne s'affichent qu'à partir de là. */
  const [touchedInfo, setTouchedInfo] = useState<{ applicationId?: boolean; name?: boolean }>({})
  const touch = (field: 'applicationId' | 'name') => setTouchedInfo((t) => (t[field] ? t : { ...t, [field]: true }))
  const [steps, setSteps] = useState<DraftStep[]>([])
  const [selectedUid, setSelectedUid] = useState<string | null>(null)
  const [removedBackendIds, setRemovedBackendIds] = useState<string[]>([])
  const [csvFileName, setCsvFileName] = useState<string | null>(null)
  const [dragUid, setDragUid] = useState<string | null>(null)

  const [load, setLoad] = useState<LoadConfig>(DEFAULT_LOAD)
  /** Limites de charge en vigueur (Paramètres > Système), lues depuis le serveur ; null tant qu'inconnues. */
  const [loadLimits, setLoadLimits] = useState<LoadLimits | null>(null)
  const [scenarioId, setScenarioId] = useState<string | null>(null)
  /** Scénario créé automatiquement par « Tester » et jamais enregistré volontairement comme brouillon. */
  const [autoCreated, setAutoCreated] = useState(false)
  const [testRun, setTestRun] = useState<TestRunState>(INITIAL_TEST_RUN)
  const [testedSignature, setTestedSignature] = useState<string | null>(null)
  /** Version qui a réussi le test complet (toutes les étapes PASSED). */
  const [passedSignature, setPassedSignature] = useState<string | null>(null)
  const [finalSaving, setFinalSaving] = useState<null | 'save' | 'launch'>(null)
  const [finalError, setFinalError] = useState<string | null>(null)
  const [draftSaving, setDraftSaving] = useState(false)
  const [leaveError, setLeaveError] = useState<string | null>(null)
  const [resume, setResume] = useState<{ loading: boolean; error: string | null }>({ loading: !!draftId || isEdit, error: null })
  const [baseline, setBaseline] = useState(() => workSnapshot(initialInfo, [], DEFAULT_LOAD))
  /** Page de modification : empreintes enregistrées par section (badge « Modifié »). */
  const [sectionBaseline, setSectionBaseline] = useState<string[] | null>(null)

  const mountedRef = useRef(true)
  // Remis à true à chaque montage : StrictMode (dev) monte/démonte/remonte
  // le composant, et un ref resté à false stopperait le suivi du test.
  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false }
  }, [])

  const application = applications.find((a) => a.id === info.applicationId)
  const baseUrl = application?.url ?? ''
  const authTokenAvailable = application?.auth?.type === 'LOGIN_TOKEN'

  const infoErrors = useMemo(() => validateInfo(info), [info])
  const csvError = validateCsv(info.csvData)
  const csvAnalysis = useMemo(() => analyzeCsv(info.csvData), [info.csvData])
  const csvColumns = csvAnalysis.error ? [] : csvAnalysis.columns
  const scenarioVariableErrors = useMemo(() => validateScenarioVariables(info.variables, csvColumns).errors, [info.variables, csvColumns])
  const scenarioVariableNames = info.variables.map((v) => v.key.trim()).filter(Boolean)
  const stepErrors = useMemo(() => Object.fromEntries(steps.map((s) => [s.uid, validateStep(s)])), [steps])
  const loadErrors = useMemo(() => ({ ...validateLoad(load, loadLimits), ...validateThresholds(info) }),
    [load, loadLimits, info.maxErrorRatePercent, info.maxP95Ms]) // eslint-disable-line react-hooks/exhaustive-deps

  const signature = draftSignature(info, steps)
  const testStale = testedSignature !== null && testedSignature !== signature
  const testPassed = passedSignature !== null && passedSignature === signature
  const selectedIndex = steps.findIndex((s) => s.uid === selectedUid)
  const selectedStep = selectedIndex >= 0 ? steps[selectedIndex] : null

  // ------------------------------------------------------------ ce qui manque (bouton « Suivant »)

  const missing: string[][] = useMemo(() => {
    const infoMissing = [infoErrors.applicationId, infoErrors.name, infoErrors.description].filter(Boolean) as string[]
    const stepsMissing: string[] = steps.length === 0 ? ['Ajoutez au moins une étape.'] : []
    steps.forEach((s, i) => {
      const first = Object.values(stepErrors[s.uid] ?? {}).find(Boolean)
      if (first) stepsMissing.push(`Étape ${i + 1}${s.name.trim() ? ` « ${s.name.trim()} »` : ''} : ${first}`)
    })
    if (csvError) stepsMissing.push(`CSV : ${csvError}`)
    stepsMissing.push(...scenarioVariableErrors)
    const results = steps.map((s) => testRun.resultsByUid[s.uid])
    const failed = results.filter((r) => r && r.status !== 'PASSED').length
    const testMissing = testPassed
      ? []
      : testStale
        ? ['Le scénario a été modifié depuis le test : relancez-le.']
        : failed > 0 && testRun.phase === 'done'
          ? [`Corrigez ${failed} étape(s) en échec, puis relancez le test.`]
          : ['Lancez le test : chaque étape doit réussir.']
    const loadMissing = Object.values(loadErrors).filter(Boolean) as string[]
    return [infoMissing, stepsMissing, testMissing, loadMissing]
  }, [infoErrors, steps, stepErrors, csvError, scenarioVariableErrors, testRun, testPassed, testStale, loadErrors])
  const stepValid = missing.map((m) => m.length === 0)

  // ------------------------------------------------------------ seuils proposés (nouveau scénario)

  // Nouveau scénario : seuils de réussite pré-remplis avec les « Seuils par défaut » (Paramètres >
  // Système), tant que l'utilisateur ne les a pas modifiés. Jamais pour un brouillon ni une modification.
  useEffect(() => {
    if (isEdit || draftId) return
    let cancelled = false
    loadThresholds().then((t) => {
      if (cancelled) return
      const suggested = { maxErrorRatePercent: String(t.maxErrorRatePercent).replace('.', ','), maxP95Ms: t.maxP95Ms > 0 ? String(t.maxP95Ms) : '' }
      const untouched = (p: ScenarioInfo) => p.maxErrorRatePercent === EMPTY_INFO.maxErrorRatePercent && p.maxP95Ms === EMPTY_INFO.maxP95Ms
      setInfo((p) => (untouched(p) ? { ...p, ...suggested } : p))
      // Référence « rien à enregistrer » : workSnapshot = [info, étapes, charge].
      setBaseline((b) => {
        const [baseInfo, ...rest] = JSON.parse(b) as [ScenarioInfo, ...unknown[]]
        return baseInfo && untouched(baseInfo) ? JSON.stringify([{ ...baseInfo, ...suggested }, ...rest]) : b
      })
    })
    return () => { cancelled = true }
  }, [isEdit, draftId])

  // ------------------------------------------------------------ charge par défaut

  // Limite d'utilisateurs du serveur (validation, tous modes) ; nouveau scénario : charge
  // pré-remplie avec la « Charge par défaut » (Paramètres > Système), preset « Personnalisé »,
  // tant que l'utilisateur n'y a pas touché. Jamais pour un brouillon ni une modification.
  useEffect(() => {
    let cancelled = false
    loadLoadDefaults().then((d) => {
      if (cancelled || !d) return
      setLoadLimits(limitsOf(d))
      if (isEdit || draftId) return
      const suggested = loadFromDefaults(d)
      const untouched = (l: LoadConfig) => JSON.stringify(l) === JSON.stringify(DEFAULT_LOAD)
      setLoad((l) => (untouched(l) ? suggested : l))
      setBaseline((b) => {
        const parsed = JSON.parse(b) as unknown[]
        return untouched(parsed[2] as LoadConfig) ? JSON.stringify([parsed[0], parsed[1], suggested]) : b
      })
    })
    return () => { cancelled = true }
  }, [isEdit, draftId])

  // ------------------------------------------------------------ reprise d'un brouillon

  useEffect(() => {
    if (!draftId) return
    let cancelled = false
    ;(async () => {
      try {
        const scenario = await scenariosBackendApi.getById(draftId)
        if (scenario.status !== 'DRAFT') throw new Error('Ce scénario n’est plus un brouillon : il a déjà été enregistré.')
        const backendSteps = await stepsBackendApi.getByScenario(draftId)
        const state = parseDraft(scenario.draftState, scenario) ?? stateFromBackend(scenario, backendSteps)
        const existing = new Set(backendSteps.map((s) => s.id))
        const restored = state.steps.map((s) => (s.backendId && !existing.has(s.backendId) ? { ...s, backendId: null } : s))
        const referenced = new Set(restored.map((s) => s.backendId).filter(Boolean))
        if (cancelled) return
        const step = Math.min(3, Math.max(0, scenario.wizardStep ?? state.maxReached))
        setInfo(state.info)
        setSteps(restored)
        setLoad(state.load)
        setCsvFileName(state.csvFileName)
        setPassedSignature(state.testedSignature)
        setRemovedBackendIds(backendSteps.filter((s) => !referenced.has(s.id)).map((s) => s.id))
        setScenarioId(draftId)
        setSelectedUid(restored[0]?.uid ?? null)
        setCurrent(step)
        setMaxReached(Math.max(state.maxReached, step))
        setBaseline(workSnapshot(state.info, restored, state.load))
        setResume({ loading: false, error: null })
      } catch (err) {
        if (!cancelled) setResume({ loading: false, error: describeApiError(err, 'Brouillon introuvable.') })
      }
    })()
    return () => { cancelled = true }
  }, [draftId])

  // ------------------------------------------------------------ page de modification : chargement

  useEffect(() => {
    if (!isEdit || !routeId) return
    let cancelled = false
    ;(async () => {
      try {
        const scenario = await scenariosBackendApi.getById(routeId)
        if (scenario.status === 'DRAFT') {
          // Un brouillon se termine dans l'assistant (« Reprendre »).
          navigate(`/scenarios/new?draft=${routeId}`, { replace: true })
          return
        }
        const backendSteps = [...await stepsBackendApi.getByScenario(routeId)].sort((a, b) => a.order - b.order)
        const state = stateFromBackend(scenario, backendSteps)
        if (cancelled) return
        setInfo(state.info)
        setSteps(state.steps)
        setLoad(state.load)
        setScenarioId(routeId)
        setSelectedUid(state.steps[0]?.uid ?? null)
        setCurrent(0)
        setMaxReached(3)
        setBaseline(workSnapshot(state.info, state.steps, state.load))
        setSectionBaseline(sectionSnapshots(state.info, state.steps, state.load))
        setResume({ loading: false, error: null })
      } catch (err) {
        if (cancelled) return
        setResume({ loading: false, error: err instanceof ApiError && err.status === 404
          ? 'Ce scénario n’existe pas ou a été supprimé.'
          : describeApiError(err, 'Chargement du scénario impossible.') })
      }
    })()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isEdit, routeId])

  const currentSections = sectionSnapshots(info, steps, load)
  const modifiedSections = currentSections.map((s, i) => !!sectionBaseline && i !== 2 && s !== sectionBaseline[i])
  /** Enregistrable (page de modification) : informations, étapes et charge valides ; le test n'est pas exigé. */
  const savable = stepValid[0] && stepValid[1] && stepValid[3]

  // ------------------------------------------------------------ quitter sans perdre son travail

  const dirty = isEdit ? modifiedSections.some(Boolean) : workSnapshot(info, steps, load) !== baseline
  const needsConfirm = !resume.loading && (dirty || autoCreated)
  const allowLeaveRef = useRef(false)
  const needsConfirmRef = useRef(needsConfirm)
  needsConfirmRef.current = needsConfirm && !allowLeaveRef.current

  const blocker = useBlocker(useCallback<BlockerFunction>(
    ({ currentLocation, nextLocation }) => needsConfirmRef.current
      && (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search),
    [],
  ))

  useEffect(() => {
    if (!needsConfirm) return
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [needsConfirm])

  const leaveNow = () => {
    allowLeaveRef.current = true
    needsConfirmRef.current = false
    if (blocker.state === 'blocked') blocker.proceed()
    else navigate('/scenarios')
  }

  // ------------------------------------------------------------ navigation

  const goTo = (target: number) => {
    // Page de modification : navigation libre entre les sections.
    if (isEdit || target <= current) {
      setCurrent(target)
      return
    }
    for (let i = current; i < target; i++) {
      if (!stepValid[i]) {
        setCurrent(i)
        return
      }
    }
    setCurrent(target)
    setMaxReached((m) => Math.max(m, target))
  }

  const next = () => {
    if (stepValid[current]) goTo(current + 1)
  }

  // ------------------------------------------------------------ steps list

  const addStep = () => {
    const step = newStep()
    setSteps((prev) => [...prev, step])
    setSelectedUid(step.uid)
  }

  // L'écran Étapes s'ouvre directement sur une étape vide à remplir.
  useEffect(() => {
    if (current === 1 && steps.length === 0 && !resume.loading) addStep()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, steps.length, resume.loading])

  const updateStep = (uid: string, patch: Partial<DraftStep>) =>
    setSteps((prev) => prev.map((s) => (s.uid === uid ? { ...s, ...patch } : s)))

  const removeStep = (uid: string) => {
    const idx = steps.findIndex((s) => s.uid === uid)
    const step = steps[idx]
    if (step?.backendId) setRemovedBackendIds((prev) => [...prev, step.backendId!])
    const remaining = steps.filter((s) => s.uid !== uid)
    setSteps(remaining)
    if (selectedUid === uid) setSelectedUid(remaining[Math.min(idx, remaining.length - 1)]?.uid ?? null)
  }

  const copyStep = (uid: string) => {
    const idx = steps.findIndex((s) => s.uid === uid)
    const copy = duplicateStep(steps[idx])
    setSteps((prev) => [...prev.slice(0, idx + 1), copy, ...prev.slice(idx + 1)])
    setSelectedUid(copy.uid)
  }

  const moveStep = (uid: string, toIndex: number) => {
    setSteps((prev) => {
      const from = prev.findIndex((s) => s.uid === uid)
      if (from < 0 || toIndex < 0 || toIndex >= prev.length || from === toIndex) return prev
      const copy = [...prev]
      const [item] = copy.splice(from, 1)
      copy.splice(toIndex, 0, item)
      return copy
    })
  }

  // ------------------------------------------------------------ persistence

  /** Crée (en BROUILLON) ou met à jour le Scénario puis synchronise ses Steps
   * (ordre = position dans la liste). Renvoie l'id du scénario et la correspondance uid → id. */
  const persist = async (loadPart: typeof TEST_RUN_LOAD) => {
    const payload: BackendScenarioRequest = {
      applicationId: info.applicationId,
      name: info.name.trim(),
      description: info.description.trim() || null,
      csvData: info.csvData.trim() || null,
      ...loadPart,
      draft: true,
      variables: variablesToJson(info.variables),
      csvDistribution: info.csvDistribution,
      requestDetailMode: info.requestDetailMode ?? null,
      ...thresholdsToRequest(info),
    }
    let id = scenarioId
    if (id) {
      await scenariosBackendApi.update(id, payload)
    } else {
      id = (await scenariosBackendApi.create(payload)).id
      setScenarioId(id)
      setAutoCreated(true)
    }

    for (const backendId of removedBackendIds) {
      try {
        await stepsBackendApi.remove(backendId)
      } catch (err) {
        if (!(err instanceof ApiError && err.status === 404)) throw err
      }
    }
    setRemovedBackendIds([])

    const idByUid: Record<string, string> = {}
    try {
      for (let i = 0; i < steps.length; i++) {
        const step = steps[i]
        const request = stepToRequest(step, id, i + 1)
        if (step.backendId) {
          await stepsBackendApi.update(step.backendId, request)
          idByUid[step.uid] = step.backendId
        } else {
          idByUid[step.uid] = (await stepsBackendApi.create(request)).id
        }
      }
    } finally {
      // Même en cas d'échec partiel, les étapes déjà créées gardent leur id
      // (jamais recréées en double au prochain enregistrement).
      setSteps((prev) => prev.map((s) => (idByUid[s.uid] && !s.backendId ? { ...s, backendId: idByUid[s.uid] } : s)))
    }
    return { scenarioId: id, idByUid }
  }

  /** « Enregistrer comme brouillon » : tout ce qui est saisi (étapes incomplètes comprises) + l'étape atteinte. */
  const saveDraft = async () => {
    if (!info.applicationId) throw new Error('Choisissez d’abord l’application à tester pour pouvoir enregistrer un brouillon.')
    const name = info.name.trim() || defaultDraftName()
    const savedInfo = { ...info, name }
    const csvValid = !validateCsv(info.csvData)
    const payload: BackendScenarioRequest = {
      applicationId: info.applicationId,
      name: name.slice(0, 255),
      description: info.description.trim().slice(0, 1000) || null,
      csvData: csvValid ? info.csvData.trim() || null : null,
      ...(hasErrors(loadErrors) ? TEST_RUN_LOAD : loadToRequest(load)),
      draft: true,
      // Variables invalides : conservées dans l'état du brouillon, base inchangée.
      variables: scenarioVariableErrors.length ? null : variablesToJson(info.variables),
      csvDistribution: info.csvDistribution,
      requestDetailMode: info.requestDetailMode ?? null,
      ...thresholdsToRequest(info),
      wizardStep: current,
      draftState: serializeDraft({ info: savedInfo, steps, load, maxReached: Math.max(maxReached, current), csvFileName, testedSignature: testPassed ? signature : null }),
    }
    const id = scenarioId
      ? (await scenariosBackendApi.update(scenarioId, payload)).id
      : (await scenariosBackendApi.create(payload)).id
    setScenarioId(id)
    setAutoCreated(false)
    if (!info.name.trim()) setInfo(savedInfo)
    setBaseline(workSnapshot(savedInfo, steps, load))
    return name
  }

  const saveDraftAndStay = async () => {
    setDraftSaving(true)
    try {
      const name = await saveDraft()
      showToast(`Brouillon « ${name} » enregistré.`, 'success')
    } catch (err) {
      showToast(describeApiError(err, 'Enregistrement du brouillon impossible.'), 'danger')
    } finally {
      if (mountedRef.current) setDraftSaving(false)
    }
  }

  const saveDraftAndLeave = async () => {
    setDraftSaving(true)
    setLeaveError(null)
    try {
      const name = await saveDraft()
      showToast(`Brouillon « ${name} » enregistré. Reprenez-le depuis la liste des scénarios.`, 'success')
      leaveNow()
    } catch (err) {
      setLeaveError(describeApiError(err, 'Enregistrement du brouillon impossible.'))
    } finally {
      if (mountedRef.current) setDraftSaving(false)
    }
  }

  const quitWithoutSaving = async () => {
    setLeaveError(null)
    if (autoCreated && scenarioId) {
      setDraftSaving(true)
      try {
        await scenariosBackendApi.removeDraft(scenarioId)
      } catch (err) {
        if (!(err instanceof ApiError && err.status === 404)) {
          setLeaveError(describeApiError(err, 'Suppression du scénario créé pendant le test impossible.'))
          setDraftSaving(false)
          return
        }
      }
      setDraftSaving(false)
    }
    leaveNow()
  }

  /** Test détaillé de tout le scénario (POST /api/scenarios/{id}/test-run, flux en direct) :
   * 1 utilisateur virtuel, 1 passage, aucune exécution dans l'historique. */
  const runTest = async () => {
    if (!stepValid[0] || !stepValid[1]) {
      showToast('Complétez les informations et les étapes avant de tester.', 'warning')
      return
    }
    const sig = signature
    const orderedUids = steps.map((s) => s.uid)
    setTestRun({ ...INITIAL_TEST_RUN, phase: 'saving', scope: 'all' })
    let ids: { scenarioId: string; idByUid: Record<string, string> }
    if (isEdit && scenarioId) {
      // Page de modification : on teste la version affichée SANS l'enregistrer.
      ids = { scenarioId, idByUid: {} }
    } else {
      try {
        ids = await persist(TEST_RUN_LOAD)
      } catch (err) {
        setTestRun({ ...INITIAL_TEST_RUN, phase: 'error', errorMessage: describeApiError(err, 'Enregistrement impossible.') })
        return
      }
    }

    const uidByBackendId = Object.fromEntries(Object.entries(ids.idByUid).map(([uid, bid]) => [bid, uid]))
    const uidOf = (index: number, stepId?: string | null) => (!isEdit && stepId && uidByBackendId[stepId]) || orderedUids[index - 1] || null
    const final: { result: BackendTestRunResponse | null } = { result: null }
    const draft = isEdit ? fullRequest(ids.scenarioId, info, steps, load) : null
    try {
      setTestRun((t) => ({ ...t, phase: 'running' }))
      const stream = draft
        ? (cb: Parameters<typeof scenariosBackendApi.testRunStream>[1]) => scenariosBackendApi.testRunDraftStream(ids.scenarioId, draft, cb)
        : (cb: Parameters<typeof scenariosBackendApi.testRunStream>[1]) => scenariosBackendApi.testRunStream(ids.scenarioId, cb)
      await stream((event) => {
        if (!mountedRef.current) return
        if (event.type === 'auth') {
          setTestRun((t) => ({ ...t, auth: event.auth }))
        } else if (event.type === 'step-started') {
          setTestRun((t) => ({ ...t, runningUid: uidOf(event.index), runningLabel: event.name }))
        } else if (event.type === 'step') {
          const uid = uidOf(event.step.index, event.step.stepId)
          if (uid) setTestRun((t) => ({ ...t, resultsByUid: { ...t.resultsByUid, [uid]: event.step } }))
        } else if (event.type === 'done') {
          final.result = event.result
        }
      })
      if (!mountedRef.current) return
      const result = final.result
      if (!result) throw new Error('Le test s’est interrompu avant la fin (connexion au serveur LoadPilot perdue ?).')
      setTestRun((t) => ({
        ...t,
        phase: 'done',
        runningUid: null,
        runningLabel: null,
        auth: result.auth,
        durationMs: result.totalDurationMs,
        timedOut: result.timedOut,
        variables: result.variables,
      }))
      setTestedSignature(sig)
      setPassedSignature(result.success ? sig : null)
    } catch (err) {
      if (!mountedRef.current) return
      setTestRun((t) => ({ ...t, phase: 'error', runningUid: null, runningLabel: null, errorMessage: describeApiError(err, 'Le test a échoué.') }))
    }
  }

  /** « Exécuter » une seule étape (POST /api/steps/{id}/test-run), avec les variables
   * capturées lors du dernier test (ex. transferId). */
  const runSingleStep = async (uid: string) => {
    if (!stepValid[0] || !stepValid[1]) {
      showToast('Complétez les informations et les étapes avant de tester.', 'warning')
      return
    }
    const step = steps.find((s) => s.uid === uid)
    if (!step) return
    const knownVariables = testRun.variables
    if (isEdit && scenarioId) {
      // Version non enregistrée : étapes 1..n jouées pour disposer des captures précédentes.
      const position = steps.findIndex((s) => s.uid === uid) + 1
      const orderedUids = steps.map((s) => s.uid)
      setTestRun((t) => ({ ...t, phase: 'running', scope: 'step', errorMessage: null, runningUid: uid, runningLabel: step.name || 'Étape' }))
      try {
        const result = await scenariosBackendApi.testRunDraft(scenarioId, fullRequest(scenarioId, info, steps, load), position)
        if (!mountedRef.current) return
        setTestRun((t) => {
          const resultsByUid = { ...t.resultsByUid }
          result.steps.forEach((r) => { if (orderedUids[r.index - 1]) resultsByUid[orderedUids[r.index - 1]] = r })
          return { ...t, phase: 'done', runningUid: null, runningLabel: null, auth: result.auth, resultsByUid, variables: { ...t.variables, ...result.variables } }
        })
      } catch (err) {
        if (!mountedRef.current) return
        setTestRun((t) => ({ ...t, phase: 'error', runningUid: null, runningLabel: null, errorMessage: describeApiError(err, 'Le test de l’étape a échoué.') }))
      }
      return
    }
    setTestRun((t) => ({ ...t, phase: 'saving', scope: 'step', errorMessage: null }))
    let ids: { scenarioId: string; idByUid: Record<string, string> }
    try {
      ids = await persist(TEST_RUN_LOAD)
    } catch (err) {
      setTestRun((t) => ({ ...t, phase: 'error', errorMessage: describeApiError(err, 'Enregistrement impossible.') }))
      return
    }
    try {
      setTestRun((t) => ({ ...t, phase: 'running', runningUid: uid, runningLabel: step.name || 'Étape' }))
      const result = await stepsBackendApi.testRun(ids.idByUid[uid], knownVariables)
      if (!mountedRef.current) return
      setTestRun((t) => ({
        ...t,
        phase: 'done',
        runningUid: null,
        runningLabel: null,
        auth: result.auth,
        resultsByUid: result.steps[0] ? { ...t.resultsByUid, [uid]: result.steps[0] } : t.resultsByUid,
        variables: { ...t.variables, ...result.variables },
      }))
    } catch (err) {
      if (!mountedRef.current) return
      setTestRun((t) => ({ ...t, phase: 'error', runningUid: null, runningLabel: null, errorMessage: describeApiError(err, 'Le test de l’étape a échoué.') }))
    }
  }

  /** Fin de l'assistant : synchronisation, publication (brouillon → actif, validé par le backend), lancement éventuel. */
  const finish = async (launch: boolean) => {
    const firstInvalid = stepValid.findIndex((ok) => !ok)
    if (firstInvalid >= 0) {
      setCurrent(firstInvalid)
      return
    }
    setFinalSaving(launch ? 'launch' : 'save')
    setFinalError(null)
    try {
      const { scenarioId: id } = await persist(loadToRequest(load))
      await scenariosBackendApi.publish(id)
      allowLeaveRef.current = true
      needsConfirmRef.current = false
      if (launch) {
        const execution = await executionsBackendApi.execute({ scenarioId: id })
        monitor.track(execution.id, info.name.trim())
        showToast(`Scénario « ${info.name.trim()} » enregistré et lancé.`, 'success')
        navigate(`/executions/live/${execution.id}`)
      } else {
        showToast(`Scénario « ${info.name.trim()} » enregistré.`, 'success')
        navigate('/scenarios')
      }
    } catch (err) {
      allowLeaveRef.current = false
      if (mountedRef.current) setFinalError(describeApiError(err, 'Enregistrement impossible.'))
    } finally {
      if (mountedRef.current) setFinalSaving(null)
    }
  }

  /**
   * Page de modification : PUT /api/scenarios/{id}/full (informations + étapes en une transaction),
   * puis lancement éventuel. Le test n'est pas exigé pour enregistrer.
   */
  const saveEdits = async (launch: boolean, thenLeave = false) => {
    if (!scenarioId) return
    const firstInvalid = [0, 1, 3].find((i) => !stepValid[i])
    if (firstInvalid !== undefined) {
      setCurrent(firstInvalid)
      showToast('Corrigez les champs signalés avant d’enregistrer.', 'warning')
      return
    }
    setFinalSaving(launch ? 'launch' : 'save')
    setFinalError(null)
    try {
      if (dirty) {
        const saved = await scenariosBackendApi.updateFull(scenarioId, fullRequest(scenarioId, info, steps, load))
        const updatedSteps = steps.map((s, i) => ({ ...s, backendId: saved.steps[i]?.id ?? s.backendId }))
        setSteps(updatedSteps)
        setRemovedBackendIds([])
        setBaseline(workSnapshot(info, updatedSteps, load))
        setSectionBaseline(sectionSnapshots(info, updatedSteps, load))
      }
      if (launch) {
        allowLeaveRef.current = true
        needsConfirmRef.current = false
        const execution = await executionsBackendApi.execute({ scenarioId })
        monitor.track(execution.id, info.name.trim())
        showToast(`Scénario « ${info.name.trim()} » ${dirty ? 'enregistré et ' : ''}lancé.`, 'success')
        navigate(`/executions/live/${execution.id}`)
      } else {
        showToast(`Modifications de « ${info.name.trim()} » enregistrées.`, 'success')
        if (thenLeave) leaveNow()
      }
    } catch (err) {
      allowLeaveRef.current = false
      const message = describeApiError(err, 'Enregistrement impossible.')
      if (mountedRef.current) setFinalError(message)
      showToast(message, 'danger')
    } finally {
      if (mountedRef.current) setFinalSaving(null)
    }
  }

  // ------------------------------------------------------------ render

  if (!canWrite) {
    return (
      <div className="pt-content">
        <div className="pt-card sw-empty">
          <i className="bi bi-lock"></i>
          <h2>Création réservée</h2>
          <p>Seuls les rôles Administrateur et Performance Engineer peuvent créer des scénarios.</p>
          <Link to="/scenarios" className="pt-btn-outline">Retour aux scénarios</Link>
        </div>
      </div>
    )
  }

  if (resume.loading && isEdit) {
    return (
      <div className="pt-content sw-root" role="status" aria-label="Chargement du scénario">
        <div className="sw-skeleton sw-skeleton-title"></div>
        <div className="sw-skeleton sw-skeleton-line"></div>
        <div className="sw-skeleton sw-skeleton-stepper"></div>
        <div className="sw-skeleton sw-skeleton-card"></div>
      </div>
    )
  }

  if (resume.loading || resume.error) {
    return (
      <div className="pt-content">
        <div className="pt-card sw-empty">
          {resume.loading ? (
            <><span className="spinner-border text-primary"></span><p>Chargement du brouillon…</p></>
          ) : (
            <>
              <i className="bi bi-exclamation-octagon"></i>
              <h2>{isEdit ? 'Scénario introuvable' : 'Reprise impossible'}</h2>
              <p>{resume.error}</p>
              <Link to="/scenarios" className="pt-btn-outline">Retour aux scénarios</Link>
            </>
          )}
        </div>
      </div>
    )
  }

  const busy = testRun.phase === 'saving' || testRun.phase === 'running' || finalSaving !== null || draftSaving
  const allValid = stepValid.every(Boolean)

  return (
    <div className="pt-content sw-root">
      <div className="sw-header">
        <button type="button" className="sw-back" onClick={() => navigate('/scenarios')}>
          <i className="bi bi-arrow-left"></i> Scénarios
        </button>
        {isEdit ? (
          <>
            <h1>Modifier le scénario</h1>
            <p>
              <strong>{info.name || 'Sans nom'}</strong> · {application?.name ?? 'Application'}
              {dirty && <span className="sw-modified-pill ms-2">Modifications non enregistrées</span>}
            </p>
          </>
        ) : (
          <>
            <h1>{draftId ? 'Reprendre le brouillon' : 'Nouveau scénario'}</h1>
            <p>Décrivez le parcours à tester, vérifiez-le, puis choisissez la charge.</p>
          </>
        )}
      </div>

      <nav className="sw-stepper" aria-label={isEdit ? 'Sections' : 'Progression'}>
        {WIZARD_STEPS.map((s, i) => {
          const done = !isEdit && (i < current || (i <= maxReached && i !== current && stepValid[i]))
          const reachable = isEdit || i <= current || stepValid.slice(0, i).every(Boolean)
          return (
            <button
              key={s.title}
              type="button"
              className={`sw-stepper-item ${i === current ? 'active' : ''} ${done ? 'done' : ''}`}
              onClick={() => goTo(i)}
              disabled={!reachable || busy}
              aria-current={i === current ? 'step' : undefined}
            >
              <span className="sw-stepper-dot">{done ? <i className="bi bi-check-lg"></i> : i + 1}</span>
              <span className="sw-stepper-text">
                <span className="sw-stepper-title">
                  {s.title}
                  {isEdit && modifiedSections[i] && <span className="sw-modified-pill ms-1">Modifié</span>}
                  {isEdit && i !== 2 && !stepValid[i] && <i className="bi bi-exclamation-circle-fill text-danger ms-1" aria-label="À corriger"></i>}
                </span>
                <span className="sw-stepper-sub">{s.subtitle}</span>
              </span>
            </button>
          )
        })}
      </nav>

      <section className="sw-body">
        {/* ---------------------------------------------------- 1. Informations */}
        {current === 0 && (
          <div className="sw-narrow">
            <div className="sw-section-label">Application à tester</div>
            {appsLoading ? (
              <div className="sw-muted-box"><span className="spinner-border spinner-border-sm me-2"></span>Chargement des applications…</div>
            ) : appsError ? (
              <div className="sw-inline-alert ko"><i className="bi bi-exclamation-octagon"></i><span>{appsError}</span></div>
            ) : applications.length === 0 ? (
              <div className="sw-empty compact">
                <i className="bi bi-hdd-network"></i>
                <p>Aucune application déclarée. Ajoutez d’abord l’application à tester.</p>
                <Link to="/applications" className="pt-btn-primary">Déclarer une application</Link>
              </div>
            ) : (
              <>
                <select
                  className={`pt-form-control ${touchedInfo.applicationId && infoErrors.applicationId ? 'is-invalid' : ''}`}
                  value={info.applicationId}
                  onChange={(e) => { setInfo((p) => ({ ...p, applicationId: e.target.value })); touch('applicationId') }}
                  onBlur={() => touch('applicationId')}
                  aria-label="Application à tester"
                >
                  <option value="">Sélectionnez une application…</option>
                  {applications.map((app) => (
                    <option key={app.id} value={app.id}>{app.name} — {app.url}</option>
                  ))}
                </select>
                {touchedInfo.applicationId && infoErrors.applicationId && <div className="sw-field-error">{infoErrors.applicationId}</div>}
                {application && (
                  <div className="sw-field-hint d-flex align-items-center gap-2">
                    <span className={`sw-app-status ${application.status === 'CONNECTED' ? 'ok' : application.status ? 'ko' : ''}`}></span>
                    {application.status === 'CONNECTED' ? 'Connectée' : application.status ? 'Dernier test de connectivité en échec' : 'Connectivité jamais testée'}
                    <span>·</span>
                    <code>{application.url}</code>
                  </div>
                )}
                {application && application.auth && application.auth.type !== 'NONE' && (
                  <div className="sw-field-hint d-flex align-items-center gap-2">
                    <i className="bi bi-shield-lock text-success"></i>
                    Authentification « {authTypeLabel(application.auth.type)} » ajoutée automatiquement à chaque étape.
                  </div>
                )}
                {application && application.status && application.status !== 'CONNECTED' && (
                  <div className="sw-inline-alert warn mt-2">
                    <i className="bi bi-wifi-off"></i>
                    <span>Le dernier test de connectivité de cette application a échoué. Vous pouvez continuer, mais vérifiez qu’elle est démarrée.</span>
                  </div>
                )}
              </>
            )}

            <div className="sw-section-label mt-4">Scénario</div>
            <div className="pt-card d-flex flex-column gap-3">
              <div>
                <label className="pt-form-label" htmlFor="sw-name">Nom du scénario</label>
                <input
                  id="sw-name"
                  className={`pt-form-control ${touchedInfo.name && infoErrors.name ? 'is-invalid' : ''}`}
                  placeholder="Ex : Parcours virement national"
                  value={info.name}
                  maxLength={255}
                  onChange={(e) => { setInfo((p) => ({ ...p, name: e.target.value })); touch('name') }}
                  onBlur={() => touch('name')}
                  autoFocus
                />
                {touchedInfo.name && infoErrors.name && <div className="sw-field-error">{infoErrors.name}</div>}
              </div>
              <div>
                <label className="pt-form-label" htmlFor="sw-desc">Description <span className="sw-optional">optionnel</span></label>
                <textarea
                  id="sw-desc"
                  className={`pt-form-control ${infoErrors.description ? 'is-invalid' : ''}`}
                  rows={3}
                  placeholder="Ce que ce scénario simule (ex : un agent se connecte puis effectue un virement)."
                  value={info.description}
                  onChange={(e) => setInfo((p) => ({ ...p, description: e.target.value }))}
                />
                <div className="d-flex justify-content-between">
                  {infoErrors.description ? <div className="sw-field-error">{infoErrors.description}</div> : <span />}
                  <span className="sw-counter">{info.description.length}/1000</span>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ---------------------------------------------------- 2. Étapes */}
        {current === 1 && (
          steps.length > 0 && (
            <div className="sw-steps-layout">
              <aside className="sw-step-list">
                <div className="sw-step-list-head">
                  <span>{steps.length} étape{steps.length > 1 ? 's' : ''}</span>
                  <span className="sw-field-hint m-0">Glissez pour réordonner</span>
                </div>
                <ol>
                  {steps.map((s, i) => {
                    const invalid = !isBlankStep(s) && hasErrors(stepErrors[s.uid])
                    const result = testRun.resultsByUid[s.uid]
                    return (
                      <li
                        key={s.uid}
                        draggable
                        onDragStart={(e) => { setDragUid(s.uid); e.dataTransfer.effectAllowed = 'move' }}
                        onDragOver={(e) => { e.preventDefault(); if (dragUid && dragUid !== s.uid) moveStep(dragUid, i) }}
                        onDragEnd={() => setDragUid(null)}
                        className={`sw-step-card ${s.uid === selectedUid ? 'selected' : ''} ${dragUid === s.uid ? 'dragging' : ''} ${invalid ? 'invalid' : ''}`}
                        onClick={() => setSelectedUid(s.uid)}
                      >
                        <span className="sw-step-grip" aria-hidden="true"><i className="bi bi-grip-vertical"></i></span>
                        <span className="sw-step-num">{i + 1}</span>
                        <span className="sw-step-main">
                          <span className="d-flex align-items-center gap-2">
                            <span className={`sw-method-badge sw-method-${s.method.toLowerCase()}`}>{s.method}</span>
                            <span className="sw-step-name">{s.name || 'Sans nom'}</span>
                          </span>
                          <span className="sw-step-url">{s.url || '—'}</span>
                          {s.url.trim() && !hasContentCheck(s) && (
                            <span className="sw-step-nocheck" title="Seul le code HTTP est vérifié : ajoutez « La réponse doit contenir » dans l’onglet Validation.">
                              <i className="bi bi-shield-exclamation" aria-hidden="true"></i> Sans vérification de contenu
                            </span>
                          )}
                        </span>
                        {invalid ? (
                          <i className="bi bi-exclamation-circle-fill sw-step-flag ko" title="À compléter"></i>
                        ) : result && !testStale ? (
                          <i className={`bi ${result.success ? 'bi-check-circle-fill ok' : 'bi-x-circle-fill ko'} sw-step-flag`}></i>
                        ) : null}
                        <span className="sw-step-actions" onClick={(e) => e.stopPropagation()}>
                          <button type="button" className="sw-icon-btn" onClick={() => moveStep(s.uid, i - 1)} disabled={i === 0} aria-label="Monter"><i className="bi bi-chevron-up"></i></button>
                          <button type="button" className="sw-icon-btn" onClick={() => moveStep(s.uid, i + 1)} disabled={i === steps.length - 1} aria-label="Descendre"><i className="bi bi-chevron-down"></i></button>
                          <button type="button" className="sw-icon-btn" onClick={() => copyStep(s.uid)} aria-label="Dupliquer"><i className="bi bi-copy"></i></button>
                          <button type="button" className="sw-icon-btn danger" onClick={() => removeStep(s.uid)} aria-label="Supprimer"><i className="bi bi-trash3"></i></button>
                        </span>
                      </li>
                    )
                  })}
                </ol>

                <div className="sw-add-step">
                  <button type="button" className="sw-add-btn" onClick={addStep}>
                    <i className="bi bi-plus-lg"></i> Ajouter une étape
                  </button>
                </div>

                <CsvPanel
                  csvData={info.csvData}
                  fileName={csvFileName}
                  steps={steps}
                  authTokenAvailable={authTokenAvailable}
                  virtualUsers={Number(load.virtualUsers) || null}
                  scenarioVariables={scenarioVariableNames}
                  distribution={info.csvDistribution}
                  onDistributionChange={(csvDistribution) => setInfo((p) => ({ ...p, csvDistribution }))}
                  onChange={(csvData, fileName) => {
                    setInfo((p) => ({ ...p, csvData }))
                    setCsvFileName(fileName)
                  }}
                />

                <ScenarioVariablesPanel
                  rows={info.variables}
                  csvColumns={csvColumns}
                  onChange={(variables) => setInfo((p) => ({ ...p, variables }))}
                />
              </aside>

              <div className="sw-step-editor-wrap">
                {selectedStep ? (
                  <StepEditor
                    key={selectedStep.uid}
                    step={selectedStep}
                    index={selectedIndex}
                    baseUrl={baseUrl}
                    errors={stepErrors[selectedStep.uid]}
                    showErrors={!isBlankStep(selectedStep)}
                    variables={availableVariables(steps, selectedIndex, csvColumns, info.variables, authTokenAvailable)}
                    laterCaptures={laterCaptures(steps, selectedIndex)}
                    hasCsv={csvColumns.length > 0}
                    onChange={(patch) => updateStep(selectedStep.uid, patch)}
                  />
                ) : (
                  <div className="sw-empty compact">
                    <i className="bi bi-hand-index"></i>
                    <p>Sélectionnez une étape pour la modifier.</p>
                  </div>
                )}
              </div>
            </div>
          )
        )}

        {/* ---------------------------------------------------- 3. Tester */}
        {current === 2 && (
          <div className="sw-narrow">
            <TestRunPanel
              steps={steps}
              baseUrl={baseUrl}
              run={testRun}
              stale={testStale}
              onRun={runTest}
              onRunStep={runSingleStep}
              onEditStep={(uid) => { setSelectedUid(uid); setCurrent(1) }}
              onUseRelativeUrl={(uid, path) => updateStep(uid, { url: path })}
            />
            {testPassed && Object.keys(testRun.resultsByUid).length === 0 && (
              <div className="sw-inline-alert mt-3">
                <i className="bi bi-patch-check"></i>
                <span>Ce brouillon a déjà réussi le test et n’a pas été modifié depuis : vous pouvez continuer, ou relancer le test.</span>
              </div>
            )}
          </div>
        )}

        {/* ---------------------------------------------------- 4. Charge & résumé */}
        {current === 3 && (
          <>
            <LoadSummaryPanel
          limits={loadLimits}
              info={info}
              application={application}
              steps={steps}
              load={load}
              errors={loadErrors}
              showErrors
              testRun={testRun}
              testStale={testStale}
              testPassed={testPassed}
              csvRows={csvAnalysis.error || csvAnalysis.columns.length === 0 ? null : csvAnalysis.rows.length}
              onChange={(patch) => setLoad((p) => ({ ...p, ...patch }))}
              onGoTo={goTo}
              onRequestDetailModeChange={(requestDetailMode) => setInfo((p) => ({ ...p, requestDetailMode }))}
              onThresholdsChange={(patch) => setInfo((p) => ({ ...p, ...patch }))}
            />
            {!isEdit && finalError && <div className="sw-inline-alert ko mt-3"><i className="bi bi-exclamation-octagon"></i><span>{finalError}</span></div>}
          </>
        )}
        {isEdit && finalError && <div className="sw-inline-alert ko mt-3"><i className="bi bi-exclamation-octagon"></i><span>{finalError}</span></div>}
      </section>

      {isEdit ? (
        <footer className="sw-footer">
          <div className="d-flex gap-2 flex-wrap">
            <button type="button" className="pt-btn-ghost" onClick={() => navigate('/scenarios')} disabled={busy}>Annuler</button>
          </div>
          <div className="sw-footer-right">
            <div className="d-flex gap-2 flex-wrap justify-content-end">
              <button type="button" className="pt-btn-outline" onClick={() => { setCurrent(2); runTest() }} disabled={busy || !stepValid[0] || !stepValid[1]}
                title="Tester la version affichée (1 utilisateur, 1 passage) sans l’enregistrer">
                {testRun.phase === 'running' && testRun.scope === 'all' ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-play-circle"></i>} Tester
              </button>
              <button type="button" className="pt-btn-outline" onClick={() => saveEdits(false)} disabled={busy || !dirty || !savable}>
                {finalSaving === 'save' ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-save"></i>} Enregistrer
              </button>
              <button type="button" className="pt-btn-primary" onClick={() => saveEdits(true)} disabled={busy || !savable}>
                {finalSaving === 'launch' ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-rocket-takeoff"></i>} {dirty ? 'Enregistrer et lancer' : 'Lancer'}
              </button>
            </div>
          </div>
        </footer>
      ) : (
      <footer className="sw-footer">
        <div className="d-flex gap-2 flex-wrap">
          <button type="button" className="pt-btn-ghost" onClick={() => navigate('/scenarios')} disabled={busy}>Annuler</button>
          <button type="button" className="pt-btn-ghost" onClick={saveDraftAndStay} disabled={busy || !info.applicationId}
            title={info.applicationId ? 'Enregistrer le travail en cours et le reprendre plus tard' : 'Choisissez d’abord l’application à tester'}>
            {draftSaving ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-floppy"></i>} Enregistrer comme brouillon
          </button>
        </div>
        <div className="sw-footer-right">
          <div className="d-flex gap-2 flex-wrap justify-content-end">
            {current > 0 && (
              <button type="button" className="pt-btn-outline" onClick={() => setCurrent(current - 1)} disabled={busy}>
                <i className="bi bi-arrow-left"></i> Précédent
              </button>
            )}
            {current < 3 && (
              <button type="button" className="pt-btn-primary" onClick={next} disabled={busy || !stepValid[current]}>
                Suivant <i className="bi bi-arrow-right"></i>
              </button>
            )}
            {current === 3 && (
              <>
                <button type="button" className="pt-btn-outline" onClick={() => finish(false)} disabled={busy || !allValid}>
                  {finalSaving === 'save' ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-save"></i>} Enregistrer
                </button>
                <button type="button" className="pt-btn-primary" onClick={() => finish(true)} disabled={busy || !allValid}>
                  {finalSaving === 'launch' ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-rocket-takeoff"></i>} Enregistrer et lancer
                </button>
              </>
            )}
          </div>
        </div>
      </footer>
      )}

      {blocker.state === 'blocked' && isEdit && (
        <div className="modal fade show d-block sw-modal-backdrop" tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="sw-leave-edit-title">
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content sw-modal">
              <div className="modal-body p-4">
                <h5 id="sw-leave-edit-title" className="mb-2">Quitter sans enregistrer ?</h5>
                <p className="mb-0 sw-muted">
                  Les modifications des sections {WIZARD_STEPS.filter((_, i) => modifiedSections[i]).map((s) => `« ${s.title} »`).join(', ')} seront perdues.
                </p>
                {!savable && <p className="sw-field-hint mt-2 mb-0">Certaines sections contiennent des erreurs : corrigez-les pour pouvoir enregistrer.</p>}
              </div>
              <div className="modal-footer flex-wrap gap-2">
                <button type="button" className="pt-btn-ghost" onClick={() => blocker.reset()} disabled={finalSaving !== null}>Continuer l’édition</button>
                <button type="button" className="pt-btn-danger" onClick={leaveNow} disabled={finalSaving !== null}>Quitter sans enregistrer</button>
                <button type="button" className="pt-btn-primary" onClick={() => saveEdits(false, true)} disabled={finalSaving !== null || !savable}>
                  {finalSaving ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-save"></i>} Enregistrer et quitter
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {blocker.state === 'blocked' && !isEdit && (
        <div className="modal fade show d-block sw-modal-backdrop" tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="sw-leave-title">
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content sw-modal">
              <div className="modal-body p-4">
                <h5 id="sw-leave-title" className="mb-2">Quitter la création du scénario ?</h5>
                <p className="mb-2 sw-muted">
                  {autoCreated
                    ? 'Le scénario a été enregistré automatiquement pour le test. Enregistrez-le comme brouillon pour le reprendre plus tard, ou quittez : il sera supprimé.'
                    : scenarioId
                      ? 'Vos dernières modifications ne sont pas enregistrées dans le brouillon.'
                      : 'Votre travail n’est pas encore enregistré.'}
                </p>
                {!info.applicationId && (
                  <p className="sw-field-hint mb-0">Pour enregistrer un brouillon, choisissez d’abord l’application à tester.</p>
                )}
                {leaveError && <div className="sw-inline-alert ko mt-2"><i className="bi bi-exclamation-octagon"></i><span>{leaveError}</span></div>}
              </div>
              <div className="modal-footer flex-wrap gap-2">
                <button type="button" className="pt-btn-ghost" onClick={() => { setLeaveError(null); blocker.reset() }} disabled={draftSaving}>
                  Continuer l’édition
                </button>
                <button type="button" className="pt-btn-danger" onClick={quitWithoutSaving} disabled={draftSaving}>
                  Quitter sans enregistrer
                </button>
                <button type="button" className="pt-btn-primary" onClick={saveDraftAndLeave} disabled={draftSaving || !info.applicationId}>
                  {draftSaving ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-floppy"></i>} Enregistrer comme brouillon
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default ScenarioWizard
