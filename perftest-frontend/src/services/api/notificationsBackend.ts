// ============================================================
// P1-B — CRUD réel des Notifications personnelles (voir
// controller.NotificationController côté backend). AUCUN mock, AUCUN
// localStorage : la seule source de vérité est /api/notifications, résolu
// depuis le Jwt Keycloak de la requête (jamais un userId fourni côté
// client). Remplace entièrement l'ancien utils/notificationsStore.ts
// (localStorage + data/notifications.ts fictif — voir Sidebar.tsx).
// ============================================================

import { springHttp } from './httpClient'
import { BackendNotificationResponse, BackendPagedResponse } from '../../types/backendContracts'

const RESOURCE = '/api/notifications'

export const notificationsBackendApi = {
  /** Les plus récentes en premier. `read` optionnel filtre par statut de lecture. */
  list: (params: { read?: boolean; page?: number; size?: number } = {}) => {
    const qs = new URLSearchParams()
    if (params.read != null) qs.set('read', String(params.read))
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    const query = qs.toString()
    return springHttp.get<BackendPagedResponse<BackendNotificationResponse>>(`${RESOURCE}${query ? `?${query}` : ''}`)
  },
  /** Pensé pour le badge de la barre latérale (polling léger, voir Sidebar.tsx). */
  unreadCount: () => springHttp.get<{ count: number }>(`${RESOURCE}/unread-count`),
  markRead: (id: string) => springHttp.patch<BackendNotificationResponse>(`${RESOURCE}/${id}/read`, undefined),
  markAllRead: () => springHttp.patch<{ updated: number }>(`${RESOURCE}/read-all`, undefined),
  delete: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
}
