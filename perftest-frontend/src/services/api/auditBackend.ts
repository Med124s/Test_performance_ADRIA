// ============================================================
// Phase 24 — accès en LECTURE SEULE au vrai backend Spring Boot pour le
// module Audit Logs. Isolé (comme les services *Backend.ts des phases
// précédentes) : /audit-logs était jusqu'ici un simple placeholder
// "Bientôt disponible" (ComingSoonPage) — aucun service front-end
// (`auditApi`) n'existait avant cette phase, donc aucun risque de casser
// un autre module en créant celui-ci.
//
// Contrat réel (voir AuditLogController.java, lu avant d'écrire ce
// fichier) : `/api/audit-logs` réservé à SUPER_ADMIN et
// PERFORMANCE_ENGINEER (@PreAuthorize au niveau de la classe, VIEWER
// explicitement exclu — 403) :
//   GET /api/audit-logs        -> PagedResponse<AuditLogResponse> (paginé,
//                                  filtres combinables userId/username/
//                                  action/module/result/from/to, tri fixe
//                                  date DESC côté backend)
//   GET /api/audit-logs/{id}   -> AuditLogResponse
//   GET /api/audit-logs/stats  -> AuditStatsResponse
//   GET /api/audit-logs/export -> CSV (text/csv), mêmes filtres que la liste
// Aucune écriture (les AuditLog ne sont jamais créés depuis ce frontend).
//
// `exportCsv` ne peut pas passer par `springHttp` (qui parse toujours la
// réponse en JSON, voir httpClient.ts) : un petit fetch() dédié est utilisé
// ici, réutilisant le MÊME token Keycloak (getAccessToken) et la même
// gestion d'erreur (ApiError) — jamais un second système d'authentification.
// ============================================================

import { springHttp, ApiError, SPRING_API_URL } from './httpClient'
import { getAccessToken } from '../auth/keycloakClient'
import {
  BackendAuditLogResponse,
  BackendAuditStatsResponse,
  BackendPagedResponse,
  BackendAuditAction,
  BackendAuditModule,
  BackendAuditResult,
} from '../../types/backendContracts'

const RESOURCE = '/api/audit-logs'

export interface AuditLogFilters {
  userId?: string
  username?: string
  action?: BackendAuditAction
  module?: BackendAuditModule
  result?: BackendAuditResult
  from?: string
  to?: string
}

function buildQuery(filters: AuditLogFilters, page?: number, size?: number): string {
  const params = new URLSearchParams()
  if (filters.userId) params.set('userId', filters.userId)
  if (filters.username) params.set('username', filters.username)
  if (filters.action) params.set('action', filters.action)
  if (filters.module) params.set('module', filters.module)
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
  stats: () => springHttp.get<BackendAuditStatsResponse>(`${RESOURCE}/stats`),
  /** Retourne le CSV brut réel généré par le backend (RFC 4180) — jamais
   * reconstruit côté frontend à partir des lignes déjà chargées, pour ne
   * jamais diverger d'un export qui pourrait porter sur d'autres filtres/
   * une pagination différente. */
  exportCsv: async (filters: AuditLogFilters): Promise<Blob> => {
    const token = await getAccessToken()
    const headers: Record<string, string> = {}
    if (token) headers['Authorization'] = `Bearer ${token}`
    let response: Response
    try {
      response = await fetch(`${SPRING_API_URL}${RESOURCE}/export${buildQuery(filters)}`, { headers })
    } catch {
      throw new ApiError('Impossible de joindre le serveur LoadPilot (Spring Boot) pour l\'export.', 0)
    }
    if (!response.ok) {
      let message = `Erreur ${response.status} sur ${RESOURCE}/export`
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
