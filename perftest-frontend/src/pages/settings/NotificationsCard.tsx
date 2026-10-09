import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { notificationPreferencesBackendApi } from '../../services/api/notificationPreferencesBackend'
import type { BackendNotificationChannels, BackendNotificationPreferenceResponse, BackendNotificationType } from '../../types/backendContracts'
import { useRegressionThreshold } from '../../utils/thresholds'
import { describeSettingsError } from './settingsErrors'

// Notifications (Paramètres > Mon profil) : quatre familles d'événements, chacune dans
// l'application et, si un serveur e-mail est configuré, par e-mail. Chaque interrupteur
// enregistre aussitôt (PATCH par type) et est réellement respecté par le serveur.

export interface NotificationGroup {
  id: string
  label: string
  icon: string
  types: BackendNotificationType[]
  description: (regressionPercent: number) => string
}

export const NOTIFICATION_GROUPS: NotificationGroup[] = [
  { id: 'end', label: 'Fin d’exécution', icon: 'bi-flag', types: ['EXECUTION_SUCCESS', 'EXECUTION_THRESHOLDS_EXCEEDED', 'EXECUTION_CANCELLED'],
    description: () => 'Exécution que vous avez lancée : réussie, seuils dépassés ou annulée.' },
  { id: 'failure', label: 'Échec', icon: 'bi-x-octagon', types: ['EXECUTION_FAILED'],
    description: () => 'Exécution qui n’a pas pu se dérouler (application injoignable, authentification refusée…).' },
  { id: 'regression', label: 'Régression', icon: 'bi-graph-up-arrow', types: ['SCHEDULE_REGRESSION'],
    description: (r) => `P95 d’une exécution planifiée en hausse de plus de ${r.toLocaleString('fr-FR')} % par rapport à la précédente.` },
  { id: 'schedule', label: 'Planification', icon: 'bi-calendar-event', types: ['SCHEDULE_FAILED'],
    description: () => 'Lancement prévu qui n’a pas eu lieu (ignoré, manqué ou refusé).' },
]

type Channel = 'enabled' | 'emailEnabled'

export default function NotificationsCard() {
  const regression = useRegressionThreshold()
  const [prefs, setPrefs] = useState<BackendNotificationPreferenceResponse[] | null>(null)
  const [channels, setChannels] = useState<BackendNotificationChannels | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    notificationPreferencesBackendApi.list().then(setPrefs).catch((err) => setError(describeSettingsError(err, 'Impossible de charger vos préférences.')))
    notificationPreferencesBackendApi.channels().then(setChannels).catch(() => setChannels(null))
  }, [])

  const state = (group: NotificationGroup, channel: Channel): 'on' | 'off' | 'mixed' => {
    const values = group.types.map((t) => !!prefs?.find((p) => p.type === t)?.[channel])
    return values.every(Boolean) ? 'on' : values.some(Boolean) ? 'mixed' : 'off'
  }

  const toggle = async (group: NotificationGroup, channel: Channel, value: boolean) => {
    setBusy(`${group.id}-${channel}`)
    setError(null)
    try {
      const updated = await Promise.all(group.types.map((t) => notificationPreferencesBackendApi.update(t, { [channel]: value })))
      setPrefs((prev) => prev?.map((p) => updated.find((u) => u.type === p.type) ?? p) ?? prev)
    } catch (err) {
      setError(describeSettingsError(err, 'Impossible d’enregistrer cette préférence.'))
    } finally {
      setBusy(null)
    }
  }

  const emailAvailable = !!channels?.email
  const emailReason = !channels ? null : !channels.emailServer ? 'Aucun serveur e-mail n’est configuré sur LoadPilot.'
    : !channels.email ? 'Votre compte n’a pas d’adresse e-mail.' : null

  return (
    <section className="pt-card h-100 st-card" aria-labelledby="st-notif-title">
      <h2 className="st-card-title" id="st-notif-title"><i className="bi bi-bell" aria-hidden="true"></i>Notifications</h2>
      {error && <div className="pt-alert-banner danger mb-2" role="alert"><i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}</div>}
      {!prefs ? (
        !error && <p className="st-note">Chargement de vos préférences…</p>
      ) : (
        <table className="st-notif-table">
          <thead>
            <tr>
              <th scope="col"><span className="visually-hidden">Événement</span></th>
              <th scope="col">Application</th>
              <th scope="col">E-mail</th>
            </tr>
          </thead>
          <tbody>
            {NOTIFICATION_GROUPS.map((g) => (
              <tr key={g.id}>
                <th scope="row">
                  <span className="st-notif-label"><i className={`bi ${g.icon}`} aria-hidden="true"></i>{g.label}</span>
                  <span className="st-notif-desc">{g.description(regression)}</span>
                </th>
                {(['enabled', 'emailEnabled'] as Channel[]).map((channel) => {
                  const s = state(g, channel)
                  const disabled = busy !== null || (channel === 'emailEnabled' && !emailAvailable && s === 'off')
                  return (
                    <td key={channel}>
                      <label className="pt-toggle st-toggle" title={channel === 'emailEnabled' && !emailAvailable ? emailReason ?? undefined : undefined}>
                        <input type="checkbox" checked={s === 'on'} disabled={disabled}
                          ref={(el) => { if (el) el.indeterminate = s === 'mixed' }}
                          aria-label={`${g.label} : ${channel === 'enabled' ? 'dans l’application' : 'par e-mail'}`}
                          onChange={(e) => toggle(g, channel, e.target.checked)} />
                        <span className="toggle-slider"></span>
                      </label>
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="st-note">
        {emailAvailable ? <>E-mails envoyés à <strong>{channels?.emailAddress}</strong>.</> : emailReason ?? ''}
      </p>
      <Link to="/notifications" className="st-link">Voir toutes mes notifications <i className="bi bi-chevron-right" aria-hidden="true"></i></Link>
    </section>
  )
}
