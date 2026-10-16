// ============================================================
// Phase 15 — Contrats REST du backend Spring Boot LoadPilot, tels
// qu'implémentés réellement (Phases 4 à 14), recopiés ici à titre de
// RÉFÉRENCE pour la future migration.
//
// Ce fichier n'est importé par AUCUNE page ni service actuel : il ne
// remplace PAS `types/index.ts` (qui reflète JSON Server et reste la seule
// source de vérité tant que la migration n'a pas eu lieu) et ne change
// donc AUCUN comportement existant. Son seul but est d'éviter de
// re-déterminer ces formes depuis zéro lors de la connexion réelle au
// backend (Phase 16+) : les noms de champs ci-dessous sont ceux des DTO
// Java (`dto/response/*.java`), pas une supposition.
//
// Toutes les entités backend utilisent un UUID sérialisé en `string` (voir
// EntityId dans types/index.ts, déjà cohérent — aucune migration de type
// nécessaire sur les IDs).
// ============================================================

// ---- Enums réels du backend (voir backend/src/main/java/.../enums) ----

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

// P1-B — voir enums.NotificationType/ScheduleType côté backend.
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
  /** P1-D — préférence RÉELLE persistée (voir AppUser.timezone côté backend),
   * jamais issue du Jwt. `null` si l'utilisateur n'a jamais choisi de fuseau. */
  timezone: string | null
  /** Rôle applicatif (jamais offline_access, uma_authorization, default-roles-…) et son libellé français. */
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

/** PUT /api/profile/identity (Super administrateur) : jamais le nom d'utilisateur, le rôle ni l'état du compte. */
export interface BackendProfileIdentityRequest {
  firstName: string
  lastName: string
  email: string
}

/** PATCH /api/profile : préférences facultatives (null = inchangée). username/name/email restent
 * la propriété du système d'authentification, jamais éditables ici. */
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

/** GET/PUT /api/settings/thresholds : seuils proposés aux nouveaux scénarios et seuil de régression. */
export interface BackendThresholdSettings {
  maxErrorRatePercent: number
  /** 0 = aucun seuil de P95 proposé. */
  maxP95Ms: number
  regressionPercent: number
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

/** Authentification appliquée automatiquement à toutes les requêtes vers
 * l'Application (voir enums.ApplicationAuthType côté backend). */
export type BackendApplicationAuthType = 'NONE' | 'BEARER_TOKEN' | 'API_KEY' | 'BASIC' | 'LOGIN_TOKEN'
export type BackendApiKeyLocation = 'HEADER' | 'QUERY'

/** Secrets (token, apiKeyValue, password, loginBody) : vides en mise à jour
 * = valeur déjà enregistrée conservée (jamais renvoyée par le backend). */
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
  tokenHeaderName?: string | null
  tokenPrefix?: string | null
  /** BEARER_TOKEN / API_KEY / BASIC : endpoint protégé où le test de
   * connectivité vérifie réellement les identifiants (obligatoire). */
  verificationPath?: string | null
}

/** Vue SANS secret : seules des indications "configuré" sont exposées. */
export interface BackendApplicationAuthResponse {
  type: BackendApplicationAuthType
  apiKeyName: string | null
  apiKeyLocation: BackendApiKeyLocation | null
  username: string | null
  loginPath: string | null
  tokenJsonPath: string | null
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
}

export interface BackendMonitoringResponse {
  type: BackendMonitoringType
  url: string | null
  authType: BackendMonitoringAuthType
  headerName: string | null
  secretConfigured: boolean
  intervalSeconds: number
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
  /** Monitoring CPU / RAM (absent : serveur antérieur, équivaut à NONE). */
  monitoring?: BackendMonitoringResponse
  /** Archivage logique : masquée des listes par défaut, restaurable. */
  archived: boolean
  archivedAt: string | null
  /** `null` tant qu'aucun test de disponibilité réel n'a été lancé — voir
   * BackendApplicationStatus. Ne pas confondre avec Application.status côté
   * frontend actuel (`Actif`/`Inactif`), qui est un simple bascule
   * activé/désactivé et n'a pas d'équivalent direct côté backend. */
  status: BackendApplicationStatus | null
  /** Message lisible du dernier test (jamais de secret) ; null si aucun test
   * valide (jamais testée, ou modifiée depuis le dernier test). */
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
  /** P0-A — moteur de charge reel (voir HttpClientExecutionEngine). Tous
   * nullable : le backend applique ses propres defauts reels cote service
   * (virtualUsers=1, rampUpSeconds=0, thinkTimeMs=0) si null — jamais
   * fabrique cote frontend. */
  virtualUsers?: number | null
  rampUpSeconds?: number | null
  durationSeconds?: number | null
  iterations?: number | null
  thinkTimeMs?: number | null
  /** P1-Q Etape B — donnees CSV brutes optionnelles (premiere ligne = noms
   * de colonnes/variables), une ligne distribuee par utilisateur virtuel
   * (voir Scenario.csvData/CsvDataSource cote backend). null/absent =
   * aucune variable de donnees, comportement historique inchange. */
  csvData?: string | null
  /** Master prompt final (Lot A) — debit cible optionnel (requetes/s,
   * approximatif, partage entre tous les VUs — voir Scenario.targetRps/
   * PacingGate cote backend). null/absent = aucun pacing, comportement
   * historique inchange. Ne remplace jamais virtualUsers/rampUpSeconds/
   * thinkTimeMs. */
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
}

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
  /** P0-A — valeurs REELLEMENT appliquees par le moteur de charge a chaque
   * Execution de ce Scenario (voir ExecutionTransactionHelper.prepareAndStart,
   * qui les copie telles quelles sur l'Execution au lancement). */
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
  /** P1-Q Etape B — toutes optionnelles (voir StepRequest.java) : absentes/
   * null = comportement historique inchange (voir HttpClientExecutionEngine). */
  thinkTimeMs?: number | null
  timeoutSeconds?: number | null
  followRedirects?: boolean | null
  assertionBodyContains?: string | null
  /** Master prompt final (Lot B) — capture de variable dynamique depuis la
   * reponse de CETTE etape (voir Step.captureVariableName/captureJsonPath,
   * JsonPathExtractor cote backend). N'a d'effet que si les DEUX champs
   * sont renseignes ; disponible aux etapes suivantes du MEME utilisateur
   * virtuel uniquement (jamais partage entre VUs). */
  captureVariableName?: string | null
  captureJsonPath?: string | null
  /** Variables de chemin (:nom / {nom}) : objet JSON {"nom": "valeur ou ${variable}"}. */
  pathParams?: string | null
  /** Paramètres de requête : tableau JSON [{"key","value","enabled"}] (l'URL finale est construite par le backend). */
  queryParams?: string | null
}

// ------------------------------------------------------------
// Test détaillé (TestRunController / TestRunResponse.java) : 1 utilisateur
// virtuel, 1 passage, aucune Execution créée. Secrets déjà masqués ("••••••").
// ------------------------------------------------------------

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
  /** TIMEOUT, CONNECTION_REFUSED, DNS, TLS, NETWORK, INVALID_REQUEST, UNRESOLVED_VARIABLE,
   * HTTP_STATUS, ASSERTION, AUTH_FAILED, GLOBAL_TIMEOUT, PREVIOUS_FAILED, INTERRUPTED */
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
  /** Phase 19 — ajouté après lecture réelle de StepMapper/StepResponse.java :
   * exposé par le backend en plus de scenarioId, par confort d'affichage
   * (jamais l'entité Scenario complète), comme applicationName sur
   * BackendScenarioResponse. Absent de la version initiale de ce fichier
   * (Phase 15, écrite avant lecture du code réel) — ajout minimal, pas une
   * supposition. */
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
  /** Variables de chemin (objet JSON {"nom": "valeur"}) et paramètres de requête (tableau JSON). */
  pathParams: string | null
  queryParams: string | null
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
  /** P0-A — copie figee des parametres de charge du Scenario au moment du
   * lancement (voir Execution.java) : reste fidele meme si le Scenario est
   * reconfigure plus tard. */
  virtualUsers: number
  rampUpSeconds: number
  durationSeconds: number | null
  iterations: number | null
  totalSteps: number
  successfulSteps: number
  failedSteps: number
  duration: number | null
  errorMessage: string | null
}

/** P0-A — GET /api/executions/{id}/status (voir ExecutionController) :
 * reponse legere dediee au polling frequent pendant une execution encore
 * QUEUED/RUNNING. `progressPercent` est `null` tant qu'il n'est pas
 * reellement calculable (voir ExecutionServiceImpl.computeProgressPercent —
 * jamais fabrique en mode iterations/1-passe sans durationSeconds). */
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
}

/**
 * CPU (en % d'un cœur : 100 % = un cœur entièrement occupé) et RAM (Mo) du PROCESSUS de
 * l'application testée — ni la machine entière, ni LoadPilot.
 */
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

/** P1-A — GET /api/executions/history (voir ExecutionController#history) :
 * vue allegee d'une execution pour les listes (page Executions via /page, page Rapports via
 * /history), paginee cote backend.
 *
 * ABSENCE DOCUMENTEE : aucun champ "createdBy"/"launchedBy" — Execution n'a
 * aucune colonne referencant un utilisateur (voir ExecutionHistoryResponse
 * cote backend pour la justification complete). Ne jamais l'inventer ici. */
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
  /** P1-C — utilisateur ayant réellement lancé cette exécution (voir P1-B,
   * Execution.triggeredBy) — `null` pour toute exécution antérieure à P1-B
   * ou dont l'origine n'a pu être associée à personne, jamais deviné. */
  triggeredByUsername: string | null
  /** P1-C — requêtes réellement exécutées / durée (s), même formule que
   * PerformanceStatisticsService — `null` si non calculable. */
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

/** P1-A — filtres reels de GET /api/executions/history, tous optionnels. */
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

/** P1-A — statistiques REELLEMENT calculees (voir
 * PerformanceStatisticsService cote backend) : percentiles calcules a la
 * demande depuis ExecutionStepResult (le backend ne les calculait nulle
 * part avant P1-A — jamais une valeur approximative). Toute valeur est
 * `null` quand non calculable (population vide), jamais fabriquee. */
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

/** P1-A — GET /api/executions/{id}/report. Distinct de
 * BackendExecutionDetailResponse (vue brute, deja utilisee par
 * ExecutionDetail.tsx) : celui-ci est la vue agregee/statistique dediee au
 * reporting (ExecutionReport.tsx).
 *
 * ABSENCE DOCUMENTEE : pas de "thinkTimeMs" — jamais fige sur Execution en
 * P0-A (voir ExecutionReportResponse cote backend), jamais reconstitue ici
 * depuis le Scenario courant (violerait l'immutabilite des parametres
 * historiques d'une execution deja terminee). */
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
  /** P1-C — voir BackendExecutionHistoryResponse.triggeredByUsername. */
  triggeredByUsername: string | null
  /** Temps total passé en pause (ms) ; le débit est calculé sans ce temps. */
  pausedDurationMs: number
  statistics: BackendExecutionStatistics
  steps: BackendStepReportEntry[]
  errors: BackendReportErrorEntry[]
  /** Seuils de réussite et leur résultat ; null = exécution antérieure aux seuils. */
  thresholds?: BackendReportThresholds | null
  retention?: BackendResultsRetention | null
}

export interface BackendExecutionStepResultResponse {
  stepId: string
  stepName: string
  method: BackendHttpMethod
  /** URL déjà résolue (relative -> absolue via Application.url), jamais l'URL brute du Step. */
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
  /** Requêtes/seconde — valeur AU NIVEAU DE L'EXÉCUTION, pas mesurée par step. */
  throughput: number | null
  /** Pourcentage (0-100), basé sur les steps réellement exécutés. */
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
  /** Steps RÉELLEMENT exécutés (pas la somme des Steps configurés — voir
   * politique stop-on-failure du moteur d'exécution backend). */
  totalStepsExecuted: number
  successfulSteps: number
  failedSteps: number
  /** `null` si totalStepsExecuted === 0 (jamais 0/100 inventé). */
  successRate: number | null
  failureRate: number | null
}

export interface BackendPerformanceSummary {
  /** `null` si aucune Metric n'existe pour le périmètre demandé. */
  averageResponseTime: number | null
  averageThroughput: number | null
  averageErrorRate: number | null
}

/** P1-C — un scénario du widget "Top scénarios" (voir DashboardServiceImpl#buildTopScenarios). */
export interface BackendTopScenarioResponse {
  scenarioId: string
  scenarioName: string
  applicationName: string
  executionCount: number
  successRate: number | null
  avgResponseTime: number | null
}

/**
 * GET /api/dashboard/overview?applicationId=&from=&to= : tout le Dashboard, filtré par application
 * et par période (exécutions lancées dans la période). null = non calculable, jamais inventé.
 */
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
}

export interface BackendDashboardResponse {
  applications: BackendApplicationsSummary
  scenarios: BackendScenariosSummary
  executions: BackendExecutionsSummary
  performance: BackendPerformanceSummary
  /** P1-C — plage réellement appliquée (`null`/`null` = tout l'historique,
   * comportement identique à avant P1-C). "executions"/"performance"/
   * "topScenarios" sont filtrés par cette plage ; "applications"/
   * "scenarios" restent toujours all-time (voir DashboardResponse côté backend). */
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

// ---- Users (Phase 25 — Keycloak reel, aucun stockage local) ----

/** Les 3 seuls roles applicatifs reels (voir enums.AppRole cote backend,
 * provisionnes dans Keycloak en Phase 16.5). */
export type BackendAppRole = 'SUPER_ADMIN' | 'PERFORMANCE_ENGINEER' | 'VIEWER'

export interface BackendUserSummaryResponse {
  id: string
  username: string
  email: string | null
  enabled: boolean
  /** Le seul des 3 BackendAppRole reellement assigne cote Keycloak ;
   * `null` si aucun des 3 (jamais une valeur inventee par defaut). */
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

// ---- Notifications (P1-B — GET /api/notifications, réelles, personnelles) ----

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

// ---- Scheduled Executions (P1-B — GET/POST /api/scheduled-executions) ----

export interface BackendScheduledExecutionRequest {
  scenarioId: string
  name: string
  scheduleType: BackendScheduleType
  /** Requis (et uniquement pertinent) si scheduleType === 'RECURRING_CRON'. */
  cronExpression?: string | null
  /** Requis (et uniquement pertinent) si scheduleType === 'ONE_TIME' — ISO 8601, doit être dans le futur. */
  runAt?: string | null
  /** Identifiant de fuseau horaire IANA (ex: "Africa/Casablanca", "UTC") — toujours obligatoire. */
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
  /** `null` = ne se déclenchera plus jamais automatiquement (ONE_TIME déjà déclenchée, ou désactivée). */
  nextRunAt: string | null
  enabled: boolean
  createdByUsername: string | null
  createdAt: string
  updatedAt: string
  lastTriggeredAt: string | null
  lastExecutionId: string | null
  /** Raison du dernier ÉCHEC DE DÉCLENCHEMENT (avant même la création d'une Execution) — `null` si le dernier déclenchement a réussi ou n'a jamais eu lieu. */
  lastTriggerError: string | null
  endAt: string | null
  notifyMode: BackendScheduleNotifyMode
  state: BackendScheduleState
  /** Raison de l'état (au survol), en français ; 
ull pour une planification active. */
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

// ---- Notification Preferences (P1-D — GET/PATCH /api/notification-preferences) ----

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

// ---- System Config (P1-D — GET /api/system/config, lecture seule, SUPER_ADMIN) ----

export interface BackendSystemConfigResponse {
  maxVirtualUsersPerExecution: number
  maxGlobalVirtualUsers: number
  maxConcurrentExecutions: number
  executionTimeoutSeconds: number
  availabilityTimeoutSeconds: number
  schedulerPollIntervalMs: number
}

// ---- Format d'erreur uniforme (voir GlobalExceptionHandler) ----

export interface BackendErrorResponse {
  timestamp: string
  status: number
  error: string
  message: string
  path: string
}

// ---- Suppression cohérente (migration 017) ----

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

/** Résultat d'une action groupée, élément par élément (succès partiels). */
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
