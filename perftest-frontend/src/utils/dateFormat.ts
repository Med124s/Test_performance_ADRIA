import { inUserZone } from './userTimezone'

/** Date et heure courtes dans le fuseau de l'utilisateur : « 11/10/2026 08:00 » (avec secondes si `seconds`). */
export function formatDate(iso: string, options: { seconds?: boolean } = {}): string {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const time: Intl.DateTimeFormatOptions = options.seconds
    ? { hour: '2-digit', minute: '2-digit', second: '2-digit' }
    : { hour: '2-digit', minute: '2-digit' }
  return d.toLocaleDateString('fr-FR', inUserZone()) + ' ' + d.toLocaleTimeString('fr-FR', inUserZone(time))
}
