import { Application, Scenario, Execution as ApiExecution } from '../types'
import { computeMetricsForExecutions, durationToSeconds, formatDuration } from '../utils/metrics'

export interface ExecutionStatusBreakdown {
  success: number
  inProgress: number
  failed: number
  cancelled: number
}

export interface RecentExecution {
  name: string
  status: 'Réussi' | 'Échoué' | 'En cours' | 'Annulée'
  time: string
  color: 'success' | 'danger' | 'info' | 'neutral'
  icon: string
}

export interface TopScenario {
  name: string
  percent: number
}

export interface DashboardStats {
  avgResponseTime: number // ms
  throughput: number // req/s
  errorRate: number // %
  activeUsers: number
  testsInProgress: number
  scenarios: number
  executions: number
  avgDurationSec: number
  responseTimeSeries: number[] // 7 points
  executionStatus: ExecutionStatusBreakdown
  recentExecutions: RecentExecution[]
  topScenarios: TopScenario[]
}

const statusMeta: Record<ApiExecution['status'], { status: RecentExecution['status']; color: RecentExecution['color']; icon: string }> = {
  'Réussie': { status: 'Réussi', color: 'success', icon: 'bi-check-circle-fill' },
  'Échouée': { status: 'Échoué', color: 'danger', icon: 'bi-x-circle-fill' },
  'Avec erreurs': { status: 'Échoué', color: 'danger', icon: 'bi-x-circle-fill' },
  'En cours': { status: 'En cours', color: 'info', icon: 'bi-play-circle-fill' },
  'Suspendue': { status: 'En cours', color: 'info', icon: 'bi-pause-circle-fill' },
  'Annulée': { status: 'Annulée', color: 'neutral', icon: 'bi-slash-circle-fill' },
}

/** Moyenne réelle des temps de réponse des 7 derniers jours calendaires,
 * calculée à partir des vrais stepResults des exécutions fournies (0 si
 * aucune exécution réelle ce jour-là — jamais de valeur inventée). */
function dailyAvgResponseTime(executions: ApiExecution[]): number[] {
  const now = new Date()
  return Array.from({ length: 7 }, (_, i) => {
    const dayStart = new Date(now)
    dayStart.setDate(now.getDate() - (6 - i))
    dayStart.setHours(0, 0, 0, 0)
    const dayEnd = new Date(dayStart)
    dayEnd.setDate(dayStart.getDate() + 1)
    const dayResults = executions
      .filter((e) => {
        const t = new Date(e.startedAt)
        return t >= dayStart && t < dayEnd
      })
      .flatMap((e) => e.stepResults)
    return dayResults.length > 0
      ? Math.round(dayResults.reduce((s, r) => s + (r.responseTimeMs ?? 0), 0) / dayResults.length)
      : 0
  })
}

/**
 * Calcule un snapshot de statistiques 100% réel à partir d'un ensemble
 * d'exécutions (et des scénarios associés) — aucune valeur aléatoire.
 * Reprend exactement la même logique de calcul (moyennes sur les vrais
 * stepResults) que la page Métriques.
 */
function computeStatsFromExecutions(executions: ApiExecution[], scenarios: Scenario[]): DashboardStats {
  const perf = computeMetricsForExecutions(executions)
  const totalReq = perf.totalRequests
  const avgResponseTime = perf.avgResponseTime
  const errorRate = Math.round(perf.errorRate * 100) / 100
  const throughput = Math.round(perf.throughput * 100) / 100
  const avgDurationSec = executions.length > 0
    ? Math.round(executions.reduce((s, e) => s + durationToSeconds(e.duration), 0) / executions.length)
    : 0

  // "Actifs" = réellement en cours d'exécution maintenant, jamais un chiffre
  // de remplissage.
  const runningExecs = executions.filter((e) => e.status === 'En cours')
  const testsInProgress = runningExecs.length
  const activeUsers = runningExecs.reduce((s, e) => s + (parseInt(e.users) || 0), 0)

  const executionStatus: ExecutionStatusBreakdown = {
    success: executions.filter((e) => e.status === 'Réussie').length,
    inProgress: executions.filter((e) => e.status === 'En cours').length,
    failed: executions.filter((e) => e.status === 'Échouée' || e.status === 'Avec erreurs').length,
    cancelled: executions.filter((e) => e.status === 'Suspendue').length,
  }

  // "Scénarios les plus utilisés" = part réelle des exécutions par scénario.
  const execCountByScenario = new Map<string, number>()
  executions.forEach((e) => execCountByScenario.set(e.scenarioId, (execCountByScenario.get(e.scenarioId) ?? 0) + 1))
  const topScenarios: TopScenario[] = Array.from(execCountByScenario.entries())
    .map(([scenarioId, count]) => ({
      name: scenarios.find((s) => String(s.id) === String(scenarioId))?.name ?? scenarioId,
      percent: executions.length > 0 ? Math.round((count / executions.length) * 100) : 0,
    }))
    .sort((a, b) => b.percent - a.percent)
    .slice(0, 4)

  const recentExecutions: RecentExecution[] = [...executions]
    .sort((a, b) => (a.startedAt < b.startedAt ? 1 : -1))
    .slice(0, 3)
    .map((e) => {
      // Filet de sécurité : un statut inattendu/corrompu ne doit jamais faire
      // planter tout le Dashboard, seulement dégrader l'affichage de cette ligne.
      const meta = statusMeta[e.status] ?? { status: 'En cours', color: 'info', icon: 'bi-question-circle-fill' }
      const scenarioName = scenarios.find((s) => String(s.id) === String(e.scenarioId))?.name ?? e.scenarioId
      const time = new Date(e.startedAt).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
      return { name: scenarioName, status: meta.status, color: meta.color, icon: meta.icon, time }
    })

  return {
    avgResponseTime,
    throughput,
    errorRate,
    activeUsers,
    testsInProgress,
    scenarios: scenarios.length,
    executions: executions.length,
    avgDurationSec,
    responseTimeSeries: dailyAvgResponseTime(executions),
    executionStatus,
    recentExecutions,
    topScenarios,
  }
}

/**
 * Snapshot de statistiques pour une application donnée (par son vrai id),
 * calculé uniquement à partir des vraies données JSON Server passées en
 * paramètre (toutes les exécutions réelles de l'application).
 */
export function buildStats(
  seedId: string,
  real: { applications: Application[]; scenarios: Scenario[]; executions: ApiExecution[] }
): DashboardStats {
  const app = real.applications.find((a) => String(a.id) === String(seedId))
  const scenariosForApp = app ? real.scenarios.filter((s) => String(s.applicationId) === String(app.id)) : []
  const executionsForApp = app ? real.executions.filter((e) => String(e.applicationId) === String(app.id)) : []
  return computeStatsFromExecutions(executionsForApp, scenariosForApp)
}

/** Vue agrégée ("Toutes les applications") : calculée directement sur la
 * totalité des vraies exécutions et scénarios de JSON Server. */
export function buildAggregateStats(
  real: { applications: Application[]; scenarios: Scenario[]; executions: ApiExecution[] }
): DashboardStats {
  return computeStatsFromExecutions(real.executions, real.scenarios)
}

export function getStats(
  appId: string | 'all',
  real: { applications: Application[]; scenarios: Scenario[]; executions: ApiExecution[] }
): DashboardStats {
  return appId === 'all' ? buildAggregateStats(real) : buildStats(appId, real)
}

export { formatDuration }

