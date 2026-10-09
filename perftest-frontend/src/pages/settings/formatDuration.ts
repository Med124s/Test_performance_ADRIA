/** Durée lisible : « 500 ms », « 5 s », « 1 min 30 s », « 2 h ». */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms)) return '—'
  if (ms < 1000) return `${Math.round(ms)} ms`
  const totalSeconds = ms / 1000
  if (totalSeconds < 60) return `${totalSeconds.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} s`
  const h = Math.floor(totalSeconds / 3600)
  const m = Math.floor((totalSeconds % 3600) / 60)
  const s = Math.round(totalSeconds % 60)
  if (h > 0) return m > 0 ? `${h} h ${m} min` : `${h} h`
  return s > 0 ? `${m} min ${s} s` : `${m} min`
}
