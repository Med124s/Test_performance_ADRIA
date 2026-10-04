import { BackendExecutionTimeline } from '../../types/backendContracts'

// ============================================================
// Préparation des données de l'analyse dans le temps (fonctions pures,
// testées) : libellés de temps, étapes les plus lentes, erreurs par type.
// ============================================================

/** « 16 s » pour un test court, « 2:05 » au-delà de 2 minutes. */
export function formatT(seconds: number, long: boolean): string {
  if (!long) return `${seconds} s`
  const m = Math.floor(seconds / 60)
  return `${m}:${String(seconds % 60).padStart(2, '0')}`
}

export function isLongRun(tl: BackendExecutionTimeline): boolean {
  return tl.durationMs > 120_000 || tl.bucketSeconds >= 60
}

/** Indices des n étapes au P95 global le plus élevé. */
export function slowestSteps(tl: BackendExecutionTimeline, n: number): number[] {
  return tl.steps
    .map((s, i) => ({ i, p95: s.overallP95Ms ?? -1 }))
    .sort((a, b) => b.p95 - a.p95)
    .slice(0, n)
    .map((s) => s.i)
}

/**
 * Erreurs par intervalle, une série par type d'erreur (code HTTP ou message), éventuellement
 * restreintes à une étape. Seuls les types présents (après filtre) sont renvoyés.
 */
export function errorsByType(tl: BackendExecutionTimeline, step: number | null): { types: string[]; series: number[][] } {
  const perType = new Map<number, number[]>()
  for (const p of tl.errorPoints) {
    if (step != null && p.step !== step) continue
    let s = perType.get(p.type)
    if (!s) {
      s = new Array(tl.t.length).fill(0)
      perType.set(p.type, s)
    }
    s[p.bucket] += p.count
  }
  const typeIndexes = [...perType.keys()].sort((a, b) => a - b)
  return { types: typeIndexes.map((i) => tl.errorTypes[i]), series: typeIndexes.map((i) => perType.get(i)!) }
}

/** Erreurs par seconde (le nombre par intervalle divisé par la largeur de l'intervalle). */
export function perSecond(count: number, bucketSeconds: number): number {
  return Math.round((count / bucketSeconds) * 10) / 10
}

/** Palette distincte (lisible en thème clair et sombre). */
export const STEP_COLORS = ['#4F46E5', '#F59E0B', '#10B981', '#EF4444', '#06B6D4', '#A855F7', '#EC4899', '#84CC16', '#F97316', '#64748B']
export const ERROR_COLORS = ['#EF4444', '#F97316', '#EAB308', '#A855F7', '#06B6D4', '#64748B']
