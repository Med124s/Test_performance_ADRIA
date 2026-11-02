// Appels API des applications testées (/api/applications).

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
  BackendNetworkRequest,
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
  create: (data: BackendApplicationRequest) => springHttp.post<BackendApplicationResponse>(RESOURCE, data),
  update: (id: string, data: BackendApplicationRequest) =>
    springHttp.put<BackendApplicationResponse>(`${RESOURCE}/${id}`, data),
  /** Suppression définitive en cascade (Super administrateur). */
  remove: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
  deletionImpact: (id: string) => springHttp.get<BackendDeletionImpact>(`${RESOURCE}/${id}/deletion-impact`),
  bulkDeletionImpact: (ids: string[]) => springHttp.post<BackendDeletionImpact>(`${RESOURCE}/bulk-deletion-impact`, { ids }),
  archive: (id: string) => springHttp.post<BackendApplicationResponse>(`${RESOURCE}/${id}/archive`),
  restore: (id: string) => springHttp.post<BackendApplicationResponse>(`${RESOURCE}/${id}/restore`),
  bulkArchive: (ids: string[], mode: 'ARCHIVE' | 'RESTORE' = 'ARCHIVE') =>
    springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-archive`, { ids, mode }),
  bulkDelete: (ids: string[], mode: 'ARCHIVE' | 'PERMANENT') =>
    springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-delete`, { ids, mode }),
  /** Test de connectivité de l'application, fait par le serveur. */
  test: (id: string) => springHttp.post<BackendApplicationTestResponse>(`${RESOURCE}/${id}/test`),
  /** « Tester le monitoring » avec la configuration saisie (rien n'est enregistré) ; applicationId : secret enregistré réutilisé. */
  testMonitoring: (monitoring: BackendMonitoringRequest, applicationId?: string, network?: BackendNetworkRequest) =>
    springHttp.post<BackendMonitoringTestResponse>(`${RESOURCE}/monitoring/test`,
      { applicationId: applicationId ?? null, monitoring, network: network ?? null }),
}
