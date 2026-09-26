import { useEffect, useState } from 'react'
import TopBar from '../components/TopBar'
import { useAuth } from '../context/AuthContext'
import { systemConfigBackendApi } from '../services/api/systemConfigBackend'
import { BackendSystemConfigResponse } from '../types/backendContracts'
import { ApiError } from '../services/api/httpClient'

// ============================================================
// P1-D — page RÉELLE (remplace l'ancien ComingSoonPage), mais
// DÉLIBÉRÉMENT LECTURE SEULE : voir le rapport P1-D, section
// "Configurations" pour l'analyse complète. Aucune configuration
// mutable/persistée n'a été créée : les limites ci-dessous restent
// pilotées par variable d'environnement (voir application.yml côté
// backend), un choix déjà établi et éprouvé depuis P0-B/P1-B. Cette page
// se contente d'exposer honnêtement leur valeur RÉELLEMENT active
// (GET /api/system/config, SUPER_ADMIN) — jamais un formulaire qui
// prétendrait les modifier alors que le backend ne les persiste pas.
// ============================================================

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0: return err.message
      case 401: return 'Vous devez être connecté (Keycloak) pour consulter la configuration système.'
      case 403: return "Réservé aux Super Administrateurs."
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function Configurations() {
  const { authProvider, rawRoles } = useAuth()
  const isKeycloak = authProvider === 'keycloak'
  const isSuperAdmin = isKeycloak && rawRoles.includes('ROLE_SUPER_ADMIN')

  const [config, setConfig] = useState<BackendSystemConfigResponse | null>(null)
  const [loading, setLoading] = useState(isSuperAdmin)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    if (!isSuperAdmin) return
    setLoading(true)
    setError(null)
    systemConfigBackendApi.get()
      .then(setConfig)
      .catch((err) => setError(describeApiError(err, 'Impossible de charger la configuration système.')))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [isSuperAdmin])

  const rows = config ? [
    { label: 'Utilisateurs virtuels max. par exécution', value: config.maxVirtualUsersPerExecution, icon: 'bi-people' },
    { label: 'Utilisateurs virtuels max. (global)', value: config.maxGlobalVirtualUsers, icon: 'bi-diagram-3' },
    { label: 'Exécutions simultanées max.', value: config.maxConcurrentExecutions, icon: 'bi-play-circle' },
    { label: "Timeout d'exécution (par requête)", value: `${config.executionTimeoutSeconds} s`, icon: 'bi-stopwatch' },
    { label: 'Timeout du test de disponibilité', value: `${config.availabilityTimeoutSeconds} s`, icon: 'bi-wifi' },
    { label: 'Intervalle du scheduler (planifications)', value: `${config.schedulerPollIntervalMs} ms`, icon: 'bi-calendar-week' },
  ] : []

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Configurations</h1>
          <p>Limites opérationnelles réellement actives sur ce backend</p>
        </div>
        <TopBar searchPlaceholder="" />
      </div>

      {!isSuperAdmin ? (
        <div className="pt-empty-state">
          <i className="bi bi-shield-lock" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
          <p>Cette page est réservée aux Super Administrateurs.</p>
        </div>
      ) : (
        <>
          {error && (
            <div className="pt-alert-banner danger mb-3">
              <i className="bi bi-exclamation-triangle-fill"></i>
              {error}
              <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={load}>Réessayer</button>
            </div>
          )}

          <div className="pt-card mb-3" style={{ padding: '0.85rem 1.1rem', fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
            <i className="bi bi-info-circle me-2 text-primary"></i>
            Ces limites sont pilotées par variable d'environnement côté serveur — elles ne sont <strong>pas modifiables depuis cette page</strong>.
            Pour les changer, ajustez la variable correspondante et redémarrez le backend.
          </div>

          <div className="pt-card" style={{ padding: 0 }}>
            {loading ? (
              <div className="pt-empty-state">
                <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
                <p>Chargement de la configuration...</p>
              </div>
            ) : !config ? null : (
              <div className="pt-table-wrapper">
                <table className="pt-table">
                  <thead><tr><th>Paramètre</th><th style={{ textAlign: 'right' }}>Valeur active</th></tr></thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.label}>
                        <td><i className={`bi ${row.icon} me-2 text-primary`}></i>{row.label}</td>
                        <td style={{ textAlign: 'right', fontFamily: 'monospace', fontWeight: 600 }}>{row.value}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  )
}

export default Configurations
