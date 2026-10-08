// ============================================================
// Format des nombres en français : virgule pour les décimales, espace
// pour les milliers, au plus une décimale par défaut (ex. 68,7 ; 8 673).
// « — » pour une valeur absente (jamais 0 inventé).
// ============================================================

const formatters = new Map<number, Intl.NumberFormat>()

function formatter(maxDecimals: number): Intl.NumberFormat {
  let f = formatters.get(maxDecimals)
  if (!f) {
    f = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: maxDecimals, useGrouping: true })
    formatters.set(maxDecimals, f)
  }
  return f
}

/** 8673 → « 8 673 » ; 68.66 → « 68,7 ». */
export function formatNumber(value: number | null | undefined, maxDecimals = 1): string {
  if (value == null || !Number.isFinite(value)) return '—'
  return formatter(maxDecimals).format(value)
}

/** « 6,9 % ». */
export const formatPercent = (value: number | null | undefined, maxDecimals = 1) =>
  value == null ? '—' : `${formatNumber(value, maxDecimals)} %`

/** « 1 234 ms ». */
export const formatMs = (value: number | null | undefined) => (value == null ? '—' : `${formatNumber(value, 0)} ms`)

/** « 68,7 req/s ». */
export const formatRps = (value: number | null | undefined) => (value == null ? '—' : `${formatNumber(value, 1)} req/s`)

/** Écart signé : « +23,5 % » / « −8 % ». */
export function formatSignedPercent(value: number | null | undefined): string {
  if (value == null) return '—'
  const abs = formatNumber(Math.abs(value), 1)
  return value > 0 ? `+${abs} %` : value < 0 ? `−${abs} %` : '0 %'
}
