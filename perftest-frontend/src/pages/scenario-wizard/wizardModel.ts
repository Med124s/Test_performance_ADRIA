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
  BackendCsvDistribution, BackendRequestDetailMode,
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
  /** Détail des requêtes conservé à l'exécution (absent des anciens brouillons : défaut serveur). */
  requestDetailMode?: BackendRequestDetailMode
  /** Seuils de réussite (saisie texte) : taux d'erreurs maximal en % (défaut « 1 »), P95 maximal en ms (vide = aucun).
   * Absents des anciens brouillons : valeurs du serveur conservées. */
  maxErrorRatePercent?: string
  maxP95Ms?: string
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

/** Code HTTP attendu proposé selon la méthode (modifiable) : création = 201, le reste = 200. */
export const DEFAULT_EXPECTED_STATUS: Record<BackendHttpMethod, string> = {
  GET: '200', POST: '201', PUT: '200', PATCH: '200', DELETE: '200',
}

/**
 * Nouveau code attendu quand la méthode change : suit la méthode tant que l'utilisateur ne l'a pas
 * personnalisé (vide ou égal à la valeur proposée pour l'ancienne méthode), sinon conservé.
 */
export function expectedStatusAfterMethodChange(step: Pick<DraftStep, 'method' | 'expectedStatus'>, method: BackendHttpMethod): string {
  const current = step.expectedStatus.trim()
  return current === '' || current === DEFAULT_EXPECTED_STATUS[step.method] ? DEFAULT_EXPECTED_STATUS[method] : step.expectedStatus
}

/** Vrai si l'étape vérifie le contenu de la réponse (« La réponse doit contenir »). */
export const hasContentCheck = (step: Pick<DraftStep, 'assertionBodyContains'>) => step.assertionBodyContains.trim() !== ''

export function newStep(partial: Partial<DraftStep> = {}): DraftStep {
  return {
    uid: nextUid(),
    backendId: null,
    name: '',
    method: 'GET',
    url: '',
    headers: [],
    body: '',
    expectedStatus: DEFAULT_EXPECTED_STATUS[partial.method ?? 'GET'],
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

/** Limites de charge d'un test en vigueur (Paramètres > Système), lues depuis le serveur. */
export interface LoadLimits {
  maxVirtualUsers: number
  maxDurationSeconds: number
  maxIterations: number
  maxTargetRps: number
}

/** Plage fixe du think time (ms), identique au serveur (LoadRules.MAX_THINK_TIME_MS). */
export const MAX_THINK_TIME_MS = 60000
/** Plafond technique des utilisateurs virtuels (ScenarioRequest côté serveur) quand les limites ne sont pas encore connues. */
export const MAX_VIRTUAL_USERS_FALLBACK = 10000

/** Nombre avec espace des milliers, identique au serveur (LoadRules.number) : 3 600, 10 000. */
export function formatInt(n: number): string {
  return String(Math.trunc(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ' ')
}

const LIMIT_SUFFIX = ' (limite d’un test sur ce serveur).'
/** Messages identiques au serveur (LoadRules). */
export const vuTooHighMessage = (max: number) => `Le nombre d’utilisateurs ne doit pas dépasser ${formatInt(max)}${LIMIT_SUFFIX}`
export const RAMP_UP_TOO_LONG = 'Le ramp-up doit être plus court que la durée.'

/**
 * Règles de « Charge & résumé », identiques au serveur
 * (LoadRules.validate), avec les limites en vigueur : entiers, minimums, maximums (limites du serveur,
 * think time 0 à 60 000 ms), ramp-up jamais au-delà de la durée maximale et plus court que la durée
 * quand elle est fixée, facultatifs vides acceptés.
 */
export function validateLoad(load: Omit<LoadConfig, 'presetId'>, limits?: Partial<LoadLimits> | null): FieldErrors {
  const errors: FieldErrors = {}
  const maxVu = limits?.maxVirtualUsers ?? MAX_VIRTUAL_USERS_FALLBACK
  const vu = intField(load.virtualUsers, { min: 1, required: true, label: 'Le nombre d’utilisateurs' })
  if (vu) errors.virtualUsers = vu
  else if (Number(load.virtualUsers) > maxVu) errors.virtualUsers = vuTooHighMessage(maxVu)
  const think = intField(load.thinkTimeMs, { min: 0, required: true, label: 'Le think time' })
  if (think) errors.thinkTimeMs = think
  else if (Number(load.thinkTimeMs) > MAX_THINK_TIME_MS) errors.thinkTimeMs = `Le think time ne doit pas dépasser ${formatInt(MAX_THINK_TIME_MS)} ms.`
  const ramp = intField(load.rampUpSeconds, { min: 0, required: true, label: 'Le ramp-up' })
  if (ramp) errors.rampUpSeconds = ramp
  else if (limits?.maxDurationSeconds != null && Number(load.rampUpSeconds) > limits.maxDurationSeconds) {
    errors.rampUpSeconds = `Le ramp-up ne doit pas dépasser ${formatInt(limits.maxDurationSeconds)} s (durée maximale d’un test).`
  }
  const d = intField(load.durationSeconds, { min: 1, label: 'La durée' })
  if (d) errors.durationSeconds = d
  else if (limits?.maxDurationSeconds != null && load.durationSeconds.trim() && Number(load.durationSeconds) > limits.maxDurationSeconds) {
    errors.durationSeconds = `La durée ne doit pas dépasser ${formatInt(limits.maxDurationSeconds)} s${LIMIT_SUFFIX}`
  }
  const it = intField(load.iterations, { min: 1, label: 'Le nombre d’itérations' })
  if (it) errors.iterations = it
  else if (limits?.maxIterations != null && load.iterations.trim() && Number(load.iterations) > limits.maxIterations) {
    errors.iterations = `Le nombre d’itérations ne doit pas dépasser ${formatInt(limits.maxIterations)}${LIMIT_SUFFIX}`
  }
  const rps = intField(load.targetRps, { min: 1, label: 'Le débit cible' })
  if (rps) errors.targetRps = rps
  else if (limits?.maxTargetRps != null && load.targetRps.trim() && Number(load.targetRps) > limits.maxTargetRps) {
    errors.targetRps = `Le débit cible ne doit pas dépasser ${formatInt(limits.maxTargetRps)} req/s${LIMIT_SUFFIX}`
  }
  if (!ramp && !d && !errors.rampUpSeconds && load.durationSeconds.trim() && Number(load.rampUpSeconds) >= Number(load.durationSeconds)) errors.rampUpSeconds = RAMP_UP_TOO_LONG
  return errors
}

/** Libellés et aides courtes des champs de charge, avec la plage autorisée par le serveur ; les mêmes dans « Charge & résumé » et dans Paramètres. */
export const LOAD_FIELDS: { key: Exclude<keyof LoadConfig, 'presetId'>; label: string; required: boolean; placeholder?: string; hint: (limits?: Partial<LoadLimits> | null) => string }[] = [
  { key: 'virtualUsers', label: 'Utilisateurs virtuels', required: true,
    hint: (l) => `Nombre d’utilisateurs simulés en parallèle (1 à ${formatInt(l?.maxVirtualUsers ?? MAX_VIRTUAL_USERS_FALLBACK)}).` },
  { key: 'thinkTimeMs', label: 'Think time (ms)', required: true, hint: () => `Pause d’un utilisateur entre deux itérations : 0 à ${formatInt(MAX_THINK_TIME_MS)} ms.` },
  { key: 'rampUpSeconds', label: 'Ramp-up (s)', required: true,
    hint: (l) => `Démarrage progressif des utilisateurs${l?.maxDurationSeconds != null ? ` : 0 à ${formatInt(l.maxDurationSeconds)} s` : ''}, plus court que la durée.` },
  { key: 'durationSeconds', label: 'Durée (s)', required: false, placeholder: 'Sans limite de temps',
    hint: (l) => l?.maxDurationSeconds != null ? `1 à ${formatInt(l.maxDurationSeconds)} s ; vide = jusqu’à la fin des itérations.` : 'Vide = jusqu’à la fin des itérations.' },
  { key: 'iterations', label: 'Itérations', required: false,
    hint: (l) => `Passages complets du scénario par utilisateur${l?.maxIterations != null ? ` : 1 à ${formatInt(l.maxIterations)}` : ''}.` },
  { key: 'targetRps', label: 'Débit cible (req/s)', required: false, placeholder: 'Illimité',
    hint: (l) => `Requêtes par seconde, tous utilisateurs confondus${l?.maxTargetRps != null ? ` : jusqu’à ${formatInt(l.maxTargetRps)} req/s` : ''}.` },
]

/** Temps de réponse supposé par requête quand le test de validation n'a rien mesuré (estimation). */
export const ASSUMED_REQUEST_MS = 300

export interface LoadEstimate {
  /** Durée totale approximative du test (s). */
  totalSeconds: number
  /** Nombre approximatif de requêtes envoyées. */
  requests: number
  /** Démarrage du dernier utilisateur (s) : (n − 1) × ramp-up / n, comme le moteur. */
  lastStartSeconds: number
  /** Temps d'un utilisateur du début à la fin de ses itérations (sans durée fixée). */
  perUserSeconds: number
  /** Temps de réponse moyen utilisé (ms) et sa source. */
  requestMs: number
  measured: boolean
}

/**
 * Estimation APPROXIMATIVE d'un test (mêmes règles que le moteur) :
 * - chaque utilisateur démarre (ramp-up / nombre d'utilisateurs) après le précédent ;
 * - durée fixée : le test s'arrête après cette durée, comptée depuis le début (ramp-up compris) ;
 * - sans durée : chaque utilisateur joue ses itérations (1 si rien n'est indiqué), avec le think time
 *   entre deux itérations ;
 * - débit cible : jamais plus de requêtes par seconde que ce débit.
 * Temps de réponse : moyenne mesurée par le test de validation, sinon ASSUMED_REQUEST_MS.
 */
export function estimateLoad(load: Omit<LoadConfig, 'presetId'>, stepCount: number, measuredRequestMs?: number | null): LoadEstimate | null {
  if (Object.keys(validateLoad(load)).length > 0 || stepCount <= 0) return null
  const vu = Number(load.virtualUsers)
  const ramp = Number(load.rampUpSeconds)
  const think = Number(load.thinkTimeMs) / 1000
  const duration = load.durationSeconds.trim() ? Number(load.durationSeconds) : null
  const iterations = load.iterations.trim() ? Number(load.iterations) : null
  const rps = load.targetRps.trim() ? Number(load.targetRps) : null
  const measured = measuredRequestMs != null && measuredRequestMs > 0
  const requestMs = measured ? Math.round(measuredRequestMs!) : ASSUMED_REQUEST_MS
  const iterationSeconds = (stepCount * requestMs) / 1000
  const stagger = vu > 1 && ramp > 0 ? ramp / vu : 0
  const lastStartSeconds = (vu - 1) * stagger
  const cycle = iterationSeconds + think

  let requests: number
  let totalSeconds: number
  let perUserSeconds: number
  if (duration != null) {
    let iterationsPlayed = 0
    let lastEnd = 0
    for (let i = 0; i < vu; i++) {
      const start = i * stagger
      const active = duration - start
      if (active <= 0) continue
      // Une itération commencée avant la fin de la durée va jusqu'au bout.
      let n = Math.ceil(active / cycle)
      if (iterations != null) n = Math.min(n, iterations)
      iterationsPlayed += n
      lastEnd = Math.max(lastEnd, start + n * iterationSeconds + Math.max(0, n - 1) * think)
    }
    requests = iterationsPlayed * stepCount
    totalSeconds = Math.min(lastEnd, duration + iterationSeconds)
    perUserSeconds = iterations != null ? iterations * iterationSeconds + (iterations - 1) * think : duration
    if (rps != null) requests = Math.min(requests, Math.floor(rps * duration))
  } else {
    const n = iterations ?? 1
    perUserSeconds = n * iterationSeconds + (n - 1) * think
    requests = vu * n * stepCount
    totalSeconds = lastStartSeconds + perUserSeconds
    if (rps != null) totalSeconds = Math.max(totalSeconds, requests / rps)
  }
  return { totalSeconds: Math.max(0, totalSeconds), requests: Math.max(0, Math.round(requests)), lastStartSeconds, perUserSeconds, requestMs, measured }
}

/** Durée lisible arrondie : « 45 s », « 16 min 5 s », « 1 h 12 min ». */
export function formatApproxSeconds(seconds: number): string {
  const s = Math.max(0, Math.round(seconds))
  if (s >= 3600) {
    const h = Math.floor(s / 3600)
    const m = Math.round((s % 3600) / 60)
    return m ? `${h} h ${m} min` : `${h} h`
  }
  return formatSeconds(s)
}

/** Avertissements non bloquants sur une configuration de charge valide. */
export function loadWarnings(load: LoadConfig, estimate?: LoadEstimate | null): string[] {
  const warnings: string[] = []
  if (!load.durationSeconds.trim() && !load.iterations.trim()) warnings.push('Ni durée ni itérations : chaque utilisateur jouera le scénario une seule fois.')
  if (estimate && !load.durationSeconds.trim() && estimate.lastStartSeconds >= 1 && estimate.lastStartSeconds > estimate.perUserSeconds) {
    warnings.push(`Le dernier utilisateur démarrera après ${formatApproxSeconds(estimate.lastStartSeconds)}, alors qu’un utilisateur termine en ≈ ${formatApproxSeconds(estimate.perUserSeconds)} : `
      + 'peu d’utilisateurs seront actifs en même temps. Réduisez le ramp-up ou fixez une durée.')
  }
  if (estimate && estimate.totalSeconds > 3600) warnings.push(`Durée estimée supérieure à 1 heure (≈ ${formatApproxSeconds(estimate.totalSeconds)}).`)
  return warnings
}

/** Profil hors des limites du serveur : raison affichée en infobulle (null si utilisable). */
export function presetBlockedReason(preset: LoadPreset, limits: Partial<LoadLimits> | null | undefined): string | null {
  if (!preset.values || !limits) return null
  const v = preset.values
  if (limits.maxVirtualUsers != null && Number(v.virtualUsers) > limits.maxVirtualUsers) return `Dépasse la limite du serveur : ${formatInt(limits.maxVirtualUsers)} utilisateurs`
  if (limits.maxDurationSeconds != null && v.durationSeconds && Number(v.durationSeconds) > limits.maxDurationSeconds) {
    return `Dépasse la limite du serveur : ${formatInt(limits.maxDurationSeconds)} s de durée`
  }
  if (limits.maxIterations != null && v.iterations && Number(v.iterations) > limits.maxIterations) return `Dépasse la limite du serveur : ${formatInt(limits.maxIterations)} itérations`
  return null
}

/** Temps de réponse moyen mesuré par le test de validation (ms), ou null. */
export function measuredRequestMs(results: { response?: { durationMs: number } | null }[]): number | null {
  const times = results.map((r) => r.response?.durationMs).filter((v): v is number => typeof v === 'number' && v >= 0)
  return times.length ? times.reduce((a, b) => a + b, 0) / times.length : null
}


/** Seuils de réussite : taux d'erreurs maximal obligatoire (0 à 100, virgule acceptée), P95 maximal optionnel (ms). */
export function validateThresholds(info: Pick<ScenarioInfo, 'maxErrorRatePercent' | 'maxP95Ms'>): FieldErrors {
  const errors: FieldErrors = {}
  if (info.maxErrorRatePercent !== undefined) {
    const raw = info.maxErrorRatePercent.trim().replace(',', '.')
    const n = Number(raw)
    if (!raw) errors.maxErrorRatePercent = 'Indiquez le taux d’erreurs maximal (ex. 1).'
    else if (!/^\d+(\.\d+)?$/.test(raw) || n > 100) errors.maxErrorRatePercent = 'Le taux d’erreurs maximal doit être un nombre entre 0 et 100.'
  }
  if (info.maxP95Ms !== undefined && info.maxP95Ms.trim()) {
    const p = intField(info.maxP95Ms, { min: 1, label: 'Le P95 maximal' })
    if (p) errors.maxP95Ms = p
  }
  return errors
}

/** Seuils pour le serveur : P95 vide = 0 (« aucun seuil ») ; champs absents (ancien brouillon) = inchangés. */
export function thresholdsToRequest(info: Pick<ScenarioInfo, 'maxErrorRatePercent' | 'maxP95Ms'>): { maxErrorRatePercent?: number; maxP95Ms?: number } {
  const out: { maxErrorRatePercent?: number; maxP95Ms?: number } = {}
  if (info.maxErrorRatePercent !== undefined && info.maxErrorRatePercent.trim()) out.maxErrorRatePercent = Number(info.maxErrorRatePercent.trim().replace(',', '.'))
  if (info.maxP95Ms !== undefined) out.maxP95Ms = info.maxP95Ms.trim() ? Number(info.maxP95Ms.trim()) : 0
  return out
}

/** Valeurs texte des seuils d'un scénario enregistré (défaut 1 %). */
export function thresholdsFromScenario(s: { maxErrorRatePercent?: number | null; maxP95Ms?: number | null }): Pick<ScenarioInfo, 'maxErrorRatePercent' | 'maxP95Ms'> {
  return { maxErrorRatePercent: String(s.maxErrorRatePercent ?? 1).replace('.', ','), maxP95Ms: s.maxP95Ms == null ? '' : String(s.maxP95Ms) }
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
