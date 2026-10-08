import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { usePagination } from '../hooks/usePagination'
import Pagination from '../components/Pagination'
import type { BackendAppRole, BackendUserCreatedResponse, BackendUserSummaryResponse } from '../types/backendContracts'
import { usersBackendApi } from '../services/api/usersBackend'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import RowActionsMenu from './executions/RowActionsMenu'
import UserDrawer from './users/UserDrawer'
import CreateUserModal from './users/CreateUserModal'
import ResetPasswordDialog from './users/ResetPasswordDialog'
import { LastLogin, RoleBadge, StatusLabel, UserAvatar, UserDialog, describeUserApiError, displayNameOf } from './users/userParts'
import { APP_ROLES, roleMeta } from '../utils/appRoles'
import './users/users.css'

// ============================================================
// Onglet « Utilisateurs » : les VRAIS comptes Keycloak (identité, statut,
// rôle), administrés via le backend Spring Boot (services/api/usersBackend.ts),
// qui parle lui-même à l'Admin REST API de Keycloak avec un compte de service
// dédié — jamais un mot de passe transmis par ce frontend : création et
// réinitialisation passent par un e-mail Keycloak.
//
// Accès réservé à SUPER_ADMIN (voir @PreAuthorize sur UserController).
// `GET /api/users` renvoie tout le realm : recherche, filtres et pagination
// sont faits ici, sur la liste complète.
// ============================================================

type RoleFilter = 'all' | BackendAppRole | 'none'
type StatusFilter = 'all' | 'active' | 'disabled'

type PendingAction =
  | { kind: 'role'; user: BackendUserSummaryResponse; role: BackendAppRole }
  | { kind: 'status'; user: BackendUserSummaryResponse; enabled: boolean }

const plural = (n: number, one: string, many: string) => `${n} ${n > 1 ? many : one}`
const ROLE_COUNT_LABEL: Record<BackendAppRole, [string, string]> = {
  SUPER_ADMIN: ['administrateur', 'administrateurs'],
  PERFORMANCE_ENGINEER: ['ingénieur', 'ingénieurs'],
  VIEWER: ['lecteur', 'lecteurs'],
}

function normalize(s: string) {
  return s.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()
}

export interface UsersRolesProps {
  /** Affichée dans l'onglet de « Utilisateurs et rôles », sans son propre en-tête. */
  embedded?: boolean
  /** Fenêtre « Ajouter un utilisateur » ouverte depuis l'en-tête de la page parente. */
  createOpen?: boolean
  onCreateClose?: () => void
}

function UsersRoles({ embedded = false, createOpen, onCreateClose }: UsersRolesProps = {}) {
  const { authProvider, rawRoles, user: me } = useAuth()
  const { showToast } = useToast()
  const canAdminister = authProvider === 'keycloak' && rawRoles.includes('ROLE_SUPER_ADMIN')

  const [users, setUsers] = useState<BackendUserSummaryResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [roleFilter, setRoleFilter] = useState<RoleFilter>('all')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')
  // `?user=<id>` (lien depuis le journal d'audit) ouvre directement la fiche.
  const [searchParams] = useSearchParams()
  const [openUserId, setOpenUserId] = useState<string | null>(() => searchParams.get('user'))
  const [pending, setPending] = useState<PendingAction | null>(null)
  const [resetUser, setResetUser] = useState<BackendUserSummaryResponse | null>(null)
  const [busy, setBusy] = useState(false)
  const [localCreateOpen, setLocalCreateOpen] = useState(false)
  const showCreate = createOpen ?? localCreateOpen
  const closeCreate = useCallback(() => { setLocalCreateOpen(false); onCreateClose?.() }, [onCreateClose])

  const loadUsers = useCallback(() => {
    if (!canAdminister) return
    setLoading(true)
    setError(null)
    usersBackendApi.list()
      .then((list) => setUsers(list))
      .catch((err) => setError(describeUserApiError(err, 'Impossible de charger les utilisateurs.')))
      .finally(() => setLoading(false))
  }, [canAdminister])

  useEffect(() => { loadUsers() }, [loadUsers])

  const sorted = useMemo(
    () => [...users].sort((a, b) => displayNameOf(a).localeCompare(displayNameOf(b), 'fr', { sensitivity: 'base' })),
    [users],
  )
  const filtered = useMemo(() => {
    const q = normalize(search.trim())
    return sorted.filter((u) =>
      (!q || [displayNameOf(u), u.username, u.email ?? ''].some((v) => normalize(v).includes(q)))
      && (roleFilter === 'all' || (roleFilter === 'none' ? u.role === null : u.role === roleFilter))
      && (statusFilter === 'all' || (statusFilter === 'active') === u.enabled))
  }, [sorted, search, roleFilter, statusFilter])

  const { page, setPage, totalPages, pageItems, startIndex, endIndex, totalItems } = usePagination(filtered, 10)
  const filtersActive = search.trim() !== '' || roleFilter !== 'all' || statusFilter !== 'all'
  const resetFilters = () => { setSearch(''); setRoleFilter('all'); setStatusFilter('all') }

  const counts = useMemo(() => ({
    total: users.length,
    active: users.filter((u) => u.enabled).length,
    disabled: users.filter((u) => !u.enabled).length,
    none: users.filter((u) => u.role === null).length,
    byRole: Object.fromEntries(APP_ROLES.map((r) => [r.role, users.filter((u) => u.role === r.role).length])) as Record<BackendAppRole, number>,
  }), [users])

  const openUser = users.find((u) => u.id === openUserId) ?? null
  const isSelf = (u: BackendUserSummaryResponse) => !!me?.id && u.id === me.id
  const replaceUser = (updated: BackendUserSummaryResponse) => setUsers((prev) => prev.map((u) => (u.id === updated.id ? { ...u, ...updated } : u)))

  const confirmPending = async () => {
    if (!pending || busy) return
    setBusy(true)
    const name = displayNameOf(pending.user)
    try {
      if (pending.kind === 'role') {
        replaceUser(await usersBackendApi.updateRole(pending.user.id, pending.role))
        showToast(`${name} est maintenant ${roleMeta(pending.role).label.toLowerCase()}.`, 'success')
      } else {
        replaceUser(await usersBackendApi.updateStatus(pending.user.id, pending.enabled))
        showToast(pending.enabled ? `Compte de ${name} réactivé.` : `Compte de ${name} désactivé.`, 'success')
      }
      setPending(null)
    } catch (err) {
      showToast(describeUserApiError(err, 'L’opération a échoué.'), 'danger')
    } finally {
      setBusy(false)
    }
  }

  const onCreated = (result: BackendUserCreatedResponse) => {
    setUsers((prev) => [...prev.filter((u) => u.id !== result.user.id), result.user])
    closeCreate()
    const name = displayNameOf(result.user)
    if (result.passwordSet) showToast(`${name} a été ajouté avec le mot de passe défini.`, 'success')
    else if (result.invitationSent) showToast(`${name} a été ajouté. Un lien pour choisir son mot de passe a été envoyé à ${result.user.email}.`, 'success')
    else showToast(`${name} a été ajouté, mais l’e-mail n’a pas pu partir : utilisez « Réinitialiser le mot de passe ».`, 'warning')
    setOpenUserId(result.user.id)
  }

  const rowActions = (u: BackendUserSummaryResponse) => [
    { label: 'Modifier le rôle', icon: 'bi-shield-check', onSelect: () => setOpenUserId(u.id) },
    { label: 'Réinitialiser le mot de passe', icon: 'bi-key', onSelect: () => setResetUser(u) },
    u.enabled
      ? { label: 'Désactiver', icon: 'bi-person-slash', danger: true, onSelect: () => setPending({ kind: 'status', user: u, enabled: false }),
          disabled: isSelf(u), reason: 'Vous ne pouvez pas désactiver votre propre compte.' }
      : { label: 'Réactiver', icon: 'bi-person-check', onSelect: () => setPending({ kind: 'status', user: u, enabled: true }) },
  ]

  const header = !embedded && (
    <div className="pt-page-header ur-header">
      <div className="page-title">
        <h1>Utilisateurs et rôles</h1>
        <p>Gérez qui accède à LoadPilot et ce que chacun peut faire</p>
      </div>
      {canAdminister && (
        <button type="button" className="pt-btn-primary" onClick={() => setLocalCreateOpen(true)}>
          <i className="bi bi-person-plus" aria-hidden="true"></i> Ajouter un utilisateur
        </button>
      )}
    </div>
  )

  if (!canAdminister) {
    return (
      <div className={embedded ? undefined : 'pt-content'}>
        {header}
        <div className="pt-card">
          <div className="pt-empty-state" style={{ padding: '4rem 1.5rem' }}>
            <i className="bi bi-shield-lock" style={{ fontSize: '40px', color: 'var(--pt-text-light)' }}></i>
            <p style={{ fontSize: '15px', fontWeight: 600, color: 'var(--pt-text)' }}>Accès restreint</p>
            <p>La gestion des utilisateurs est réservée aux administrateurs.</p>
          </div>
        </div>
      </div>
    )
  }

  const summaryItem = (key: string, label: string, pressed: boolean, onClick: () => void) => (
    <button key={key} type="button" className="ur-summary-item" aria-pressed={pressed} onClick={onClick}>
      <strong>{label.split(' ')[0]}</strong> {label.split(' ').slice(1).join(' ')}
    </button>
  )
  const summary = [
    summaryItem('total', plural(counts.total, 'utilisateur', 'utilisateurs'), false, resetFilters),
    summaryItem('active', plural(counts.active, 'actif', 'actifs'), statusFilter === 'active' && roleFilter === 'all',
      () => { setRoleFilter('all'); setStatusFilter(statusFilter === 'active' ? 'all' : 'active') }),
    ...(counts.disabled > 0 ? [summaryItem('disabled', plural(counts.disabled, 'désactivé', 'désactivés'), statusFilter === 'disabled' && roleFilter === 'all',
      () => { setRoleFilter('all'); setStatusFilter(statusFilter === 'disabled' ? 'all' : 'disabled') })] : []),
    ...APP_ROLES.map((r) => summaryItem(r.role, plural(counts.byRole[r.role], ...ROLE_COUNT_LABEL[r.role]), roleFilter === r.role && statusFilter === 'all',
      () => { setStatusFilter('all'); setRoleFilter(roleFilter === r.role ? 'all' : r.role) })),
    ...(counts.none > 0 ? [summaryItem('none', plural(counts.none, 'sans rôle', 'sans rôle'), roleFilter === 'none',
      () => { setStatusFilter('all'); setRoleFilter(roleFilter === 'none' ? 'all' : 'none') })] : []),
  ]

  return (
    <div className={embedded ? undefined : 'pt-content'}>
      {header}

      {error && (
        <div className="pt-alert-banner danger mb-3" role="alert">
          <i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={loadUsers}>Réessayer</button>
        </div>
      )}

      {!loading && users.length > 0 && (
        <nav className="ur-summary" aria-label="Résumé des utilisateurs (cliquer pour filtrer)">
          {summary.flatMap((item, i) => (i === 0 ? [item] : [<span key={`sep-${i}`} className="ur-summary-sep" aria-hidden="true">·</span>, item]))}
        </nav>
      )}

      <div className="ur-filters">
        <div className="pt-search">
          <i className="bi bi-search" aria-hidden="true"></i>
          <input type="search" placeholder="Rechercher par nom, nom d’utilisateur ou e-mail…" aria-label="Rechercher un utilisateur"
            value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
        <select className="pt-form-control" aria-label="Filtrer par rôle" value={roleFilter} onChange={(e) => setRoleFilter(e.target.value as RoleFilter)}>
          <option value="all">Tous les rôles</option>
          {APP_ROLES.map((r) => <option key={r.role} value={r.role}>{r.label}</option>)}
          <option value="none">Sans rôle</option>
        </select>
        <select className="pt-form-control" aria-label="Filtrer par statut" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}>
          <option value="all">Tous les statuts</option>
          <option value="active">Actifs</option>
          <option value="disabled">Désactivés</option>
        </select>
        {filtersActive && (
          <button type="button" className="pt-btn-ghost ur-reset" onClick={resetFilters}><i className="bi bi-x-lg" aria-hidden="true"></i> Effacer les filtres</button>
        )}
      </div>

      <div className="pt-card ur-card">
        {loading ? (
          <table className="ur-table" aria-busy="true"><tbody>
            {[0, 1, 2].map((i) => <tr key={i} className="ur-skeleton-row"><td colSpan={5}><div className="ex-skeleton" style={{ width: `${70 - i * 12}%` }}></div></td></tr>)}
          </tbody></table>
        ) : filtered.length === 0 ? (
          <div className="ur-empty">
            <i className="bi bi-people" aria-hidden="true"></i>
            <p>{users.length === 0 ? 'Aucun utilisateur pour le moment.' : 'Aucun utilisateur ne correspond à ces filtres.'}</p>
            {filtersActive && <button type="button" className="pt-btn-outline" onClick={resetFilters}>Effacer les filtres</button>}
          </div>
        ) : (
          <table className="ur-table">
            <thead>
              <tr>
                <th scope="col">Utilisateur</th>
                <th scope="col">Rôle</th>
                <th scope="col">Statut</th>
                <th scope="col">Dernière connexion</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {pageItems.map((u) => {
                const name = displayNameOf(u)
                return (
                  <tr key={u.id} className={`ur-row ${openUserId === u.id ? 'selected' : ''}`} tabIndex={0}
                    aria-label={`${name}, ouvrir le détail`}
                    onClick={(e) => { if (!(e.target as HTMLElement).closest('button, a, [role="menu"]')) setOpenUserId(u.id) }}
                    onKeyDown={(e) => { if ((e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) { e.preventDefault(); setOpenUserId(u.id) } }}>
                    <td>
                      <div className="ur-person">
                        <UserAvatar user={u} />
                        <div className="ur-person-text">
                          <span className="ur-person-name">{name}{isSelf(u) && <span className="ur-you">vous</span>}</span>
                          <span className="ur-person-mail">{u.email ?? u.username}</span>
                        </div>
                      </div>
                    </td>
                    <td className="ur-cell-role"><RoleBadge role={u.role} /></td>
                    <td className="ur-cell-status"><StatusLabel enabled={u.enabled} /></td>
                    <td className="ur-cell-last"><LastLogin iso={u.lastLoginAt} /></td>
                    <td><RowActionsMenu actions={rowActions(u)} label={`Actions pour ${name}`} width={240} /></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
        {!loading && filtered.length > 0 && (
          <Pagination page={page} totalPages={totalPages} onPageChange={setPage} startIndex={startIndex} endIndex={endIndex} totalItems={totalItems} itemLabel="utilisateurs" />
        )}
      </div>

      {openUser && (
        <UserDrawer user={openUser} isSelf={isSelf(openUser)} busy={busy} onClose={() => setOpenUserId(null)}
          onRequestRole={(role) => setPending({ kind: 'role', user: openUser, role })}
          onRequestReset={() => setResetUser(openUser)}
          onRequestStatus={(enabled) => setPending({ kind: 'status', user: openUser, enabled })} />
      )}

      {pending && <ConfirmAction action={pending} busy={busy} onCancel={() => setPending(null)} onConfirm={confirmPending} />}

      {showCreate && <CreateUserModal onClose={closeCreate} onCreated={onCreated} />}

      {resetUser && (
        <ResetPasswordDialog user={resetUser} onClose={() => setResetUser(null)}
          onDone={(message) => { setResetUser(null); showToast(message, 'success') }} />
      )}
    </div>
  )
}

function ConfirmAction({ action, busy, onCancel, onConfirm }: {
  action: PendingAction
  busy: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  const name = displayNameOf(action.user)
  let title: string
  let confirmLabel: string
  let danger = false
  let body: JSX.Element
  if (action.kind === 'role') {
    const next = roleMeta(action.role)
    title = 'Changer le rôle'
    confirmLabel = 'Changer le rôle'
    body = (<>
      <p>Le rôle de <strong>{name}</strong> passera de {action.user.role ? <RoleBadge role={action.user.role} /> : 'aucun rôle'} à <RoleBadge role={action.role} />.</p>
      <p className="ur-hint">{next.summary} Le changement s’applique à sa prochaine connexion ou au renouvellement de sa session (5 minutes au plus).</p>
    </>)
  } else if (action.kind === 'status' && !action.enabled) {
    title = 'Désactiver le compte'
    confirmLabel = 'Désactiver'
    danger = true
    body = (<>
      <p><strong>{name}</strong> ne pourra plus se connecter à LoadPilot.</p>
      <p className="ur-hint">Ses exécutions, rapports et planifications sont conservés. Vous pourrez réactiver le compte à tout moment.</p>
    </>)
  } else {
    title = 'Réactiver le compte'
    confirmLabel = 'Réactiver'
    body = <p><strong>{name}</strong> pourra de nouveau se connecter à LoadPilot avec son mot de passe actuel.</p>
  }
  return (
    <UserDialog title={title} titleId="ur-confirm-title" onClose={onCancel} busy={busy}
      footer={<>
        <button type="button" className="pt-btn-outline" onClick={onCancel} disabled={busy}>Annuler</button>
        <button type="button" className={danger ? 'ur-btn-danger' : 'pt-btn-primary'} onClick={onConfirm} disabled={busy}>
          {busy && <i className="bi bi-arrow-repeat pt-spin me-1" aria-hidden="true"></i>}{confirmLabel}
        </button>
      </>}>
      {body}
    </UserDialog>
  )
}

export default UsersRoles
