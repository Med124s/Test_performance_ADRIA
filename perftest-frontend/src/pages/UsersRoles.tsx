import React, { useState, useMemo, useEffect } from 'react'
import TopBar from '../components/TopBar'
import { loadJSON, saveJSON } from '../utils/persistentStore'
import { useApiList } from '../hooks/useApiResource'
import { usePagination } from '../hooks/usePagination'
import Pagination from '../components/Pagination'
import { rolesApi } from '../services/api/roles'
import { Role } from '../types'

/* ============================================
   V2 Data Model — Many-to-Many Roles
   Le catalogue de rôles (Role) est désormais une vraie ressource JSON
   Server (voir src/pages/RolesCatalog.tsx / services/api/roles.ts) — cette
   page ne fait plus que le lire, pour rester cohérente avec le catalogue.
   L'attribution rôle↔utilisateur (USER_ROLES) reste une maquette locale.
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
  status: 'Actif' | 'Inactif'
  lastLogin: string
  avatar: string
  createdAt: string
  twoFactor: boolean
  permissions: string[]
}

/* ---- Mock data: USER_ROLES assignments (un seul rôle par utilisateur) —
   les ids de rôle correspondent aux ids seedés dans db.json ("1"=Admin,
   "2"=Testeur, "3"=Visiteur). ---- */
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

/* ---- Mock data: USERS ---- */
const usersData: UserItem[] = [
  {
    id: 1, name: 'Admin User', email: 'admin@perftest.com', status: 'Actif',
    lastLogin: "Aujourd'hui 10:42", avatar: 'AU', createdAt: '15/01/2025', twoFactor: true,
    permissions: ['Gestion complète des utilisateurs & rôles', 'Configuration du système & sécurité', "Création & exécution des tests de charge", 'Accès total aux journaux d\'audit', 'Exportation & suppression des rapports', 'Gestion des clés API & Webhooks'],
  },
  {
    id: 2, name: 'Test Manager', email: 'manager@perftest.com', status: 'Actif',
    lastLogin: 'Hier 16:15', avatar: 'TM', createdAt: '20/02/2025', twoFactor: true,
    permissions: ['Gestion des scénarios & campagnes', 'Lancement et arrêt des exécutions', 'Validation et approbation des rapports', "Gestion de l'équipe de test", 'Consultation des métriques d\'exécution'],
  },
  {
    id: 3, name: 'Test Engineer', email: 'engineer@perftest.com', status: 'Actif',
    lastLogin: "Aujourd'hui 09:12", avatar: 'TE', createdAt: '10/03/2025', twoFactor: false,
    permissions: ['Création & édition de scénarios', 'Lancement des exécutions de test', 'Visualisation des résultats en direct', 'Exportation des rapports au format PDF/CSV'],
  },
  {
    id: 4, name: 'Read Only User', email: 'readonly@perftest.com', status: 'Actif',
    lastLogin: '28/07/2026 14:20', avatar: 'RO', createdAt: '05/04/2025', twoFactor: false,
    permissions: ['Consultation des tableaux de bord', 'Lecture seule des rapports de test', 'Visualisation de l\'historique des exécutions'],
  },
  {
    id: 5, name: 'DevOps User', email: 'devops@perftest.com', status: 'Actif',
    lastLogin: 'Hier 18:30', avatar: 'DU', createdAt: '12/04/2025', twoFactor: true,
    permissions: ['Configuration des agents de charge', 'Intégration CI/CD & webhooks', 'Surveillance des services & infrastructure', 'Consultation des logs système'],
  },
  {
    id: 6, name: 'Analyst User', email: 'analyst@perftest.com', status: 'Inactif',
    lastLogin: '15/07/2026 11:05', avatar: 'AN', createdAt: '01/05/2025', twoFactor: false,
    permissions: ['Analyse avancée des métriques de performance', 'Création de rapports comparatifs customisés', 'Exportation brute des jeux de données'],
  },
  {
    id: 7, name: 'Support User', email: 'support@perftest.com', status: 'Actif',
    lastLogin: "Aujourd'hui 08:00", avatar: 'SU', createdAt: '18/05/2025', twoFactor: true,
    permissions: ["Consultation de l'état des services", 'Assistance utilisateur & tickets', 'Lecture des logs d\'erreurs système'],
  },
  {
    id: 8, name: 'John Doe', email: 'john.doe@perftest.com', status: 'Actif',
    lastLogin: "Aujourd'hui 11:00", avatar: 'JD', createdAt: '01/06/2025', twoFactor: true,
    permissions: ['Création & édition de scénarios', 'Lancement des exécutions de test', 'Visualisation des résultats en direct', 'Exportation des rapports au format PDF/CSV'],
  },
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

function todayFormatted() {
  const d = new Date()
  return `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()}`
}

/* ---- Reusable inline styles ---- */
const actionBtnStyle: React.CSSProperties = {
  width: '32px', height: '32px', borderRadius: 'var(--pt-radius-sm)',
  border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)',
  display: 'flex', alignItems: 'center', justifyContent: 'center',
  color: 'var(--pt-text-muted)', cursor: 'pointer', flexShrink: 0,
  transition: 'all 0.15s ease',
}

function ActionBtn({ icon, title, onClick, danger }: { icon: string; title: string; onClick: () => void; danger?: boolean }) {
  return (
    <button
      style={{
        ...actionBtnStyle,
        color: danger ? 'var(--pt-danger)' : 'var(--pt-text-muted)',
        borderColor: danger ? 'var(--pt-danger)' : 'var(--pt-border)',
      }}
      title={title}
      onClick={onClick}
      onMouseEnter={(e) => {
        e.currentTarget.style.borderColor = danger ? 'var(--pt-danger)' : 'var(--pt-primary)'
        e.currentTarget.style.color = danger ? 'var(--pt-danger)' : 'var(--pt-primary)'
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.borderColor = danger ? 'var(--pt-danger)' : 'var(--pt-border)'
        e.currentTarget.style.color = danger ? 'var(--pt-danger)' : 'var(--pt-text-muted)'
      }}
    >
      <i className={`bi ${icon}`} style={{ fontSize: '14px' }}></i>
    </button>
  )
}

/* ---- Role badge component ---- */
function RoleBadge({ role, onRevoke }: { role: Role; onRevoke?: () => void }) {
  const badge = getRoleBadgeStyle(role.name)
  return (
    <span
      className="pt-pill"
      style={{
        background: badge.bg,
        color: badge.color,
        fontSize: '11.5px',
        gap: onRevoke ? '0.35rem' : '0.25rem',
        lineHeight: 1,
      }}
    >
      {role.name}
      {onRevoke && (
        <i
          className="bi bi-x-lg"
          style={{
            fontSize: '9px',
            cursor: 'pointer',
            opacity: 0.5,
            transition: 'opacity 0.15s',
          }}
          title={`Révoquer ${role.name}`}
          onClick={(e) => { e.stopPropagation(); onRevoke() }}
          onMouseEnter={(e) => { e.currentTarget.style.opacity = '1' }}
          onMouseLeave={(e) => { e.currentTarget.style.opacity = '0.5' }}
        />
      )}
    </span>
  )
}

/* ============================================
   Component
   ============================================ */

export default function UsersRoles() {
  // Catalogue de rôles réel (JSON Server) — même source que RolesCatalog.tsx.
  const { data: roles } = useApiList<Role>(() => rolesApi.getAll())
  const [userRoles, setUserRoles] = useState<UserRoleAssignment[]>(() =>
    loadJSON('pt_user_roles_v2', initialUserRoles)
  )
  const [selectedUser, setSelectedUser] = useState<UserItem>(usersData[0])
  const [searchQuery, setSearchQuery] = useState('')
  const [roleFilters, setRoleFilters] = useState<string[]>([])
  const [selectedUsers, setSelectedUsers] = useState<number[]>([])

  // Persiste les attributions rôle/utilisateur — partagé avec la page
  // Catalogue des rôles pour rester cohérent partout dans l'app.
  useEffect(() => {
    saveJSON('pt_user_roles_v2', userRoles)
  }, [userRoles])

  // Modal state
  const [showRoleModal, setShowRoleModal] = useState(false)
  const [modalUser, setModalUser] = useState<UserItem | null>(null)
  const [checkedRoles, setCheckedRoles] = useState<string[]>([])

  // Multi-select dropdown state
  const [showRoleDropdown, setShowRoleDropdown] = useState(false)

  /* ---- Derived: roles for a given user ---- */
  function getUserRoles(userId: number): Role[] {
    const assignments = userRoles.filter((ur) => ur.userId === userId)
    return assignments.map((a) => roles.find((r) => r.id === a.roleId)).filter(Boolean) as Role[]
  }

  function getUserRoleAssignment(userId: number, roleId: string): UserRoleAssignment | undefined {
    return userRoles.find((ur) => ur.userId === userId && ur.roleId === roleId)
  }

  /* ---- Filtering ---- */
  const filteredUsers = useMemo(() => {
    return usersData.filter((u) => {
      const matchesSearch = u.name.toLowerCase().includes(searchQuery.toLowerCase()) || u.email.toLowerCase().includes(searchQuery.toLowerCase())
      const userRoleIds = userRoles.filter((ur) => ur.userId === u.id).map((ur) => ur.roleId)
      const matchesRole = roleFilters.length === 0 || roleFilters.some((rf) => userRoleIds.includes(rf))
      return matchesSearch && matchesRole
    })
  }, [searchQuery, roleFilters, userRoles])

  const { page, setPage, totalPages, pageItems, startIndex, endIndex, totalItems } = usePagination(filteredUsers, 10)

  const toggleSelectAll = () => {
    if (selectedUsers.length === filteredUsers.length) setSelectedUsers([])
    else setSelectedUsers(filteredUsers.map((u) => u.id))
  }

  const toggleSelectUser = (id: number) => {
    setSelectedUsers((prev) => prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id])
  }

  /* ---- Role management actions ---- */
  function openRoleModal(user: UserItem) {
    const currentRoleIds = userRoles.filter((ur) => ur.userId === user.id).map((ur) => ur.roleId)
    setModalUser(user)
    setCheckedRoles(currentRoleIds)
    setShowRoleModal(true)
  }

  function toggleCheckedRole(roleId: string) {
    setCheckedRoles((prev) => prev.includes(roleId) ? prev.filter((r) => r !== roleId) : [...prev, roleId])
  }

  function saveRoles() {
    if (!modalUser) return
    const today = todayFormatted()
    const removedRoleIds = userRoles.filter((ur) => ur.userId === modalUser.id && !checkedRoles.includes(ur.roleId)).map((ur) => ur.roleId)
    const existingRoleIds = userRoles.filter((ur) => ur.userId === modalUser.id).map((ur) => ur.roleId)
    const newRoleIds = checkedRoles.filter((rid) => !existingRoleIds.includes(rid))

    setUserRoles((prev) => {
      let updated = prev.filter((ur) => !(ur.userId === modalUser.id && removedRoleIds.includes(ur.roleId)))
      const additions: UserRoleAssignment[] = newRoleIds.map((roleId) => ({ userId: modalUser.id, roleId, date_attribution: today }))
      return [...updated, ...additions]
    })
    setShowRoleModal(false)
    setModalUser(null)
  }

  function revokeRole(userId: number, roleId: string) {
    setUserRoles((prev) => prev.filter((ur) => !(ur.userId === userId && ur.roleId === roleId)))
  }

  /* ---- Multi-select filter ---- */
  function toggleRoleFilter(roleId: string) {
    setRoleFilters((prev) => prev.includes(roleId) ? prev.filter((r) => r !== roleId) : [...prev, roleId])
  }

  const roleFilterLabel = roleFilters.length === 0 ? 'Tous les rôles' : roleFilters.length === 1 ? roles.find((r) => r.id === roleFilters[0])?.name || '1 rôle' : `${roleFilters.length} rôles`

  const activeUsers = usersData.filter((u) => u.status === 'Actif').length

  return (
    <div className="pt-content">
      {/* Header */}
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Users & Roles</h1>
          <p>Gérez les utilisateurs et leurs permissions (modèle multi-rôles V2)</p>
        </div>
        <div className="d-flex align-items-center gap-3 flex-wrap">
          <button className="pt-btn-primary">
            <i className="bi bi-person-plus-fill me-1"></i>
            Nouvel utilisateur
          </button>
          <TopBar searchPlaceholder="Rechercher un utilisateur..." />
        </div>
      </div>

      {/* Stat Cards */}
      <div className="row g-3 mb-4">
        {[
          { label: 'Utilisateurs totaux', value: usersData.length, trend: '+3 ce mois', icon: 'bi-people', color: 'blue', positive: true },
          { label: 'Actifs', value: activeUsers, trend: `${Math.round((activeUsers / usersData.length) * 100)}% du total`, icon: 'bi-check-circle', color: 'green', positive: true },
          { label: 'Rôles', value: roles.length, trend: 'Catalogue configuré', icon: 'bi-shield', color: 'blue', positive: false },
          { label: 'Attributions', value: userRoles.length, trend: 'USER_ROLES', icon: 'bi-link-45deg', color: 'purple', positive: false },
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

      {/* Main Content: Two Column Layout */}
      <div className="row g-3">
        {/* Left Column: User Table */}
        <div className="col-12 col-lg-8">
          <div className="pt-card" style={{ padding: 0 }}>
            {/* Table Header Controls */}
            <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
              <div className="pt-search" style={{ width: '260px' }}>
                <i className="bi bi-search"></i>
                <input type="text" placeholder="Rechercher par nom, email..." value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} />
              </div>

              <div className="d-flex align-items-center gap-2" style={{ position: 'relative' }}>
                {/* Multi-select role filter */}
                <div style={{ position: 'relative' }}>
                  <button
                    className="pt-btn-outline"
                    style={{ padding: '0.5rem 0.75rem', fontSize: '12.5px', minWidth: '160px', justifyContent: 'space-between', gap: '0.5rem' }}
                    onClick={() => setShowRoleDropdown(!showRoleDropdown)}
                  >
                    <span className="d-flex align-items-center gap-1">
                      <i className="bi bi-funnel" style={{ fontSize: '12px' }}></i>
                      {roleFilterLabel}
                    </span>
                    <i className="bi bi-chevron-down" style={{ fontSize: '10px', transition: 'transform 0.2s', transform: showRoleDropdown ? 'rotate(180deg)' : 'none' }}></i>
                  </button>

                  {showRoleDropdown && (
                    <>
                      <div style={{ position: 'fixed', inset: 0, zIndex: 1049 }} onClick={() => setShowRoleDropdown(false)} />
                      <div style={{
                        position: 'absolute', top: 'calc(100% + 4px)', right: 0,
                        background: 'var(--pt-card-bg)', border: '1px solid var(--pt-border)',
                        borderRadius: 'var(--pt-radius-sm)', boxShadow: '0 10px 30px rgba(0,0,0,0.12)',
                        padding: '0.5rem', minWidth: '210px', zIndex: 1050,
                      }}>
                        <div style={{ fontSize: '11px', fontWeight: 600, color: 'var(--pt-text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em', padding: '0.25rem 0.5rem', marginBottom: '4px' }}>
                          Filtrer par rôles
                        </div>
                        {roles.map((role) => {
                          const badge = getRoleBadgeStyle(role.name)
                          const isChecked = roleFilters.includes(role.id)
                          return (
                            <label key={role.id} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', padding: '0.4rem 0.5rem', borderRadius: '6px', cursor: 'pointer', fontSize: '13px', color: 'var(--pt-text)', transition: 'background 0.1s' }}
                              onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--pt-bg)')}
                              onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}>
                              <input type="checkbox" checked={isChecked} onChange={() => toggleRoleFilter(role.id)} style={{ accentColor: 'var(--pt-primary)', width: '16px', height: '16px' }} />
                              <span className="pt-pill" style={{ background: badge.bg, color: badge.color, fontSize: '11px' }}>{role.name}</span>
                            </label>
                          )
                        })}
                        {roleFilters.length > 0 && (
                          <button className="pt-btn-ghost" style={{ width: '100%', fontSize: '12px', marginTop: '4px', padding: '0.35rem' }} onClick={() => setRoleFilters([])}>
                            <i className="bi bi-arrow-counterclockwise me-1"></i>Réinitialiser
                          </button>
                        )}
                      </div>
                    </>
                  )}
                </div>
              </div>
            </div>

            {/* Table */}
            <div className="pt-table-wrapper">
              <table className="pt-table">
                <thead>
                  <tr>
                    <th style={{ width: '40px' }}>
                      <input type="checkbox" checked={selectedUsers.length === filteredUsers.length && filteredUsers.length > 0} onChange={toggleSelectAll} />
                    </th>
                    <th>Utilisateur</th>
                    <th>Rôles</th>
                    <th>Statut</th>
                    <th>Dernière connexion</th>
                    <th style={{ textAlign: 'right' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {pageItems.map((user) => {
                    const isSelected = selectedUser.id === user.id
                    const userRoleList = getUserRoles(user.id)
                    return (
                      <tr key={user.id} style={{ cursor: 'pointer', background: isSelected ? 'var(--pt-primary-light)' : undefined }} onClick={() => setSelectedUser(user)}>
                        <td onClick={(e) => e.stopPropagation()}>
                          <input type="checkbox" checked={selectedUsers.includes(user.id)} onChange={() => toggleSelectUser(user.id)} />
                        </td>
                        <td>
                          <div className="d-flex align-items-center gap-2.5">
                            <div style={{ width: '36px', height: '36px', borderRadius: '50%', background: 'var(--pt-primary)', color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 600, fontSize: '13px', flexShrink: 0 }}>
                              {user.avatar}
                            </div>
                            <div>
                              <div style={{ fontWeight: 600, fontSize: '13.5px', color: 'var(--pt-text)' }}>{user.name}</div>
                              <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{user.email}</div>
                            </div>
                          </div>
                        </td>
                        <td onClick={(e) => e.stopPropagation()}>
                          <div className="d-flex flex-wrap align-items-center gap-1">
                            {userRoleList.length > 0 ? userRoleList.map((role) => (
                              <RoleBadge key={role.id} role={role} onRevoke={() => revokeRole(user.id, role.id)} />
                            )) : (
                              <span style={{ fontSize: '11.5px', color: 'var(--pt-text-light)', fontStyle: 'italic' }}>Aucun rôle</span>
                            )}
                            <button
                              title="Ajouter un rôle"
                              onClick={(e) => { e.stopPropagation(); openRoleModal(user) }}
                              style={{
                                width: '26px', height: '26px', borderRadius: '50%',
                                border: '1px dashed var(--pt-border)', background: 'transparent',
                                color: 'var(--pt-text-muted)', cursor: 'pointer', flexShrink: 0,
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                transition: 'all 0.15s ease',
                              }}
                              onMouseEnter={(e) => { e.currentTarget.style.borderColor = 'var(--pt-primary)'; e.currentTarget.style.color = 'var(--pt-primary)'; e.currentTarget.style.background = 'var(--pt-primary-light)' }}
                              onMouseLeave={(e) => { e.currentTarget.style.borderColor = 'var(--pt-border)'; e.currentTarget.style.color = 'var(--pt-text-muted)'; e.currentTarget.style.background = 'transparent' }}
                            >
                              <i className="bi bi-plus-lg" style={{ fontSize: '12px' }}></i>
                            </button>
                          </div>
                        </td>
                        <td>
                          <span className={`pt-pill ${user.status === 'Actif' ? 'success' : 'neutral'}`}>
                            <i className={`bi ${user.status === 'Actif' ? 'bi-check-circle-fill' : 'bi-dash-circle-fill'}`} style={{ fontSize: '10px' }}></i>
                            {user.status}
                          </span>
                        </td>
                        <td style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{user.lastLogin}</td>
                        <td style={{ textAlign: 'right' }} onClick={(e) => e.stopPropagation()}>
                          <div className="d-flex justify-content-end gap-1">
                            <ActionBtn icon="bi-eye" title="Voir détails" onClick={() => setSelectedUser(user)} />
                            <ActionBtn icon="bi-shield-plus" title="Gérer les rôles" onClick={() => openRoleModal(user)} />
                            <ActionBtn icon="bi-three-dots" title="Options" onClick={() => {}} />
                          </div>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>

            <Pagination
              page={page}
              totalPages={totalPages}
              onPageChange={setPage}
              startIndex={startIndex}
              endIndex={endIndex}
              totalItems={totalItems}
              itemLabel="utilisateurs"
            />
          </div>
        </div>

        {/* Right Column: Detail Drawer Panel */}
        <div className="col-12 col-lg-4">
          <div className="pt-card">
            <div className="d-flex align-items-center justify-content-between mb-3 pb-3 border-bottom">
              <h6 style={{ fontSize: '14px', fontWeight: 700, margin: 0 }}>Détails de l'utilisateur</h6>
              <span className="pt-pill info" style={{ fontSize: '11px' }}>Sélectionné</span>
            </div>

            {/* User Profile Overview */}
            <div className="text-center pb-3 border-bottom mb-3">
              <div className="mx-auto mb-2" style={{ width: '64px', height: '64px', borderRadius: '50%', background: 'var(--pt-primary)', color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700, fontSize: '22px', boxShadow: 'var(--pt-shadow-sm)' }}>
                {selectedUser.avatar}
              </div>
              <h5 style={{ fontSize: '16px', fontWeight: 700, margin: '0 0 2px 0', color: 'var(--pt-text)' }}>{selectedUser.name}</h5>
              <div style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', marginBottom: '10px' }}>{selectedUser.email}</div>
              <div className="d-flex justify-content-center flex-wrap gap-1 align-items-center mb-3">
                {getUserRoles(selectedUser.id).length > 0 ? getUserRoles(selectedUser.id).map((role) => (
                  <RoleBadge key={role.id} role={role} />
                )) : (
                  <span style={{ fontSize: '11.5px', color: 'var(--pt-text-light)', fontStyle: 'italic' }}>Aucun rôle attribué</span>
                )}
                <span className={`pt-pill ${selectedUser.status === 'Actif' ? 'success' : 'neutral'}`} style={{ fontSize: '11px' }}>
                  {selectedUser.status}
                </span>
              </div>
              <button className="pt-btn-outline" style={{ fontSize: '12px', padding: '0.35rem 0.85rem', width: '100%' }} onClick={() => openRoleModal(selectedUser)}>
                <i className="bi bi-shield-plus me-1"></i> Gérer les rôles
              </button>
            </div>

            {/* Key User Info Fields */}
            <div className="mb-3 border-bottom pb-3">
              <div className="d-flex justify-content-between py-1" style={{ fontSize: '13px' }}>
                <span style={{ color: 'var(--pt-text-muted)' }}>Date création:</span>
                <span style={{ fontWeight: 600, color: 'var(--pt-text)' }}>{selectedUser.createdAt}</span>
              </div>
              <div className="d-flex justify-content-between py-1" style={{ fontSize: '13px' }}>
                <span style={{ color: 'var(--pt-text-muted)' }}>Dernière connexion:</span>
                <span style={{ fontWeight: 600, color: 'var(--pt-text)' }}>{selectedUser.lastLogin}</span>
              </div>
              <div className="d-flex justify-content-between py-1 align-items-center" style={{ fontSize: '13px' }}>
                <span style={{ color: 'var(--pt-text-muted)' }}>2FA Active:</span>
                {selectedUser.twoFactor ? (
                  <span className="pt-pill success" style={{ fontSize: '11px' }}><i className="bi bi-check-circle-fill me-1"></i> Activé</span>
                ) : (
                  <span className="pt-pill warning" style={{ fontSize: '11px' }}><i className="bi bi-exclamation-triangle-fill me-1"></i> Désactivé</span>
                )}
              </div>
              {/* Roles with attribution dates */}
              <div className="py-2" style={{ fontSize: '13px' }}>
                <span style={{ color: 'var(--pt-text-muted)', display: 'block', marginBottom: '6px', fontWeight: 600 }}>
                  <i className="bi bi-shield-check me-1"></i>Rôles attribués ({getUserRoles(selectedUser.id).length})
                </span>
                {getUserRoles(selectedUser.id).length > 0 ? (
                  <div className="d-flex flex-column gap-1.5">
                    {getUserRoles(selectedUser.id).map((role) => {
                      const assignment = getUserRoleAssignment(selectedUser.id, role.id)
                      return (
                        <div key={role.id} className="d-flex justify-content-between align-items-center" style={{ padding: '8px 10px', borderRadius: 'var(--pt-radius-sm)', background: 'var(--pt-bg)', border: '1px solid var(--pt-border)' }}>
                          <div className="d-flex align-items-center gap-2">
                            <RoleBadge role={role} />
                          </div>
                          <div className="d-flex align-items-center gap-2">
                            <span style={{ fontSize: '11px', color: 'var(--pt-text-muted)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                              <i className="bi bi-calendar3" style={{ fontSize: '10px' }}></i>
                              {assignment?.date_attribution || '—'}
                            </span>
                            <button
                              title={`Révoquer ${role.name}`}
                              onClick={() => revokeRole(selectedUser.id, role.id)}
                              style={{
                                width: '22px', height: '22px', borderRadius: '6px', flexShrink: 0,
                                border: 'none', background: 'transparent', cursor: 'pointer',
                                color: 'var(--pt-text-light)', display: 'flex', alignItems: 'center', justifyContent: 'center',
                                transition: 'all 0.15s ease',
                              }}
                              onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--pt-danger-light)'; e.currentTarget.style.color = 'var(--pt-danger)' }}
                              onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--pt-text-light)' }}
                            >
                              <i className="bi bi-x-lg" style={{ fontSize: '10px' }}></i>
                            </button>
                          </div>
                        </div>
                      )
                    })}
                  </div>
                ) : (
                  <span style={{ fontSize: '12px', color: 'var(--pt-text-light)' }}>Aucun rôle attribué</span>
                )}
              </div>
            </div>

            {/* Permissions List */}
            <div className="mb-3">
              <div className="d-flex justify-content-between align-items-center mb-2" style={{ fontSize: '12.5px', fontWeight: 600 }}>
                <span style={{ color: 'var(--pt-text)' }}>Permissions accordées</span>
                <span style={{ color: 'var(--pt-text-muted)' }}>{selectedUser.permissions.length} actives</span>
              </div>
              <div className="d-flex flex-column gap-2" style={{ maxHeight: '220px', overflowY: 'auto' }}>
                {selectedUser.permissions.map((perm, index) => (
                  <div key={index} className="d-flex align-items-start gap-2 p-2 rounded" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12px' }}>
                    <i className="bi bi-check-circle-fill text-success" style={{ fontSize: '14px', marginTop: '1px' }}></i>
                    <span style={{ color: 'var(--pt-text)' }}>{perm}</span>
                  </div>
                ))}
              </div>
            </div>

            {/* Drawer Actions */}
            <div className="d-flex gap-2">
              <button className="pt-btn-primary flex-grow-1" style={{ fontSize: '12.5px', padding: '0.45rem' }}>
                <i className="bi bi-pencil me-1"></i> Éditer
              </button>
              <button className="pt-btn-outline" style={{ fontSize: '12.5px', padding: '0.45rem' }} title="Réinitialiser mot de passe">
                <i className="bi bi-key"></i>
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* ============================================ */}
      {/* Modal: Gérer les rôles de l'utilisateur       */}
      {/* ============================================ */}
      {showRoleModal && modalUser && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem', backdropFilter: 'blur(2px)' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', width: '520px', maxWidth: '95vw', maxHeight: '90vh', display: 'flex', flexDirection: 'column', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            {/* Modal Header */}
            <div className="d-flex justify-content-between align-items-center" style={{ padding: '1.25rem 1.5rem', borderBottom: '1px solid var(--pt-border)' }}>
              <h5 style={{ fontWeight: 700, margin: 0, fontSize: '16px', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <i className="bi bi-shield-check" style={{ color: 'var(--pt-primary)' }}></i>
                Gérer les rôles — {modalUser.name}
              </h5>
              <button onClick={() => setShowRoleModal(false)} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)', lineHeight: 1, padding: 0, width: '28px', height: '28px', display: 'flex', alignItems: 'center', justifyContent: 'center', borderRadius: '6px', transition: 'all 0.15s' }}
                onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--pt-bg)'; e.currentTarget.style.color = 'var(--pt-text)' }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'none'; e.currentTarget.style.color = 'var(--pt-text-muted)' }}>
                <i className="bi bi-x"></i>
              </button>
            </div>

            {/* Modal Body */}
            <div style={{ padding: '1.5rem', overflowY: 'auto', flex: 1 }}>
              <div className="d-flex align-items-center gap-2 p-2 px-3 rounded mb-3" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)' }}>
                <div style={{ width: '32px', height: '32px', borderRadius: '50%', background: 'var(--pt-primary)', color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 600, fontSize: '12px', flexShrink: 0 }}>
                  {modalUser.avatar}
                </div>
                <div>
                  <div style={{ fontWeight: 600, fontSize: '13px', color: 'var(--pt-text)' }}>{modalUser.name}</div>
                  <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{modalUser.email}</div>
                </div>
              </div>

              <p style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', marginBottom: '0.75rem' }}>
                Cochez les rôles à attribuer. La date d'attribution est enregistrée automatiquement.
              </p>

              <div className="d-flex flex-column gap-2">
                {roles.map((role) => {
                  const badge = getRoleBadgeStyle(role.name)
                  const isChecked = checkedRoles.includes(role.id)
                  const existingAssignment = getUserRoleAssignment(modalUser.id, role.id)
                  return (
                    <label key={role.id} style={{
                      display: 'flex', alignItems: 'center', gap: '0.75rem', padding: '0.65rem 0.85rem', borderRadius: 'var(--pt-radius-sm)',
                      border: `1px solid ${isChecked ? 'var(--pt-primary)' : 'var(--pt-border)'}`,
                      background: isChecked ? 'var(--pt-primary-light)' : 'var(--pt-bg)',
                      cursor: 'pointer', transition: 'all 0.15s ease',
                    }}>
                      <input type="checkbox" checked={isChecked} onChange={() => toggleCheckedRole(role.id)} style={{ width: '18px', height: '18px', accentColor: 'var(--pt-primary)', flexShrink: 0 }} />
                      <div className="flex-grow-1">
                        <div className="d-flex align-items-center gap-2 mb-1">
                          <span className="pt-pill" style={{ background: badge.bg, color: badge.color, fontSize: '11.5px' }}>{role.name}</span>
                          {existingAssignment && (
                            <span style={{ fontSize: '11px', color: 'var(--pt-text-muted)', display: 'flex', alignItems: 'center', gap: '3px' }}>
                              <i className="bi bi-calendar3" style={{ fontSize: '9px' }}></i>
                              {existingAssignment.date_attribution}
                            </span>
                          )}
                        </div>
                        <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{role.description}</div>
                      </div>
                    </label>
                  )
                })}
              </div>
            </div>

            {/* Modal Footer */}
            <div className="d-flex justify-content-end gap-2" style={{ padding: '1rem 1.5rem', borderTop: '1px solid var(--pt-border)' }}>
              <button className="pt-btn-outline" style={{ fontSize: '13px' }} onClick={() => setShowRoleModal(false)}>Annuler</button>
              <button className="pt-btn-primary" style={{ fontSize: '13px' }} onClick={saveRoles}>
                <i className="bi bi-check-lg me-1"></i> Enregistrer
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
