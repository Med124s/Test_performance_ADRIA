import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { Application, Scenario, StepHeader } from '../types'
import { applicationsApi } from '../services/api/applications'
import { scenariosApi } from '../services/api/scenarios'
import { useApiList } from '../hooks/useApiResource'
import { getRecommendedScenarios, HIGHLIGHTED_SCENARIO_NAMES, RecommendedScenarioDef } from '../data/recommendedScenarios'
import { saveScenarioWithSteps, LocalStepDraft } from '../services/scenarioSave'
import { useAuth } from '../context/AuthContext'

const MEDALS = ['🥇', '🥈', '🥉']

/**
 * Étape préalable à la création d'un scénario : choisir l'Application
 * d'abord (aucune présélection automatique), puis proposer ses scénarios
 * existants et ses scénarios recommandés — remplace l'ancien comportement
 * où CreateScenario.tsx présélectionnait silencieusement la première
 * application de la liste.
 */
function CreateScenarioLanding() {
  const navigate = useNavigate()
  const { canEdit } = useAuth()
  const { data: applications, loading: appsLoading } = useApiList<Application>(() => applicationsApi.getAll())
  const { data: scenarios } = useApiList<Scenario>(() => scenariosApi.getAll())

  const [selectedApp, setSelectedApp] = useState<Application | null>(null)
  const [usingScenario, setUsingScenario] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const existingScenarios = selectedApp ? scenarios.filter((s) => s.applicationId === selectedApp.id) : []
  const recommended = selectedApp ? getRecommendedScenarios(selectedApp.name) : []

  const handleUseRecommended = async (def: RecommendedScenarioDef) => {
    if (!selectedApp || !canEdit || usingScenario) return
    setUsingScenario(def.name)
    setActionError(null)
    try {
      const localSteps: LocalStepDraft[] = def.steps.map((s, idx) => ({
        id: `tmp-${idx}`,
        order: idx + 1,
        name: s.name,
        method: s.method,
        url: s.url,
        bodyJson: s.bodyJson,
        headers: s.headers?.map((h, i): StepHeader => ({ id: i + 1, key: h.key, value: h.value, enabled: true })),
      }))
      const saved = await saveScenarioWithSteps({
        scenarioId: null,
        name: def.name,
        applicationId: selectedApp.id,
        description: def.description,
        createdBy: 'Vous',
        localSteps,
        existingStepIds: new Set(),
        status: 'Actif',
      })
      navigate(`/scenarios/create?edit=${saved.id}`)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "Erreur lors de la création du scénario")
      setUsingScenario(null)
    }
  }

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Nouveau scénario</h1>
          <p>{selectedApp ? `Application : ${selectedApp.name}` : 'Choisissez une application pour commencer'}</p>
        </div>
        <TopBar searchPlaceholder="" />
      </div>

      {actionError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {actionError}
        </div>
      )}

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
                    <i className={`bi ${app.icon}`}></i>
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

          {recommended.length > 0 && (
            <div className="pt-card mb-3" style={{ padding: 0 }}>
              <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                <h6 style={{ fontSize: '14.5px', fontWeight: 600, margin: 0 }}>Scénarios recommandés</h6>
                <p style={{ fontSize: '12px', color: 'var(--pt-text-muted)', margin: '2px 0 0' }}>
                  Une sélection prête à l'emploi pour cette application — pas un classement calculé.
                </p>
              </div>
              <div className="p-3 d-flex flex-column gap-2">
                {recommended.map((def) => {
                  const medalIdx = HIGHLIGHTED_SCENARIO_NAMES.indexOf(def.name)
                  return (
                    <div
                      key={def.name}
                      className="d-flex align-items-center justify-content-between gap-3"
                      style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '0.75rem 1rem' }}
                    >
                      <div>
                        <div style={{ fontSize: '13.5px', fontWeight: 600 }}>
                          {medalIdx >= 0 && <span className="me-1">{MEDALS[medalIdx]}</span>}
                          {def.name}
                        </div>
                        <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>{def.description}</div>
                      </div>
                      {canEdit && (
                        <button
                          className="pt-btn-primary"
                          style={{ fontSize: '12.5px', flexShrink: 0 }}
                          onClick={() => handleUseRecommended(def)}
                          disabled={usingScenario === def.name}
                        >
                          {usingScenario === def.name ? (
                            <><i className="bi bi-arrow-repeat me-1 pt-spin"></i> Création...</>
                          ) : (
                            'Utiliser ce scénario'
                          )}
                        </button>
                      )}
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {canEdit && (
            <button className="pt-btn-outline" onClick={() => navigate(`/scenarios/create?new=1&app=${selectedApp.id}`)}>
              <i className="bi bi-plus-lg me-1"></i> Créer un scénario personnalisé
            </button>
          )}
        </>
      )}
    </div>
  )
}

export default CreateScenarioLanding
