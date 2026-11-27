// Mode stress : libellés du résultat (point de rupture) et de la montée en direct, partagés par le suivi, le détail et le rapport.

import { BackendBreakpointCriterion, BackendStressLive, BackendStressResult } from '../types/backendContracts'

const nf = (v: number, digits = 1) => v.toLocaleString('fr-FR', { maximumFractionDigits: digits })

export const formatRps = (v: number | null | undefined) => (v == null ? '—' : `${nf(v)} req/s`)
export const formatMsValue = (v: number | null | undefined) => (v == null ? '—' : `${nf(v, 0)} ms`)

/** Fenêtre d'évaluation de la rupture (StressSettings.WINDOW_SECONDS). */
const WINDOW_SECONDS = 5

/** Vitesse de montée et précision qui en découle : « +20 req/s par seconde · rupture à ± 100 req/s près ». */
export function stressRampLabel(rampRpsPerSecond: number | null | undefined, withPrecision = true): string | null {
  if (rampRpsPerSecond == null) return null
  const ramp = `+${nf(rampRpsPerSecond, 0)} req/s par seconde`
  return withPrecision ? `${ramp} · rupture située à ± ${nf(WINDOW_SECONDS * rampRpsPerSecond, 0)} req/s près` : ramp
}

/** « 3 min 42 s » ou « 42 s ». */
export function formatElapsed(seconds: number | null | undefined): string {
  if (seconds == null) return '—'
  return seconds >= 60 ? `${Math.floor(seconds / 60)} min ${seconds % 60} s` : `${seconds} s`
}

export const CRITERION_LABELS: Record<BackendBreakpointCriterion, string> = {
  RESPONSE_TIME: 'temps de réponse (P95)',
  REFUSALS: 'refus (délais, connexions refusées, 429, 502/503/504)',
  THROUGHPUT: 'débit obtenu sous le débit offert',
}

export const LIMIT_LABELS: Record<'TARGET_RPS' | 'VIRTUAL_USERS' | 'DURATION', string> = {
  TARGET_RPS: 'débit cible maximal',
  VIRTUAL_USERS: 'utilisateurs virtuels par test',
  DURATION: 'durée maximale d’un test',
}

/** Titre court du résultat. */
export function stressOutcomeTitle(stress: BackendStressResult): string {
  switch (stress.outcome) {
    case 'BREAKPOINT': return `Point de rupture vers ${formatRps(stress.offeredRps)}`
    case 'NO_BREAKPOINT': return `Aucune rupture jusqu’à ${formatRps(stress.peakOfferedRps)}`
    case 'BASELINE_UNUSABLE': return 'Mesure de référence impossible'
    case 'INCONCLUSIVE': return 'Non concluant : LoadPilot saturé'
    default: return 'Test de stress interrompu'
  }
}

export const STRESS_PHASES: { key: BackendStressLive['phase']; label: string }[] = [
  { key: 'WARMUP', label: 'Échauffement' },
  { key: 'BASELINE', label: 'Mesure de référence' },
  { key: 'RAMP', label: 'Montée' },
  { key: 'HOLD', label: 'Limite atteinte' },
]

/** Libellé de progression d'un test de stress en cours. */
export function stressProgressLabel(live: BackendStressLive): string {
  const phase = STRESS_PHASES.find((p) => p.key === live.phase)?.label ?? ''
  if (live.phase === 'WARMUP' || live.phase === 'BASELINE') {
    const end = live.phase === 'WARMUP' ? live.warmupEndSeconds : live.baselineEndSeconds
    return `${phase} · ${Math.max(0, end - live.elapsedSeconds)} s restantes`
  }
  if (live.phase === 'HOLD' && live.holdingLimit) return `${phase} (${LIMIT_LABELS[live.holdingLimit]}) · ${formatRps(live.offeredRps)} offertes`
  return `${phase} · ${formatRps(live.offeredRps)} offertes`
}
