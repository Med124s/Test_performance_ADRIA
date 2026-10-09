// Préférences de notification de l'utilisateur connecté : dans l'application et par e-mail.
import { springHttp } from './httpClient'
import { BackendNotificationChannels, BackendNotificationPreferenceResponse, BackendNotificationType } from '../../types/backendContracts'

const RESOURCE = '/api/notification-preferences'

export const notificationPreferencesBackendApi = {
  list: () => springHttp.get<BackendNotificationPreferenceResponse[]>(RESOURCE),
  setEnabled: (type: BackendNotificationType, enabled: boolean) =>
    springHttp.patch<BackendNotificationPreferenceResponse>(`${RESOURCE}/${type}`, { enabled }),
  /** Canaux d'un type ; un champ absent reste inchangé. */
  update: (type: BackendNotificationType, channels: { enabled?: boolean; emailEnabled?: boolean }) =>
    springHttp.patch<BackendNotificationPreferenceResponse>(`${RESOURCE}/${type}`, channels),
  channels: () => springHttp.get<BackendNotificationChannels>(`${RESOURCE}/channels`),
}
