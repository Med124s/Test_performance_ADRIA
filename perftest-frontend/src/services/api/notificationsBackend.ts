// Notifications de l'utilisateur connecté.

import { springHttp } from './httpClient'
import { BackendNotificationResponse, BackendPagedResponse } from '../../types/backendContracts'

const RESOURCE = '/api/notifications'

export const notificationsBackendApi = {
  /** Les plus récentes en premier, par pages. `read` et `types` optionnels filtrent la liste. */
  list: (params: { read?: boolean; types?: string[]; page?: number; size?: number } = {}) => {
    const qs = new URLSearchParams()
    if (params.read != null) qs.set('read', String(params.read))
    for (const t of params.types ?? []) qs.append('type', t)
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    const query = qs.toString()
    return springHttp.get<BackendPagedResponse<BackendNotificationResponse>>(`${RESOURCE}${query ? `?${query}` : ''}`)
  },
  /** Nombre de notifications non lues. */
  unreadCount: () => springHttp.get<{ count: number }>(`${RESOURCE}/unread-count`),
  markRead: (id: string) => springHttp.patch<BackendNotificationResponse>(`${RESOURCE}/${id}/read`, undefined),
  markAllRead: () => springHttp.patch<{ updated: number }>(`${RESOURCE}/read-all`, undefined),
  delete: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
}
