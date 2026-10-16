import { inUserZone } from './userTimezone'

/**
 * Date et heure courtes, en français, dans le fuseau de l'utilisateur : « 11/10/2026 08:00 »
 * (« 11/10/2026 08:00:15 » avec `seconds`). Une date illisible est renvoyée telle quelle.
 * Utilisée par les pages Exécutions, Scénarios, Applications, Notifications et Détail d'exécution.
 */
export function formatDate(iso: string, options: { seconds?: boolean } = {}): string {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const time: Intl.DateTimeFormatOptions = options.seconds
    ? { hour: '2-digit', minute: '2-digit', second: '2-digit' }
    : { hour: '2-digit', minute: '2-digit' }
  return d.toLocaleDateString('fr-FR', inUserZone()) + ' ' + d.toLocaleTimeString('fr-FR', inUserZone(time))
}
