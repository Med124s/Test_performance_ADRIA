import { scenariosApi } from './api/scenarios'
import { stepsApi } from './api/steps'
import { Scenario, Step, StepHeader, StepAssertion, ScenarioVariable, ScenarioSchedule } from '../types'

export interface LocalStepDraft {
  /** ID réel (venant de JSON Server) si l'étape existait déjà, ou un ID
   * temporaire côté client (ex: "tmp-...") si elle vient d'être ajoutée
   * dans cette session d'édition. */
  id: string
  order: number
  name: string
  method: Step['method']
  url: string
  description?: string
  headers?: StepHeader[]
  bodyJson?: string
  assertions?: StepAssertion[]
  timeoutMs?: number
  active?: boolean
  followRedirects?: boolean
  pauseBeforeMs?: number
  pacingAfterMs?: number
}

export interface SaveScenarioParams {
  /** null = création d'un nouveau scénario ; sinon mise à jour de ce scénario. */
  scenarioId: string | null
  name: string
  applicationId: string
  description?: string
  createdBy: string
  localSteps: LocalStepDraft[]
  /** IDs des étapes qui existaient déjà en base avant cette session d'édition
   * — permet de distinguer "à mettre à jour" (PATCH) de "à créer" (POST),
   * et de détecter celles qui ont été retirées (DELETE). */
  existingStepIds: Set<string>
  // Champs du wizard "Utilisateurs" / "Planification" (étapes 3 et 4) —
  // optionnels : un appelant qui n'a pas encore rempli ces écrans (ex.
  // enregistrement direct depuis l'Étape 1) les omet simplement, sans
  // jamais écraser une valeur déjà enregistrée (voir httpClient : les clés
  // undefined ne sont jamais sérialisées).
  status?: Scenario['status']
  virtualUsers?: number
  rampUpSeconds?: number
  userProfile?: string
  dataSource?: 'manual' | 'csv'
  csvFileName?: string
  testVariables?: ScenarioVariable[]
  schedule?: ScenarioSchedule
}

/**
 * Sauvegarde un scénario complet (infos générales + toutes ses étapes) en
 * une seule opération cohérente sur JSON Server : crée le scénario (ou le
 * met à jour), puis crée/actualise/supprime ses étapes réelles pour que la
 * base reflète exactement l'état de l'écran d'édition.
 */
export async function saveScenarioWithSteps(params: SaveScenarioParams): Promise<Scenario> {
  const wizardFields = {
    virtualUsers: params.virtualUsers,
    rampUpSeconds: params.rampUpSeconds,
    userProfile: params.userProfile,
    dataSource: params.dataSource,
    csvFileName: params.csvFileName,
    testVariables: params.testVariables,
    schedule: params.schedule,
  }

  const scenario = params.scenarioId
    ? await scenariosApi.update(params.scenarioId, {
        name: params.name,
        applicationId: params.applicationId,
        description: params.description,
        status: params.status,
        ...wizardFields,
      })
    : await scenariosApi.create({
        name: params.name,
        applicationId: params.applicationId,
        status: params.status ?? 'Actif',
        description: params.description,
        createdAt: new Date().toISOString(),
        createdBy: params.createdBy,
        ...wizardFields,
      })

  const removedIds = [...params.existingStepIds].filter(
    (id) => !params.localSteps.some((s) => s.id === id)
  )

  // Écrit les étapes une par une plutôt qu'en parallèle (Promise.all) :
  // json-server (--watch, mono-thread, fichier unique) ne tient pas la
  // charge de plusieurs écritures concurrentes sur un scénario à
  // beaucoup d'étapes — certaines requêtes se terminaient en
  // ERR_CONNECTION_RESET et l'étape correspondante n'était jamais
  // enregistrée alors que le scénario, lui, semblait sauvegardé.
  for (const s of params.localSteps) {
    const payload = {
      scenarioId: scenario.id,
      order: s.order,
      name: s.name,
      method: s.method,
      url: s.url,
      description: s.description,
      headers: s.headers,
      bodyJson: s.bodyJson,
      assertions: s.assertions,
      timeoutMs: s.timeoutMs,
      active: s.active,
      followRedirects: s.followRedirects,
      pauseBeforeMs: s.pauseBeforeMs,
      pacingAfterMs: s.pacingAfterMs,
    }
    if (params.existingStepIds.has(s.id)) {
      await stepsApi.update(s.id, payload)
    } else {
      await stepsApi.create(payload)
    }
  }

  for (const id of removedIds) {
    await stepsApi.remove(id)
  }

  return scenario
}
