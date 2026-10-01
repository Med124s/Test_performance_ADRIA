import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import TopBar from '../components/TopBar'
import {
  BackendExecutionResponse,
  BackendExecutionDetailResponse,
  BackendExecutionStatusResponse,
  BackendScenarioResponse,
  BackendStepResponse,
  BackendApplicationResponse,
  BackendStopMode,
} from '../types/backendContracts'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { stepsBackendApi } from '../services/api/stepsBackend'
import { ApiError } from '../services/api/httpClient'
import { useApiList } from '../hooks/useApiResource'
import { usePagination } from '../hooks/usePagination'
import Pagination from '../components/Pagination'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

// ============================================================
// P1-O — Le moteur legacy (JSON Server, useScenarioLauncher/
// LaunchScenarioModal, onglet "Legacy (JSON Server)") a été retiré : voir
// rapport P1-O pour la preuve runtime complète du chemin réel (PostgreSQL +
// Keycloak + Spring Boot + Spring Security, RBAC SUPER_ADMIN/
// PERFORMANCE_ENGINEER/VIEWER validés, workflow métier complet
// Application→Scenario→Step→Execution→History→Report testé de bout en
// bout). Cette page n'a plus qu'un seul chemin réel.
//
// P0-A — HttpClientExecutionEngine (threads virtuels Java 21) est désormais
// un vrai moteur de charge ASYNCHRONE : POST /api/executions répond
// immédiatement (202, QUEUED/RUNNING), la charge réelle (utilisateurs
// virtuels, ramp-up, durée/itérations — configurés sur le Scénario, voir
// Scenarios.tsx) s'exécute en arrière-plan. Cette page poll donc
// GET /api/executions (toutes les 2s, uniquement tant qu'au moins une
// Execution affichée est QUEUED/RUNNING — jamais de polling permanent) pour
// suivre la progression réelle jusqu'à un statut terminal. Le bouton
// "Annuler" est câblé et actif uniquement pour QUEUED/RUNNING (voir
// isCancellable et ExecutionServiceImpl.cancel — interruption réelle des
// utilisateurs virtuels en cours, pas un simple changement de statut).
// "Relancer" utilise le vrai endpoint POST /{id}/retry, qui crée une
// NOUVELLE Execution côté backend (jamais un clone local React).
// ============================================================

const STATUS_TABS = ['Tous', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED'] as const
type StatusTab = typeof STATUS_TABS[number]

// P0-A — QUEUED et RUNNING doivent rester visuellement distinguables (voir
// rapport P0-A, section UX) : QUEUED n'a pas encore réellement démarré
// (aucun utilisateur virtuel n'a encore envoyé de requête), RUNNING est la
// charge réelle en cours.
const statusLabel: Record<Exclude<StatusTab, 'Tous'>, string> = {
  QUEUED: 'En attente',
  RUNNING: 'En cours',
  SUCCESS: 'Réussie',
  FAILED: 'Échouée',
  CANCELLED: 'Annulée',
}

const statusPillClass: Record<Exclude<StatusTab, 'Tous'>, string> = {
  QUEUED: 'neutral',
  RUNNING: 'info',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELLED: 'neutral',
}

/** P0-A — seuls ces deux statuts sont réellement annulables côté backend
 * (voir ExecutionServiceImpl.cancel) : gate réelle du bouton Annuler, jamais
 * affiché/actif pour un statut terminal. */
function isCancellable(status: BackendExecutionResponse['status']): boolean {
  return status === 'QUEUED' || status === 'RUNNING'
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 400:
        return `Données invalides : ${err.message}`
      case 401:
        return 'Vous devez être connecté (Keycloak) pour effectuer cette action.'
      case 403:
        return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      case 404:
        return 'Scénario ou exécution introuvable (il/elle a peut-être déjà été supprimé(e)).'
      case 409:
        return err.message
      case 429:
        // P0-B — limite de capacite LoadPilot atteinte (utilisateurs
        // virtuels globaux ou nombre d'executions simultanees, voir
        // RunningExecutionRegistry) : le backend fournit deja un message
        // explicite (quelle limite, quelle valeur) - jamais un message
        // generique qui masquerait la vraie raison du refus.
        return `Limite de capacité LoadPilot atteinte : ${err.message}`
      default:
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function formatDate(iso: string) {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

function formatDuration(ms: number | null): string {
  if (ms == null) return '—'
  const totalSec = Math.round(ms / 1000)
  const h = String(Math.floor(totalSec / 3600)).padStart(2, '0')
  const m = String(Math.floor((totalSec % 3600) / 60)).padStart(2, '0')
  const s = String(totalSec % 60).padStart(2, '0')
  return `${h}:${m}:${s}`
}

// ============================================================
// Vue Spring Boot (Phase 20)
// ============================================================

function SpringExecutions() {
  const navigate = useNavigate()
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()

  const isKeycloak = authProvider === 'keycloak'
  // POST /api/executions, /cancel et /retry sont tous réservés à SUPER_ADMIN
  // et PERFORMANCE_ENGINEER côté backend (voir ExecutionController).
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  const { data: executions, loading: execLoading, error: execError, refetch: refetchExecutions } =
    useApiList<BackendExecutionResponse>(() => executionsBackendApi.getAll())
  const { data: scenarios } = useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll())
  const { data: applications } = useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const { data: allSteps } = useApiList<BackendStepResponse>(() => stepsBackendApi.getAll())

  const scenarioById = useMemo(() => new Map(scenarios.map((s) => [s.id, s])), [scenarios])
  const stepCountByScenario = useMemo(() => {
    const map = new Map<string, number>()
    for (const step of allSteps) {
      map.set(step.scenarioId, (map.get(step.scenarioId) ?? 0) + 1)
    }
    return map
  }, [allSteps])

  const [activeTab, setActiveTab] = useState<StatusTab>('Tous')
  const [searchQuery, setSearchQuery] = useState('')

  // Modale "Nouvelle exécution" — restaurée en 2 étapes d'après l'ancien
  // frontend (composant LaunchScenarioModal + useScenarioLauncher, commit
  // f04f935) : Application → Scénario (filtré par application, et ne
  // proposant que des scénarios possédant réellement des Steps) →
  // VUs/Durée/Ramp-up/Think time/Débit cible ÉDITABLES avec badge "Valeur du
  // scénario", puis confirmation. Comme sur la page Scénarios (voir
  // Scenarios.tsx), l'édition de ces champs persiste réellement les valeurs
  // sur le Scénario (PUT /api/scenarios/{id}) avant le lancement — le
  // contrat `ExecutionRequest` reste `{ scenarioId }` uniquement, aucune
  // surcharge "par exécution" n'existe côté backend. La progression après
  // lancement réutilise le même modèle "Exécution en direct" (agrégé,
  // `progressPercent` réel) que Scenarios.tsx : le backend n'expose aucun
  // détail par VU/étape ni de "Pause" (voir ce même commentaire là-bas).
  const [showLaunchModal, setShowLaunchModal] = useState(false)
  const [launchStep, setLaunchStep] = useState<1 | 2>(1)
  const [selectedApplicationId, setSelectedApplicationId] = useState('')
  const [selectedScenarioId, setSelectedScenarioId] = useState('')
  const [launchForm, setLaunchForm] = useState({
    virtualUsers: '',
    durationSeconds: '',
    rampUpSeconds: '',
    thinkTimeMs: '',
    targetRps: '',
    stopMode: 'AUTO' as BackendStopMode,
  })
  const [launching, setLaunching] = useState(false)
  const [launchError, setLaunchError] = useState<string | null>(null)
  const [liveExecution, setLiveExecution] = useState<BackendExecutionStatusResponse | null>(null)
  const [cancellingLive, setCancellingLive] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

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

  // Modale Détail (résultats réels de l'Execution Spring sélectionnée)
  const [selectedExecutionDetail, setSelectedExecutionDetail] = useState<BackendExecutionDetailResponse | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  const [retryingId, setRetryingId] = useState<string | null>(null)
  const [cancellingId, setCancellingId] = useState<string | null>(null)

  // P0-A — polling réel, jamais permanent : ne s'active que tant qu'au
  // moins une Execution affichée est réellement QUEUED/RUNNING, s'arrête de
  // lui-même sinon (voir note en tête de fichier — pas de WebSocket, un
  // polling raisonnable suffit pour ce volume/cette fréquence).
  const hasActiveExecution = executions.some((e) => e.status === 'QUEUED' || e.status === 'RUNNING')
  useEffect(() => {
    if (!hasActiveExecution) return
    const interval = setInterval(() => { refetchExecutions() }, 2000)
    return () => clearInterval(interval)
  }, [hasActiveExecution, refetchExecutions])

  // Rafraîchit aussi la modale Détail pendant qu'elle affiche une Execution
  // encore active, pour que ses compteurs/son statut suivent la réalité en
  // direct sans que l'utilisateur ait à la refermer/rouvrir.
  useEffect(() => {
    if (!selectedExecutionDetail || !isCancellable(selectedExecutionDetail.status)) return
    const interval = setInterval(() => {
      executionsBackendApi.getById(selectedExecutionDetail.id).then(setSelectedExecutionDetail).catch(() => {})
    }, 2000)
    return () => clearInterval(interval)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedExecutionDetail?.id, selectedExecutionDetail?.status])

  const filteredExecutions = executions.filter((exec) => {
    if (activeTab !== 'Tous' && exec.status !== activeTab) return false
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase()
      const appName = scenarioById.get(exec.scenarioId)?.applicationName ?? ''
      return exec.scenarioName.toLowerCase().includes(q) || appName.toLowerCase().includes(q)
    }
    return true
  })

  const { page, setPage, totalPages, pageItems, startIndex, endIndex, totalItems } = usePagination(filteredExecutions, 10)

  const openLaunchModal = () => {
    if (!canWrite) return
    setLaunchStep(1)
    setSelectedApplicationId('')
    setSelectedScenarioId('')
    setLaunchForm({ virtualUsers: '', durationSeconds: '', rampUpSeconds: '', thinkTimeMs: '', targetRps: '', stopMode: 'AUTO' })
    setLaunchError(null)
    setShowLaunchModal(true)
  }
  const closeLaunchModal = () => {
    if (launching) return
    setShowLaunchModal(false)
  }

  const selectScenario = (scenario: BackendScenarioResponse | undefined) => {
    setSelectedScenarioId(scenario?.id ?? '')
    if (scenario) {
      setLaunchForm({
        virtualUsers: String(scenario.virtualUsers),
        durationSeconds: scenario.durationSeconds != null ? String(scenario.durationSeconds) : '',
        rampUpSeconds: String(scenario.rampUpSeconds),
        thinkTimeMs: String(scenario.thinkTimeMs),
        targetRps: scenario.targetRps != null ? String(scenario.targetRps) : '',
        stopMode: scenario.stopMode,
      })
    }
  }

  const handleLaunch = async () => {
    const scenario = scenarioById.get(selectedScenarioId)
    if (!canWrite || !scenario || launching) return
    // Vérifie réellement que le scénario a des Steps AVANT de créer une
    // Execution — le backend refuserait de toute façon (409), mais on évite
    // ici l'appel réseau inutile et on donne le message exact demandé.
    if ((stepCountByScenario.get(scenario.id) ?? 0) === 0) {
      setLaunchError('Ce scénario ne contient aucune étape.')
      return
    }
    setLaunching(true)
    setLaunchError(null)
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
      }
      // P0-A — asynchrone : répond 202 avec QUEUED/RUNNING, jamais le
      // résultat final. On bascule sur la modale "Exécution en direct"
      // (polling réel de son statut) plutôt que de simplement fermer.
      const created = await executionsBackendApi.execute({ scenarioId: scenario.id })
      await refetchExecutions()
      setShowLaunchModal(false)
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
      const message = describeApiError(err, "Erreur lors du lancement de l'exécution.")
      setLaunchError(message)
      showToast(message, 'danger')
    } finally {
      setLaunching(false)
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

  const handleRetry = async (exec: BackendExecutionResponse) => {
    if (!canWrite || retryingId) return
    setRetryingId(exec.id)
    try {
      const result = await executionsBackendApi.retry(exec.id)
      await refetchExecutions()
      showToast(`Nouvelle exécution de « ${result.scenarioName} » lancée (${result.virtualUsers} utilisateur(s) virtuel(s)).`, 'success')
    } catch (err) {
      showToast(describeApiError(err, 'Erreur lors de la relance.'), 'danger')
    } finally {
      setRetryingId(null)
    }
  }

  const handleCancel = async (exec: BackendExecutionResponse) => {
    if (!canWrite || cancellingId || !isCancellable(exec.status)) return
    setCancellingId(exec.id)
    try {
      await executionsBackendApi.cancel(exec.id)
      await refetchExecutions()
      showToast(`Exécution de « ${exec.scenarioName} » annulée.`, 'success')
    } catch (err) {
      showToast(describeApiError(err, "Erreur lors de l'annulation."), 'danger')
    } finally {
      setCancellingId(null)
    }
  }

  const openDetail = (exec: BackendExecutionResponse) => {
    setDetailLoading(true)
    setSelectedExecutionDetail(null)
    executionsBackendApi.getById(exec.id)
      .then(setSelectedExecutionDetail)
      .catch((err) => showToast(describeApiError(err, "Impossible de charger le détail de l'exécution."), 'danger'))
      .finally(() => setDetailLoading(false))
  }

  // Scénarios proposés dans la modale de lancement : uniquement ceux qui ont
  // réellement au moins une Step Spring — pas de "0 étape" présélectionnable.
  const launchableScenarios = scenarios.filter((s) => (stepCountByScenario.get(s.id) ?? 0) > 0)
  const launchableScenariosForApp = launchableScenarios.filter((s) => s.applicationId === selectedApplicationId)
  const selectedScenario = scenarioById.get(selectedScenarioId)

  return (
    <>
      <div className="d-flex align-items-center justify-content-end mb-3">
        {canWrite && (
          <button className="pt-btn-primary" onClick={openLaunchModal}>
            <i className="bi bi-play-fill fs-6"></i>
            Nouvelle exécution
          </button>
        )}
      </div>

      {execError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger les exécutions : {execError}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => refetchExecutions()}>
            Réessayer
          </button>
        </div>
      )}

      {/* Stat Cards */}
      <div className="row g-3 mb-4">
        {[
          { label: 'Total Exécutions', value: executions.length, icon: 'bi-play-circle', color: 'blue' },
          { label: 'Réussies', value: executions.filter(e => e.status === 'SUCCESS').length, icon: 'bi-check-circle', color: 'green' },
          { label: 'Échouées', value: executions.filter(e => e.status === 'FAILED').length, icon: 'bi-x-circle', color: 'red' },
          { label: 'Annulées', value: executions.filter(e => e.status === 'CANCELLED').length, icon: 'bi-slash-circle', color: 'orange' },
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
            {STATUS_TABS.map(tab => (
              <button
                key={tab}
                className={`btn btn-sm ${activeTab === tab ? 'btn-primary' : 'btn-outline-secondary'}`}
                onClick={() => setActiveTab(tab)}
                style={{ borderRadius: 'var(--pt-radius-sm)', fontWeight: 600, fontSize: '13px' }}
              >
                {tab === 'Tous' ? 'Toutes' : statusLabel[tab]}
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
            <p>Aucune exécution Spring Boot pour l'instant. Lancez votre premier test !</p>
          </div>
        ) : (
        <div className="pt-table-wrapper">
          <table className="pt-table">
            <thead>
              <tr>
                <th>Scénario</th>
                <th>Application</th>
                <th>Statut</th>
                <th>Étapes</th>
                <th>Durée</th>
                <th>Démarré le</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {pageItems.map(exec => {
                const appName = scenarioById.get(exec.scenarioId)?.applicationName ?? '—'
                return (
                <tr key={exec.id}>
                  <td>
                    <button
                      onClick={() => navigate(`/scenarios?q=${encodeURIComponent(exec.scenarioName)}`)}
                      title="Voir ce scénario"
                      style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '13.5px', fontWeight: 500, color: 'var(--pt-text)', textDecoration: 'none' }}
                    >
                      {exec.scenarioName}
                    </button>
                  </td>
                  <td><span style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>{appName}</span></td>
                  <td>
                    <span className={`pt-pill ${statusPillClass[exec.status]}`}>
                      <span style={{ width: '6px', height: '6px', borderRadius: '50%', display: 'inline-block', marginRight: '5px', background: 'currentColor' }}></span>
                      {statusLabel[exec.status]}
                    </span>
                  </td>
                  <td>
                    <span style={{ fontSize: '12.5px' }}>
                      <span style={{ color: 'var(--pt-success)', fontWeight: 600 }}>{exec.successfulSteps}</span>
                      {' / '}
                      <span style={{ color: 'var(--pt-danger)', fontWeight: 600 }}>{exec.failedSteps}</span>
                      {' / '}
                      <span style={{ color: 'var(--pt-text-muted)' }}>{exec.totalSteps}</span>
                    </span>
                  </td>
                  <td><span style={{ fontSize: '13px', fontFamily: 'monospace' }}>{formatDuration(exec.duration)}</span></td>
                  <td><span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{formatDate(exec.startedAt)}</span></td>
                  <td>
                    <div className="d-flex justify-content-end gap-1">
                      <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Voir les détails" onClick={() => openDetail(exec)}>
                        <i className="bi bi-eye" style={{ fontSize: '14px' }}></i>
                      </button>
                      {/* Phase 23 : ExecutionDetail.tsx/ExecutionReport.tsx savent désormais
                          afficher une Execution Spring (détectée via son UUID réel) — lien
                          direct vers la page de rapport dédiée. */}
                      <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Voir le rapport" onClick={() => navigate(`/executions/report/${exec.id}`)}>
                        <i className="bi bi-file-text" style={{ fontSize: '14px' }}></i>
                      </button>
                      {canWrite && isCancellable(exec.status) && (
                        <button
                          className="topbar-icon"
                          style={{ width: '32px', height: '32px', border: '1px solid var(--pt-danger)', color: 'var(--pt-danger)' }}
                          title="Annuler cette exécution (interrompt réellement les utilisateurs virtuels en cours)"
                          disabled={cancellingId === exec.id}
                          onClick={() => handleCancel(exec)}
                        >
                          <i className={`bi ${cancellingId === exec.id ? 'bi-arrow-repeat pt-spin' : 'bi-slash-circle'}`} style={{ fontSize: '14px' }}></i>
                        </button>
                      )}
                      {canWrite && (
                        <button
                          className="topbar-icon"
                          style={{ width: '32px', height: '32px', border: '1px solid var(--pt-primary)', color: 'var(--pt-primary)' }}
                          title="Relancer (crée une nouvelle exécution)"
                          disabled={retryingId === exec.id}
                          onClick={() => handleRetry(exec)}
                        >
                          <i className={`bi ${retryingId === exec.id ? 'bi-arrow-repeat pt-spin' : 'bi-arrow-repeat'}`} style={{ fontSize: '14px' }}></i>
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

      {/* Modale "Nouvelle exécution" — 2 étapes, restaurée depuis l'ancien
          frontend (voir commentaire d'état plus haut). */}
      {showLaunchModal && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '560px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            <div className="d-flex justify-content-between align-items-center mb-2">
              <h5 style={{ fontWeight: 700, margin: 0 }}><i className="bi bi-gear me-2 text-primary"></i>Nouvelle exécution</h5>
              <button onClick={closeLaunchModal} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)' }}>
                <i className="bi bi-x"></i>
              </button>
            </div>
            <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)', marginBottom: '4px' }}>Étape {launchStep} sur 2</div>
            <div style={{ height: '4px', borderRadius: '2px', background: 'var(--pt-bg)', overflow: 'hidden', marginBottom: '1.25rem' }}>
              <div style={{ height: '100%', width: launchStep === 1 ? '50%' : '100%', background: 'var(--pt-primary)', transition: 'width 0.2s ease' }}></div>
            </div>

            {launchError && (
              <div className="pt-alert-banner danger mb-3">
                <i className="bi bi-exclamation-triangle-fill"></i>
                {launchError}
              </div>
            )}

            {launchStep === 1 ? (
              <>
                <div className="row g-3">
                  <div className="col-12">
                    <label className="pt-form-label">Application *</label>
                    <select
                      className="pt-form-control"
                      value={selectedApplicationId}
                      onChange={(e) => { setSelectedApplicationId(e.target.value); selectScenario(undefined) }}
                    >
                      <option value="" disabled>Sélectionner une application…</option>
                      {applications.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                    </select>
                  </div>
                  <div className="col-12">
                    <label className="pt-form-label">Scénario *</label>
                    <select
                      className="pt-form-control"
                      value={selectedScenarioId}
                      disabled={!selectedApplicationId}
                      onChange={(e) => selectScenario(launchableScenariosForApp.find((s) => s.id === e.target.value))}
                    >
                      <option value="" disabled>
                        {selectedApplicationId ? 'Sélectionner un scénario…' : "Choisissez d'abord une application"}
                      </option>
                      {launchableScenariosForApp.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                    </select>
                    {selectedApplicationId && launchableScenariosForApp.length === 0 && (
                      <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>
                        Aucun scénario avec étape pour cette application.
                      </div>
                    )}
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label d-flex align-items-center gap-2">
                      Utilisateurs virtuels (VUs)
                      {selectedScenario && (
                        <span className="pt-pill neutral" style={{ fontSize: '10px' }}>Valeur du scénario : {selectedScenario.virtualUsers}</span>
                      )}
                    </label>
                    <input type="number" min={1} className="pt-form-control" disabled={!selectedScenario} value={launchForm.virtualUsers}
                      onChange={(e) => setLaunchForm((f) => ({ ...f, virtualUsers: e.target.value }))} />
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label">Durée (secondes)</label>
                    <input type="number" min={0} className="pt-form-control" disabled={!selectedScenario} value={launchForm.durationSeconds}
                      placeholder="Optionnel"
                      onChange={(e) => setLaunchForm((f) => ({ ...f, durationSeconds: e.target.value }))} />
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label d-flex align-items-center gap-2">
                      Ramp-up (secondes)
                      {selectedScenario && (
                        <span className="pt-pill neutral" style={{ fontSize: '10px' }}>Valeur du scénario : {selectedScenario.rampUpSeconds}</span>
                      )}
                    </label>
                    <input type="number" min={0} className="pt-form-control" disabled={!selectedScenario} value={launchForm.rampUpSeconds}
                      onChange={(e) => setLaunchForm((f) => ({ ...f, rampUpSeconds: e.target.value }))} />
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label">Think time (ms)</label>
                    <input type="number" min={0} className="pt-form-control" disabled={!selectedScenario} value={launchForm.thinkTimeMs}
                      onChange={(e) => setLaunchForm((f) => ({ ...f, thinkTimeMs: e.target.value }))} />
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label">Débit cible (req/s) optionnel</label>
                    <input type="number" min={0} className="pt-form-control" disabled={!selectedScenario} value={launchForm.targetRps}
                      placeholder="Ex: 500"
                      onChange={(e) => setLaunchForm((f) => ({ ...f, targetRps: e.target.value }))} />
                  </div>
                  <div className="col-12">
                    <label className="pt-form-label">Mode d'arrêt</label>
                    <select className="pt-form-control" disabled={!selectedScenario} value={launchForm.stopMode} onChange={(e) => setLaunchForm((f) => ({ ...f, stopMode: e.target.value as BackendStopMode }))}>
                      <option value="AUTO">Automatique (durée/itérations définies)</option>
                      <option value="MANUAL">Manuel (tourne jusqu'à annulation)</option>
                    </select>
                    <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>
                      Réellement appliqué par le moteur d'exécution — en mode Manuel, seul "Annuler" arrête l'exécution.
                    </div>
                  </div>
                </div>
                <div className="d-flex gap-2 justify-content-end mt-4">
                  <button onClick={closeLaunchModal} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                    Annuler
                  </button>
                  <button onClick={() => setLaunchStep(2)} disabled={!selectedScenario} style={{ background: !selectedScenario ? '#93C5FD' : 'var(--pt-primary)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: !selectedScenario ? 'not-allowed' : 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                    Suivant <i className="bi bi-arrow-right ms-1"></i>
                  </button>
                </div>
              </>
            ) : selectedScenario ? (
              <>
                <div className="pt-alert-banner mb-3" style={{ fontSize: '12px' }}>
                  <i className="bi bi-info-circle-fill"></i> Les valeurs modifiées seront enregistrées sur le scénario (PUT réel) avant le lancement de l'exécution.
                </div>
                <div className="d-flex flex-column gap-2" style={{ fontSize: '13px' }}>
                  <div className="d-flex justify-content-between"><span className="text-muted">Application</span><strong>{selectedScenario.applicationName}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Scénario</span><strong>{selectedScenario.name}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Utilisateurs virtuels (VUs)</span><strong>{launchForm.virtualUsers || selectedScenario.virtualUsers}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Ramp-up</span><strong>{launchForm.rampUpSeconds || selectedScenario.rampUpSeconds} s</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Durée</span><strong>{launchForm.durationSeconds !== '' ? `${launchForm.durationSeconds} s` : '—'}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Think time</span><strong>{launchForm.thinkTimeMs || selectedScenario.thinkTimeMs} ms</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Débit cible</span><strong>{launchForm.targetRps !== '' ? `${launchForm.targetRps} req/s` : 'Aucun'}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Mode d'arrêt</span><strong>{launchForm.stopMode === 'AUTO' ? 'Automatique' : 'Manuel'}</strong></div>
                </div>
                <div className="d-flex gap-2 justify-content-end mt-4">
                  <button onClick={() => setLaunchStep(1)} disabled={launching} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                    <i className="bi bi-arrow-left me-1"></i> Précédent
                  </button>
                  <button onClick={handleLaunch} disabled={launching} style={{ background: 'var(--pt-success)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 600, minWidth: '140px' }}>
                    {launching ? <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Lancement...</> : <><i className="bi bi-play-fill me-2"></i>Lancer</>}
                  </button>
                </div>
              </>
            ) : null}
          </div>
        </div>
      )}

      {/* "Exécution en direct" — progression AGRÉGÉE réelle après lancement
          depuis cette modale (même modèle que Scenarios.tsx : aucun détail
          par VU/étape ni "Pause" côté backend). */}
      {liveExecution && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 10000, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
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

      {/* Modale Détail Execution */}
      {(detailLoading || selectedExecutionDetail) && (
        <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1050 }}>
          <div className="modal-dialog modal-dialog-centered modal-lg">
            <div className="modal-content" style={{ borderRadius: 'var(--pt-radius)', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}>
              <div className="modal-header">
                <h5 className="modal-title d-flex align-items-center gap-2" style={{ fontSize: '16px', fontWeight: 600 }}>
                  <i className="bi bi-play-circle text-primary"></i>
                  Détail de l'exécution {selectedExecutionDetail ? `— ${selectedExecutionDetail.scenarioName}` : ''}
                </h5>
                <div className="d-flex align-items-center gap-2">
                  {selectedExecutionDetail && canWrite && isCancellable(selectedExecutionDetail.status) && (
                    <button
                      className="pt-btn-outline"
                      style={{ fontSize: '12px', padding: '0.3rem 0.7rem', color: 'var(--pt-danger)', borderColor: 'var(--pt-danger)' }}
                      disabled={cancellingId === selectedExecutionDetail.id}
                      onClick={() => handleCancel(selectedExecutionDetail)}
                    >
                      <i className={`bi ${cancellingId === selectedExecutionDetail.id ? 'bi-arrow-repeat pt-spin' : 'bi-slash-circle'} me-1`}></i>
                      Annuler
                    </button>
                  )}
                  <button type="button" className="btn-close" onClick={() => setSelectedExecutionDetail(null)}></button>
                </div>
              </div>
              <div className="modal-body p-4">
                {detailLoading ? (
                  <div className="text-center text-muted py-4">
                    <i className="bi bi-arrow-repeat pt-spin me-1"></i>Chargement...
                  </div>
                ) : selectedExecutionDetail && (
                  <>
                    <div className="row g-3 mb-3">
                      <div className="col-6 col-md-3">
                        <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Statut</div>
                        <span className={`pt-pill ${statusPillClass[selectedExecutionDetail.status]}`}>{statusLabel[selectedExecutionDetail.status]}</span>
                      </div>
                      <div className="col-6 col-md-3">
                        <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Durée</div>
                        <div style={{ fontSize: '13.5px', fontWeight: 600 }}>{formatDuration(selectedExecutionDetail.duration)}</div>
                      </div>
                      <div className="col-6 col-md-3">
                        <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Démarrée le</div>
                        <div style={{ fontSize: '13.5px', fontWeight: 600 }}>{formatDate(selectedExecutionDetail.startedAt)}</div>
                      </div>
                      <div className="col-6 col-md-3">
                        <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Terminée le</div>
                        <div style={{ fontSize: '13.5px', fontWeight: 600 }}>{selectedExecutionDetail.finishedAt ? formatDate(selectedExecutionDetail.finishedAt) : '—'}</div>
                      </div>
                    </div>
                    <div className="d-flex flex-wrap gap-2 mb-3">
                      <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-people me-1"></i>{selectedExecutionDetail.virtualUsers} VU</span>
                      <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-graph-up-arrow me-1"></i>Ramp-up {selectedExecutionDetail.rampUpSeconds}s</span>
                      {selectedExecutionDetail.durationSeconds != null && (
                        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-stopwatch me-1"></i>Durée {selectedExecutionDetail.durationSeconds}s</span>
                      )}
                      {selectedExecutionDetail.iterations != null && (
                        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-arrow-repeat me-1"></i>{selectedExecutionDetail.iterations} itération(s)</span>
                      )}
                    </div>
                    {selectedExecutionDetail.errorMessage && (
                      <div className="pt-alert-banner danger mb-3">
                        <i className="bi bi-exclamation-triangle-fill"></i>
                        {selectedExecutionDetail.errorMessage}
                      </div>
                    )}
                    <h6 style={{ fontSize: '14px', fontWeight: 600 }}>
                      Résultats par étape ({selectedExecutionDetail.results.length}
                      {selectedExecutionDetail.totalSteps > selectedExecutionDetail.results.length
                        ? ` sur ${selectedExecutionDetail.totalSteps} — arrêt à la première étape en échec`
                        : ''})
                    </h6>
                    <div className="pt-table-wrapper" style={{ maxHeight: '320px', overflowY: 'auto' }}>
                      <table className="pt-table" style={{ fontSize: '12.5px' }}>
                        <thead>
                          <tr>
                            <th>#</th>
                            <th>Étape</th>
                            <th>HTTP</th>
                            <th>Temps</th>
                            <th>Résultat</th>
                          </tr>
                        </thead>
                        <tbody>
                          {selectedExecutionDetail.results.map((r, idx) => (
                            <tr key={idx}>
                              <td>{idx + 1}</td>
                              <td>
                                <span style={{ padding: '0.1rem 0.4rem', borderRadius: '4px', fontSize: '10.5px', fontWeight: 700, background: 'var(--pt-primary-light)', color: 'var(--pt-primary)' }}>{r.method}</span>
                                {' '}<span style={{ fontWeight: 600 }}>{r.stepName}</span>{' '}
                                <code style={{ fontSize: '11px', color: 'var(--pt-text-muted)' }}>{r.url}</code>
                              </td>
                              <td>{r.httpStatus ?? '—'}</td>
                              <td>{r.responseTime} ms</td>
                              <td>
                                {r.success ? (
                                  <span style={{ color: 'var(--pt-success)', fontWeight: 600 }}><i className="bi bi-check-lg me-1"></i>Réussi</span>
                                ) : (
                                  <span style={{ color: 'var(--pt-danger)', fontWeight: 600 }} title={r.error ?? undefined}><i className="bi bi-x-lg me-1"></i>Échec{r.error ? ` — ${r.error}` : ''}</span>
                                )}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </>
                )}
              </div>
              <div className="modal-footer d-flex justify-content-end">
                <button className="pt-btn-outline" onClick={() => setSelectedExecutionDetail(null)}>Fermer</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  )
}


// ============================================================
// Page hôte — P1-O : un seul chemin réel désormais (le moteur Legacy et
// son onglet ont été retirés, voir note en tête de fichier).
// ============================================================

function Executions() {
  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Exécutions</h1>
          <p>Lancez et suivez vos exécutions de tests de charge</p>
        </div>
        <TopBar searchPlaceholder="Rechercher une exécution..." />
      </div>

      <SpringExecutions />
    </div>
  )
}

export default Executions
