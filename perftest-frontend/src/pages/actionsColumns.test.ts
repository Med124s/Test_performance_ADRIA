import { describe, expect, it } from 'vitest'

// Tout tableau avec une colonne de boutons par ligne (voir, rapport, modifier, relancer,
// supprimer, menu « ⋯ »…) a un en-tête visible « Actions ».

const sources = import.meta.glob(['./**/*.tsx', '!./**/*.test.tsx'], { query: '?raw', import: 'default', eager: true }) as Record<string, string>

const WITH_ACTIONS = [
  './Applications.tsx',
  './Scenarios.tsx',
  './Executions.tsx', // Exécutions et Rapports (même tableau)
  './ScheduledExecutions.tsx',
  './scheduling/ScheduleHistory.tsx',
  './UsersRoles.tsx',
  './AuditLogs.tsx',
  './execution-detail/StepRequestsExplorer.tsx',
  './execution-report/ExtremesPanel.tsx',
]

describe('Colonne « Actions » des tableaux', () => {
  it.each(WITH_ACTIONS)('%s : en-tête visible « Actions »', (file) => {
    const code = sources[file]
    expect(code, file).toBeDefined()
    expect(code).toMatch(/<th\b[^>]*>Actions<\/th>/)
    expect(code).not.toMatch(/visually-hidden">(Actions|Détail)<\/span><\/th>/)
  })

  it('aucun tableau n’a d’en-tête de colonne vide au-dessus de boutons', () => {
    // En-têtes vides restants autorisés : coin des lignes de comparaison et pastille d'état
    // du suivi en direct (aucun bouton dans ces colonnes).
    const allowed: Record<string, number> = { './ExecutionCompare.tsx': 1, './ExecutionLive.tsx': 1 }
    for (const [file, code] of Object.entries(sources)) {
      const empty = (code.match(/<th(\s[^>]*)?><\/th>/g) ?? []).length
      expect(empty, file).toBe(allowed[file] ?? 0)
    }
  })
})
