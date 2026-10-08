import { BackendStepEvolution, BackendStepTrend, BackendStepTrendKind } from '../../types/backendContracts'

// ============================================================
// Tableau « Évolution des étapes pendant le test » : libellés, tri,
// infobulles (fonctions pures, testées).
// Le calcul (P95 début / fin, tendance) est fait par le serveur
// (StepEvolutionAnalyzer) : ces règles n'en sont que l'explication.
// ============================================================

export const TREND_META: Record<BackendStepTrendKind, { label: string; pill: string; icon: string }> = {
  DEGRADED: { label: 'Dégradée', pill: 'danger', icon: 'bi-arrow-up-right' },
  WATCH: { label: 'À surveiller', pill: 'warning', icon: 'bi-eye' },
  STABLE: { label: 'Stable', pill: 'success', icon: 'bi-check2' },
  INSUFFICIENT: { label: 'Pas assez de données', pill: 'neutral', icon: 'bi-dash' },
}

/** Règle des badges (mêmes seuils que le serveur), affichée en infobulle. */
export const TREND_RULE =
  'Basée surtout sur la hausse du P95 en millisecondes. '
  + 'Dégradée : hausse d’au moins 100 ms et d’au moins 30 %. '
  + 'À surveiller : hausse d’au moins 30 ms et d’au moins 15 %. '
  + 'Stable : hausse plus faible, ou baisse (une étape rapide qui passe de 20 à 40 ms reste stable).'

const RANK: Record<BackendStepTrendKind, number> = { DEGRADED: 0, WATCH: 1, STABLE: 2, INSUFFICIENT: 3 }

/** De la plus dégradée à la plus stable : tendance, puis hausse en ms décroissante. */
export function sortTrends(steps: BackendStepTrend[]): BackendStepTrend[] {
  return [...steps].sort((a, b) => RANK[a.trend] - RANK[b.trend]
    || (b.deltaMs ?? Number.NEGATIVE_INFINITY) - (a.deltaMs ?? Number.NEGATIVE_INFINITY))
}

/** « 12 s », ou « 2:05 » pour un test long. */
export function formatSeconds(seconds: number, long: boolean): string {
  const s = Math.round(seconds)
  return long ? `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}` : `${s} s`
}

const fmtInt = (n: number) => n.toLocaleString('fr-FR')

/** « +137 ms », « −15 ms », « 0 ms ». */
export function formatDeltaMs(delta: number): string {
  if (delta > 0) return `+${fmtInt(delta)} ms`
  if (delta < 0) return `−${fmtInt(-delta)} ms`
  return '0 ms'
}

export function formatDeltaPercent(percent: number): string {
  const v = Math.round(percent)
  return v > 0 ? `+${v} %` : v < 0 ? `−${-v} %` : '0 %'
}

/** Infobulle d'une ligne : P95 de début et de fin (avec leur période) et nombre de requêtes de l'étape. */
export function rowTooltip(step: BackendStepTrend, ev: BackendStepEvolution, long: boolean): string {
  const period = (from: number, to: number) => `de ${formatSeconds(from, long)} à ${formatSeconds(to, long)}`
  const p95 = (v: number | null) => (v == null ? '—' : `${fmtInt(v)} ms`)
  return [
    `${step.name}`,
    `P95 au début : ${p95(step.startP95Ms)} (${fmtInt(step.startRequests)} requêtes, ${period(ev.startFromSeconds, ev.startToSeconds)})`,
    `P95 à la fin : ${p95(step.endP95Ms)} (${fmtInt(step.endRequests)} requêtes, ${period(ev.endFromSeconds, ev.endToSeconds)})`,
    `Requêtes de l’étape : ${fmtInt(step.totalRequests)}`,
  ].join('\n')
}
