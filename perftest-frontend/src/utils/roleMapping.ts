// ============================================================
// Phase 16 — Correspondance entre les labels de rôle actuels du frontend
// (français, voir AuthContext.UserRole) et les rôles techniques réels du
// backend Spring Boot (voir SecurityConfig/JwtAuthConverter).
//
// Décision produit validée (Phase 16) :
//   Visiteur → ROLE_VIEWER
//   Testeur  → ROLE_PERFORMANCE_ENGINEER
//   Admin    → ROLE_SUPER_ADMIN
//
// AUCUN nouveau système de rôles : ces fonctions traduisent uniquement
// entre les deux vocabulaires déjà décidés (celui du backend fait foi côté
// autorisation réelle, voir @PreAuthorize/SecurityFilterChain).
// ============================================================

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

/**
 * Un jeton Keycloak réel peut porter PLUSIEURS rôles réalm à la fois. Le
 * frontend actuel ne modélise qu'un rôle unique par utilisateur (voir
 * `AuthUser.role`) : par prudence, le rôle le plus privilégié présent est
 * choisi (SUPER_ADMIN > PERFORMANCE_ENGINEER > VIEWER), plutôt que de
 * deviner arbitrairement. Renvoie `null` si aucun des 3 rôles backend
 * connus n'est présent (jamais un rôle par défaut inventé).
 */
export function backendRolesToFrontendRole(roles: string[]): UserRole | null {
  if (roles.includes('ROLE_SUPER_ADMIN')) return 'Admin'
  if (roles.includes('ROLE_PERFORMANCE_ENGINEER')) return 'Testeur'
  if (roles.includes('ROLE_VIEWER')) return 'Visiteur'
  return null
}
