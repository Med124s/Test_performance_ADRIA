// ============================================================
// Module centralisé de calcul des métriques de performance —
// seule source de vérité pour ces calculs (moyenne/min/max/P95/
// taux de succès/erreur/débit), à partir des vrais StepResult et
// Execution stockés dans JSON Server. Remplace les copies dupliquées
// qui existaient dans dashboardStats.ts, Metriques.tsx,
// ExecutionDetail.tsx et ExecutionReport.tsx.
// ============================================================

import { Execution, PerformanceMetrics, StepMetrics, StepResult } from '../types'

export function average(nums: number[]): number {
  if (nums.length === 0) return 0
  return nums.reduce((s, n) => s + n, 0) / nums.length
}

export function min(nums: number[]): number {
  return nums.length === 0 ? 0 : Math.min(...nums)
}

export function max(nums: number[]): number {
  return nums.length === 0 ? 0 : Math.max(...nums)
}

/** Percentile par la méthode "nearest-rank" (ex: p=95 pour le P95),
 * calculé sur les valeurs réellement enregistrées — jamais une valeur
 * fictive. */
export function percentile(nums: number[], p: number): number {
  if (nums.length === 0) return 0
  const sorted = [...nums].sort((a, b) => a - b)
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))
  return sorted[index]
}

export function durationToSeconds(d: string): number {
  const m = d.match(/^(\d+):(\d+):(\d+)$/)
  if (!m) return 0
  return (+m[1]) * 3600 + (+m[2]) * 60 + (+m[3])
}

export function formatDuration(totalSeconds: number): string {
  const h = Math.floor(totalSeconds / 3600)
  const m = Math.floor((totalSeconds % 3600) / 60)
  const s = Math.floor(totalSeconds % 60)
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** Calcule l'ensemble des métriques de performance à partir de vrais
 * StepResult. `totalDurationSec`, si fourni, permet de calculer le débit
 * (requêtes / seconde) ; sans durée connue, le débit est 0 plutôt
 * qu'inventé. */
export function computePerformanceMetrics(
  stepResults: StepResult[],
  totalDurationSec = 0
): PerformanceMetrics {
  // Une étape 'skipped' (Étape active décochée) n'a envoyé aucune requête —
  // elle ne doit compter ni comme un succès, ni comme une erreur, ni dans
  // le total de requêtes réellement effectuées.
  const sentResults = stepResults.filter((r) => r.status !== 'skipped')
  const totalRequests = sentResults.length
  const successCount = sentResults.filter((r) => r.status === 'success').length
  const errorCount = sentResults.filter((r) => r.status === 'error').length
  const responseTimes = sentResults
    .map((r) => r.responseTimeMs)
    .filter((v): v is number => typeof v === 'number')

  return {
    totalRequests,
    successCount,
    errorCount,
    successRate: totalRequests > 0 ? (successCount / totalRequests) * 100 : 0,
    errorRate: totalRequests > 0 ? (errorCount / totalRequests) * 100 : 0,
    avgResponseTime: Math.round(average(responseTimes)),
    minResponseTime: Math.round(min(responseTimes)),
    maxResponseTime: Math.round(max(responseTimes)),
    throughput: totalDurationSec > 0 ? totalRequests / totalDurationSec : 0,
  }
}

/** Regroupe les résultats réels par étape (stepId) et calcule les métriques
 * de CHAQUE étape séparément — jamais les métriques globales du scénario
 * recopiées pour chaque étape. Le débit par étape utilise la même durée
 * totale que le scénario (une étape seule n'a pas sa propre durée mesurée
 * indépendamment). Ordre de sortie : première apparition du stepId dans
 * `stepResults` (= ordre d'exécution réel), pas un tri alphabétique. */
export function computeMetricsByStep(stepResults: StepResult[], totalDurationSec = 0): StepMetrics[] {
  const order: string[] = []
  const byStep = new Map<string, StepResult[]>()
  stepResults.forEach((r) => {
    const key = String(r.stepId)
    if (!byStep.has(key)) {
      byStep.set(key, [])
      order.push(key)
    }
    byStep.get(key)!.push(r)
  })
  return order.map((stepId) => {
    const results = byStep.get(stepId)!
    const cpuSamples = results.map((r) => r.serverMetrics?.cpu).filter((v): v is number => typeof v === 'number')
    const ramSamples = results.map((r) => r.serverMetrics?.ram).filter((v): v is number => typeof v === 'number')
    return {
      stepId,
      metrics: computePerformanceMetrics(results, totalDurationSec),
      avgCpu: cpuSamples.length > 0 ? Math.round(average(cpuSamples)) : null,
      avgRam: ramSamples.length > 0 ? Math.round(average(ramSamples)) : null,
    }
  })
}

/** Même calcul, mais agrégé sur un ensemble d'exécutions réelles (durée
 * totale = somme des durées de chaque exécution). */
export function computeMetricsForExecutions(executions: Execution[]): PerformanceMetrics {
  const allResults = executions.flatMap((e) => e.stepResults)
  const totalDurationSec = executions.reduce((s, e) => s + durationToSeconds(e.duration), 0)
  return computePerformanceMetrics(allResults, totalDurationSec)
}
