import React, { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Scenario, Application, Step, Execution } from '../types'
import { scenariosApi } from '../services/api/scenarios'
import { applicationsApi } from '../services/api/applications'
import { stepsApi } from '../services/api/steps'
import { executionsApi } from '../services/api/executions'
import { useApiList } from '../hooks/useApiResource'
import { usePagination } from '../hooks/usePagination'
import Pagination from '../components/Pagination'
import { useScenarioLauncher } from '../hooks/useScenarioLauncher'
import LaunchScenarioModal from '../components/LaunchScenarioModal'
import * as stepBridge from '../utils/scenarioStepBridge'
import { useAuth } from '../context/AuthContext'

const scenarioIcons: Record<string, string> = {
  Web: 'bi-globe2',
  'API REST': 'bi-code-slash',
  SOAP: 'bi-braces',
  Mobile: 'bi-phone',
}

function Scenarios() {
  const navigate = useNavigate()
  const { canEdit } = useAuth()
  const [searchParams] = useSearchParams()
  const appFilter = searchParams.get('app')
  const queryFilter = searchParams.get('q')
  const initialFilter = appFilter ?? queryFilter ?? ''

  // Données réelles depuis JSON Server — une seule source de vérité,
  // reliées entre elles par de vrais IDs (applicationId, scenarioId...).
  const { data: scenarios, loading: scenariosLoading, error: scenariosError, refetch: refetchScenarios } =
    useApiList<Scenario>(() => scenariosApi.getAll())
  const { data: applications } = useApiList<Application>(() => applicationsApi.getAll())
  const { data: executions, refetch: refetchExecutions } = useApiList<Execution>(() => executionsApi.getAll())
  // Étapes de TOUS les scénarios (pas juste celui ouvert dans la modale
  // détail) — sert à afficher un aperçu des étapes directement dans la
  // liste, sous le nom du scénario.
  const { data: allSteps } = useApiList<Step>(() => stepsApi.getAll())

  // Flux de lancement (modale + progression réelle) partagé avec la page
  // Exécutions — voir hooks/useScenarioLauncher. On reste ici sur la page
  // Scénarios pendant tout le lancement (pas de navigation).
  const launcher = useScenarioLauncher(refetchExecutions)

  const [searchTerm, setSearchTerm] = useState(initialFilter)
  const [selectedStatus, setSelectedStatus] = useState('Tous')
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [toastMessage, setToastMessage] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)

  // Modals state
  const [selectedScenarioDetail, setSelectedScenarioDetail] = useState<Scenario | null>(null)
  const [showStepsInDetail, setShowStepsInDetail] = useState(false)
  const [detailSteps, setDetailSteps] = useState<Step[]>([])
  const [detailStepsLoading, setDetailStepsLoading] = useState(false)

  // Application liée à chaque scénario, résolue par ID (jamais par nom).
  const appById = useMemo(() => {
    const map = new Map<string, Application>()
    applications.forEach((a) => map.set(a.id, a))
    return map
  }, [applications])

  // Étapes de chaque scénario, triées dans l'ordre réel d'exécution — pour
  // l'aperçu affiché sous le nom du scénario dans la liste.
  const stepsByScenario = useMemo(() => {
    const map = new Map<string, Step[]>()
    for (const step of allSteps) {
      const list = map.get(step.scenarioId) ?? []
      list.push(step)
      map.set(step.scenarioId, list)
    }
    map.forEach((list) => list.sort((a, b) => a.order - b.order))
    return map
  }, [allSteps])

  // Dernière exécution réelle par scénario, calculée à partir de la vraie
  // liste d'exécutions de JSON Server.
  const latestExecByScenario = useMemo(() => {
    const map = new Map<string, Execution>()
    for (const exec of executions) {
      const current = map.get(exec.scenarioId)
      if (!current || new Date(exec.startedAt) > new Date(current.startedAt)) {
        map.set(exec.scenarioId, exec)
      }
    }
    return map
  }, [executions])

  useEffect(() => {
    if (initialFilter) setSearchTerm(initialFilter)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [appFilter, queryFilter])

  // Charge les vraies étapes du scénario sélectionné dès l'ouverture de la
  // fenêtre de détail (elles ne sont plus embarquées dans le scénario —
  // elles vivent dans leur propre ressource /steps, reliées par scenarioId).
  useEffect(() => {
    if (!selectedScenarioDetail) {
      setDetailSteps([])
      return
    }
    setDetailStepsLoading(true)
    stepsApi.getByScenario(selectedScenarioDetail.id)
      .then(setDetailSteps)
      .catch(() => setDetailSteps([]))
      .finally(() => setDetailStepsLoading(false))
  }, [selectedScenarioDetail])

  const showNotification = (msg: string) => {
    setToastMessage(msg)
    setTimeout(() => setToastMessage(null), 3000)
  }

  const handleDeleteScenario = async (scenario: Scenario) => {
    if (!canEdit) return
    setDeleting(true)
    try {
      // Supprime aussi les étapes liées (cascade côté frontend, JSON
      // Server ne le fait pas automatiquement) — séquentiellement, sinon
      // json-server (--watch, mono-thread) peut réinitialiser une connexion
      // sous plusieurs suppressions concurrentes et laisser une étape orpheline.
      const steps = await stepsApi.getByScenario(scenario.id)
      for (const s of steps) {
        await stepsApi.remove(s.id)
      }
      await scenariosApi.remove(scenario.id)
      await refetchScenarios()
      setSelectedIds((prev) => prev.filter((id) => id !== scenario.id))
      showNotification(`Scénario "${scenario.name}" supprimé.`)
    } catch (err) {
      showNotification(err instanceof Error ? err.message : 'Erreur lors de la suppression')
    } finally {
      setDeleting(false)
    }
  }

  const handleSelectAll = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSelectedIds(e.target.checked ? filteredScenarios.map((s) => s.id) : [])
  }

  const handleSelectOne = (id: string) => {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]))
  }

  const filteredScenarios = scenarios.filter((scenario) => {
    const appName = appById.get(scenario.applicationId)?.name ?? ''
    const matchesSearch =
      scenario.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      appName.toLowerCase().includes(searchTerm.toLowerCase())

    const matchesStatus = selectedStatus === 'Tous' || scenario.status === selectedStatus

    return matchesSearch && matchesStatus
  })

  const { page, setPage, totalPages, pageItems, startIndex, endIndex, totalItems } = usePagination(filteredScenarios, 10)

  const formatDate = (iso: string) => {
    const d = new Date(iso)
    if (isNaN(d.getTime())) return iso
    return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
  }

  // Ouvre la modale de configuration rapide directement ici : l'utilisateur
  // reste sur la page Scénarios pendant toute la configuration et la
  // progression de l'exécution (voir hooks/useScenarioLauncher).
  const handleLaunch = (scenario: Scenario) => {
    if (!canEdit) return
    launcher.openForScenario(scenario)
  }

  // Un scénario planifié (date/heure future) se déclenchera tout seul
  // (voir hooks/useScheduledExecutions) — proposer aussi le bouton manuel
  // ferait croire à deux exécutions possibles alors qu'une seule doit
  // avoir lieu.
  const isScheduledPending = (scenario: Scenario): boolean => {
    if (scenario.schedule?.executionType !== 'scheduled') return false
    if (!scenario.schedule.scheduledDate) return false
    const due = new Date(`${scenario.schedule.scheduledDate}T${scenario.schedule.scheduledTime || '00:00'}`)
    return !Number.isNaN(due.getTime()) && due.getTime() > Date.now()
  }

  return (
    <div className="pt-content">
      {/* Toast Notification */}
      {toastMessage && (
        <div
          style={{
            position: 'fixed', bottom: '24px', right: '24px', zIndex: 9999,
            background: 'var(--pt-text)', color: 'var(--pt-card-bg)', padding: '12px 20px',
            borderRadius: 'var(--pt-radius-sm)', boxShadow: 'var(--pt-shadow-md)',
            display: 'flex', alignItems: 'center', gap: '10px', fontSize: '13.5px', fontWeight: 500,
          }}
        >
          <i className="bi bi-info-circle-fill" style={{ color: 'var(--pt-primary)' }}></i>
          {toastMessage}
        </div>
      )}

      {/* MODAL: Detail Scénario */}
      {selectedScenarioDetail && (
        <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1050 }}>
          <div className="modal-dialog modal-dialog-centered modal-lg">
            <div className="modal-content" style={{ borderRadius: 'var(--pt-radius)', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}>
              <div className="modal-header">
                <h5 className="modal-title d-flex align-items-center gap-2" style={{ fontSize: '16px', fontWeight: 600 }}>
                  <i className="bi bi-info-circle text-primary"></i>
                  Détails du scénario : {selectedScenarioDetail.name}
                </h5>
                <button type="button" className="btn-close" onClick={() => { setSelectedScenarioDetail(null); setShowStepsInDetail(false) }}></button>
              </div>

              <div className="modal-body p-4 d-flex flex-column gap-3">
                <div className="row g-3">
                  <div className="col-12 col-md-6">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Nom du scénario</div>
                    <div style={{ fontSize: '14px', fontWeight: 600 }}>{selectedScenarioDetail.name}</div>
                  </div>
                  <div className="col-12 col-md-6">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Application liée</div>
                    <div style={{ fontSize: '14px', fontWeight: 600 }}>{appById.get(selectedScenarioDetail.applicationId)?.name ?? '—'}</div>
                  </div>
                  <div className="col-12 col-md-6">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Dernière exécution</div>
                    <div style={{ fontSize: '13.5px', fontWeight: 500 }}>
                      {latestExecByScenario.has(selectedScenarioDetail.id)
                        ? formatDate(latestExecByScenario.get(selectedScenarioDetail.id)!.startedAt)
                        : '—'}
                    </div>
                  </div>
                  <div className="col-12 col-md-6">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Date de création</div>
                    <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{selectedScenarioDetail.createdAt}</div>
                  </div>
                  {selectedScenarioDetail.description && (
                    <div className="col-12">
                      <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Description</div>
                      <div style={{ fontSize: '13px', background: 'var(--pt-bg)', padding: '8px 12px', borderRadius: '6px', marginTop: '4px' }}>
                        {selectedScenarioDetail.description}
                      </div>
                    </div>
                  )}
                </div>

                <div className="mt-2">
                  <div className="d-flex justify-content-between align-items-center mb-2">
                    <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                      <i className="bi bi-list-check me-1" style={{ color: 'var(--pt-primary)' }}></i>
                      Étapes configurées ({detailSteps.length})
                    </h6>
                    <button
                      className="pt-btn-outline"
                      style={{ fontSize: '12px', padding: '0.2rem 0.6rem' }}
                      onClick={() => setShowStepsInDetail(!showStepsInDetail)}
                    >
                      <i className={`bi ${showStepsInDetail ? 'bi-chevron-up' : 'bi-eye'}`}></i>
                      {showStepsInDetail ? ' Masquer les étapes' : ' Voir les étapes'}
                    </button>
                  </div>

                  {showStepsInDetail && (
                    <div className="d-flex flex-column gap-2" style={{ maxHeight: '360px', overflowY: 'auto' }}>
                      {detailStepsLoading ? (
                        <div className="text-center text-muted py-2" style={{ fontSize: '13px' }}>
                          <i className="bi bi-arrow-repeat pt-spin me-1"></i>Chargement des étapes...
                        </div>
                      ) : detailSteps.length > 0 ? (
                        detailSteps.map((st, idx) => (
                          <div key={st.id} style={{ border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '10px 12px', background: 'var(--pt-bg)' }}>
                            <div className="d-flex align-items-center gap-2 flex-wrap">
                              <span style={{ fontSize: '11px', fontWeight: 700, color: 'var(--pt-text-muted)' }}>#{idx + 1}</span>
                              <span style={{ padding: '0.15rem 0.4rem', borderRadius: '4px', fontSize: '11px', fontWeight: 700, background: st.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)', color: st.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)' }}>
                                {st.method}
                              </span>
                              <span style={{ fontWeight: 600, fontSize: '13px' }}>{st.name}</span>
                              <code style={{ fontSize: '11.5px', color: 'var(--pt-primary)', marginLeft: 'auto' }}>{st.url}</code>
                            </div>
                            {st.description && (
                              <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)', marginTop: '6px' }}>{st.description}</div>
                            )}
                            {(st.headers && st.headers.length > 0) || st.bodyJson || (st.assertions && st.assertions.length > 0) || st.timeoutMs ? (
                              <div className="row g-2 mt-1">
                                {st.headers && st.headers.length > 0 && (
                                  <div className="col-12">
                                    <div style={{ fontSize: '10.5px', fontWeight: 700, color: 'var(--pt-text-muted)', textTransform: 'uppercase', marginBottom: '3px' }}>
                                      Headers HTTP ({st.headers.length})
                                    </div>
                                    <div className="d-flex flex-column gap-1">
                                      {st.headers.map((h) => (
                                        <div key={h.id} style={{ fontSize: '11.5px', display: 'flex', gap: '6px', opacity: h.enabled ? 1 : 0.5 }}>
                                          <code style={{ color: 'var(--pt-primary)' }}>{h.key || '—'}</code>
                                          <span style={{ color: 'var(--pt-text-muted)' }}>:</span>
                                          <code>{h.value || '—'}</code>
                                        </div>
                                      ))}
                                    </div>
                                  </div>
                                )}
                                {st.bodyJson && (
                                  <div className="col-12">
                                    <div style={{ fontSize: '10.5px', fontWeight: 700, color: 'var(--pt-text-muted)', textTransform: 'uppercase', marginBottom: '3px' }}>
                                      Body de la requête
                                    </div>
                                    <pre style={{ fontSize: '11px', background: 'var(--pt-card-bg)', border: '1px solid var(--pt-border)', borderRadius: '4px', padding: '6px 8px', margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                                      {st.bodyJson}
                                    </pre>
                                  </div>
                                )}
                                {st.assertions && st.assertions.length > 0 && (
                                  <div className="col-12">
                                    <div style={{ fontSize: '10.5px', fontWeight: 700, color: 'var(--pt-text-muted)', textTransform: 'uppercase', marginBottom: '3px' }}>
                                      Assertions ({st.assertions.length})
                                    </div>
                                    <div className="d-flex flex-column gap-1">
                                      {st.assertions.map((a) => (
                                        <div key={a.id} style={{ fontSize: '11.5px' }}>
                                          <span className="pt-pill neutral" style={{ fontSize: '10.5px' }}>{a.type}</span>
                                          {' '}{a.property && <code>{a.property}</code>}{' '}{a.operator}{' '}<strong>{a.targetValue}</strong>
                                        </div>
                                      ))}
                                    </div>
                                  </div>
                                )}
                                {st.timeoutMs && (
                                  <div className="col-12 d-flex gap-3 flex-wrap" style={{ fontSize: '11px', color: 'var(--pt-text-muted)' }}>
                                    <span><i className="bi bi-hourglass-split me-1"></i>Timeout: {st.timeoutMs} ms</span>
                                  </div>
                                )}
                              </div>
                            ) : (
                              <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '6px', fontStyle: 'italic' }}>
                                Aucune configuration détaillée (headers/body/assertions) pour cette étape.
                              </div>
                            )}
                          </div>
                        ))
                      ) : (
                        <div className="text-center text-muted py-2" style={{ fontSize: '13px' }}>
                          Aucune étape définie pour ce scénario.
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>

              {/* Vue Consulter strictement en lecture seule : aucune action
                  mutante (lancer/modifier) dans cette modale — seulement
                  Fermer. */}
              <div className="modal-footer d-flex justify-content-end">
                <button className="pt-btn-outline" onClick={() => { setSelectedScenarioDetail(null); setShowStepsInDetail(false) }}>
                  Fermer
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Page Header */}
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Scénarios</h1>
          <p>Créez, gérez et organisez vos scénarios de test</p>
        </div>
      </div>

      {scenariosError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger les scénarios : {scenariosError}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => refetchScenarios()}>
            Réessayer
          </button>
        </div>
      )}

      {/* Stat Cards — calculées depuis les vraies données */}
      <div className="row g-3 mb-4">
        <div className="col-6 col-md-4">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Total des scénarios</div>
                <div className="stat-value">{scenariosLoading ? '—' : scenarios.length}</div>
              </div>
              <div className="stat-icon blue"><i className="bi bi-collection-play"></i></div>
            </div>
          </div>
        </div>
        <div className="col-6 col-md-4">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Scénarios actifs</div>
                <div className="stat-value">{scenariosLoading ? '—' : scenarios.filter((s) => s.status === 'Actif').length}</div>
              </div>
              <div className="stat-icon green"><i className="bi bi-check-circle"></i></div>
            </div>
          </div>
        </div>
        <div className="col-6 col-md-4">
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Scénarios inactifs</div>
                <div className="stat-value">{scenariosLoading ? '—' : scenarios.filter((s) => s.status === 'Inactif').length}</div>
              </div>
              <div className="stat-icon purple"><i className="bi bi-pause-circle"></i></div>
            </div>
          </div>
        </div>
      </div>

      {/* Search + Create */}
      <div className="d-flex align-items-center gap-3 flex-wrap mb-3">
        <div className="pt-search">
          <i className="bi bi-search"></i>
          <input type="text" placeholder="Rechercher par nom ou application..." value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)} />
        </div>
        {canEdit && (
          <button
            className="pt-btn-primary"
            onClick={() => {
              stepBridge.resetAll()
              navigate('/scenarios/new')
            }}
          >
            <i className="bi bi-plus-lg"></i>
            + Nouveau scénario
          </button>
        )}
      </div>

      {/* Table Card */}
      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <div className="d-flex align-items-center gap-3">
            <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Liste des scénarios</h6>
            <span className="pt-pill neutral" style={{ fontSize: '11px' }}>
              {filteredScenarios.length} sur {scenarios.length}
            </span>
          </div>
          <div className="d-flex align-items-center gap-2 flex-wrap">
            <select className="pt-form-control" style={{ width: 'auto', fontSize: '12.5px' }} value={selectedStatus} onChange={(e) => setSelectedStatus(e.target.value)}>
              <option value="Tous">Tous les statuts</option>
              <option value="Actif">Actif</option>
              <option value="Inactif">Inactif</option>
            </select>
          </div>
        </div>

        {scenariosLoading ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement des scénarios...</p>
          </div>
        ) : (
        <div className="pt-table-wrapper">
          <table className="pt-table">
            <thead>
              <tr>
                <th style={{ width: '40px' }}>
                  <input type="checkbox" checked={filteredScenarios.length > 0 && selectedIds.length === filteredScenarios.length} onChange={handleSelectAll} />
                </th>
                <th>Nom</th>
                <th>Application</th>
                <th>Statut</th>
                <th>Dernière exécution</th>
                <th>Créé le</th>
                <th>Créé par</th>
                <th>Détail exécution</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredScenarios.length === 0 ? (
                <tr>
                  <td colSpan={9} style={{ textAlign: 'center', padding: '2rem' }}>
                    <i className="bi bi-inbox" style={{ fontSize: '28px', color: 'var(--pt-text-muted)' }}></i>
                    <p style={{ color: 'var(--pt-text-muted)', marginTop: '0.5rem', margin: 0 }}>Aucun scénario trouvé</p>
                  </td>
                </tr>
              ) : (
                pageItems.map((scenario) => {
                  const isSelected = selectedIds.includes(scenario.id)
                  const app = appById.get(scenario.applicationId)
                  const icon = app ? scenarioIcons[app.type] ?? 'bi-collection-play' : 'bi-collection-play'
                  const latestExec = latestExecByScenario.get(scenario.id)
                  const scenarioSteps = stepsByScenario.get(scenario.id) ?? []
                  const stepsPreview =
                    scenarioSteps.length === 0
                      ? 'Aucune étape'
                      : scenarioSteps.map((s) => `${s.order}. ${s.method} ${s.url}`).join('  →  ')

                  return (
                    <tr key={scenario.id} style={{ background: isSelected ? 'var(--pt-sidebar-item-hover)' : undefined, cursor: 'pointer' }}>
                      <td onClick={(e) => e.stopPropagation()}>
                        <input type="checkbox" checked={isSelected} onChange={() => handleSelectOne(scenario.id)} />
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <div className="d-flex align-items-center gap-3">
                          <div style={{ width: '38px', height: '38px', borderRadius: '10px', background: 'var(--pt-primary-light)', color: 'var(--pt-primary)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '17px', flexShrink: 0 }}>
                            <i className={`bi ${icon}`}></i>
                          </div>
                          <div>
                            <span style={{ fontSize: '13.5px', fontWeight: 600, color: 'var(--pt-primary)', textDecoration: 'none', cursor: 'pointer' }}>
                              {scenario.name}
                            </span>
                            <div
                              title={stepsPreview}
                              style={{
                                fontSize: '11.5px',
                                color: 'var(--pt-text-muted)',
                                marginTop: '1px',
                                maxWidth: '320px',
                                overflow: 'hidden',
                                textOverflow: 'ellipsis',
                                whiteSpace: 'nowrap',
                                fontFamily: scenarioSteps.length > 0 ? 'monospace' : undefined,
                              }}
                            >
                              {stepsPreview}
                            </div>
                          </div>
                        </div>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span style={{ fontSize: '13px', fontWeight: 500, color: 'var(--pt-text)' }}>{app?.name ?? '—'}</span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span className={`pt-pill ${scenario.status === 'Actif' ? 'success' : 'neutral'}`}>
                          <i className={`bi ${scenario.status === 'Actif' ? 'bi-check-circle-fill' : 'bi-pause-circle-fill'}`} style={{ fontSize: '11px' }}></i>
                          {scenario.status}
                        </span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
                          {latestExec ? formatDate(latestExec.startedAt) : '—'}
                        </span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{scenario.createdAt}</span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <div className="d-flex align-items-center gap-2">
                          <div style={{ width: '22px', height: '22px', borderRadius: '50%', background: 'var(--pt-primary-light)', color: 'var(--pt-primary)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '10px', fontWeight: 700, flexShrink: 0 }}>
                            {scenario.createdBy.split(' ').map((p) => p[0]).join('')}
                          </div>
                          <span style={{ fontSize: '12.5px', color: 'var(--pt-text)' }}>{scenario.createdBy}</span>
                        </div>
                      </td>
                      {/* Détail exécution : lien réel vers l'ID de la dernière exécution */}
                      <td onClick={(e) => e.stopPropagation()}>
                        {latestExec ? (
                          <button
                            onClick={() => navigate(`/executions/detail/${latestExec.id}`)}
                            style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '12.5px', color: 'var(--pt-primary)', fontWeight: 600, textDecoration: 'underline' }}
                            title="Voir le détail de la dernière exécution"
                          >
                            Exécution #{latestExec.id}
                          </button>
                        ) : (
                          <span style={{ fontSize: '12px', color: 'var(--pt-text-light)' }}>Aucune exécution</span>
                        )}
                      </td>
                      <td onClick={(e) => e.stopPropagation()}>
                        <div className="d-flex justify-content-end gap-2">
                          <button className="topbar-icon" title="Voir détails" style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }} onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                            <i className="bi bi-eye" style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}></i>
                          </button>
                          {canEdit && (
                            <>
                              {isScheduledPending(scenario) ? (
                                <span
                                  className="pt-pill info"
                                  style={{ fontSize: '10.5px', display: 'inline-flex', alignItems: 'center', height: '30px' }}
                                  title={`Se lance automatiquement le ${scenario.schedule?.scheduledDate} à ${scenario.schedule?.scheduledTime}`}
                                >
                                  <i className="bi bi-alarm me-1"></i> Planifié
                                </span>
                              ) : (
                                <button className="topbar-icon" title="Exécuter le scénario" style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-success)', background: 'var(--pt-success-light)' }} onClick={() => handleLaunch(scenario)}>
                                  <i className="bi bi-play-fill" style={{ fontSize: '14px', color: 'var(--pt-success)' }}></i>
                                </button>
                              )}
                              <button className="topbar-icon" title="Modifier" style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-primary)', background: 'var(--pt-primary-light)' }} onClick={() => navigate(`/scenarios/create?edit=${scenario.id}`)}>
                                <i className="bi bi-pencil" style={{ fontSize: '13px', color: 'var(--pt-primary)' }}></i>
                              </button>
                              <button className="topbar-icon" title="Supprimer" disabled={deleting} style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-danger)', background: 'rgba(220,38,38,0.06)' }} onClick={() => handleDeleteScenario(scenario)}>
                                <i className="bi bi-trash" style={{ fontSize: '13px', color: 'var(--pt-danger)' }}></i>
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
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
          itemLabel="scénarios"
        />
      </div>

      <LaunchScenarioModal launcher={launcher} scenarios={scenarios} applications={applications} />
    </div>
  )
}

export default Scenarios
