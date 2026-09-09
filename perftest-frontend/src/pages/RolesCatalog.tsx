import { useState, useMemo, useEffect } from 'react'
import TopBar from '../components/TopBar'
import { loadJSON, saveJSON } from '../utils/persistentStore'
import { useApiList } from '../hooks/useApiResource'
import { rolesApi } from '../services/api/roles'
import { Role } from '../types'
import { PERMISSION_MODULES, ALL_PERMISSION_KEYS, permissionKey } from '../data/permissionCatalog'
import { firstError, validateRequired, validateMaxLength, NAME_MAX_LENGTH, DESCRIPTION_MAX_LENGTH } from '../utils/validation'

/* ============================================
   Rôles — catalogue réel (JSON Server, voir services/api/roles.ts).
   L'attribution rôle↔utilisateur (USER_ROLES) reste, elle, une maquette
   locale (localStorage) — seul le catalogue de rôles/permissions lui-même
   est devenu une vraie ressource persistée.
   ============================================ */

interface UserRoleAssignment {
  userId: number
  roleId: string
  date_attribution: string
}

interface UserItem {
  id: number
  name: string
  email: string
  avatar: string
}

/* ---- Mock data: USERS (minimal for reference) ---- */
const usersData: UserItem[] = [
  { id: 1, name: 'Admin User', email: 'admin@perftest.com', avatar: 'AU' },
  { id: 2, name: 'Test Manager', email: 'manager@perftest.com', avatar: 'TM' },
  { id: 3, name: 'Test Engineer', email: 'engineer@perftest.com', avatar: 'TE' },
  { id: 4, name: 'Read Only User', email: 'readonly@perftest.com', avatar: 'RO' },
  { id: 5, name: 'DevOps User', email: 'devops@perftest.com', avatar: 'DU' },
  { id: 6, name: 'Analyst User', email: 'analyst@perftest.com', avatar: 'AN' },
  { id: 7, name: 'Support User', email: 'support@perftest.com', avatar: 'SU' },
  { id: 8, name: 'John Doe', email: 'john.doe@perftest.com', avatar: 'JD' },
]

/* ---- Mock data: USER_ROLES (un seul rôle par utilisateur) — les ids de
   rôle correspondent aux ids seedés dans db.json ("1"=Admin, "2"=Testeur,
   "3"=Visiteur). ---- */
const initialUserRoles: UserRoleAssignment[] = [
  { userId: 1, roleId: '1', date_attribution: '15/01/2025' },
  { userId: 2, roleId: '2', date_attribution: '20/02/2025' },
  { userId: 3, roleId: '2', date_attribution: '10/03/2025' },
  { userId: 4, roleId: '3', date_attribution: '05/04/2025' },
  { userId: 5, roleId: '1', date_attribution: '12/04/2025' },
  { userId: 6, roleId: '3', date_attribution: '01/05/2025' },
  { userId: 7, roleId: '3', date_attribution: '18/05/2025' },
  { userId: 8, roleId: '2', date_attribution: '01/06/2025' },
]

/* ---- Helpers ---- */
function getRoleBadgeStyle(role: string) {
  switch (role) {
    case 'Admin':    return { bg: 'var(--pt-primary-light)', color: 'var(--pt-primary)' }
    case 'Testeur':  return { bg: 'var(--pt-success-light)', color: 'var(--pt-success)' }
    case 'Visiteur': return { bg: 'var(--pt-warning-light)', color: 'var(--pt-warning)' }
    default:         return { bg: '#F3F4F6', color: 'var(--pt-text-muted)' }
  }
}

/* ---- Reusable Action Button ---- */
function ActionBtn({ icon, title, onClick, danger }: { icon: string; title: string; onClick: () => void; danger?: boolean }) {
  return (
    <button
      style={{
        width: '32px', height: '32px', borderRadius: 'var(--pt-radius-sm)',
        border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: danger ? 'var(--pt-danger)' : 'var(--pt-text-muted)', cursor: 'pointer', flexShrink: 0,
        transition: 'all 0.15s ease',
      }}
      title={title}
      onClick={onClick}
      onMouseEnter={(e) => {
        e.currentTarget.style.borderColor = danger ? 'var(--pt-danger)' : 'var(--pt-primary)'
        e.currentTarget.style.color = danger ? 'var(--pt-danger)' : 'var(--pt-primary)'
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.borderColor = 'var(--pt-border)'
        e.currentTarget.style.color = danger ? 'var(--pt-danger)' : 'var(--pt-text-muted)'
      }}
    >
      <i className={`bi ${icon}`} style={{ fontSize: '14px' }}></i>
    </button>
  )
}

/* ============================================
   Component
   ============================================ */

export default function RolesCatalog() {
  const { data: roles, loading: rolesLoading, refetch: refetchRoles } = useApiList<Role>(() => rolesApi.getAll())
  const [userRoles, setUserRoles] = useState<UserRoleAssignment[]>(() =>
    loadJSON('pt_user_roles_v2', initialUserRoles)
  )
  const [searchQuery, setSearchQuery] = useState('')
  const [saving, setSaving] = useState(false)

  // L'attribution rôle↔utilisateur reste une maquette locale (non demandée
  // dans cette passe) — seul le catalogue de rôles est maintenant réel.
  useEffect(() => {
    saveJSON('pt_user_roles_v2', userRoles)
  }, [userRoles])

  // Create/Edit modal
  const [showModal, setShowModal] = useState(false)
  const [editingRole, setEditingRole] = useState<Role | null>(null)
  const [formName, setFormName] = useState('')
  const [formDescription, setFormDescription] = useState('')
  const [formPermissions, setFormPermissions] = useState<Set<string>>(new Set())
  const [touched, setTouched] = useState<{ name?: boolean }>({})
  const [attemptedSave, setAttemptedSave] = useState(false)

  // Delete modal
  const [showDeleteModal, setShowDeleteModal] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<Role | null>(null)

  /* ---- Validation ---- */
  const nameError = firstError(
    validateRequired(formName, 'Le nom du rôle'),
    validateMaxLength(formName, NAME_MAX_LENGTH, 'Le nom du rôle')
  )
  const descriptionError = formDescription ? validateMaxLength(formDescription, DESCRIPTION_MAX_LENGTH, 'La description') : null
  const permissionsError = formPermissions.size === 0 ? 'Sélectionnez au moins une permission.' : null
  const isFormValid = !nameError && !descriptionError && !permissionsError

  /* ---- Derived: user count per role ---- */
  function getUserCountForRole(roleId: string): number {
    return userRoles.filter((ur) => ur.roleId === roleId).length
  }

  function getUsersForRole(roleId: string): UserItem[] {
    const userIds = userRoles.filter((ur) => ur.roleId === roleId).map((ur) => ur.userId)
    return usersData.filter((u) => userIds.includes(u.id))
  }

  /* ---- Filtering ---- */
  const filteredRoles = useMemo(() => {
    return roles.filter(r => r.name.toLowerCase().includes(searchQuery.toLowerCase()) || (r.description ?? '').toLowerCase().includes(searchQuery.toLowerCase()))
  }, [roles, searchQuery])

  /* ---- CRUD actions ---- */
  function openCreateModal() {
    setEditingRole(null)
    setFormName('')
    setFormDescription('')
    setFormPermissions(new Set())
    setTouched({})
    setAttemptedSave(false)
    setShowModal(true)
  }
  function openEditModal(role: Role) {
    setEditingRole(role)
    setFormName(role.name)
    setFormDescription(role.description ?? '')
    setFormPermissions(new Set(role.permissions))
    setTouched({})
    setAttemptedSave(false)
    setShowModal(true)
  }
  function closeModal() {
    if (saving) return
    setShowModal(false)
  }

  function togglePermission(key: string) {
    setFormPermissions((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  function toggleSelectAll(checked: boolean) {
    setFormPermissions(checked ? new Set(ALL_PERMISSION_KEYS) : new Set())
  }

  async function handleSaveRole() {
    setTouched({ name: true })
    setAttemptedSave(true)
    if (!isFormValid || saving) return
    setSaving(true)
    try {
      const payload = {
        name: formName.trim(),
        description: formDescription.trim() || undefined,
        permissions: Array.from(formPermissions),
        updatedAt: new Date().toISOString(),
      }
      if (editingRole) {
        await rolesApi.update(editingRole.id, payload)
      } else {
        await rolesApi.create({ ...payload, createdAt: new Date().toISOString() })
      }
      await refetchRoles()
      setShowModal(false)
      setEditingRole(null)
    } finally {
      setSaving(false)
    }
  }

  function openDeleteModal(role: Role) { setDeleteTarget(role); setShowDeleteModal(true) }

  async function confirmDelete() {
    if (!deleteTarget || saving) return
    setSaving(true)
    try {
      await rolesApi.remove(deleteTarget.id)
      setUserRoles(prev => prev.filter(ur => ur.roleId !== deleteTarget.id))
      await refetchRoles()
      setShowDeleteModal(false)
      setDeleteTarget(null)
    } finally {
      setSaving(false)
    }
  }

  const rolesWithUsers = roles.filter(r => getUserCountForRole(r.id) > 0).length
  const rolesWithoutUsers = roles.length - rolesWithUsers

  return (
    <div className="pt-content">
      {/* Header */}
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Catalogue des rôles</h1>
          <p>Créez, modifiez et gérez les rôles et permissions disponibles dans le système</p>
        </div>
        <div className="d-flex align-items-center gap-3 flex-wrap">
          <button className="pt-btn-primary" onClick={openCreateModal}>
            <i className="bi bi-plus-lg me-1"></i>
            Nouveau rôle
          </button>
          <TopBar searchPlaceholder="Rechercher un rôle..." />
        </div>
      </div>

      {/* Stat Cards */}
      <div className="row g-3 mb-4">
        {[
          { label: 'Rôles totaux', value: roles.length, trend: 'Dans le catalogue', icon: 'bi-shield', color: 'blue', positive: false },
          { label: 'Rôles attribués', value: rolesWithUsers, trend: 'Actifs', icon: 'bi-check-circle', color: 'green', positive: true },
          { label: 'Rôles non attribués', value: rolesWithoutUsers, trend: 'Disponibles', icon: 'bi-shield-slash', color: 'orange', positive: false },
          { label: 'Attributions totales', value: userRoles.length, trend: 'USER_ROLES', icon: 'bi-link-45deg', color: 'purple', positive: false },
        ].map((card, i) => (
          <div key={i} className="col-12 col-sm-6 col-xl-3">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">{card.label}</div>
                  <div className="stat-value">{card.value}</div>
                  <div className={`stat-trend ${card.positive ? 'positive' : 'neutral'}`}>
                    {card.positive && <i className="bi bi-arrow-up me-1"></i>}{card.trend}
                  </div>
                </div>
                <div className={`stat-icon ${card.color}`}><i className={`bi ${card.icon}`}></i></div>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Search + Nouveau, above the list, left-aligned */}
      <div className="d-flex align-items-center gap-2 flex-wrap mb-3">
        <div className="pt-search" style={{ width: '300px', minWidth: '200px' }}>
          <i className="bi bi-search"></i>
          <input type="text" placeholder="Rechercher un rôle..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
        </div>
        <button className="pt-btn-primary" style={{ fontSize: '12.5px', padding: '0.5rem 0.75rem' }} onClick={openCreateModal}>
          <i className="bi bi-plus-lg"></i> Nouveau
        </button>
      </div>

      {/* Roles Table */}
      <div className="row g-3">
        <div className="col-12">
          <div className="pt-card" style={{ padding: 0 }}>
            {/* Table Header Controls */}
            <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
              <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Liste des rôles ({filteredRoles.length})</h6>
              {rolesLoading && <span className="text-muted" style={{ fontSize: '12px' }}><i className="bi bi-arrow-repeat pt-spin me-1"></i>Chargement...</span>}
            </div>

            {/* Table */}
            <div className="pt-table-wrapper">
              <table className="pt-table">
                <thead>
                  <tr>
                    <th style={{ width: '40px' }}>#</th>
                    <th>Nom du rôle</th>
                    <th>Description</th>
                    <th style={{ width: '220px' }}>Permissions</th>
                    <th style={{ textAlign: 'center', width: '120px' }}>Utilisateurs</th>
                    <th style={{ textAlign: 'right', width: '100px' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredRoles.length > 0 ? filteredRoles.map((role, index) => {
                    const badge = getRoleBadgeStyle(role.name)
                    const userCount = getUserCountForRole(role.id)
                    const moduleLabels = Array.from(new Set(role.permissions.map((p) => p.split('.')[0])))
                      .map((id) => PERMISSION_MODULES.find((m) => m.id === id)?.label ?? id)
                    const shownModules = moduleLabels.slice(0, 3)
                    const extraModules = moduleLabels.length - shownModules.length
                    return (
                      <tr key={role.id}>
                        <td style={{ color: 'var(--pt-text-muted)', fontSize: '12px' }}>{index + 1}</td>
                        <td>
                          <span className="pt-pill" style={{ background: badge.bg, color: badge.color, fontSize: '12px' }}>{role.name}</span>
                        </td>
                        <td style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>{role.description || '—'}</td>
                        <td>
                          <div className="d-flex flex-wrap align-items-center gap-1">
                            <span style={{ fontSize: '11.5px', fontWeight: 700, color: 'var(--pt-text)' }}>
                              {role.permissions.length}/{ALL_PERMISSION_KEYS.length}
                            </span>
                            {shownModules.map((label) => (
                              <span key={label} className="pt-pill neutral" style={{ fontSize: '10.5px' }}>{label}</span>
                            ))}
                            {extraModules > 0 && (
                              <span className="pt-pill neutral" style={{ fontSize: '10.5px' }}>+{extraModules}</span>
                            )}
                          </div>
                        </td>
                        <td style={{ textAlign: 'center' }}>
                          {userCount > 0 ? (
                            <span className="pt-pill info" style={{ fontSize: '11.5px' }} title={`${userCount} utilisateur(s) ont ce rôle`}>
                              <i className="bi bi-people-fill me-1" style={{ fontSize: '10px' }}></i>{userCount}
                            </span>
                          ) : (
                            <span style={{ fontSize: '11.5px', color: 'var(--pt-text-light)' }}>—</span>
                          )}
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <div className="d-flex justify-content-end gap-1">
                            <ActionBtn icon="bi-pencil" title="Éditer" onClick={() => openEditModal(role)} />
                            <ActionBtn icon="bi-trash" title="Supprimer" onClick={() => openDeleteModal(role)} danger />
                          </div>
                        </td>
                      </tr>
                    )
                  }) : (
                    <tr>
                      <td colSpan={6} style={{ textAlign: 'center', padding: '3rem', color: 'var(--pt-text-muted)' }}>
                        <i className="bi bi-inbox" style={{ fontSize: '32px', display: 'block', marginBottom: '8px', opacity: 0.5 }}></i>
                        {rolesLoading ? 'Chargement des rôles...' : 'Aucun rôle trouvé. Cliquez sur "Nouveau rôle" pour en créer un.'}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            {/* Footer info */}
            <div className="pt-pagination" style={{ padding: '0.75rem 1.25rem' }}>
              <span>{filteredRoles.length} rôle(s) sur {roles.length}</span>
              <div className="d-flex align-items-center gap-2" style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>
                <i className="bi bi-info-circle"></i>
                Les suppressions de rôles attribués révoquent automatiquement toutes les attributions
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* ============================================ */}
      {/* Modal: Create / Edit role                    */}
      {/* ============================================ */}
      {showModal && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem', backdropFilter: 'blur(2px)' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', width: '720px', maxWidth: '95vw', maxHeight: '90vh', display: 'flex', flexDirection: 'column', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            {/* Header */}
            <div className="d-flex justify-content-between align-items-center" style={{ padding: '1.25rem 1.5rem', borderBottom: '1px solid var(--pt-border)', flexShrink: 0 }}>
              <h5 style={{ fontWeight: 700, margin: 0, fontSize: '16px', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <i className={`bi ${editingRole ? 'bi-pencil-square' : 'bi-plus-circle'}`} style={{ color: 'var(--pt-primary)' }}></i>
                {editingRole ? 'Modifier le rôle' : 'Nouveau rôle'}
              </h5>
              <button onClick={closeModal} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)', lineHeight: 1, padding: 0, width: '28px', height: '28px', display: 'flex', alignItems: 'center', justifyContent: 'center', borderRadius: '6px', transition: 'all 0.15s' }}
                onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--pt-bg)'; e.currentTarget.style.color = 'var(--pt-text)' }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'none'; e.currentTarget.style.color = 'var(--pt-text-muted)' }}>
                <i className="bi bi-x"></i>
              </button>
            </div>

            {/* Body (scrollable) */}
            <div style={{ padding: '1.5rem', overflowY: 'auto' }} className="d-flex flex-column gap-3">
              <div>
                <label className="pt-form-label">Nom du rôle *</label>
                <input
                  type="text"
                  className="pt-form-control"
                  style={{ borderColor: touched.name && nameError ? 'var(--pt-danger)' : undefined }}
                  placeholder="ex: Chef de projet, QA Lead..."
                  value={formName}
                  onChange={e => setFormName(e.target.value)}
                  onBlur={() => setTouched(t => ({ ...t, name: true }))}
                  autoFocus
                />
                {touched.name && nameError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{nameError}</div>
                )}
              </div>
              <div>
                <label className="pt-form-label">Description</label>
                <textarea className="pt-form-control" rows={2} placeholder="Décrivez les responsabilités de ce rôle..." value={formDescription} onChange={e => setFormDescription(e.target.value)}></textarea>
                {descriptionError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{descriptionError}</div>
                )}
              </div>

              {/* Permissions */}
              <div style={{ borderTop: '1px solid var(--pt-border)', paddingTop: '1rem' }}>
                <div className="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2">
                  <label className="pt-form-label mb-0" style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
                    <i className="bi bi-shield-check"></i> Permissions
                    <span className="pt-pill neutral" style={{ fontSize: '11px' }}>{formPermissions.size}/{ALL_PERMISSION_KEYS.length}</span>
                  </label>
                  <label style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', fontSize: '12.5px', fontWeight: 600, cursor: 'pointer' }}>
                    <input
                      type="checkbox"
                      checked={formPermissions.size === ALL_PERMISSION_KEYS.length}
                      onChange={e => toggleSelectAll(e.target.checked)}
                      style={{ accentColor: 'var(--pt-primary)', width: '16px', height: '16px' }}
                    />
                    Tout sélectionner
                  </label>
                </div>
                {attemptedSave && permissionsError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginBottom: '8px' }}>{permissionsError}</div>
                )}

                <div className="row g-2" style={{ maxHeight: '360px', overflowY: 'auto', paddingRight: '4px' }}>
                  {PERMISSION_MODULES.map((mod) => {
                    const moduleKeys = mod.actions.map((a) => permissionKey(mod.id, a.key))
                    const checkedCount = moduleKeys.filter((k) => formPermissions.has(k)).length
                    return (
                      <div className="col-12 col-sm-6" key={mod.id}>
                        <div style={{ border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '0.75rem', background: 'var(--pt-bg)', height: '100%' }}>
                          <div className="d-flex justify-content-between align-items-center mb-2">
                            <span style={{ fontSize: '12.5px', fontWeight: 700, display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
                              <i className={`bi ${mod.icon}`} style={{ color: 'var(--pt-primary)' }}></i>{mod.label}
                            </span>
                            <span className="pt-pill neutral" style={{ fontSize: '10.5px' }}>{checkedCount}/{moduleKeys.length}</span>
                          </div>
                          <div className="d-flex flex-column gap-1">
                            {mod.actions.map((action) => {
                              const key = permissionKey(mod.id, action.key)
                              const checked = formPermissions.has(key)
                              return (
                                <label
                                  key={key}
                                  style={{
                                    display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '12.5px',
                                    padding: '0.3rem 0.5rem', borderRadius: '6px', cursor: 'pointer',
                                    border: '1px solid', borderColor: checked ? 'var(--pt-primary)' : 'transparent',
                                    background: checked ? 'var(--pt-primary-light)' : 'transparent',
                                  }}
                                >
                                  <input
                                    type="checkbox"
                                    checked={checked}
                                    onChange={() => togglePermission(key)}
                                    style={{ accentColor: 'var(--pt-primary)', width: '15px', height: '15px' }}
                                  />
                                  {action.label}
                                </label>
                              )
                            })}
                          </div>
                        </div>
                      </div>
                    )
                  })}
                </div>
              </div>
            </div>

            {/* Footer */}
            <div className="d-flex justify-content-end gap-2" style={{ padding: '1rem 1.5rem', borderTop: '1px solid var(--pt-border)', flexShrink: 0 }}>
              <button className="pt-btn-outline" style={{ fontSize: '13px' }} onClick={closeModal} disabled={saving}>Annuler</button>
              <button className="pt-btn-primary" style={{ fontSize: '13px' }} onClick={handleSaveRole} disabled={saving}>
                {saving ? <><i className="bi bi-arrow-repeat me-1 pt-spin"></i>Enregistrement...</> : <><i className="bi bi-check-lg me-1"></i>{editingRole ? 'Enregistrer' : 'Créer le rôle'}</>}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ============================================ */}
      {/* Modal: Delete confirmation                   */}
      {/* ============================================ */}
      {showDeleteModal && deleteTarget && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem', backdropFilter: 'blur(2px)' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', width: '480px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            {/* Header */}
            <div className="d-flex justify-content-between align-items-center" style={{ padding: '1.25rem 1.5rem', borderBottom: '1px solid var(--pt-border)' }}>
              <h5 style={{ fontWeight: 700, margin: 0, fontSize: '16px', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <i className="bi bi-exclamation-triangle-fill" style={{ color: 'var(--pt-danger)' }}></i>
                Supprimer « {deleteTarget.name} » ?
              </h5>
              <button onClick={() => setShowDeleteModal(false)} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)', lineHeight: 1, padding: 0, width: '28px', height: '28px', display: 'flex', alignItems: 'center', justifyContent: 'center', borderRadius: '6px', transition: 'all 0.15s' }}
                onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--pt-bg)'; e.currentTarget.style.color = 'var(--pt-text)' }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'none'; e.currentTarget.style.color = 'var(--pt-text-muted)' }}>
                <i className="bi bi-x"></i>
              </button>
            </div>

            {/* Body */}
            <div style={{ padding: '1.5rem' }}>
              {getUserCountForRole(deleteTarget.id) > 0 ? (
                <>
                  <div className="d-flex align-items-start gap-2 p-3 rounded mb-3" style={{ background: 'var(--pt-warning-light)', border: '1px solid var(--pt-warning)' }}>
                    <i className="bi bi-exclamation-triangle-fill" style={{ color: 'var(--pt-warning)', fontSize: '18px', marginTop: '1px' }}></i>
                    <div>
                      <div style={{ fontWeight: 600, fontSize: '13px', color: 'var(--pt-warning)' }}>
                        {getUserCountForRole(deleteTarget.id)} utilisateur(s) ont ce rôle
                      </div>
                      <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)', marginTop: '2px' }}>
                        La suppression révoquera automatiquement toutes les attributions USER_ROLES.
                      </div>
                    </div>
                  </div>

                  <div style={{ fontSize: '12.5px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '8px' }}>
                    <i className="bi bi-people me-1"></i>Utilisateurs affectés :
                  </div>
                  <div className="d-flex flex-wrap gap-2">
                    {getUsersForRole(deleteTarget.id).map(u => (
                      <span key={u.id} className="d-flex align-items-center gap-1.5" style={{ padding: '4px 10px', borderRadius: '20px', background: 'var(--pt-bg)', border: '1px solid var(--pt-border)' }}>
                        <div style={{ width: '22px', height: '22px', borderRadius: '50%', background: 'var(--pt-primary)', color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '10px', fontWeight: 600 }}>{u.avatar}</div>
                        <span style={{ fontSize: '12px', color: 'var(--pt-text)' }}>{u.name}</span>
                      </span>
                    ))}
                  </div>
                </>
              ) : (
                <div className="d-flex align-items-center gap-2 p-3 rounded" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)' }}>
                  <i className="bi bi-info-circle-fill" style={{ color: 'var(--pt-info)', fontSize: '18px' }}></i>
                  <span style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>
                    Ce rôle n'est attribué à aucun utilisateur. Vous pouvez le supprimer en toute sécurité.
                  </span>
                </div>
              )}
            </div>

            {/* Footer */}
            <div className="d-flex justify-content-end gap-2" style={{ padding: '1rem 1.5rem', borderTop: '1px solid var(--pt-border)' }}>
              <button className="pt-btn-outline" style={{ fontSize: '13px' }} onClick={() => setShowDeleteModal(false)} disabled={saving}>Annuler</button>
              <button className="pt-btn-danger" style={{ fontSize: '13px' }} onClick={confirmDelete} disabled={saving}>
                <i className="bi bi-trash me-1"></i>
                {saving ? 'Suppression...' : getUserCountForRole(deleteTarget.id) > 0 ? `Supprimer et révoquer (${getUserCountForRole(deleteTarget.id)})` : 'Supprimer'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
