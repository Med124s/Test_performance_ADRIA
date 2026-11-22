import { BackendExecutionTimeline } from '../../types/backendContracts'

// Données de l'analyse dans le temps : libellés de temps, étapes les plus lentes, erreurs par type.

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

/** Intervalle du graphique qui contient la seconde "seconds" (dernier t <= seconds) ; -1 si avant le premier. */
export function bucketIndexAt(t: number[], seconds: number): number {
  let index = -1
  for (let i = 0; i < t.length && t[i] <= seconds; i++) index = i
  return index
}

/** Repères d'un test de stress sur l'axe des intervalles : mesure de référence, début et confirmation de la rupture. */
export interface StressMarks {
  baseline: { from: number; to: number } | null
  onset: number | null
  confirmed: number | null
}

export function stressMarks(t: number[], stress: { outcome: string | null; onsetSeconds: number | null; detectedSeconds: number | null },
  warmupSeconds: number, baselineEndSeconds: number): StressMarks {
  const from = bucketIndexAt(t, warmupSeconds)
  const to = bucketIndexAt(t, baselineEndSeconds - 1)
  const breakpoint = stress.outcome === 'BREAKPOINT'
  return {
    baseline: from >= 0 && to >= from ? { from, to } : null,
    onset: breakpoint && stress.onsetSeconds != null ? bucketIndexAt(t, stress.onsetSeconds) : null,
    confirmed: breakpoint && stress.detectedSeconds != null ? bucketIndexAt(t, stress.detectedSeconds) : null,
  }
}
