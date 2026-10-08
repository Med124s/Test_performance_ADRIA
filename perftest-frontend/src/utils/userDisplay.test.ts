import { describe, expect, it } from 'vitest'
import { avatarTone, AVATAR_TONES, fullDate, initialsOf, relativeTime } from './userDisplay'
import { appRoleFromRawRoles, roleLabelFromRawRoles, APP_ROLES } from './appRoles'

describe('Initiales et couleur d’avatar', () => {
  it('initiales calculées sur le nom affiché (plus « MC » pour « Admin Test »)', () => {
    expect(initialsOf('Admin Test')).toBe('AT')
    expect(initialsOf('viewer-test')).toBe('VT')
    expect(initialsOf('Jean Pierre Martin')).toBe('JM')
    expect(initialsOf('lina')).toBe('LI')
    expect(initialsOf('stagiaire.2025')).toBe('ST')
    expect(initialsOf('Élodie Ancienne')).toBe('ÉA')
    expect(initialsOf('2025')).toBe('20')
    expect(initialsOf('  ')).toBe('?')
  })

  it('couleur stable : même identifiant -> même teinte, dans la palette', () => {
    const ids = ['u-admin', 'u-eng', 'u-view', '756a64ce-0f11-4a18-b485-b2e04fe47b88']
    for (const id of ids) {
      expect(avatarTone(id)).toBe(avatarTone(id))
      expect(avatarTone(id)).toBeGreaterThanOrEqual(0)
      expect(avatarTone(id)).toBeLessThan(AVATAR_TONES)
    }
    expect(new Set(ids.map(avatarTone)).size).toBeGreaterThan(1)
  })
})

describe('Dates', () => {
  const now = Date.parse('2026-10-07T16:00:00Z')
  it('relative en français, null si absente', () => {
    expect(relativeTime('2026-10-07T15:59:30Z', now)).toBe('à l’instant')
    expect(relativeTime('2026-10-07T15:55:00Z', now)).toBe('il y a 5 minutes')
    expect(relativeTime('2026-10-06T14:00:00Z', now)).toBe('hier')
    expect(relativeTime('2026-09-01T10:00:00Z', now)).toBe('le mois dernier')
    expect(relativeTime(null, now)).toBeNull()
    expect(relativeTime('pas une date', now)).toBeNull()
  })
  it('complète au survol', () => {
    expect(fullDate('2026-10-07T14:08:00Z')).toMatch(/^7 octobre 2026 à \d{2}:08$/)
    expect(fullDate(undefined)).toBeNull()
  })
})

describe('Rôles en français', () => {
  it('libellés, du plus au moins privilégié, jamais le code technique', () => {
    expect(APP_ROLES.map((r) => r.label)).toEqual(['Administrateur', 'Ingénieur performance', 'Lecteur'])
    expect(roleLabelFromRawRoles(['offline_access', 'ROLE_SUPER_ADMIN'])).toBe('Administrateur')
    expect(roleLabelFromRawRoles(['ROLE_VIEWER', 'ROLE_PERFORMANCE_ENGINEER'])).toBe('Ingénieur performance')
    expect(roleLabelFromRawRoles(['ROLE_VIEWER'])).toBe('Lecteur')
    expect(roleLabelFromRawRoles([])).toBe('Aucun rôle')
    expect(appRoleFromRawRoles(['ROLE_VIEWER'])).toBe('VIEWER')
  })
})
