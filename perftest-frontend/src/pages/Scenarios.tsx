import { ChangeEvent, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import {
  BackendScenarioResponse,
  BackendStepResponse,
  BackendExecutionResponse,
  BackendExecutionStatusResponse,
  BackendStopMode,
} from '../types/backendContracts'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { stepsBackendApi } from '../services/api/stepsBackend'
import { ApiError } from '../services/api/httpClient'
import { useApiList } from '../hooks/useApiResource'
import { usePagination } from '../hooks/usePagination'
import Pagination from '../components/Pagination'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { backendToFrontendActiveStatus, backendToFrontendExecutionStatus } from '../utils/statusMapping'

// ============================================================
// Restauration du design ancien (voir rapport d'analyse dédié) — cette page
// redevient la LISTE (cartes stat, recherche, filtre statut, tableau,
// pagination, modale de détail EN LECTURE SEULE), exactement comme avant
// P1-G/P1-Q. La création et la modification d'un Scénario (métadonnées +
// paramètres de charge) ainsi que la gestion de ses Steps se font désormais
// sur les pages dédiées /scenarios/new, /scenarios/create et
// /scenarios/create-step (stepper à 3 étapes, voir ScenarioWizard.tsx /
// ScenarioStepEditor.tsx) — jamais dans une modale inline sur cette page.
//
// Toutes les données restent 100% réelles (Spring Boot / PostgreSQL) :
// scenariosBackendApi, stepsBackendApi, executionsBackendApi. Aucun JSON
// Server, aucune donnée mock.
// ============================================================

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 401:
        return 'Vous devez être connecté (Keycloak) pour effectuer cette action.'
      case 403:
        return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      case 404:
        return 'Scénario introuvable (il a peut-être déjà été supprimé).'
      case 409:
        return err.message
      case 429:
        return `Limite de capacité LoadPilot atteinte : ${err.message}`
      default:
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function Scenarios() {
  const navigate = useNavigate()
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const [searchParams] = useSearchParams()
  const appFilter = searchParams.get('app')
  const queryFilter = searchParams.get('q')
  const initialFilter = appFilter ?? queryFilter ?? ''

  const isKeycloak = authProvider === 'keycloak'
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))
  const canDelete = canWrite

  const { data: scenarios, loading: scenariosLoading, error: scenariosError, refetch: refetchScenarios } =
    useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll())
  const { data: executions, refetch: refetchExecutions } = useApiList<BackendExecutionResponse>(() => executionsBackendApi.getAll())
  const { data: allSteps } = useApiList<BackendStepResponse>(() => stepsBackendApi.getAll())

  const [searchTerm, setSearchTerm] = useState(initialFilter)
  const [selectedStatus, setSelectedStatus] = useState<'Tous' | 'ACTIVE' | 'INACTIVE'>('Tous')
  const [selectedIds, setSelectedIds] = useState<string[]>([])

  // Modale Détail — EN LECTURE SEULE (comme dans l'ancien design) : plus
  // aucune action de création/modification/suppression d'étape depuis
  // cette modale, tout cela se fait désormais sur /scenarios/create.
  const [selectedScenarioDetail, setSelectedScenarioDetail] = useState<BackendScenarioResponse | null>(null)
  const [showStepsInDetail, setShowStepsInDetail] = useState(false)
  const [detailSteps, setDetailSteps] = useState<BackendStepResponse[]>([])
  const [detailStepsLoading, setDetailStepsLoading] = useState(false)

  const [deleteConfirm, setDeleteConfirm] = useState<BackendScenarioResponse | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const stepsByScenario = useMemo(() => {
    const map = new Map<string, BackendStepResponse[]>()
    for (const step of allSteps) {
      const list = map.get(step.scenarioId) ?? []
      list.push(step)
      map.set(step.scenarioId, list)
    }
    map.forEach((list) => list.sort((a, b) => a.order - b.order))
    return map
  }, [allSteps])

  const latestExecByScenario = useMemo(() => {
    const map = new Map<string, BackendExecutionResponse>()
    for (const exec of executions) {
      const current = map.get(exec.scenarioId)
      if (!current || new Date(exec.startedAt) > new Date(current.startedAt)) {
        map.set(exec.scenarioId, exec)
      }
    }
    return map
  }, [executions])

  const [launchingScenarioId, setLaunchingScenarioId] = useState<string | null>(null)

  // "Configurer le test" — Étape 1 = Application/Scénario (verrouillés au
  // scénario cliqué) + VUs/Durée/Ramp-up/Think time/Débit cible/Mode
  // d'arrêt ÉDITABLES, Étape 2 = récapitulatif + lancement réel. Ces 6
  // champs correspondent chacun à un VRAI champ persistant sur Scenario
  // (virtualUsers/durationSeconds/rampUpSeconds/thinkTimeMs/targetRps/
  // stopMode) : l'édition ici appelle `scenariosBackendApi.update` (PUT réel)
  // avant de lancer — `BackendExecutionRequest` reste `{scenarioId}`
  // uniquement (voir ExecutionRequest.java), aucune surcharge "par
  // exécution" n'est inventée : éditer ici modifie réellement le scénario.
  //
  // La progression "en direct" n'affiche qu'un pourcentage global
  // (`GET /api/executions/{id}/status`, champ réel `progressPercent`) :
  // aucun détail par utilisateur virtuel ni par étape n'est exposé par le
  // backend, et il n'existe aucun endpoint "pause" — seul "Annuler"
  // (`POST /api/executions/{id}/cancel`, déjà réel) est proposé.
  const [launchConfirmScenario, setLaunchConfirmScenario] = useState<BackendScenarioResponse | null>(null)
  const [launchStep, setLaunchStep] = useState<1 | 2>(1)
  const [launchForm, setLaunchForm] = useState({
    virtualUsers: '',
    durationSeconds: '',
    rampUpSeconds: '',
    thinkTimeMs: '',
    targetRps: '',
    stopMode: 'AUTO' as BackendStopMode,
  })
  const [liveExecution, setLiveExecution] = useState<BackendExecutionStatusResponse | null>(null)
  const [cancellingLive, setCancellingLive] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const openLaunchModal = (scenario: BackendScenarioResponse) => {
    setLaunchConfirmScenario(scenario)
    setLaunchStep(1)
    setLaunchForm({
      virtualUsers: String(scenario.virtualUsers),
      durationSeconds: scenario.durationSeconds != null ? String(scenario.durationSeconds) : '',
      rampUpSeconds: String(scenario.rampUpSeconds),
      thinkTimeMs: String(scenario.thinkTimeMs),
      targetRps: scenario.targetRps != null ? String(scenario.targetRps) : '',
      stopMode: scenario.stopMode,
    })
  }

  useEffect(() => {
    return () => {
      if (pollRef.current) clearInterval(pollRef.current)
    }
  }, [])

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }

  const handleConfirmLaunch = async () => {
    if (!canWrite || !launchConfirmScenario) return
    const scenario = launchConfirmScenario
    const stepCount = (stepsByScenario.get(scenario.id) ?? []).length
    if (stepCount === 0) {
      showToast('Ce scénario ne contient aucune étape.', 'danger')
      setLaunchConfirmScenario(null)
      return
    }
    setLaunchingScenarioId(scenario.id)
    try {
      const editedVirtualUsers = Number(launchForm.virtualUsers) || scenario.virtualUsers
      const editedRampUp = launchForm.rampUpSeconds === '' ? scenario.rampUpSeconds : Number(launchForm.rampUpSeconds)
      const editedDuration = launchForm.durationSeconds === '' ? null : Number(launchForm.durationSeconds)
      const editedThinkTime = launchForm.thinkTimeMs === '' ? scenario.thinkTimeMs : Number(launchForm.thinkTimeMs)
      const editedTargetRps = launchForm.targetRps === '' ? null : Number(launchForm.targetRps)
      const valuesChanged =
        editedVirtualUsers !== scenario.virtualUsers ||
        editedRampUp !== scenario.rampUpSeconds ||
        editedDuration !== scenario.durationSeconds ||
        editedThinkTime !== scenario.thinkTimeMs ||
        editedTargetRps !== scenario.targetRps ||
        launchForm.stopMode !== scenario.stopMode
      if (valuesChanged) {
        await scenariosBackendApi.update(scenario.id, {
          applicationId: scenario.applicationId,
          name: scenario.name,
          description: scenario.description,
          virtualUsers: editedVirtualUsers,
          rampUpSeconds: editedRampUp,
          durationSeconds: editedDuration,
          iterations: scenario.iterations,
          thinkTimeMs: editedThinkTime,
          csvData: scenario.csvData,
          targetRps: editedTargetRps,
          stopMode: launchForm.stopMode,
        })
        await refetchScenarios()
      }
      const created = await executionsBackendApi.execute({ scenarioId: scenario.id })
      await refetchExecutions()
      setLaunchConfirmScenario(null)
      const status = await executionsBackendApi.getStatus(created.id)
      setLiveExecution(status)
      pollRef.current = setInterval(async () => {
        try {
          const polled = await executionsBackendApi.getStatus(created.id)
          setLiveExecution(polled)
          if (polled.status === 'SUCCESS' || polled.status === 'FAILED' || polled.status === 'CANCELLED') {
            stopPolling()
            refetchExecutions()
          }
        } catch {
          stopPolling()
        }
      }, 1500)
    } catch (err) {
      showToast(describeApiError(err, "Erreur lors du lancement de l'exécution."), 'danger')
    } finally {
      setLaunchingScenarioId(null)
    }
  }

  const handleCancelLive = async () => {
    if (!liveExecution) return
    setCancellingLive(true)
    try {
      await executionsBackendApi.cancel(liveExecution.id)
      const polled = await executionsBackendApi.getStatus(liveExecution.id)
      setLiveExecution(polled)
      stopPolling()
      refetchExecutions()
    } catch (err) {
      showToast(describeApiError(err, "Erreur lors de l'annulation."), 'danger')
    } finally {
      setCancellingLive(false)
    }
  }

  const formatDate = (iso: string) => {
    const d = new Date(iso)
    if (isNaN(d.getTime())) return iso
    return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
  }

  const handleDeleteScenario = async () => {
    if (!canDelete || !deleteConfirm) return
    setDeleting(true)
    setActionError(null)
    try {
      await scenariosBackendApi.remove(deleteConfirm.id)
      await refetchScenarios()
      setSelectedIds((prev) => prev.filter((id) => id !== deleteConfirm.id))
      showToast(`Scénario « ${deleteConfirm.name} » supprimé avec succès.`, 'success')
      setDeleteConfirm(null)
    } catch (err) {
      const message = describeApiError(err, 'Erreur lors de la suppression.')
      setActionError(message)
      showToast(message, 'danger')
    } finally {
      setDeleting(false)
    }
  }

  const handleSelectAll = (e: ChangeEvent<HTMLInputElement>) => {
    setSelectedIds(e.target.checked ? filteredScenarios.map((s) => s.id) : [])
  }

  const handleSelectOne = (id: string) => {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]))
  }

  const filteredScenarios = scenarios.filter((scenario) => {
    const matchesSearch =
      scenario.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      scenario.applicationName.toLowerCase().includes(searchTerm.toLowerCase())
    const matchesStatus = selectedStatus === 'Tous' || scenario.status === selectedStatus
    return matchesSearch && matchesStatus
  })

  const { page, setPage, totalPages, pageItems, startIndex, endIndex, totalItems } = usePagination(filteredScenarios, 10)

  const loadDetailSteps = (scenarioId: string) => {
    setDetailStepsLoading(true)
    stepsBackendApi.getByScenario(scenarioId)
      .then(setDetailSteps)
      .catch(() => setDetailSteps([]))
      .finally(() => setDetailStepsLoading(false))
  }

  return (
    <div className="pt-content">
      {/* MODAL: Detail Scénario — LECTURE SEULE */}
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
                    <div style={{ fontSize: '14px', fontWeight: 600 }}>{selectedScenarioDetail.applicationName}</div>
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
                    <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{formatDate(selectedScenarioDetail.createdAt)}</div>
                  </div>
                  {selectedScenarioDetail.description && (
                    <div className="col-12">
                      <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Description</div>
                      <div style={{ fontSize: '13px', background: 'var(--pt-bg)', padding: '8px 12px', borderRadius: '6px', marginTop: '4px' }}>
                        {selectedScenarioDetail.description}
                      </div>
                    </div>
                  )}
                  <div className="col-12">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>Profil de charge</div>
                    <div className="d-flex flex-wrap gap-2">
                      <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
                        <i className="bi bi-people me-1"></i>{selectedScenarioDetail.virtualUsers} VU
                      </span>
                      <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
                        <i className="bi bi-graph-up-arrow me-1"></i>Ramp-up {selectedScenarioDetail.rampUpSeconds}s
                      </span>
                      {selectedScenarioDetail.durationSeconds != null && (
                        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
                          <i className="bi bi-stopwatch me-1"></i>Durée {selectedScenarioDetail.durationSeconds}s
                        </span>
                      )}
                      {selectedScenarioDetail.iterations != null && (
                        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
                          <i className="bi bi-arrow-repeat me-1"></i>{selectedScenarioDetail.iterations} itération(s)
                        </span>
                      )}
                      {selectedScenarioDetail.thinkTimeMs > 0 && (
                        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
                          <i className="bi bi-hourglass-split me-1"></i>Think time {selectedScenarioDetail.thinkTimeMs}ms
                        </span>
                      )}
                    </div>
                  </div>
                </div>

                <div className="mt-2">
                  <div className="d-flex justify-content-between align-items-center mb-2 flex-wrap gap-2">
                    <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                      <i className="bi bi-list-check me-1" style={{ color: 'var(--pt-primary)' }}></i>
                      Étapes configurées ({detailSteps.length})
                    </h6>
                    <div className="d-flex gap-2">
                      {canWrite && (
                        <button
                          className="pt-btn-outline"
                          style={{ fontSize: '12px', padding: '0.2rem 0.6rem' }}
                          onClick={() => navigate(`/scenarios/create?edit=${selectedScenarioDetail.id}&wizardStep=2`)}
                        >
                          <i className="bi bi-pencil"></i> Gérer les étapes
                        </button>
                      )}
                      <button
                        className="pt-btn-outline"
                        style={{ fontSize: '12px', padding: '0.2rem 0.6rem' }}
                        onClick={() => {
                          if (!showStepsInDetail) loadDetailSteps(selectedScenarioDetail.id)
                          setShowStepsInDetail(!showStepsInDetail)
                        }}
                      >
                        <i className={`bi ${showStepsInDetail ? 'bi-chevron-up' : 'bi-eye'}`}></i>
                        {showStepsInDetail ? ' Masquer les étapes' : ' Voir les étapes'}
                      </button>
                    </div>
                  </div>

                  {showStepsInDetail && (
                    <div className="d-flex flex-column gap-2" style={{ maxHeight: '360px', overflowY: 'auto' }}>
                      {detailStepsLoading ? (
                        <div className="text-center text-muted py-2" style={{ fontSize: '13px' }}>
                          <i className="bi bi-arrow-repeat pt-spin me-1"></i>Chargement des étapes...
                        </div>
                      ) : detailSteps.length > 0 ? (
                        detailSteps.map((st) => (
                          <div key={st.id} style={{ border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '10px 12px', background: 'var(--pt-bg)' }}>
                            <div className="d-flex align-items-center gap-2 flex-wrap">
                              <span style={{ fontSize: '11px', fontWeight: 700, color: 'var(--pt-text-muted)' }}>#{st.order}</span>
                              <span style={{ padding: '0.15rem 0.4rem', borderRadius: '4px', fontSize: '11px', fontWeight: 700, background: st.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)', color: st.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)' }}>
                                {st.method}
                              </span>
                              <span style={{ fontWeight: 600, fontSize: '13px' }}>{st.name}</span>
                              <code style={{ fontSize: '11.5px', color: 'var(--pt-primary)' }}>{st.url}</code>
                              {st.expectedStatus != null && (
                                <span className="pt-pill neutral" style={{ fontSize: '10.5px' }}>Attendu: {st.expectedStatus}</span>
                              )}
                            </div>
                            {(st.headers || st.body) && (
                              <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '6px' }}>
                                {st.headers && <div>Headers : <code>{st.headers}</code></div>}
                                {st.body && <div>Body : <code>{st.body}</code></div>}
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

              {/* Vue Consulter strictement en lecture seule (comme dans l'ancien
                  design) : aucune action mutante ici — modifier/gérer les
                  étapes se fait sur /scenarios/create. */}
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

      {/* Stat Cards */}
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
                <div className="stat-value">{scenariosLoading ? '—' : scenarios.filter((s) => s.status === 'ACTIVE').length}</div>
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
                <div className="stat-value">{scenariosLoading ? '—' : scenarios.filter((s) => s.status === 'INACTIVE').length}</div>
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
        {canWrite && (
          <button className="pt-btn-primary" onClick={() => navigate('/scenarios/new')}>
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
            <select
              className="pt-form-control"
              style={{ width: 'auto', fontSize: '12.5px' }}
              value={selectedStatus}
              onChange={(e) => setSelectedStatus(e.target.value as 'Tous' | 'ACTIVE' | 'INACTIVE')}
            >
              <option value="Tous">Tous les statuts</option>
              <option value="ACTIVE">Actif</option>
              <option value="INACTIVE">Inactif</option>
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
                  const latestExec = latestExecByScenario.get(scenario.id)
                  const scenarioSteps = stepsByScenario.get(scenario.id) ?? []
                  const stepsPreview =
                    scenarioSteps.length === 0
                      ? 'Aucune étape'
                      : scenarioSteps.map((s) => `${s.order}. ${s.method} ${s.url}`).join('  →  ')
                  const displayStatus = backendToFrontendActiveStatus(scenario.status)

                  return (
                    <tr key={scenario.id} style={{ background: isSelected ? 'var(--pt-sidebar-item-hover)' : undefined, cursor: 'pointer' }}>
                      <td onClick={(e) => e.stopPropagation()}>
                        <input type="checkbox" checked={isSelected} onChange={() => handleSelectOne(scenario.id)} />
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <div className="d-flex align-items-center gap-3">
                          <div style={{ width: '38px', height: '38px', borderRadius: '10px', background: 'var(--pt-primary-light)', color: 'var(--pt-primary)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '17px', flexShrink: 0 }}>
                            <i className="bi bi-collection-play"></i>
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
                        <span style={{ fontSize: '13px', fontWeight: 500, color: 'var(--pt-text)' }}>{scenario.applicationName}</span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span className={`pt-pill ${displayStatus === 'Actif' ? 'success' : 'neutral'}`}>
                          <i className={`bi ${displayStatus === 'Actif' ? 'bi-check-circle-fill' : 'bi-pause-circle-fill'}`} style={{ fontSize: '11px' }}></i>
                          {displayStatus}
                        </span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
                          {latestExec ? formatDate(latestExec.startedAt) : '—'}
                        </span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{formatDate(scenario.createdAt)}</span>
                      </td>
                      <td onClick={() => { setSelectedScenarioDetail(scenario); setShowStepsInDetail(false) }}>
                        <div className="d-flex align-items-center gap-2">
                          <div style={{ width: '22px', height: '22px', borderRadius: '50%', background: 'var(--pt-primary-light)', color: 'var(--pt-primary)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '10px', fontWeight: 700, flexShrink: 0 }}>
                            {scenario.createdBy.split(' ').map((p) => p[0]).join('')}
                          </div>
                          <span style={{ fontSize: '12.5px', color: 'var(--pt-text)' }}>{scenario.createdBy}</span>
                        </div>
                      </td>
                      <td onClick={(e) => e.stopPropagation()}>
                        {latestExec ? (
                          <button
                            onClick={() => navigate(`/executions?q=${encodeURIComponent(scenario.name)}`)}
                            style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '12.5px', color: 'var(--pt-primary)', fontWeight: 600, textDecoration: 'underline' }}
                            title="Voir cette exécution sur la page Exécutions"
                          >
                            {backendToFrontendExecutionStatus(latestExec.status)} — {formatDate(latestExec.startedAt)}
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
                          {canWrite && (
                            <button
                              className="topbar-icon"
                              disabled={launchingScenarioId === scenario.id}
                              title={`Exécuter le scénario (${scenario.virtualUsers} VU, ramp-up ${scenario.rampUpSeconds}s${scenario.durationSeconds ? `, durée ${scenario.durationSeconds}s` : scenario.iterations ? `, ${scenario.iterations} itération(s)` : ''}) — asynchrone, suivez la progression sur la page Exécutions`}
                              style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-success)', background: 'var(--pt-success-light)' }}
                              onClick={() => openLaunchModal(scenario)}
                            >
                              <i className={`bi ${launchingScenarioId === scenario.id ? 'bi-arrow-repeat pt-spin' : 'bi-play-fill'}`} style={{ fontSize: '14px', color: 'var(--pt-success)' }}></i>
                            </button>
                          )}
                          {canWrite && (
                            <button className="topbar-icon" title="Modifier" style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-primary)', background: 'var(--pt-primary-light)' }} onClick={() => navigate(`/scenarios/create?edit=${scenario.id}`)}>
                              <i className="bi bi-pencil" style={{ fontSize: '13px', color: 'var(--pt-primary)' }}></i>
                            </button>
                          )}
                          {canDelete && (
                            <button className="topbar-icon" title="Supprimer" disabled={deleting} style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-danger)', background: 'rgba(220,38,38,0.06)' }} onClick={() => setDeleteConfirm(scenario)}>
                              <i className="bi bi-trash" style={{ fontSize: '13px', color: 'var(--pt-danger)' }}></i>
                            </button>
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

      {/* Delete Confirmation Modal */}
      {deleteConfirm && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '400px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)', textAlign: 'center' }}>
            <div style={{ width: '56px', height: '56px', borderRadius: '50%', background: 'var(--pt-danger-light)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 1rem' }}>
              <i className="bi bi-trash" style={{ fontSize: '24px', color: 'var(--pt-danger)' }}></i>
            </div>
            <h5 style={{ fontWeight: 700, marginBottom: '8px' }}>Supprimer le scénario ?</h5>
            <p style={{ color: 'var(--pt-text-muted)', fontSize: '14px', marginBottom: '1.5rem' }}>
              « {deleteConfirm.name} » sera définitivement supprimé. Refusé si des étapes y sont encore rattachées.
            </p>
            {actionError && (
              <div className="pt-alert-banner danger mb-3" style={{ textAlign: 'left' }}>
                <i className="bi bi-exclamation-triangle-fill"></i>
                {actionError}
              </div>
            )}
            <div className="d-flex gap-2 justify-content-center">
              <button onClick={() => { setDeleteConfirm(null); setActionError(null) }} disabled={deleting} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                Annuler
              </button>
              <button onClick={handleDeleteScenario} disabled={deleting} style={{ background: 'var(--pt-danger)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                {deleting ? 'Suppression...' : 'Supprimer'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* "Configurer le test" — restaurée en 2 étapes d'après capture Bandicam
          #18 (voir commentaire d'état plus haut). */}
      {launchConfirmScenario && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1080, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '560px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            <div className="d-flex justify-content-between align-items-center mb-2">
              <h5 style={{ fontWeight: 700, margin: 0 }}><i className="bi bi-gear me-2 text-primary"></i>Configurer le test</h5>
              <button onClick={() => setLaunchConfirmScenario(null)} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)' }}><i className="bi bi-x"></i></button>
            </div>
            <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>Étape {launchStep} sur 2</div>
            <div style={{ height: '4px', borderRadius: '2px', background: 'var(--pt-bg)', overflow: 'hidden', marginBottom: '1.25rem' }}>
              <div style={{ height: '100%', width: launchStep === 1 ? '50%' : '100%', background: 'var(--pt-primary)', transition: 'width 0.2s ease' }}></div>
            </div>

            {launchStep === 1 ? (
              <>
                <div className="row g-3 mb-1">
                  <div className="col-12">
                    <label className="pt-form-label">Application *</label>
                    <select className="pt-form-control" value={launchConfirmScenario.applicationId} disabled>
                      <option value={launchConfirmScenario.applicationId}>{launchConfirmScenario.applicationName}</option>
                    </select>
                  </div>
                  <div className="col-12">
                    <label className="pt-form-label">Scénario *</label>
                    <select className="pt-form-control" value={launchConfirmScenario.id} disabled>
                      <option value={launchConfirmScenario.id}>{launchConfirmScenario.name}</option>
                    </select>
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label d-flex align-items-center gap-2">
                      Utilisateurs virtuels (VUs)
                      <span className="pt-pill neutral" style={{ fontSize: '10px' }}>Valeur du scénario : {launchConfirmScenario.virtualUsers}</span>
                    </label>
                    <input type="number" min={1} className="pt-form-control" value={launchForm.virtualUsers}
                      onChange={(e) => setLaunchForm((f) => ({ ...f, virtualUsers: e.target.value }))} />
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label">Durée (secondes)</label>
                    <input type="number" min={0} className="pt-form-control" value={launchForm.durationSeconds}
                      placeholder="Optionnel"
                      onChange={(e) => setLaunchForm((f) => ({ ...f, durationSeconds: e.target.value }))} />
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label d-flex align-items-center gap-2">
                      Ramp-up (secondes)
                      <span className="pt-pill neutral" style={{ fontSize: '10px' }}>Valeur du scénario : {launchConfirmScenario.rampUpSeconds}</span>
                    </label>
                    <input type="number" min={0} className="pt-form-control" value={launchForm.rampUpSeconds}
                      onChange={(e) => setLaunchForm((f) => ({ ...f, rampUpSeconds: e.target.value }))} />
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label">Think time (ms)</label>
                    <input type="number" min={0} className="pt-form-control" value={launchForm.thinkTimeMs}
                      onChange={(e) => setLaunchForm((f) => ({ ...f, thinkTimeMs: e.target.value }))} />
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label">Débit cible (req/s) optionnel</label>
                    <input type="number" min={0} className="pt-form-control" value={launchForm.targetRps}
                      placeholder="Ex: 500"
                      onChange={(e) => setLaunchForm((f) => ({ ...f, targetRps: e.target.value }))} />
                  </div>
                  <div className="col-12">
                    <label className="pt-form-label">Mode d'arrêt</label>
                    <select className="pt-form-control" value={launchForm.stopMode} onChange={(e) => setLaunchForm((f) => ({ ...f, stopMode: e.target.value as BackendStopMode }))}>
                      <option value="AUTO">Automatique (durée/itérations définies)</option>
                      <option value="MANUAL">Manuel (tourne jusqu'à annulation)</option>
                    </select>
                    <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>
                      Réellement appliqué par le moteur d'exécution — en mode Manuel, seul "Annuler" arrête l'exécution.
                    </div>
                  </div>
                </div>
                <div className="d-flex gap-2 justify-content-end mt-4">
                  <button onClick={() => setLaunchConfirmScenario(null)} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                    Annuler
                  </button>
                  <button onClick={() => setLaunchStep(2)} style={{ background: 'var(--pt-primary)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                    Suivant <i className="bi bi-arrow-right ms-1"></i>
                  </button>
                </div>
              </>
            ) : (
              <>
                <div className="pt-alert-banner mb-3" style={{ fontSize: '12px' }}>
                  <i className="bi bi-info-circle-fill"></i> Les valeurs modifiées seront enregistrées sur le scénario (PUT réel) avant le lancement de l'exécution.
                </div>
                <div className="d-flex flex-column gap-2" style={{ fontSize: '13px' }}>
                  <div className="d-flex justify-content-between"><span className="text-muted">Application</span><strong>{launchConfirmScenario.applicationName}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Scénario</span><strong>{launchConfirmScenario.name}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Utilisateurs virtuels (VUs)</span><strong>{launchForm.virtualUsers || launchConfirmScenario.virtualUsers}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Ramp-up</span><strong>{launchForm.rampUpSeconds || launchConfirmScenario.rampUpSeconds} s</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Durée</span><strong>{launchForm.durationSeconds !== '' ? `${launchForm.durationSeconds} s` : '—'}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Think time</span><strong>{launchForm.thinkTimeMs || launchConfirmScenario.thinkTimeMs} ms</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Débit cible</span><strong>{launchForm.targetRps !== '' ? `${launchForm.targetRps} req/s` : 'Aucun'}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Mode d'arrêt</span><strong>{launchForm.stopMode === 'AUTO' ? 'Automatique' : 'Manuel'}</strong></div>
                </div>
                <div className="d-flex gap-2 justify-content-end mt-4">
                  <button onClick={() => setLaunchStep(1)} disabled={launchingScenarioId === launchConfirmScenario.id} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                    <i className="bi bi-arrow-left me-1"></i> Précédent
                  </button>
                  <button onClick={handleConfirmLaunch} disabled={launchingScenarioId === launchConfirmScenario.id} style={{ background: 'var(--pt-success)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                    {launchingScenarioId === launchConfirmScenario.id ? <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Lancement...</> : <><i className="bi bi-play-fill me-2"></i>Lancer</>}
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* "Exécution en direct" — progression AGRÉGÉE réelle (aucun détail
          par VU/étape exposé par le backend actuel, aucun "Pause" : voir
          commentaire plus haut). */}
      {liveExecution && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1085, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '460px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            <h5 style={{ fontWeight: 700, marginBottom: '4px' }}><i className="bi bi-rocket-takeoff me-2 text-primary"></i>Exécution en direct</h5>
            <p style={{ color: 'var(--pt-text-muted)', fontSize: '12.5px', marginBottom: '1rem' }}>Progression en temps réel</p>
            <div className="d-flex justify-content-between mb-1" style={{ fontSize: '13px' }}>
              <span>Progression</span>
              <strong>{liveExecution.progressPercent}%</strong>
            </div>
            <div style={{ height: '8px', borderRadius: '4px', background: 'var(--pt-bg)', overflow: 'hidden', marginBottom: '1rem' }}>
              <div style={{ height: '100%', width: `${liveExecution.progressPercent}%`, background: liveExecution.status === 'FAILED' ? 'var(--pt-danger)' : 'var(--pt-primary)', transition: 'width 0.3s ease' }}></div>
            </div>
            <div className="d-flex flex-column gap-1 mb-3" style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
              <div className="d-flex justify-content-between"><span>Statut</span><strong style={{ color: 'var(--pt-text)' }}>{liveExecution.status}</strong></div>
            </div>
            <div className="d-flex gap-2 justify-content-end">
              {(liveExecution.status === 'QUEUED' || liveExecution.status === 'RUNNING') ? (
                <button onClick={handleCancelLive} disabled={cancellingLive} style={{ background: 'var(--pt-danger)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                  {cancellingLive ? 'Annulation...' : 'Annuler'}
                </button>
              ) : (
                <button onClick={() => setLiveExecution(null)} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                  Fermer
                </button>
              )}
              {liveExecution.status !== 'QUEUED' && liveExecution.status !== 'RUNNING' && (
                <button onClick={() => navigate(`/executions/report/${liveExecution.id}`)} style={{ background: 'var(--pt-primary)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                  Voir le rapport
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default Scenarios
