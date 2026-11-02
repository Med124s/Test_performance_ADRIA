import PageTabs, { PageTab } from '../components/PageTabs'
import { useAuth } from '../context/AuthContext'
import Settings from './Settings'
import Configurations from './Configurations'
import SettingsIntegrations from './SettingsIntegrations'
import './executions/executions.css'

// « Paramètres » : onglets Mon profil, Système et Intégrations.

export default function Parametres() {
  const { authProvider, rawRoles } = useAuth()
  const isSuperAdmin = authProvider === 'keycloak' && rawRoles.includes('ROLE_SUPER_ADMIN')

  const tabs: PageTab[] = [
    { id: 'profil', label: 'Mon profil', icon: 'bi-person-circle', content: <Settings embedded /> },
    ...(isSuperAdmin ? [
      { id: 'systeme', label: 'Système', icon: 'bi-hdd-stack', content: <Configurations embedded /> },
      { id: 'integrations', label: 'Intégrations', icon: 'bi-plug', content: <SettingsIntegrations embedded /> },
    ] : []),
  ]

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Paramètres</h1>
          <p>{isSuperAdmin ? 'Votre profil, les limites du serveur et les intégrations' : 'Votre profil et vos préférences'}</p>
        </div>
      </div>
      <PageTabs tabs={tabs} label="Sections des paramètres" />
    </div>
  )
}
