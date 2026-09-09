import React, { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Application, Scenario, Execution, ApplicationConnectionStatus } from '../types'
import { applicationsApi, deriveConnectionStatus } from '../services/api/applications'
import { scenariosApi } from '../services/api/scenarios'
import { executionsApi } from '../services/api/executions'
import { useApiList } from '../hooks/useApiResource'
import { usePagination } from '../hooks/usePagination'
import Pagination from '../components/Pagination'
import { firstError, validateRequired, validateMaxLength, validateAbsoluteUrl, NAME_MAX_LENGTH } from '../utils/validation'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

const emptyForm = {
  name: '',
  url: '',
  type: 'Web' as Application['type'],
  authMethod: 'Bearer Token' as Application['authMethod'],
  authToken: '',
  authUsername: '',
  authPassword: '',
  authClientId: '',
  authClientSecret: '',
  monitoringUrl: '',
}

const statusPillStyle: Record<ApplicationConnectionStatus, { bg: string; color: string; icon: string }> = {
  'Connectée': { bg: 'var(--pt-success-light)', color: 'var(--pt-success)', icon: 'bi-wifi' },
  'Non connectée': { bg: 'var(--pt-danger-light)', color: 'var(--pt-danger)', icon: 'bi-wifi-off' },
}

function Applications() {
  const navigate = useNavigate()
  const { canEdit } = useAuth()
  const { showToast } = useToast()

  // Données réelles depuis JSON Server (services/api) — plus aucune donnée
  // statique/codée en dur. Chaque liste expose loading/error/refetch.
  const { data: apps, loading: appsLoading, error: appsError, refetch: refetchApps } =
    useApiList<Application>(() => applicationsApi.getAll())
  const { data: scenarios } = useApiList<Scenario>(() => scenariosApi.getAll())
  const { data: executions } = useApiList<Execution>(() => executionsApi.getAll())

  const [searchQuery, setSearchQuery] = useState('')
  const [showModal, setShowModal] = useState(false)
  const [editingApp, setEditingApp] = useState<Application | null>(null)
  const [form, setForm] = useState(emptyForm)
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null)
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'success' | 'error'>('idle')
  const [isViewOnly, setIsViewOnly] = useState(false)
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  // Erreurs de validation affichées sous les champs "Nom" / "URL" — au blur
  // (touched) puis systématiquement dès la tentative d'enregistrement.
  const [touched, setTouched] = useState<{ name?: boolean; url?: boolean }>({})
  const nameError = firstError(
    validateRequired(form.name, "Le nom de l'application"),
    validateMaxLength(form.name, NAME_MAX_LENGTH, "Le nom de l'application")
  )
  const urlError = firstError(validateRequired(form.url, "L'URL"), validateAbsoluteUrl(form.url))
  const isFormValid = !nameError && !urlError

  const filtered = apps.filter(a =>
    a.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    a.url.toLowerCase().includes(searchQuery.toLowerCase()) ||
    a.type.toLowerCase().includes(searchQuery.toLowerCase())
  )

  const { page, setPage, totalPages, pageItems, startIndex, endIndex, totalItems } = usePagination(filtered, 10)

  // Dernière exécution + statut de connexion réels, par application (id
  // stable), recalculés à partir des vraies exécutions de JSON Server.
  const latestExecutionByApp = useMemo(() => {
    const map = new Map<string, Execution>()
    for (const exec of executions) {
      const current = map.get(exec.applicationId)
      if (!current || new Date(exec.startedAt) > new Date(current.startedAt)) {
        map.set(exec.applicationId, exec)
      }
    }
    return map
  }, [executions])

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
    setActionError(null)
    setTouched({})
    setIsViewOnly(false)
    setShowModal(true)
  }

  const openEdit = (app: Application) => {
    setEditingApp(app)
    setForm({
      name: app.name,
      url: app.url,
      type: app.type,
      authMethod: app.authMethod,
      authToken: app.authToken || '',
      authUsername: app.authUsername || '',
      authPassword: app.authPassword || '',
      authClientId: app.authClientId || '',
      authClientSecret: app.authClientSecret || '',
      monitoringUrl: app.monitoringUrl || '',
    })
    setTestStatus('idle')
    setActionError(null)
    setTouched({})
    setIsViewOnly(false)
    setShowModal(true)
  }

  /** Mode "Consulter" : ouvre la même modale que Modifier mais en lecture
   * seule pure (pas de formulaire, aucune action mutante) — n'édite jamais
   * `form`, contrairement à openEdit. */
  const openView = (app: Application) => {
    setEditingApp(app)
    setActionError(null)
    setIsViewOnly(true)
    setShowModal(true)
  }

  const closeModal = () => {
    setShowModal(false)
    setTestStatus('idle')
    setIsViewOnly(false)
  }

  const handleSubmit = async () => {
    // Filet de sécurité (en plus des boutons masqués/fieldset désactivé) :
    // même déclenché par un moyen détourné, un rôle Visiteur ne doit
    // jamais pouvoir modifier une donnée.
    if (!canEdit) return
    // Fait apparaître les erreurs de tous les champs (même non visités) et
    // bloque l'enregistrement tant que le formulaire n'est pas valide —
    // cohérent avec la validation au blur ci-dessous.
    setTouched({ name: true, url: true })
    if (!isFormValid || saving) return
    setSaving(true)
    setActionError(null)
    try {
      if (editingApp) {
        // PATCH réel sur JSON Server
        await applicationsApi.update(editingApp.id, form)
        showToast(`Application « ${form.name} » modifiée avec succès.`, 'success')
      } else {
        const icons: Record<string, string> = { 'Web': 'bi-globe2', 'API REST': 'bi-code-slash', 'SOAP': 'bi-braces', 'Mobile': 'bi-phone' }
        const colors: Record<string, Application['color']> = { 'Web': 'blue', 'API REST': 'green', 'SOAP': 'orange', 'Mobile': 'purple' }
        // POST réel sur JSON Server
        await applicationsApi.create({
          ...form,
          status: 'Actif',
          icon: icons[form.type] || 'bi-globe2',
          color: colors[form.type] || 'blue',
          createdAt: new Date().toISOString(),
        })
        showToast(`Application « ${form.name} » ajoutée avec succès.`, 'success')
      }
      await refetchApps()
      setShowModal(false)
      setTestStatus('idle')
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Erreur lors de l\'enregistrement'
      setActionError(message)
      showToast(message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  // Vrai test de connexion : envoie une vraie requête HTTP vers l'URL de
  // l'application (timeout court) au lieu de simuler le résultat avec un
  // setTimeout. Une réponse (même non-2xx) prouve que le serveur répond ;
  // seule une vraie erreur réseau/CORS/timeout est considérée comme un échec.
  const handleTestAndAdd = async () => {
    setTouched({ name: true, url: true })
    if (!isFormValid || testStatus === 'testing') return
    setTestStatus('testing')
    setActionError(null)

    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 6000)
    try {
      await fetch(form.url, { method: 'GET', signal: controller.signal })
      setTestStatus('success')
      setTimeout(() => {
        handleSubmit()
      }, 700)
    } catch (err) {
      const isAbort = err instanceof DOMException && err.name === 'AbortError'
      setActionError(
        isAbort
          ? `Délai de connexion dépassé sur ${form.url}.`
          : `Impossible de joindre ${form.url} : ${err instanceof Error ? err.message : 'erreur réseau'}.`
      )
      setTestStatus('error')
    } finally {
      clearTimeout(timer)
    }
  }

  const handleDelete = async (id: string) => {
    if (!canEdit) return
    const appName = apps.find((a) => String(a.id) === String(id))?.name ?? ''
    setSaving(true)
    try {
      // DELETE réel sur JSON Server
      await applicationsApi.remove(id)
      await refetchApps()
      setDeleteConfirm(null)
      showToast(`Application « ${appName} » supprimée avec succès.`, 'success')
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Erreur lors de la suppression'
      setActionError(message)
      showToast(message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  const iconColorStyle = (color: Application['color']) => ({
    width: '40px', height: '40px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '18px', flexShrink: 0 as const,
    background: color === 'blue' ? 'var(--pt-primary-light)' : color === 'green' ? 'var(--pt-success-light)' : color === 'purple' ? '#EDE9FE' : 'var(--pt-warning-light)',
    color: color === 'blue' ? 'var(--pt-primary)' : color === 'green' ? 'var(--pt-success)' : color === 'purple' ? '#7C3AED' : 'var(--pt-warning)',
  })

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Applications</h1>
          <p>Gérez vos applications à tester</p>
        </div>
      </div>

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
          { label: 'Applications actives', value: apps.filter(a => a.status === 'Actif').length, icon: 'bi-check-circle', color: 'green' },
          { label: 'Applications inactives', value: apps.filter(a => a.status === 'Inactif').length, icon: 'bi-pause-circle', color: 'purple' },
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
        {canEdit && (
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
                <th>Type</th>
                <th>Auth</th>
                <th>Statut</th>
                <th>Scénarios</th>
                <th>Dernière exécution</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {pageItems.map((app) => {
                const latestExec = latestExecutionByApp.get(app.id) ?? null
                const connStatus = deriveConnectionStatus(app, latestExec)
                const pill = statusPillStyle[connStatus]
                return (
                <tr key={app.id}>
                  <td><input type="checkbox" /></td>
                  <td>
                    <div className="d-flex align-items-center gap-3">
                      <div style={iconColorStyle(app.color)}><i className={`bi ${app.icon}`}></i></div>
                      <span style={{ fontSize: '13.5px', fontWeight: 600, color: 'var(--pt-text)' }}>{app.name}</span>
                    </div>
                  </td>
                  <td>
                    <a href={app.url} target="_blank" rel="noreferrer" style={{ fontSize: '13px', color: 'var(--pt-primary)' }}>
                      {app.url}
                    </a>
                  </td>
                  <td><span style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>{app.type}</span></td>
                  <td><span style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>{app.authMethod}</span></td>
                  <td>
                    <span
                      className="pt-pill"
                      style={{ background: pill.bg, color: pill.color, fontSize: '11.5px' }}
                      title={latestExec ? `Dernière exécution : ${latestExec.status}` : 'Aucune exécution enregistrée'}
                    >
                      <i className={`bi ${pill.icon}`} style={{ fontSize: '10px' }}></i>
                      {connStatus}
                    </span>
                  </td>
                  <td>
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
                  </td>
                  <td>
                    {latestExec ? (
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
                      {canEdit && (
                        <>
                          <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)' }} title="Modifier" onClick={() => openEdit(app)}>
                            <i className="bi bi-pencil" style={{ fontSize: '14px' }}></i>
                          </button>
                          <button className="topbar-icon" style={{ width: '32px', height: '32px', border: '1px solid var(--pt-border)', color: 'var(--pt-danger)' }} title="Supprimer" onClick={() => setDeleteConfirm(app.id)}>
                            <i className="bi bi-trash" style={{ fontSize: '14px' }}></i>
                          </button>
                        </>
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

            {isViewOnly && editingApp ? (
              // Mode Consulter : contenu d'information pur, jamais un
              // formulaire — aucun champ modifiable, aucun secret en clair.
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
                <div className="col-6">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Type</div>
                  <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{editingApp.type}</div>
                </div>
                <div className="col-6">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Statut</div>
                  {(() => {
                    const connStatus = deriveConnectionStatus(editingApp, latestExecutionByApp.get(editingApp.id) ?? null)
                    const pill = statusPillStyle[connStatus]
                    return (
                      <span className="pt-pill" style={{ background: pill.bg, color: pill.color, fontSize: '11.5px' }}>
                        <i className={`bi ${pill.icon}`} style={{ fontSize: '10px' }}></i> {connStatus}
                      </span>
                    )
                  })()}
                </div>
                <div className="col-6">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Méthode d'authentification</div>
                  <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{editingApp.authMethod}</div>
                </div>
                {editingApp.authMethod !== 'Aucune' && (
                  <div className="col-6">
                    <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Identifiants</div>
                    <div style={{ fontSize: '13.5px', fontWeight: 500 }}>
                      {editingApp.authToken || editingApp.authUsername || editingApp.authClientId
                        ? '•••••••• (configuré)'
                        : 'Non configuré'}
                    </div>
                  </div>
                )}
                <div className="col-12">
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Date de création</div>
                  <div style={{ fontSize: '13.5px', fontWeight: 500 }}>{editingApp.createdAt}</div>
                </div>
              </div>
            ) : (
            <fieldset disabled={!canEdit} className="row g-3" style={{ border: 'none', padding: 0, margin: 0 }}>
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
              <div className="col-6">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Type</label>
                <select className="pt-form-control" style={{ width: '100%' }} value={form.type} onChange={e => setForm(p => ({...p, type: e.target.value as Application['type']}))}>
                  <option>Web</option>
                  <option>API REST</option>
                  <option>SOAP</option>
                  <option>Mobile</option>
                </select>
              </div>
              <div className="col-6">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Méthode d'authentification</label>
                <select className="pt-form-control" style={{ width: '100%' }} value={form.authMethod} onChange={e => setForm(p => ({...p, authMethod: e.target.value as Application['authMethod']}))}>
                  <option>Aucune</option>
                  <option>Basic</option>
                  <option>Bearer Token</option>
                  <option>API Key</option>
                  <option>OAuth2</option>
                </select>
              </div>

              {form.authMethod === 'Bearer Token' && (
                <div className="col-6">
                  <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Token</label>
                  <input type="password" className="pt-form-control" style={{ width: '100%' }} placeholder="Collez votre token" value={form.authToken} onChange={e => setForm(p => ({...p, authToken: e.target.value}))} />
                </div>
              )}

              {form.authMethod === 'API Key' && (
                <div className="col-6">
                  <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Clé API</label>
                  <input type="password" className="pt-form-control" style={{ width: '100%' }} placeholder="Collez votre clé API" value={form.authToken} onChange={e => setForm(p => ({...p, authToken: e.target.value}))} />
                </div>
              )}

              {form.authMethod === 'Basic' && (
                <>
                  <div className="col-6">
                    <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Nom d'utilisateur</label>
                    <input className="pt-form-control" style={{ width: '100%' }} placeholder="ex: admin" value={form.authUsername} onChange={e => setForm(p => ({...p, authUsername: e.target.value}))} />
                  </div>
                  <div className="col-6">
                    <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Mot de passe</label>
                    <input type="password" className="pt-form-control" style={{ width: '100%' }} placeholder="••••••••" value={form.authPassword} onChange={e => setForm(p => ({...p, authPassword: e.target.value}))} />
                  </div>
                </>
              )}

              {form.authMethod === 'OAuth2' && (
                <>
                  <div className="col-6">
                    <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Client ID</label>
                    <input className="pt-form-control" style={{ width: '100%' }} placeholder="ex: 8f3a-client-id" value={form.authClientId} onChange={e => setForm(p => ({...p, authClientId: e.target.value}))} />
                  </div>
                  <div className="col-6">
                    <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>Client Secret</label>
                    <input type="password" className="pt-form-control" style={{ width: '100%' }} placeholder="••••••••" value={form.authClientSecret} onChange={e => setForm(p => ({...p, authClientSecret: e.target.value}))} />
                  </div>
                </>
              )}

              <div className="col-12">
                <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' }}>
                  URL de monitoring <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span>
                </label>
                <input
                  className="pt-form-control"
                  style={{ width: '100%' }}
                  placeholder="ex: http://localhost:5000/server-metrics"
                  value={form.monitoringUrl}
                  onChange={e => setForm(p => ({ ...p, monitoringUrl: e.target.value }))}
                />
                <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>
                </div>
              </div>
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
                    canEdit && (
                      <button onClick={handleSubmit} disabled={!isFormValid || saving} style={{ background: !isFormValid ? '#93C5FD' : 'var(--pt-primary)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: !isFormValid ? 'not-allowed' : 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                        {saving ? <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Enregistrement...</> : <><i className="bi bi-check2 me-2"></i>Enregistrer</>}
                      </button>
                    )
                  ) : (
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
                        <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Connexion en cours...</>
                      ) : testStatus === 'success' ? (
                        <><i className="bi bi-check-circle-fill me-2"></i>Connecté</>
                      ) : testStatus === 'error' ? (
                        <><i className="bi bi-exclamation-triangle-fill me-2"></i>Échec — Réessayer</>
                      ) : (
                        <><i className="bi bi-wifi me-2"></i>Tester l'application</>
                      )}
                    </button>
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
              {apps.find(a => String(a.id) === String(deleteConfirm))?.name} sera définitivement supprimée.
            </p>
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
