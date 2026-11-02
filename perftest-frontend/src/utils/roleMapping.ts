// Correspondance entre les rôles du frontend (libellés français) et les rôles du backend.

import type { UserRole } from '../context/AuthContext'

export type BackendRole = 'ROLE_SUPER_ADMIN' | 'ROLE_PERFORMANCE_ENGINEER' | 'ROLE_VIEWER'

const FRONTEND_TO_BACKEND: Record<UserRole, BackendRole> = {
  Visiteur: 'ROLE_VIEWER',
  Testeur: 'ROLE_PERFORMANCE_ENGINEER',
  Admin: 'ROLE_SUPER_ADMIN',
}

const BACKEND_TO_FRONTEND: Record<BackendRole, UserRole> = {
  ROLE_VIEWER: 'Visiteur',
  ROLE_PERFORMANCE_ENGINEER: 'Testeur',
  ROLE_SUPER_ADMIN: 'Admin',
}

export function frontendRoleToBackendRole(role: UserRole): BackendRole {
  return FRONTEND_TO_BACKEND[role]
}

/** Rôle du frontend correspondant aux rôles du jeton ; le plus privilégié s'il y en a plusieurs. */
export function backendRolesToFrontendRole(roles: string[]): UserRole | null {
  if (roles.includes('ROLE_SUPER_ADMIN')) return 'Admin'
  if (roles.includes('ROLE_PERFORMANCE_ENGINEER')) return 'Testeur'
  if (roles.includes('ROLE_VIEWER')) return 'Visiteur'
  return null
}
