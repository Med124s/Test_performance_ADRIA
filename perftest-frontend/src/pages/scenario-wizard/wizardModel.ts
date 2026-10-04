// ============================================================
// Assistant "Nouveau scénario" — modèle local (brouillon), validation et
// conversion vers les contrats réels du backend Spring Boot.
//
// Le brouillon vit en mémoire tant que l'utilisateur n'a ni testé ni
// enregistré : rien n'est envoyé au backend avant l'étape "Tester" (ou
// "Enregistrer"). Les bornes de validation sont celles de ScenarioRequest/
// StepRequest côté backend — jamais une valeur que le backend rejetterait.
// ============================================================

import { ApiError } from '../../services/api/httpClient'
import { analyzeCsv, validateCsv as validateCsvData } from './csvModel'
import { QueryParamRow, VARIABLE_NAME, VariableRow, detectPathVariables, splitUrl } from './variablesModel'
import {
  BackendCsvDistribution,
  BackendHttpMethod,
  BackendScenarioRequest,
  BackendStepRequest,
} from '../../types/backendContracts'

export const HTTP_METHODS: BackendHttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

export interface HeaderRow {
  key: string
  value: string
}

export interface DraftStep {
  /** Identifiant local stable (clé React, sélection, mapping des résultats). */
  uid: string
  /** Id backend une fois l'étape réellement enregistrée, sinon null. */
  backendId: string | null
  name: string
  method: BackendHttpMethod
  url: string
  headers: HeaderRow[]
  body: string
  expectedStatus: string
  assertionBodyContains: string
  captureVariableName: string
  captureJsonPath: string
  thinkTimeMs: string
  timeoutSeconds: string
  followRedirects: '' | 'true' | 'false'
  /** Variables de chemin (:nom / {nom}) : nom → valeur fixe ou ${variable}. */
  pathParams: Record<string, string>
  /** Paramètres de requête (synchronisés avec l'URL ; les désactivés n'y figurent pas). */
  queryParams: QueryParamRow[]
}

export interface ScenarioInfo {
  applicationId: string
  name: string
  description: string
  /** Jeu de données CSV optionnel (variables ${colonne}), saisi avec les étapes
   * pour être disponible dès le test. */
  csvData: string
  /** Variables du scénario (constantes clé/valeur, 3e source après capture et CSV). */
  variables: VariableRow[]
  /** Distribution du CSV entre utilisateurs virtuels. */
  csvDistribution: BackendCsvDistribution
}

/**
 * Paramètres de charge — mêmes règles que le moteur (HttpClientExecutionEngine) :
 * durée et itérations sont tous deux optionnels ; les deux renseignés = arrêt à la
 * première limite atteinte ; aucun = une seule itération par utilisateur.
 */
export interface LoadConfig {
  presetId: string
  virtualUsers: string
  rampUpSeconds: string
  durationSeconds: string
  iterations: string
  thinkTimeMs: string
  targetRps: string
}

export interface LoadPreset {
  id: string
  label: string
  icon: string
  description: string
  values: Omit<LoadConfig, 'presetId'> | null
}

export const LOAD_PRESETS: LoadPreset[] = [
  {
    id: 'smoke',
    label: 'Smoke test',
    icon: 'bi-lightning-charge',
    description: '1 utilisateur, 1 passage — vérifier que tout fonctionne.',
    values: { virtualUsers: '1', rampUpSeconds: '0', durationSeconds: '', iterations: '1', thinkTimeMs: '0', targetRps: '' },
  },
  {
    id: 'load',
    label: 'Charge',
    icon: 'bi-people',
    description: '10 utilisateurs pendant 1 minute, montée en 10 s.',
    values: { virtualUsers: '10', rampUpSeconds: '10', durationSeconds: '60', iterations: '', thinkTimeMs: '500', targetRps: '' },
  },
  {
    id: 'stress',
    label: 'Stress',
    icon: 'bi-fire',
    description: '50 utilisateurs pendant 2 minutes, montée en 30 s.',
    values: { virtualUsers: '50', rampUpSeconds: '30', durationSeconds: '120', iterations: '', thinkTimeMs: '200', targetRps: '' },
  },
  {
    id: 'custom',
    label: 'Personnalisé',
    icon: 'bi-sliders',
    description: 'Réglez vous-même chaque paramètre.',
    values: null,
  },
]

export const DEFAULT_LOAD: LoadConfig = { presetId: 'smoke', ...LOAD_PRESETS[0].values! }

/** Paramètres du test de validation : un seul utilisateur, un seul passage. */
export const TEST_RUN_LOAD: Pick<
  BackendScenarioRequest,
  'virtualUsers' | 'rampUpSeconds' | 'durationSeconds' | 'iterations' | 'thinkTimeMs' | 'targetRps'
> = { virtualUsers: 1, rampUpSeconds: 0, durationSeconds: null, iterations: 1, thinkTimeMs: 0, targetRps: null }

let uidCounter = 0
const nextUid = () => `step-${Date.now().toString(36)}-${(uidCounter++).toString(36)}`

export function newStep(partial: Partial<DraftStep> = {}): DraftStep {
  return {
    uid: nextUid(),
    backendId: null,
    name: '',
    method: 'GET',
    url: '',
    headers: [],
    body: '',
    expectedStatus: '',
    assertionBodyContains: '',
    captureVariableName: '',
    captureJsonPath: '',
    thinkTimeMs: '',
    timeoutSeconds: '',
    followRedirects: '',
    pathParams: {},
    queryParams: [],
    ...partial,
  }
}

/** Copie d'une étape (nouvel uid, jamais liée à l'étape backend d'origine). */
export function duplicateStep(step: DraftStep): DraftStep {
  return { ...step, uid: nextUid(), backendId: null, name: `${step.name} (copie)`, headers: step.headers.map((h) => ({ ...h })), pathParams: { ...step.pathParams }, queryParams: step.queryParams.map((q) => ({ ...q })) }
}

// ---------------------------------------------------------------- headers

export function headersToJson(rows: HeaderRow[]): string | null {
  const entries = rows.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value] as const)
  return entries.length ? JSON.stringify(Object.fromEntries(entries)) : null
}

export function hasHeader(rows: HeaderRow[], name: string): boolean {
  return rows.some((r) => r.key.trim().toLowerCase() === name.toLowerCase())
}

// ---------------------------------------------------------------- JSON body

export type BodyFormat = 'empty' | 'json' | 'text'

export function detectBodyFormat(body: string): BodyFormat {
  const trimmed = body.trim()
  if (!trimmed) return 'empty'
  // ${variables} hors guillemets rendraient un JSON valide "invalide" : on les
  // neutralise uniquement pour ce diagnostic, jamais dans le corps envoyé.
  const probe = trimmed.replace(/\$\{[^}]+\}/g, '0')
  try {
    JSON.parse(probe)
    return 'json'
  } catch {
    return 'text'
  }
}

export function formatJsonBody(body: string): string | null {
  try {
    return JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    return null
  }
}

// ---------------------------------------------------------------- variables

export interface AvailableVariable {
  name: string
  source: string
}

/** Variables utilisables par l'étape d'index `index` : colonnes CSV + valeurs
 * capturées par les étapes PRÉCÉDENTES (même utilisateur virtuel). */
export function variablesAvailableAt(steps: DraftStep[], index: number, csvData: string): AvailableVariable[] {
  const vars: AvailableVariable[] = []
  analyzeCsv(csvData).columns.forEach((name) => vars.push({ name, source: 'CSV' }))
  steps.slice(0, index).forEach((s, i) => {
    const name = s.captureVariableName.trim()
    if (name && s.captureJsonPath.trim()) vars.push({ name, source: `Étape ${i + 1}` })
  })
  return vars
}

// ---------------------------------------------------------------- validation

export type FieldErrors = Partial<Record<string, string>>

const isInt = (v: string) => /^-?\d+$/.test(v.trim())

function intField(value: string, { min, max, required, label }: { min: number; max?: number; required?: boolean; label: string }): string | undefined {
  if (!value.trim()) return required ? `${label} est obligatoire.` : undefined
  if (!isInt(value)) return `${label} doit être un nombre entier.`
  const n = Number(value)
  if (n < min) return `${label} doit être au moins ${min}.`
  if (max !== undefined && n > max) return `${label} ne doit pas dépasser ${max}.`
  return undefined
}

export function validateInfo(info: ScenarioInfo): FieldErrors {
  const errors: FieldErrors = {}
  if (!info.applicationId) errors.applicationId = 'Choisissez l’application à tester.'
  if (!info.name.trim()) errors.name = 'Donnez un nom au scénario.'
  else if (info.name.trim().length > 255) errors.name = 'Le nom ne doit pas dépasser 255 caractères.'
  if (info.description.length > 1000) errors.description = 'La description ne doit pas dépasser 1000 caractères.'
  return errors
}

/** Mêmes règles que le moteur (voir csvModel.ts / CsvDataSource.java). */
export function validateCsv(csvData: string): string | undefined {
  return validateCsvData(csvData)
}

// Mêmes règles que le backend (service/validation/StepRules.java).
const HEADER_NAME = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/
const ABSOLUTE_URL = /^https?:\/\/[^/?#\s]+/i

export function validateStep(step: DraftStep): FieldErrors {
  const errors: FieldErrors = {}
  if (!step.name.trim()) errors.name = 'Donnez un nom à l’étape.'
  else if (step.name.trim().length > 255) errors.name = 'Le nom ne doit pas dépasser 255 caractères.'
  if (!step.url.trim()) errors.url = 'Indiquez le chemin ou l’URL à appeler.'
  else if (/\s/.test(step.url.trim())) errors.url = 'L’URL ne doit pas contenir d’espace.'
  else if (step.url.includes('://') && !ABSOLUTE_URL.test(step.url.trim())) {
    errors.url = 'URL invalide : utilisez un chemin relatif (ex. /api/ressource) ou une adresse http(s)://hôte.'
  }
  if (/^[{[]/.test(step.body.trim()) && detectBodyFormat(step.body) !== 'json') {
    errors.body = 'Corps JSON invalide : vérifiez les guillemets, virgules et accolades.'
  }
  const expected = intField(step.expectedStatus, { min: 100, max: 599, label: 'Le code attendu' })
  if (expected) errors.expectedStatus = expected
  if (step.assertionBodyContains.length > 500) errors.assertionBodyContains = 'L’assertion ne doit pas dépasser 500 caractères.'
  const capName = step.captureVariableName.trim()
  const capPath = step.captureJsonPath.trim()
  if (capName && !capPath) errors.captureJsonPath = 'Indiquez où lire la valeur (ex : $.token).'
  if (capPath && !capName) errors.captureVariableName = 'Donnez un nom à la variable (ex : token).'
  if (capName && !/^[A-Za-z_][A-Za-z0-9_]*$/.test(capName)) errors.captureVariableName = 'Lettres, chiffres et _ uniquement (ex : token).'
  if (capName.length > 255) errors.captureVariableName = 'Le nom de variable ne doit pas dépasser 255 caractères.'
  if (capPath.length > 500) errors.captureJsonPath = 'Le chemin ne doit pas dépasser 500 caractères.'
  const badPath = detectPathVariables(step.url).find((n) => !VARIABLE_NAME.test(n))
  if (badPath) errors.url = `Variable de chemin « ${badPath} » invalide.`
  if (step.queryParams.some((q) => !q.key.trim() && q.value.trim())) errors.queryParams = 'Chaque paramètre de requête doit avoir un nom.'
  if (step.headers.some((h) => !h.key.trim() && h.value.trim())) errors.headers = 'Chaque en-tête doit avoir un nom.'
  else {
    const bad = step.headers.find((h) => h.key.trim() && !HEADER_NAME.test(h.key.trim()))
    if (bad) errors.headers = `Nom d’en-tête invalide « ${bad.key.trim()} » : pas d’espace ni de caractère spécial.`
  }
  return errors
}

export function validateLoad(load: LoadConfig): FieldErrors {
  const errors: FieldErrors = {}
  const vu = intField(load.virtualUsers, { min: 1, max: 500, required: true, label: 'Le nombre d’utilisateurs' })
  if (vu) errors.virtualUsers = vu
  const ramp = intField(load.rampUpSeconds, { min: 0, required: true, label: 'Le ramp-up' })
  if (ramp) errors.rampUpSeconds = ramp
  const d = intField(load.durationSeconds, { min: 1, label: 'La durée' })
  if (d) errors.durationSeconds = d
  const it = intField(load.iterations, { min: 1, label: 'Le nombre d’itérations' })
  if (it) errors.iterations = it
  const think = intField(load.thinkTimeMs, { min: 0, required: true, label: 'Le think time' })
  if (think) errors.thinkTimeMs = think
  const rps = intField(load.targetRps, { min: 1, label: 'Le débit cible' })
  if (rps) errors.targetRps = rps
  return errors
}

/** Avertissements non bloquants sur une configuration de charge valide. */
export function loadWarnings(load: LoadConfig): string[] {
  const warnings: string[] = []
  const vu = Number(load.virtualUsers) || 0
  const ramp = Number(load.rampUpSeconds) || 0
  const duration = Number(load.durationSeconds) || 0
  if (duration && ramp > duration) warnings.push(`Le ramp-up (${ramp} s) dépasse la durée (${duration} s) : les utilisateurs démarrés après la fin de la durée ne joueront aucune itération.`)
  if (vu > 200) warnings.push('Au-delà de 200 utilisateurs simultanés (toutes exécutions confondues), le serveur refusera le lancement.')
  if (!load.durationSeconds.trim() && !load.iterations.trim()) warnings.push('Ni durée ni itérations : chaque utilisateur jouera le scénario une seule fois.')
  return warnings
}

export const hasErrors = (errors: FieldErrors) => Object.values(errors).some(Boolean)

// ---------------------------------------------------------------- payloads

const optInt = (v: string) => (v.trim() ? Number(v) : null)

export function stepToRequest(step: DraftStep, scenarioId: string, order: number): BackendStepRequest {
  return {
    scenarioId,
    name: step.name.trim(),
    method: step.method,
    // L'URL finale (variables de chemin, query encodée) est construite par le backend.
    url: splitUrl(step.url.trim()).path,
    headers: headersToJson(step.headers),
    body: step.body.trim() ? step.body : null,
    order,
    expectedStatus: optInt(step.expectedStatus),
    thinkTimeMs: optInt(step.thinkTimeMs),
    timeoutSeconds: optInt(step.timeoutSeconds),
    followRedirects: step.followRedirects === '' ? null : step.followRedirects === 'true',
    assertionBodyContains: step.assertionBodyContains.trim() || null,
    captureVariableName: step.captureVariableName.trim() || null,
    captureJsonPath: step.captureJsonPath.trim() || null,
    pathParams: pathParamsToJson(step),
    queryParams: step.queryParams.some((q) => q.key.trim()) ? JSON.stringify(step.queryParams.filter((q) => q.key.trim())) : null,
  }
}

/** Variables de chemin détectées dans l'URL ; valeur vide = ${même nom}. */
export function pathParamsToJson(step: DraftStep): string | null {
  const names = detectPathVariables(step.url)
  if (names.length === 0) return null
  return JSON.stringify(Object.fromEntries(names.map((n) => [n, step.pathParams[n]?.trim() || `\${${n}}`])))
}

export function loadToRequest(load: LoadConfig): typeof TEST_RUN_LOAD {
  return {
    virtualUsers: Number(load.virtualUsers),
    rampUpSeconds: Number(load.rampUpSeconds),
    durationSeconds: optInt(load.durationSeconds),
    iterations: optInt(load.iterations),
    thinkTimeMs: Number(load.thinkTimeMs),
    targetRps: optInt(load.targetRps),
  }
}

/** Empreinte du contenu testable, pour signaler "modifié depuis le dernier test". */
export function draftSignature(info: ScenarioInfo, steps: DraftStep[]): string {
  return JSON.stringify([info.applicationId, info.csvData, info.csvDistribution, info.variables, steps.map(({ uid, backendId, ...rest }) => rest)])
}

/** Même politique que les autres pages : le code HTTP réel prime, le message
 * backend est réutilisé quand il est déjà explicite. */
export function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 400:
        return `Données invalides : ${err.message}`
      case 401:
        return 'Votre session a expiré : reconnectez-vous.'
      case 403:
        return 'Action refusée : votre rôle ne permet pas de créer des scénarios.'
      case 404:
        return 'Ressource introuvable (elle a peut-être été supprimée).'
      case 409:
        return err.message
      case 429:
        return `Limite de capacité LoadPilot atteinte : ${err.message}`
      default:
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

export function formatMs(ms: number | null | undefined): string {
  if (ms == null) return '—'
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)} s` : `${Math.round(ms)} ms`
}

export function formatSeconds(total: number): string {
  if (total < 60) return `${total} s`
  const m = Math.floor(total / 60)
  const s = total % 60
  return s ? `${m} min ${s} s` : `${m} min`
}
