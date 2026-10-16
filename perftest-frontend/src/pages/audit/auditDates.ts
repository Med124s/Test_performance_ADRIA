import { dayEndIso, dayStartIso } from '../executions/executionsModel'
/** « jj/mm/aaaa » -> début ou fin de ce jour dans le fuseau de l'utilisateur, ou null si la date n'existe pas. */
export function parseFrenchDate(text: string, endOfDay = false): Date | null {
  const m = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(text.trim())
  if (!m) return null
  const day = Number(m[1])
  const month = Number(m[2])
  const year = Number(m[3])
  const check = new Date(Date.UTC(year, month - 1, day))
  if (check.getUTCFullYear() !== year || check.getUTCMonth() !== month - 1 || check.getUTCDate() !== day) return null
  const isoDay = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
  const iso = endOfDay ? dayEndIso(isoDay) : dayStartIso(isoDay)
  return iso ? new Date(iso) : null
}
