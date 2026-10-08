import { BackendComparisonColumn } from '../../types/backendContracts'

// ============================================================
// Comparaison d'exécutions : meilleure / moins bonne valeur par ligne et
// lecture du plafond de débit (fonctions pures, testées).
// ============================================================

/** Sens d'une mesure : plus haut = mieux (débit), plus bas = mieux (temps, erreurs, ressources), ou neutre. */
export type Direction = 'higher' | 'lower' | 'neutral'

/** Indices de la meilleure et de la moins bonne valeur ; aucun si moins de deux valeurs ou toutes égales. */
export function bestWorst(values: (number | null | undefined)[], direction: Direction): { best: number | null; worst: number | null } {
  if (direction === 'neutral') return { best: null, worst: null }
  const present = values.map((v, i) => ({ v, i })).filter((x): x is { v: number; i: number } => x.v != null && Number.isFinite(x.v))
  if (present.length < 2) return { best: null, worst: null }
  const min = present.reduce((a, b) => (b.v < a.v ? b : a))
  const max = present.reduce((a, b) => (b.v > a.v ? b : a))
  if (min.v === max.v) return { best: null, worst: null }
  return direction === 'higher' ? { best: max.i, worst: min.i } : { best: min.i, worst: max.i }
}

/** Seuil : quand la charge augmente, le débit doit suivre au moins à 80 % pour « suivre la charge ». */
export const SCALING_EFFICIENCY = 0.8

/**
 * Lecture du plafond : exécutions triées par utilisateurs virtuels ; entre deux paliers, efficacité =
 * (débit / débit précédent) / (VU / VU précédents). Premier palier sous SCALING_EFFICIENCY : le débit
 * ne suit plus la charge. Null si moins de deux charges différentes.
 */
export function plateauReading(columns: Pick<BackendComparisonColumn, 'virtualUsers' | 'throughput'>[]): string | null {
  const pts = columns
    .filter((c) => c.virtualUsers != null && c.virtualUsers > 0 && c.throughput != null && c.throughput > 0)
    .map((c) => ({ vu: c.virtualUsers as number, rps: c.throughput as number }))
    .sort((a, b) => a.vu - b.vu)
  const distinct = pts.filter((p, i) => i === 0 || p.vu !== pts[i - 1].vu)
  if (distinct.length < 2) return null
  const fmt = (v: number) => v.toLocaleString('fr-FR', { maximumFractionDigits: 1 })
  for (let i = 1; i < distinct.length; i++) {
    const a = distinct[i - 1]
    const b = distinct[i]
    const efficiency = (b.rps / a.rps) / (b.vu / a.vu)
    if (efficiency < SCALING_EFFICIENCY) {
      return `Le débit suit la charge jusqu’à ${a.vu} utilisateurs (${fmt(a.rps)} req/s) ; de ${a.vu} à ${b.vu} utilisateurs `
        + `(× ${fmt(b.vu / a.vu)}), il ne passe que de ${fmt(a.rps)} à ${fmt(b.rps)} req/s (× ${fmt(b.rps / a.rps)}) : `
        + `le débit commence à plafonner vers ${a.vu}–${b.vu} utilisateurs.`
    }
  }
  const first = distinct[0]
  const last = distinct[distinct.length - 1]
  return `Pas de plafond visible : le débit suit la charge de ${first.vu} à ${last.vu} utilisateurs (${fmt(first.rps)} → ${fmt(last.rps)} req/s).`
}
