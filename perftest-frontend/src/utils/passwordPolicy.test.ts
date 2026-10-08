import { describe, expect, it } from 'vitest'
import { checkPassword, generatePassword } from './passwordPolicy'
import type { BackendPasswordPolicy } from '../types/backendContracts'

const REALM: BackendPasswordPolicy = { minLength: 8, maxLength: 64, minUpperCase: 1, minLowerCase: 1, minDigits: 1, minSpecialChars: 0, notUsername: true, notEmail: true }
const failed = (pw: string, policy: BackendPasswordPolicy | null = REALM, id = { username: 'lina', email: 'lina@loadpilot.local' }) =>
  checkPassword(pw, policy, id).filter((r) => !r.ok).map((r) => r.id)

describe('Politique de mot de passe (mêmes règles que le serveur)', () => {
  it('règles en français, vérifiées une à une', () => {
    expect(checkPassword('', REALM).map((r) => r.label)).toEqual(['Au moins 8 caractères', 'Au moins 1 majuscule', 'Au moins 1 minuscule',
      'Au moins 1 chiffre', 'Au plus 64 caractères', 'Différent du nom d’utilisateur', 'Différent de l’adresse e-mail'])
    expect(failed('abc')).toEqual(['length', 'upper', 'digits'])
    expect(failed('Abcdefgh')).toEqual(['digits'])
    expect(failed('Abcdefg1')).toEqual([])
    expect(failed('A1' + 'b'.repeat(70))).toEqual(['max'])
  })

  it('différent du nom d’utilisateur et de l’e-mail (sans tenir compte de la casse)', () => {
    expect(failed('Lina.Dupont1', REALM, { username: 'lina.dupont1', email: '' })).toEqual(['username'])
    expect(failed('Lina@Loadpilot.local1', REALM, { username: 'x', email: 'lina@loadpilot.local1' })).toEqual(['email'])
  })

  it('caractères spéciaux comptés comme le serveur (tout sauf lettres, chiffres et « _ »)', () => {
    const special = { ...REALM, minSpecialChars: 2 }
    expect(failed('Abcdefg1_', special)).toContain('special')
    expect(failed('Abcdefg1!-', special)).toEqual([])
  })

  it('sans politique connue : seul « saisi » est exigé', () => {
    expect(checkPassword('', null)).toEqual([{ id: 'required', label: 'Saisissez un mot de passe', ok: false }])
    expect(failed('x', null)).toEqual([])
  })

  it('« Générer » : 16 caractères aléatoires, conformes, sans caractères ambigus', () => {
    const seen = new Set<string>()
    for (let i = 0; i < 50; i++) {
      const pw = generatePassword(REALM)
      expect(pw).toHaveLength(16)
      expect(failed(pw)).toEqual([])
      expect(pw).not.toMatch(/[lI1O0]/)
      seen.add(pw)
    }
    expect(seen.size).toBe(50)
    expect(generatePassword({ ...REALM, minLength: 24 })).toHaveLength(24)
    expect(generatePassword({ ...REALM, maxLength: 12 })).toHaveLength(12)
    expect(failed(generatePassword({ ...REALM, minSpecialChars: 3 }), { ...REALM, minSpecialChars: 3 })).toEqual([])
  })
})
