import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { BackendApplicationResponse, BackendScenarioResponse } from '../types/backendContracts'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { useApiList } from '../hooks/useApiResource'
import { useAuth } from '../context/AuthContext'

// ============================================================
// Restauration du design ancien (route /scenarios/new, ex-
// CreateScenarioLanding.tsx) — étape préalable : choisir d'abord
// l'Application, puis proposer ses scénarios existants ou la création d'un
// nouveau. Contrairement à l'ancienne version (JSON Server), toutes les
// données viennent du backend Spring Boot réel (applicationsBackendApi /
// scenariosBackendApi) — aucune donnée mock, aucun JSON Server.
//
// La liste "scénarios recommandés" de l'ancien design (data/
// recommendedScenarios.ts) n'a volontairement PAS été restaurée : ce
// contenu était une simple maquette décorative sans lien avec les données
// réelles d'une installation — la restaurer aurait recréé une
// fonctionnalité fictive.
// ============================================================

function ScenarioLanding() {
  const navigate = useNavigate()
  const { authProvider, rawRoles } = useAuth()
  const isKeycloak = authProvider === 'keycloak'
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  const { data: applications, loading: appsLoading } = useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const { data: scenarios } = useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll())

  const [selectedApp, setSelectedApp] = useState<BackendApplicationResponse | null>(null)

  const existingScenarios = selectedApp ? scenarios.filter((s) => s.applicationId === selectedApp.id) : []

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Nouveau scénario</h1>
          <p>{selectedApp ? `Application : ${selectedApp.name}` : 'Choisissez une application pour commencer'}</p>
        </div>
        <TopBar searchPlaceholder="" />
      </div>

      {!selectedApp ? (
        <div className="pt-card" style={{ padding: 0 }}>
          <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
            <h6 style={{ fontSize: '14.5px', fontWeight: 600, margin: 0 }}>Applications</h6>
          </div>
          {appsLoading ? (
            <div className="pt-empty-state">
              <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
              <p>Chargement des applications...</p>
            </div>
          ) : applications.length === 0 ? (
            <div className="pt-empty-state">
              <i className="bi bi-globe2" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
              <p>Aucune application n'a encore été créée.</p>
              <button className="pt-btn-primary" onClick={() => navigate('/applications')}>
                Ajouter une application
              </button>
            </div>
          ) : (
            <div className="p-3 d-flex flex-column gap-2">
              {applications.map((app) => (
                <button
                  key={app.id}
                  onClick={() => setSelectedApp(app)}
                  className="d-flex align-items-center gap-3"
                  style={{
                    textAlign: 'left',
                    background: 'var(--pt-bg)',
                    border: '1px solid var(--pt-border)',
                    borderRadius: 'var(--pt-radius-sm)',
                    padding: '0.9rem 1rem',
                    cursor: 'pointer',
                  }}
                >
                  <div
                    style={{
                      width: '38px', height: '38px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center',
                      background: 'var(--pt-primary-light)', color: 'var(--pt-primary)', fontSize: '17px', flexShrink: 0,
                    }}
                  >
                    <i className="bi bi-globe2"></i>
                  </div>
                  <div className="flex-grow-1">
                    <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--pt-text)' }}>{app.name}</div>
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>{app.url}</div>
                  </div>
                  <i className="bi bi-chevron-right" style={{ color: 'var(--pt-text-muted)' }}></i>
                </button>
              ))}
            </div>
          )}
        </div>
      ) : (
        <>
          <button className="pt-btn-outline mb-3" onClick={() => setSelectedApp(null)}>
            <i className="bi bi-arrow-left me-1"></i> Changer d'application
          </button>

          {existingScenarios.length > 0 && (
            <div className="pt-card mb-3" style={{ padding: 0 }}>
              <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                <h6 style={{ fontSize: '14.5px', fontWeight: 600, margin: 0 }}>
                  Scénarios existants <span className="pt-pill neutral ms-1">{existingScenarios.length}</span>
                </h6>
              </div>
              <div className="p-3 d-flex flex-column gap-2">
                {existingScenarios.map((s) => (
                  <button
                    key={s.id}
                    onClick={() => navigate(`/scenarios/create?edit=${s.id}`)}
                    className="d-flex align-items-center justify-content-between"
                    style={{
                      background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)',
                      padding: '0.75rem 1rem', cursor: 'pointer', textAlign: 'left',
                    }}
                  >
                    <span style={{ fontSize: '13.5px', fontWeight: 600 }}>{s.name}</span>
                    <i className="bi bi-pencil" style={{ color: 'var(--pt-text-muted)', fontSize: '13px' }}></i>
                  </button>
                ))}
              </div>
            </div>
          )}

          {canWrite && (
            <button className="pt-btn-primary" onClick={() => navigate(`/scenarios/create?app=${selectedApp.id}`)}>
              <i className="bi bi-plus-lg me-1"></i> Créer un scénario personnalisé
            </button>
          )}
        </>
      )}
    </div>
  )
}

export default ScenarioLanding
