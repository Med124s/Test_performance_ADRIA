import React, { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import TopBar from '../components/TopBar'
import * as stepBridge from '../utils/scenarioStepBridge'
import { applicationsApi } from '../services/api/applications'
import { scenariosApi } from '../services/api/scenarios'
import { stepsApi } from '../services/api/steps'
import { saveScenarioWithSteps, LocalStepDraft } from '../services/scenarioSave'
import { runStep, buildVariableMap } from '../services/stepRunner'
import { loadDefaultTestSettings } from '../utils/defaultTestSettings'
import { parseCsvFile } from '../utils/csv'
import { firstError, validateRequired, validateStepUrl, validateJson, validatePositiveInteger } from '../utils/validation'
import { useScenarioLauncher } from '../hooks/useScenarioLauncher'
import LaunchScenarioModal from '../components/LaunchScenarioModal'
import { Scenario, Step, StepResult } from '../types'

interface HeaderItem {
  id: number
  key: string
  value: string
  enabled: boolean
}

interface AssertionItem {
  id: number
  type: string
  property: string
  operator: string
  targetValue: string
  status: 'passed' | 'failed' | 'pending'
}

function CreateStep() {
  const navigate = useNavigate()

  // Liste réelle des applications (JSON Server), chargée une fois au montage.
  const [applications, setApplications] = useState<import('../types').Application[]>([])
  useEffect(() => {
    applicationsApi.getAll().then(setApplications).catch(() => setApplications([]))
  }, [])

  // URL réelle de l'application liée au scénario en cours, pour l'afficher
  // à la place du champ "Environnement" (supprimé) — c'est une information
  // authentique et utile, contrairement à un environnement fictif.
  const linkedScenarioMeta = stepBridge.loadScenarioMeta()
  const linkedApplication = applications.find((a) => a.name === linkedScenarioMeta?.application)

  // Form states (Left column)
  const [method, setMethod] = useState<'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS' | 'TRACE'>('POST')
  const [stepName, setStepName] = useState('Login utilisateur')
  const [url, setUrl] = useState('/auth/login')
  const [description, setDescription] = useState(
    "Authentification de l'utilisateur avec email et mot de passe."
  )
  const [followRedirects, setFollowRedirects] = useState(true)
  const [activeStep, setActiveStep] = useState(true)
  const [timeoutMs, setTimeoutMs] = useState(3000)
  // Erreurs inline (nom/URL obligatoires, body JSON valide) — même pattern
  // qu'Applications.tsx / CreateScenario.tsx : affichées au blur puis
  // systématiquement dès la tentative d'enregistrement.
  const [fieldsTouched, setFieldsTouched] = useState<{ name?: boolean; url?: boolean; body?: boolean }>({})
  const stepNameError = validateRequired(stepName, "Le nom de l'étape")
  const stepUrlError = firstError(validateRequired(url, 'La ressource'), validateStepUrl(url))
  const timeoutError = validatePositiveInteger(timeoutMs, 'Le timeout')

  // Configuration states (Right column)
  const [headers, setHeaders] = useState<HeaderItem[]>([
    { id: 1, key: 'Content-Type', value: 'application/json', enabled: true },
    { id: 2, key: 'Accept', value: 'application/json', enabled: true },
    { id: 3, key: 'Authorization', value: 'Bearer {{auth_token}}', enabled: true },
  ])

  const [headersJson, setHeadersJson] = useState(`{
  "Content-Type": "application/json",
  "Accept": "application/json",
  "Authorization": "Bearer {{auth_token}}"
}`)

  const [headersFormatMode, setHeadersFormatMode] = useState<'table' | 'json'>('table')

  const [bodyJson, setBodyJson] = useState(`{
  "email": "{{email}}",
  "password": "{{password}}",
  "remember": true
}`)
  const bodyJsonError = validateJson(bodyJson, 'Le body de la requête')

  const [assertions, setAssertions] = useState<AssertionItem[]>([
    {
      id: 1,
      type: 'Status code',
      property: '',
      operator: 'Égal à',
      targetValue: '200',
      status: 'passed',
    },
    {
      id: 2,
      type: 'JSON path',
      property: '$.token',
      operator: 'Existe',
      targetValue: 'true',
      status: 'passed',
    },
  ])

  const [pauseBeforeMs, setPauseBeforeMs] = useState(500)
  const [pacingAfterMs, setPacingAfterMs] = useState(1000)
  const [toastMessage, setToastMessage] = useState<string | null>(null)
  const [toastType, setToastType] = useState<'success' | 'danger'>('success')
  // Anti double-soumission pour "Enregistrer le scénario" (étape Résumé) —
  // sans ça, un double-clic déclenche deux POST et crée un scénario en
  // double dans JSON Server.
  const [savingScenario, setSavingScenario] = useState(false)
  // Scénario réellement enregistré par "Enregistrer le scénario" (étape
  // Résumé) — dès qu'il est défini, on propose Exécuter / Changer / Exécuter
  // directement au lieu de naviguer aussitôt vers la liste des scénarios.
  const [savedScenario, setSavedScenario] = useState<Scenario | null>(null)
  // Copie figée de la liste d'étapes au moment de l'enregistrement — le
  // Résumé doit continuer à les afficher après coup, alors que
  // stepBridge.resetAll() (appelé juste après la sauvegarde) vide le pont
  // sessionStorage dont ce même écran lit normalement en direct ("Étapes
  // (0) / Aucune étape." apparaissait sinon juste après avoir enregistré,
  // alors que le scénario était bien enregistré avec toutes ses étapes).
  const [savedStepsSnapshot, setSavedStepsSnapshot] = useState<stepBridge.CoreStep[]>([])
  const launcher = useScenarioLauncher()

  // Identifiant de l'étape en cours d'édition, transmis depuis la page
  // "Étapes" (CreateScenario) via le pont de données.
  const [editingStepId] = useState<string | null>(() => stepBridge.getEditingStepId())

  // Au montage, on relie cette page à l'étape réellement sélectionnée dans
  // la liste : on récupère ses infos de base (méthode/nom/url) et, si elle a
  // déjà été configurée, ses détails avancés (headers/body/assertions...).
  useEffect(() => {
    if (editingStepId === null) return

    const steps = stepBridge.loadSteps()
    // Comparaison en string des deux côtés — voir CreateScenario.tsx pour
    // le détail : sans elle, un id numérique venant de json-server ne
    // correspond jamais à editingStepId (toujours string), et cette page
    // retombe silencieusement sur ses valeurs par défaut ("Login
    // utilisateur" / "/auth/login") quelle que soit l'étape cliquée.
    const coreStep = steps?.find((s) => String(s.id) === String(editingStepId))
    const details = stepBridge.loadStepDetails(editingStepId)

    if (coreStep) {
      setMethod(coreStep.method)
      setStepName(coreStep.name)
      setUrl(coreStep.url)
    }

    if (details) {
      setDescription(details.description)
      setFollowRedirects(details.followRedirects)
      setActiveStep(details.activeStep)
      setTimeoutMs(details.timeoutMs)
      setHeaders(details.headers)
      setBodyJson(details.bodyJson)
      setAssertions(details.assertions)
      setPauseBeforeMs(details.pauseBeforeMs)
      setPacingAfterMs(details.pacingAfterMs)
      return
    }

    const isRealExistingStep = !editingStepId.startsWith('tmp-')
    if (isRealExistingStep) {
      // Le pont sessionStorage n'a jamais ses détails avancés (headers/body/
      // assertions/timeout...) pour une étape ouverte depuis un scénario en
      // édition : CreateScenario ne charge QUE id/order/method/name/url dans
      // le pont, jamais ces champs. `details` étant donc TOUJOURS `null` ici
      // pour une étape existante, il faut aller chercher la vraie donnée
      // sur JSON Server plutôt que de supposer "rien n'a jamais été
      // configuré" — sans ce recours, une étape ayant réellement un body/des
      // headers/des assertions enregistrés s'affichait vide (perte
      // apparente de données déjà sauvegardées, jamais réellement perdues
      // en base mais invisibles à l'écran).
      stepsApi.getById(editingStepId).then((apiStep) => {
        setDescription(apiStep.description ?? '')
        setFollowRedirects(apiStep.followRedirects ?? true)
        setActiveStep(apiStep.active ?? true)
        setTimeoutMs(apiStep.timeoutMs ?? 3000)
        setHeaders(apiStep.headers ?? [])
        setBodyJson(apiStep.bodyJson ?? '')
        setAssertions((apiStep.assertions ?? []).map((a) => ({ ...a, status: 'pending' as const })))
        setPauseBeforeMs(apiStep.pauseBeforeMs ?? 0)
        setPacingAfterMs(apiStep.pacingAfterMs ?? 0)
      }).catch(() => {
        // Étape introuvable côté API (rare) : on garde les valeurs neutres
        // ci-dessous plutôt que de laisser le jeu de données fictif visible.
      })
    }

    // Nouvelle étape (id "tmp-...", jamais encore enregistrée) — ou étape
    // introuvable côté API : valeurs neutres. Les états initiaux de
    // bodyJson/headers/assertions (jeu de données de démonstration "Login
    // utilisateur") ne doivent JAMAIS rester affichés comme s'ils étaient
    // déjà enregistrés pour cette étape — sinon "Suivant" sans y toucher
    // attacherait ce body de login fictif à n'importe quelle étape.
    setDescription('')
    setHeaders([])
    setBodyJson('')
    setAssertions([])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Navigation entre les étapes du wizard :
  // 2 = détail HTTP d'une étape = Configuration (globale 2)
  // 4 = Utilisateurs (globale 3) · 5 = Planification (globale 4)
  // 6 = Résumé (globale 5) — l'écran interne 3 (ancienne "Configuration du
  // Scénario", fusionnée dans l'écran 2) n'existe plus.
  const [searchParams] = useSearchParams()
  const wizardParam = searchParams.get('wizard')
  const initialWizardStep = (
    wizardParam === '2'
      ? 2
      : wizardParam === '3' || wizardParam === '4' || wizardParam === '5'
      ? ((Number(wizardParam) + 1) as 4 | 5 | 6)
      : 2
  )
  const [currentStep, setCurrentStep] = useState<2 | 3 | 4 | 5 | 6>(initialWizardStep)

  // Étape 2 (Configuration) : statut du scénario — seul endroit de
  // l'application où ce champ est réellement éditable.
  const [status, setStatus] = useState<'Actif' | 'Inactif'>('Actif')

  // Étape 3 - Utilisateurs (valeurs neutres, remplacées par l'effet
  // d'initialisation ci-dessous dès qu'un brouillon ou un scénario existant
  // est trouvé — jamais de jeu de données fictif "50 VUs" imposé d'office).
  const [virtualUsers, setVirtualUsers] = useState(50)
  const [rampUpSeconds, setRampUpSeconds] = useState(30)
  const virtualUsersError = validatePositiveInteger(virtualUsers, 'Le nombre d\'utilisateurs virtuels')
  // Ramp-up à 0 est légitime (montée en charge instantanée) — seule une
  // valeur négative ou non entière est invalide, contrairement à VUs/Timeout.
  const rampUpError = Number.isInteger(rampUpSeconds) && rampUpSeconds >= 0
    ? null
    : 'Le ramp-up doit être un nombre entier positif ou nul.'
  const [userProfile, setUserProfile] = useState('Utilisateur standard')
  const [dataSource, setDataSource] = useState<'manual' | 'csv'>('manual')
  const [csvFileName, setCsvFileName] = useState('')
  const [testVariables, setTestVariables] = useState<{ id: number; name: string; value: string }[]>([])
  // Aperçu du fichier CSV réellement lu (FileReader, voir utils/csv.ts) —
  // ne survit pas à un rechargement de page (un <input type="file"> ne peut
  // pas être re-rempli par JS), contrairement à `testVariables` qui, lui,
  // est sauvegardé avec le scénario. Sert uniquement à afficher un aperçu
  // honnête de ce qui a été importé pendant la session en cours.
  const [csvRowCount, setCsvRowCount] = useState(0)
  const [csvError, setCsvError] = useState<string | null>(null)

  const handleAddVariable = () => {
    setTestVariables([...testVariables, { id: Date.now(), name: '', value: '' }])
  }

  const handleRemoveVariable = (id: number) => {
    setTestVariables(testVariables.filter((v) => v.id !== id))
  }

  // Lit réellement le fichier CSV choisi (aucune valeur inventée) et en
  // dérive les Variables de test : colonnes = noms, valeurs de la 1ère
  // ligne = valeurs. Le moteur d'exécution (runStep) ne rejoue aujourd'hui
  // qu'un seul passage par exécution — pas de boucle réelle par VU — donc
  // c'est cette 1ère ligne qui alimente réellement les ${variables} des
  // étapes, exactement comme pour la saisie manuelle.
  const handleCsvFileChange = async (file: File | undefined) => {
    if (!file) return
    setCsvError(null)
    try {
      const { columns, rows } = await parseCsvFile(file)
      if (columns.length === 0 || rows.length === 0) {
        setCsvError('Fichier CSV vide ou illisible (attendu : 1re ligne = noms de colonnes, lignes suivantes = valeurs).')
        setCsvFileName(file.name)
        setCsvRowCount(0)
        setTestVariables([])
        return
      }
      setCsvFileName(file.name)
      setCsvRowCount(rows.length)
      setTestVariables(columns.map((name, idx) => ({ id: idx + 1, name, value: rows[0][name] ?? '' })))
    } catch (err) {
      setCsvError(err instanceof Error ? err.message : 'Impossible de lire ce fichier CSV.')
      setCsvFileName(file.name)
      setCsvRowCount(0)
      setTestVariables([])
    }
  }

  // Étape 4 - Planification
  const [executionType, setExecutionType] = useState<'immediate' | 'scheduled' | 'recurring'>(
    'immediate'
  )
  const [scheduledDate, setScheduledDate] = useState('')
  const [scheduledTime, setScheduledTime] = useState('09:00')
  const [recurrence, setRecurrence] = useState('Quotidien')

  // Initialisation unique du wizard (Configuration/Utilisateurs/
  // Planification) : reprend un brouillon local s'il existe (retour depuis
  // CreateScenario sans avoir sauvegardé), sinon va chercher les vraies
  // valeurs du scénario en base (mode édition), sinon garde des valeurs de
  // départ neutres (nouveau scénario). Effectué une seule fois par vraie
  // entrée sur cette page — même garde que resumeDecisionRef côté
  // CreateScenario, pour rester robuste au double-appel de React.StrictMode.
  const wizardInitRef = useRef(false)
  useEffect(() => {
    if (wizardInitRef.current) return
    wizardInitRef.current = true

    const draft = stepBridge.loadWizardConfig()
    if (draft) {
      setStatus(draft.status)
      setVirtualUsers(draft.virtualUsers)
      setRampUpSeconds(draft.rampUpSeconds)
      setUserProfile(draft.userProfile)
      setDataSource(draft.dataSource)
      setCsvFileName(draft.csvFileName)
      setTestVariables(draft.testVariables)
      setExecutionType(draft.executionType)
      setScheduledDate(draft.scheduledDate)
      setScheduledTime(draft.scheduledTime)
      setRecurrence(draft.recurrence)
      return
    }

    const editingScenarioId = stepBridge.loadEditingScenarioId()
    if (!editingScenarioId) return
    scenariosApi.getById(editingScenarioId).then((scenario) => {
      setStatus(scenario.status)
      const defaults = loadDefaultTestSettings()
      setVirtualUsers(scenario.virtualUsers ?? defaults.concurrency)
      setRampUpSeconds(scenario.rampUpSeconds ?? defaults.rampUpSeconds)
      setUserProfile(scenario.userProfile ?? 'Utilisateur standard')
      setDataSource(scenario.dataSource ?? 'manual')
      setCsvFileName(scenario.csvFileName ?? '')
      setTestVariables(scenario.testVariables ?? [])
      if (scenario.schedule) {
        setExecutionType(scenario.schedule.executionType)
        setScheduledDate(scenario.schedule.scheduledDate ?? '')
        setScheduledTime(scenario.schedule.scheduledTime ?? '09:00')
        setRecurrence(scenario.schedule.recurrence ?? 'Quotidien')
      }
    }).catch(() => {
      // Scénario introuvable : on garde les valeurs de départ neutres.
    })
  }, [])

  // Synchronise en continu vers le pont, pour que ces valeurs survivent à un
  // aller-retour complet vers CreateScenario (autre route, autre montage).
  useEffect(() => {
    stepBridge.saveWizardConfig({
      status,
      virtualUsers,
      rampUpSeconds,
      userProfile,
      dataSource,
      csvFileName,
      testVariables,
      executionType,
      scheduledDate,
      scheduledTime,
      recurrence,
    })
  }, [status, virtualUsers, rampUpSeconds, userProfile, dataSource, csvFileName, testVariables, executionType, scheduledDate, scheduledTime, recurrence])

  // Retour vers la page "Étapes" (CreateScenario) : reconstruit "?edit=<id>"
  // à partir du pont si un scénario existant est en cours de modification —
  // cette route n'a elle-même jamais connaissance de cet id (voir
  // scenarioStepBridge.ts). Sans cela, CreateScenario perdrait son contexte
  // d'édition et "Enregistrer le scénario" créerait un doublon au lieu de
  // mettre à jour le scénario existant.
  const goToScenario = () => {
    const editingId = stepBridge.loadEditingScenarioId()
    if (editingId) {
      // On revient d'une simple session de configuration d'étape : garder
      // le brouillon local (voir CreateScenario) plutôt que de le remplacer
      // par un rechargement des vraies données, qui écraserait ce qu'on
      // vient de configurer ici.
      stepBridge.markReturningFromStepConfig()
      navigate(`/scenarios/create?edit=${editingId}`)
    } else {
      navigate('/scenarios/create')
    }
  }

  // Renvoie les infos de cette étape (méthode/nom/url + détails avancés)
  // vers la page "Étapes", pour que la liste des étapes du scénario reste à
  // jour avec ce qui vient d'être configuré ici.
  const persistStepToBridge = () => {
    if (editingStepId === null) return

    stepBridge.updateStepCore(editingStepId, {
      method,
      name: stepName,
      url,
      status: activeStep ? 'Actif' : 'Inactif',
    })

    stepBridge.saveStepDetails(editingStepId, {
      description,
      followRedirects,
      activeStep,
      timeoutMs,
      headers,
      bodyJson,
      assertions,
      pauseBeforeMs,
      pacingAfterMs,
    })
  }

  const handleGoToStep = (step: 2 | 3 | 4 | 5 | 6) => {
    if (step === 2 || step < currentStep) {
      if (currentStep === 2) persistStepToBridge()
      setCurrentStep(step)
      window.scrollTo({ top: 0, behavior: 'smooth' })
    }
  }

  const handleNextStep = () => {
    if (currentStep === 2) {
      setFieldsTouched({ name: true, url: true, body: true })
      if (stepNameError) {
        showNotification(stepNameError, 'danger')
        return
      }
      if (stepUrlError) {
        showNotification(stepUrlError, 'danger')
        return
      }
      if (bodyJsonError) {
        showNotification(bodyJsonError, 'danger')
        return
      }
      if (timeoutError) {
        showNotification(timeoutError, 'danger')
        return
      }
      persistStepToBridge()
      // Écran interne 3 (ancienne "Configuration du Scénario") supprimé —
      // cet écran (Headers/Body/Assertions) EST désormais "Configuration",
      // donc on avance directement vers "Utilisateurs".
      setCurrentStep(4)
      window.scrollTo({ top: 0, behavior: 'smooth' })
      return
    }
    if (currentStep === 4) {
      if (virtualUsersError) {
        showNotification(virtualUsersError, 'danger')
        return
      }
      if (rampUpError) {
        showNotification(rampUpError, 'danger')
        return
      }
    }
    if (currentStep === 5 && executionType === 'scheduled' && !scheduledDate) {
      showNotification('Veuillez renseigner une date de planification.', 'danger')
      return
    }
    if (currentStep < 6) {
      setCurrentStep((s) => (s + 1) as 2 | 3 | 4 | 5 | 6)
      window.scrollTo({ top: 0, behavior: 'smooth' })
    }
  }

  const handlePrevStep = () => {
    if (currentStep === 2) {
      persistStepToBridge()
      goToScenario()
      return
    }
    if (currentStep === 4) {
      // Symétrique de handleNextStep : "Utilisateurs" revient directement à
      // "Configuration" (écran interne 2), l'écran interne 3 n'existe plus.
      setCurrentStep(2)
      window.scrollTo({ top: 0, behavior: 'smooth' })
      return
    }
    setCurrentStep((s) => (s - 1) as 2 | 3 | 4 | 5 | 6)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const handleSaveScenario = async () => {
    // Garde anti double-soumission : sans elle, un double-clic sur
    // "Enregistrer le scénario" déclenche deux POST et crée un scénario en
    // double dans JSON Server (bug constaté avec des scénarios dupliqués à
    // quelques dizaines de ms d'écart).
    if (savingScenario) return
    if (stepNameError || stepUrlError || bodyJsonError || timeoutError || virtualUsersError || rampUpError) {
      setFieldsTouched({ name: true, url: true, body: true })
      showNotification(
        stepNameError || stepUrlError || bodyJsonError || timeoutError || virtualUsersError || rampUpError || 'Formulaire invalide.',
        'danger'
      )
      return
    }
    setSavingScenario(true)
    persistStepToBridge()

    // Étape Résumé : on enregistre pour de bon le scénario complet (infos
    // générales + toutes les étapes revues/configurées), puis on retourne
    // directement à la liste des scénarios pour l'y retrouver.
    const meta = stepBridge.loadScenarioMeta()
    const coreSteps = stepBridge.loadSteps() ?? []
    // Scénario existant en cours de modification, le cas échéant — sans ça,
    // enregistrer depuis cet écran créerait toujours un nouveau scénario au
    // lieu de mettre à jour l'existant (voir goToScenario ci-dessus).
    const editingScenarioId = stepBridge.loadEditingScenarioId()

    let saved: Scenario | null = null

    if (meta && meta.name.trim() && coreSteps.length > 0) {
      try {
        const allApps = await applicationsApi.getAll()
        const linkedApp = allApps.find((a) => a.name === meta.application)
        if (!linkedApp) {
          showNotification('Application introuvable pour ce scénario.', 'danger')
          setSavingScenario(false)
          return
        }

        const localSteps: LocalStepDraft[] = coreSteps.map((s, idx) => {
          const details = stepBridge.loadStepDetails(s.id)
          return {
            id: s.id,
            order: idx + 1,
            name: s.name,
            method: s.method,
            url: s.url,
            description: details?.description || undefined,
            headers: details?.headers,
            bodyJson: details?.bodyJson || undefined,
            assertions: details?.assertions,
            timeoutMs: details?.timeoutMs,
            active: details?.activeStep,
            followRedirects: details?.followRedirects,
            pauseBeforeMs: details?.pauseBeforeMs,
            pacingAfterMs: details?.pacingAfterMs,
          }
        })

        // Enregistrement réel sur JSON Server — met à jour le scénario
        // existant si on était en train de le modifier, le crée sinon.
        // Inclut aussi les valeurs des étapes 2-4 du wizard (Configuration/
        // Utilisateurs/Planification), configurées sur cette même page.
        saved = await saveScenarioWithSteps({
          scenarioId: editingScenarioId,
          name: meta.name.trim(),
          applicationId: linkedApp.id,
          description: meta.description.trim() || undefined,
          createdBy: 'Vous',
          localSteps,
          existingStepIds: new Set(coreSteps.filter((s) => !s.id.startsWith('tmp-')).map((s) => s.id)),
          status,
          virtualUsers,
          rampUpSeconds,
          userProfile,
          dataSource,
          csvFileName: csvFileName || undefined,
          testVariables,
          schedule: {
            executionType,
            scheduledDate: scheduledDate || undefined,
            scheduledTime: scheduledTime || undefined,
            recurrence: recurrence || undefined,
          },
        })

        setSavedStepsSnapshot(coreSteps)
        stepBridge.resetAll()
      } catch (err) {
        showNotification(err instanceof Error ? err.message : "Erreur lors de l'enregistrement", 'danger')
        setSavingScenario(false)
        return
      }
    }

    setSavingScenario(false)

    if (saved) {
      // On reste sur cette page : simple confirmation avant de retourner à
      // la liste des scénarios (voir résumé, section "Que voulez-vous faire").
      setSavedScenario(saved)
      showNotification('Scénario enregistré avec succès !', 'success')

      // "Immédiate" ("Lancer le scénario dès l'enregistrement", voir l'écran
      // Planification) : on lance réellement le scénario ici, tout de suite
      // après l'enregistrement — sans attendre un clic manuel sur
      // "Exécuter". Même mécanisme (formOverride) que le déclencheur
      // planifié de useScheduledExecutions, en arrière-plan (pas de modale).
      if (executionType === 'immediate') {
        const defaults = loadDefaultTestSettings()
        launcher.handleLaunch(saved, {
          scenarioId: saved.id,
          applicationId: saved.applicationId,
          virtualUsers: saved.virtualUsers ?? defaults.concurrency,
          duration: 300,
          rampUp: saved.rampUpSeconds ?? defaults.rampUpSeconds,
          thinkTime: defaults.stepDelayMs,
          debit: '',
          stopMode: 'auto',
        })
      }
    } else {
      showNotification('Scénario complet enregistré avec succès !', 'success')
      setTimeout(() => {
        navigate('/scenarios')
      }, 1000)
    }
  }

  // Test Step state
  const [isTestingStep, setIsTestingStep] = useState(false)
  const [testResult, setTestResult] = useState<StepResult | null>(null)

  const showNotification = (msg: string, type: 'success' | 'danger' = 'success') => {
    setToastType(type)
    setToastMessage(msg)
    setTimeout(() => setToastMessage(null), 3000)
  }

  // currentStep est un numéro d'écran interne (2 = détail HTTP/"Configuration
  // de l'Étape", 3..6 = les 4 derniers écrans du wizard) ; le stepper
  // affiché, lui, montre les 5 étapes GLOBALES du scénario (1..5). La liste
  // des étapes ("Étapes", global 1) vit uniquement dans CreateScenario.tsx —
  // on y arrive déjà depuis là, donc dès l'entrée sur cette page (écran
  // interne 2 : méthode/URL/headers/body/assertions) on est dans "Configuration"
  // (global 2), pas encore dans "Étapes".
  const globalActiveStep = currentStep === 2 ? 2 : currentStep - 1

  const stepperItems = [1, 2, 3, 4, 5].map((number) => ({
    number,
    label:
      number === 1
        ? 'Étapes'
        : number === 2
        ? 'Configuration'
        : number === 3
        ? 'Utilisateurs'
        : number === 4
        ? 'Planification'
        : 'Résumé',
    active: number === globalActiveStep,
    completed: number < globalActiveStep || number === 1,
  }))

  const handleAddHeader = () => {
    const newHeader: HeaderItem = {
      id: Date.now(),
      key: '',
      value: '',
      enabled: true,
    }
    setHeaders([...headers, newHeader])
  }

  const handleRemoveHeader = (id: number) => {
    setHeaders(headers.filter((h) => h.id !== id))
  }

  // "Ajouter Assertion" part d'un type prédéfini (Status code) mais chaque
  // champ reste éditable ensuite (voir handleUpdateAssertion) — l'utilisateur
  // peut changer le type vers "Personnalisée" et saisir librement condition/
  // opérateur/valeur attendue, sans jeu de valeurs figé imposé.
  const handleAddAssertion = () => {
    const newAssertion: AssertionItem = {
      id: Date.now(),
      type: 'Status code',
      property: '',
      operator: 'Égal à',
      targetValue: '',
      status: 'pending',
    }
    setAssertions([...assertions, newAssertion])
  }

  const handleUpdateAssertion = (id: number, patch: Partial<AssertionItem>) => {
    setAssertions(assertions.map((a) => (a.id === id ? { ...a, ...patch } : a)))
  }

  const handleRemoveAssertion = (id: number) => {
    setAssertions(assertions.filter((a) => a.id !== id))
  }

  // Envoie une vraie requête HTTP pour l'étape en cours d'édition (méthode,
  // headers activés, body, timeout configurés dans le formulaire) — jamais
  // de résultat simulé. Une erreur réseau/CORS/timeout réelle est affichée
  // telle quelle, jamais remplacée par un faux succès.
  const handleTestStep = async () => {
    if (!url.trim()) {
      showNotification("Veuillez renseigner une URL pour tester l'étape.", 'danger')
      return
    }
    if (!linkedApplication) {
      showNotification("Aucune application liée à ce scénario : impossible de tester l'étape.", 'danger')
      return
    }

    setIsTestingStep(true)
    setTestResult(null)

    const stepToTest: Step = {
      id: editingStepId ?? 'test',
      scenarioId: '',
      order: 0,
      name: stepName,
      method,
      url,
      headers,
      bodyJson,
      assertions,
      timeoutMs,
      // "Tester cette étape" envoie toujours la requête, même si "Étape
      // active" est décoché — ce toggle ne concerne que l'exécution réelle
      // du scénario (voir useScenarioLauncher), pas ce test manuel ponctuel.
      active: true,
      followRedirects,
      pauseBeforeMs,
      pacingAfterMs,
    }

    const defaults = loadDefaultTestSettings()
    const result = await runStep(stepToTest, linkedApplication.url, defaults.httpTimeoutSeconds * 1000, {
      variables: buildVariableMap(testVariables),
    })
    setIsTestingStep(false)
    setTestResult(result)
    showNotification(
      result.status === 'success' ? "Test de l'étape terminé avec succès !" : "Le test de l'étape a échoué.",
      result.status === 'success' ? 'success' : 'danger'
    )
  }

  return (
    <div className="pt-content">
      {/* Toast Notification */}
      {toastMessage && (
        <div
          style={{
            position: 'fixed',
            bottom: '24px',
            right: '24px',
            zIndex: 9999,
            background: toastType === 'danger' ? '#EF4444' : 'var(--pt-text)',
            color: 'var(--pt-card-bg)',
            padding: '12px 20px',
            borderRadius: 'var(--pt-radius-sm)',
            boxShadow: 'var(--pt-shadow-md)',
            display: 'flex',
            alignItems: 'center',
            gap: '10px',
            fontSize: '13.5px',
            fontWeight: 500,
          }}
        >
          <i
            className={`bi ${
              toastType === 'danger' ? 'bi-exclamation-triangle-fill' : 'bi-check-circle-fill'
            }`}
            style={{ color: toastType === 'danger' ? '#fff' : 'var(--pt-success)' }}
          ></i>
          {toastMessage}
        </div>
      )}

      {/* Page Header */}
      <div className="pt-page-header">
        <div>
          <button
            className="btn btn-link p-0"
            onClick={() => {
              persistStepToBridge()
              goToScenario()
            }}
            style={{
              textDecoration: 'none',
              color: 'var(--pt-primary)',
              fontSize: '13px',
              fontWeight: 500,
              display: 'inline-flex',
              alignItems: 'center',
              gap: '6px',
              marginBottom: '8px',
              border: 'none',
              background: 'none',
            }}
          >
            <i className="bi bi-arrow-left"></i> Retour au scénario
          </button>
          <div className="page-title">
            <h1>Créer / Modifier une étape de scénario</h1>
          </div>
        </div>
        <div className="d-flex align-items-center gap-2">
          {(currentStep === 2) && (
            <button
              className="pt-btn-outline"
              style={{ fontSize: '12.5px' }}
              onClick={handleTestStep}
              disabled={isTestingStep}
            >
              {isTestingStep ? (
                <>
                  <span className="spinner-border spinner-border-sm me-1" role="status"></span>
                  Test en cours...
                </>
              ) : (
                <>
                  <i className="bi bi-play-circle text-success me-1"></i> Tester cette étape
                </>
              )}
            </button>
          )}
          <TopBar searchPlaceholder="" />
        </div>
      </div>

      {/* Stepper Navigation */}
      <div className="pt-card mb-4" style={{ padding: '1.25rem 1.5rem' }}>
        <div className="d-flex align-items-center justify-content-between">
          {stepperItems.map((step, index) => {
            const isLast = index === stepperItems.length - 1
            return (
              <React.Fragment key={step.number}>
                <div
                  className="d-flex align-items-center gap-2"
                  style={{
                    zIndex: 2,
                    cursor: step.number === 1 || step.number <= globalActiveStep ? 'pointer' : 'default',
                    opacity: step.number > globalActiveStep && step.number !== 1 ? 0.6 : 1,
                  }}
                  onClick={() => {
                    if (step.number === 1) {
                      persistStepToBridge()
                      goToScenario()
                    } else if (step.number <= globalActiveStep) {
                      // step.number 2 = "Configuration" = écran interne 2
                      // (Headers/Body/Assertions) ; 3/4/5 = écrans internes
                      // 4/5/6 (l'écran interne 3 n'existe plus).
                      handleGoToStep((step.number === 2 ? 2 : step.number + 1) as 2 | 3 | 4 | 5 | 6)
                    }
                  }}
                >
                  <div
                    style={{
                      width: '36px',
                      height: '36px',
                      borderRadius: '50%',
                      background: step.active
                        ? 'var(--pt-primary)'
                        : step.completed
                        ? 'var(--pt-success)'
                        : 'var(--pt-card-bg)',
                      border: step.active || step.completed ? 'none' : '2px solid var(--pt-border)',
                      color: step.active || step.completed ? 'white' : 'var(--pt-text-muted)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontWeight: 700,
                      fontSize: '14px',
                      transition: 'all 0.2s ease',
                    }}
                  >
                    {step.completed ? <i className="bi bi-check-lg"></i> : step.number}
                  </div>
                  <div>
                    <div
                      style={{
                        fontSize: '13.5px',
                        fontWeight: step.active ? 700 : 500,
                        color: step.active
                          ? 'var(--pt-primary)'
                          : step.completed
                          ? 'var(--pt-text)'
                          : 'var(--pt-text-muted)',
                      }}
                    >
                      {step.label}
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)' }}>
                      Étape {step.number} sur 5
                    </div>
                  </div>
                </div>

                {!isLast && (
                  <div
                    style={{
                      flex: 1,
                      height: '2px',
                      background: step.completed ? 'var(--pt-success)' : 'var(--pt-border)',
                      margin: '0 1rem',
                      alignSelf: 'center',
                    }}
                  ></div>
                )}
              </React.Fragment>
            )
          })}
        </div>
      </div>

      {/* STEP 2: Configuration */}
      {currentStep === 2 && (
      <>
      {/* Two-Column Layout */}
      <div className="row g-4 mb-4">
        {/* LEFT COLUMN: Main Step Form */}
        <div className="col-12 col-lg-5">
          <div className="pt-card" style={{ height: '100%' }}>
            <div
              className="pb-3 mb-3 d-flex align-items-center justify-content-between"
              style={{ borderBottom: '1px solid var(--pt-border)' }}
            >
              <div className="d-flex align-items-center gap-2">
                <i className="bi bi-sliders" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
                <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>
                  Paramètres Généraux
                </h6>
              </div>
              <span className="pt-pill info">Configuration de l'Étape</span>
            </div>

            <div className="d-flex flex-column gap-3">
              {/* Méthode Dropdown */}
              <div>
                <label className="pt-form-label">Méthode HTTP *</label>
                <select
                  className="pt-form-control"
                  style={{
                    fontWeight: 700,
                    color:
                      method === 'GET'
                        ? 'var(--pt-primary)'
                        : method === 'POST'
                        ? 'var(--pt-success)'
                        : method === 'DELETE'
                        ? 'var(--pt-danger)'
                        : 'var(--pt-warning)',
                  }}
                  value={method}
                  onChange={(e) => setMethod(e.target.value as any)}
                >
                  <option value="GET">GET - Lecture de données</option>
                  <option value="POST">POST - Soumission de données</option>
                  <option value="PUT">PUT - Mise à jour complète</option>
                  <option value="PATCH">PATCH - Modification partielle</option>
                  <option value="DELETE">DELETE - Suppression</option>
                  <option value="HEAD">HEAD - En-têtes seulement</option>
                  <option value="OPTIONS">OPTIONS - Options disponibles</option>
                  <option value="TRACE">TRACE - Diagnostic de la requête</option>
                </select>
              </div>

              {/* Nom du Step */}
              <div>
                <label className="pt-form-label">Nom de l'étape *</label>
                <input
                  type="text"
                  className="pt-form-control"
                  value={stepName}
                  onChange={(e) => setStepName(e.target.value)}
                  onBlur={() => setFieldsTouched((t) => ({ ...t, name: true }))}
                  placeholder="ex: Ajout au Panier"
                />
                {fieldsTouched.name && stepNameError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{stepNameError}</div>
                )}
              </div>

              {/* URL Input */}
              <div>
                <label className="pt-form-label">URL Cible / Endpoint *</label>
                <input
                  type="text"
                  className="pt-form-control"
                  style={{ fontFamily: 'monospace', fontSize: '13px' }}
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  onBlur={() => setFieldsTouched((t) => ({ ...t, url: true }))}
                  placeholder="/auth/login ou https://api.example.com/v1/auth"
                />
                {fieldsTouched.url && stepUrlError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{stepUrlError}</div>
                )}
              </div>

              {/* Description */}
              <div>
                <label className="pt-form-label">Description</label>
                <textarea
                  className="pt-form-control"
                  rows={3}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="Expliquez l'objectif de cette étape..."
                ></textarea>
              </div>

              {/* Toggles */}
              <div
                className="p-3 rounded"
                style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)' }}
              >
                <div className="d-flex align-items-center justify-content-between mb-3">
                  <div>
                    <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)' }}>
                      Suivre les redirections (Follow Redirects)
                    </div>
                    <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>
                      Redirection automatique HTTP 301/302
                    </div>
                  </div>
                  <label className="pt-toggle">
                    <input
                      type="checkbox"
                      checked={followRedirects}
                      onChange={(e) => setFollowRedirects(e.target.checked)}
                    />
                    <span className="toggle-slider"></span>
                  </label>
                </div>

                <div className="d-flex align-items-center justify-content-between">
                  <div>
                    <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)' }}>
                      Étape active
                    </div>
                    <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>
                      Inclure cette étape lors de l'exécution
                    </div>
                  </div>
                  <label className="pt-toggle">
                    <input
                      type="checkbox"
                      checked={activeStep}
                      onChange={(e) => setActiveStep(e.target.checked)}
                    />
                    <span className="toggle-slider"></span>
                  </label>
                </div>
              </div>

              {/* Timeout Input */}
              <div>
                <label className="pt-form-label">Timeout (en millisecondes)</label>
                <div className="d-flex align-items-center gap-2">
                  <input
                    type="number"
                    min={1}
                    className="pt-form-control"
                    value={timeoutMs}
                    onChange={(e) => setTimeoutMs(Number(e.target.value))}
                  />
                  <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', width: '40px' }}>
                    ms
                  </span>
                </div>
                {timeoutError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{timeoutError}</div>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* RIGHT COLUMN: Advanced Request Config, Headers, Body, Assertions */}
        <div className="col-12 col-lg-7">
          <div className="pt-card d-flex flex-column gap-4">
            {/* Header: Environment selection */}
            <div
              className="pb-3 d-flex justify-content-between align-items-center flex-wrap gap-2"
              style={{ borderBottom: '1px solid var(--pt-border)' }}
            >
              <div className="d-flex align-items-center gap-2">
                <i className="bi bi-hdd-network" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
                <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>
                  Configuration de la Requête
                </h6>
              </div>

              <div className="d-flex align-items-center gap-2">
                <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', fontWeight: 500 }}>
                  Application cible:
                </span>
                <span
                  className="pt-pill neutral"
                  style={{ fontSize: '12.5px', fontWeight: 600 }}
                  title="URL réelle de l'application liée à ce scénario"
                >
                  <i className="bi bi-globe2 me-1"></i>
                  {linkedApplication ? `${linkedApplication.name} (${linkedApplication.url})` : 'Aucune application liée'}
                </span>
              </div>
            </div>

            {/* Headers Section */}
            <div>
              <div className="d-flex justify-content-between align-items-center mb-2">
                <label className="pt-form-label mb-0" style={{ fontSize: '13.5px' }}>
                  <i className="bi bi-list-nested me-1"></i> Headers HTTP ({headers.length})
                </label>
                <div className="d-flex align-items-center gap-2">
                  <div className="btn-group btn-group-sm" role="group">
                    <button
                      className={`btn btn-sm ${headersFormatMode === 'table' ? 'btn-primary' : 'btn-outline-secondary'}`}
                      style={{ fontSize: '11.5px', padding: '0.2rem 0.5rem' }}
                      onClick={() => setHeadersFormatMode('table')}
                    >
                      Tableau
                    </button>
                    <button
                      className={`btn btn-sm ${headersFormatMode === 'json' ? 'btn-primary' : 'btn-outline-secondary'}`}
                      style={{ fontSize: '11.5px', padding: '0.2rem 0.5rem' }}
                      onClick={() => setHeadersFormatMode('json')}
                    >
                      JSON
                    </button>
                  </div>
                  {headersFormatMode === 'table' && (
                    <button
                      className="pt-btn-outline"
                      style={{ padding: '0.25rem 0.6rem', fontSize: '12px' }}
                      onClick={handleAddHeader}
                    >
                      <i className="bi bi-plus-lg"></i> Ajouter
                    </button>
                  )}
                </div>
              </div>

              {headersFormatMode === 'table' ? (
                <div
                  style={{
                    border: '1px solid var(--pt-border)',
                    borderRadius: 'var(--pt-radius-sm)',
                    overflow: 'hidden',
                  }}
                >
                  <table className="pt-table">
                    <thead>
                      <tr style={{ background: 'var(--pt-bg)' }}>
                        <th style={{ width: '30px' }}></th>
                        <th>Clé (Header Name)</th>
                        <th>Valeur (Header Value)</th>
                        <th style={{ width: '40px', textAlign: 'right' }}></th>
                      </tr>
                    </thead>
                    <tbody>
                      {headers.map((hdr, idx) => (
                        <tr key={hdr.id}>
                          <td>
                            <input
                              type="checkbox"
                              checked={hdr.enabled}
                              onChange={() => {
                                const updated = [...headers]
                                updated[idx].enabled = !updated[idx].enabled
                                setHeaders(updated)
                              }}
                            />
                          </td>
                          <td>
                            <input
                              type="text"
                              className="pt-form-control"
                              style={{
                                fontSize: '12.5px',
                                padding: '0.25rem 0.5rem',
                                fontFamily: 'monospace',
                              }}
                              value={hdr.key}
                              onChange={(e) => {
                                const updated = [...headers]
                                updated[idx].key = e.target.value
                                setHeaders(updated)
                              }}
                              placeholder="Clé"
                            />
                          </td>
                          <td>
                            <input
                              type="text"
                              className="pt-form-control"
                              style={{
                                fontSize: '12.5px',
                                padding: '0.25rem 0.5rem',
                                fontFamily: 'monospace',
                              }}
                              value={hdr.value}
                              onChange={(e) => {
                                const updated = [...headers]
                                updated[idx].value = e.target.value
                                setHeaders(updated)
                              }}
                              placeholder="Valeur"
                            />
                          </td>
                          <td style={{ textAlign: 'right' }}>
                            <button
                              className="topbar-icon"
                              style={{ width: '28px', height: '28px' }}
                              onClick={() => handleRemoveHeader(hdr.id)}
                            >
                              <i className="bi bi-x-lg" style={{ fontSize: '12px', color: 'var(--pt-danger)' }}></i>
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div style={{ position: 'relative' }}>
                  <textarea
                    className="pt-form-control"
                    rows={4}
                    style={{
                      fontFamily: 'Consolas, Monaco, "Andale Mono", monospace',
                      fontSize: '12.5px',
                      background: '#0f172a',
                      color: '#38bdf8',
                      lineHeight: '1.5',
                      whiteSpace: 'pre',
                      borderRadius: '6px',
                      border: '1px solid var(--pt-border)',
                      padding: '12px',
                    }}
                    value={headersJson}
                    onChange={(e) => setHeadersJson(e.target.value)}
                    spellCheck={false}
                  ></textarea>
                </div>
              )}
            </div>

            {/* Body JSON Section with Highlight Monospace */}
            <div>
              <div className="d-flex justify-content-between align-items-center mb-2">
                <label className="pt-form-label mb-0" style={{ fontSize: '13.5px' }}>
                  <i className="bi bi-code-square me-1"></i> Body Requête (JSON)
                </label>
                <span className="pt-pill neutral" style={{ fontSize: '11px', fontFamily: 'monospace' }}>
                  application/json
                </span>
              </div>
              <div
                style={{
                  borderRadius: '6px',
                  overflow: 'hidden',
                  border: '1px solid var(--pt-border)',
                  background: '#0f172a',
                }}
              >
                <div
                  style={{
                    background: '#1e293b',
                    color: '#94a3b8',
                    padding: '4px 12px',
                    fontSize: '11px',
                    fontFamily: 'monospace',
                    borderBottom: '1px solid #334155',
                    display: 'flex',
                    justifyContent: 'space-between',
                  }}
                >
                  <span>JSON Payload</span>
                  <span>Syntax Monospace</span>
                </div>
                <textarea
                  className="pt-form-control"
                  rows={6}
                  style={{
                    fontFamily: 'Consolas, Monaco, "Andale Mono", "Courier New", monospace',
                    fontSize: '12.5px',
                    background: '#0f172a',
                    color: '#f8fafc',
                    lineHeight: '1.5',
                    whiteSpace: 'pre',
                    border: 'none',
                    borderRadius: '0',
                    padding: '12px',
                    boxShadow: 'none',
                  }}
                  value={bodyJson}
                  onChange={(e) => setBodyJson(e.target.value)}
                  onBlur={() => setFieldsTouched((t) => ({ ...t, body: true }))}
                  spellCheck={false}
                ></textarea>
                {fieldsTouched.body && bodyJsonError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{bodyJsonError}</div>
                )}
              </div>
            </div>

            {/* Assertions Section */}
            <div>
              <div className="d-flex justify-content-between align-items-center mb-2">
                <label className="pt-form-label mb-0" style={{ fontSize: '13.5px' }}>
                  <i className="bi bi-shield-check me-1"></i> Assertions & Règles de Validation ({assertions.length})
                </label>
                <button
                  className="pt-btn-outline"
                  style={{ padding: '0.25rem 0.6rem', fontSize: '12px' }}
                  onClick={handleAddAssertion}
                >
                  <i className="bi bi-plus-lg"></i> Ajouter Assertion
                </button>
              </div>

              <div className="d-flex flex-column gap-2">
                {assertions.map((assertion) => (
                  <div
                    key={assertion.id}
                    className="p-2.5 rounded d-flex align-items-center flex-wrap gap-2"
                    style={{
                      background: 'var(--pt-bg)',
                      border: '1px solid var(--pt-border)',
                      fontSize: '12.5px',
                    }}
                  >
                    <select
                      className="pt-form-control"
                      style={{ fontSize: '12px', padding: '0.25rem 0.4rem', width: '150px', flexShrink: 0 }}
                      value={assertion.type}
                      onChange={(e) => handleUpdateAssertion(assertion.id, { type: e.target.value })}
                    >
                      <option>Status code</option>
                      <option>JSON path</option>
                      <option>Header</option>
                      <option>Response time</option>
                      <option>Personnalisée</option>
                    </select>
                    <input
                      type="text"
                      className="pt-form-control"
                      style={{ fontSize: '12px', padding: '0.25rem 0.5rem', flex: '1 1 140px', fontFamily: 'monospace' }}
                      value={assertion.property}
                      onChange={(e) => handleUpdateAssertion(assertion.id, { property: e.target.value })}
                      placeholder={assertion.type === 'Personnalisée' ? 'condition libre (ex: $.data.length)' : 'propriété (ex: $.token)'}
                    />
                    <select
                      className="pt-form-control"
                      style={{ fontSize: '12px', padding: '0.25rem 0.4rem', width: '130px', flexShrink: 0 }}
                      value={assertion.operator}
                      onChange={(e) => handleUpdateAssertion(assertion.id, { operator: e.target.value })}
                    >
                      <option>Égal à</option>
                      <option>Différent de</option>
                      <option>Existe</option>
                      <option>N'existe pas</option>
                      <option>Contient</option>
                      <option>Supérieur à</option>
                      <option>Inférieur à</option>
                    </select>
                    <input
                      type="text"
                      className="pt-form-control"
                      style={{ fontSize: '12px', padding: '0.25rem 0.5rem', flex: '1 1 100px', fontFamily: 'monospace' }}
                      value={assertion.targetValue}
                      onChange={(e) => handleUpdateAssertion(assertion.id, { targetValue: e.target.value })}
                      placeholder="valeur attendue"
                    />

                    <button
                      className="topbar-icon"
                      style={{ width: '28px', height: '28px', flexShrink: 0 }}
                      onClick={() => handleRemoveAssertion(assertion.id)}
                    >
                      <i className="bi bi-trash" style={{ fontSize: '12px', color: 'var(--pt-danger)' }}></i>
                    </button>
                  </div>
                ))}
                {assertions.length === 0 && (
                  <div style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
                    Aucune assertion. Cliquez "Ajouter Assertion" puis choisissez un type prédéfini ou "Personnalisée" pour une condition libre.
                  </div>
                )}
              </div>
            </div>

            {/* Timers / Pacing Section */}
            <div
              className="p-3 rounded"
              style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)' }}
            >
              <h6 style={{ fontSize: '13.5px', fontWeight: 600, marginBottom: '0.75rem' }}>
                <i className="bi bi-clock-history me-1" style={{ color: 'var(--pt-primary)' }}></i>
                Timers & Délais de Pacing
              </h6>
              <div className="row g-3">
                <div className="col-12 col-md-6">
                  <label className="pt-form-label">Pause avant étape (Think time)</label>
                  <div className="d-flex align-items-center gap-2">
                    <input
                      type="number"
                      className="pt-form-control"
                      value={pauseBeforeMs}
                      onChange={(e) => setPauseBeforeMs(Number(e.target.value))}
                    />
                    <span style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>ms</span>
                  </div>
                </div>

                <div className="col-12 col-md-6">
                  <label className="pt-form-label">Attente après requête (Pacing)</label>
                  <div className="d-flex align-items-center gap-2">
                    <input
                      type="number"
                      className="pt-form-control"
                      value={pacingAfterMs}
                      onChange={(e) => setPacingAfterMs(Number(e.target.value))}
                    />
                    <span style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>ms</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Test Result Display Section (When test is performed) */}
      {(isTestingStep || testResult) && (
        <div className="pt-card mb-4">
          <div
            className="pb-3 mb-3 d-flex justify-content-between align-items-center"
            style={{ borderBottom: '1px solid var(--pt-border)' }}
          >
            <div className="d-flex align-items-center gap-2">
              <i className="bi bi-terminal-fill" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
              <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>
                Résultat du test de l'étape
              </h6>
            </div>
            {testResult && (
              <div className="d-flex align-items-center gap-2">
                <span className={`pt-pill ${testResult.status === 'success' ? 'success' : 'danger'}`}>
                  <i className={`bi ${testResult.status === 'success' ? 'bi-check-circle-fill' : 'bi-x-circle-fill'} me-1`}></i>
                  {testResult.httpStatus ? `${testResult.httpStatus} ${testResult.response?.statusText ?? ''}` : 'Échec'}
                </span>
                {testResult.responseTimeMs !== undefined && (
                  <span className="pt-pill info">
                    <i className="bi bi-lightning-charge me-1"></i> {testResult.responseTimeMs} ms
                  </span>
                )}
              </div>
            )}
          </div>

          {isTestingStep ? (
            <div className="text-center py-4">
              <div className="spinner-border text-primary mb-2" role="status">
                <span className="visually-hidden">Chargement...</span>
              </div>
              <div style={{ fontSize: '13.5px', fontWeight: 500 }}>
                Envoi de la requête à <code style={{ color: 'var(--pt-primary)' }}>{url}</code>...
              </div>
            </div>
          ) : testResult ? (
            <div className="d-flex flex-column gap-3">
              {testResult.error && (
                <div className="pt-alert-banner danger">
                  <i className="bi bi-exclamation-triangle-fill"></i>
                  {testResult.error}
                </div>
              )}
              {testResult.response?.headers && Object.keys(testResult.response.headers).length > 0 && (
                <div>
                  <label className="pt-form-label mb-1">Response Headers</label>
                  <pre
                    style={{
                      background: 'var(--pt-bg)',
                      border: '1px solid var(--pt-border)',
                      borderRadius: '6px',
                      padding: '10px 12px',
                      fontSize: '11.5px',
                      margin: 0,
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                    }}
                  >
                    {Object.entries(testResult.response.headers).map(([k, v]) => `${k}: ${v}`).join('\n')}
                  </pre>
                </div>
              )}
              {testResult.response?.body && (
                <div>
                  <label className="pt-form-label mb-1">Response Body</label>
                  <div
                    style={{
                      background: '#0f172a',
                      color: '#38bdf8',
                      padding: '12px',
                      borderRadius: '6px',
                      fontFamily: 'Consolas, Monaco, "Andale Mono", monospace',
                      fontSize: '12.5px',
                      whiteSpace: 'pre-wrap',
                      maxHeight: '200px',
                      overflowY: 'auto',
                      border: '1px solid #334155',
                    }}
                  >
                    {testResult.response.body}
                  </div>
                </div>
              )}
            </div>
          ) : null}
        </div>
      )}
      </>
      )}

      {/* STEP 4 (interne) / Étape globale 3 : Utilisateurs */}
      {currentStep === 4 && (
        <div className="pt-card mb-4">
          <div
            className="pb-3 mb-3 d-flex align-items-center gap-2"
            style={{ borderBottom: '1px solid var(--pt-border)' }}
          >
            <i className="bi bi-people-fill" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
            <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>
              Utilisateurs Virtuels & Données de Test
            </h6>
          </div>

          <div className="row g-4">
            <div className="col-12 col-lg-6">
              <div className="d-flex flex-column gap-3">
                <div>
                  <label className="pt-form-label">Nombre d'utilisateurs virtuels *</label>
                  <input
                    type="number"
                    className="pt-form-control"
                    min={1}
                    value={virtualUsers}
                    onChange={(e) => setVirtualUsers(Number(e.target.value))}
                  />
                  {virtualUsersError && (
                    <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{virtualUsersError}</div>
                  )}
                </div>

                <div>
                  <label className="pt-form-label">Montée en charge (Ramp-up)</label>
                  <div className="d-flex align-items-center gap-2">
                    <input
                      type="number"
                      className="pt-form-control"
                      min={0}
                      value={rampUpSeconds}
                      onChange={(e) => setRampUpSeconds(Number(e.target.value))}
                    />
                    <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', width: '90px' }}>
                      secondes
                    </span>
                  </div>
                  {rampUpError && (
                    <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{rampUpError}</div>
                  )}
                </div>

                <div>
                  <label className="pt-form-label">Profil utilisateur</label>
                  <select
                    className="pt-form-control"
                    value={userProfile}
                    onChange={(e) => setUserProfile(e.target.value)}
                  >
                    <option>Utilisateur standard</option>
                    <option>Utilisateur premium</option>
                    <option>Utilisateur invité (non authentifié)</option>
                    <option>Administrateur</option>
                  </select>
                </div>

                <div>
                  <label className="pt-form-label">Source des données</label>
                  <div className="d-flex gap-2 mb-2">
                    <button
                      className={dataSource === 'csv' ? 'pt-btn-primary' : 'pt-btn-outline'}
                      style={{ fontSize: '12.5px', flex: 1 }}
                      onClick={() => setDataSource(dataSource === 'csv' ? 'manual' : 'csv')}
                    >
                      <i className="bi bi-filetype-csv"></i> Import CSV
                    </button>
                  </div>

                  {dataSource === 'csv' && (
                    <div
                      className="p-3 rounded d-flex align-items-center justify-content-between"
                      style={{ background: 'var(--pt-bg)', border: '1px dashed var(--pt-border)' }}
                    >
                      <div style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
                        {csvFileName ? (
                          <>
                            <i className={`bi ${csvError ? 'bi-exclamation-triangle-fill' : 'bi-file-earmark-check-fill'} me-1`} style={{ color: csvError ? 'var(--pt-danger)' : 'var(--pt-success)' }}></i>
                            {csvFileName}
                            {!csvError && ` — ${csvRowCount} ligne${csvRowCount > 1 ? 's' : ''}`}
                          </>
                        ) : (
                          'Aucun fichier sélectionné'
                        )}
                      </div>
                      <label className="pt-btn-outline" style={{ fontSize: '12px', cursor: 'pointer', margin: 0 }}>
                        <i className="bi bi-upload"></i> Choisir un fichier
                        <input
                          type="file"
                          accept=".csv"
                          style={{ display: 'none' }}
                          onChange={(e) => handleCsvFileChange(e.target.files?.[0])}
                        />
                      </label>
                    </div>
                  )}
                  {dataSource === 'csv' && csvError && (
                    <div style={{ fontSize: '12px', color: 'var(--pt-danger)', marginTop: '6px' }}>
                      <i className="bi bi-exclamation-circle me-1"></i>
                      {csvError}
                    </div>
                  )}
                </div>
              </div>
            </div>

            <div className="col-12 col-lg-6">
              <div className="d-flex justify-content-between align-items-center mb-2">
                <label className="pt-form-label mb-0" style={{ fontSize: '13.5px' }}>
                  <i className="bi bi-braces me-1"></i> Variables de test ({testVariables.length})
                </label>
                {dataSource === 'manual' && (
                  <button
                    className="pt-btn-outline"
                    style={{ padding: '0.25rem 0.6rem', fontSize: '12px' }}
                    onClick={handleAddVariable}
                  >
                    <i className="bi bi-plus-lg"></i> Ajouter
                  </button>
                )}
              </div>

              {dataSource === 'manual' ? (
                <div
                  style={{
                    border: '1px solid var(--pt-border)',
                    borderRadius: 'var(--pt-radius-sm)',
                    overflow: 'hidden',
                  }}
                >
                  <table className="pt-table">
                    <thead>
                      <tr style={{ background: 'var(--pt-bg)' }}>
                        <th>Variable</th>
                        <th>Valeur / Génération</th>
                        <th style={{ width: '40px', textAlign: 'right' }}></th>
                      </tr>
                    </thead>
                    <tbody>
                      {testVariables.map((v, idx) => (
                        <tr key={v.id}>
                          <td>
                            <input
                              type="text"
                              className="pt-form-control"
                              style={{ fontSize: '12.5px', padding: '0.25rem 0.5rem', fontFamily: 'monospace' }}
                              value={v.name}
                              onChange={(e) => {
                                const updated = [...testVariables]
                                updated[idx].name = e.target.value
                                setTestVariables(updated)
                              }}
                              placeholder="nom"
                            />
                          </td>
                          <td>
                            <input
                              type="text"
                              className="pt-form-control"
                              style={{ fontSize: '12.5px', padding: '0.25rem 0.5rem', fontFamily: 'monospace' }}
                              value={v.value}
                              onChange={(e) => {
                                const updated = [...testVariables]
                                updated[idx].value = e.target.value
                                setTestVariables(updated)
                              }}
                              placeholder="valeur"
                            />
                          </td>
                          <td style={{ textAlign: 'right' }}>
                            <button
                              className="topbar-icon"
                              style={{ width: '28px', height: '28px' }}
                              onClick={() => handleRemoveVariable(v.id)}
                            >
                              <i className="bi bi-trash" style={{ fontSize: '12px', color: 'var(--pt-danger)' }}></i>
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : testVariables.length > 0 ? (
                <div
                  className="p-3 rounded"
                  style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12.5px' }}
                >
                  <div style={{ color: 'var(--pt-text-muted)', marginBottom: '8px' }}>
                    <i className="bi bi-info-circle me-1"></i>
                    {csvRowCount > 1
                      ? `${csvRowCount} lignes détectées — le moteur d'exécution ne rejoue qu'un passage par exécution : la 1re ligne alimente les \${variables} des étapes.`
                      : "1 ligne détectée — ses valeurs alimentent les \${variables} des étapes."}
                  </div>
                  <table className="pt-table" style={{ margin: 0 }}>
                    <thead>
                      <tr style={{ background: 'var(--pt-bg)' }}>
                        <th>Variable</th>
                        <th>Valeur (1re ligne)</th>
                      </tr>
                    </thead>
                    <tbody>
                      {testVariables.map((v) => (
                        <tr key={v.id}>
                          <td style={{ fontFamily: 'monospace' }}>{v.name}</td>
                          <td style={{ fontFamily: 'monospace' }}>{v.value}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div
                  className="p-3 rounded"
                  style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12.5px', color: 'var(--pt-text-muted)' }}
                >
                  <i className="bi bi-info-circle me-1"></i>
                  Importez un fichier CSV (1re ligne = noms de colonnes) pour en dériver les Variables de test.
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* STEP 5 (interne) / Étape globale 4 : Planification */}
      {currentStep === 5 && (
        <div className="pt-card mb-4">
          <div
            className="pb-3 mb-3 d-flex align-items-center gap-2"
            style={{ borderBottom: '1px solid var(--pt-border)' }}
          >
            <i className="bi bi-calendar-event-fill" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
            <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>
              Planification de l'Exécution
            </h6>
          </div>

          <div className="row g-4">
            <div className="col-12">
              <label className="pt-form-label">Type d'exécution *</label>
              <div className="d-flex flex-column gap-2 mb-3">
                {[
                  { key: 'immediate', label: 'Immédiate', desc: "Lancer le scénario dès l'enregistrement" },
                  { key: 'scheduled', label: 'Planifiée', desc: 'Exécuter à une date et heure précises' },
                  { key: 'recurring', label: 'Récurrente', desc: 'Répéter selon une fréquence définie' },
                ].map((opt) => (
                  <div
                    key={opt.key}
                    onClick={() => setExecutionType(opt.key as any)}
                    className="p-3 rounded d-flex align-items-center gap-3"
                    style={{
                      cursor: 'pointer',
                      border: `1px solid ${executionType === opt.key ? 'var(--pt-primary)' : 'var(--pt-border)'}`,
                      background: executionType === opt.key ? 'var(--pt-primary-light, rgba(79,70,229,0.06))' : 'var(--pt-bg)',
                    }}
                  >
                    <input type="radio" checked={executionType === opt.key} onChange={() => setExecutionType(opt.key as any)} />
                    <div>
                      <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)' }}>{opt.label}</div>
                      <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{opt.desc}</div>
                    </div>
                  </div>
                ))}
              </div>

              {executionType === 'scheduled' && (
                <div className="row g-3">
                  <div className="col-6">
                    <label className="pt-form-label">Date *</label>
                    <input
                      type="date"
                      className="pt-form-control"
                      value={scheduledDate}
                      onChange={(e) => setScheduledDate(e.target.value)}
                    />
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label">Heure</label>
                    <input
                      type="time"
                      className="pt-form-control"
                      value={scheduledTime}
                      onChange={(e) => setScheduledTime(e.target.value)}
                    />
                  </div>
                </div>
              )}

              {executionType === 'recurring' && (
                <div>
                  <label className="pt-form-label">Fréquence</label>
                  <select className="pt-form-control" value={recurrence} onChange={(e) => setRecurrence(e.target.value)}>
                    <option>Quotidien</option>
                    <option>Hebdomadaire</option>
                    <option>Mensuel</option>
                  </select>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* STEP 6 (interne) / Étape globale 5 : Résumé */}
      {currentStep === 6 && (
        <div className="pt-card mb-4">
          <div
            className="pb-3 mb-3 d-flex align-items-center gap-2"
            style={{ borderBottom: '1px solid var(--pt-border)' }}
          >
            <i className="bi bi-clipboard-check-fill" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
            <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>
              Résumé du Scénario
            </h6>
          </div>

          {/* Avant l'enregistrement : lecture en direct du pont (reflète les
              derniers changements). Après : la copie figée, car le pont
              sessionStorage est vidé juste après un enregistrement réussi. */}
          {(() => {
            const displaySteps = savedScenario ? savedStepsSnapshot : (stepBridge.loadSteps() ?? [])
            return (
          <div className="row g-4">
            <div className="col-12 col-lg-6">
              <div className="d-flex justify-content-between align-items-center mb-2">
                <h6 style={{ fontSize: '13px', fontWeight: 700, margin: 0 }}>
                  Étapes ({displaySteps.length})
                </h6>
                <button className="pt-btn-ghost" style={{ padding: '0.2rem 0.5rem', fontSize: '11.5px' }} onClick={goToScenario}>
                  <i className="bi bi-pencil"></i> Modifier
                </button>
              </div>
              <div className="p-3 rounded d-flex flex-column gap-2" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12.5px' }}>
                {displaySteps.length === 0 ? (
                  <span className="text-muted">Aucune étape.</span>
                ) : (
                  displaySteps.map((s) => (
                    <div key={s.id} className="d-flex align-items-center gap-2">
                      <span
                        style={{
                          padding: '0.1rem 0.4rem', borderRadius: '4px', fontSize: '10.5px', fontWeight: 700,
                          background: s.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)',
                          color: s.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)',
                        }}
                      >
                        {s.method}
                      </span>
                      <span style={{ fontWeight: 600 }}>{s.name}</span>
                      <code style={{ marginLeft: 'auto', color: 'var(--pt-primary)' }}>{s.url}</code>
                    </div>
                  ))
                )}
              </div>

              <div className="d-flex justify-content-between align-items-center mb-2 mt-3">
                <h6 style={{ fontSize: '13px', fontWeight: 700, margin: 0 }}>Scénario</h6>
                <button className="pt-btn-ghost" style={{ padding: '0.2rem 0.5rem', fontSize: '11.5px' }} onClick={goToScenario}>
                  <i className="bi bi-pencil"></i> Modifier
                </button>
              </div>
              <div className="p-3 rounded d-flex flex-column gap-2" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12.5px' }}>
                <div className="d-flex justify-content-between"><span className="text-muted">Nom du scénario</span><strong>{linkedScenarioMeta?.name || '—'}</strong></div>
                <div className="d-flex justify-content-between"><span className="text-muted">Application</span><strong>{linkedApplication ? `${linkedApplication.name} (${linkedApplication.url})` : '—'}</strong></div>
                <div className="d-flex justify-content-between"><span className="text-muted">Description</span><strong>{linkedScenarioMeta?.description || '—'}</strong></div>
                <div className="d-flex align-items-center justify-content-between">
                  <span className="text-muted">Statut</span>
                  <label className="pt-toggle">
                    <input
                      type="checkbox"
                      checked={status === 'Actif'}
                      onChange={(e) => setStatus(e.target.checked ? 'Actif' : 'Inactif')}
                    />
                    <span className="toggle-slider"></span>
                  </label>
                </div>
              </div>

              <div className="d-flex justify-content-between align-items-center mb-2 mt-3">
                <h6 style={{ fontSize: '13px', fontWeight: 700, margin: 0 }}>Utilisateurs</h6>
                <button className="pt-btn-ghost" style={{ padding: '0.2rem 0.5rem', fontSize: '11.5px' }} onClick={() => handleGoToStep(4)}>
                  <i className="bi bi-pencil"></i> Modifier
                </button>
              </div>
              <div className="p-3 rounded d-flex flex-column gap-2" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12.5px' }}>
                <div className="d-flex justify-content-between"><span className="text-muted">Utilisateurs virtuels</span><strong>{virtualUsers}</strong></div>
                <div className="d-flex justify-content-between"><span className="text-muted">Ramp-up</span><strong>{rampUpSeconds} s</strong></div>
                <div className="d-flex justify-content-between"><span className="text-muted">Profil</span><strong>{userProfile}</strong></div>
                <div className="d-flex justify-content-between"><span className="text-muted">Source de données</span><strong>{dataSource === 'manual' ? `${testVariables.length} variables` : csvFileName ? `${csvFileName} (${csvRowCount} ligne${csvRowCount > 1 ? 's' : ''}, ${testVariables.length} variables)` : 'CSV (aucun fichier)'}</strong></div>
              </div>
            </div>

            <div className="col-12 col-lg-6">
              <div className="d-flex justify-content-between align-items-center mb-2">
                <h6 style={{ fontSize: '13px', fontWeight: 700, margin: 0 }}>Planification</h6>
                <button className="pt-btn-ghost" style={{ padding: '0.2rem 0.5rem', fontSize: '11.5px' }} onClick={() => handleGoToStep(5)}>
                  <i className="bi bi-pencil"></i> Modifier
                </button>
              </div>
              <div className="p-3 rounded d-flex flex-column gap-2" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12.5px' }}>
                <div className="d-flex justify-content-between">
                  <span className="text-muted">Type</span>
                  <strong>
                    {executionType === 'immediate' ? 'Immédiate' : executionType === 'scheduled' ? 'Planifiée' : 'Récurrente'}
                  </strong>
                </div>
                {executionType === 'scheduled' && (
                  <div className="d-flex justify-content-between"><span className="text-muted">Date/Heure</span><strong>{scheduledDate || '—'} {scheduledTime}</strong></div>
                )}
                {executionType === 'recurring' && (
                  <div className="d-flex justify-content-between"><span className="text-muted">Fréquence</span><strong>{recurrence}</strong></div>
                )}
              </div>

              {savedScenario ? (
                <div
                  className="p-3 rounded mt-3"
                  style={{ background: 'var(--pt-success-light)', border: '1px solid var(--pt-success)' }}
                >
                  <div className="d-flex align-items-center gap-2 mb-3">
                    <i className="bi bi-check-circle-fill" style={{ color: 'var(--pt-success)', fontSize: '18px' }}></i>
                    <div style={{ fontSize: '12.5px', color: 'var(--pt-text)' }}>
                      Scénario enregistré. Aller à la liste des scénarios ?
                    </div>
                  </div>
                  <div className="d-flex gap-2">
                    <button className="pt-btn-primary" style={{ fontSize: '12.5px' }} onClick={() => navigate('/scenarios')}>
                      <i className="bi bi-check-lg me-1"></i> Oui
                    </button>
                    <button className="pt-btn-outline" style={{ fontSize: '12.5px' }} onClick={() => setSavedScenario(null)}>
                      Annuler
                    </button>
                  </div>
                </div>
              ) : (
                <div
                  className="p-3 rounded d-flex align-items-center gap-2 mt-3"
                  style={{ background: 'var(--pt-success-light)', border: '1px solid var(--pt-success)' }}
                >
                  <i className="bi bi-check-circle-fill" style={{ color: 'var(--pt-success)', fontSize: '18px' }}></i>
                  <div style={{ fontSize: '12.5px', color: 'var(--pt-text)' }}>
                    Le scénario est prêt à être enregistré. Vérifiez les informations ci-dessus avant de valider.
                  </div>
                </div>
              )}
            </div>
          </div>
            )
          })()}
        </div>
      )}

      {/* Footer Controls */}
      <div
        className="pt-card d-flex justify-content-between align-items-center flex-wrap gap-2"
        style={{ padding: '1rem 1.25rem' }}
      >
        <button className="pt-btn-outline" onClick={handlePrevStep}>
          <i className="bi bi-arrow-left"></i> {currentStep === 2 ? 'Retour au scénario' : 'Précédent'}
        </button>

        <div className="d-flex align-items-center gap-2">
          {(currentStep === 2) && (
            <button className="pt-btn-outline" onClick={handleTestStep} disabled={isTestingStep}>
              <i className="bi bi-play-circle text-success me-1"></i> Tester cette étape
            </button>
          )}
          {currentStep < 6 && (
            <button
              className="pt-btn-primary"
              onClick={handleNextStep}
              disabled={
                (currentStep === 2 && (!!stepNameError || !!stepUrlError || !!bodyJsonError || !!timeoutError)) ||
                (currentStep === 4 && (!!virtualUsersError || !!rampUpError))
              }
            >
              Suivant <i className="bi bi-arrow-right"></i>
            </button>
          )}
          {currentStep === 6 && !savedScenario && (
            <button className="pt-btn-primary" onClick={handleSaveScenario} disabled={savingScenario || !!stepNameError || !!stepUrlError || !!bodyJsonError || !!timeoutError || !!virtualUsersError || !!rampUpError}>
              {savingScenario ? <><i className="bi bi-arrow-repeat me-1 pt-spin"></i> Enregistrement...</> : <><i className="bi bi-check-lg me-1"></i> Enregistrer le scénario</>}
            </button>
          )}
        </div>
      </div>

      <LaunchScenarioModal
        launcher={launcher}
        scenarios={savedScenario ? [savedScenario] : []}
        applications={applications}
      />
    </div>
  )
}

export default CreateStep
