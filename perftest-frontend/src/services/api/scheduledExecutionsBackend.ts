// ============================================================
// P1-B — CRUD/actions réels des ScheduledExecution (voir
// controller.ScheduledExecutionController côté backend) : planification
// PERSISTÉE en base, survit à un redémarrage, déclenchée par le même
// moteur exactement que le lancement manuel (voir ExecutionService côté
// backend). Remplace l'ancien hooks/useScheduledExecutions.ts (100%
// frontend, JSON Server, ne survivant pas à la fermeture de l'onglet) —
// voir MainLayout.tsx pour le décommissionnement de l'ancien hook.
// ============================================================

import { springHttp } from './httpClient'
import { BackendScheduledExecutionRequest, BackendScheduledExecutionResponse } from '../../types/backendContracts'

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
  /** Déclenche immédiatement (même moteur/protections que le déclenchement automatique). */
  runNow: (id: string) => springHttp.post<BackendScheduledExecutionResponse>(`${RESOURCE}/${id}/run-now`, undefined),
  delete: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
}
