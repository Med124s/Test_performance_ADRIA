import React, { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { Application, Step } from '../types'
import { applicationsApi } from '../services/api/applications'
import { scenariosApi } from '../services/api/scenarios'
import { stepsApi } from '../services/api/steps'
import { saveScenarioWithSteps, LocalStepDraft } from '../services/scenarioSave'
import { runStep, buildVariableMap, LiveStepState } from '../services/stepRunner'
import { loadDefaultTestSettings } from '../utils/defaultTestSettings'
import { useApiList } from '../hooks/useApiResource'
import * as stepBridge from '../utils/scenarioStepBridge'
import { firstError, validateRequired, validateMaxLength, validateStepUrl, NAME_MAX_LENGTH } from '../utils/validation'

let tempIdCounter = 0
const makeTempStepId = () => `tmp-${Date.now()}-${tempIdCounter++}`

interface ScenarioStep {
  id: string
  order: number
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS' | 'TRACE'
  name: string
  url: string
  timeout: string
  status: 'Actif' | 'Inactif'
}

const initialSteps: ScenarioStep[] = [
  {
    id: 'demo-1',
    order: 1,
    method: 'GET',
    name: "Ouvrir la page d'accueil",
    url: '/',
    timeout: 'Status 200',
    status: 'Actif',
  },
  {
    id: 'demo-2',
    order: 2,
    method: 'GET',
    name: 'Voir les produits',
    url: '/produits',
    timeout: 'Status 200',
    status: 'Actif',
  },
  {
    id: 'demo-3',
    order: 3,
    method: 'POST',
    name: 'Ajouter un produit au panier',
    url: '/panier/ajouter',
    timeout: 'Status 200',
    status: 'Actif',
  },
  {
    id: 'demo-4',
    order: 4,
    method: 'GET',
    name: 'Voir le panier',
    url: '/panier',
    timeout: 'Status 200',
    status: 'Actif',
  },
  {
    id: 'demo-5',
    order: 5,
    method: 'POST',
    name: 'Login utilisateur',
    url: '/auth/login',
    timeout: 'Status 200',
    status: 'Actif',
  },
  {
    id: 'demo-6',
    order: 6,
    method: 'POST',
    name: 'Passer la commande',
    url: '/commande/checkout',
    timeout: 'Status 200',
    status: 'Actif',
  },
  {
    id: 'demo-7',
    order: 7,
    method: 'GET',
    name: 'Voir la confirmation',
    url: '/commande/succes',
    timeout: 'Status 200',
    status: 'Actif',
  },
]

function CreateScenario() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  // Rechargées à chaque montage depuis JSON Server : une application
  // ajoutée sur la page Applications est donc disponible ici sans avoir
  // besoin de recharger la page manuellement.
  const { data: applications } = useApiList<Application>(() => applicationsApi.getAll())

  // "Nouveau scénario" (bouton + Nouveau scénario) arrive avec ?new=1 :
  // chaque scénario ayant ses propres étapes, on démarre alors avec une
  // liste vide plutôt qu'avec l'exemple de démonstration.
  const isNewScenario = searchParams.get('new') === '1'
  // "Modifier" (crayon sur la liste des scénarios) arrive avec ?edit=<id> :
  // on récupère alors les vraies infos déjà enregistrées pour ce scénario
  // (et ses vraies étapes) depuis JSON Server.
  const editScenarioId = searchParams.get('edit')
  // "Créer un scénario personnalisé" (depuis CreateScenarioLanding) arrive
  // avec ?app=<id> : l'application est déjà choisie explicitement par
  // l'utilisateur, jamais présélectionnée automatiquement (voir l'effet de
  // synchronisation plus bas, qui ne retombe plus sur applications[0]).
  const appIdFromQuery = searchParams.get('app')

  const [steps, setSteps] = useState<ScenarioStep[]>(() =>
    isNewScenario ? [] : stepBridge.loadSteps() as unknown as ScenarioStep[] ?? initialSteps
  )
  // IDs des étapes qui existent réellement en base (mode édition) — sert à
  // savoir, à l'enregistrement, lesquelles PATCH-er vs POST-er vs supprimer.
  const [existingStepIds, setExistingStepIds] = useState<Set<string>>(new Set())
  const [loadingEdit, setLoadingEdit] = useState(!!editScenarioId)

  // Garde la page "Configuration détaillée" (create-step) synchronisée avec
  // la liste d'étapes : toute modification ici (ajout, édition, ordre,
  // suppression) est répercutée dans le pont de données.
  useEffect(() => {
    stepBridge.saveSteps(steps as unknown as stepBridge.CoreStep[])
  }, [steps])
  // Estimation réelle (pas une valeur inventée) du temps d'un seul passage
  // du scénario par UN utilisateur virtuel : somme des vraies pauses/pacing
  // déjà configurés par étape (voir CreateStep.tsx), ou le délai par défaut
  // de l'application pour les étapes pas encore configurées en détail.
  // Ne couvre volontairement PAS le ramp-up ni la répétition sur plusieurs
  // VUs — ces valeurs (Utilisateurs, Planification) ne sont pas encore
  // connues à cet écran (étape 1 du wizard).
  const estimatedSingleRunSeconds = useMemo(() => {
    const defaultWaitMs = loadDefaultTestSettings().stepDelayMs
    return steps.reduce((total, s) => {
      const details = stepBridge.loadStepDetails(s.id)
      const waitMs = details ? details.pauseBeforeMs + details.pacingAfterMs : defaultWaitMs
      return total + waitMs / 1000
    }, 0)
  }, [steps])

  const [selectedStepIds, setSelectedStepIds] = useState<string[]>([])
  // Suppression réelle et immédiate (pas un simple retrait du brouillon
  // local) — sans ça, une étape supprimée réapparaissait tant que le
  // scénario n'était pas réenregistré jusqu'au Résumé. `null`/`false` =
  // aucune confirmation affichée.
  const [deleteStepConfirmId, setDeleteStepConfirmId] = useState<string | null>(null)
  const [showDeleteSelectedConfirm, setShowDeleteSelectedConfirm] = useState(false)
  const [deletingSteps, setDeletingSteps] = useState(false)
  const savedMeta = stepBridge.loadScenarioMeta()
  const [scenarioId, setScenarioId] = useState<string | null>(editScenarioId)

  // CreateStep n'a aucun moyen de connaître le scénario en cours d'édition
  // via sa propre URL (elle ne transmet jamais "?edit=<id>") : on le
  // republie ici dans le pont à chaque changement, pour que CreateStep
  // puisse reconstruire une navigation de retour qui préserve le contexte
  // d'édition (sinon "Enregistrer le scénario" créerait un doublon au lieu
  // de mettre à jour le scénario existant — voir CreateStep.tsx).
  useEffect(() => {
    stepBridge.saveEditingScenarioId(scenarioId)
  }, [scenarioId])
  const [scenarioName, setScenarioName] = useState(
    savedMeta?.name ?? (isNewScenario ? '' : 'Parcours Utilisateur Complet')
  )
  const [application, setApplication] = useState(savedMeta?.application ?? '')
  const [applicationId, setApplicationId] = useState(savedMeta?.applicationId ?? appIdFromQuery ?? '')
  const [description, setDescription] = useState(
    savedMeta?.description ?? (isNewScenario ? '' : 'Test complet du parcours utilisateur sur le site e-commerce.')
  )

  // Mode édition : charge le vrai scénario + ses vraies étapes depuis
  // JSON Server, et pré-remplit tous les champs du formulaire.
  //
  // On revient ici (remount complet) chaque fois que l'utilisateur va
  // configurer une étape sur CreateStep puis revient — si un brouillon local
  // pour CE MÊME scénario existe déjà dans le pont (nom/étapes déjà
  // modifiés, étape ajoutée...), il ne faut PAS re-télécharger l'état
  // serveur : ça écraserait ces modifications non encore enregistrées. On ne
  // va chercher les vraies données que lors de la toute première entrée en
  // mode édition pour ce scénario.
  //
  // resumeDecisionRef fige le choix "reprendre le brouillon" vs "recharger
  // depuis JSON Server" (et la consommation du signal ponctuel du pont) une
  // seule fois par vraie entrée en mode édition — indépendamment d'un
  // éventuel doublon d'invocation de cet effet par React.StrictMode en
  // développement. Le fetch lui-même garde en revanche le mécanisme standard
  // d'annulation ci-dessous (laissé intact), qui gère correctement ce
  // doublon à sa manière habituelle.
  const resumeDecisionRef = useRef<{ editScenarioId: string; resuming: boolean } | null>(null)
  useEffect(() => {
    if (!editScenarioId) {
      setLoadingEdit(false)
      return
    }

    if (resumeDecisionRef.current?.editScenarioId !== editScenarioId) {
      const resumingLocalDraft =
        stepBridge.peekReturningFromStepConfig() &&
        stepBridge.loadEditingScenarioId() === editScenarioId &&
        stepBridge.loadSteps() !== null
      stepBridge.clearReturningFromStepConfig()
      resumeDecisionRef.current = { editScenarioId, resuming: resumingLocalDraft }
    }

    if (resumeDecisionRef.current.resuming) {
      const localSteps = stepBridge.loadSteps() ?? []
      setExistingStepIds(new Set(localSteps.filter((s) => !s.id.startsWith('tmp-')).map((s) => s.id)))
      setLoadingEdit(false)
      return
    }

    let cancelled = false
    setLoadingEdit(true)
    Promise.all([scenariosApi.getById(editScenarioId), stepsApi.getByScenario(editScenarioId)])
      .then(([scenario, apiSteps]) => {
        if (cancelled) return
        setScenarioId(String(scenario.id))
        setScenarioName(scenario.name)
        setApplicationId(String(scenario.applicationId))
        setDescription(scenario.description ?? '')
        // String(s.id) : json-server renvoie des ids numériques pour les
        // ressources créées sans id explicite, alors que tout le code de
        // cette page compare les ids en `===` en s'attendant à des strings
        // (le type TS le déclare, mais rien ne le garantit à l'exécution) —
        // sans cette conversion à la frontière API, cliquer sur une étape
        // pour la modifier ne la retrouve jamais (17 === "17" est faux), et
        // CreateStep retombe silencieusement sur ses valeurs par défaut
        // ("Login utilisateur" / "/auth/login") quelle que soit l'étape
        // réellement cliquée.
        const mappedSteps: ScenarioStep[] = apiSteps.map((s) => ({
          id: String(s.id),
          order: s.order,
          method: s.method,
          name: s.name,
          url: s.url,
          timeout: 'Status 200',
          status: 'Actif',
        }))
        setSteps(mappedSteps)
        setExistingStepIds(new Set(apiSteps.map((s) => String(s.id))))
      })
      .catch(() => showNotification("Impossible de charger le scénario à modifier.", 'danger'))
      .finally(() => { if (!cancelled) setLoadingEdit(false) })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editScenarioId])

  // Synchronise le nom d'application affiché avec l'ID sélectionné (source
  // de vérité pour la relation Scénario → Application) — comparaison en
  // string des deux côtés : json-server peut renvoyer des ids numériques
  // pour certaines applications alors que `e.target.value` d'un <select>
  // est toujours une string ; comparer strictement (`===`) sans coercion
  // faisait échouer silencieusement ce lookup dès qu'on choisissait une
  // application dont l'id n'était pas une string, laissant `application`
  // bloqué sur son ancienne valeur.
  //
  // Aucune présélection automatique de la première application ici — voir
  // CreateScenarioLanding.tsx, qui est désormais le seul point d'entrée
  // pour un nouveau scénario et impose un choix explicite.
  useEffect(() => {
    if (!applicationId) return
    const app = applications.find((a) => String(a.id) === String(applicationId))
    if (app) setApplication(app.name)
  }, [applicationId, applications])

  // Garde-fou : si on arrive sur cet écran sans application connue (ni
  // édition, ni ?app=, ni brouillon en cours dans le pont), il n'y a rien
  // de valide à afficher — on renvoie vers le choix d'application plutôt
  // que de présélectionner silencieusement la première de la liste.
  useEffect(() => {
    if (editScenarioId || appIdFromQuery || savedMeta?.applicationId) return
    navigate('/scenarios/new', { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Garde les infos générales du scénario (nom/application/description)
  // disponibles pour la suite du wizard (Configuration → ... → Résumé sur
  // CreateStep), afin que l'enregistrement final à l'étape Résumé
  // sauvegarde le scénario complet et pas seulement ses étapes.
  useEffect(() => {
    stepBridge.saveScenarioMeta({ name: scenarioName, application, applicationId, description })
  }, [scenarioName, application, applicationId, description])
  const [toastMessage, setToastMessage] = useState<string | null>(null)
  const [toastType, setToastType] = useState<'success' | 'danger'>('success')
  const [saving, setSaving] = useState(false)
  // Erreur inline sous "Nom du scénario", affichée au blur puis
  // systématiquement dès la tentative d'enregistrement — même pattern que
  // sur la page Applications.
  const [nameTouched, setNameTouched] = useState(false)
  const scenarioNameError = firstError(
    validateRequired(scenarioName, 'Le nom du scénario'),
    validateMaxLength(scenarioName, NAME_MAX_LENGTH, 'Le nom du scénario')
  )

  // Test modal state (vrais résultats HTTP — voir handleRunTest)
  const [showTestModal, setShowTestModal] = useState(false)
  const [isSimulating, setIsSimulating] = useState(false)
  const [simulationComplete, setSimulationComplete] = useState(false)
  const [liveTestSteps, setLiveTestSteps] = useState<LiveStepState[]>([])

  // Add / Edit step modal state
  const [showStepModal, setShowStepModal] = useState(false)
  // Anti double-clic sur "Ajouter l'étape" / "Enregistrer les modifications"
  // — handleSaveStep est synchrone (pas de requête réseau à ce stade), mais
  // un double-clic assez rapide peut encore déclencher deux appels avant le
  // re-render qui masquerait normalement le bouton.
  const [savingStep, setSavingStep] = useState(false)
  const [editingStepId, setEditingStepId] = useState<string | null>(null)
  const [stepFormMethod, setStepFormMethod] = useState<ScenarioStep['method']>('GET')
  const [stepFormName, setStepFormName] = useState('')
  const [stepFormUrl, setStepFormUrl] = useState('')
  const [stepFormTimeout, setStepFormTimeout] = useState('Status 200')
  const [stepFormStatus, setStepFormStatus] = useState<'Actif' | 'Inactif'>('Actif')
  const [stepFormTouched, setStepFormTouched] = useState<{ name?: boolean; url?: boolean }>({})
  const stepFormNameError = validateRequired(stepFormName, "Le nom de l'étape")
  const stepFormUrlError = firstError(validateRequired(stepFormUrl, 'La ressource'), validateStepUrl(stepFormUrl))
  const isStepFormValid = !stepFormNameError && !stepFormUrlError

  const showNotification = (msg: string, type: 'success' | 'danger' = 'success') => {
    setToastType(type)
    setToastMessage(msg)
    setTimeout(() => setToastMessage(null), 3000)
  }

  const stepperItems = [
    { number: 1, label: 'Étapes', active: true, completed: false },
    { number: 2, label: 'Configuration', active: false, completed: false },
    { number: 3, label: 'Utilisateurs', active: false, completed: false },
    { number: 4, label: 'Planification', active: false, completed: false },
    { number: 5, label: 'Résumé', active: false, completed: false },
  ]

  // Validation commune à "Suivant" et au clic sur le stepper (étapes 2-5) —
  // mêmes règles que handleSaveScenario, mais sans enregistrer : on ne
  // bloque l'accès à la suite du wizard que si l'Étape 1 est réellement
  // incomplète.
  const validateStepsScreen = (): boolean => {
    setNameTouched(true)
    if (scenarioNameError) {
      showNotification(scenarioNameError, 'danger')
      return false
    }
    if (!applicationId) {
      showNotification('Veuillez sélectionner une application.', 'danger')
      return false
    }
    if (steps.length === 0) {
      showNotification('Le scénario doit contenir au moins 1 étape.', 'danger')
      return false
    }
    return true
  }

  const goToWizardStep = (n: 2 | 3 | 4 | 5) => {
    if (!validateStepsScreen()) return
    if (steps[0]) stepBridge.setEditingStepId(steps[0].id, false)
    navigate(`/scenarios/create-step?wizard=${n}`)
  }

  const handleSelectAll = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.checked) {
      setSelectedStepIds(steps.map((s) => s.id))
    } else {
      setSelectedStepIds([])
    }
  }

  const handleSelectOne = (id: string) => {
    if (selectedStepIds.includes(id)) {
      setSelectedStepIds(selectedStepIds.filter((i) => i !== id))
    } else {
      setSelectedStepIds([...selectedStepIds, id])
    }
  }

  // Suppression réelle : une étape déjà enregistrée (id réel, pas "tmp-")
  // est immédiatement supprimée sur JSON Server — elle ne doit jamais
  // pouvoir "réapparaître" faute d'avoir réenregistré tout le scénario.
  const handleDeleteStep = async (id: string) => {
    if (deletingSteps) return
    setDeletingSteps(true)
    try {
      if (!id.startsWith('tmp-')) {
        await stepsApi.remove(id)
      }
      const updated = steps.filter((s) => String(s.id) !== String(id))
      updated.forEach((s, idx) => { s.order = idx + 1 })
      setSteps(updated)
      setSelectedStepIds((prev) => prev.filter((i) => String(i) !== String(id)))
      setExistingStepIds((prev) => {
        const next = new Set(prev)
        next.delete(id)
        return next
      })
      showNotification('Étape supprimée définitivement.')
    } catch (err) {
      showNotification(err instanceof Error ? err.message : "Erreur lors de la suppression de l'étape", 'danger')
    } finally {
      setDeletingSteps(false)
      setDeleteStepConfirmId(null)
    }
  }

  // Suppression en masse (sélection via la case "tout sélectionner" de
  // l'en-tête, ou une sélection partielle) — en parallèle : contrairement
  // à scenarioSave.ts (POST/PATCH avec un vrai corps JSON, sensibles aux
  // écritures concurrentes rapprochées sous json-server mono-thread), de
  // simples DELETE sur des ids distincts ne se marchent pas dessus, et
  // l'utilisateur attend que "tout" disparaisse d'un coup, pas étape par
  // étape.
  const handleDeleteSelectedSteps = async () => {
    if (deletingSteps || selectedStepIds.length === 0) return
    setDeletingSteps(true)
    try {
      await Promise.all(
        selectedStepIds
          .filter((id) => !id.startsWith('tmp-'))
          .map((id) => stepsApi.remove(id))
      )
      const removed = new Set(selectedStepIds.map(String))
      const updated = steps.filter((s) => !removed.has(String(s.id)))
      updated.forEach((s, idx) => { s.order = idx + 1 })
      setSteps(updated)
      setSelectedStepIds([])
      setExistingStepIds((prev) => {
        const next = new Set(prev)
        removed.forEach((id) => next.delete(id))
        return next
      })
      showNotification(`${selectedStepIds.length} étape(s) supprimée(s) définitivement.`)
    } catch (err) {
      showNotification(err instanceof Error ? err.message : 'Erreur lors de la suppression des étapes', 'danger')
    } finally {
      setDeletingSteps(false)
      setShowDeleteSelectedConfirm(false)
    }
  }

  const moveStepUp = (index: number) => {
    if (index === 0) return
    const updated = [...steps]
    const temp = updated[index]
    updated[index] = updated[index - 1]
    updated[index - 1] = temp
    updated.forEach((s, idx) => {
      s.order = idx + 1
    })
    setSteps(updated)
  }

  const moveStepDown = (index: number) => {
    if (index === steps.length - 1) return
    const updated = [...steps]
    const temp = updated[index]
    updated[index] = updated[index + 1]
    updated[index + 1] = temp
    updated.forEach((s, idx) => {
      s.order = idx + 1
    })
    setSteps(updated)
  }

  // Si des étapes précises sont cochées, le test ne porte que sur elles ;
  // sinon (aucune sélection), il porte sur l'ensemble du scénario.
  const stepsToTest = selectedStepIds.length > 0
    ? steps.filter((s) => selectedStepIds.includes(s.id))
    : steps

  // Envoie une vraie requête HTTP pour chaque étape sélectionnée (méthode,
  // headers, body, timeout tels que configurés via "Configurer les
  // détails") contre la vraie application choisie — jamais de résultat
  // simulé. Une erreur réseau/CORS/timeout réelle est affichée telle
  // quelle, jamais remplacée par un faux succès.
  const handleRunTest = async () => {
    const application = applications.find((a) => String(a.id) === String(applicationId))
    if (!application) {
      showNotification('Aucune application sélectionnée : impossible de tester.', 'danger')
      return
    }

    setShowTestModal(true)
    setIsSimulating(true)
    setSimulationComplete(false)

    // Repli sur les vraies données serveur si cette étape n'a jamais été
    // ouverte dans "Configuration" pendant cette session (cache local du
    // navigateur vide) — sans ça, "Tester" envoyait les requêtes sans
    // headers ni body pour toute étape créée via un scénario recommandé
    // (ou existante non encore ouverte), même si elle est correctement
    // configurée côté serveur, ce qui produisait de faux échecs (401/400).
    const fullSteps: Step[] = await Promise.all(
      stepsToTest.map(async (s) => {
        const details = stepBridge.loadStepDetails(s.id)
        const needsServerFallback = !details && !s.id.startsWith('tmp-')
        const serverStep = needsServerFallback ? await stepsApi.getById(s.id).catch(() => null) : null
        return {
          id: s.id,
          scenarioId: scenarioId ?? '',
          order: s.order,
          name: s.name,
          method: s.method,
          url: s.url,
          description: details?.description ?? serverStep?.description,
          headers: details?.headers ?? serverStep?.headers,
          bodyJson: details?.bodyJson ?? serverStep?.bodyJson,
          assertions: details?.assertions ?? serverStep?.assertions,
          timeoutMs: details?.timeoutMs ?? serverStep?.timeoutMs,
          active: details?.activeStep ?? serverStep?.active,
          followRedirects: details?.followRedirects ?? serverStep?.followRedirects,
          pauseBeforeMs: details?.pauseBeforeMs ?? serverStep?.pauseBeforeMs,
          pacingAfterMs: details?.pacingAfterMs ?? serverStep?.pacingAfterMs,
        }
      })
    )

    setLiveTestSteps(fullSteps.map((step) => ({ step, status: 'pending' })))

    const defaultTimeoutMs = loadDefaultTestSettings().httpTimeoutSeconds * 1000
    const variables = buildVariableMap(stepBridge.loadWizardConfig()?.testVariables)
    const results: LiveStepState[] = []
    for (let i = 0; i < fullSteps.length; i++) {
      const step = fullSteps[i]
      setLiveTestSteps((prev) => prev.map((ls, idx) => (idx === i ? { ...ls, status: 'running' } : ls)))
      const result = await runStep(step, application.url, defaultTimeoutMs, { variables })
      const updated: LiveStepState = { step, status: result.status, httpStatus: result.httpStatus, responseTimeMs: result.responseTimeMs, error: result.error }
      results.push(updated)
      setLiveTestSteps((prev) => prev.map((ls, idx) => (idx === i ? updated : ls)))
    }

    setIsSimulating(false)
    setSimulationComplete(true)
  }

  const handleSaveScenario = async () => {
    // Garde anti double-soumission : un double-clic (ou un appui rapide
    // répété) sur "Enregistrer le scénario" ne doit jamais déclencher deux
    // POST/PATCH — sans cette garde, le bouton restait cliquable pendant
    // toute la durée de l'enregistrement.
    if (saving) return
    setNameTouched(true)
    if (scenarioNameError) {
      showNotification(scenarioNameError, 'danger')
      return
    }
    if (!applicationId) {
      showNotification('Veuillez sélectionner une application.', 'danger')
      return
    }
    if (steps.length === 0) {
      showNotification('Le scénario doit contenir au moins 1 étape.', 'danger')
      return
    }

    setSaving(true)
    try {
      // Pour chaque étape, on récupère aussi sa configuration détaillée
      // (headers, body, assertions, timers) si elle a été renseignée via
      // la page "Configuration" du wizard.
      const localSteps: LocalStepDraft[] = steps.map((s, idx) => {
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

      // Reprend un éventuel brouillon des étapes 2-4 du wizard (Configuration/
      // Utilisateurs/Planification), déjà rempli dans cette session même si
      // l'utilisateur enregistre directement depuis l'Étape 1 sans être allé
      // jusqu'au Résumé — pour ne jamais perdre ce qui a déjà été configuré.
      const wizardConfig = stepBridge.loadWizardConfig()

      // Enregistrement réel sur JSON Server (POST si nouveau, PATCH si
      // modification) : le scénario apparaîtra donc dans la liste, lié à
      // l'application choisie via son vrai ID.
      const saved = await saveScenarioWithSteps({
        scenarioId,
        name: scenarioName.trim(),
        applicationId,
        description: description.trim() || undefined,
        createdBy: 'Vous',
        localSteps,
        existingStepIds,
        status: wizardConfig?.status,
        virtualUsers: wizardConfig?.virtualUsers,
        rampUpSeconds: wizardConfig?.rampUpSeconds,
        userProfile: wizardConfig?.userProfile,
        dataSource: wizardConfig?.dataSource,
        csvFileName: wizardConfig?.csvFileName || undefined,
        testVariables: wizardConfig?.testVariables,
        schedule: wizardConfig
          ? {
              executionType: wizardConfig.executionType,
              scheduledDate: wizardConfig.scheduledDate || undefined,
              scheduledTime: wizardConfig.scheduledTime || undefined,
              recurrence: wizardConfig.recurrence || undefined,
            }
          : undefined,
      })

      stepBridge.resetAll()
      showNotification(scenarioId ? 'Scénario mis à jour avec succès !' : 'Scénario enregistré avec succès !', 'success')
      setTimeout(() => {
        navigate('/scenarios')
      }, 800)
      return saved
    } catch (err) {
      showNotification(err instanceof Error ? err.message : "Erreur lors de l'enregistrement", 'danger')
    } finally {
      setSaving(false)
    }
  }

  const openAddStepModal = () => {
    setEditingStepId(null)
    setStepFormMethod('GET')
    setStepFormName('')
    setStepFormUrl('')
    setStepFormTimeout('Status 200')
    setStepFormStatus('Actif')
    setStepFormTouched({})
    setSavingStep(false)
    setShowStepModal(true)
  }

  const openEditStepModal = (step: ScenarioStep) => {
    setEditingStepId(step.id)
    setStepFormMethod(step.method)
    setStepFormName(step.name)
    setStepFormUrl(step.url)
    setStepFormTimeout(step.timeout)
    setStepFormStatus(step.status)
    setStepFormTouched({})
    setSavingStep(false)
    setShowStepModal(true)
  }

  const closeStepModal = () => {
    setShowStepModal(false)
    setSavingStep(false)
  }

  const handleSaveStep = () => {
    if (savingStep) return
    setStepFormTouched({ name: true, url: true })
    if (!isStepFormValid) return
    setSavingStep(true)

    if (editingStepId !== null) {
      // Update an existing step in place
      const updatedSteps = steps.map((s) =>
        s.id === editingStepId
          ? {
              ...s,
              method: stepFormMethod,
              name: stepFormName.trim(),
              url: stepFormUrl.trim(),
              timeout: stepFormTimeout.trim() || 'Status 200',
              status: stepFormStatus,
            }
          : s
      )
      setSteps(updatedSteps)
      stepBridge.saveSteps(updatedSteps)
      showNotification('Étape modifiée avec succès')
      setShowStepModal(false)
    } else {
      // Append a new step to the scenario
      const nextId = makeTempStepId()
      const newStep: ScenarioStep = {
        id: nextId,
        order: steps.length + 1,
        method: stepFormMethod,
        name: stepFormName.trim(),
        url: stepFormUrl.trim(),
        timeout: stepFormTimeout.trim() || 'Status 200',
        status: stepFormStatus,
      }
      const updatedSteps = [...steps, newStep]
      setSteps(updatedSteps)
      stepBridge.saveSteps(updatedSteps)
      showNotification('Étape ajoutée avec succès')
      setShowStepModal(false)

      // Reste sur cette page : la nouvelle étape doit apparaître tout de
      // suite dans le tableau, sous le nom du scénario (voir "Étapes du
      // scénario" plus bas). La configuration détaillée (headers, body,
      // assertions...) reste accessible à tout moment via l'icône
      // "Configurer les détails" de la ligne, sans redirection forcée.
    }
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

      {/* Modal Simulation Test Scénario */}
      {showTestModal && (
        <div
          className="modal fade show d-block"
          tabIndex={-1}
          style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1050 }}
        >
          <div className="modal-dialog modal-dialog-centered modal-lg">
            <div
              className="modal-content"
              style={{
                borderRadius: 'var(--pt-radius)',
                border: '1px solid var(--pt-border)',
                background: 'var(--pt-card-bg)',
              }}
            >
              <div className="modal-header">
                <h5 className="modal-title d-flex align-items-center gap-2" style={{ fontSize: '16px', fontWeight: 600 }}>
                  <i className="bi bi-play-circle-fill text-primary"></i>
                  {selectedStepIds.length > 0
                    ? `Test — ${selectedStepIds.length} étape${selectedStepIds.length > 1 ? 's' : ''} sélectionnée${selectedStepIds.length > 1 ? 's' : ''}`
                    : 'Test du Scénario'}
                </h5>
                <button
                  type="button"
                  className="btn-close"
                  onClick={() => setShowTestModal(false)}
                ></button>
              </div>

              <div className="modal-body p-4">
                {isSimulating ? (
                  <div className="text-center py-5">
                    <div className="spinner-border text-primary mb-3" role="status" style={{ width: '3rem', height: '3rem' }}>
                      <span className="visually-hidden">Chargement...</span>
                    </div>
                    <h6 style={{ fontWeight: 600 }}>Envoi des vraies requêtes HTTP...</h6>
                    <p style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>
                      {selectedStepIds.length > 0
                        ? `Test de ${stepsToTest.length} étape${stepsToTest.length > 1 ? 's' : ''} sélectionnée${stepsToTest.length > 1 ? 's' : ''} contre l'application réelle`
                        : `Test séquentiel des ${stepsToTest.length} étapes contre l'application réelle`}
                    </p>
                  </div>
                ) : simulationComplete ? (
                  (() => {
                    // Les étapes "skipped" (Étape active décochée) n'ont envoyé
                    // aucune requête : ni un succès ni un échec, exclues du calcul.
                    const executedSteps = liveTestSteps.filter((s) => s.status !== 'skipped')
                    const passedCount = executedSteps.filter((s) => s.status === 'success').length
                    const allPassed = passedCount === executedSteps.length && executedSteps.length > 0
                    return (
                  <div className="d-flex flex-column gap-3">
                    <div
                      className="p-3 rounded d-flex align-items-center justify-content-between"
                      style={{ background: allPassed ? 'var(--pt-success-light)' : 'var(--pt-danger-light)', border: `1px solid ${allPassed ? 'var(--pt-success)' : 'var(--pt-danger)'}` }}
                    >
                      <div className="d-flex align-items-center gap-2">
                        <i className={`bi ${allPassed ? 'bi-check-circle-fill' : 'bi-exclamation-triangle-fill'}`} style={{ fontSize: '20px', color: allPassed ? 'var(--pt-success)' : 'var(--pt-danger)' }}></i>
                        <div>
                          <strong style={{ fontSize: '14px', color: 'var(--pt-text)' }}>
                            {allPassed ? 'Succès Global' : 'Échec'} — {passedCount}/{executedSteps.length} étape{executedSteps.length > 1 ? 's' : ''} passée{executedSteps.length > 1 ? 's' : ''}
                            {executedSteps.length < liveTestSteps.length ? ` (${liveTestSteps.length - executedSteps.length} ignorée${liveTestSteps.length - executedSteps.length > 1 ? 's' : ''})` : ''}
                          </strong>
                          <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>
                            Résultats réels — chaque étape active a envoyé une vraie requête HTTP à l'application.
                          </div>
                        </div>
                      </div>
                      <span className={`pt-pill ${allPassed ? 'success' : 'danger'}`}>
                        {executedSteps.length > 0 ? Math.round((passedCount / executedSteps.length) * 100) : 0}% Réussi
                      </span>
                    </div>

                    <h6 style={{ fontSize: '13.5px', fontWeight: 600, marginTop: '8px', marginBottom: 0 }}>
                      Détail par étape :
                    </h6>

                    <div className="pt-table-wrapper" style={{ maxHeight: '250px', overflowY: 'auto' }}>
                      <table className="pt-table" style={{ fontSize: '12.5px' }}>
                        <thead>
                          <tr style={{ background: 'var(--pt-bg)' }}>
                            <th style={{ width: '40px' }}>#</th>
                            <th style={{ width: '70px' }}>Méthode</th>
                            <th>Étape</th>
                            <th>Status</th>
                            <th>Temps</th>
                            <th>Résultat</th>
                          </tr>
                        </thead>
                        <tbody>
                          {liveTestSteps.map((ls, idx) => (
                            <tr key={ls.step.id}>
                              <td style={{ fontWeight: 600 }}>#{idx + 1}</td>
                              <td>
                                <span
                                  style={{
                                    padding: '0.15rem 0.4rem',
                                    borderRadius: '4px',
                                    fontSize: '11px',
                                    fontWeight: 700,
                                    background: ls.step.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)',
                                    color: ls.step.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)',
                                  }}
                                >
                                  {ls.step.method}
                                </span>
                              </td>
                              <td style={{ fontWeight: 500 }}>{ls.step.name}</td>
                              <td>
                                <span className={`pt-pill ${ls.status === 'success' ? 'success' : ls.status === 'skipped' ? 'neutral' : 'danger'}`} style={{ fontSize: '11px' }}>
                                  {ls.status === 'skipped' ? '—' : ls.httpStatus ?? '—'}
                                </span>
                              </td>
                              <td style={{ color: 'var(--pt-text-muted)' }}>{ls.responseTimeMs !== undefined ? `${ls.responseTimeMs} ms` : '—'}</td>
                              <td>
                                {ls.status === 'success' ? (
                                  <span style={{ color: 'var(--pt-success)', fontWeight: 600 }}>
                                    <i className="bi bi-check-lg me-1"></i> Passé
                                  </span>
                                ) : ls.status === 'skipped' ? (
                                  <span style={{ color: 'var(--pt-text-muted)', fontWeight: 600 }}>
                                    <i className="bi bi-skip-forward me-1"></i> Ignorée (étape inactive)
                                  </span>
                                ) : (
                                  <span style={{ color: 'var(--pt-danger)', fontWeight: 600 }} title={ls.error}>
                                    <i className="bi bi-x-lg me-1"></i> Échec{ls.error ? ` — ${ls.error}` : ''}
                                  </span>
                                )}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                    )
                  })()
                ) : null}
              </div>

              <div className="modal-footer d-flex justify-content-between">
                <button
                  type="button"
                  className="pt-btn-outline"
                  onClick={() => handleRunTest()}
                  disabled={isSimulating}
                >
                  <i className="bi bi-arrow-clockwise me-1"></i> Relancer
                </button>
                <button
                  type="button"
                  className="pt-btn-primary"
                  onClick={() => setShowTestModal(false)}
                >
                  Fermer
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Modal Ajouter / Modifier une étape */}
      {showStepModal && (
        <div
          className="modal fade show d-block"
          tabIndex={-1}
          style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1050 }}
        >
          <div className="modal-dialog modal-dialog-centered">
            <div
              className="modal-content"
              style={{
                borderRadius: 'var(--pt-radius)',
                border: '1px solid var(--pt-border)',
                background: 'var(--pt-card-bg)',
              }}
            >
              <div className="modal-header">
                <h5 className="modal-title d-flex align-items-center gap-2" style={{ fontSize: '16px', fontWeight: 600 }}>
                  <i className="bi bi-list-check text-primary"></i>
                  {editingStepId !== null ? "Modifier l'étape" : 'Ajouter une étape'}
                </h5>
                <button type="button" className="btn-close" onClick={closeStepModal}></button>
              </div>

              <div className="modal-body p-4">
                <div className="row g-3">
                  <div className="col-12 col-md-4">
                    <label className="pt-form-label">Méthode</label>
                    <select
                      className="pt-form-control"
                      value={stepFormMethod}
                      onChange={(e) => setStepFormMethod(e.target.value as ScenarioStep['method'])}
                    >
                      <option value="GET">GET</option>
                      <option value="POST">POST</option>
                      <option value="PUT">PUT</option>
                      <option value="PATCH">PATCH</option>
                      <option value="DELETE">DELETE</option>
                      <option value="HEAD">HEAD</option>
                      <option value="OPTIONS">OPTIONS</option>
                      <option value="TRACE">TRACE</option>
                    </select>
                  </div>

                  <div className="col-12 col-md-8">
                    <label className="pt-form-label">Nom de l'étape *</label>
                    <input
                      type="text"
                      className="pt-form-control"
                      value={stepFormName}
                      onChange={(e) => setStepFormName(e.target.value)}
                      onBlur={() => setStepFormTouched((t) => ({ ...t, name: true }))}
                      placeholder="Ex : Voir les produits"
                    />
                    {stepFormTouched.name && stepFormNameError && (
                      <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{stepFormNameError}</div>
                    )}
                  </div>

                  <div className="col-12">
                    <label className="pt-form-label">URL / Ressource *</label>
                    <input
                      type="text"
                      className="pt-form-control"
                      value={stepFormUrl}
                      onChange={(e) => setStepFormUrl(e.target.value)}
                      onBlur={() => setStepFormTouched((t) => ({ ...t, url: true }))}
                      placeholder="/produits"
                    />
                    {stepFormTouched.url && stepFormUrlError && (
                      <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{stepFormUrlError}</div>
                    )}
                  </div>
                </div>
              </div>

              <div className="modal-footer d-flex justify-content-between">
                <button type="button" className="pt-btn-outline" onClick={closeStepModal}>
                  Annuler
                </button>
                <button type="button" className="pt-btn-primary" onClick={handleSaveStep} disabled={savingStep || !isStepFormValid}>
                  <i className="bi bi-check-lg me-1"></i>
                  {savingStep ? 'Enregistrement...' : editingStepId !== null ? 'Enregistrer les modifications' : "Ajouter l'étape"}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Top Header with Breadcrumb and Actions */}
      <div className="pt-page-header">
        <div>
          <button
            className="btn btn-link p-0"
            onClick={() => navigate('/scenarios')}
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
            <i className="bi bi-arrow-left"></i> Retour aux scénarios
          </button>
          <div className="page-title">
            <h1>Créer / Modifier un scénario</h1>
          </div>
        </div>
        <div className="d-flex align-items-center gap-2">
          <TopBar searchPlaceholder="" />
        </div>
      </div>

      {/* Le formulaire est prérempli avec des valeurs par défaut le temps que
          le vrai scénario (et ses vraies étapes) se chargent depuis JSON
          Server — on le signale pour éviter que l'utilisateur ne modifie ces
          valeurs provisoires avant qu'elles ne soient remplacées par les
          vraies données. */}
      {loadingEdit && (
        <div className="pt-alert-banner mb-3">
          <i className="bi bi-arrow-repeat pt-spin"></i>
          Chargement du scénario à modifier...
        </div>
      )}

      {/* Stepper Navigation */}
      <div className="pt-card mb-4" style={{ padding: '1.25rem 1.5rem' }}>
        <div
          className="d-flex align-items-center justify-content-between"
          style={{ position: 'relative' }}
        >
          {stepperItems.map((step, index) => {
            const isLast = index === stepperItems.length - 1
            return (
              <React.Fragment key={step.number}>
                <div
                  className="d-flex align-items-center gap-2"
                  style={{ zIndex: 2, cursor: 'pointer' }}
                  onClick={() => {
                    if (step.number >= 2 && step.number <= 5) {
                      goToWizardStep(step.number as 2 | 3 | 4 | 5)
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

      {/* Summary Section */}
      <div className="pt-card mb-4">
        <div
          className="d-flex align-items-center gap-2 pb-3 mb-3"
          style={{ borderBottom: '1px solid var(--pt-border)' }}
        >
          <i className="bi bi-info-circle" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
          <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>
            Infos du scénario
          </h6>
        </div>

        <div className="row g-3">
          <div className="col-12 col-md-6 col-lg-4">
            <label className="pt-form-label">Nom du scénario *</label>
            <input
              type="text"
              className="pt-form-control"
              value={scenarioName}
              onChange={(e) => setScenarioName(e.target.value)}
              onBlur={() => setNameTouched(true)}
            />
            {nameTouched && scenarioNameError && (
              <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{scenarioNameError}</div>
            )}
          </div>

          <div className="col-12 col-md-6 col-lg-4">
            <label className="pt-form-label">Application</label>
            <select
              className="pt-form-control"
              value={applicationId}
              disabled={!!applicationId}
              title={applicationId ? "L'application d'un scénario ne peut plus être changée une fois sélectionnée." : undefined}
              onChange={(e) => {
                setApplicationId(e.target.value)
                const app = applications.find((a) => String(a.id) === e.target.value)
                if (app) setApplication(app.name)
              }}
            >
              {applications.filter(a => a.status === 'Actif').map(a => (
                <option key={a.id} value={a.id}>{a.name}</option>
              ))}
            </select>
            {applicationId && (
              <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>
                <i className="bi bi-lock-fill me-1"></i>
                L'application n'est plus modifiable après sélection.
              </div>
            )}
          </div>

          <div className="col-12">
            <label className="pt-form-label">Description</label>
            <textarea
              className="pt-form-control"
              rows={2}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            ></textarea>
          </div>

          <div className="col-12">
            <button
              className="pt-btn-primary"
              style={{ fontSize: '12.5px' }}
              onClick={openAddStepModal}
              disabled={loadingEdit}
            >
              <i className="bi bi-plus-lg"></i> Ajouter une étape
            </button>
          </div>
        </div>
      </div>

      {/* Steps Table Section */}
      <div className="pt-card" style={{ padding: 0 }}>
        {/* Table Header Controls */}
        <div
          className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2"
          style={{ borderBottom: '1px solid var(--pt-border)' }}
        >
          <div>
            <h6 style={{ fontSize: '14.5px', fontWeight: 600, margin: 0 }}>
              Étapes du scénario <span className="pt-pill neutral ms-1">{steps.length} étapes</span>
            </h6>
          </div>
          {selectedStepIds.length > 0 && (
            <button
              className="pt-btn-outline"
              style={{ fontSize: '12.5px', color: 'var(--pt-danger)', borderColor: 'var(--pt-danger)' }}
              onClick={() => setShowDeleteSelectedConfirm(true)}
              disabled={deletingSteps}
            >
              <i className="bi bi-trash me-1"></i> Supprimer la sélection ({selectedStepIds.length})
            </button>
          )}
        </div>

        {/* Steps Table */}
        <div className="pt-table-wrapper">
          <table className="pt-table">
            <thead>
              <tr>
                <th style={{ width: '40px' }}>
                  <input
                    type="checkbox"
                    checked={steps.length > 0 && selectedStepIds.length === steps.length}
                    onChange={handleSelectAll}
                  />
                </th>
                <th style={{ width: '60px' }}>#</th>
                <th style={{ width: '100px' }}>Méthode</th>
                <th>Nom</th>
                <th>URL / Ressource</th>
                <th>Attente</th>
                <th style={{ width: '100px' }}>Statut</th>
                <th style={{ textAlign: 'right', width: '220px' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {steps.length === 0 ? (
                <tr>
                  <td colSpan={8} className="text-center py-4 text-muted">
                    Aucune étape dans ce scénario. Cliquez sur "+ Ajouter une étape" pour commencer.
                  </td>
                </tr>
              ) : (
                steps.map((step, index) => {
                  const isSelected = selectedStepIds.includes(step.id)
                  const isGet = step.method === 'GET'

                  return (
                    <tr
                      key={step.id}
                      style={{ background: isSelected ? 'var(--pt-sidebar-item-hover)' : undefined }}
                    >
                      <td>
                        <input
                          type="checkbox"
                          checked={isSelected}
                          onChange={() => handleSelectOne(step.id)}
                        />
                      </td>
                      <td>
                        <span
                          style={{
                            fontSize: '12.5px',
                            fontWeight: 600,
                            color: 'var(--pt-text-muted)',
                          }}
                        >
                          #{index + 1}
                        </span>
                      </td>
                      <td>
                        <span
                          style={{
                            display: 'inline-block',
                            padding: '0.2rem 0.6rem',
                            borderRadius: '6px',
                            fontSize: '11.5px',
                            fontWeight: 700,
                            background: isGet ? 'var(--pt-primary-light)' : 'var(--pt-success-light)',
                            color: isGet ? 'var(--pt-primary)' : 'var(--pt-success)',
                            letterSpacing: '0.02em',
                          }}
                        >
                          {step.method}
                        </span>
                      </td>
                      <td>
                        <div className="d-flex align-items-center gap-2">
                          <Link
                            to="/scenarios/create-step"
                            onClick={() => stepBridge.setEditingStepId(step.id, false)}
                            title="Configurer les détails de cette étape"
                            style={{
                              fontSize: '13.5px',
                              fontWeight: 600,
                              color: 'var(--pt-text)',
                              textDecoration: 'none',
                            }}
                          >
                            {step.name}
                          </Link>
                        </div>
                      </td>
                      <td>
                        <code
                          style={{
                            fontSize: '12.5px',
                            color: 'var(--pt-primary)',
                            background: 'rgba(79,70,229,0.06)',
                            padding: '0.15rem 0.4rem',
                            borderRadius: '4px',
                          }}
                        >
                          {step.url}
                        </code>
                      </td>
                      <td>
                        <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
                          {step.timeout}
                        </span>
                      </td>
                      <td>
                        <span className={`pt-pill ${step.status === 'Actif' ? 'success' : 'neutral'}`} style={{ fontSize: '11px' }}>
                          {step.status}
                        </span>
                      </td>
                      <td>
                        <div className="d-flex justify-content-end gap-1">
                          {/* Flèche haut */}
                          <button
                            className="topbar-icon"
                            title="Déplacer vers le haut"
                            disabled={index === 0}
                            style={{
                              width: '32px',
                              height: '32px',
                              opacity: index === 0 ? 0.4 : 1,
                              cursor: index === 0 ? 'not-allowed' : 'pointer',
                            }}
                            onClick={() => moveStepUp(index)}
                          >
                            <i className="bi bi-arrow-up" style={{ fontSize: '13px' }}></i>
                          </button>

                          {/* Flèche bas */}
                          <button
                            className="topbar-icon"
                            title="Déplacer vers le bas"
                            disabled={index === steps.length - 1}
                            style={{
                              width: '32px',
                              height: '32px',
                              opacity: index === steps.length - 1 ? 0.4 : 1,
                              cursor: index === steps.length - 1 ? 'not-allowed' : 'pointer',
                            }}
                            onClick={() => moveStepDown(index)}
                          >
                            <i className="bi bi-arrow-down" style={{ fontSize: '13px' }}></i>
                          </button>

                          {/* Modifier (Méthode / Nom / URL rapide) */}
                          <button
                            className="topbar-icon"
                            title="Modifier cette étape"
                            style={{ width: '32px', height: '32px' }}
                            onClick={() => openEditStepModal(step)}
                          >
                            <i className="bi bi-pencil" style={{ fontSize: '13px' }}></i>
                          </button>

                          {/* Configurer (Headers, Body, Assertions, Timers...) */}
                          <button
                            className="topbar-icon"
                            title="Configurer les détails (headers, body, assertions...)"
                            style={{ width: '32px', height: '32px' }}
                            onClick={() => {
                              stepBridge.setEditingStepId(step.id, false)
                              navigate('/scenarios/create-step')
                            }}
                          >
                            <i className="bi bi-sliders" style={{ fontSize: '13px' }}></i>
                          </button>

                          {/* Supprimer */}
                          <button
                            className="topbar-icon"
                            title="Supprimer"
                            style={{ width: '32px', height: '32px' }}
                            onClick={() => setDeleteStepConfirmId(step.id)}
                          >
                            <i
                              className="bi bi-trash"
                              style={{ fontSize: '13px', color: 'var(--pt-danger)' }}
                            ></i>
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>

        {/* Footer Navigation Bar */}
        <div
          className="d-flex justify-content-between align-items-center p-3"
          style={{ borderTop: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}
        >
          <span style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>
            {steps.length > 0 && (
              <>
                <i className="bi bi-clock me-1"></i> Estimation d'un passage (1 utilisateur) : ~{' '}
                {estimatedSingleRunSeconds >= 60
                  ? `${Math.floor(estimatedSingleRunSeconds / 60)} min ${Math.round(estimatedSingleRunSeconds % 60)} sec`
                  : `${Math.round(estimatedSingleRunSeconds)} sec`}
                {' '}<span title="Ne couvre pas le ramp-up ni la répétition sur plusieurs utilisateurs virtuels, configurés aux étapes suivantes.">
                  <i className="bi bi-info-circle" style={{ fontSize: '11px' }}></i>
                </span>
              </>
            )}
          </span>

          <div className="d-flex align-items-center gap-2">
            <button className="pt-btn-outline" onClick={() => navigate('/scenarios')}>
              Retour aux scénarios
            </button>
            <button className="pt-btn-outline" onClick={handleRunTest}>
              <i className="bi bi-play-circle text-success me-1"></i> Tester
            </button>
            <button
              className="pt-btn-primary"
              onClick={handleSaveScenario}
              disabled={loadingEdit || saving || !!scenarioNameError || !applicationId || steps.length === 0}
            >
              {saving ? <><i className="bi bi-arrow-repeat me-1 pt-spin"></i> Enregistrement...</> : <><i className="bi bi-check-lg me-1"></i> Enregistrer le scénario</>}
            </button>
            <button className="pt-btn-primary" onClick={() => goToWizardStep(2)} disabled={loadingEdit}>
              Suivant <i className="bi bi-arrow-right ms-1"></i>
            </button>
          </div>
        </div>
      </div>

      {/* Confirmation de suppression — la suppression d'étape est désormais
          réelle et immédiate (voir handleDeleteStep), donc irréversible :
          une confirmation est indispensable avant d'agir. */}
      {deleteStepConfirmId !== null && (
        <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1060 }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content">
              <div className="modal-body p-4">
                <h6 style={{ fontWeight: 700 }}>Supprimer cette étape ?</h6>
                <p className="text-muted mb-0" style={{ fontSize: '13.5px' }}>
                  Cette étape sera supprimée définitivement, tout de suite. Cette action ne peut pas être annulée.
                </p>
              </div>
              <div className="modal-footer">
                <button className="pt-btn-outline" onClick={() => setDeleteStepConfirmId(null)} disabled={deletingSteps}>
                  Annuler
                </button>
                <button
                  className="pt-btn-primary"
                  style={{ background: 'var(--pt-danger)', borderColor: 'var(--pt-danger)' }}
                  onClick={() => deleteStepConfirmId && handleDeleteStep(deleteStepConfirmId)}
                  disabled={deletingSteps}
                >
                  {deletingSteps ? <><i className="bi bi-arrow-repeat me-1 pt-spin"></i> Suppression...</> : <><i className="bi bi-trash me-1"></i> Oui, supprimer</>}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {showDeleteSelectedConfirm && (
        <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1060 }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content">
              <div className="modal-body p-4">
                <h6 style={{ fontWeight: 700 }}>Supprimer {selectedStepIds.length} étape(s) ?</h6>
                <p className="text-muted mb-0" style={{ fontSize: '13.5px' }}>
                  {selectedStepIds.length === steps.length
                    ? 'Toutes les étapes du scénario seront supprimées définitivement, tout de suite.'
                    : 'Les étapes sélectionnées seront supprimées définitivement, tout de suite.'}
                  {' '}Cette action ne peut pas être annulée.
                </p>
              </div>
              <div className="modal-footer">
                <button className="pt-btn-outline" onClick={() => setShowDeleteSelectedConfirm(false)} disabled={deletingSteps}>
                  Annuler
                </button>
                <button
                  className="pt-btn-primary"
                  style={{ background: 'var(--pt-danger)', borderColor: 'var(--pt-danger)' }}
                  onClick={handleDeleteSelectedSteps}
                  disabled={deletingSteps}
                >
                  {deletingSteps ? <><i className="bi bi-arrow-repeat me-1 pt-spin"></i> Suppression...</> : <><i className="bi bi-trash me-1"></i> Oui, supprimer</>}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default CreateScenario
