// ============================================================
// Planification — logique pure de l'interface (testée sans rendu) :
// fréquences « humaines » <-> expression CRON, traduction d'un CRON en
// français, dates en jj/mm/aaaa hh:mm dans un fuseau horaire choisi, et
// libellés des états. Le calcul qui fait foi (prochaines dates) reste celui
// du serveur (POST /api/scheduled-executions/preview) : ici, seulement de
// quoi construire la saisie et l'afficher.
// ============================================================

import {
  BackendScheduleState, BackendScheduledExecutionResponse, BackendScheduledRunOutcome,
} from '../../types/backendContracts'

export type Frequency = 'DAILY' | 'WEEKDAYS' | 'WEEKLY' | 'MONTHLY' | 'CUSTOM'

export const FREQUENCY_OPTIONS: { value: Frequency; label: string }[] = [
  { value: 'DAILY', label: 'Chaque jour' },
  { value: 'WEEKDAYS', label: 'Jours ouvrés (lun.–ven.)' },
  { value: 'WEEKLY', label: 'Chaque semaine' },
  { value: 'MONTHLY', label: 'Chaque mois' },
  { value: 'CUSTOM', label: 'Personnalisée (CRON)' },
]

/** Jours de la semaine, lundi d'abord (valeur = numéro CRON : 1 = lundi … 7 = dimanche). */
export const WEEK_DAYS: { value: number; short: string; long: string; cron: string }[] = [
  { value: 1, short: 'Lun', long: 'lundi', cron: 'MON' },
  { value: 2, short: 'Mar', long: 'mardi', cron: 'TUE' },
  { value: 3, short: 'Mer', long: 'mercredi', cron: 'WED' },
  { value: 4, short: 'Jeu', long: 'jeudi', cron: 'THU' },
  { value: 5, short: 'Ven', long: 'vendredi', cron: 'FRI' },
  { value: 6, short: 'Sam', long: 'samedi', cron: 'SAT' },
  { value: 7, short: 'Dim', long: 'dimanche', cron: 'SUN' },
]

export interface RecurrenceForm {
  frequency: Frequency
  /** « HH:MM ». */
  time: string
  weekDays: number[]
  monthDay: number
  /** Saisie libre (5 ou 6 champs) pour « Personnalisée ». */
  customCron: string
}

const pad = (n: number) => String(n).padStart(2, '0')

/** « 02:00 » -> [2, 0] ; null si invalide. */
export function parseTime(text: string): [number, number] | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(text.trim())
  if (!m) return null
  const h = Number(m[1])
  const min = Number(m[2])
  return h <= 23 && min <= 59 ? [h, min] : null
}

/** Expression CRON (6 champs, format du serveur) d'une fréquence ; null si la saisie est incomplète. */
export function buildCron(form: RecurrenceForm): string | null {
  if (form.frequency === 'CUSTOM') {
    const fields = splitCron(form.customCron)
    return fields ? fields.join(' ') : null
  }
  const t = parseTime(form.time)
  if (!t) return null
  const [h, m] = t
  switch (form.frequency) {
    case 'DAILY': return `0 ${m} ${h} * * *`
    case 'WEEKDAYS': return `0 ${m} ${h} * * MON-FRI`
    case 'WEEKLY': {
      if (form.weekDays.length === 0) return null
      const days = [...form.weekDays].sort((a, b) => a - b).map((d) => WEEK_DAYS[d - 1].cron).join(',')
      return `0 ${m} ${h} * * ${days}`
    }
    case 'MONTHLY':
      return form.monthDay >= 1 && form.monthDay <= 31 ? `0 ${m} ${h} ${form.monthDay} * *` : null
  }
}

/** Champs d'un CRON (5 champs standard complétés par « 0 » secondes) ; null si le nombre de champs est faux. */
export function splitCron(cron: string): string[] | null {
  const fields = cron.trim().split(/\s+/).filter(Boolean)
  if (fields.length === 5) return ['0', ...fields]
  return fields.length === 6 ? fields : null
}

const DAY_NAMES: Record<string, number> = { MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6, SUN: 7 }

/** Jours de semaine d'un champ CRON (« MON-FRI », « 1,3 », « SAT,SUN »…) ; null si non reconnu. */
function parseWeekDays(field: string): number[] | null {
  const out = new Set<number>()
  for (const part of field.toUpperCase().split(',')) {
    const range = part.split('-')
    const toNum = (s: string) => (s in DAY_NAMES ? DAY_NAMES[s] : /^\d$/.test(s) ? (Number(s) === 0 ? 7 : Number(s)) : NaN)
    if (range.length === 1) {
      const n = toNum(range[0])
      if (!(n >= 1 && n <= 7)) return null
      out.add(n)
    } else if (range.length === 2) {
      const a = toNum(range[0])
      const b = toNum(range[1])
      if (!(a >= 1 && a <= 7 && b >= a && b <= 7)) return null
      for (let d = a; d <= b; d++) out.add(d)
    } else return null
  }
  return [...out].sort((x, y) => x - y)
}

const isNum = (s: string, max: number, min = 0) => /^\d{1,2}$/.test(s) && Number(s) >= min && Number(s) <= max

/** Retrouve la fréquence « humaine » d'un CRON enregistré (modification) ; « Personnalisée » sinon. */
export function parseCron(cron: string | null | undefined): RecurrenceForm {
  const custom: RecurrenceForm = { frequency: 'CUSTOM', time: '02:00', weekDays: [1], monthDay: 1, customCron: cron ? toFiveFields(cron) : '' }
  const f = cron ? splitCron(cron) : null
  if (!f) return custom
  const [s, m, h, dom, mon, dow] = f
  if (s !== '0' || !isNum(m, 59) || !isNum(h, 23) || mon !== '*') return custom
  const time = `${pad(Number(h))}:${pad(Number(m))}`
  if (dom === '*' && (dow === '*' || dow === '?')) return { ...custom, frequency: 'DAILY', time }
  if (dom === '*' || dom === '?') {
    const days = parseWeekDays(dow)
    if (!days) return custom
    if (days.join(',') === '1,2,3,4,5') return { ...custom, frequency: 'WEEKDAYS', time }
    return { ...custom, frequency: 'WEEKLY', time, weekDays: days }
  }
  if (isNum(dom, 31, 1) && (dow === '*' || dow === '?')) return { ...custom, frequency: 'MONTHLY', time, monthDay: Number(dom) }
  return custom
}

/** CRON à 6 champs dont les secondes valent 0 -> forme standard à 5 champs (plus lisible). */
export function toFiveFields(cron: string): string {
  const f = splitCron(cron)
  return f && f[0] === '0' ? f.slice(1).join(' ') : cron.trim()
}

function listFr(items: string[]): string {
  if (items.length <= 1) return items.join('')
  return `${items.slice(0, -1).join(', ')} et ${items[items.length - 1]}`
}

/**
 * Traduction en français d'une expression CRON (5 ou 6 champs) : « Tous les jours à 02:00 »,
 * « Du lundi au vendredi à 06:30 », « Toutes les 5 minutes »… ; null si l'expression est invalide.
 */
export function describeCron(cron: string): string | null {
  const f = splitCron(cron)
  if (!f) return null
  const [s, m, h, dom, mon, dow] = f
  const valid = (field: string, max: number, min = 0) =>
    field === '*' || field === '?' || /^\*\/\d{1,2}$/.test(field) || field.split(',').every((p) => {
      const r = p.split('-')
      return r.every((x) => isNum(x, max, min)) && r.length <= 2
    })
  const monthValid = mon === '*' || valid(mon, 12, 1)
  const dowDays = dow === '*' || dow === '?' ? null : parseWeekDays(dow)
  if (!valid(s, 59) || !valid(m, 59) || !valid(h, 23) || !valid(dom, 31, 1) || !monthValid || (dow !== '*' && dow !== '?' && !dowDays)) {
    return null
  }

  // Intervalles courts : « Toutes les 5 minutes », « Toutes les heures ».
  const every = (field: string) => (/^\*\/(\d{1,2})$/.exec(field)?.[1] ?? null)
  const sameEveryDay = dom === '*' && mon === '*' && !dowDays
  if (s === '0' && every(m) && h === '*' && sameEveryDay) {
    const n = Number(every(m))
    return n === 1 ? 'Toutes les minutes' : `Toutes les ${n} minutes`
  }
  if (s === '0' && m === '*' && h === '*' && sameEveryDay) return 'Toutes les minutes'
  if (s === '0' && isNum(m, 59) && h === '*' && sameEveryDay) {
    return Number(m) === 0 ? 'Toutes les heures, à l’heure pile' : `Toutes les heures, à la minute ${Number(m)}`
  }
  if (s === '0' && isNum(m, 59) && every(h) && sameEveryDay) {
    return `Toutes les ${Number(every(h))} heures, à la minute ${Number(m)}`
  }

  if (s !== '0' || !isNum(m, 59) || !isNum(h, 23)) {
    return 'Expression personnalisée (secondes, plages ou intervalles) : voir l’aperçu des prochaines exécutions'
  }
  const at = `à ${pad(Number(h))}:${pad(Number(m))}`
  const months = mon === '*' ? '' : ` (mois : ${mon})`
  if (dom === '*' || dom === '?') {
    if (!dowDays || dowDays.length === 7) return `Tous les jours ${at}${months}`
    if (dowDays.join(',') === '1,2,3,4,5') return `Du lundi au vendredi ${at}${months}`
    if (dowDays.join(',') === '6,7') return `Le samedi et le dimanche ${at}${months}`
    return `Chaque ${listFr(dowDays.map((d) => WEEK_DAYS[d - 1].long))} ${at}${months}`
  }
  if (isNum(dom, 31, 1) && !dowDays) {
    return `Le ${Number(dom) === 1 ? '1er' : Number(dom)} de chaque mois ${at}${months}`
  }
  return `Expression personnalisée ${at} : voir l’aperçu des prochaines exécutions`
}

// ------------------------------------------------------------------ fuseaux horaires et dates

export const browserTimezone = (() => {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' } catch { return 'UTC' }
})()

const FALLBACK_TIMEZONES = ['UTC', 'Africa/Casablanca', 'Europe/Paris', 'Europe/London', 'Europe/Madrid', 'America/New_York',
  'America/Montreal', 'Asia/Dubai', 'Asia/Tokyo']

/** Fuseaux IANA proposés (celui du navigateur en tête). */
export function timezoneOptions(): string[] {
  let all: string[] = FALLBACK_TIMEZONES
  try {
    const supported = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone')
    if (supported && supported.length > 0) all = supported.includes('UTC') ? supported : ['UTC', ...supported]
  } catch { /* liste de repli */ }
  return [browserTimezone, ...all.filter((z) => z !== browserTimezone)]
}

/** Composantes date / heure d'un instant dans un fuseau. */
function partsIn(date: Date, timezone: string) {
  const fmt = new Intl.DateTimeFormat('en-GB', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  })
  const p: Record<string, number> = {}
  for (const part of fmt.formatToParts(date)) if (part.type !== 'literal') p[part.type] = Number(part.value)
  return p as { year: number; month: number; day: number; hour: number; minute: number; second: number }
}

/** Décalage (ms) du fuseau par rapport à UTC à cet instant. */
function offsetMs(date: Date, timezone: string): number {
  const p = partsIn(date, timezone)
  return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - Math.floor(date.getTime() / 1000) * 1000
}

/** Heure de mur (aaaa-mm-jj, HH:MM) dans un fuseau -> instant ISO (UTC). */
export function zonedToIso(isoDay: string, time: string, timezone: string): string | null {
  const d = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDay)
  const t = parseTime(time)
  if (!d || !t) return null
  const wall = Date.UTC(Number(d[1]), Number(d[2]) - 1, Number(d[3]), t[0], t[1])
  try {
    let guess = wall - offsetMs(new Date(wall), timezone)
    guess = wall - offsetMs(new Date(guess), timezone)
    return new Date(guess).toISOString()
  } catch {
    return null
  }
}

/** Instant ISO -> { aaaa-mm-jj, HH:MM } dans un fuseau. */
export function isoToZoned(iso: string, timezone: string): { day: string; time: string } {
  const p = partsIn(new Date(iso), timezone)
  return { day: `${p.year}-${pad(p.month)}-${pad(p.day)}`, time: `${pad(p.hour)}:${pad(p.minute)}` }
}

/** « mar. 7 oct. 2026 à 02:00 » dans le fuseau donné. */
export function formatInZone(iso: string, timezone: string): string {
  const d = new Date(iso)
  try {
    const day = d.toLocaleDateString('fr-FR', { timeZone: timezone, weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
    const time = d.toLocaleTimeString('fr-FR', { timeZone: timezone, hour: '2-digit', minute: '2-digit' })
    return `${day} à ${time}`
  } catch {
    return d.toISOString()
  }
}

/** « 07/10/2026 02:00 » -> { day: '2026-10-07', time: '02:00' } ; null si invalide. */
export function parseFrDateTime(text: string): { day: string; time: string } | null {
  const m = /^(\d{2})\/(\d{2})\/(\d{4}) (\d{2}):(\d{2})$/.exec(text.trim())
  if (!m) return null
  const [, dd, mm, yyyy, hh, mi] = m
  const date = new Date(Date.UTC(Number(yyyy), Number(mm) - 1, Number(dd)))
  if (date.getUTCDate() !== Number(dd) || date.getUTCMonth() !== Number(mm) - 1) return null
  if (!parseTime(`${hh}:${mi}`)) return null
  return { day: `${yyyy}-${mm}-${dd}`, time: `${hh}:${mi}` }
}

/** Masque de saisie « jj/mm/aaaa hh:mm » (chiffres seulement, séparateurs ajoutés). */
export function maskFrDateTime(raw: string): string {
  const digits = raw.replace(/\D/g, '').slice(0, 12)
  let out = ''
  for (let i = 0; i < digits.length; i++) {
    if (i === 2 || i === 4) out += '/'
    if (i === 8) out += ' '
    if (i === 10) out += ':'
    out += digits[i]
  }
  return out
}

export function formatFrDay(isoDay: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDay)
  return m ? `${m[3]}/${m[2]}/${m[1]}` : isoDay
}

// ------------------------------------------------------------------ affichage d'une planification

/** Fréquence en français pour la liste : « Tous les jours à 02:00 », « Une fois, le 07/10/2026 à 02:00 ». */
export function describeSchedule(s: Pick<BackendScheduledExecutionResponse, 'scheduleType' | 'cronExpression' | 'runAt' | 'timezone' | 'endAt'>): string {
  if (s.scheduleType === 'ONE_TIME') {
    if (!s.runAt) return 'Une seule fois'
    const z = isoToZoned(s.runAt, s.timezone)
    return `Une fois, le ${formatFrDay(z.day)} à ${z.time}`
  }
  const base = (s.cronExpression && describeCron(s.cronExpression)) || `CRON ${s.cronExpression ?? ''}`
  if (!s.endAt) return base
  return `${base} · jusqu’au ${formatFrDay(isoToZoned(s.endAt, s.timezone).day)}`
}

export const STATE_LABEL: Record<BackendScheduleState, string> = {
  ACTIVE: 'Active',
  PAUSED: 'En pause',
  SUSPENDED: 'Suspendue',
  COMPLETED: 'Terminée',
  EXPIRED: 'Expirée',
}

export const STATE_PILL: Record<BackendScheduleState, string> = {
  ACTIVE: 'success',
  PAUSED: 'warning',
  SUSPENDED: 'danger',
  COMPLETED: 'neutral',
  EXPIRED: 'neutral',
}

export const STATES: BackendScheduleState[] = ['ACTIVE', 'PAUSED', 'SUSPENDED', 'COMPLETED', 'EXPIRED']

export const OUTCOME_LABEL: Record<BackendScheduledRunOutcome, string> = {
  SKIPPED_RUNNING: 'Ignorée',
  SKIPPED_CAPACITY: 'Ignorée',
  MISSED: 'Manquée',
  FAILED: 'Non lancée',
}

/** Écart de P95 : « ↑ +35 % » (plus lent), « ↓ −12 % » (plus rapide), ton selon le seuil de régression
 * réglable (Paramètres > Système, 20 % par défaut) — le même que le Dashboard et les notifications. */
export function p95Change(changePercent: number | null | undefined, threshold = 20): { text: string; tone: 'worse' | 'better' | 'same' } | null {
  if (changePercent == null || !Number.isFinite(changePercent)) return null
  const abs = Math.abs(changePercent).toLocaleString('fr-FR', { maximumFractionDigits: 1 })
  if (changePercent > threshold) return { text: `↑ +${abs} %`, tone: 'worse' }
  if (changePercent < -threshold) return { text: `↓ −${abs} %`, tone: 'better' }
  return { text: `${changePercent >= 0 ? '+' : '−'}${abs} %`, tone: 'same' }
}
