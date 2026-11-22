// Types des requêtes et réponses JSON de l'API du backend.

// ---- Enums du backend ----

export type BackendApplicationStatus = 'CONNECTED' | 'FAILED' | 'ERROR'
/** DRAFT : brouillon de l'assistant (ni exécutable ni planifiable). */
export type BackendScenarioStatus = 'ACTIVE' | 'INACTIVE' | 'DRAFT'
export type BackendStepStatus = 'ACTIVE' | 'INACTIVE'
/** THRESHOLDS_EXCEEDED : test allé au bout, mais un seuil de réussite du scénario est dépassé. */
export type BackendExecutionStatus = 'QUEUED' | 'RUNNING' | 'PAUSED' | 'SUCCESS' | 'THRESHOLDS_EXCEEDED' | 'FAILED' | 'CANCELLED'
export type BackendHttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
export type BackendAuditAction =
  | 'CREATE' | 'READ' | 'UPDATE' | 'DELETE' | 'TEST' | 'EXECUTE' | 'FINISH' | 'RETRY' | 'CANCEL' | 'PAUSE' | 'RESUME'
  | 'LOGIN' | 'LOGOUT' | 'ENABLE' | 'DISABLE' | 'TRIGGER'
  | 'VIEW_HISTORY' | 'VIEW_REPORT' | 'EXPORT_REPORT' | 'EXPORT_CSV' | 'ARCHIVE' | 'RESTORE'
  | 'LOGIN_FAILED' | 'ACCOUNT_LOCKED' | 'PASSWORD_SET' | 'PASSWORD_RESET_LINK' | 'PASSWORD_CHANGED' | 'ROLE_CHANGE' | 'PURGE'
export type BackendAuditModule =
  | 'AUTH' | 'USER' | 'APPLICATION' | 'SCENARIO' | 'STEP' | 'EXECUTION' | 'METRIC' | 'DASHBOARD' | 'ADMIN'
  | 'SCHEDULING' | 'SECURITY' | 'SETTINGS'
/** Categories du filtre de la page Journal d'audit (regroupent des modules). */
export type BackendAuditCategory = 'APPLICATIONS' | 'SCENARIOS' | 'EXECUTIONS' | 'SCHEDULING' | 'USERS' | 'SECURITY' | 'SETTINGS'
export type BackendAuditResult = 'SUCCESS' | 'FAILURE'

/** Type de notification. */
export type BackendNotificationType =
  | 'EXECUTION_SUCCESS' | 'EXECUTION_THRESHOLDS_EXCEEDED' | 'EXECUTION_FAILED' | 'EXECUTION_CANCELLED' | 'SCHEDULE_TRIGGERED' | 'SCHEDULE_FAILED' | 'SCHEDULE_REGRESSION'
export type BackendScheduleType = 'ONE_TIME' | 'RECURRING_CRON'
/** ALWAYS : à chaque fin d'exécution ; PROBLEMS : seulement en cas d'échec ou de régression. */
export type BackendScheduleNotifyMode = 'ALWAYS' | 'PROBLEMS'
/** État calculé côté serveur (voir ScheduleViewService). */
export type BackendScheduleState = 'ACTIVE' | 'PAUSED' | 'SUSPENDED' | 'COMPLETED' | 'EXPIRED'
export type BackendScheduledRunOutcome = 'SKIPPED_RUNNING' | 'SKIPPED_CAPACITY' | 'MISSED' | 'FAILED'

// ---- Profile (GET /api/profile) ----

export interface BackendProfileResponse {
  id: string
  username: string | null
  name: string | null
  email: string | null
  roles: string[]
  /** Fuseau horaire IANA choisi ; `null` si jamais choisi. */
  timezone: string | null
  /** Rôle applicatif et son libellé français. */
  appRole?: 'SUPER_ADMIN' | 'PERFORMANCE_ENGINEER' | 'VIEWER' | null
  appRoleLabel?: string | null
  /** Apparence mémorisée sur le profil ; null = jamais choisie. */
  theme?: 'LIGHT' | 'DARK' | 'SYSTEM' | null
  /** Prénom et nom (système d'authentification), pour pré-remplir « Modifier ». */
  firstName?: string | null
  lastName?: string | null
  /** true pour le Super administrateur seulement : prénom, nom et e-mail modifiables. */
  canEditIdentity?: boolean
}

/** PUT /api/profile/identity (Super administrateur). */
export interface BackendProfileIdentityRequest {
  firstName: string
  lastName: string
  email: string
}

/** PATCH /api/profile : préférences facultatives (null = inchangée). */
export interface BackendProfileUpdateRequest {
  timezone?: string
  theme?: 'LIGHT' | 'DARK' | 'SYSTEM'
}

/** GET /api/profile/security : dernière connexion et sessions ouvertes du compte connecté. */
export interface BackendProfileSecurity {
  lastLoginAt: string | null
  lastLoginIp: string | null
  sessions: { id: string; device: string | null; mobile: boolean; ip: string | null; started: string | null; lastAccess: string | null; current: boolean }[]
  /** false : navigateur / système inconnus (sessions lues sans ces informations). */
  devicesKnown: boolean
  message: string | null
}

/** GET/PUT /api/settings/limits : limites applicatives (≤ plafond du serveur) et délais en lecture seule. */
export type BackendLimitKey = 'virtualUsersPerTest' | 'virtualUsersTotal' | 'concurrentTests' | 'maxDurationSeconds' | 'maxIterations' | 'maxTargetRps'
/** GET /api/settings/load-limits : limites d'un test lues par l'assistant (tout utilisateur connecté). */
export interface BackendLoadLimits {
  maxVirtualUsers: number
  maxDurationSeconds: number
  maxIterations: number
  maxTargetRps: number
}

export interface BackendExecutionLimits {
  limits: { key: BackendLimitKey; label: string; unit: string; value: number; ceiling: number }[]
  executionTimeoutSeconds: number
  availabilityTimeoutSeconds: number
  effectiveVirtualUsersPerTest: number
  /** Ce que l'enregistrement a ajusté de lui-même (ex. « par test » ramenée au total) ; vide en lecture. */
  notices?: string[]
}

/** GET/PUT /api/settings/thresholds : valeurs proposées aux nouveaux scénarios et seuil de régression. */
export interface BackendThresholdSettings {
  maxErrorRatePercent: number
  /** 0 = aucun seuil de P95 proposé. */
  maxP95Ms: number
  regressionPercent: number
  /** Réglages proposés aux NOUVEAUX scénarios (absents = valeurs initiales, voir DEFAULT_THRESHOLDS). */
  errorBrakeEnabled?: boolean
  errorBrakeRatePercent?: number
  errorBrakeWindowSeconds?: number
  throttleOn429?: boolean
  throttleDefaultDelaySeconds?: number
  throttleMaxRetries?: number
  requestDetailMode?: BackendRequestDetailMode
}

/** GET /api/settings/storage : taille de la base et purges automatiques. */
export interface BackendStoragePurge {
  lastRunAt: string | null
  lastDeleted: number | null
  lastSummary: string | null
  /** null = purge automatique désactivée. */
  nextRunAt: string | null
}
export interface BackendStorageInfo {
  databaseSizeBytes: number | null
  results: BackendStoragePurge
  audit: BackendStoragePurge
}

/** GET /api/settings/integrations (Super administrateur) — aucun secret. */
export interface BackendIntegrations {
  mail: { enabled: boolean; kind: 'MAILPIT' | 'SMTP'; host: string; port: number; from: string; fromName: string | null; authenticated: boolean; reachable: boolean; message: string | null }
  authenticationMail: { host: string; port: string | null; from: string | null; fromName: string | null; authenticated: boolean } | null
  monitoring: { applicationId: string; applicationName: string; archived: boolean; type: 'PROMETHEUS' | 'ACTUATOR'; endpoint: string | null; reachable: boolean; message: string | null }[]
}

// ---- Applications ----

/** Authentification appliquée à toutes les requêtes vers l'application. */
export type BackendApplicationAuthType = 'NONE' | 'BEARER_TOKEN' | 'API_KEY' | 'BASIC' | 'LOGIN_TOKEN'
export type BackendApiKeyLocation = 'HEADER' | 'QUERY'

/** Où lire une valeur capturée dans la réponse : corps JSON (chemin), en-tête (nom, ou « Set-Cookie:NOM »)
 * ou page HTML (sélecteur CSS, suivi de « ::attr(NOM) » pour un attribut — capture d'étape uniquement). */
export type BackendCaptureSource = 'JSON' | 'HEADER' | 'HTML'

/** Comparaison de l'assertion JSON d'une étape : égale (défaut) ou contient. */
export type BackendJsonAssertionOperator = 'EQUALS' | 'CONTAINS'

/** Secrets (token, apiKeyValue, password, loginBody) : vides en mise à jour = valeur enregistrée conservée. */
export interface BackendApplicationAuthRequest {
  type: BackendApplicationAuthType
  token?: string | null
  apiKeyName?: string | null
  apiKeyValue?: string | null
  apiKeyLocation?: BackendApiKeyLocation | null
  username?: string | null
  password?: string | null
  loginPath?: string | null
  loginBody?: string | null
  tokenJsonPath?: string | null
  /** LOGIN_TOKEN : JSON (défaut, null) ou HEADER (tokenJsonPath = nom de l'en-tête qui contient le jeton). */
  tokenSource?: BackendCaptureSource | null
  tokenHeaderName?: string | null
  tokenPrefix?: string | null
  /** BEARER_TOKEN / API_KEY / BASIC : endpoint protégé utilisé par le test de connectivité. */
  verificationPath?: string | null
}

/** Vue sans secret : seulement des indications "configuré". */
export interface BackendApplicationAuthResponse {
  type: BackendApplicationAuthType
  apiKeyName: string | null
  apiKeyLocation: BackendApiKeyLocation | null
  username: string | null
  loginPath: string | null
  tokenJsonPath: string | null
  /** null = JSON. */
  tokenSource?: BackendCaptureSource | null
  tokenHeaderName: string | null
  tokenPrefix: string | null
  verificationPath: string | null
  tokenConfigured: boolean
  apiKeyValueConfigured: boolean
  passwordConfigured: boolean
  loginBodyConfigured: boolean
}

export interface BackendApplicationRequest {
  name: string
  description: string | null
  url: string
  /** Absent = aucune authentification à la création, inchangée en mise à jour. */
  auth?: BackendApplicationAuthRequest
  /** Absent = aucun monitoring à la création, inchangé en mise à jour. */
  monitoring?: BackendMonitoringRequest
  /** Absent = aucun proxy à la création, réseau inchangé en mise à jour. */
  network?: BackendNetworkRequest
}

/** Proxy pour joindre l'application : du serveur (défaut), aucun, ou propre à l'application. */
export type BackendProxyMode = 'INHERIT' | 'NONE' | 'CUSTOM'

/** Mot de passe vide en modification = mot de passe enregistré conservé. */
export interface BackendNetworkRequest {
  proxyMode: BackendProxyMode
  proxyHost: string | null
  proxyPort: number | null
  proxyUsername: string | null
  proxyPassword: string | null
  /** Autorités de certification (PEM) propres à cette application ; vide = confiance de la JVM seule. */
  tlsCaCertificate?: string | null
}

/** Résumé d'un certificat d'autorité enregistré. */
export interface BackendCertificateInfo {
  subject: string
  issuer: string
  notAfter: string
  expired: boolean
}

export interface BackendNetworkResponse {
  proxyMode: BackendProxyMode
  proxyHost: string | null
  proxyPort: number | null
  proxyUsername: string | null
  proxyPasswordConfigured: boolean
  tlsCaCertificate?: string | null
  tlsCaCertificates?: BackendCertificateInfo[]
}

export type BackendMonitoringType = 'NONE' | 'PROMETHEUS' | 'ACTUATOR'
export type BackendMonitoringAuthType = 'NONE' | 'BEARER' | 'HEADER'

/** Monitoring CPU / RAM du processus de l'application testée. Secret vide en modification = inchangé. */
export interface BackendMonitoringRequest {
  type: BackendMonitoringType
  url: string | null
  authType: BackendMonitoringAuthType
  headerName: string | null
  secret: string | null
  intervalSeconds: number
  /** Lecture en direct, sans le proxy de l'application (URL de monitoring souvent interne). */
  withoutProxy?: boolean
}

export interface BackendMonitoringResponse {
  type: BackendMonitoringType
  url: string | null
  authType: BackendMonitoringAuthType
  headerName: string | null
  secretConfigured: boolean
  intervalSeconds: number
  withoutProxy?: boolean
}

/** « Tester le monitoring » : CPU en % d'un cœur, RAM en Mo. */
export interface BackendMonitoringTestResponse {
  success: boolean
  message: string
  cpuPercent: number | null
  memoryMb: number | null
  memoryMetric: string | null
}

export interface BackendApplicationResponse {
  id: string
  name: string
  description: string | null
  url: string
  auth: BackendApplicationAuthResponse
  /** Monitoring CPU / RAM (absent = NONE). */
  monitoring?: BackendMonitoringResponse
  /** Proxy pour joindre l'application, sans le mot de passe. */
  network?: BackendNetworkResponse
  /** Archivage logique : masquée des listes par défaut, restaurable. */
  archived: boolean
  archivedAt: string | null
  /** `null` tant qu'aucun test de connectivité n'a été lancé. */
  status: BackendApplicationStatus | null
  /** Message du dernier test ; null si aucun test valide. */
  statusMessage: string | null
  statusCheckedAt: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface BackendApplicationTestResponse {
  status: BackendApplicationStatus
  httpStatus: number | null
  responseTimeMs: number
  message: string
}

// ---- Scenarios ----

export interface BackendScenarioRequest {
  applicationId: string
  name: string
  description: string | null
  /** Paramètres de charge, tous optionnels (valeurs par défaut appliquées par le backend). */
  virtualUsers?: number | null
  rampUpSeconds?: number | null
  durationSeconds?: number | null
  iterations?: number | null
  thinkTimeMs?: number | null
  /** Données CSV optionnelles (première ligne = noms des variables). */
  csvData?: string | null
  /** Débit cible optionnel (requêtes/s, partagé entre les utilisateurs virtuels) ; null = aucun pacing. */
  targetRps?: number | null
  /** Création uniquement : true crée un brouillon (DRAFT), publié ensuite via /publish. */
  draft?: boolean | null
  /** Brouillon : étape de l'assistant atteinte (0..3) ; null/absent = inchangée. */
  wizardStep?: number | null
  /** Brouillon : état brut de l'assistant (JSON, voir draftModel.ts) ; null/absent = inchangé. */
  draftState?: string | null
  /** Variables du scénario : objet JSON {"nom": "valeur"} ; null/absent = inchangées, "{}" = aucune. */
  variables?: string | null
  csvDistribution?: BackendCsvDistribution | null
  /** Détail des requêtes conservé à l'exécution ; null/absent = inchangé. */
  requestDetailMode?: BackendRequestDetailMode | null
  /** Seuils de réussite : taux d'erreurs maximal (%) ; P95 maximal (ms, 0 = aucun). */
  maxErrorRatePercent?: number | null
  maxP95Ms?: number | null
  /** Frein automatique : taux d'erreurs (%) sur la fenêtre glissante, 0 = désactivé ; fenêtre (s, 5 à 300) ; null/absent = inchangé. */
  errorBrakeRatePercent?: number | null
  errorBrakeWindowSeconds?: number | null
  /** Ralentissement sur 429 : false = désactivé (test de résilience) ; attente sans Retry-After (1 à 300 s) ; rejeux (0 à 10). null/absent = inchangé. */
  throttleOn429?: boolean | null
  throttleDefaultDelaySeconds?: number | null
  throttleMaxRetries?: number | null
  /** Variation aléatoire du think time (± %, 0 à 100) : 0 = fixe ; null/absent = inchangée. */
  thinkTimeVariationPercent?: number | null
  /** Mode de charge ; null/absent = inchangé (MANUAL à la création). */
  loadMode?: BackendLoadMode | null
}

/** Mode de charge : MANUAL (charge saisie) ou STRESS (montée automatique jusqu'au point de rupture, réglages de charge ignorés). */
export type BackendLoadMode = 'MANUAL' | 'STRESS'

/** PUT /api/scenarios/{id}/full : informations + étapes (avec id = modifiée, sans id = créée, absente = supprimée ; ordre = position). */
export interface BackendScenarioFullRequest {
  scenario: BackendScenarioRequest
  steps: { id: string | null; step: BackendStepRequest }[]
}

export interface BackendScenarioFullResponse {
  scenario: BackendScenarioResponse
  steps: BackendStepResponse[]
}

/** Distribution du CSV : une ligne par utilisateur virtuel, ou une nouvelle ligne à chaque itération. */
export type BackendCsvDistribution = 'PER_VIRTUAL_USER' | 'PER_ITERATION'

/** Détail des requêtes d'une exécution : aucun, échecs seulement, échecs + échantillon des réussites. */
export type BackendRequestDetailMode = 'NONE' | 'FAILURES' | 'FAILURES_AND_SAMPLE'

export interface BackendScenarioResponse {
  id: string
  applicationId: string
  applicationName: string
  name: string
  description: string | null
  status: BackendScenarioStatus
  /** Paramètres de charge du scénario. */
  virtualUsers: number
  rampUpSeconds: number
  durationSeconds: number | null
  iterations: number | null
  thinkTimeMs: number
  csvData: string | null
  targetRps: number | null
  createdBy: string
  createdAt: string
  updatedAt: string
  archived: boolean
  archivedAt: string | null
  /** Vrai si l'application du scénario est archivée (scénario masqué). */
  applicationArchived: boolean
  /** Brouillons uniquement : étape de l'assistant atteinte et état brut de l'assistant. */
  wizardStep: number | null
  draftState: string | null
  /** Planifications suspendues par la désactivation du scénario (réactivables). */
  pausedSchedules: number
  variables: string | null
  csvDistribution: BackendCsvDistribution
  requestDetailMode: BackendRequestDetailMode
  /** Seuils de réussite : taux d'erreurs maximal (%, défaut 1) et P95 maximal (ms, null = aucun). */
  maxErrorRatePercent?: number | null
  maxP95Ms?: number | null
  /** Frein automatique : taux d'erreurs (%) sur la fenêtre glissante (null = désactivé) et fenêtre (s, null = 30). */
  errorBrakeRatePercent?: number | null
  errorBrakeWindowSeconds?: number | null
  /** Ralentissement sur 429 : actif (null = actif), attente sans Retry-After (s, null = 5), rejeux par étape (null = 3). */
  throttleOn429?: boolean | null
  throttleDefaultDelaySeconds?: number | null
  throttleMaxRetries?: number | null
  /** Variation aléatoire du think time (± %) ; null = fixe. */
  thinkTimeVariationPercent?: number | null
  /** Avertissements de charge, jamais bloquants (ex. beaucoup d'utilisateurs sans débit cible). */
  loadWarnings?: string[]
  /** Mode de charge (absent : réponse antérieure, MANUAL). */
  loadMode?: BackendLoadMode
}

// ---- Steps ----

export interface BackendStepRequest {
  scenarioId: string
  name: string
  method: BackendHttpMethod
  url: string
  headers: string | null
  body: string | null
  order: number
  expectedStatus: number | null
  /** Options de l'étape, toutes optionnelles. */
  thinkTimeMs?: number | null
  timeoutSeconds?: number | null
  followRedirects?: boolean | null
  assertionBodyContains?: string | null
  /** Capture d'une variable dans la réponse, disponible pour les étapes suivantes du même utilisateur virtuel. */
  captureVariableName?: string | null
  captureJsonPath?: string | null
  /** Source de la capture : JSON (défaut, null) ou HEADER (captureJsonPath = nom de l'en-tête, ou « Set-Cookie:NOM »). */
  captureSource?: BackendCaptureSource | null
  /** Temps de réponse maximal (ms) : au-delà, l'étape échoue. null = aucun contrôle. */
  maxResponseTimeMs?: number | null
  /** Assertion JSON simple : le champ (même syntaxe que la capture) égale / contient la valeur. */
  assertionJsonPath?: string | null
  assertionJsonOperator?: BackendJsonAssertionOperator | null
  assertionJsonValue?: string | null
  /** Variables de chemin (:nom / {nom}) : objet JSON {"nom": "valeur ou ${variable}"}. */
  pathParams?: string | null
  /** Paramètres de requête : tableau JSON [{"key","value","enabled"}] (l'URL finale est construite par le backend). */
  queryParams?: string | null
}

// ---- Test détaillé : 1 utilisateur virtuel, 1 passage, secrets masqués ----

export type BackendTestRunStepStatus = 'PASSED' | 'FAILED' | 'SKIPPED'

export interface BackendTestRunAuth {
  type: string
  success: boolean
  message: string
  durationMs: number
}

export interface BackendTestRunStepResult {
  index: number
  stepId: string | null
  name: string
  status: BackendTestRunStepStatus
  success: boolean
  error: string | null
  /** TIMEOUT, CONNECTION_REFUSED, DNS, TLS, NETWORK, INVALID_REQUEST, UNRESOLVED_VARIABLE, HTTP_STATUS, ASSERTION, AUTH_FAILED, GLOBAL_TIMEOUT, PREVIOUS_FAILED, INTERRUPTED */
  errorKind: string | null
  request: {
    method: BackendHttpMethod
    url: string
    headers: Record<string, string>
    body: string | null
    followRedirects: boolean
    timeoutSeconds: number
  } | null
  response: {
    status: number
    statusText: string
    durationMs: number
    sizeBytes: number
    contentType: string | null
    headers: Record<string, string>
    body: string
    truncated: boolean
    json: boolean
  } | null
  checks: {
    status: { expected: string; actual: number | null; ok: boolean }
    assertion: { expectedContent: string; ok: boolean | null } | null
    capture: { variableName: string; jsonPath: string; found: boolean; value: string | null; masked: boolean; message: string } | null
    /** Absents (ou null) si le contrôle n'est pas configuré sur l'étape. */
    responseTime?: { maxMs: number; actualMs: number | null; ok: boolean | null } | null
    json?: { path: string; operator: BackendJsonAssertionOperator; expected: string; ok: boolean | null; message: string | null } | null
  } | null
  urlWarning: { message: string; suggestedPath: string } | null
  warnings: string[]
}

export interface BackendTestRunResponse {
  scenarioId: string
  applicationUrl: string
  success: boolean
  totalSteps: number
  executedSteps: number
  passedSteps: number
  totalDurationMs: number
  timedOut: boolean
  auth: BackendTestRunAuth
  steps: BackendTestRunStepResult[]
  variables: Record<string, string>
}

/** Évènements du flux NDJSON (?stream=true). */
export type BackendTestRunEvent =
  | { type: 'started'; totalSteps: number; steps: { stepId: string; name: string; method: string; url: string }[] }
  | { type: 'auth'; auth: BackendTestRunAuth }
  | { type: 'step-started'; index: number; name: string }
  | { type: 'step'; step: BackendTestRunStepResult }
  | { type: 'done'; result: BackendTestRunResponse }

export interface BackendStepResponse {
  id: string
  scenarioId: string
  /** Nom du scénario de l'étape. */
  scenarioName: string
  name: string
  method: BackendHttpMethod
  url: string
  headers: string | null
  body: string | null
  order: number
  expectedStatus: number | null
  thinkTimeMs: number | null
  timeoutSeconds: number | null
  followRedirects: boolean | null
  assertionBodyContains: string | null
  captureVariableName: string | null
  captureJsonPath: string | null
  /** null ou absent = JSON. */
  captureSource?: BackendCaptureSource | null
  /** Variables de chemin (objet JSON {"nom": "valeur"}) et paramètres de requête (tableau JSON). */
  pathParams: string | null
  queryParams: string | null
  maxResponseTimeMs?: number | null
  assertionJsonPath?: string | null
  assertionJsonOperator?: BackendJsonAssertionOperator | null
  assertionJsonValue?: string | null
  status: BackendStepStatus
  createdAt: string
  updatedAt: string
}

// ---- Executions ----

export interface BackendExecutionRequest {
  scenarioId: string
}

export interface BackendExecutionResponse {
  id: string
  scenarioId: string
  scenarioName: string
  startedAt: string
  finishedAt: string | null
  status: BackendExecutionStatus
  /** Paramètres de charge copiés au lancement. */
  virtualUsers: number
  rampUpSeconds: number
  durationSeconds: number | null
  iterations: number | null
  totalSteps: number
  successfulSteps: number
  failedSteps: number
  duration: number | null
  errorMessage: string | null
  /** Mode de charge et résultat du test de stress (null en mode manuel). */
  loadMode?: BackendLoadMode
  stress?: BackendStressResult | null
}

/** LoadPilot lui-même (l'injecteur) : CPU en % de la machine (null si indisponible), mémoire en Mo. */
export interface BackendInjectorStatus {
  cpuPercent: number | null
  cpuMaxPercent: number | null
  machineCpuPercent: number | null
  heapUsedMb: number
  heapMaxMb: number
  activeVirtualThreads: number
  platformThreads: number
  cpuWarningPercent: number
  overloaded: boolean
  warning: string | null
}

export interface BackendExecutionStatusResponse {
  id: string
  status: BackendExecutionStatus
  startedAt: string
  finishedAt: string | null
  duration: number | null
  virtualUsers: number
  totalRequests: number
  successfulRequests: number
  failedRequests: number
  progressPercent: number | null
  scenarioId: string | null
  scenarioName: string | null
  rampUpSeconds: number | null
  durationSeconds: number | null
  iterations: number | null
  /** Itérations prévues au total (VU × itérations) ; null avec une durée seule. */
  plannedIterations: number | null
  errorMessage: string | null
  /** Compteurs en direct pendant RUNNING / PAUSED (mémoire du moteur) ; null sinon. */
  live: BackendLiveStats | null
  /** Début de la pause en cours (PAUSED), sinon null. */
  pausedAt: string | null
  /** Temps total passé en pause (ms), pause en cours comprise. */
  pausedDurationMs: number
  /** Temps de travail réel (ms), pauses exclues ; null tant que non démarré. */
  activeElapsedMs: number | null
  /** CPU / RAM du processus de l'application testée ; null/absent sans monitoring. */
  resources?: BackendExecutionResources | null
  /** LoadPilot lui-même pendant le test (CPU, mémoire, threads, saturation) ; null hors exécution en cours. */
  injector?: BackendInjectorStatus | null
  /** Mode de charge ; montée du test de stress en cours (null sinon) ; résultat du stress une fois terminé. */
  loadMode?: BackendLoadMode
  stressLive?: BackendStressLive | null
  stress?: BackendStressResult | null
}

/** Résultat d'un test de stress (outcome null : test arrêté autrement). Champs du point de rupture : début de l'anomalie. */
export type BackendStressOutcome = 'BREAKPOINT' | 'NO_BREAKPOINT' | 'INCONCLUSIVE' | 'BASELINE_UNUSABLE'
export type BackendBreakpointCriterion = 'RESPONSE_TIME' | 'REFUSALS' | 'THROUGHPUT'

export interface BackendStressResult {
  outcome: BackendStressOutcome | null
  /** NO_BREAKPOINT : limite de LoadPilot atteinte. */
  limit: 'TARGET_RPS' | 'VIRTUAL_USERS' | 'DURATION' | null
  message: string | null
  peakVirtualUsers: number | null
  peakOfferedRps: number | null
  criteria: BackendBreakpointCriterion[]
  /** Début de l'anomalie et confirmation, en secondes depuis le début du test. */
  onsetSeconds: number | null
  detectedSeconds: number | null
  virtualUsers: number | null
  offeredRps: number | null
  throughputRps: number | null
  p95Ms: number | null
  baselineP95Ms: number | null
  refusalPercent: number | null
}

/** Montée d'un test de stress en cours. */
export interface BackendStressLive {
  phase: 'WARMUP' | 'BASELINE' | 'RAMP' | 'HOLD'
  elapsedSeconds: number
  warmupEndSeconds: number
  baselineEndSeconds: number
  offeredRps: number
  peakOfferedRps: number
  virtualUsers: number
  /** P95 de la mesure de référence, null tant qu'elle n'est pas faite. */
  baselineP95Ms: number | null
  holdingLimit: 'TARGET_RPS' | 'VIRTUAL_USERS' | null
  maxVirtualUsers: number
  maxRps: number
  maxDurationSeconds: number
}

/** CPU (% d'un cœur) et RAM (Mo) du processus de l'application testée. */
export interface BackendExecutionResources {
  source: BackendMonitoringType | null
  intervalSeconds: number
  cpuPercent: number | null
  memoryMb: number | null
  cpuAvgPercent: number | null
  cpuMaxPercent: number | null
  memoryMaxMb: number | null
  /** Monitoring indisponible (l'exécution continue normalement). */
  message: string | null
  cpuHistory: (number | null)[]
  memoryHistory: (number | null)[]
}

export interface BackendLiveStepStats {
  stepId: string | null
  name: string
  method: BackendHttpMethod | null
  requests: number
  successes: number
  failures: number
  avgResponseTimeMs: number | null
}

export interface BackendLiveStats {
  elapsedMs: number
  activeVirtualUsers: number
  startedVirtualUsers: number
  completedIterations: number
  requests: number
  successes: number
  failures: number
  /** Requêtes par seconde sur les 5 dernières secondes. */
  throughputRps: number
  avgResponseTimeMs: number | null
  steps: BackendLiveStepStats[]
}

/** Ligne des listes d'exécutions (GET /api/executions/history et /page). */
export interface BackendExecutionHistoryResponse {
  id: string
  scenarioId: string
  scenarioName: string
  applicationId: string
  applicationName: string
  status: BackendExecutionStatus
  startedAt: string
  finishedAt: string | null
  duration: number | null
  virtualUsers: number
  rampUpSeconds: number
  durationSeconds: number | null
  iterations: number | null
  totalSteps: number
  successfulSteps: number
  failedSteps: number
  /** Utilisateur qui a lancé l'exécution ; `null` si inconnu. */
  triggeredByUsername: string | null
  /** Requêtes exécutées / durée (s) ; `null` si non calculable. */
  throughput: number | null
  /** CPU moyen / max du processus testé (% d'un cœur) et RAM max (Mo) ; null/absent sans monitoring. */
  cpuAvgPercent?: number | null
  cpuMaxPercent?: number | null
  memoryMaxMb?: number | null
  /** P95 (ms), même calcul que le rapport ; null tant qu'il n'est pas calculé (exécution en cours). */
  p95Ms?: number | null
  /** Raison principale du statut (« Erreurs : 2,5 % », « Annulée par … ») ; null si rien à expliquer. */
  statusReason?: string | null
  /** Seuils de réussite copiés au lancement (null = exécution antérieure aux seuils). */
  maxErrorRatePercent?: number | null
  maxP95Ms?: number | null
  /** P95 et date de l'exécution terminée précédente du même scénario. */
  previousP95Ms?: number | null
  previousStartedAt?: string | null
  /** Option « Conserver » : résultats jamais purgés. */
  keepResults?: boolean
  /** Date de suppression du détail des requêtes (le rapport figé reste) ; null = détail présent. */
  resultsPurgedAt?: string | null
}

/** Conservation du détail d'une exécution (rapport, détail). `notice` : phrase à afficher, null si rien n'a été supprimé. */
export interface BackendResultsRetention {
  keep: boolean
  resultsPurgedAt: string | null
  requestDetailsPurgedAt: string | null
  /** Rapport issu du rapport figé (détail des requêtes supprimé). */
  fromSnapshot: boolean
  notice: string | null
}

/** GET/PUT /api/settings/results-retention (Super administrateur). */
export interface BackendResultsRetentionSettings {
  resultsDays: number
  requestDetailsDays: number
}

/** Filtres de GET /api/executions/history, tous optionnels. */
export interface ExecutionHistoryFilters {
  status?: BackendExecutionStatus
  scenarioId?: string
  applicationId?: string
  dateFrom?: string
  dateTo?: string
  search?: string
  page?: number
  size?: number
  sort?: string
  /** /page seulement : exécutions terminées uniquement (page Rapports). */
  finished?: boolean
}

/** Statistiques d'une exécution ; temps en millisecondes. */
export interface BackendExecutionStatistics {
  totalRequests: number
  successfulRequests: number
  failedRequests: number
  successRate: number | null
  errorRate: number | null
  minResponseTime: number | null
  maxResponseTime: number | null
  avgResponseTime: number | null
  p50: number | null
  p75: number | null
  p90: number | null
  p95: number | null
  p99: number | null
  /** Requêtes / s sur le temps de travail réel (pauses exclues). */
  throughput: number | null
}

export interface BackendStepReportEntry {
  stepId: string
  stepName: string
  method: BackendHttpMethod
  url: string
  total: number
  success: number
  failed: number
  avgResponseTime: number | null
  minResponseTime: number | null
  maxResponseTime: number | null
  p95: number | null
  p99: number | null
  errorRate: number | null
}

export interface BackendReportErrorEntry {
  stepName: string
  method: BackendHttpMethod
  url: string
  httpStatus: number | null
  error: string | null
  timestamp: string
}

/** Rapport statistique d'une exécution (GET /api/executions/{id}/report). */
export interface BackendReportThresholds {
  maxErrorRatePercent: number
  errorRatePercent: number
  errorRateOk: boolean
  maxP95Ms: number | null
  p95Ms: number | null
  p95Ok: boolean | null
}

export interface BackendExecutionReportResponse {
  id: string
  scenarioId: string
  scenarioName: string
  applicationId: string
  applicationName: string
  status: BackendExecutionStatus
  startedAt: string
  finishedAt: string | null
  duration: number | null
  errorMessage: string | null
  virtualUsers: number
  rampUpSeconds: number
  durationSeconds: number | null
  iterations: number | null
  /** Utilisateur qui a lancé l'exécution ; `null` si inconnu. */
  triggeredByUsername: string | null
  /** Temps total passé en pause (ms) ; le débit est calculé sans ce temps. */
  pausedDurationMs: number
  statistics: BackendExecutionStatistics
  steps: BackendStepReportEntry[]
  errors: BackendReportErrorEntry[]
  /** Seuils de réussite et leur résultat ; null = exécution antérieure aux seuils. */
  thresholds?: BackendReportThresholds | null
  retention?: BackendResultsRetention | null
  /** Mode de charge et résultat du test de stress (point de rupture), gardé après la purge ; null en mode manuel. */
  loadMode?: BackendLoadMode
  stress?: BackendStressResult | null
}

export interface BackendExecutionStepResultResponse {
  stepId: string
  stepName: string
  method: BackendHttpMethod
  /** URL réellement appelée. */
  url: string
  httpStatus: number | null
  responseTime: number
  success: boolean
  error: string | null
  timestamp: string
  /** Utilisateur virtuel (0..n-1), itération (0..) et URL finale réellement appelée (null : résultats antérieurs). */
  virtualUser: number | null
  iteration: number | null
  requestUrl: string | null
}

export interface BackendExecutionDetailResponse extends BackendExecutionResponse {
  results: BackendExecutionStepResultResponse[]
  /** Détail des requêtes conservé (null : exécution antérieure, aucun détail). */
  requestDetailMode?: BackendRequestDetailMode | null
  /** Seuils de réussite : taux d'erreurs maximal (%) ; P95 maximal (ms, 0 = aucun). */
  maxErrorRatePercent?: number | null
  maxP95Ms?: number | null
  /** Plafond mémoire atteint : l'échantillon des requêtes réussies a été arrêté (échecs conservés). */
  detailSampleCapReached?: boolean
  detailedFailures?: number
  detailedSamples?: number
  retention?: BackendResultsRetention | null
  /** Débit (req/s, pauses exclues) et taux d'erreurs (%), mêmes formules que les métriques ; null : rien à mesurer. */
  throughputRps?: number | null
  errorRatePercent?: number | null
}


/** GET /api/executions/{id}/timeline : analyse dans le temps (séries alignées sur `t`, null = pas de donnée). */
export interface BackendExecutionTimeline {
  source: 'RECORDED' | 'DERIVED' | 'NONE'
  virtualUsersAvailable: boolean
  bucketSeconds: number
  durationMs: number
  t: number[]
  activeVirtualUsers: (number | null)[]
  requests: number[]
  throughput: (number | null)[]
  avgMs: (number | null)[]
  p95Ms: (number | null)[]
  errors: number[]
  pausedRatio: number[]
  steps: { key: string; name: string; method: string | null; requests: number[]; throughput: (number | null)[]; p95Ms: (number | null)[]; overallP95Ms: number | null }[]
  errorTypes: string[]
  errorPoints: { bucket: number; step: number; type: number; count: number }[]
  pauses: { fromSeconds: number; toSeconds: number }[]
  insights: { kind: 'PLATEAU' | 'NO_PLATEAU' | 'DEGRADATION' | 'ERRORS' | 'INFO'; text: string }[]
  /** Tableau « Évolution des étapes pendant le test » (absent pour une exécution sans requête). */
  stepEvolution?: BackendStepEvolution | null
  /** CPU / RAM du processus de l'application testée, alignés sur `t` ; null/absent sans monitoring. */
  resources?: BackendTimelineResources | null
  /** Début du test (epoch ms) : t = 0. */
  startEpochMs?: number
  /** Intervalle des requêtes la plus rapide / la plus lente (cartes Minimum / Maximum). */
  fastest?: BackendTimelineExtreme | null
  slowest?: BackendTimelineExtreme | null
  message: string | null
}

export interface BackendTimelineExtreme {
  bucket: number
  responseTimeMs: number
  atSeconds: number
}

/** Moyenne des mesures de chaque intervalle (null : pas de mesure dans cet intervalle). */
export interface BackendTimelineResources {
  source: BackendMonitoringType | null
  intervalSeconds: number
  memoryMetric: string | null
  message: string | null
  sampleCount: number
  cpuPercent: (number | null)[]
  memoryMb: (number | null)[]
}

export type BackendStepTrendKind ='DEGRADED' | 'WATCH' | 'STABLE' | 'INSUFFICIENT'

/** Comparaison début / fin du test par étape ; secondes en temps actif (pauses retirées). */
export interface BackendStepEvolution {
  comparable: boolean
  message: string | null
  stableSeconds: number
  startFromSeconds: number
  startToSeconds: number
  endFromSeconds: number
  endToSeconds: number
  /** Déjà triées : la plus dégradée en premier. */
  steps: BackendStepTrend[]
}

export interface BackendStepTrend {
  key: string
  name: string
  method: string | null
  trend: BackendStepTrendKind
  startP95Ms: number | null
  endP95Ms: number | null
  startRequests: number
  endRequests: number
  totalRequests: number
  deltaMs: number | null
  deltaPercent: number | null
}

/** GET /api/executions/{id}/results : une requête (ligne de résumé, toujours présente). */
export interface BackendExecutionRequestRow {
  resultId: string
  stepId: string | null
  stepName: string
  method: BackendHttpMethod | null
  virtualUser: number | null
  iteration: number | null
  timestamp: string
  httpStatus: number | null
  responseTime: number | null
  success: boolean
  error: string | null
  requestUrl: string | null
  /** FAILURE / SAMPLE si le détail complet a été conservé, sinon null. */
  detailKind: 'FAILURE' | 'SAMPLE' | null
}

export interface ExecutionResultsQuery {
  stepId?: string
  stepName?: string
  virtualUser?: number
  iteration?: number
  failedOnly?: boolean
  /** TIME (chronologique, défaut), SLOWEST (plus lentes d'abord), FASTEST (plus rapides d'abord). */
  sort?: 'TIME' | 'SLOWEST' | 'FASTEST'
  page: number
  size: number
}

/**
 * GET /api/executions/{id}/extremes?kind=MIN|MAX : les 10 requêtes les plus rapides ou les plus
 * lentes, la première étant exactement celle de la carte Minimum / Maximum.
 */
export interface BackendExecutionExtremes {
  kind: 'MIN' | 'MAX'
  cardValueMs: number | null
  measuredRequests: number
  /** Largeur de l'intervalle de l'analyse dans le temps (1 s pour un test de moins de 5 min). */
  intervalSeconds: number
  requests: {
    request: BackendExecutionRequestRow
    secondsFromStart: number
    intervalStartSeconds: number
    activeVirtualUsers: number | null
    throughput: number | null
    p95Ms: number | null
  }[]
}

/** GET /api/executions/{id}/results/{resultId}/detail : secrets masqués, corps limités à 10 Ko. */
export interface BackendExecutionRequestDetail {
  summary: BackendExecutionRequestRow
  kind: 'FAILURE' | 'SAMPLE'
  request: { method: string | null; url: string | null; headers: Record<string, string> | null; body: string | null; bodyTruncated: boolean }
  response: { status: number | null; timeMs: number | null; sizeBytes: number | null; headers: Record<string, string> | null; body: string | null; bodyTruncated: boolean } | null
  checks: {
    expectedStatus: string | null; statusOk: boolean | null; assertionText: string | null; assertionOk: boolean | null
    captureName: string | null; capturePath: string | null; captureValue: string | null; captureFound: boolean | null
  } | null
}

// ---- Metrics ----

export interface BackendMetricResponse {
  id: string
  applicationId: string | null
  scenarioId: string | null
  stepId: string
  executionId: string
  /** Millisecondes. */
  responseTime: number | null
  statusCode: number | null
  /** Requêtes/seconde de l'exécution. */
  throughput: number | null
  /** Taux d'erreurs de l'exécution (%). */
  errorRate: number | null
  timestamp: string
}

// ---- Dashboard ----

export interface BackendApplicationsSummary {
  totalApplications: number
  connectedApplications: number
  failedApplications: number
  errorApplications: number
}

export interface BackendScenariosSummary {
  totalScenarios: number
  activeScenarios: number
  draftScenarios: number
}

export interface BackendExecutionsSummary {
  totalExecutions: number
  successfulExecutions: number
  failedExecutions: number
  runningExecutions: number
  cancelledExecutions: number
  /** Nombre d'étapes exécutées. */
  totalStepsExecuted: number
  successfulSteps: number
  failedSteps: number
  /** `null` si totalStepsExecuted === 0. */
  successRate: number | null
  failureRate: number | null
}

export interface BackendPerformanceSummary {
  /** `null` si aucune métrique. */
  averageResponseTime: number | null
  averageThroughput: number | null
  averageErrorRate: number | null
}

/** Scénario du classement "Top scénarios". */
export interface BackendTopScenarioResponse {
  scenarioId: string
  scenarioName: string
  applicationName: string
  executionCount: number
  successRate: number | null
  avgResponseTime: number | null
}

/** GET /api/dashboard/overview : tableau de bord filtré par application et période ; null = non calculable. */
export interface BackendDashboardOverview {
  applicationId: string | null
  from: string | null
  to: string | null
  kpis: { p95Ms: number | null; errorRate: number | null; requests: number; executions: number; running: number }
  statusCounts: { success: number; failed: number; cancelled: number; running: number; thresholdsExceeded?: number }
  watchItems: BackendDashboardWatchItem[]
  /** Scénarios exécutés dans la période (le plus récent d'abord), pour « Évolution des performances ».
   *  Le tableau de leurs dernières exécutions est paginé : GET /api/dashboard/latest-by-scenario. */
  scenarios: BackendDashboardScenarioOption[]
  slowestSteps: { stepName: string; method: string | null; scenarioName: string; executionId: string; p95Ms: number; requests: number }[]
  running: BackendDashboardExecutionBrief[]
  recent: BackendDashboardExecutionBrief[]
  upcoming: { id: string; name: string; scenarioName: string; nextRunAt: string }[]
}

export interface BackendDashboardWatchItem {
  kind: 'APPLICATION_FAILED' | 'LAST_EXECUTION_FAILED' | 'THRESHOLDS_EXCEEDED' | 'REGRESSION' | 'MONITORING_DOWN'
  severity: 'danger' | 'warning'
  title: string
  detail: string | null
  /** Page de l'interface à ouvrir (ex. /executions/report/{id}). */
  link: string
}

/** WORSE (rouge), BETTER (vert), SAME (gris, écart faible), NONE (gris, pas de comparaison). */
export type BackendDashboardTrend = 'WORSE' | 'BETTER' | 'SAME' | 'NONE'

export interface BackendDashboardScenarioOption {
  scenarioId: string
  scenarioName: string
}

/** Ligne de GET /api/dashboard/latest-by-scenario (paginé, le plus récent d'abord). */
export interface BackendDashboardScenarioRow {
  executionId: string
  scenarioId: string
  scenarioName: string
  applicationId: string
  applicationName: string
  startedAt: string
  status: BackendExecutionStatus
  virtualUsers: number | null
  p95Ms: number | null
  errorRate: number | null
  throughputRps: number | null
  cpuMaxPercent: number | null
  previousExecutionId: string | null
  previousP95Ms: number | null
  p95ChangePercent: number | null
  trend: BackendDashboardTrend
}

export interface BackendDashboardExecutionBrief {
  id: string
  scenarioName: string
  applicationName: string
  status: BackendExecutionStatus
  startedAt: string
  duration: number | null
}

/**
 * GET /api/dashboard/scenario-trend : exécutions terminées d'un scénario, ordre chronologique.
 * Avec ?stepName=, chaque point porte aussi P95, taux d'erreurs et requêtes de cette étape.
 */
export interface BackendScenarioTrend {
  scenarioId: string
  scenarioName: string
  steps?: { name: string; method: string | null }[]
  stepName?: string | null
  points: { executionId: string; startedAt: string; status: BackendExecutionStatus; p95Ms: number | null; errorRate: number | null;
    throughputRps: number | null; virtualUsers: number | null; stepP95Ms?: number | null; stepErrorRate?: number | null;
    stepRequests?: number | null }[]
}

/** GET /api/executions/compare?ids= : 2 à 4 exécutions terminées, valeurs identiques à leurs rapports. */
export interface BackendExecutionComparison {
  sameScenario: boolean
  warning: string | null
  executions: BackendComparisonColumn[]
  /** P95 de chaque étape (même nom et méthode) dans chaque exécution, null si absente. */
  steps: { name: string; method: string | null; p95Ms: (number | null)[] }[]
}

export interface BackendComparisonColumn {
  executionId: string
  scenarioId: string
  scenarioName: string
  applicationName: string
  startedAt: string
  status: BackendExecutionStatus
  virtualUsers: number | null
  rampUpSeconds: number | null
  durationSeconds: number | null
  iterations: number | null
  activeDurationMs: number | null
  requests: number
  throughput: number | null
  p95Ms: number | null
  p99Ms: number | null
  errorRate: number | null
  cpuMaxPercent: number | null
  memoryMaxMb: number | null
  /** Mode de charge ; test de stress : débit offert au point de rupture (null sans rupture). */
  loadMode?: BackendLoadMode
  breakpointOfferedRps?: number | null
}

export interface BackendDashboardResponse {
  applications: BackendApplicationsSummary
  scenarios: BackendScenariosSummary
  executions: BackendExecutionsSummary
  performance: BackendPerformanceSummary
  /** Période appliquée (`null` / `null` = tout l'historique). */
  rangeFrom: string | null
  rangeTo: string | null
  topScenarios: BackendTopScenarioResponse[]
}

export interface BackendApplicationIdentity {
  id: string
  name: string
  status: BackendApplicationStatus | null
}

export interface BackendApplicationDashboardResponse {
  application: BackendApplicationIdentity
  scenarios: BackendScenariosSummary
  executions: BackendExecutionsSummary
  performance: BackendPerformanceSummary
}

// ---- Utilisateurs ----

/** Rôles applicatifs. */
export type BackendAppRole = 'SUPER_ADMIN' | 'PERFORMANCE_ENGINEER' | 'VIEWER'

export interface BackendUserSummaryResponse {
  id: string
  username: string
  email: string | null
  enabled: boolean
  /** Rôle applicatif de l'utilisateur ; `null` si aucun. */
  role: BackendAppRole | null
  createdAt: string | null
  firstName?: string | null
  lastName?: string | null
  /** « Prénom Nom » si renseignés dans Keycloak, sinon le username. */
  displayName?: string | null
  /** Dernière connexion (événements LOGIN Keycloak) ; null si aucune enregistrée. */
  lastLoginAt?: string | null
  /** Exécutions lancées par cet utilisateur dans LoadPilot. */
  executionCount?: number
}

export interface BackendUpdateUserRoleRequest {
  role: BackendAppRole
}

/** POST /api/users. Avec `password` : mot de passe défini tout de suite
 * (`temporaryPassword` = changement imposé à la 1re connexion), e-mail facultatif.
 * Sans : un lien est envoyé par e-mail pour le choisir (e-mail obligatoire). */
export interface BackendCreateUserRequest {
  username: string
  email?: string | null
  firstName?: string | null
  lastName?: string | null
  role: BackendAppRole
  password?: string | null
  temporaryPassword?: boolean
}

export interface BackendUserCreatedResponse {
  user: BackendUserSummaryResponse
  /** Lien de choix du mot de passe effectivement envoyé par e-mail. */
  invitationSent: boolean
  /** Mot de passe défini par l'administrateur (jamais renvoyé). */
  passwordSet?: boolean
}

/** GET /api/users/password-policy : règles du realm (0 = pas d'exigence). */
export interface BackendPasswordPolicy {
  minLength: number
  maxLength: number
  minUpperCase: number
  minLowerCase: number
  minDigits: number
  minSpecialChars: number
  notUsername: boolean
  notEmail: boolean
}

export interface BackendUpdateUserStatusRequest {
  enabled: boolean
}

// ---- Audit ----

/** Entree du journal d'audit prete a afficher (libelles francais, phrase, objet, avant -> apres). */
export interface BackendAuditLogResponse {
  id: string
  date: string
  /** USER (utilisateur connecte) ou SYSTEM (action automatique). */
  actorType: 'USER' | 'SYSTEM'
  userId: string | null
  username: string | null
  /** Origine d'une action automatique ou importee, ex. « Planification : Test nocturne ». */
  actorSource: string | null
  action: BackendAuditAction
  actionLabel: string
  module: BackendAuditModule
  category: BackendAuditCategory
  categoryLabel: string
  result: BackendAuditResult
  resultLabel: string
  /** Phrase lisible, ex. « Création de l’application « Banking » ». */
  summary: string
  /** Detail en francais (ou texte d'origine d'une ancienne ligne). */
  detail: string | null
  targetType: 'APPLICATION' | 'SCENARIO' | 'EXECUTION' | 'SCHEDULE' | 'USER' | 'SETTINGS' | null
  targetId: string | null
  targetLabel: string | null
  /** L'objet existe encore (un lien peut etre propose). */
  targetExists: boolean
  changes: { label: string; before: string | null; after: string | null }[]
  ip: string | null
  /** « localhost » pour la machine elle-meme. */
  ipLabel: string | null
  userAgent: string | null
  /** Ligne anterieure au journal enrichi (texte d'origine, sans objet ni avant / apres). */
  legacy: boolean
  description: string | null
}

/** Reglages du journal d'audit (Parametres). */
export interface BackendAuditSettings {
  logReads: boolean
  retentionDays: number
}

export interface BackendPagedResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface BackendAuditStatsResponse {
  totalActions: number
  successfulActions: number
  failedActions: number
  actionsByModule: Record<string, number>
  actionsByAction: Record<string, number>
}

// ---- Notifications ----

export interface BackendNotificationResponse {
  id: string
  type: BackendNotificationType
  title: string
  message: string | null
  relatedExecutionId: string | null
  relatedScheduleId: string | null
  read: boolean
  createdAt: string
  readAt: string | null
}

// ---- Planifications ----

export interface BackendScheduledExecutionRequest {
  scenarioId: string
  name: string
  scheduleType: BackendScheduleType
  /** Requis si scheduleType === 'RECURRING_CRON'. */
  cronExpression?: string | null
  /** Requis si scheduleType === 'ONE_TIME' : date ISO 8601 dans le futur. */
  runAt?: string | null
  /** Fuseau horaire IANA (ex. "Africa/Casablanca"). */
  timezone: string
  /** Récurrente seulement : aucune occurrence après cet instant (ISO 8601). */
  endAt?: string | null
  notifyMode?: BackendScheduleNotifyMode
}

export interface BackendScheduledExecutionResponse {
  id: string
  scenarioId: string
  scenarioName: string
  applicationId: string
  applicationName: string
  name: string
  scheduleType: BackendScheduleType
  cronExpression: string | null
  runAt: string | null
  timezone: string
  /** `null` = plus aucun lancement automatique. */
  nextRunAt: string | null
  enabled: boolean
  createdByUsername: string | null
  createdAt: string
  updatedAt: string
  lastTriggeredAt: string | null
  lastExecutionId: string | null
  /** Raison du dernier échec de déclenchement ; `null` si le dernier a réussi. */
  lastTriggerError: string | null
  endAt: string | null
  notifyMode: BackendScheduleNotifyMode
  state: BackendScheduleState
  /** Raison de l'état (au survol) ; null pour une planification active. */
  stateReason: string | null
  /** Dernière exécution lancée ; p95ChangePercent = écart avec l'exécution précédente de la planification. */
  lastRun: BackendScheduleLastRun | null
  /** Dernier lancement non fait (ignoré, manqué), seulement s'il est plus récent que la dernière exécution. */
  lastEvent: { outcome: BackendScheduledRunOutcome; plannedAt: string; reason: string } | null
}

export interface BackendScheduleLastRun {
  executionId: string
  status: BackendExecutionStatus
  startedAt: string | null
  p95Ms: number | null
  previousP95Ms: number | null
  p95ChangePercent: number | null
  errorRatePercent: number | null
}

export interface BackendSchedulePreviewRequest {
  scheduleType: BackendScheduleType
  cronExpression?: string | null
  runAt?: string | null
  timezone: string
  endAt?: string | null
}

export interface BackendSchedulePreviewResponse {
  nextRuns: string[]
  cronExpression: string | null
}

export interface BackendScheduleHistoryItem {
  kind: 'EXECUTION' | 'EVENT'
  at: string
  executionId: string | null
  status: BackendExecutionStatus | null
  p95Ms: number | null
  errorRatePercent: number | null
  throughputRps: number | null
  totalRequests: number | null
  durationMs: number | null
  outcome: BackendScheduledRunOutcome | null
  reason: string | null
}

export interface BackendScheduleHistoryResponse {
  scheduleId: string
  items: BackendScheduleHistoryItem[]
  truncated: boolean
}

// ---- Préférences de notification ----

export interface BackendNotificationPreferenceResponse {
  type: BackendNotificationType
  /** Dans l'application (actif par défaut). */
  enabled: boolean
  /** Par e-mail (désactivé par défaut). */
  emailEnabled?: boolean
}

/** GET /api/notification-preferences/channels : e-mail possible (serveur configuré et adresse connue). */
export interface BackendNotificationChannels {
  inApp: boolean
  email: boolean
  emailServer: boolean
  emailAddress: string
}

export interface BackendNotificationPreferenceUpdateRequest {
  enabled: boolean
}

// ---- Limites du serveur (lecture seule, Super administrateur) ----

export interface BackendSystemConfigResponse {
  maxVirtualUsersPerExecution: number
  maxGlobalVirtualUsers: number
  maxConcurrentExecutions: number
  executionTimeoutSeconds: number
  availabilityTimeoutSeconds: number
  schedulerPollIntervalMs: number
}

// ---- Format des erreurs ----

export interface BackendErrorResponse {
  timestamp: string
  status: number
  error: string
  message: string
  path: string
}

// ---- Suppression ----

/** Impact d'une suppression / d'un archivage, affiché avant confirmation. */
export interface BackendDeletionImpact {
  applications: number
  scenarios: number
  steps: number
  executions: number
  scheduledExecutions: number
  runningExecutions: number
  hasRunningExecution: boolean
}

/** GET /api/executions/page : lignes de la page + compteurs par statut avec les mêmes filtres. */
export interface BackendExecutionPageResponse {
  content: BackendExecutionHistoryResponse[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  counts: { total: number; queued: number; running: number; paused: number; success: number; failed: number; cancelled: number; thresholdsExceeded?: number }
}

/** POST /api/executions/deletion-impact : ce que supprime une suppression d'exécutions. */
export interface BackendExecutionDeletionImpact {
  executions: number
  results: number
  metrics: number
  /** En attente, en cours ou en pause : jamais supprimées. */
  active: number
}

export interface BackendBulkActionResponse {
  results: { id: string; name: string | null; success: boolean; message: string }[]
  succeeded: number
  failed: number
}

/** Paramètres de GET /api/scenarios paginé (status : ACTIVE, INACTIVE ou DRAFT). */
export interface ScenarioPageQuery {
  page: number
  size: number
  sort?: string
  search?: string
  status?: string
  archived?: boolean
}

/** Paramètres de GET /api/applications paginé. */
export interface ApplicationPageQuery {
  page: number
  size: number
  sort?: string
  search?: string
  status?: string
  archived?: boolean
}
