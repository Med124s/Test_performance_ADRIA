// ============================================================
// Types du domaine PERFTEST — reflètent exactement la forme des
// ressources exposées par JSON Server (db.json).
// ============================================================

export type EntityId = string

export interface Application {
  id: EntityId
  name: string
  url: string
  type: 'Web' | 'API REST' | 'SOAP' | 'Mobile'
  authMethod: 'Aucune' | 'Basic' | 'Bearer Token' | 'API Key' | 'OAuth2'
  status: 'Actif' | 'Inactif'
  icon: string
  color: 'blue' | 'green' | 'purple' | 'orange'
  createdAt: string
  // Identifiants d'authentification (optionnels, dépendent de authMethod) —
  // stockés tels quels dans JSON Server pour cette maquette.
  authToken?: string
  authUsername?: string
  authPassword?: string
  authClientId?: string
  authClientSecret?: string
  /** URL d'un service de monitoring (ex. Local Test Server local, agent
   * Spring Boot demain — voir services/api/localMonitoring.ts) rapportant
   * le vrai CPU/RAM de la machine qui héberge cette application. Distincte
   * de `url` (l'application testée elle-même) : deux adresses différentes,
   * un même serveur physique. Absente : pas de monitoring branché, aucune
   * valeur CPU/RAM n'est inventée pour autant. */
  monitoringUrl?: string
}

/** Statut de connexion affiché dans la colonne "Statut" — représente
 * UNIQUEMENT l'accessibilité réelle de l'application cible (le serveur a-t-il
 * répondu à au moins une requête récente ?), jamais la réussite/échec d'un
 * test métier (assertions, codes 4xx/5xx applicatifs) — ça, c'est le rôle du
 * statut d'Exécution, une information différente. Dérivé de la dernière
 * exécution réelle (calculé côté frontend, jamais stocké en dur). */
export type ApplicationConnectionStatus = 'Connectée' | 'Non connectée'

export interface ScenarioVariable {
  id: number
  name: string
  value: string
}

export interface ScenarioSchedule {
  executionType: 'immediate' | 'scheduled' | 'recurring'
  scheduledDate?: string
  scheduledTime?: string
  recurrence?: string
}

export interface Scenario {
  id: EntityId
  name: string
  applicationId: EntityId
  status: 'Actif' | 'Inactif'
  description?: string
  createdAt: string
  createdBy: string
  /** Champs du wizard "Utilisateurs Virtuels & Données de Test" et
   * "Planification" (étapes 3 et 4) — optionnels pour rester compatibles
   * avec les scénarios enregistrés avant l'existence de ces écrans. Servent
   * de valeur par défaut à toute exécution de ce scénario (voir
   * useScenarioLauncher.selectScenario), jamais modifiés par un override
   * ponctuel à l'exécution. */
  virtualUsers?: number
  rampUpSeconds?: number
  userProfile?: string
  dataSource?: 'manual' | 'csv'
  csvFileName?: string
  testVariables?: ScenarioVariable[]
  schedule?: ScenarioSchedule
}

export interface StepHeader {
  id: number
  key: string
  value: string
  enabled: boolean
}

export interface StepAssertion {
  id: number
  type: string
  property: string
  operator: string
  targetValue: string
}

export interface Step {
  id: EntityId
  scenarioId: EntityId
  order: number
  name: string
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS' | 'TRACE'
  url: string
  description?: string
  headers?: StepHeader[]
  bodyJson?: string
  assertions?: StepAssertion[]
  timeoutMs?: number
  /** Étape incluse dans l'exécution ? `false` = aucune requête envoyée, le
   * StepResult correspondant est marqué 'skipped'. Absent = active (valeur
   * par défaut, compatible avec les étapes existantes). */
  active?: boolean
  /** Comportement réel de fetch() face à une redirection HTTP. `false` =
   * `redirect: 'error'` (la requête échoue si le serveur redirige) au lieu
   * de `redirect: 'follow'` (suivi automatique, comportement par défaut). */
  followRedirects?: boolean
  /** Think Time réel (ms) : délai attendu avant l'envoi de la requête. */
  pauseBeforeMs?: number
  /** Pacing réel (ms) : délai attendu après la réponse avant l'étape suivante. */
  pacingAfterMs?: number
}

/** Statut d'exécution d'une étape individuelle au sein d'une exécution. */
export type StepRunStatus = 'success' | 'error' | 'pending' | 'running' | 'skipped'

export interface StepResult {
  stepId: EntityId
  status: StepRunStatus
  httpStatus?: number
  responseTimeMs?: number
  request?: { method: string; url: string; body?: string }
  response?: { statusText: string; body?: string; headers?: Record<string, string> }
  error?: string
  /** Index (0-based) de l'utilisateur virtuel réel ayant produit ce résultat
   * — plusieurs VUs rejouent le même stepId au sein d'une même Execution. */
  vu?: number
  /** Relevé CPU/RAM réel du serveur monitoré, capturé juste après cette
   * requête précise (voir useScenarioLauncher) — absent si aucun serveur
   * avec monitoringUrl n'est lié à l'application testée, ou si l'appel a
   * échoué (jamais une valeur inventée). */
  serverMetrics?: { cpu: number; ram: number; capturedAt: string } | null
}

/** Statut global d'une exécution (cohérent avec les résultats réels des
 * étapes — voir computeExecutionStatus dans services/api/executions.ts). */
export type ExecutionStatus = 'Réussie' | 'Avec erreurs' | 'Échouée' | 'En cours' | 'Suspendue' | 'Annulée'

export interface Execution {
  id: EntityId
  scenarioId: EntityId
  applicationId: EntityId
  status: ExecutionStatus
  users: string
  startedAt: string
  duration: string
  stepResults: StepResult[]
  errors: string[]
  /** Snapshots figés des métriques de TOUS les serveurs liés à l'application
   * testée au moment du lancement (voir serverSnapshotApi) — plusieurs
   * serveurs monitorés (ex. Application + Base de données) sont possibles,
   * un tableau vide signifie "aucun serveur lié". */
  serverSnapshots?: ServerSnapshot[]
  /** @deprecated Ancien champ (un seul serveur) — conservé en lecture pour
   * les exécutions créées avant le support multi-serveurs ; ne plus écrire. */
  serverSnapshot?: ServerSnapshot | null
}

export interface ApiListResult<T> {
  data: T[]
  loading: boolean
  error: string | null
  refetch: () => Promise<void>
}

/** Rôle applicatif réel (JSON Server) — ses permissions sont gérées ici,
 * mais pas encore appliquées au contrôle d'accès (voir AuthContext.canEdit,
 * qui reste la seule source de vérité pour les droits d'édition à ce stade). */
export interface Role {
  id: EntityId
  name: string
  description?: string
  permissions: string[]
  createdAt: string
  updatedAt: string
}

/** Compte réel créé via "S'inscrire" sur l'écran de connexion (JSON Server)
 * — mot de passe stocké tel quel : acceptable ici car il n'existe aucun
 * vrai backend d'authentification derrière cette maquette (pas de hachage
 * possible côté navigateur sans un serveur dédié), jamais à reproduire dans
 * une application avec de vrais utilisateurs. */
export interface User {
  id: EntityId
  name: string
  email: string
  password: string
  role: 'Visiteur' | 'Testeur' | 'Admin'
  createdAt: string
}

/** Statistiques de performance calculées à partir de vrais StepResult —
 * jamais saisies ni générées, toujours dérivées (voir utils/metrics.ts). */
export interface PerformanceMetrics {
  totalRequests: number
  successCount: number
  errorCount: number
  successRate: number // %
  errorRate: number // %
  avgResponseTime: number // ms
  minResponseTime: number // ms
  maxResponseTime: number // ms
  throughput: number // req/s
}

/** Métriques calculées pour UNE étape précise au sein d'une exécution
 * (regroupe tous les StepResult de ce stepId, tous VUs/itérations confondus)
 * — jamais les métriques globales du scénario recopiées par étape. */
export interface StepMetrics {
  stepId: EntityId
  metrics: PerformanceMetrics
  /** Moyennes réelles des relevés CPU/RAM capturés après chaque requête de
   * cette étape (voir StepResult.serverMetrics) — `null` si aucun relevé
   * n'a pu être capturé pour cette étape (jamais une valeur inventée). */
  avgCpu: number | null
  avgRam: number | null
}

/** Niveau de santé global du serveur au moment de la mesure — indépendant
 * du temps de réponse / des erreurs des endpoints testés (ceux-ci vivent
 * dans StepResult, jamais ici). */
export type ServerHealth = 'Sain' | 'Dégradé' | 'Critique'

// ============================================================
// Snapshot serveur figé par exécution — voir services/api/serverSnapshot.ts.
// Dérivé directement d'Application.monitoringUrl (pas d'entité Serveur
// séparée). Champs volontairement nommés comme le futur backend Spring Boot
// (GET /executions/{id}/server-metrics -> {cpu, ram, disk, network, health})
// pour que le branchement futur ne nécessite aucun renommage côté frontend.
// ============================================================

export interface ServerSnapshot {
  applicationId: EntityId | null
  applicationName: string | null
  capturedAt: string | null
  cpu: number | null
  ram: number | null
  disk: number | null
  network: number | null
  health: ServerHealth | null
  /** D'où viennent les valeurs ci-dessus (ex. "Local Test Server") — `null`
   * tant qu'aucune source n'a pu être résolue, pour ne jamais laisser croire
   * que des métriques `null` viennent d'un monitoring actif. */
  source: string | null
  /** `true` si les valeurs viennent de données de démonstration plutôt que
   * d'un monitoring réellement interrogé en direct. Absent/`false` = source
   * réelle (ou aucune donnée). */
  simulated?: boolean
}
