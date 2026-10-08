import PageTabs, { PageTab } from '../components/PageTabs'
import { useAuth } from '../context/AuthContext'
import Settings from './Settings'
import Configurations from './Configurations'
import SettingsIntegrations from './SettingsIntegrations'
import './executions/executions.css'

// ============================================================
// « Paramètres » : une seule page pour l'ancien « Settings » (profil et
// préférences, onglet « Mon profil », pour tous) et l'ancien
// « Configurations » (limites du serveur, onglet « Système ») avec les
// intégrations (onglet « Intégrations ») — ces deux derniers réservés au
// Super Administrateur, comme leurs données côté serveur.
// Anciennes adresses : /settings, /configurations, /settings/integrations.
// ============================================================

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
