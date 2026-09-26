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
export type BackendScenarioStatus = 'ACTIVE' | 'INACTIVE'
export type BackendStepStatus = 'ACTIVE' | 'INACTIVE'
export type BackendExecutionStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
export type BackendHttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
export type BackendAuditAction =
  | 'CREATE' | 'READ' | 'UPDATE' | 'DELETE' | 'TEST' | 'EXECUTE' | 'RETRY' | 'CANCEL' | 'LOGIN' | 'LOGOUT'
  // P1-B — cycle de vie d'une ScheduledExecution (module SCHEDULING).
  | 'ENABLE' | 'DISABLE' | 'TRIGGER'
  // P1-C — traçabilité de la consultation de l'historique/des rapports.
  | 'VIEW_HISTORY' | 'EXPORT_REPORT' | 'EXPORT_CSV'
export type BackendAuditModule =
  | 'AUTH' | 'USER' | 'APPLICATION' | 'SCENARIO' | 'STEP' | 'EXECUTION' | 'METRIC' | 'DASHBOARD' | 'ADMIN'
  | 'SCHEDULING'
export type BackendAuditResult = 'SUCCESS' | 'FAILURE'

// P1-B — voir enums.NotificationType/ScheduleType côté backend.
export type BackendNotificationType =
  | 'EXECUTION_SUCCESS' | 'EXECUTION_FAILED' | 'EXECUTION_CANCELLED' | 'SCHEDULE_TRIGGERED' | 'SCHEDULE_FAILED'
export type BackendScheduleType = 'ONE_TIME' | 'RECURRING_CRON'

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
}

/** P1-D — PATCH /api/profile : seul champ modifiable (username/name/email
 * restent la propriété de Keycloak, jamais éditables ici). */
export interface BackendProfileUpdateRequest {
  timezone: string
}

// ---- Applications ----

export interface BackendApplicationRequest {
  name: string
  description: string | null
  url: string
}

export interface BackendApplicationResponse {
  id: string
  name: string
  description: string | null
  url: string
  /** `null` tant qu'aucun test de disponibilité réel n'a été lancé — voir
   * BackendApplicationStatus. Ne pas confondre avec Application.status côté
   * frontend actuel (`Actif`/`Inactif`), qui est un simple bascule
   * activé/désactivé et n'a pas d'équivalent direct côté backend. */
  status: BackendApplicationStatus | null
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
}

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
}

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
}

/** P1-A — GET /api/executions/history (voir ExecutionController#history) :
 * vue allegee pour la page Historique, paginee cote backend.
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
  /** P1-C — écart-type de population des temps de réponse (voir
   * PercentileCalculator#stdDev côté backend) — `null` si population vide. */
  stdDevResponseTime: number | null
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
  /** P1-C — voir BackendExecutionStatistics.stdDevResponseTime. */
  stdDevResponseTime: number | null
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
  statistics: BackendExecutionStatistics
  steps: BackendStepReportEntry[]
  errors: BackendReportErrorEntry[]
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
}

export interface BackendExecutionDetailResponse extends BackendExecutionResponse {
  results: BackendExecutionStepResultResponse[]
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
  inactiveScenarios: number
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
}

export interface BackendUpdateUserRoleRequest {
  role: BackendAppRole
}

export interface BackendUpdateUserStatusRequest {
  enabled: boolean
}

// ---- Audit ----

export interface BackendAuditLogResponse {
  id: string
  userId: string | null
  username: string | null
  action: BackendAuditAction
  module: BackendAuditModule
  date: string
  ip: string | null
  result: BackendAuditResult
  description: string | null
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
}

// ---- Notification Preferences (P1-D — GET/PATCH /api/notification-preferences) ----

export interface BackendNotificationPreferenceResponse {
  type: BackendNotificationType
  enabled: boolean
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
