// ============================================================
// Phase 17 — CRUD réel contre le backend Spring Boot pour le module
// Applications UNIQUEMENT. Distinct de services/api/applications.ts (JSON
// Server), toujours utilisé par tous les autres modules non migrés
// (Scenarios/Steps/Executions/Metrics/Dashboard...) — voir Phase 17,
// rapport section "Analyse initiale" pour la raison de cette séparation
// (les deux jeux d'Applications ne partagent pas le même espace d'id).
//
// Utilise le même client HTTP central (springHttp, voir httpClient.ts) —
// même gestion d'erreurs, même en-tête Authorization: Bearer automatique
// que tout le reste de l'application.
// ============================================================

import { springHttp } from './httpClient'
import {
  ApplicationPageQuery,
  BackendApplicationRequest,
  BackendApplicationResponse,
  BackendApplicationTestResponse,
  BackendBulkActionResponse,
  BackendDeletionImpact,
  BackendMonitoringRequest,
  BackendMonitoringTestResponse,
  BackendPagedResponse,
} from '../../types/backendContracts'

const RESOURCE = '/api/applications'

export const applicationsBackendApi = {
  /** Liste complète (applications non archivées, ou archivées si demandé). */
  getAll: (archived = false) => springHttp.get<BackendApplicationResponse[]>(archived ? `${RESOURCE}?archived=true` : RESOURCE),
  /** Liste paginée, filtrée et triée côté serveur. */
  getPage: (query: ApplicationPageQuery) => {
    const params = new URLSearchParams({ page: String(query.page), size: String(query.size) })
    if (query.sort) params.set('sort', query.sort)
    if (query.search?.trim()) params.set('search', query.search.trim())
    if (query.status) params.set('status', query.status)
    if (query.archived) params.set('archived', 'true')
    return springHttp.get<BackendPagedResponse<BackendApplicationResponse>>(`${RESOURCE}?${params.toString()}`)
  },
  getById: (id: string) => springHttp.get<BackendApplicationResponse>(`${RESOURCE}/${id}`),
  /** Le backend genere id/status/createdBy/createdAt/updatedAt - jamais envoyes ici. */
  create: (data: BackendApplicationRequest) => springHttp.post<BackendApplicationResponse>(RESOURCE, data),
  update: (id: string, data: BackendApplicationRequest) =>
    springHttp.put<BackendApplicationResponse>(`${RESOURCE}/${id}`, data),
  /** Suppression DÉFINITIVE en cascade (SUPER_ADMIN). */
  remove: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
  deletionImpact: (id: string) => springHttp.get<BackendDeletionImpact>(`${RESOURCE}/${id}/deletion-impact`),
  bulkDeletionImpact: (ids: string[]) => springHttp.post<BackendDeletionImpact>(`${RESOURCE}/bulk-deletion-impact`, { ids }),
  archive: (id: string) => springHttp.post<BackendApplicationResponse>(`${RESOURCE}/${id}/archive`),
  restore: (id: string) => springHttp.post<BackendApplicationResponse>(`${RESOURCE}/${id}/restore`),
  bulkArchive: (ids: string[], mode: 'ARCHIVE' | 'RESTORE' = 'ARCHIVE') =>
    springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-archive`, { ids, mode }),
  bulkDelete: (ids: string[], mode: 'ARCHIVE' | 'PERMANENT') =>
    springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-delete`, { ids, mode }),
  /** Lance un vrai test HTTP cote serveur (voir ApplicationServiceImpl) -
   * jamais un fetch() direct depuis le frontend pour cette action. */
  test: (id: string) => springHttp.post<BackendApplicationTestResponse>(`${RESOURCE}/${id}/test`),
  /** « Tester le monitoring » avec la configuration saisie (rien n'est enregistré) ; applicationId : secret enregistré réutilisé. */
  testMonitoring: (monitoring: BackendMonitoringRequest, applicationId?: string) =>
    springHttp.post<BackendMonitoringTestResponse>(`${RESOURCE}/monitoring/test`, { applicationId: applicationId ?? null, monitoring }),
}
