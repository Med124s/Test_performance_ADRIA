import { getUserTimezone, inUserZone } from '../../utils/userTimezone'
import { zonedToIso } from '../scheduling/scheduleModel'
import { BackendExecutionHistoryResponse } from '../../types/backendContracts'
import { formatNumber } from '../../utils/numberFormat'

// ============================================================
// Page Exécutions : période, formats d'affichage (durée, date relative,
// taux d'erreur) et statuts. Fonctions pures, testées.
// ============================================================

export type Period = '' | '24h' | '7d' | '30d' | '90d' | 'custom'

export const PERIOD_OPTIONS: { value: Period; label: string }[] = [
  { value: '', label: 'Toutes' },
  { value: '24h', label: 'Dernières 24 h' },
  { value: '7d', label: '7 derniers jours' },
  { value: '30d', label: '30 derniers jours' },
  { value: 'custom', label: 'Personnalisée' },
]

const PERIOD_HOURS: Record<string, number> = { '24h': 24, '7d': 7 * 24, '30d': 30 * 24, '90d': 90 * 24 }

/**
 * Bornes envoyées au serveur. Périodes glissantes : depuis maintenant moins 24 h / 7 j / 30 j / 90 j (3 mois).
 * Personnalisée : journées entières (AAAA-MM-JJ, heure locale), bornes incluses.
 */
/** Début (00:00) et fin (23:59:59.999) d'un jour aaaa-mm-jj dans le fuseau de l'utilisateur. */
export function dayStartIso(isoDay: string): string | null {
  return zonedToIso(isoDay, '00:00', getUserTimezone())
}
export function dayEndIso(isoDay: string): string | null {
  const start = zonedToIso(isoDay, '23:59', getUserTimezone())
  return start ? new Date(new Date(start).getTime() + 59_999).toISOString() : null
}

export function periodBounds(period: Period, from: string, to: string, now = new Date()): { dateFrom?: string; dateTo?: string } {
  const hours = PERIOD_HOURS[period]
  if (hours) return { dateFrom: new Date(now.getTime() - hours * 3600 * 1000).toISOString() }
  if (period !== 'custom') return {}
  return {
    ...(from ? { dateFrom: dayStartIso(from) ?? undefined } : {}),
    ...(to ? { dateTo: dayEndIso(to) ?? undefined } : {}),
  }
}

/** « 05/10/2026 » → « 2026-10-05 » ; null si la date n'existe pas ou est incomplète. */
export function parseFrDate(text: string): string | null {
  const m = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(text.trim())
  if (!m) return null
  const [, dd, mm, yyyy] = m
  const d = new Date(Number(yyyy), Number(mm) - 1, Number(dd))
  if (d.getFullYear() !== Number(yyyy) || d.getMonth() !== Number(mm) - 1 || d.getDate() !== Number(dd)) return null
  return `${yyyy}-${mm}-${dd}`
}

/** « 2026-10-05 » → « 05/10/2026 ». */
export function formatFrDate(isoDay: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDay)
  return m ? `${m[3]}/${m[2]}/${m[1]}` : ''
}

/** Saisie guidée : chiffres seulement, barres ajoutées automatiquement (jj/mm/aaaa). */
export function maskFrDate(raw: string): string {
  const digits = raw.replace(/\D/g, '').slice(0, 8)
  if (digits.length <= 2) return digits
  if (digits.length <= 4) return `${digits.slice(0, 2)}/${digits.slice(2)}`
  return `${digits.slice(0, 2)}/${digits.slice(2, 4)}/${digits.slice(4)}`
}

/** 94 000 ms → « 1 min 34 s » ; 3 723 000 → « 1 h 02 min » ; 8 400 → « 8 s ». */
export function formatHumanDuration(ms: number | null | undefined): string {
  if (ms == null) return '—'
  const total = Math.round(ms / 1000)
  if (total < 60) return `${total} s`
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  if (h > 0) return `${h} h ${String(m).padStart(2, '0')} min`
  return `${m} min ${String(s).padStart(2, '0')} s`
}

const RTF = new Intl.RelativeTimeFormat('fr', { numeric: 'auto' })

/** « il y a 2 heures », « hier », « à l’instant ». */
export function relativeTime(iso: string, now = new Date()): string {
  const diff = (new Date(iso).getTime() - now.getTime()) / 1000
  const abs = Math.abs(diff)
  if (abs < 45) return 'à l’instant'
  if (abs < 3600) return RTF.format(Math.round(diff / 60), 'minute')
  if (abs < 86400) return RTF.format(Math.round(diff / 3600), 'hour')
  if (abs < 30 * 86400) return RTF.format(Math.round(diff / 86400), 'day')
  if (abs < 365 * 86400) return RTF.format(Math.round(diff / (30 * 86400)), 'month')
  return RTF.format(Math.round(diff / (365 * 86400)), 'year')
}

/** « lundi 5 octobre 2026 à 14:32 ». */
export function fullDate(iso: string): string {
  const d = new Date(iso)
  return `${d.toLocaleDateString('fr-FR', inUserZone({ weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }))} à ${d.toLocaleTimeString('fr-FR', inUserZone({ hour: '2-digit', minute: '2-digit' }))}`
}

/** Requêtes envoyées et taux d'erreur (%) d'une ligne (compteurs en direct si fournis). */
export function requestsOf(row: Pick<BackendExecutionHistoryResponse, 'successfulSteps' | 'failedSteps'>, live?: { ok: number; ko: number } | null) {
  const ok = live ? live.ok : row.successfulSteps ?? 0
  const ko = live ? live.ko : row.failedSteps ?? 0
  const total = ok + ko
  return { total, failed: ko, errorRate: total > 0 ? (ko * 100) / total : null }
}

/** « 8 109 · 2,5 % ». */
export function formatRequests(total: number, errorRate: number | null): { total: string; rate: string | null } {
  return { total: formatNumber(total, 0), rate: errorRate == null ? null : `${formatNumber(errorRate, 1)} %` }
}

/** Libellé et badge d'un statut : voir utils/executionStatus (table unique, statut inconnu en gris). */
export { statusLabel, statusPill } from '../../utils/executionStatus'

/** Seuil d'écart de P95 (en %) par défaut au-delà duquel la flèche passe en rouge (régression) ou en
 * vert (amélioration) : le seuil de régression réglable (Paramètres > Système), 20 % par défaut. */
export const P95_CHANGE_THRESHOLD = 20

/**
 * Écart de P95 avec l'exécution précédente du même scénario : « ↑ +12 % » rouge si > +10 %, « ↓ −15 % » vert
 * si < −10 %, gris sinon ; sans exécution précédente (ou sans P95), « — » gris.
 */
export function p95Delta(current: number | null | undefined, previous: number | null | undefined, threshold: number = P95_CHANGE_THRESHOLD): { text: string; tone: 'worse' | 'better' | 'same' | 'none'; percent: number | null } {
  if (current == null || previous == null || previous <= 0) return { text: '—', tone: 'none', percent: null }
  const percent = Math.round(((current - previous) * 1000) / previous) / 10
  const abs = formatNumber(Math.abs(percent), 1)
  if (percent > threshold) return { text: `↑ +${abs} %`, tone: 'worse', percent }
  if (percent < -threshold) return { text: `↓ −${abs} %`, tone: 'better', percent }
  const arrow = percent > 0 ? '↑' : percent < 0 ? '↓' : '='
  return { text: `${arrow} ${percent > 0 ? '+' : percent < 0 ? '−' : ''}${abs} %`, tone: 'same', percent }
}

/** Couleur de la barre CPU : vert < 70 %, orange 70–90 %, rouge > 90 %. */
export function cpuLevel(maxPercent: number): 'ok' | 'warn' | 'high' {
  if (maxPercent > 90) return 'high'
  if (maxPercent >= 70) return 'warn'
  return 'ok'
}