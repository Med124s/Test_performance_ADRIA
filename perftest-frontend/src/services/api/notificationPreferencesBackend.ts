// ============================================================
// P1-D — accès réel à GET/PATCH /api/notification-preferences (voir
// NotificationPreferenceController côté backend). Remplace la page
// NotificationsPreferences.tsx précédemment 100% fictive (canaux Slack/
// Teams/SMS/Email non implémentés, règles d'alerte sur seuils CPU/RAM
// inexistants, tout persisté en localStorage) — seul le canal in-app RÉEL
// (voir P1-B) peut être coupé, par type d'événement réel.
// ============================================================

import { springHttp } from './httpClient'
import { BackendNotificationPreferenceResponse, BackendNotificationType } from '../../types/backendContracts'

const RESOURCE = '/api/notification-preferences'

export const notificationPreferencesBackendApi = {
  list: () => springHttp.get<BackendNotificationPreferenceResponse[]>(RESOURCE),
  setEnabled: (type: BackendNotificationType, enabled: boolean) =>
    springHttp.patch<BackendNotificationPreferenceResponse>(`${RESOURCE}/${type}`, { enabled }),
}
