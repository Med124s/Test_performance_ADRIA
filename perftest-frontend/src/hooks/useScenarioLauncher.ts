import { useRef, useState } from 'react'
import { Scenario, Step, StepResult } from '../types'
import { stepsApi } from '../services/api/steps'
import { executionsApi, computeExecutionStatus } from '../services/api/executions'
import { applicationsApi } from '../services/api/applications'
import { serverSnapshotApi } from '../services/api/serverSnapshot'
import { localMonitoringApi } from '../services/api/localMonitoring'
import { loadDefaultTestSettings } from '../utils/defaultTestSettings'
import { runStep, buildVariableMap, sleep, LiveStepState } from '../services/stepRunner'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

const defaultTestSettings = loadDefaultTestSettings()

// Garde-fou de sécurité : cette page envoie de VRAIES requêtes HTTP depuis
// le navigateur vers de vraies applications (parfois des domaines publics
// tiers, ex. dummyjson.com). Sans plafond, un VUs=10000 saisi dans la
// modale déclencherait une vraie attaque par déni de service involontaire.
// Le nombre réellement exécuté est plafonné ici — et c'est CE nombre réel,
// jamais la valeur saisie, qui est affiché/stocké (voir handleLaunch).
const MAX_REAL_VUS = 50

export type { LiveStepState }

export interface LaunchForm {
  scenarioId: string
  applicationId: string
  virtualUsers: number
  duration: number
  rampUp: number
  thinkTime: number
  debit: string
  stopMode: 'auto' | 'manual'
}

/**
 * Encapsule tout le flux "lancer un scénario" (modale de configuration,
 * création d'une vraie execution dans JSON Server, progression étape par
 * étape écrite en direct sur le serveur) — partagé entre la page Exécutions
 * (bouton "Nouvelle exécution") et la page Scénarios (bouton "Exécuter",
 * qui doit rester sur place plutôt que de naviguer) pour ne pas dupliquer
 * cette logique à deux endroits.
 */
export function useScenarioLauncher(onExecuted?: () => void | Promise<void>) {
  const { canEdit } = useAuth()
  const { showToast } = useToast()
  const [showLaunchModal, setShowLaunchModal] = useState(false)
  const [launchStep, setLaunchStep] = useState<1 | 2 | 3>(1)
  const [launching, setLaunching] = useState(false)
  const [liveSteps, setLiveSteps] = useState<LiveStepState[]>([])
  const [launchError, setLaunchError] = useState<string | null>(null)

  // ------------------------------------------------------------
  // Contrôle réel de l'exécution (Pause/Reprendre/Annuler) — partagé par
  // tous les VUs en cours. `controlRef` (pas du state React) est la source
  // de vérité lue en direct dans la boucle async de chaque VU : un state
  // React ne serait visible qu'au prochain rendu, trop tard pour interrompre
  // une boucle déjà en cours. `controlState` n'existe qu'en miroir pour que
  // l'UI (boutons Pause/Reprendre/Annuler) se re-rende correctement.
  //
  // Limite honnête : ce contrôle ne vit que tant que ce hook reste monté
  // (l'onglet/la page qui a lancé le test). Fermer l'onglet ou recharger la
  // page fait perdre le contrôle réel sur l'exécution en cours — il n'y a
  // aucun processus serveur derrière (voir handleLaunch : tout tourne dans
  // le navigateur).
  type LauncherControlState = 'running' | 'paused' | 'cancelled'
  const controlRef = useRef<{ state: LauncherControlState }>({ state: 'running' })
  const [controlState, setControlState] = useState<LauncherControlState>('running')
  const abortControllerRef = useRef<AbortController | null>(null)
  // Lit `controlRef.current.state` à travers un type large à chaque appel :
  // TypeScript, sinon, "rétrécit" ce type après un premier test (ex. `!==
  // 'cancelled'` dans une condition de boucle) et considère les tests
  // suivants comme impossibles — alors que la vraie valeur peut changer
  // entre deux `await` via cancelExecution()/pauseExecution() appelés
  // depuis un autre gestionnaire d'événement pendant que la boucle tourne.
  const isCancelled = () => (controlRef.current.state as LauncherControlState) === 'cancelled'

  const pauseExecution = () => {
    if (controlRef.current.state !== 'running') return
    controlRef.current.state = 'paused'
    setControlState('paused')
  }
  const resumeExecution = () => {
    if (controlRef.current.state !== 'paused') return
    controlRef.current.state = 'running'
    setControlState('running')
  }
  const cancelExecution = () => {
    if (controlRef.current.state === 'cancelled') return
    controlRef.current.state = 'cancelled'
    setControlState('cancelled')
    // "laisser terminer une requête déjà en cours" (Pause) ne s'applique
    // plus une fois Annulé : on interrompt aussi ce qui est en vol.
    abortControllerRef.current?.abort()
  }
  /** Bloque tant que l'exécution est en pause ; rend la main immédiatement
   * si elle est annulée (l'appelant vérifie l'état juste après). */
  const waitIfPaused = async () => {
    while (controlRef.current.state === 'paused') {
      await sleep(150)
    }
  }
  const [form, setForm] = useState<LaunchForm>({
    scenarioId: '',
    applicationId: '',
    virtualUsers: defaultTestSettings.concurrency,
    duration: 300,
    rampUp: defaultTestSettings.rampUpSeconds,
    thinkTime: defaultTestSettings.stepDelayMs,
    debit: '',
    stopMode: 'auto',
  })

  /** Ouverture générique ("⚡ Lancer un Test" / "Nouvelle exécution") : ni
   * application ni scénario présélectionnés — l'utilisateur choisit
   * l'application d'abord, puis un scénario parmi ceux de cette
   * application (voir LaunchScenarioModal). Réinitialise le formulaire à
   * chaque ouverture pour ne jamais laisser un choix précédent paraître
   * présélectionné. */
  const openModal = () => {
    if (!canEdit) return
    setForm((p) => ({ ...p, scenarioId: '', applicationId: '' }))
    setLaunchStep(1)
    setLaunchError(null)
    setShowLaunchModal(true)
  }

  /** Change l'application ciblée : réinitialise le scénario choisi (un
   * scénario d'une autre application n'a plus de sens une fois
   * l'application changée) — l'utilisateur re-choisit un scénario parmi
   * ceux de la nouvelle application. */
  const selectApplication = (applicationId: string) => {
    setForm((p) => ({
      ...p,
      applicationId,
      scenarioId: '',
      virtualUsers: defaultTestSettings.concurrency,
      rampUp: defaultTestSettings.rampUpSeconds,
    }))
  }

  // Change le scénario ciblé par la modale : le scénario devient la valeur
  // par défaut de l'exécution (VUs/Ramp-up) — l'utilisateur peut ensuite la
  // surcharger librement dans le formulaire (form), sans jamais modifier le
  // scénario original (voir LaunchScenarioModal, badge "Valeur du scénario").
  const selectScenario = (scenario: Scenario | undefined) => {
    setForm((p) => ({
      ...p,
      scenarioId: scenario?.id ?? '',
      applicationId: scenario?.applicationId ?? p.applicationId,
      virtualUsers: scenario?.virtualUsers ?? defaultTestSettings.concurrency,
      rampUp: scenario?.rampUpSeconds ?? defaultTestSettings.rampUpSeconds,
    }))
  }

  /** Ouvre directement la modale pré-remplie pour un scénario donné (bouton
   * "Exécuter" sur une ligne de la liste). */
  const openForScenario = (scenario: Scenario) => {
    if (!canEdit) return
    selectScenario(scenario)
    setLaunchStep(1)
    setLaunchError(null)
    setShowLaunchModal(true)
  }

  const closeModal = () => {
    if (launching) return
    setShowLaunchModal(false)
    setLaunchStep(1)
  }

  /** Exécute UN utilisateur virtuel : rejoue la séquence complète des étapes
   * UNE seule fois (pas de répétition en boucle — Duration reste un champ
   * informatif, elle ne déclenche plus d'itérations), en reportant chaque
   * résultat en direct dans `liveSteps` à son index réel (vu * steps.length
   * + i). Vérifie l'état de contrôle partagé (pause/annulation) avant
   * chaque étape.
   *
   * `monitoringUrl`, si fourni, est interrogé juste après CHAQUE requête
   * (best-effort, jamais bloquant en cas d'échec) pour donner un CPU/RAM
   * réel par étape (voir StepResult.serverMetrics) — pas seulement un
   * snapshot unique au lancement. */
  const runVirtualUser = async (
    vu: number,
    steps: Step[],
    baseUrl: string,
    defaultTimeoutMs: number,
    variables: Record<string, string>,
    thinkTimeOverrideMs: number,
    baseIndex: number,
    signal: AbortSignal,
    monitoringUrl?: string
  ): Promise<StepResult[]> => {
    const allResults: StepResult[] = []

    for (let i = 0; i < steps.length; i++) {
      if (isCancelled()) break
      await waitIfPaused()
      if (isCancelled()) break

      const step = steps[i]
      const flatIndex = baseIndex + i
      setLiveSteps((prev) => prev.map((ls, idx) => (idx === flatIndex ? { ...ls, status: 'running' } : ls)))

      const result = await runStep(step, baseUrl, defaultTimeoutMs, { variables, vu, thinkTimeOverrideMs, signal })

      if (monitoringUrl && !isCancelled()) {
        const live = await localMonitoringApi.fetchMetrics(monitoringUrl, signal)
        result.serverMetrics = live ? { cpu: live.cpu, ram: live.ram, capturedAt: live.capturedAt } : null
      }

      allResults.push(result)

      setLiveSteps((prev) => prev.map((ls, idx) => (idx === flatIndex
        ? { ...ls, status: result.status, httpStatus: result.httpStatus, responseTimeMs: result.responseTimeMs, error: result.error }
        : ls)))
    }
    return allResults
  }

  // Lance réellement le scénario : crée l'exécution dans JSON Server, puis
  // fait tourner N vrais utilisateurs virtuels (chacun rejoue la vraie
  // séquence d'étapes, avec son propre Think Time/Pacing/timeout/
  // assertions), démarrés progressivement sur la durée de Ramp-up
  // configurée — pas une seule passe simulée. La progression réelle est
  // écrite sur le serveur au fur et à mesure — l'appelant reste sur sa page
  // et voit tout en direct.
  const handleLaunch = async (scenario: Scenario, formOverride?: LaunchForm) => {
    if (!canEdit) return
    // `formOverride` : utilisé par le déclencheur planifié (voir
    // useScheduledExecutions), qui lance un scénario en dehors de toute
    // interaction utilisateur avec la modale — il ne peut pas compter sur
    // `form` (état React mis à jour de façon asynchrone par selectScenario,
    // pas garanti à jour au moment de cet appel).
    const activeForm = formOverride ?? form
    setLaunchError(null)
    setLaunching(true)
    setLaunchStep(3)
    controlRef.current.state = 'running'
    setControlState('running')
    const abortController = new AbortController()
    abortControllerRef.current = abortController

    try {
      const [scenarioSteps, application] = await Promise.all([
        stepsApi.getByScenario(scenario.id),
        applicationsApi.getById(scenario.applicationId),
      ])
      if (scenarioSteps.length === 0) {
        setLaunchError("Ce scénario n'a aucune étape à exécuter.")
        setLaunching(false)
        return
      }

      const vuCount = Math.min(Math.max(1, Math.round(activeForm.virtualUsers) || 1), MAX_REAL_VUS)
      const rampUpMs = Math.max(0, activeForm.rampUp) * 1000
      const staggerMs = vuCount > 1 ? rampUpMs / vuCount : 0

      setLiveSteps(
        Array.from({ length: vuCount }, (_, vu) =>
          scenarioSteps.map((step) => ({ step, status: 'pending' as const, vu }))
        ).flat()
      )

      // Snapshots de tous les serveurs liés à l'application, figés au moment
      // du lancement (voir services/api/serverSnapshot.ts) — relation
      // réelle, valeurs à null tant qu'aucun backend/monitoring ne les
      // fournit ; tableau vide si l'application n'a pas de monitoringUrl.
      const serverSnapshots = await serverSnapshotApi.captureForApplication(application)

      // Même URL de monitoring, réinterrogée après CHAQUE requête (pas
      // juste une fois au lancement) pour donner un CPU/RAM réel par étape
      // (voir StepResult.serverMetrics et runVirtualUser). `undefined` si
      // l'application n'a pas de monitoringUrl : les étapes n'auront alors
      // simplement pas de CPU/RAM, jamais une valeur inventée.
      const monitoringUrl = application.monitoringUrl

      const startedAt = new Date().toISOString()
      const created = await executionsApi.create({
        scenarioId: scenario.id,
        applicationId: scenario.applicationId,
        status: 'En cours',
        users: `${vuCount} VUs`,
        startedAt,
        duration: '00:00:00',
        stepResults: [],
        errors: [],
        serverSnapshots,
      })

      const startTs = Date.now()
      const defaultTimeoutMs = defaultTestSettings.httpTimeoutSeconds * 1000
      const variables = buildVariableMap(scenario.testVariables)

      // Résultats de tous les VUs, remplis au fur et à mesure — un tableau
      // JS partagé suffit ici (mono-thread, push toujours cumulatif) ; seule
      // la mise à jour PATCH intermédiaire peut ponctuellement arriver
      // dans le désordre entre deux VUs concurrents (l'écriture finale plus
      // bas est toujours la version complète et fait foi).
      const allResultsByVu: StepResult[][] = new Array(vuCount)

      // Écriture périodique (pas à chaque résultat individuel — voir le
      // bug ERR_CONNECTION_RESET déjà rencontré avec json-server sous
      // écritures concurrentes rapprochées) pour que la fiche d'exécution
      // reflète la progression même pendant un test de plusieurs minutes.
      const flushProgress = () =>
        executionsApi.update(created.id, { stepResults: allResultsByVu.flatMap((r) => r ?? []) }).catch(() => {})
      const progressInterval = setInterval(flushProgress, 3000)

      try {
        await Promise.all(
          Array.from({ length: vuCount }, (_, vu) => {
            const baseIndex = vu * scenarioSteps.length
            return (async () => {
              if (vu > 0) await sleep(vu * staggerMs, abortController.signal)
              allResultsByVu[vu] = await runVirtualUser(
                vu,
                scenarioSteps,
                application.url,
                defaultTimeoutMs,
                variables,
                activeForm.thinkTime,
                baseIndex,
                abortController.signal,
                monitoringUrl
              )
            })()
          })
        )
      } finally {
        clearInterval(progressInterval)
      }

      const finalResults = allResultsByVu.flatMap((r) => r ?? [])
      const wasCancelled = isCancelled()
      const finalStatus = wasCancelled ? 'Annulée' : computeExecutionStatus(finalResults)
      const elapsedSec = Math.round((Date.now() - startTs) / 1000)
      const h = String(Math.floor(elapsedSec / 3600)).padStart(2, '0')
      const m = String(Math.floor((elapsedSec % 3600) / 60)).padStart(2, '0')
      const s = String(elapsedSec % 60).padStart(2, '0')
      const errors = finalResults.filter((r) => r.error).map((r) => r.error!) as string[]

      await executionsApi.update(created.id, {
        status: finalStatus,
        duration: `${h}:${m}:${s}`,
        stepResults: finalResults,
        errors,
      })

      if (onExecuted) await onExecuted()

      const errorCount = finalResults.filter((r) => r.status === 'error').length
      showToast(
        wasCancelled
          ? `Exécution de « ${scenario.name} » annulée.`
          : errorCount > 0
          ? `Exécution de « ${scenario.name} » terminée avec ${errorCount} erreur${errorCount > 1 ? 's' : ''}.`
          : `Exécution de « ${scenario.name} » terminée avec succès.`,
        wasCancelled ? 'warning' : errorCount > 0 ? 'warning' : 'success'
      )

      setTimeout(() => {
        setShowLaunchModal(false)
        setLaunchStep(1)
        setLaunching(false)
        setLiveSteps([])
      }, 1200)
    } catch (err) {
      const message = err instanceof Error ? err.message : "Erreur lors du lancement de l'exécution"
      setLaunchError(message)
      showToast(message, 'danger')
      setLaunching(false)
    }
  }

  const doneCount = liveSteps.filter((s) => s.status === 'success' || s.status === 'error' || s.status === 'skipped').length
  const errorCount = liveSteps.filter((s) => s.status === 'error').length
  const progressPct = liveSteps.length > 0 ? Math.round((doneCount / liveSteps.length) * 100) : 0
  const currentRunning = liveSteps.find((s) => s.status === 'running')

  return {
    showLaunchModal,
    launchStep,
    setLaunchStep,
    launching,
    liveSteps,
    launchError,
    form,
    setForm,
    openModal,
    openForScenario,
    selectApplication,
    selectScenario,
    closeModal,
    handleLaunch,
    doneCount,
    errorCount,
    progressPct,
    currentRunning,
    controlState,
    pauseExecution,
    resumeExecution,
    cancelExecution,
  }
}

export type ScenarioLauncher = ReturnType<typeof useScenarioLauncher>
