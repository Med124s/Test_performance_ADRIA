import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { usersBackendApi } from '../services/api/usersBackend'
import type { BackendAppRole, BackendUserSummaryResponse } from '../types/backendContracts'
import { ApiError } from '../services/api/httpClient'
import { APP_ROLES } from '../utils/appRoles'
import { ROLE_PERMISSIONS } from '../utils/rolePermissions'
import { RoleBadge } from './users/userParts'
import './users/users.css'

// ============================================================
// Onglet « Rôles » : les 3 rôles applicatifs réels (Keycloak) en lecture
// seule — une carte par rôle (avec, pour un administrateur, le nombre réel
// d'utilisateurs, via GET /api/users) et le tableau de ce que chacun peut
// faire. Ce tableau (utils/rolePermissions.ts) est vérifié par un test
// contre les annotations @PreAuthorize du backend : il ne peut pas diverger
// des règles réellement appliquées sans que le test échoue.
// ============================================================

/** embedded : affichée dans un onglet (Utilisateurs et rôles), sans son propre en-tête. */
function RolesCatalog({ embedded = false }: { embedded?: boolean } = {}) {
  const { authProvider, rawRoles } = useAuth()
  const isSuperAdmin = authProvider === 'keycloak' && rawRoles.includes('ROLE_SUPER_ADMIN')
  const [users, setUsers] = useState<BackendUserSummaryResponse[] | null>(null)

  useEffect(() => {
    if (!isSuperAdmin) return
    // Seulement pour compter les utilisateurs par rôle ; en cas d'échec le
    // nombre n'est simplement pas affiché (l'onglet reste une référence).
    usersBackendApi.list().then(setUsers).catch((err) => { if (!(err instanceof ApiError)) console.error(err) })
  }, [isSuperAdmin])

  const countByRole = useMemo(() => {
    const counts: Record<BackendAppRole, number> = { SUPER_ADMIN: 0, PERFORMANCE_ENGINEER: 0, VIEWER: 0 }
    users?.forEach((u) => { if (u.role) counts[u.role]++ })
    return counts
  }, [users])

  return (
    <div className={embedded ? undefined : 'pt-content'}>
      {!embedded && (
        <div className="pt-page-header">
          <div className="page-title">
            <h1>Rôles</h1>
            <p>Les 3 rôles de LoadPilot et ce que chacun peut faire</p>
          </div>
        </div>
      )}

      <div className="ur-role-cards">
        {APP_ROLES.map((meta) => (
          <article key={meta.role} className={`ur-role-card ur-role-card--${meta.tone}`} aria-labelledby={`ur-role-${meta.role}`}>
            <div className="ur-role-card-head">
              <span className="ur-role-card-icon" aria-hidden="true"><i className={`bi ${meta.icon}`}></i></span>
              <div>
                <h3 id={`ur-role-${meta.role}`}>{meta.label}</h3>
                {isSuperAdmin && users && (
                  <span className="ur-role-card-count">
                    <Link to="/utilisateurs?tab=utilisateurs">{countByRole[meta.role]} utilisateur{countByRole[meta.role] > 1 ? 's' : ''}</Link>
                  </span>
                )}
              </div>
            </div>
            <p>{meta.description}</p>
          </article>
        ))}
      </div>

      <div className="pt-card ur-card">
        <div className="pt-table-wrapper">
          <table className="ur-perm-table">
            <caption className="visually-hidden">Permissions de chaque rôle</caption>
            <thead>
              <tr>
                <th scope="col">Action</th>
                {APP_ROLES.map((meta) => <th key={meta.role} scope="col"><RoleBadge role={meta.role} /></th>)}
              </tr>
            </thead>
            <tbody>
              {ROLE_PERMISSIONS.map((perm) => (
                <tr key={perm.id}>
                  <th scope="row">
                    <span className="ur-perm-label"><i className={`bi ${perm.icon}`} aria-hidden="true"></i>{perm.label}</span>
                  </th>
                  {APP_ROLES.map((meta) => (
                    <td key={meta.role}>
                      {perm.roles.includes(meta.role)
                        ? <><i className="bi bi-check-circle-fill ur-yes" aria-hidden="true"></i><span className="visually-hidden">Oui</span></>
                        : <><span className="ur-no" aria-hidden="true">—</span><span className="visually-hidden">Non</span></>}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

export default RolesCatalog
