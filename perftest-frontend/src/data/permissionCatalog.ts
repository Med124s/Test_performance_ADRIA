// ============================================================
// Catalogue statique des permissions disponibles, groupées par module.
// Source unique pour le rendu de la matrice de permissions (RolesCatalog)
// et pour compter/afficher les permissions d'un rôle. Chaîne de permission
// = `${module.id}.${action.key}` (ex: "applications.read").
// ============================================================

export interface PermissionAction {
  key: string
  label: string
}

export interface PermissionModule {
  id: string
  label: string
  icon: string
  actions: PermissionAction[]
}

const CRUD: PermissionAction[] = [
  { key: 'read', label: 'Lire' },
  { key: 'create', label: 'Créer' },
  { key: 'update', label: 'Modifier' },
  { key: 'delete', label: 'Supprimer' },
]

export const PERMISSION_MODULES: PermissionModule[] = [
  { id: 'applications', label: 'Applications', icon: 'bi-globe2', actions: CRUD },
  { id: 'scenarios', label: 'Scénarios', icon: 'bi-collection-play', actions: CRUD },
  {
    id: 'executions',
    label: 'Exécutions',
    icon: 'bi-play-circle',
    actions: [
      { key: 'read', label: 'Lire' },
      { key: 'launch', label: 'Lancer' },
      { key: 'stop', label: 'Arrêter' },
      { key: 'delete', label: 'Supprimer' },
    ],
  },
  { id: 'metriques', label: 'Métriques', icon: 'bi-graph-up', actions: [{ key: 'view', label: 'Voir' }] },
  {
    id: 'rapports',
    label: 'Rapports',
    icon: 'bi-file-earmark-text',
    actions: [
      { key: 'view', label: 'Voir' },
      { key: 'export', label: 'Exporter' },
    ],
  },
  { id: 'users', label: 'Utilisateurs', icon: 'bi-people', actions: CRUD },
  { id: 'roles', label: 'Rôles', icon: 'bi-shield-lock', actions: CRUD },
]

export function permissionKey(moduleId: string, actionKey: string): string {
  return `${moduleId}.${actionKey}`
}

export const ALL_PERMISSION_KEYS: string[] = PERMISSION_MODULES.flatMap((m) =>
  m.actions.map((a) => permissionKey(m.id, a.key))
)

export function findModuleLabel(permission: string): string {
  const [moduleId] = permission.split('.')
  return PERMISSION_MODULES.find((m) => m.id === moduleId)?.label ?? permission
}
