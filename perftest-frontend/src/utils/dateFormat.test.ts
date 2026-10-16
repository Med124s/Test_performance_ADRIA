import { describe, expect, it } from 'vitest'
import { formatDate } from './dateFormat'
import { inUserZone, setUserTimezone } from './userTimezone'

// Fonction commune (correction P9) : même résultat que les anciennes copies locales des pages
// Exécutions, Scénarios, Applications, Notifications (heure:minute) et Détail d'exécution (avec secondes).

/** Anciennes implémentations, recopiées telles quelles pour la comparaison. */
const oldFormatDate = (iso: string) => {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleDateString('fr-FR', inUserZone()) + ' ' + d.toLocaleTimeString('fr-FR', inUserZone({ hour: '2-digit', minute: '2-digit' }))
}
const oldFormatDateWithSeconds = (iso: string) => {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleDateString('fr-FR', inUserZone()) + ' ' + d.toLocaleTimeString('fr-FR', inUserZone({ hour: '2-digit', minute: '2-digit', second: '2-digit' }))
}

const SAMPLES = ['2026-10-11T08:00:15Z', '2026-01-01T00:00:00Z', '2026-12-31T23:59:59.999Z', '2026-03-29T01:30:00Z']

describe('formatDate (commune)', () => {
  for (const zone of [null, 'Africa/Casablanca', 'America/New_York', 'Asia/Tokyo']) {
    it(`identique aux anciennes copies — fuseau ${zone ?? 'du navigateur'}`, () => {
      setUserTimezone(zone)
      for (const iso of SAMPLES) {
        expect(formatDate(iso)).toBe(oldFormatDate(iso))
        expect(formatDate(iso, { seconds: true })).toBe(oldFormatDateWithSeconds(iso))
      }
    })
  }

  it('format attendu ; date illisible renvoyée telle quelle', () => {
    setUserTimezone('UTC')
    expect(formatDate('2026-10-11T08:00:15Z')).toBe('11/10/2026 08:00')
    expect(formatDate('2026-10-11T08:00:15Z', { seconds: true })).toBe('11/10/2026 08:00:15')
    expect(formatDate('pas une date')).toBe('pas une date')
    expect(formatDate('')).toBe('')
  })
})
