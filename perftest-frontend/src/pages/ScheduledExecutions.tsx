import { useEffect, useState } from 'react'
import TopBar from '../components/TopBar'
import { scheduledExecutionsBackendApi } from '../services/api/scheduledExecutionsBackend'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import {
  BackendScenarioResponse,
  BackendScheduledExecutionRequest,
  BackendScheduledExecutionResponse,
  BackendScheduleType,
} from '../types/backendContracts'
import { ApiError } from '../services/api/httpClient'

// ============================================================
// P1-B — Planification RÉELLE et PERSISTÉE d'exécutions (voir
// controller.ScheduledExecutionController côté backend) : survit à un
// redémarrage backend, déclenchée par le MÊME moteur exactement que le
// lancement manuel (ExecutionService), avec une vraie protection anti
// double-déclenchement transactionnelle côté backend (voir
// ScheduledExecutionTransactionHelper). Remplace l'ancien
// hooks/useScheduledExecutions.ts (décommissionné, voir MainLayout.tsx) —
// jamais deux systèmes de planification concurrents.
// ============================================================

const labelStyle = { fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' as const }
const inputStyle = { width: '100%', padding: '8px 12px', borderRadius: 'var(--pt-radius-sm)', border: '1px solid var(--pt-border)', background: 'var(--pt-bg)', color: 'var(--pt-text)', fontSize: '13.5px' }

const browserTimezone = (() => {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' } catch { return 'UTC' }
})()

interface FormState {
  scenarioId: string
  name: string
  scheduleType: BackendScheduleType
  cronExpression: string
  runAtLocal: string
  timezone: string
}

const emptyForm: FormState = {
  scenarioId: '', name: '', scheduleType: 'ONE_TIME', cronExpression: '0 0 8 * * *', runAtLocal: '', timezone: browserTimezone,
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0: return err.message
      case 401: return 'Vous devez être connecté (Keycloak).'
      case 403: return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      case 429: return 'Limite globale de capacité atteinte — réessayez une fois qu\'une exécution en cours sera terminée.'
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

function toLocalDateTimeInputValue(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function ScheduledExecutions() {
  const [scenarios, setScenarios] = useState<BackendScenarioResponse[]>([])
  const [schedules, setSchedules] = useState<BackendScheduledExecutionResponse[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const [showForm, setShowForm] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm)
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const load = () => {
    setLoading(true)
    setError(null)
    Promise.all([scheduledExecutionsBackendApi.list(), scenariosBackendApi.getAll()])
      .then(([sched, scen]) => { setSchedules(sched); setScenarios(scen) })
      .catch((err) => setError(describeApiError(err, 'Impossible de charger les planifications.')))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const openCreateForm = () => {
    setEditingId(null)
    setForm({ ...emptyForm, scenarioId: scenarios[0]?.id ?? '' })
    setFormError(null)
    setShowForm(true)
  }

  const openEditForm = (s: BackendScheduledExecutionResponse) => {
    setEditingId(s.id)
    setForm({
      scenarioId: s.scenarioId,
      name: s.name,
      scheduleType: s.scheduleType,
      cronExpression: s.cronExpression ?? '0 0 8 * * *',
      runAtLocal: s.runAt ? toLocalDateTimeInputValue(s.runAt) : '',
      timezone: s.timezone,
    })
    setFormError(null)
    setShowForm(true)
  }

  const handleSubmit = async () => {
    setFormError(null)
    if (!form.scenarioId) { setFormError('Sélectionnez un scénario.'); return }
    if (!form.name.trim()) { setFormError('Le nom est obligatoire.'); return }
    if (form.scheduleType === 'ONE_TIME' && !form.runAtLocal) { setFormError('Choisissez une date/heure de déclenchement.'); return }
    if (form.scheduleType === 'RECURRING_CRON' && !form.cronExpression.trim()) { setFormError('Renseignez une expression cron.'); return }

    const payload: BackendScheduledExecutionRequest = {
      scenarioId: form.scenarioId,
      name: form.name.trim(),
      scheduleType: form.scheduleType,
      cronExpression: form.scheduleType === 'RECURRING_CRON' ? form.cronExpression.trim() : null,
      runAt: form.scheduleType === 'ONE_TIME' ? new Date(form.runAtLocal).toISOString() : null,
      timezone: form.timezone.trim() || 'UTC',
    }

    setSaving(true)
    try {
      if (editingId) {
        await scheduledExecutionsBackendApi.update(editingId, payload)
      } else {
        await scheduledExecutionsBackendApi.create(payload)
      }
      setShowForm(false)
      load()
    } catch (err) {
      setFormError(describeApiError(err, 'Impossible d\'enregistrer cette planification.'))
    } finally {
      setSaving(false)
    }
  }

  const runAction = async (id: string, action: () => Promise<unknown>) => {
    setBusyId(id)
    setError(null)
    try {
      await action()
      load()
    } catch (err) {
      setError(describeApiError(err, 'Action impossible.'))
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Planification</h1>
          <p>Déclenchement automatique et réel de vos scénarios, persisté côté serveur</p>
        </div>
        <div className="d-flex align-items-center gap-2">
          <button className="pt-btn-primary" onClick={openCreateForm} disabled={scenarios.length === 0 && !loading}>
            <i className="bi bi-plus-lg me-1"></i>Nouvelle planification
          </button>
          <TopBar searchPlaceholder="" />
        </div>
      </div>

      {error && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={load}>Réessayer</button>
        </div>
      )}

      <div className="pt-card" style={{ padding: 0 }}>
        {loading ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement des planifications...</p>
          </div>
        ) : !schedules || schedules.length === 0 ? (
          <div className="pt-empty-state">
            <i className="bi bi-calendar-week" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
            <p>Aucune planification — créez-en une pour déclencher automatiquement un scénario.</p>
          </div>
        ) : (
          <div className="pt-table-wrapper">
            <table className="pt-table">
              <thead>
                <tr>
                  <th>Nom</th>
                  <th>Scénario</th>
                  <th>Type</th>
                  <th>Prochaine exécution</th>
                  <th>Dernier déclenchement</th>
                  <th>Statut</th>
                  <th style={{ textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {schedules.map((s) => (
                  <tr key={s.id}>
                    <td>
                      <span style={{ fontSize: '13px', fontWeight: 600 }}>{s.name}</span>
                      {s.lastTriggerError && (
                        <div title={s.lastTriggerError} style={{ fontSize: '11px', color: 'var(--pt-danger)' }}>
                          <i className="bi bi-exclamation-triangle-fill me-1"></i>Dernier échec de déclenchement
                        </div>
                      )}
                    </td>
                    <td><span style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>{s.scenarioName}</span></td>
                    <td>
                      <span className="pt-pill neutral" style={{ fontSize: '11px' }}>
                        {s.scheduleType === 'ONE_TIME' ? 'Ponctuelle' : 'Récurrente (cron)'}
                      </span>
                      <div style={{ fontSize: '11px', color: 'var(--pt-text-light)' }}>
                        {s.scheduleType === 'RECURRING_CRON' ? s.cronExpression : formatDate(s.runAt)} · {s.timezone}
                      </div>
                    </td>
                    <td><span style={{ fontSize: '12.5px' }}>{formatDate(s.nextRunAt)}</span></td>
                    <td><span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{formatDate(s.lastTriggeredAt)}</span></td>
                    <td>
                      <span className={`pt-pill ${s.enabled ? 'success' : 'neutral'}`} style={{ fontSize: '11px' }}>
                        {s.enabled ? 'Activée' : 'Désactivée'}
                      </span>
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <div className="d-flex justify-content-end gap-1">
                        <button className="topbar-icon" style={{ width: '28px', height: '28px', border: '1px solid var(--pt-border)' }}
                          title="Exécuter maintenant" disabled={busyId === s.id}
                          onClick={() => runAction(s.id, () => scheduledExecutionsBackendApi.runNow(s.id))}>
                          <i className="bi bi-play-fill" style={{ fontSize: '13px' }}></i>
                        </button>
                        <button className="topbar-icon" style={{ width: '28px', height: '28px', border: '1px solid var(--pt-border)' }}
                          title={s.enabled ? 'Désactiver' : 'Activer'} disabled={busyId === s.id}
                          onClick={() => runAction(s.id, () => s.enabled ? scheduledExecutionsBackendApi.disable(s.id) : scheduledExecutionsBackendApi.enable(s.id))}>
                          <i className={`bi ${s.enabled ? 'bi-pause-fill' : 'bi-check2'}`} style={{ fontSize: '13px' }}></i>
                        </button>
                        <button className="topbar-icon" style={{ width: '28px', height: '28px', border: '1px solid var(--pt-border)' }}
                          title="Modifier" disabled={busyId === s.id} onClick={() => openEditForm(s)}>
                          <i className="bi bi-pencil" style={{ fontSize: '13px' }}></i>
                        </button>
                        <button className="topbar-icon" style={{ width: '28px', height: '28px', border: '1px solid var(--pt-border)' }}
                          title="Supprimer" disabled={busyId === s.id}
                          onClick={() => runAction(s.id, () => scheduledExecutionsBackendApi.delete(s.id))}>
                          <i className="bi bi-trash" style={{ fontSize: '13px' }}></i>
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showForm && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '520px', maxWidth: '95vw', maxHeight: '90vh', overflowY: 'auto', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
            <div className="d-flex justify-content-between align-items-center mb-3">
              <h5 style={{ fontWeight: 700, margin: 0 }}>{editingId ? 'Modifier la planification' : 'Nouvelle planification'}</h5>
              <button onClick={() => setShowForm(false)} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)' }}>
                <i className="bi bi-x"></i>
              </button>
            </div>

            {formError && (
              <div className="pt-alert-banner danger mb-3">
                <i className="bi bi-exclamation-triangle-fill"></i>
                {formError}
              </div>
            )}

            <div className="row g-3">
              <div className="col-12">
                <label style={labelStyle}>Scénario *</label>
                <select style={inputStyle} value={form.scenarioId} onChange={(e) => setForm((p) => ({ ...p, scenarioId: e.target.value }))}>
                  <option value="" disabled>Sélectionner un scénario…</option>
                  {scenarios.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.applicationName})</option>)}
                </select>
              </div>
              <div className="col-12">
                <label style={labelStyle}>Nom de la planification *</label>
                <input style={inputStyle} value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} placeholder="Ex: Test de charge nocturne" />
              </div>
              <div className="col-12">
                <label style={labelStyle}>Type *</label>
                <select style={inputStyle} value={form.scheduleType} onChange={(e) => setForm((p) => ({ ...p, scheduleType: e.target.value as BackendScheduleType }))}>
                  <option value="ONE_TIME">Ponctuelle (une seule fois)</option>
                  <option value="RECURRING_CRON">Récurrente (expression cron)</option>
                </select>
              </div>
              {form.scheduleType === 'ONE_TIME' ? (
                <div className="col-12">
                  <label style={labelStyle}>Date et heure de déclenchement *</label>
                  <input type="datetime-local" style={inputStyle} value={form.runAtLocal} onChange={(e) => setForm((p) => ({ ...p, runAtLocal: e.target.value }))} />
                </div>
              ) : (
                <div className="col-12">
                  <label style={labelStyle}>Expression cron * (6 champs : secondes minutes heures jour mois jourSemaine)</label>
                  <input style={{ ...inputStyle, fontFamily: 'monospace' }} value={form.cronExpression} onChange={(e) => setForm((p) => ({ ...p, cronExpression: e.target.value }))} placeholder="0 0 8 * * *" />
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>Exemple : "0 0 8 * * *" = tous les jours à 08:00.</div>
                </div>
              )}
              <div className="col-12">
                <label style={labelStyle}>Fuseau horaire *</label>
                <input style={inputStyle} value={form.timezone} onChange={(e) => setForm((p) => ({ ...p, timezone: e.target.value }))} placeholder="Africa/Casablanca" />
                <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>Identifiant IANA (ex: Africa/Casablanca, UTC, Europe/Paris) — pré-rempli avec le fuseau de votre navigateur.</div>
              </div>
            </div>

            <div className="d-flex justify-content-end gap-2 mt-4">
              <button className="pt-btn-outline" onClick={() => setShowForm(false)} disabled={saving}>Annuler</button>
              <button className="pt-btn-primary" onClick={handleSubmit} disabled={saving}>
                {saving ? 'Enregistrement...' : editingId ? 'Enregistrer' : 'Créer'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default ScheduledExecutions
