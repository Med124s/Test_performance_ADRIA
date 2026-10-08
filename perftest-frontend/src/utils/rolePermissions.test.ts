import { describe, expect, it } from 'vitest'
import { ROLE_PERMISSIONS } from './rolePermissions'
import type { BackendAppRole } from '../types/backendContracts'

// Code Java réel des contrôleurs backend, relu à chaque exécution du test.
const sources = import.meta.glob('../../../backend/src/main/java/com/loadpilot/backend/controller/*.java',
  { query: '?raw', import: 'default', eager: true }) as Record<string, string>

const ALL: BackendAppRole[] = ['SUPER_ADMIN', 'PERFORMANCE_ENGINEER', 'VIEWER']

function controllerSource(name: string): string {
  const entry = Object.entries(sources).find(([path]) => path.endsWith(`/${name}.java`))
  if (!entry) throw new Error(`Contrôleur introuvable : ${name}`)
  return entry[1]
}

function rolesOf(preAuthorize: string): BackendAppRole[] {
  return [...preAuthorize.matchAll(/'([A-Z_]+)'/g)].map((m) => m[1] as BackendAppRole)
}

/** Rôles autorisés par le backend pour l'endpoint (annotation de méthode, sinon de classe, sinon tout utilisateur connecté). */
function backendRoles(controller: string, mapping: string): BackendAppRole[] {
  const lines = controllerSource(controller).split(/\r?\n/)
  const classLine = lines.findIndex((l) => /^public class /.test(l))
  const matches = lines.flatMap((l, i) => (l.trim() === mapping && i > classLine ? [i] : []))
  expect(matches, `${controller} : « ${mapping} » doit apparaître une seule fois`).toHaveLength(1)
  // Bloc d'annotations de la méthode : jusqu'à sa signature (vers le bas) et jusqu'à la fin de la méthode précédente (vers le haut).
  let start = matches[0]
  while (start > 0 && !/[;}{]\s*$/.test(lines[start - 1]) && lines[start - 1].trim() !== '') start--
  let end = matches[0]
  while (end < lines.length && !/\bpublic\b/.test(lines[end])) end++
  const block = lines.slice(start, end + 1).join('\n')
  const methodRule = block.match(/@PreAuthorize\("([^"]+)"\)/)
  if (methodRule) return rolesOf(methodRule[1])
  const classRule = lines.slice(0, classLine).join('\n').match(/@PreAuthorize\("([^"]+)"\)/)
  return classRule ? rolesOf(classRule[1]) : ALL
}

describe('Tableau des permissions (onglet Rôles) = règles réelles du backend', () => {
  it('les contrôleurs backend sont bien lus (règle de méthode, de classe, ou aucune)', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(10)
    expect(backendRoles('ScenarioController', '@DeleteMapping("/{id}")')).toEqual(['SUPER_ADMIN'])
    expect(backendRoles('ScenarioController', '@PostMapping("/{id}/archive")').sort()).toEqual(['PERFORMANCE_ENGINEER', 'SUPER_ADMIN'])
    expect(backendRoles('UserController', '@PutMapping("/{id}/status")')).toEqual(['SUPER_ADMIN'])
    expect(backendRoles('DashboardController', '@GetMapping("/overview")')).toEqual(ALL)
  })

  for (const permission of ROLE_PERMISSIONS) {
    it(`« ${permission.label} »`, () => {
      expect(permission.evidence.length).toBeGreaterThan(0)
      for (const { controller, mapping } of permission.evidence) {
        expect([...backendRoles(controller, mapping)].sort(), `${controller} ${mapping}`).toEqual([...permission.roles].sort())
      }
    })
  }
})
