import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { BackendApplicationRequest, BackendApplicationResponse, BackendApplicationStatus, BackendScenarioResponse, BackendExecutionResponse } from '../types/backendContracts'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { ApiError } from '../services/api/httpClient'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { useApiList } from '../hooks/useApiResource'
import { usePagination } from '../hooks/usePagination'
import Pagination from '../components/Pagination'
import { firstError, validateRequired, validateMaxLength, validateAbsoluteUrl, NAME_MAX_LENGTH, DESCRIPTION_MAX_LENGTH } from '../utils/validation'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

// ============================================================
// Phase 17 — ce module (et lui seul) parle désormais au vrai backend Spring
// Boot (voir services/api/applicationsBackend.ts), plus à JSON Server.
//
// Champs volontairement ABSENTS du formulaire par rapport à l'ancienne
// version (type/authMethod/token/username/password/clientId/clientSecret/
// icon/color) : aucun n'existe dans le contrat réel du backend
// (ApplicationRequest/ApplicationResponse, voir types/backendContracts.ts)
// — les garder aurait signifié les saisir sans jamais les persister nulle
// part, ce qui aurait été trompeur. `description` est en revanche un champ
// réel du backend, ajouté ici.
//
// P1-I — "Scénarios"/"Dernière exécution" utilisent désormais les VRAIS
// Scénarios/Exécutions Spring Boot (scenariosBackendApi/executionsBackendApi,
// déjà réels et déjà utilisés par Scenarios.tsx), plus JSON Server.
//
// Endpoint dashboard PAR APPLICATION déjà existant côté backend
// (GET /api/dashboard/applications/{id} -> ApplicationDashboardResponse,
// déjà câblé côté frontend : dashboardBackendApi.getByApplication)
// délibérément NON utilisé ici, pour deux raisons prouvées (voir rapport
// P1-I) : (1) son DTO ne contient AUCUN champ date — seulement des
// COMPTEURS agrégés (ScenariosSummaryResponse/ExecutionsSummaryResponse),
// impossible d'en dériver "la dernière exécution" ; (2) l'appeler une fois
// par ligne affichée créerait un vrai N+1 (aucun endpoint de liste groupée
// n'existe : GET /api/dashboard ne couvre que la plateforme entière).
//
// Solution retenue : scenariosBackendApi.getAll()/executionsBackendApi.getAll()
// (un seul appel chacun, quel que soit le nombre d'Applications affichées),
// jointes côté client par scenario.applicationId puis execution.scenarioId
// — même principe que Scenarios.tsx (stepsByScenario/latestExecByScenario).
// ============================================================

const emptyForm = {
  name: '',
  description: '',
  url: '',
  // Passage produit réel (2026-09-30) — ces 3 champs sont désormais
  // RÉELLEMENT persistés (Application.type/authMethod/authToken côté
  // backend/PostgreSQL, voir ApplicationRequest.java). authToken reste
  // volontairement écriture seule (jamais relu, voir hasAuthToken sur
  // BackendApplicationResponse) — laisser ce champ vide lors d'une
  // modification conserve le token déjà enregistré.
  type: 'Web' as 'Web' | 'API REST' | 'SOAP' | 'Mobile',
  authMethod: 'Bearer Token' as 'Aucune' | 'Basic' | 'Bearer Token' | 'API Key' | 'OAuth2',
  authToken: '',
}

type StatusKey = BackendApplicationStatus | 'UNTESTED'

const statusPillStyle: Record<StatusKey, { bg: string; color: string; icon: string; label: string }> = {
  CONNECTED: { bg: 'var(--pt-success-light)', color: 'var(--pt-success)', icon: 'bi-wifi', label: 'Connectée' },
  FAILED: { bg: 'var(--pt-danger-light)', color: 'var(--pt-danger)', icon: 'bi-wifi-off', label: 'Échec' },
  ERROR: { bg: 'var(--pt-danger-light)', color: 'var(--pt-danger)', icon: 'bi-exclamation-triangle', label: 'Erreur' },
  UNTESTED: { bg: 'var(--pt-bg)', color: 'var(--pt-text-muted)', icon: 'bi-question-circle', label: 'Non testée' },
}

function statusKey(status: BackendApplicationStatus | null): StatusKey {
  return status ?? 'UNTESTED'
}

/** Traduit une erreur RÉELLE (jamais masquée, voir Phase 17 section 17) en
 * message utilisateur. Le code HTTP prime toujours ; 409/400 réutilisent le
 * message backend tel quel (déjà explicite, ex: "des scenarios sont encore
 * rattaches"). */
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
        return 'Application introuvable (elle a peut-être déjà été supprimée).'
      case 409:
        return err.message
      default:
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function Applications() {
  const navigate = useNavigate()
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()

  // Le backend Spring Boot exige un JWT réel (voir SecurityConfig) : sans
  // session Keycloak active, aucun token n'existe donc aucune action
  // d'écriture n'est proposée - la liste elle-même échouera en 401 (voir
  // appsError ci-dessous), affiché tel quel, jamais masqué.
  const isKeycloak = authProvider === 'keycloak'
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))
  const canDelete = isKeycloak && rawRoles.includes('ROLE_SUPER_ADMIN')

  const { data: apps, loading: appsLoading, error: appsError, refetch: refetchApps } =
    useApiList<BackendApplicationResponse>(() => applicationsBackendApi.getAll())
  const { data: scenarios, loading: scenariosLoading, error: scenariosError } =
    useApiList<BackendScenarioResponse>(() => scenariosBackendApi.getAll())
  const { data: executions, loading: executionsLoading, error: executionsError } =
    useApiList<BackendExecutionResponse>(() => executionsBackendApi.getAll())

  const [searchQuery, setSearchQuery] = useState('')
  const [showModal, setShowModal] = useState(false)
  const [editingApp, setEditingApp] = useState<BackendApplicationResponse | null>(null)
  const [form, setForm] = useState(emptyForm)
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null)
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'success' | 'error'>('idle')
  const [testResultMessage, setTestResultMessage] = useState<string | null>(null)
  const [isViewOnly, setIsViewOnly] = useState(false)
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [touched, setTouched] = useState<{ name?: boolean; url?: boolean }>({})

  const nameError = firstError(
    validateRequired(form.name, "Le nom de l'application"),
    validateMaxLength(form.name, NAME_MAX_LENGTH, "Le nom de l'application")
  )
  const urlError = firstError(validateRequired(form.url, "L'URL"), validateAbsoluteUrl(form.url))
  const descriptionError = validateMaxLength(form.description, DESCRIPTION_MAX_LENGTH, 'La description')
  const isFormValid = !nameError && !urlError && !descriptionError

  const filtered = apps.filter(a =>
    a.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    a.url.toLowerCase().includes(searchQuery.toLowerCase()) ||
    (a.description ?? '').toLowerCase().includes(searchQuery.toLowerCase())
  )

  const { page, setPage, totalPages, pageItems, startIndex, endIndex, totalItems } = usePagination(filtered, 10)

  // P1-I — Dernière exécution + nombre de scénarios RÉELS (Spring Boot) par
  // application. Execution n'a pas de colonne applicationId directe (voir
  // entity.Execution : uniquement une FK vers Scenario) — la résolution
  // passe donc par scenario.applicationId, exactement comme Scenarios.tsx.
  const scenarioAppById = useMemo(() => {
    const map = new Map<string, string>()
    for (const s of scenarios) map.set(s.id, s.applicationId)
    return map
  }, [scenarios])

  const latestExecutionByApp = useMemo(() => {
    const map = new Map<string, BackendExecutionResponse>()
    for (const exec of executions) {
      const applicationId = scenarioAppById.get(exec.scenarioId)
      if (!applicationId) continue
      const current = map.get(applicationId)
      if (!current || new Date(exec.startedAt) > new Date(current.startedAt)) {
        map.set(applicationId, exec)
      }
    }
    return map
  }, [executions, scenarioAppById])

  const scenarioCountByApp = (applicationId: string) =>
    scenarios.filter((s) => s.applicationId === applicationId).length

  const formatDate = (iso: string) => {
    const d = new Date(iso)
    if (isNaN(d.getTime())) return iso
    return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
  }

  const openAdd = () => {
    setEditingApp(null)
    setForm(emptyForm)
    setTestStatus('idle')
    setTestResultMessage(null)
    setActionError(null)
    setTouched({})
    setIsViewOnly(false)
    setShowModal(true)
  }

  const openEdit = (app: BackendApplicationResponse) => {
    setEditingApp(app)
    setForm({
      name: app.name,
      description: app.description || '',
      url: app.url,
      // Restaurés depuis les vraies valeurs persistées. authToken reste
      // vide (jamais relu, voir hasAuthToken) — un champ vide au moment
      // d'enregistrer signifie "ne pas modifier le token existant".
      type: (app.type as typeof emptyForm.type) || 'Web',
      authMethod: (app.authMethod as typeof emptyForm.authMethod) || 'Bearer Token',
      authToken: '',
    })
    setTestStatus('idle')
    setTestResultMessage(null)
    setActionError(null)
    setTouched({})
    setIsViewOnly(false)
    setShowModal(true)
  }

  /** Mode "Consulter" : lecture seule pure, jamais un formulaire. */
  const openView = (app: BackendApplicationResponse) => {
    setEditingApp(app)
    setActionError(null)
    setIsViewOnly(true)
    setShowModal(true)
  }

  const closeModal = () => {
    setShowModal(false)
    setTestStatus('idle')
    setTestResultMessage(null)
    setIsViewOnly(false)
  }

  const buildPayload = (): BackendApplicationRequest => ({
    name: form.name.trim(),
    description: form.description.trim() || null,
    url: form.url.trim(),
    type: form.type,
    authMethod: form.authMethod === 'Aucune' ? null : form.authMethod,
    // Vide = ne pas modifier le token déjà enregistré (voir
    // ApplicationServiceImpl.update, authToken écriture seule).
    authToken: form.authToken.trim() || undefined,
  })

  const handleSubmit = async () => {
    // Filet de sécurité (en plus des boutons masqués/fieldset désactivé) :
    // même déclenché par un moyen détourné, le frontend ne fait jamais
    // confiance qu'à lui-même — le backend revalide de toute façon (403).
    if (!canWrite) return
    setTouched({ name: true, url: true })
    if (!isFormValid || saving) return
    setSaving(true)
    setActionError(null)
    try {
      if (editingApp) {
        const updated = await applicationsBackendApi.update(editingApp.id, buildPayload())
        showToast(`Application « ${updated.name} » modifiée avec succès.`, 'success')
      } else {
        const created = await applicationsBackendApi.create(buildPayload())
        showToast(`Application « ${created.name} » ajoutée avec succès.`, 'success')
      }
      // Affichage mis à jour depuis la VRAIE réponse backend (via refetch),
      // jamais une mise à jour optimiste locale.
      await refetchApps()
      setShowModal(false)
      setTestStatus('idle')
    } catch (err) {
      const message = describeApiError(err, "Erreur lors de l'enregistrement.")
      setActionError(message)
      showToast(message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  // Test de disponibilité RÉEL, exécuté par le backend (jamais un fetch()
  // direct depuis ce composant, voir Phase 17 section 10). Le backend exige
  // une Application déjà créée pour la tester (POST /{id}/test) : la
  // création est donc faite d'abord, et réussit indépendamment du résultat
  // du test qui suit (comportement réel du backend, voir Phase 6) — une
  // différence assumée avec l'ancien flux "test avant création".
  const handleTestAndAdd = async () => {
    if (!canWrite) return
    setTouched({ name: true, url: true })
    if (!isFormValid || testStatus === 'testing') return
    setTestStatus('testing')
    setActionError(null)
    setTestResultMessage(null)
    try {
      const created = await applicationsBackendApi.create(buildPayload())
      const result = await applicationsBackendApi.test(created.id)
      setTestResultMessage(result.message)
      setTestStatus(result.status === 'CONNECTED' ? 'success' : 'error')
      await refetchApps()
      showToast(
        `Application « ${created.name} » ajoutée (statut réel : ${statusPillStyle[result.status].label}).`,
        result.status === 'CONNECTED' ? 'success' : 'warning'
      )
      setTimeout(() => {
        setShowModal(false)
        setTestStatus('idle')
        setTestResultMessage(null)
      }, 1100)
    } catch (err) {
      const message = describeApiError(err, "Erreur lors de la création/du test.")
      setActionError(message)
      setTestStatus('error')
      showToast(message, 'danger')
    }
  }

  const handleDelete = async (id: string) => {
    if (!canDelete) return
    const appName = apps.find((a) => a.id === id)?.name ?? ''
    setSaving(true)
    setActionError(null)
    try {
      await applicationsBackendApi.remove(id)
      await refetchApps()
      setDeleteConfirm(null)
      showToast(`Application « ${appName} » supprimée avec succès.`, 'success')
    } catch (err) {
      const message = describeApiError(err, 'Erreur lors de la suppression.')
      setActionError(message)
      showToast(message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Applications</h1>
          <p>Gérez vos applications à tester</p>
        </div>
      </div>

      {(scenariosError || executionsError) && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger {scenariosError && executionsError ? 'les scénarios et exécutions' : scenariosError ? 'les scénarios' : 'les exécutions'} réel(le)s ({scenariosError || executionsError}) — les colonnes "Scénarios"/"Dernière exécution" ci-dessous peuvent être incomplètes.
        </div>
      )}

      {appsError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Impossible de charger les applications : {appsError}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={() => refetchApps()}>
            Réessayer
          </button>
        </div>
      )}

      <div className="row g-3 mb-4">
        {[
          { label: 'Total applications', value: apps.length, icon: 'bi-globe2', color: 'blue' },
          { label: 'Connectées', value: apps.filter(a => a.status === 'CONNECTED').length, icon: 'bi-wifi', color: 'green' },
          { label: 'Non connectées', value: apps.filter(a => a.status === 'FAILED' || a.status === 'ERROR').length, icon: 'bi-wifi-off', color: 'purple' },
        ].map((card, i) => (
          <div key={i} className="col-6 col-md-4">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">{card.label}</div>
                  <div className="stat-value">{appsLoading ? '—' : card.value}</div>
                </div>
                <div className={`stat-icon ${card.color}`}><i className={`bi ${card.icon}`}></i></div>
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className="d-flex align-items-center gap-3 flex-wrap mb-3">
        <div className="pt-search">
          <i className="bi bi-search"></i>
          <input type="text" placeholder="Rechercher une application..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
        </div>
        {canWrite && (
          <button className="pt-btn-primary" onClick={openAdd}>
            <i className="bi bi-plus-lg"></i>
            Ajouter une application
          </button>
        )}
      </div>

      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Liste des applications ({filtered.length})</h6>
        </div>

        {appsLoading ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement des applications...</p>
          </div>
        ) : filtered.length === 0 ? (
          <div className="pt-empty-state">
            <i className="bi bi-globe2" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
            <p>Aucune application {searchQuery ? 'ne correspond à votre recherche' : "n'a encore été créée"}.</p>
          </div>
        ) : (
        <div className="pt-table-wrapper">
          <table className="pt-table">
            <thead>
              <tr>
                <th style={{ width: '40px' }}><input type="checkbox" /></th>
                <th>Nom</th>
                <th>URL</th>
                <th>Description</th>
                <th>Statut</th>
                <th>Scénarios</th>
                <th>Dernière exécution</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {pageItems.map((app) => {
                const latestExec = latestExecutionByApp.get(app.id) ?? null
                const pill = statusPillStyle[statusKey(app.status)]
                return (
                <tr key={app.id}>
                  <td><input type="checkbox" /></td>
                  <td>
                    <div className="d-flex align-items-center gap-3">
                      <div style={{ width: '40px', height: '40px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '18px', flexShrink: 0, background: 'var(--pt-primary-light)', color: 'var(--pt-primary)' }}>
                        <i className="bi bi-globe2"></i>
                      </div>
                      <span style={{ fontSize: '13.5px', fontWeight: 600, color: 'var(--pt-text)' }}>{app.name}</span>
                    </div>
                  </td>
                  <td>
                    <a href={app.url} target="_blank" rel="noreferrer" style={{ fontSize: '13px', color: 'var(--pt-primary)' }}>
                      {app.url}
                    </a>
                  </td>
                  <td><span style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>{app.description || '—'}</span></td>
                  <td>
                    <span
                      className="pt-pill"
                      style={{ background: pill.bg, color: pill.color, fontSize: '11.5px' }}
                      title={app.status ? `Résultat du dernier test réel : ${app.status}` : "Aucun test de disponibilité lancé"}
                    >
                      <i className={`bi ${pill.icon}`} style={{ fontSize: '10px' }}></i>
                      {pill.label}
                    </span>
                  </td>
                  <td>
                    {scenariosLoading ? (
                      <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}></i>
                    ) : (
                      <button
                        onClick={() => navigate(`/scenarios?app=${encodeURIComponent(app.name)}`)}
                        title="Voir les scénarios de cette application"
                        style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer' }}
                      >
                        <span className="pt-pill neutral">
                          <i className="bi bi-diagram-3 me-1" style={{ fontSize: '11px' }}></i>
                          {scenarioCountByApp(app.id)} scénario{scenarioCountByApp(app.id) > 1 ? 's' : ''}
                        </span>
                      </button>
                    )}
                  </td>
                  <td>
                    {executionsLoading ? (
                      <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}></i>
                    ) : latestExec ? (
                      <button
                        onClick={() => navigate(`/executions/detail/${latestExec.id}`)}
                        title="Voir le détail de cette exécution"
                        style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: '13px', color: 'var(--pt-text-muted)', textDecoration: 'none' }}
                      >
                        {formatDate(latestExec.startedAt)}
                      </button>
                    ) : (
                      <span style={{ fontSize: '13px', color: 'var(--pt-text-light)' }}>—</span>
                    )}
                  </td>
                  <td>
                    <div className="d-flex justify-content-end gap-1">
                      <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Voir" onClick={() => openView(app)}>
                        <i className="bi bi-eye" style={{ fontSize: '14px' }}></i>
                      </button>
                      {canWrite && (
                        <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Modifier" onClick={() => openEdit(app)}>
                          <i className="bi bi-pencil" style={{ fontSize: '14px' }}></i>
                        </button>
                      )}
                      {canDelete && (
                        <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)', color: 'var(--pt-danger)' }} title="Supprimer" onClick={() => setDeleteConfirm(app.id)}>
                          <i className="bi bi-trash" style={{ fontSize: '14px' }}></i>
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
          itemLabel="applications"
        />
      </div>

      {/* Add/Edit Modal */}
      {showModal && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '520px', maxWidth: '95vw', maxHeight: '90vh', overflowY: 'auto', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            <div className="d-flex justify-content-between align-items-center mb-4">
              <h5 style={{ fontWeight: 700, margin: 0 }}>
                {isViewOnly ? `Détails — ${editingApp?.name ?? ''}` : editingApp ? 'Modifier l\'application' : 'Ajouter une application'}
              </h5>
              <button onClick={closeModal} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)' }}>
                <i className="bi bi-x"></i>
              </button>
            </div>

            {actionError && (
              <div className="pt-alert-banner danger mb-3">
                <i className="bi bi-exclamation-triangle-fill"></i>
                {actionError}
              </div>
            )}

            {testResultMessage && (
              <div className={`pt-alert-banner ${testStatus === 'success' ? 'success' : 'danger'} mb-3`}>
                <i className={`bi ${testStatus === 'success' ? 'bi-check-circle-fill' : 'bi-exclamation-triangle-fill'}`}></i>
                {testResultMessage}
              </div>
            )}

            {isViewOnly && editingApp ? (
              // Mode Consulter : contenu d'information pur, jamais un formulaire.
              <div className="row g-3">
                <div className="col-12">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Nom de l'application</div>
                  <div style={{ fontSize: '14px', fontWeight: 600 }}>{editingApp.name}</div>
                </div>
                <div className="col-12">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>URL</div>
                  <a href={editingApp.url} target="_blank" rel="noreferrer" style={{ fontSize: '13.5px', fontWeight: 500, color: 'var(--pt-primary)', wordBreak: 'break-all' }}>
                    {editingApp.url} <i className="bi bi-box-arrow-up-right ms-1" style={{ fontSize: '11px' }}></i>
                  </a>
                </div>
                {editingApp.description && (
                  <div className="col-12">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Description</div>
                    <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{editingApp.description}</div>
                  </div>
                )}
                <div className="col-6">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Statut réseau réel</div>
                  {(() => {
                    const pill = statusPillStyle[statusKey(editingApp.status)]
                    return (
                      <span className="pt-pill" style={{ background: pill.bg, color: pill.color, fontSize: '11.5px' }}>
                        <i className={`bi ${pill.icon}`} style={{ fontSize: '10px' }}></i> {pill.label}
                      </span>
                    )
                  })()}
                </div>
                <div className="col-12">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Date de création</div>
                  <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{formatDate(editingApp.createdAt)}</div>
                </div>
              </div>
            ) : (
            <fieldset disabled={!canWrite} className="row g-3" style={{ border: 'none', padding: 0, margin: 0 }}>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Nom de l'application *</label>
                <input
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: touched.name && nameError ? 'var(--pt-danger)' : undefined }}
                  placeholder="ex: Mon Application Web"
                  value={form.name}
                  onChange={e => setForm(p => ({...p, name: e.target.value}))}
                  onBlur={() => setTouched(t => ({ ...t, name: true }))}
                />
                {touched.name && nameError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{nameError}</div>
                )}
              </div>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>URL *</label>
                <input
                  className="pt-form-control"
                  style={{ width: '100%', borderColor: touched.url && urlError ? 'var(--pt-danger)' : undefined }}
                  placeholder="ex: https://app.monsite.com"
                  value={form.url}
                  onChange={e => setForm(p => ({...p, url: e.target.value}))}
                  onBlur={() => setTouched(t => ({ ...t, url: true }))}
                />
                {touched.url && urlError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{urlError}</div>
                )}
              </div>
              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  Description <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span>
                </label>
                <input
                  className="pt-form-control"
                  style={{ width: '100%' }}
                  placeholder="ex: Application de test"
                  value={form.description}
                  onChange={e => setForm(p => ({...p, description: e.target.value}))}
                />
                {descriptionError && (
                  <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{descriptionError}</div>
                )}
              </div>

              <div className="col-6">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Type</label>
                <select className="pt-form-control" style={{ width: '100%' }} value={form.type} onChange={e => setForm(p => ({ ...p, type: e.target.value as typeof form.type }))}>
                  <option>Web</option>
                  <option>API REST</option>
                  <option>SOAP</option>
                  <option>Mobile</option>
                </select>
              </div>
              <div className="col-6">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Méthode d'authentification</label>
                <select className="pt-form-control" style={{ width: '100%' }} value={form.authMethod} onChange={e => setForm(p => ({ ...p, authMethod: e.target.value as typeof form.authMethod }))}>
                  <option>Aucune</option>
                  <option>Basic</option>
                  <option>Bearer Token</option>
                  <option>API Key</option>
                  <option>OAuth2</option>
                </select>
              </div>
              {form.authMethod !== 'Aucune' && (
                <div className="col-12">
                  <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Token</label>
                  <input
                    type="password" className="pt-form-control" style={{ width: '100%' }}
                    placeholder={editingApp?.hasAuthToken ? '•••••••• (laisser vide pour conserver le token actuel)' : 'Collez votre token'}
                    value={form.authToken}
                    onChange={e => setForm(p => ({ ...p, authToken: e.target.value }))}
                  />
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>
                    Réellement enregistré côté serveur — jamais relu ni réaffiché en clair (comme un mot de passe).
                    {editingApp?.hasAuthToken ? ' Un token est actuellement configuré.' : ''}
                  </div>
                </div>
              )}
            </fieldset>
            )}

            <div className="d-flex gap-2 justify-content-end mt-4">
              {isViewOnly ? (
                <button onClick={closeModal} className="pt-btn-outline" style={{ padding: '8px 20px' }}>
                  Fermer
                </button>
              ) : (
                <>
                  <button onClick={closeModal} disabled={testStatus === 'testing' || saving} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: testStatus === 'testing' ? 'not-allowed' : 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                    Annuler
                  </button>
                  {editingApp ? (
                    canWrite && (
                      <button onClick={handleSubmit} disabled={!isFormValid || saving} style={{ background: !isFormValid ? '#93C5FD' : 'var(--pt-primary)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: !isFormValid ? 'not-allowed' : 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                        {saving ? <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Enregistrement...</> : <><i className="bi bi-check2 me-2"></i>Enregistrer</>}
                      </button>
                    )
                  ) : (
                    canWrite && (
                      <button
                        onClick={handleTestAndAdd}
                        disabled={!isFormValid || testStatus === 'testing' || testStatus === 'success'}
                        style={{
                          background: !isFormValid ? '#93C5FD' : testStatus === 'success' ? 'var(--pt-success)' : testStatus === 'error' ? 'var(--pt-danger)' : 'var(--pt-primary)',
                          color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px',
                          cursor: !isFormValid || testStatus === 'testing' ? 'not-allowed' : 'pointer',
                          fontSize: '13.5px', fontWeight: 600, minWidth: '190px',
                        }}
                      >
                        {testStatus === 'testing' ? (
                          <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Création + test en cours...</>
                        ) : testStatus === 'success' ? (
                          <><i className="bi bi-check-circle-fill me-2"></i>Connectée</>
                        ) : testStatus === 'error' ? (
                          <><i className="bi bi-exclamation-triangle-fill me-2"></i>Ajoutée — Échec du test</>
                        ) : (
                          <><i className="bi bi-wifi me-2"></i>Tester et ajouter</>
                        )}
                      </button>
                    )
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Delete Confirmation Modal */}
      {deleteConfirm !== null && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '400px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)', textAlign: 'center' }}>
            <div style={{ width: '56px', height: '56px', borderRadius: '50%', background: 'var(--pt-danger-light)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 1rem' }}>
              <i className="bi bi-trash" style={{ fontSize: '24px', color: 'var(--pt-danger)' }}></i>
            </div>
            <h5 style={{ fontWeight: 700, marginBottom: '8px' }}>Supprimer l'application ?</h5>
            <p style={{ color: 'var(--pt-text-muted)', fontSize: '14px', marginBottom: '1.5rem' }}>
              {apps.find(a => a.id === deleteConfirm)?.name} sera définitivement supprimée.
            </p>
            {actionError && (
              <div className="pt-alert-banner danger mb-3" style={{ textAlign: 'left' }}>
                <i className="bi bi-exclamation-triangle-fill"></i>
                {actionError}
              </div>
            )}
            <div className="d-flex gap-2 justify-content-center">
              <button onClick={() => setDeleteConfirm(null)} disabled={saving} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>
                Annuler
              </button>
              <button onClick={() => handleDelete(deleteConfirm)} disabled={saving} style={{ background: 'var(--pt-danger)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                {saving ? 'Suppression...' : 'Supprimer'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default Applications
