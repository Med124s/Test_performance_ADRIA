// Série d'essais de stress : libellés de l'état, du verdict consolidé et de chaque essai.

import { BackendStressSeriesResponse, BackendStressSeriesStatus, BackendStressSeriesVerdict } from '../types/backendContracts'
import { formatRps } from './stressResult'

/** Nombres d'essais proposés au lancement (StressSettings.SERIES_RUNS) ; 1 = test seul, sans série. */
export const SERIES_RUNS = [1, 3, 5] as const

/** Annuler un essai interrompt la série (StressSeriesService : essai annulé = non concluant). */
export const SERIES_RUN_CANCEL_WARNING = 'Cet essai fait partie d’une série : l’annuler interrompt la série, les essais suivants ne sont pas lancés. '
  + 'Les essais déjà terminés restent dans le résultat consolidé.'

export const SERIES_STATUS_LABELS: Record<BackendStressSeriesStatus, string> = {
  RUNNING: 'En cours',
  COMPLETED: 'Terminée',
  INTERRUPTED: 'Interrompue',
  CANCELLED: 'Annulée',
}

/** Ton de l'encadré consolidé (classes rp-stress-*). */
export const VERDICT_TONE: Record<BackendStressSeriesVerdict, 'success' | 'warning' | 'danger' | 'neutral'> = {
  CONFIRMED: 'warning',
  DISPERSED: 'danger',
  NOT_CONFIRMED: 'neutral',
  NO_BREAKPOINT: 'success',
  NOT_COMPARABLE: 'neutral',
  NONE: 'neutral',
}

export const VERDICT_TITLES: Record<BackendStressSeriesVerdict, string> = {
  CONFIRMED: 'Point de rupture confirmé',
  DISPERSED: 'Résultats dispersés',
  NOT_CONFIRMED: 'Point de rupture non confirmé',
  NO_BREAKPOINT: 'Aucune rupture',
  NOT_COMPARABLE: 'Essais non comparables',
  NONE: 'Aucun essai concluant',
}

/** Résultat d'un essai en quelques mots : point de rupture, montée jusqu'à la limite, ou raison de l'absence de résultat. */
export function runOutcomeLabel(run: BackendStressSeriesResponse['runs'][number]): string {
  const stress = run.stress
  if (run.status === 'QUEUED' || run.status === 'RUNNING' || run.status === 'PAUSED') return 'En cours'
  switch (stress?.outcome) {
    case 'BREAKPOINT': return `Rupture vers ${formatRps(stress.offeredRps)}`
    case 'NO_BREAKPOINT': return `Aucune rupture jusqu’à ${formatRps(stress.peakOfferedRps)}`
    case 'BASELINE_UNUSABLE': return 'Mesure de référence impossible'
    case 'INCONCLUSIVE': return 'Non concluant : LoadPilot saturé'
    default: return run.status === 'CANCELLED' ? 'Annulé' : 'Sans résultat'
  }
}

/** Secondes restantes avant l'essai suivant (0 si dépassé), null hors pause. */
export function secondsUntilNextRun(series: Pick<BackendStressSeriesResponse, 'nextRunAt'>, now: number): number | null {
  if (!series.nextRunAt) return null
  return Math.max(0, Math.ceil((new Date(series.nextRunAt).getTime() - now) / 1000))
}

/** Rang de l'essai en cours ou du prochain : essais déjà lancés + 1 pendant une pause. */
export function currentRunIndex(series: BackendStressSeriesResponse): number {
  const active = series.runs.find((r) => r.executionId === series.currentExecutionId)
  if (active) return active.index
  return Math.min(series.requestedRuns, series.runs.length + (series.status === 'RUNNING' ? 1 : 0))
}
