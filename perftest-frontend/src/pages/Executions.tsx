import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import TopBar from '../components/TopBar'
import LaunchScenarioModal from '../components/LaunchScenarioModal'
import { Application, Execution, Scenario, Step, StepRunStatus } from '../types'
import { applicationsApi } from '../services/api/applications'
import { scenariosApi } from '../services/api/scenarios'
import { executionsApi } from '../services/api/executions'
import { stepsApi } from '../services/api/steps'
import { useApiList } from '../hooks/useApiResource'
import { usePagination } from '../hooks/usePagination'
import Pagination from '../components/Pagination'
import { useScenarioLauncher } from '../hooks/useScenarioLauncher'
import { consumeExecutionPrefill } from '../utils/executionBridge'
import { useAuth } from '../context/AuthContext'

function Executions() {
  const navigate = useNavigate()
  const { canEdit } = useAuth()

  // Données réelles depuis JSON Server.
  const { data: executions, loading: execLoading, refetch: refetchExecutions } =
    useApiList<Execution>(() => executionsApi.getAll())
  const { data: scenarios } = useApiList<Scenario>(() => scenariosApi.getAll())
  const { data: applications } = useApiList<Application>(() => applicationsApi.getAll())
  // Étapes de TOUS les scénarios — sert à afficher, sous le nom du
  // scénario, le détail réel de chaque étape exécutée (méthode/URL +
  // statut) sans devoir ouvrir le détail complet de l'exécution.
  const { data: allSteps } = useApiList<Step>(() => stepsApi.getAll())

  const scenarioById = useMemo(() => new Map(scenarios.map((s) => [s.id, s])), [scenarios])
  const appById = useMemo(() => new Map(applications.map((a) => [a.id, a])), [applications])
  const stepById = useMemo(() => new Map(allSteps.map((s) => [s.id, s])), [allSteps])

  const stepStatusLabel = (status: StepRunStatus): string => {
    switch (status) {
      case 'success': return 'Réussi'
      case 'error': return 'Échoué'
      case 'skipped': return 'Ignorée'
      case 'running': return 'En cours'
      default: return 'En attente'
    }
  }
  const stepStatusColor = (status: StepRunStatus): string => {
    switch (status) {
      case 'success': return 'var(--pt-success)'
      case 'error': return 'var(--pt-danger)'
      case 'skipped': return 'var(--pt-text-muted)'
      case 'running': return 'var(--pt-info)'
      default: return 'var(--pt-warning)'
    }
  }

  const [activeTab, setActiveTab] = useState<'all' | 'running' | 'success' | 'warning' | 'failed'>('all')
  const [searchQuery, setSearchQuery] = useState('')

  // Flux de lancement (modale + progression réelle) partagé avec la page
  // Scénarios — voir hooks/useScenarioLauncher.
  const launcher = useScenarioLauncher(refetchExecutions)
  const { openModal, openForScenario } = launcher

  // Arrivée depuis la page Scénarios / ExecutionDetail ("Relancer le test") :
  // on ouvre directement la modale de lancement, pré-remplie avec le bon
  // scénario.
  useEffect(() => {
    const prefill = consumeExecutionPrefill()
    if (prefill && scenarios.length > 0) {
      const sc = scenarios.find((s) => s.name === prefill.scenario)
      if (sc) openForScenario(sc)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scenarios])

  const getStatusBadge = (status: Execution['status']) => {
    const map: Record<Execution['status'], { cls: string }> = {
      'Réussie': { cls: 'success' },
      'Avec erreurs': { cls: 'warning' },
      'Échouée': { cls: 'danger' },
      'En cours': { cls: 'info' },
      'Suspendue': { cls: 'neutral' },
      'Annulée': { cls: 'neutral' },
    }
    const { cls } = map[status]
    return (
      <span className={`pt-pill ${cls}`}>
        <span style={{ width: '6px', height: '6px', borderRadius: '50%', display: 'inline-block', marginRight: '5px', backgroundColor: cls === 'success' ? 'var(--pt-success)' : cls === 'warning' ? 'var(--pt-warning)' : cls === 'danger' ? 'var(--pt-danger)' : cls === 'info' ? 'var(--pt-info)' : 'var(--pt-neutral)' }}></span>
        {status}
      </span>
    )
  }

  const filteredExecutions = executions.filter(item => {
    if (activeTab === 'running' && item.status !== 'En cours') return false
    if (activeTab === 'success' && item.status !== 'Réussie') return false
    if (activeTab === 'warning' && item.status !== 'Avec erreurs') return false
    if (activeTab === 'failed' && item.status !== 'Échouée') return false
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase()
      const scenarioName = scenarioById.get(item.scenarioId)?.name ?? ''
      const appName = appById.get(item.applicationId)?.name ?? ''
      return item.id.toLowerCase().includes(q) || scenarioName.toLowerCase().includes(q) || appName.toLowerCase().includes(q)
    }
    return true
  })

  const { page, setPage, totalPages, pageItems, startIndex, endIndex, totalItems } = usePagination(filteredExecutions, 10)

  const handleSuspend = async (id: string) => {
    if (!canEdit) return
    await executionsApi.update(id, { status: 'Suspendue' })
    await refetchExecutions()
  }
  const handleStop = async (id: string) => {
    if (!canEdit) return
    await executionsApi.update(id, { status: 'Échouée' })
    await refetchExecutions()
  }

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Exécutions</h1>
          <p>Lancez et suivez vos exécutions de tests de charge</p>
        </div>
        <div className="d-flex align-items-center gap-3">
          {canEdit && (
            <button className="pt-btn-primary" onClick={openModal}>
              <i className="bi bi-play-fill fs-6"></i>
              Nouvelle exécution
            </button>
          )}
          <TopBar searchPlaceholder="Rechercher une exécution..." />
        </div>
      </div>

      {/* Stat Cards */}
      <div className="row g-3 mb-4">
        {[
          { label: 'Total Exécutions', value: executions.length, icon: 'bi-play-circle', color: 'blue' },
          { label: 'Réussies', value: executions.filter(e => e.status === 'Réussie').length, icon: 'bi-check-circle', color: 'green' },
          { label: 'Avec erreurs', value: executions.filter(e => e.status === 'Avec erreurs').length, icon: 'bi-exclamation-triangle', color: 'orange' },
          { label: 'Échouées', value: executions.filter(e => e.status === 'Échouée').length, icon: 'bi-x-circle', color: 'red' },
        ].map((c, i) => (
          <div key={i} className="col-12 col-sm-6 col-xl-3">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">{c.label}</div>
                  <div className="stat-value">{execLoading ? '—' : c.value}</div>
                </div>
                <div className={`stat-icon ${c.color}`}><i className={`bi ${c.icon}`}></i></div>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Table */}
      <div className="pt-card">
        <div className="d-flex flex-wrap align-items-center justify-content-between gap-3 mb-4 pb-3 border-bottom" style={{ borderColor: 'var(--pt-border)' }}>
          <div className="d-flex gap-2 flex-wrap">
            {(['all', 'running', 'success', 'warning', 'failed'] as const).map(tab => (
              <button
                key={tab}
                className={`btn btn-sm ${activeTab === tab ? 'btn-primary' : 'btn-outline-secondary'}`}
                onClick={() => setActiveTab(tab)}
                style={{ borderRadius: 'var(--pt-radius-sm)', fontWeight: 600, fontSize: '13px' }}
              >
                {tab === 'all' ? 'Toutes' : tab === 'running' ? 'En cours' : tab === 'success' ? 'Réussies' : tab === 'warning' ? 'Avec erreurs' : 'Échouées'}
              </button>
            ))}
          </div>
          <div className="pt-search" style={{ width: '240px' }}>
            <i className="bi bi-search"></i>
            <input type="text" placeholder="Rechercher..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
          </div>
        </div>

        {execLoading ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement des exécutions...</p>
          </div>
        ) : filteredExecutions.length === 0 ? (
          <div className="pt-empty-state">
            <i className="bi bi-play-circle" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
            <p>Aucune exécution pour l'instant. Lancez votre premier test !</p>
          </div>
        ) : (
        <div className="pt-table-wrapper">
          <table className="pt-table">
            <thead>
              <tr>
                <th>#</th>
                <th>Scénario</th>
                <th>Application</th>
                <th>Statut</th>
                <th>Utilisateurs</th>
                <th>Durée</th>
                <th>Démarré le</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {pageItems.map(exec => {
                const scenarioName = scenarioById.get(exec.scenarioId)?.name ?? '—'
                const appName = appById.get(exec.applicationId)?.name ?? '—'
                return (
                <tr key={exec.id}>
                  <td><span style={{ fontFamily: 'monospace', fontSize: '13px', fontWeight: 600, color: 'var(--pt-primary)' }}>#{exec.id}</span></td>
                  <td>
                    <button
                      onClick={() => navigate(`/scenarios?q=${encodeURIComponent(scenarioName)}`)}
                      title="Voir ce scénario"
                      style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '13.5px', fontWeight: 500, color: 'var(--pt-text)', textDecoration: 'none' }}
                    >
                      {scenarioName}
                    </button>
                    {exec.stepResults.length === 0 ? (
                      <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '2px' }}>Aucune étape exécutée</div>
                    ) : (
                      <div
                        title={exec.stepResults
                          .map((r, i) => {
                            const step = stepById.get(r.stepId)
                            const label = step ? `${step.method} ${step.url}` : String(r.stepId)
                            return `${i + 1}. ${label} — ${stepStatusLabel(r.status)}`
                          })
                          .join('  →  ')}
                        style={{
                          fontSize: '11px',
                          fontFamily: 'monospace',
                          marginTop: '2px',
                          maxWidth: '320px',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {exec.stepResults.map((r, i) => {
                          const step = stepById.get(r.stepId)
                          const label = step ? `${step.method} ${step.url}` : String(r.stepId)
                          return (
                            <span key={i}>
                              {i > 0 && <span style={{ color: 'var(--pt-text-muted)' }}>{'  →  '}</span>}
                              <span style={{ color: 'var(--pt-text-muted)' }}>{i + 1}. {label}</span>{' '}
                              <span style={{ color: stepStatusColor(r.status), fontWeight: 600 }}>({stepStatusLabel(r.status)})</span>
                            </span>
                          )
                        })}
                      </div>
                    )}
                  </td>
                  <td>
                    <button
                      onClick={() => navigate(`/scenarios?app=${encodeURIComponent(appName)}`)}
                      title="Voir les scénarios de cette application"
                      style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '13px', color: 'var(--pt-text-muted)', textDecoration: 'none' }}
                    >
                      {appName}
                    </button>
                  </td>
                  <td>{getStatusBadge(exec.status)}</td>
                  <td>
                    <div className="d-flex align-items-center gap-2">
                      <i className="bi bi-people" style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}></i>
                      <span style={{ fontSize: '13px' }}>{exec.users}</span>
                    </div>
                  </td>
                  <td><span style={{ fontSize: '13px', fontFamily: 'monospace' }}>{exec.duration}</span></td>
                  <td><span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{new Date(exec.startedAt).toLocaleString('fr-FR')}</span></td>
                  <td>
                    <div className="d-flex justify-content-end gap-1">
                      <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Voir les détails" onClick={() => navigate(`/executions/detail/${exec.id}`)}>
                        <i className="bi bi-eye" style={{ fontSize: '14px' }}></i>
                      </button>
                      <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Voir le rapport" onClick={() => navigate(`/executions/report/${exec.id}`)}>
                        <i className="bi bi-file-text" style={{ fontSize: '14px' }}></i>
                      </button>
                      {canEdit && exec.status === 'En cours' && (
                        <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-warning)', color: 'var(--pt-warning)' }} title="Suspendre" onClick={() => handleSuspend(exec.id)}>
                          <i className="bi bi-pause-fill" style={{ fontSize: '14px' }}></i>
                        </button>
                      )}
                      {canEdit && (exec.status === 'En cours' || exec.status === 'Suspendue') && (
                        <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-danger)', color: 'var(--pt-danger)' }} title="Arrêter" onClick={() => handleStop(exec.id)}>
                          <i className="bi bi-stop-fill" style={{ fontSize: '14px' }}></i>
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              )})}
            </tbody>
          </table>
        </div>
        )}

        <Pagination
          page={page}
          totalPages={totalPages}
          onPageChange={setPage}
          startIndex={startIndex}
          endIndex={endIndex}
          totalItems={totalItems}
          itemLabel="exécutions"
        />
      </div>

      <LaunchScenarioModal launcher={launcher} scenarios={scenarios} applications={applications} />
    </div>
  )
}

export default Executions
