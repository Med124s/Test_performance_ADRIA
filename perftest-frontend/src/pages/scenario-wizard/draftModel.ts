// ============================================================
// Brouillons de l'assistant « Nouveau scénario » : état sauvegardé dans
// scenario.draft_state (JSON), reprise (« Reprendre ») et détection des
// modifications non enregistrées.
// ============================================================

import { inUserZone } from '../../utils/userTimezone'
import { BackendScenarioFullRequest, BackendScenarioResponse, BackendStepResponse } from '../../types/backendContracts'
import { analyzeCsv } from './csvModel'
import { QueryParamRow, parseQueryString, splitUrl, urlWithQuery, variablesFromJson, variablesToJson } from './variablesModel'
import {
  DEFAULT_LOAD, DraftStep, LoadConfig, ScenarioInfo, loadToRequest, newStep, stepToRequest, thresholdsFromScenario, thresholdsToRequest, errorBrakeFromScenario, errorBrakeToRequest, throttleFromScenario, throttleToRequest, thinkVariationFromScenario, thinkVariationToRequest,
} from './wizardModel'

export interface WizardDraftState {
  version: 1
  info: ScenarioInfo
  steps: DraftStep[]
  load: LoadConfig
  maxReached: number
  csvFileName: string | null
  /** Empreinte de la version qui a réussi le test complet (null sinon) : évite de retester un brouillon inchangé. */
  testedSignature: string | null
}

/** Le CSV valide est déjà dans scenario.csv_data : il n'est recopié dans l'état que s'il est invalide. */
export function serializeDraft(state: Omit<WizardDraftState, 'version'>): string {
  const csvValid = !analyzeCsv(state.info.csvData).error
  return JSON.stringify({ version: 1, ...state, info: { ...state.info, csvData: csvValid ? '' : state.info.csvData } })
}

export function parseDraft(json: string | null, scenario: BackendScenarioResponse): WizardDraftState | null {
  if (!json) return null
  try {
    const raw = JSON.parse(json) as Partial<WizardDraftState>
    if (raw.version !== 1 || !raw.info || !Array.isArray(raw.steps)) return null
    return {
      version: 1,
      // Valeurs par défaut pour les brouillons enregistrés avant l'ajout des variables du scénario.
      info: { ...EMPTY_INFO, ...raw.info, csvData: raw.info.csvData || scenario.csvData || '' },
      // Valeurs par défaut d'abord (champs ajoutés depuis l'enregistrement), uid/backendId conservés.
      steps: raw.steps.map((s) => ({ ...newStep(), ...s })),
      load: { ...DEFAULT_LOAD, ...raw.load },
      maxReached: raw.maxReached ?? 0,
      csvFileName: raw.csvFileName ?? null,
      testedSignature: raw.testedSignature ?? null,
    }
  } catch {
    return null
  }
}

/** Étape enregistrée (créée par « Tester ») → étape de l'assistant. */
export function stepFromBackend(step: BackendStepResponse): DraftStep {
  let headers: { key: string; value: string }[] = []
  try {
    headers = step.headers ? Object.entries(JSON.parse(step.headers) as Record<string, string>).map(([key, value]) => ({ key, value: String(value) })) : []
  } catch {
    headers = []
  }
  const str = (n: number | null) => (n == null ? '' : String(n))
  // Paramètres d'une URL historique (query dans l'URL) + paramètres enregistrés séparément.
  const queryParams = [...parseQueryString(splitUrl(step.url).query),
    ...parseJson<QueryParamRow[]>(step.queryParams, []).map((q) => ({ key: q.key ?? '', value: q.value ?? '', enabled: q.enabled !== false }))]
  return newStep({
    backendId: step.id,
    name: step.name,
    method: step.method,
    // URL affichée = chemin + paramètres actifs (comme dans l'éditeur).
    url: urlWithQuery(step.url, queryParams),
    headers,
    body: step.body ?? '',
    expectedStatus: str(step.expectedStatus),
    assertionBodyContains: step.assertionBodyContains ?? '',
    captureVariableName: step.captureVariableName ?? '',
    captureJsonPath: step.captureJsonPath ?? '',
    ...(step.captureSource === 'HEADER' || step.captureSource === 'HTML' ? { captureSource: step.captureSource } : {}),
    thinkTimeMs: str(step.thinkTimeMs),
    timeoutSeconds: str(step.timeoutSeconds),
    followRedirects: step.followRedirects == null ? '' : step.followRedirects ? 'true' : 'false',
    pathParams: parseJson<Record<string, string>>(step.pathParams, {}),
    queryParams,
    ...(step.maxResponseTimeMs != null ? { maxResponseTimeMs: String(step.maxResponseTimeMs) } : {}),
    ...(step.assertionJsonPath
      ? { assertionJsonPath: step.assertionJsonPath, assertionJsonOperator: step.assertionJsonOperator ?? 'EQUALS', assertionJsonValue: step.assertionJsonValue ?? '' }
      : {}),
  })
}

/** Brouillon sans état (créé par « Tester » puis onglet fermé) : reconstruit depuis le scénario et ses étapes. */
export function stateFromBackend(scenario: BackendScenarioResponse, steps: BackendStepResponse[]): WizardDraftState {
  return {
    version: 1,
    info: {
      applicationId: scenario.applicationId, name: scenario.name, description: scenario.description ?? '', csvData: scenario.csvData ?? '',
      variables: variablesFromJson(scenario.variables), csvDistribution: scenario.csvDistribution ?? 'PER_VIRTUAL_USER',
      requestDetailMode: scenario.requestDetailMode ?? 'FAILURES_AND_SAMPLE',
      ...thresholdsFromScenario(scenario),
      ...errorBrakeFromScenario(scenario),
      ...throttleFromScenario(scenario),
      ...thinkVariationFromScenario(scenario),
    },
    steps: steps.map(stepFromBackend),
    load: loadFromScenario(scenario),
    maxReached: Math.max(scenario.wizardStep ?? 2, steps.length ? 2 : 1),
    csvFileName: null,
    testedSignature: null,
  }
}

/** Paramètres de charge réellement enregistrés sur le scénario (préréglage « Personnalisé »). */
export function loadFromScenario(scenario: BackendScenarioResponse): LoadConfig {
  const str = (n: number | null | undefined) => (n == null ? '' : String(n))
  return {
    ...DEFAULT_LOAD,
    presetId: 'custom',
    virtualUsers: str(scenario.virtualUsers),
    rampUpSeconds: str(scenario.rampUpSeconds),
    durationSeconds: str(scenario.durationSeconds),
    iterations: str(scenario.iterations),
    thinkTimeMs: str(scenario.thinkTimeMs),
    targetRps: str(scenario.targetRps),
  }
}

/** Corps de PUT /api/scenarios/{id}/full (et du test d'une version non enregistrée). */
export function fullRequest(scenarioId: string, info: ScenarioInfo, steps: DraftStep[], load: LoadConfig): BackendScenarioFullRequest {
  return {
    scenario: {
      applicationId: info.applicationId,
      name: info.name.trim(),
      description: info.description.trim() || null,
      csvData: info.csvData.trim() || null,
      ...loadToRequest(load),
      variables: variablesToJson(info.variables),
      csvDistribution: info.csvDistribution,
      requestDetailMode: info.requestDetailMode ?? null,
      ...thresholdsToRequest(info),
      ...errorBrakeToRequest(info),
      ...throttleToRequest(info),
      ...thinkVariationToRequest(info),
    },
    steps: steps.map((s, i) => ({ id: s.backendId, step: stepToRequest(s, scenarioId, i + 1) })),
  }
}

/** Scénario enregistré tel quel (page Scénarios : « Tester cette étape »). */
export function savedInfo(scenario: BackendScenarioResponse): ScenarioInfo {
  return {
    applicationId: scenario.applicationId, name: scenario.name, description: scenario.description ?? '', csvData: scenario.csvData ?? '',
    variables: variablesFromJson(scenario.variables), csvDistribution: scenario.csvDistribution ?? 'PER_VIRTUAL_USER',
    requestDetailMode: scenario.requestDetailMode ?? 'FAILURES_AND_SAMPLE',
    ...thresholdsFromScenario(scenario),
    ...errorBrakeFromScenario(scenario),
    ...throttleFromScenario(scenario),
    ...thinkVariationFromScenario(scenario),
  }
}

export function defaultDraftName(now = new Date()): string {
  return `Brouillon du ${now.toLocaleDateString('fr-FR', inUserZone())} ${now.toLocaleTimeString('fr-FR', inUserZone({ hour: '2-digit', minute: '2-digit' }))}`
}

export const isBlankStep = (s: DraftStep) => {
  const blank = newStep()
  return s.name === blank.name && s.method === blank.method && s.url === blank.url && s.headers.length === 0 && s.body === ''
    && (s.expectedStatus === '' || s.expectedStatus === blank.expectedStatus) && s.assertionBodyContains === '' && s.captureVariableName === '' && s.captureJsonPath === ''
    && !s.maxResponseTimeMs?.trim() && !s.assertionJsonPath?.trim()
}

/** Empreinte du travail saisi (étapes vides ajoutées automatiquement ignorées) pour « modifications non enregistrées ». */
export function workSnapshot(info: ScenarioInfo, steps: DraftStep[], load: LoadConfig): string {
  return JSON.stringify([info, steps.filter((s) => !isBlankStep(s)).map(({ uid, backendId, ...rest }) => rest), load])
}

export const EMPTY_INFO: ScenarioInfo = { applicationId: '', name: '', description: '', csvData: '', variables: [], csvDistribution: 'PER_VIRTUAL_USER', requestDetailMode: 'FAILURES_AND_SAMPLE', maxErrorRatePercent: '1', maxP95Ms: '', errorBrakeRatePercent: '', errorBrakeWindowSeconds: '30', throttleOn429: true, throttleDefaultDelaySeconds: '5', throttleMaxRetries: '3', thinkTimeVariationPercent: '' }

function parseJson<T>(json: string | null | undefined, fallback: T): T {
  if (!json) return fallback
  try {
    return (JSON.parse(json) as T) ?? fallback
  } catch {
    return fallback
  }
}
