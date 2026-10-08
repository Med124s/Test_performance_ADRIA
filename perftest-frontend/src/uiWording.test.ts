import { describe, expect, it } from 'vitest'

// Le mot « Keycloak » ne doit apparaître dans aucun texte visible par l'utilisateur
// (libellés, messages d'erreur, infobulles, notifications, page de connexion).
// Il reste autorisé dans le code (noms de fichiers, variables, types), la
// configuration et les commentaires.

const sources = import.meta.glob(['./**/*.{ts,tsx}', '!./**/*.test.{ts,tsx}', '!./setupTests.ts'],
  { query: '?raw', import: 'default', eager: true }) as Record<string, string>
const themeMessages = import.meta.glob('../../keycloak/themes/loadpilot/login/messages/*.properties',
  { query: '?raw', import: 'default', eager: true }) as Record<string, string>
const themeTemplates = import.meta.glob('../../keycloak/themes/loadpilot/login/*.ftl',
  { query: '?raw', import: 'default', eager: true }) as Record<string, string>

/** Retire commentaires et identifiants de code contenant « Keycloak » (loginWithKeycloak, KeycloakJwtClaims…). */
function visibleCandidates(code: string): string {
  return code
    .replace(/\{\/\*[\s\S]*?\*\/\}/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:'"`])\/\/.*$/gm, '$1')
    .replace(/^\s*import .*$/gm, '')
    .replace(/[A-Za-z_$][\w$]*Keycloak[\w$]*|Keycloak[A-Za-z_$][\w$]*/g, '')
}

describe('Aucune mention « Keycloak » dans l’interface', () => {
  it('les sources du frontend sont bien lues', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(50)
    expect(visibleCandidates("const a = loginWithKeycloak() // Keycloak\nconst b = 'géré par Keycloak'")).toContain('géré par Keycloak')
  })

  it('frontend : aucun texte affiché ne contient « Keycloak »', () => {
    const offenders = Object.entries(sources).flatMap(([file, code]) =>
      visibleCandidates(code).split('\n').filter((l) => /Keycloak/.test(l)).map((l) => `${file}: ${l.trim()}`))
    expect(offenders).toEqual([])
  })

  it('page de connexion : textes (hors commentaires) et gabarits sans « Keycloak »', () => {
    expect(Object.keys(themeMessages).length).toBe(2)
    for (const [file, text] of Object.entries(themeMessages)) {
      const lines = text.split(/\r?\n/).filter((l) => l.trim() && !l.trim().startsWith('#'))
      expect(lines.filter((l) => /keycloak/i.test(l)), file).toEqual([])
    }
    for (const [file, ftl] of Object.entries(themeTemplates)) {
      const withoutComments = ftl.replace(/<#--[\s\S]*?-->/g, '')
        .replace(/id="keycloak-bg"/g, '')
      expect(withoutComments.match(/Keycloak/g), file).toBeNull()
    }
  })
})
