// ============================================================
// Journal d'audit (réservé au Super administrateur, voir AuditLogController.java) :
//   GET /api/audit-logs          -> PagedResponse<AuditLogResponse> (filtres côté serveur :
//                                    q, actor, category, result, from, to ; tri date DESC)
//   GET /api/audit-logs/{id}     -> AuditLogResponse
//   GET /api/audit-logs/actors   -> utilisateurs présents dans le journal
//   GET /api/audit-logs/export   -> CSV en français, mêmes filtres que la liste
//   GET/PUT /api/settings/audit  -> réglages (consultations journalisées, conservation)
// Aucune écriture d'entrée depuis le frontend : elles ne sont créées que par le backend.
//
// `exportCsv` ne peut pas passer par `springHttp` (qui parse toujours la réponse en JSON) :
// un fetch() dédié réutilise le même jeton (getAccessToken) et la même gestion d'erreur.
// ============================================================

import { springHttp, ApiError, SPRING_API_URL } from './httpClient'
import { getAccessToken } from '../auth/keycloakClient'
import {
  BackendAuditLogResponse,
  BackendAuditCategory,
  BackendAuditResult,
  BackendAuditSettings,
  BackendPagedResponse,
} from '../../types/backendContracts'

const RESOURCE = '/api/audit-logs'

export interface AuditLogFilters {
  /** Recherche libre : utilisateur, objet, description. */
  q?: string
  /** Nom d'utilisateur, ou "__system" pour les actions automatiques. */
  actor?: string
  category?: BackendAuditCategory
  result?: BackendAuditResult
  /** Instants ISO. */
  from?: string
  to?: string
}

export const SYSTEM_ACTOR = '__system'

function buildQuery(filters: AuditLogFilters, page?: number, size?: number): string {
  const params = new URLSearchParams()
  if (filters.q?.trim()) params.set('q', filters.q.trim())
  if (filters.actor) params.set('actor', filters.actor)
  if (filters.category) params.set('category', filters.category)
  if (filters.result) params.set('result', filters.result)
  if (filters.from) params.set('from', filters.from)
  if (filters.to) params.set('to', filters.to)
  if (page != null) params.set('page', String(page))
  if (size != null) params.set('size', String(size))
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

export const auditBackendApi = {
  list: (filters: AuditLogFilters, page: number, size: number) =>
    springHttp.get<BackendPagedResponse<BackendAuditLogResponse>>(`${RESOURCE}${buildQuery(filters, page, size)}`),
  getById: (id: string) => springHttp.get<BackendAuditLogResponse>(`${RESOURCE}/${id}`),
  actors: () => springHttp.get<string[]>(`${RESOURCE}/actors`),
  settings: () => springHttp.get<BackendAuditSettings>('/api/settings/audit'),
  updateSettings: (settings: BackendAuditSettings) => springHttp.put<BackendAuditSettings>('/api/settings/audit', settings),
  /** CSV réel généré par le backend avec les filtres en cours (jamais reconstruit côté frontend). */
  exportCsv: async (filters: AuditLogFilters): Promise<Blob> => {
    const token = await getAccessToken()
    const headers: Record<string, string> = {}
    if (token) headers['Authorization'] = `Bearer ${token}`
    let response: Response
    try {
      response = await fetch(`${SPRING_API_URL}${RESOURCE}/export${buildQuery(filters)}`, { headers })
    } catch {
      throw new ApiError('Impossible de joindre le serveur LoadPilot pour l’export.', 0)
    }
    if (!response.ok) {
      let message = `Erreur ${response.status} lors de l’export`
      try {
        const body = await response.json()
        if (body?.message) message = body.message
      } catch {
        // pas de corps JSON exploitable
      }
      throw new ApiError(message, response.status)
    }
    return response.blob()
  },
}
