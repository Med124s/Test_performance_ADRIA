import { useEffect, useState } from 'react'
import TopBar from '../components/TopBar'
import { notificationPreferencesBackendApi } from '../services/api/notificationPreferencesBackend'
import { BackendNotificationPreferenceResponse, BackendNotificationType } from '../types/backendContracts'
import { ApiError } from '../services/api/httpClient'

// ============================================================
// P1-D — page RÉELLE (remplace l'ancienne version 100% fictive : canaux
// Slack/Teams/Webhooks/SMS non implémentés, règles d'alerte sur seuils
// CPU/RAM inexistants depuis la Phase 30, silence windows jamais
// fonctionnelles, tout persisté en localStorage — voir le rapport P1-D,
// section "Notification preferences" pour l'analyse complète).
//
// Seul le canal in-app RÉEL (voir P1-B) est supporté aujourd'hui — cette
// page ne prétend JAMAIS qu'Email/Slack/Teams/SMS fonctionnent. Chaque
// toggle ci-dessous appelle réellement PATCH /api/notification-preferences/
// {type} et est réellement respecté par NotificationServiceImpl côté
// backend (une notification désactivée n'est simplement jamais générée).
// ============================================================

const typeLabel: Record<BackendNotificationType, string> = {
  EXECUTION_SUCCESS: 'Exécution réussie',
  EXECUTION_FAILED: 'Exécution échouée',
  EXECUTION_CANCELLED: 'Exécution annulée',
  SCHEDULE_TRIGGERED: 'Planification déclenchée',
  SCHEDULE_FAILED: 'Échec de déclenchement de planification',
}
const typeDescription: Record<BackendNotificationType, string> = {
  EXECUTION_SUCCESS: "Une exécution que vous avez lancée (manuellement ou via une planification) s'est terminée avec succès.",
  EXECUTION_FAILED: "Une exécution que vous avez lancée s'est terminée en échec.",
  EXECUTION_CANCELLED: 'Une exécution que vous avez lancée a été annulée.',
  SCHEDULE_TRIGGERED: 'Une de vos planifications vient de démarrer une exécution.',
  SCHEDULE_FAILED: "Une de vos planifications n'a pas pu démarrer d'exécution (ex: limite de capacité atteinte).",
}
const typeIcon: Record<BackendNotificationType, string> = {
  EXECUTION_SUCCESS: 'bi-check-circle-fill',
  EXECUTION_FAILED: 'bi-x-circle-fill',
  EXECUTION_CANCELLED: 'bi-slash-circle-fill',
  SCHEDULE_TRIGGERED: 'bi-calendar-check-fill',
  SCHEDULE_FAILED: 'bi-calendar-x-fill',
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0: return err.message
      case 401: return 'Vous devez être connecté (Keycloak) pour gérer vos préférences.'
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function NotificationsPreferences() {
  const [preferences, setPreferences] = useState<BackendNotificationPreferenceResponse[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyType, setBusyType] = useState<BackendNotificationType | null>(null)

  const load = () => {
    setLoading(true)
    setError(null)
    notificationPreferencesBackendApi.list()
      .then(setPreferences)
      .catch((err) => setError(describeApiError(err, 'Impossible de charger vos préférences de notification.')))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const toggle = async (type: BackendNotificationType, enabled: boolean) => {
    setBusyType(type)
    setError(null)
    try {
      const updated = await notificationPreferencesBackendApi.setEnabled(type, enabled)
      setPreferences((prev) => prev?.map((p) => (p.type === type ? updated : p)) ?? prev)
    } catch (err) {
      setError(describeApiError(err, 'Impossible de mettre à jour cette préférence.'))
    } finally {
      setBusyType(null)
    }
  }

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Notifications — Préférences</h1>
          <p>Choisissez les événements réels pour lesquels vous recevez une notification in-app</p>
        </div>
        <TopBar searchPlaceholder="" />
      </div>

      <div className="pt-card mb-3" style={{ padding: '0.85rem 1.1rem', fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-info-circle me-2 text-primary"></i>
        Seul le canal <strong>in-app</strong> est actuellement implémenté. Email, Slack, Teams, Webhooks et SMS ne sont pas
        encore intégrés à LoadPilot — ils n'apparaissent donc pas ici.
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
            <p>Chargement de vos préférences...</p>
          </div>
        ) : !preferences ? null : (
          <div>
            {preferences.map((pref) => (
              <div key={pref.type} className="d-flex align-items-center gap-3 p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                <i className={`bi ${typeIcon[pref.type]}`} style={{ fontSize: '18px', color: 'var(--pt-primary)' }}></i>
                <div className="flex-grow-1">
                  <div style={{ fontSize: '13.5px', fontWeight: 600 }}>{typeLabel[pref.type]}</div>
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>{typeDescription[pref.type]}</div>
                </div>
                <label className="pt-toggle">
                  <input
                    type="checkbox"
                    checked={pref.enabled}
                    disabled={busyType === pref.type}
                    onChange={(e) => toggle(pref.type, e.target.checked)}
                  />
                  <span className="toggle-slider"></span>
                </label>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

export default NotificationsPreferences
