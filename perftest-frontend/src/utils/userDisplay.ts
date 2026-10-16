import { inUserZone } from './userTimezone'
// Affichage des personnes : initiales et couleur d'avatar, dates relatives.

/** Initiales du nom AFFICHÉ (« Admin Test » -> « AT », « viewer-test » -> « VT », « lina » -> « LI »,
 * « stagiaire.2025 » -> « ST » : les morceaux qui ne commencent pas par une lettre sont ignorés). */
export function initialsOf(name: string | null | undefined): string {
  const parts = (name ?? '').trim().split(/[\s._-]+/).filter(Boolean)
  const words = parts.filter((w) => /^\p{L}/u.test(w))
  if (words.length === 0) return parts.length ? parts[0].slice(0, 2).toUpperCase() : '?'
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return (words[0][0] + words[words.length - 1][0]).toUpperCase()
}

/** Teintes d'avatar (fond / texte définis en CSS : .ur-avatar--0 … --7). */
export const AVATAR_TONES = 8

/** Couleur stable propre à chaque utilisateur : même identifiant -> même couleur, à chaque visite. */
export function avatarTone(seed: string): number {
  let hash = 0
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0
  return hash % AVATAR_TONES
}

const rtf = new Intl.RelativeTimeFormat('fr', { numeric: 'auto' })
const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 3600], ['month', 30 * 24 * 3600], ['week', 7 * 24 * 3600],
  ['day', 24 * 3600], ['hour', 3600], ['minute', 60],
]

/** « il y a 5 minutes », « hier », « à l'instant » ; null si date absente ou invalide. */
export function relativeTime(iso: string | null | undefined, now: number = Date.now()): string | null {
  if (!iso) return null
  const t = new Date(iso).getTime()
  if (isNaN(t)) return null
  const seconds = Math.round((t - now) / 1000)
  if (Math.abs(seconds) < 60) return 'à l’instant'
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) return rtf.format(Math.round(seconds / size), unit)
  }
  return 'à l’instant'
}

/** Date complète en français (« 7 octobre 2026 à 16:08 ») ; null si absente ou invalide. */
export function fullDate(iso: string | null | undefined): string | null {
  if (!iso) return null
  const d = new Date(iso)
  if (isNaN(d.getTime())) return null
  return d.toLocaleDateString('fr-FR', inUserZone({ day: 'numeric', month: 'long', year: 'numeric' }))
    + ' à ' + d.toLocaleTimeString('fr-FR', inUserZone({ hour: '2-digit', minute: '2-digit' }))
}
