import { useEffect, useMemo, useState } from 'react'
import TopBar from '../components/TopBar'
import { usePagination } from '../hooks/usePagination'
import Pagination from '../components/Pagination'
import { BackendUserSummaryResponse, BackendAppRole } from '../types/backendContracts'
import { usersBackendApi } from '../services/api/usersBackend'
import { ApiError } from '../services/api/httpClient'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

// ============================================================
// Phase 25 — cette page administre désormais les VRAIS comptes Keycloak
// (identité/statut activé-désactivé/rôle), via le backend Spring Boot
// (voir services/api/usersBackend.ts), qui parle lui-même à l'Admin REST
// API de Keycloak via un compte de service dédié — jamais le compte admin
// Keycloak, jamais un mot de passe transmis par ce frontend, jamais un
// second système d'utilisateurs en base.
//
// Avant cette phase, /users-roles affichait 8 utilisateurs ENTIÈREMENT
// FICTIFS codés en dur dans le composant (tableau `usersData`), avec des
// attributions rôle↔utilisateur persistées dans le localStorage du
// navigateur — rien de tout cela n'existait côté serveur. Tout a été
// retiré : plus aucune donnée inventée (2FA, dernière connexion, avatar,
// liste de permissions détaillées) n'est affichée, faute d'équivalent réel
// simple côté Keycloak/Admin API sans étendre le périmètre de cette phase.
//
// P1-O — `services/api/users.ts` (JSON Server, login mock) et
// `services/api/roles.ts` (catalogue JSON Server de descriptions de
// permissions) ont tous deux été retirés (n'avaient de toute façon aucun
// rapport avec cette page) : les rôles affichés ici sont les 3 rôles RÉELS
// Keycloak (ROLE_SUPER_ADMIN/ROLE_PERFORMANCE_ENGINEER/ROLE_VIEWER).
//
// Accès réservé à SUPER_ADMIN (voir @PreAuthorize sur UserController) :
// l'administration des comptes/rôles est l'opération la plus sensible du
// système (elle peut accorder ROLE_SUPER_ADMIN à n'importe qui) — aucun
// autre rôle n'y a accès, contrairement à Audit Logs (SUPER_ADMIN +
// PERFORMANCE_ENGINEER).
//
// Pagination et recherche : `GET /api/users` ne supporte aucun filtre ni
// pagination côté backend (liste complète du realm) — la recherche/tri/
// pagination ci-dessous sont donc 100% frontend, sur les données déjà
// entièrement chargées (volume réaliste pour un realm Keycloak de ce
// projet), jamais un filtre backend imaginaire.
// ============================================================

const ROLES: BackendAppRole[] = ['SUPER_ADMIN', 'PERFORMANCE_ENGINEER', 'VIEWER']

const roleBadgeStyle: Record<BackendAppRole, { bg: string; color: string }> = {
  SUPER_ADMIN: { bg: 'var(--pt-primary-light)', color: 'var(--pt-primary)' },
  PERFORMANCE_ENGINEER: { bg: 'var(--pt-success-light)', color: 'var(--pt-success)' },
  VIEWER: { bg: 'var(--pt-warning-light)', color: 'var(--pt-warning)' },
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 401:
        return 'Vous devez être connecté (Keycloak) pour administrer les utilisateurs.'
      case 403:
        return "Action refusée : l'administration des utilisateurs est réservée au rôle Administrateur."
      case 404:
        return 'Utilisateur introuvable (il a peut-être été supprimé côté Keycloak).'
      case 502:
        return err.message
      default:
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function formatDate(iso: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return '—'
  return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

function RoleBadge({ role }: { role: BackendAppRole | null }) {
  if (!role) return <span style={{ fontSize: '11.5px', color: 'var(--pt-text-light)', fontStyle: 'italic' }}>Aucun rôle</span>
  const badge = roleBadgeStyle[role]
  return <span className="pt-pill" style={{ background: badge.bg, color: badge.color, fontSize: '11.5px' }}>{role}</span>
}

function UsersRoles() {
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const isKeycloak = authProvider === 'keycloak'
  // GET/PUT /api/users sont reserves a SUPER_ADMIN au niveau de la classe
  // du controleur (voir UserController.java).
  const canAdminister = isKeycloak && rawRoles.includes('ROLE_SUPER_ADMIN')

  const [users, setUsers] = useState<BackendUserSummaryResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedUser, setSelectedUser] = useState<BackendUserSummaryResponse | null>(null)

  const [showRoleModal, setShowRoleModal] = useState(false)
  const [modalUser, setModalUser] = useState<BackendUserSummaryResponse | null>(null)
  const [pendingRole, setPendingRole] = useState<BackendAppRole>('VIEWER')
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [togglingId, setTogglingId] = useState<string | null>(null)

  const loadUsers = () => {
    if (!canAdminister) return
    setLoading(true)
    setError(null)
    usersBackendApi.list()
      .then((list) => {
        setUsers(list)
        setSelectedUser((prev) => list.find((u) => u.id === prev?.id) ?? list[0] ?? null)
      })
      .catch((err) => setError(describeApiError(err, 'Impossible de charger les utilisateurs.')))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    loadUsers()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canAdminister])

  const filteredUsers = useMemo(
    () => users.filter((u) =>
      u.username.toLowerCase().includes(searchQuery.toLowerCase()) ||
      (u.email ?? '').toLowerCase().includes(searchQuery.toLowerCase())
    ),
    [users, searchQuery]
  )

  const { page, setPage, totalPages, pageItems, startIndex, endIndex, totalItems } = usePagination(filteredUsers, 10)

  const activeCount = users.filter((u) => u.enabled).length
  const noRoleCount = users.filter((u) => u.role === null).length

  const openRoleModal = (user: BackendUserSummaryResponse) => {
    setModalUser(user)
    setPendingRole(user.role ?? 'VIEWER')
    setActionError(null)
    setShowRoleModal(true)
  }

  const saveRole = async () => {
    if (!modalUser || saving) return
    setSaving(true)
    setActionError(null)
    try {
      const updated = await usersBackendApi.updateRole(modalUser.id, pendingRole)
      setUsers((prev) => prev.map((u) => (u.id === updated.id ? updated : u)))
      setSelectedUser((prev) => (prev?.id === updated.id ? updated : prev))
      showToast(`Rôle de « ${updated.username} » mis à jour : ${updated.role ?? 'aucun'}.`, 'success')
      setShowRoleModal(false)
    } catch (err) {
      const message = describeApiError(err, 'Erreur lors de la mise à jour du rôle.')
      setActionError(message)
      showToast(message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  const toggleEnabled = async (user: BackendUserSummaryResponse) => {
    if (togglingId) return
    setTogglingId(user.id)
    try {
      const updated = await usersBackendApi.updateStatus(user.id, !user.enabled)
      setUsers((prev) => prev.map((u) => (u.id === updated.id ? updated : u)))
      setSelectedUser((prev) => (prev?.id === updated.id ? updated : prev))
      showToast(`Utilisateur « ${updated.username} » ${updated.enabled ? 'activé' : 'désactivé'} avec succès.`, 'success')
    } catch (err) {
      showToast(describeApiError(err, 'Erreur lors du changement de statut.'), 'danger')
    } finally {
      setTogglingId(null)
    }
  }

  if (!canAdminister) {
    return (
      <div className="pt-content">
        <div className="pt-page-header">
          <div className="page-title">
            <h1>Users &amp; Roles</h1>
            <p>Gérez les utilisateurs et leurs permissions</p>
          </div>
        </div>
        <div className="pt-card">
          <div className="pt-empty-state" style={{ padding: '4rem 1.5rem' }}>
            <i className="bi bi-shield-lock" style={{ fontSize: '40px', color: 'var(--pt-text-light)' }}></i>
            <p style={{ fontSize: '15px', fontWeight: 600, color: 'var(--pt-text)' }}>Accès restreint</p>
            <p>L'administration des utilisateurs est réservée au rôle Administrateur (réellement appliqué côté backend).</p>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Users &amp; Roles</h1>
          <p>Gérez les utilisateurs et leurs rôles réels (Keycloak)</p>
        </div>
        <TopBar searchPlaceholder="Rechercher un utilisateur..." />
      </div>

      {error && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={loadUsers}>Réessayer</button>
        </div>
      )}

      <div className="row g-3 mb-4">
        {[
          { label: 'Utilisateurs totaux', value: users.length, icon: 'bi-people', color: 'blue' },
          { label: 'Actifs', value: activeCount, icon: 'bi-check-circle', color: 'green' },
          { label: 'Rôles applicatifs', value: ROLES.length, icon: 'bi-shield', color: 'purple' },
          { label: 'Sans rôle assigné', value: noRoleCount, icon: 'bi-question-circle', color: 'orange' },
        ].map((card, i) => (
          <div key={i} className="col-12 col-sm-6 col-xl-3">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div><div className="stat-label">{card.label}</div><div className="stat-value">{loading ? '—' : card.value}</div></div>
                <div className={`stat-icon ${card.color}`}><i className={`bi ${card.icon}`}></i></div>
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className="row g-3">
        <div className="col-12 col-lg-8">
          <div className="pt-card" style={{ padding: 0 }}>
            <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
              <div className="pt-search" style={{ width: '260px' }}>
                <i className="bi bi-search"></i>
                <input type="text" placeholder="Rechercher par username, email..." value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} />
              </div>
            </div>

            {loading ? (
              <div className="pt-empty-state">
                <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
                <p>Chargement des utilisateurs...</p>
              </div>
            ) : filteredUsers.length === 0 ? (
              <div className="pt-empty-state">
                <i className="bi bi-inbox" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
                <p>Aucune donnée disponible.</p>
              </div>
            ) : (
              <div className="pt-table-wrapper">
                <table className="pt-table">
                  <thead>
                    <tr>
                      <th>Utilisateur</th>
                      <th>Rôle</th>
                      <th>Statut</th>
                      <th>Créé le</th>
                      <th style={{ textAlign: 'right' }}>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {pageItems.map((user) => {
                      const isSelected = selectedUser?.id === user.id
                      return (
                        <tr key={user.id} style={{ cursor: 'pointer', background: isSelected ? 'var(--pt-primary-light)' : undefined }} onClick={() => setSelectedUser(user)}>
                          <td>
                            <div className="d-flex align-items-center gap-2">
                              <div style={{ width: '32px', height: '32px', borderRadius: '50%', background: 'var(--pt-primary)', color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 600, fontSize: '12px', flexShrink: 0 }}>
                                {user.username.slice(0, 2).toUpperCase()}
                              </div>
                              <div>
                                <div style={{ fontWeight: 600, fontSize: '13.5px' }}>{user.username}</div>
                                <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{user.email ?? '—'}</div>
                              </div>
                            </div>
                          </td>
                          <td onClick={(e) => e.stopPropagation()}>
                            <div className="d-flex align-items-center gap-2">
                              <RoleBadge role={user.role} />
                              <button className="topbar-icon" style={{ width: '26px', height: '26px', border: '1px solid var(--pt-border)' }} title="Modifier le rôle" onClick={() => openRoleModal(user)}>
                                <i className="bi bi-pencil" style={{ fontSize: '11px' }}></i>
                              </button>
                            </div>
                          </td>
                          <td>
                            <span className={`pt-pill ${user.enabled ? 'success' : 'neutral'}`}>
                              <i className={`bi ${user.enabled ? 'bi-check-circle-fill' : 'bi-dash-circle-fill'}`} style={{ fontSize: '10px' }}></i>
                              {user.enabled ? 'Actif' : 'Inactif'}
                            </span>
                          </td>
                          <td style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{formatDate(user.createdAt)}</td>
                          <td style={{ textAlign: 'right' }} onClick={(e) => e.stopPropagation()}>
                            <button
                              className="topbar-icon"
                              disabled={togglingId === user.id}
                              style={{ width: '30px', height: '30px', border: `1px solid ${user.enabled ? 'var(--pt-danger)' : 'var(--pt-success)'}` }}
                              title={user.enabled ? 'Désactiver' : 'Activer'}
                              onClick={() => toggleEnabled(user)}
                            >
                              <i className={`bi ${togglingId === user.id ? 'bi-arrow-repeat pt-spin' : user.enabled ? 'bi-slash-circle' : 'bi-check-circle'}`} style={{ fontSize: '13px', color: user.enabled ? 'var(--pt-danger)' : 'var(--pt-success)' }}></i>
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}

            <Pagination page={page} totalPages={totalPages} onPageChange={setPage} startIndex={startIndex} endIndex={endIndex} totalItems={totalItems} itemLabel="utilisateurs" />
          </div>
        </div>

        <div className="col-12 col-lg-4">
          <div className="pt-card">
            <div className="d-flex align-items-center justify-content-between mb-3 pb-3 border-bottom">
              <h6 style={{ fontSize: '14px', fontWeight: 700, margin: 0 }}>Détails de l'utilisateur</h6>
            </div>
            {!selectedUser ? (
              <p className="text-muted mb-0" style={{ fontSize: '13px' }}>Sélectionnez un utilisateur.</p>
            ) : (
              <>
                <div className="text-center pb-3 border-bottom mb-3">
                  <div className="mx-auto mb-2" style={{ width: '56px', height: '56px', borderRadius: '50%', background: 'var(--pt-primary)', color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700, fontSize: '18px' }}>
                    {selectedUser.username.slice(0, 2).toUpperCase()}
                  </div>
                  <h5 style={{ fontSize: '15px', fontWeight: 700, margin: '0 0 2px 0' }}>{selectedUser.username}</h5>
                  <div style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', marginBottom: '10px' }}>{selectedUser.email ?? '—'}</div>
                  <div className="d-flex justify-content-center gap-1 mb-3">
                    <RoleBadge role={selectedUser.role} />
                    <span className={`pt-pill ${selectedUser.enabled ? 'success' : 'neutral'}`} style={{ fontSize: '11px' }}>{selectedUser.enabled ? 'Actif' : 'Inactif'}</span>
                  </div>
                  <button className="pt-btn-outline" style={{ fontSize: '12px', width: '100%' }} onClick={() => openRoleModal(selectedUser)}>
                    <i className="bi bi-shield-plus me-1"></i> Modifier le rôle
                  </button>
                </div>
                <div className="d-flex justify-content-between py-1" style={{ fontSize: '13px' }}>
                  <span className="text-muted">ID Keycloak :</span>
                  <span style={{ fontFamily: 'monospace', fontSize: '11.5px' }}>{selectedUser.id.slice(0, 13)}…</span>
                </div>
                <div className="d-flex justify-content-between py-1" style={{ fontSize: '13px' }}>
                  <span className="text-muted">Créé le :</span>
                  <span style={{ fontWeight: 600 }}>{formatDate(selectedUser.createdAt)}</span>
                </div>
              </>
            )}
          </div>
        </div>
      </div>

      {showRoleModal && modalUser && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', width: '420px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            <div className="d-flex justify-content-between align-items-center" style={{ padding: '1.25rem 1.5rem', borderBottom: '1px solid var(--pt-border)' }}>
              <h5 style={{ fontWeight: 700, margin: 0, fontSize: '16px' }}>Rôle — {modalUser.username}</h5>
              <button onClick={() => setShowRoleModal(false)} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)' }}><i className="bi bi-x"></i></button>
            </div>
            <div style={{ padding: '1.5rem' }}>
              {actionError && (
                <div className="pt-alert-banner danger mb-3"><i className="bi bi-exclamation-triangle-fill"></i>{actionError}</div>
              )}
              <p style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', marginBottom: '0.75rem' }}>
                Cette opération modifie réellement le rôle réalm Keycloak de l'utilisateur.
              </p>
              <div className="d-flex flex-column gap-2">
                {ROLES.map((role) => {
                  const badge = roleBadgeStyle[role]
                  const isChecked = pendingRole === role
                  return (
                    <label key={role} style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', padding: '0.65rem 0.85rem', borderRadius: 'var(--pt-radius-sm)', border: `1px solid ${isChecked ? 'var(--pt-primary)' : 'var(--pt-border)'}`, background: isChecked ? 'var(--pt-primary-light)' : 'var(--pt-bg)', cursor: 'pointer' }}>
                      <input type="radio" name="role" checked={isChecked} onChange={() => setPendingRole(role)} style={{ width: '16px', height: '16px' }} />
                      <span className="pt-pill" style={{ background: badge.bg, color: badge.color, fontSize: '11.5px' }}>{role}</span>
                    </label>
                  )
                })}
              </div>
            </div>
            <div className="d-flex justify-content-end gap-2" style={{ padding: '1rem 1.5rem', borderTop: '1px solid var(--pt-border)' }}>
              <button className="pt-btn-outline" style={{ fontSize: '13px' }} onClick={() => setShowRoleModal(false)} disabled={saving}>Annuler</button>
              <button className="pt-btn-primary" style={{ fontSize: '13px' }} onClick={saveRole} disabled={saving}>
                {saving ? <><i className="bi bi-arrow-repeat me-1 pt-spin"></i>Enregistrement...</> : <><i className="bi bi-check-lg me-1"></i>Enregistrer</>}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default UsersRoles
