import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import PageTabs from '../components/PageTabs'
import UsersRoles from './UsersRoles'
import RolesCatalog from './RolesCatalog'
import { useAuth } from '../context/AuthContext'
import './executions/executions.css'
import './users/users.css'

// ============================================================
// « Utilisateurs et rôles » : l'ancienne page « Users & Roles » (onglet
// « Utilisateurs ») et le « Catalogue des rôles » (onglet « Rôles »).
// Anciennes adresses : /users-roles, /roles-catalog.
// ============================================================

export default function UtilisateursRoles() {
  const { authProvider, rawRoles } = useAuth()
  const [params] = useSearchParams()
  const [createOpen, setCreateOpen] = useState(false)
  const canAdminister = authProvider === 'keycloak' && rawRoles.includes('ROLE_SUPER_ADMIN')
  const onUsersTab = (params.get('tab') ?? 'utilisateurs') !== 'roles'

  return (
    <div className="pt-content">
      <div className="pt-page-header ur-header">
        <div className="page-title">
          <h1>Utilisateurs et rôles</h1>
          <p>Gérez qui accède à LoadPilot et ce que chacun peut faire</p>
        </div>
        {canAdminister && onUsersTab && (
          <button type="button" className="pt-btn-primary" onClick={() => setCreateOpen(true)}>
            <i className="bi bi-person-plus" aria-hidden="true"></i> Ajouter un utilisateur
          </button>
        )}
      </div>
      <PageTabs label="Sections utilisateurs et rôles" tabs={[
        { id: 'utilisateurs', label: 'Utilisateurs', icon: 'bi-people', content: <UsersRoles embedded createOpen={createOpen} onCreateClose={() => setCreateOpen(false)} /> },
        { id: 'roles', label: 'Rôles', icon: 'bi-shield-lock', content: <RolesCatalog embedded /> },
      ]} />
    </div>
  )
}
