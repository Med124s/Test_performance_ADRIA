// ============================================================
// Journal d'audit (réservé au Super administrateur, voir AuditLogController.java) :
//   GET /api/audit-logs          -> PagedResponse<AuditLogResponse> (filtres côté serveur :
//                                    q, actor, category, result, from, to ; tri date DESC)
//   GET /api/audit-logs/{id}     -> AuditLogResponse
//   GET /api/audit-logs/actors   -> utilisateurs présents dans le journal
//   GET/PUT /api/settings/audit  -> réglages (consultations journalisées, conservation)
// Aucune écriture d'entrée depuis le frontend : elles ne sont créées que par le backend.
// ============================================================

import { springHttp } from './httpClient'
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
}
