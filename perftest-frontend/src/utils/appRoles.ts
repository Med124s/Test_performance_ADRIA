// ============================================================
// Les 3 rôles applicatifs réels (Keycloak ROLE_SUPER_ADMIN /
// ROLE_PERFORMANCE_ENGINEER / ROLE_VIEWER) présentés en français, avec une
// icône et une couleur propres. Source unique pour toutes les pages
// (Utilisateurs et rôles, barre latérale, en-tête) : jamais le code brut
// SUPER_ADMIN affiché à l'utilisateur.
// ============================================================

import type { BackendAppRole } from '../types/backendContracts'

export interface AppRoleMeta {
  role: BackendAppRole
  label: string
  /** Classe Bootstrap Icons. */
  icon: string
  /** Suffixe des classes de couleur (.ur-role--admin, etc.). */
  tone: 'admin' | 'engineer' | 'viewer'
  /** Une phrase, pour le choix du rôle. */
  summary: string
  /** Description complète, pour les cartes de l'onglet Rôles. */
  description: string
}

/** Du plus au moins privilégié. */
export const APP_ROLES: AppRoleMeta[] = [
  {
    role: 'SUPER_ADMIN',
    label: 'Administrateur',
    icon: 'bi-shield-fill-check',
    tone: 'admin',
    summary: 'Tous les droits, y compris la gestion des utilisateurs et les suppressions définitives.',
    description: 'Gère les utilisateurs, les rôles, la suppression définitive des données et la configuration système, en plus des droits de l’ingénieur performance.',
  },
  {
    role: 'PERFORMANCE_ENGINEER',
    label: 'Ingénieur performance',
    icon: 'bi-speedometer2',
    tone: 'engineer',
    summary: 'Crée les scénarios, lance, planifie et analyse les tests.',
    description: 'Crée, configure et exécute les tests de performance, et gère les exécutions.',
  },
  {
    role: 'VIEWER',
    label: 'Lecteur',
    icon: 'bi-eye',
    tone: 'viewer',
    summary: 'Consulte les tableaux de bord, exécutions et rapports, sans rien modifier.',
    description: 'Accès en lecture seule à l’ensemble de l’application. Peut gérer son profil et ses notifications.',
  },
]

const BY_ROLE = Object.fromEntries(APP_ROLES.map((r) => [r.role, r])) as Record<BackendAppRole, AppRoleMeta>

export function roleMeta(role: BackendAppRole): AppRoleMeta {
  return BY_ROLE[role]
}

/** Rôle applicatif le plus privilégié parmi les rôles techniques du jeton (ROLE_…), ou null. */
export function appRoleFromRawRoles(rawRoles: string[]): BackendAppRole | null {
  for (const meta of APP_ROLES) {
    if (rawRoles.includes(`ROLE_${meta.role}`)) return meta.role
  }
  return null
}

/** Libellé français du rôle de l'utilisateur connecté (barre latérale, en-tête). */
export function roleLabelFromRawRoles(rawRoles: string[]): string {
  const role = appRoleFromRawRoles(rawRoles)
  return role ? BY_ROLE[role].label : 'Aucun rôle'
}
