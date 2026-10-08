// ============================================================
// Ce que chaque rôle peut faire, tel que le backend l'applique réellement.
// Chaque ligne cite les endpoints dont elle découle (`evidence`) : le test
// rolePermissions.test.ts relit le code Java des contrôleurs et vérifie que
// leurs annotations @PreAuthorize donnent exactement ces rôles. Si une règle
// change côté backend, ce test échoue tant que ce tableau n'est pas mis à jour.
// ============================================================

import type { BackendAppRole } from '../types/backendContracts'

export interface PermissionEvidence {
  /** Nom du fichier contrôleur (backend/src/main/java/.../controller). */
  controller: string
  /** Ligne d'annotation exacte de l'endpoint, ex. '@DeleteMapping("/{id}")'. */
  mapping: string
}

export interface RolePermission {
  id: string
  label: string
  icon: string
  roles: BackendAppRole[]
  evidence: PermissionEvidence[]
}

const ALL: BackendAppRole[] = ['SUPER_ADMIN', 'PERFORMANCE_ENGINEER', 'VIEWER']
const EDITORS: BackendAppRole[] = ['SUPER_ADMIN', 'PERFORMANCE_ENGINEER']
const ADMIN: BackendAppRole[] = ['SUPER_ADMIN']

export const ROLE_PERMISSIONS: RolePermission[] = [
  {
    id: 'view', label: 'Consulter le tableau de bord, les applications, scénarios, exécutions et rapports', icon: 'bi-eye', roles: ALL,
    evidence: [
      { controller: 'DashboardController', mapping: '@GetMapping("/overview")' },
      { controller: 'ApplicationController', mapping: '@GetMapping' },
      { controller: 'ScenarioController', mapping: '@GetMapping' },
      { controller: 'ExecutionController', mapping: '@GetMapping("/{id}/report")' },
      { controller: 'ScheduledExecutionController', mapping: '@GetMapping' },
    ],
  },
  {
    id: 'edit', label: 'Créer et modifier les applications, scénarios et étapes', icon: 'bi-pencil-square', roles: EDITORS,
    evidence: [
      { controller: 'ApplicationController', mapping: '@PostMapping' },
      { controller: 'ApplicationController', mapping: '@PutMapping("/{id}")' },
      { controller: 'ScenarioController', mapping: '@PostMapping' },
      { controller: 'ScenarioController', mapping: '@PutMapping("/{id}")' },
      { controller: 'StepController', mapping: '@PostMapping' },
    ],
  },
  {
    id: 'run', label: 'Lancer, mettre en pause et annuler un test', icon: 'bi-play-circle', roles: EDITORS,
    evidence: [
      { controller: 'ExecutionController', mapping: '@PostMapping' },
      { controller: 'ExecutionController', mapping: '@PostMapping("/{id}/pause")' },
      { controller: 'ExecutionController', mapping: '@PostMapping("/{id}/cancel")' },
      { controller: 'TestRunController', mapping: '@PostMapping("/api/scenarios/{id}/test-run")' },
    ],
  },
  {
    id: 'schedule', label: 'Planifier des exécutions', icon: 'bi-calendar-event', roles: EDITORS,
    evidence: [
      { controller: 'ScheduledExecutionController', mapping: '@PostMapping' },
      { controller: 'ScheduledExecutionController', mapping: '@PatchMapping("/{id}/disable")' },
    ],
  },
  {
    id: 'archive', label: 'Archiver et restaurer des applications et scénarios', icon: 'bi-archive', roles: EDITORS,
    evidence: [
      { controller: 'ApplicationController', mapping: '@PostMapping("/{id}/archive")' },
      { controller: 'ScenarioController', mapping: '@PostMapping("/{id}/archive")' },
    ],
  },
  {
    id: 'delete-executions', label: 'Supprimer des exécutions', icon: 'bi-trash3', roles: EDITORS,
    evidence: [{ controller: 'ExecutionController', mapping: '@DeleteMapping("/{id}")' }],
  },
  {
    id: 'delete-permanently', label: 'Supprimer définitivement une application ou un scénario', icon: 'bi-x-octagon', roles: ADMIN,
    evidence: [
      { controller: 'ApplicationController', mapping: '@DeleteMapping("/{id}")' },
      { controller: 'ScenarioController', mapping: '@DeleteMapping("/{id}")' },
    ],
  },
  {
    id: 'audit', label: 'Consulter et exporter le journal d’audit', icon: 'bi-journal-text', roles: ADMIN,
    evidence: [
      { controller: 'AuditLogController', mapping: '@GetMapping' },
      { controller: 'AuditLogController', mapping: '@GetMapping("/export")' },
    ],
  },
  {
    id: 'users', label: 'Gérer les utilisateurs et leurs rôles', icon: 'bi-people', roles: ADMIN,
    evidence: [
      { controller: 'UserController', mapping: '@GetMapping' },
      { controller: 'UserController', mapping: '@PostMapping' },
      { controller: 'UserController', mapping: '@PutMapping("/{id}/role")' },
    ],
  },
  {
    id: 'settings', label: 'Consulter les paramètres système', icon: 'bi-sliders', roles: ADMIN,
    evidence: [{ controller: 'SystemConfigController', mapping: '@GetMapping' }],
  },
  {
    id: 'profile', label: 'Gérer son profil et ses notifications', icon: 'bi-person-gear', roles: ALL,
    evidence: [
      { controller: 'ProfileController', mapping: '@PatchMapping' },
      { controller: 'NotificationPreferenceController', mapping: '@PatchMapping("/{type}")' },
    ],
  },
]
