// Appels API des planifications (/api/scheduled-executions).

import { springHttp } from './httpClient'
import {
  BackendScheduleHistoryResponse, BackendSchedulePreviewRequest, BackendSchedulePreviewResponse,
  BackendScheduledExecutionRequest, BackendScheduledExecutionResponse,
} from '../../types/backendContracts'

const RESOURCE = '/api/scheduled-executions'

export const scheduledExecutionsBackendApi = {
  list: (scenarioId?: string) =>
    springHttp.get<BackendScheduledExecutionResponse[]>(`${RESOURCE}${scenarioId ? `?scenarioId=${scenarioId}` : ''}`),
  getById: (id: string) => springHttp.get<BackendScheduledExecutionResponse>(`${RESOURCE}/${id}`),
  create: (data: BackendScheduledExecutionRequest) =>
    springHttp.post<BackendScheduledExecutionResponse>(RESOURCE, data),
  update: (id: string, data: BackendScheduledExecutionRequest) =>
    springHttp.put<BackendScheduledExecutionResponse>(`${RESOURCE}/${id}`, data),
  enable: (id: string) => springHttp.patch<BackendScheduledExecutionResponse>(`${RESOURCE}/${id}/enable`, undefined),
  disable: (id: string) => springHttp.patch<BackendScheduledExecutionResponse>(`${RESOURCE}/${id}/disable`, undefined),
  /** Déclenche immédiatement la planification. */
  runNow: (id: string) => springHttp.post<BackendScheduledExecutionResponse>(`${RESOURCE}/${id}/run-now`, undefined),
  delete: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
  /** 3 prochains lancements calculés comme le déclencheur ; 400 avec un message clair si la saisie est invalide. */
  preview: (data: BackendSchedulePreviewRequest) =>
    springHttp.post<BackendSchedulePreviewResponse>(`${RESOURCE}/preview`, data),
  /** Exécutions lancées et lancements ignorés / manqués, du plus récent au plus ancien. */
  history: (id: string, limit = 100) =>
    springHttp.get<BackendScheduleHistoryResponse>(`${RESOURCE}/${id}/history?limit=${limit}`),
}
