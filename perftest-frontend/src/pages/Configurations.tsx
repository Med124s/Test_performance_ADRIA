import { useEffect, useState } from 'react'
import TopBar from '../components/TopBar'
import { useAuth } from '../context/AuthContext'
import { settingsBackendApi } from '../services/api/settingsBackend'
import { BackendStorageInfo } from '../types/backendContracts'
import AuditSettingsCard from './audit/AuditSettingsCard'
import ResultsRetentionCard from './audit/ResultsRetentionCard'
import LimitsCard from './settings/LimitsCard'
import ThresholdsCard from './settings/ThresholdsCard'
import LoadDefaultsCard from './settings/LoadDefaultsCard'
import './audit/audit.css'
import './settings/settings.css'

export { formatDuration } from './settings/formatDuration'

// ============================================================
// Paramètres > « Système » (Super administrateur) :
//  - limites des tests (modifiables sous le plafond du serveur) et délais en lecture seule ;
//  - charge et seuils par défaut des nouveaux scénarios ;
//  - conservation des résultats et journal d'audit, avec la taille de la base et l'état des purges.
// ============================================================

/** embedded : affichée dans un onglet (Paramètres), sans son propre en-tête. */
function Configurations({ embedded = false }: { embedded?: boolean } = {}) {
  const { authProvider, rawRoles } = useAuth()
  const isSuperAdmin = authProvider === 'keycloak' && rawRoles.includes('ROLE_SUPER_ADMIN')
  const [storage, setStorage] = useState<BackendStorageInfo | null>(null)
  /** Rechargée après un changement de limites (avertissement si la charge par défaut les dépasse). */
  const [limitsVersion, setLimitsVersion] = useState(0)

  useEffect(() => {
    if (!isSuperAdmin) return
    settingsBackendApi.storage().then(setStorage).catch(() => setStorage(null))
  }, [isSuperAdmin])

  return (
    <div className={embedded ? undefined : 'pt-content'}>
      {!embedded && (
        <div className="pt-page-header">
          <div className="page-title">
            <h1>Système</h1>
            <p>Limites, valeurs par défaut et conservation des données</p>
          </div>
          <TopBar searchPlaceholder="" />
        </div>
      )}

      {!isSuperAdmin ? (
        <div className="pt-empty-state">
          <i className="bi bi-shield-lock" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }} aria-hidden="true"></i>
          <p>Cette page est réservée au Super administrateur.</p>
        </div>
      ) : (
        <>
          <LimitsCard onSaved={() => setLimitsVersion((v) => v + 1)} />
          <LoadDefaultsCard key={limitsVersion} />
          <ThresholdsCard />
          <ResultsRetentionCard storage={storage} />
          <AuditSettingsCard storage={storage} />
        </>
      )}
    </div>
  )
}

export default Configurations
