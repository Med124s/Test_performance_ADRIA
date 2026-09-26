import { describe, expect, it } from 'vitest'
import { backendRolesToFrontendRole, frontendRoleToBackendRole } from './roleMapping'

describe('frontendRoleToBackendRole', () => {
  it('traduit chaque rôle frontend vers son rôle Keycloak réel', () => {
    expect(frontendRoleToBackendRole('Visiteur')).toBe('ROLE_VIEWER')
    expect(frontendRoleToBackendRole('Testeur')).toBe('ROLE_PERFORMANCE_ENGINEER')
    expect(frontendRoleToBackendRole('Admin')).toBe('ROLE_SUPER_ADMIN')
  })
})

describe('backendRolesToFrontendRole', () => {
  it('choisit le rôle le plus privilégié quand un JWT porte plusieurs rôles réalm', () => {
    expect(backendRolesToFrontendRole(['ROLE_VIEWER', 'ROLE_SUPER_ADMIN'])).toBe('Admin')
    expect(backendRolesToFrontendRole(['ROLE_VIEWER', 'ROLE_PERFORMANCE_ENGINEER'])).toBe('Testeur')
  })

  it('reconnaît un seul rôle connu', () => {
    expect(backendRolesToFrontendRole(['ROLE_VIEWER'])).toBe('Visiteur')
  })

  it('renvoie null si aucun des 3 rôles connus n\'est présent (jamais un rôle par défaut inventé)', () => {
    expect(backendRolesToFrontendRole([])).toBeNull()
    expect(backendRolesToFrontendRole(['offline_access', 'uma_authorization'])).toBeNull()
  })
})
