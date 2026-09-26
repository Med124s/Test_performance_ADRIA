// ============================================================
// Phase 20 (mise à jour P0-A) — CRUD/actions réels contre le backend Spring
// Boot pour le module Executions UNIQUEMENT. Distinct de
// services/api/executions.ts (JSON Server), toujours utilisé tel quel par le
// workflow legacy (useScenarioLauncher, useScheduledExecutions,
// ExecutionDetail.tsx, ExecutionReport.tsx) — même raison qu'en Phases 17-19 :
// les Executions JSON Server référencent des Scenario/Step JSON Server,
// jamais les UUID Spring Boot.
//
// P0-A — le moteur d'exécution backend (HttpClientExecutionEngine, threads
// virtuels Java 21) est désormais ASYNCHRONE : POST /api/executions répond
// immédiatement (202 Accepted) avec l'Execution QUEUED (ou déjà RUNNING —
// voir ExecutionServiceImpl, la tâche réelle peut démarrer avant même la
// sérialisation de la réponse), jamais le résultat final. Le frontend doit
// suivre la progression via GET /{id} ou GET /{id}/status (polling — voir
// Scenarios.tsx/Executions.tsx/ExecutionDetail.tsx) jusqu'à un statut
// terminal (SUCCESS/FAILED/CANCELLED). `cancel` interrompt réellement une
// Execution QUEUED ou RUNNING (voir ExecutionServiceImpl.cancel) — plus
// seulement un endpoint sans fenêtre d'usage réelle.
//
// Utilise le même client HTTP central (springHttp, voir httpClient.ts).
// ============================================================

import { springHttp, ApiError, SPRING_API_URL } from './httpClient'
import { getAccessToken } from '../auth/keycloakClient'
import {
  BackendExecutionRequest,
  BackendExecutionResponse,
  BackendExecutionDetailResponse,
  BackendExecutionStatusResponse,
  BackendExecutionHistoryResponse,
  BackendExecutionReportResponse,
  BackendPagedResponse,
  ExecutionHistoryFilters,
} from '../../types/backendContracts'

const RESOURCE = '/api/executions'

/** P1-A — meme convention que auditBackend.ts (buildQuery). */
function buildHistoryQuery(filters: ExecutionHistoryFilters): string {
  const params = new URLSearchParams()
  if (filters.status) params.set('status', filters.status)
  if (filters.scenarioId) params.set('scenarioId', filters.scenarioId)
  if (filters.applicationId) params.set('applicationId', filters.applicationId)
  if (filters.dateFrom) params.set('dateFrom', filters.dateFrom)
  if (filters.dateTo) params.set('dateTo', filters.dateTo)
  if (filters.search) params.set('search', filters.search)
  if (filters.page != null) params.set('page', String(filters.page))
  if (filters.size != null) params.set('size', String(filters.size))
  if (filters.sort) params.set('sort', filters.sort)
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

export const executionsBackendApi = {
  getAll: () => springHttp.get<BackendExecutionResponse[]>(RESOURCE),
  getByScenario: (scenarioId: string) =>
    springHttp.get<BackendExecutionResponse[]>(`${RESOURCE}?scenarioId=${scenarioId}`),
  getById: (id: string) => springHttp.get<BackendExecutionDetailResponse>(`${RESOURCE}/${id}`),
  /** P0-A — asynchrone : répond 202 avec l'Execution QUEUED (ou déjà RUNNING),
   * jamais le résultat final. Poller GET /{id} ou getStatus(id) pour suivre
   * la progression réelle jusqu'à un statut terminal. */
  execute: (data: BackendExecutionRequest) => springHttp.post<BackendExecutionResponse>(RESOURCE, data),
  /** P0-A — réponse légère dédiée au polling fréquent (voir
   * ExecutionController#getStatus / BackendExecutionStatusResponse). */
  getStatus: (id: string) => springHttp.get<BackendExecutionStatusResponse>(`${RESOURCE}/${id}/status`),
  /** P0-A — interrompt réellement une Execution QUEUED ou RUNNING (annulation
   * réelle des utilisateurs virtuels en cours, voir rapport P0-A) ; 409 si déjà
   * terminée. */
  cancel: (id: string) => springHttp.post<BackendExecutionResponse>(`${RESOURCE}/${id}/cancel`),
  /** Crée une NOUVELLE Execution pour le même scénario (le backend ne modifie
   * jamais l'exécution d'origine) — jamais un clone local côté React. */
  retry: (id: string) => springHttp.post<BackendExecutionResponse>(`${RESOURCE}/${id}/retry`),
  /** P1-A — historique paginé/filtré/trié CÔTÉ BACKEND (voir
   * ExecutionController#history) — jamais un chargement complet suivi d'un
   * filtrage/tri en React. */
  getHistory: (filters: ExecutionHistoryFilters) =>
    springHttp.get<BackendPagedResponse<BackendExecutionHistoryResponse>>(`${RESOURCE}/history${buildHistoryQuery(filters)}`),
  /** P1-A — rapport statistique complet (percentiles réels, agrégation par
   * step, erreurs) — voir GET /api/executions/{id}/report. */
  getReport: (id: string) => springHttp.get<BackendExecutionReportResponse>(`${RESOURCE}/${id}/report`),
  /** P1-A — même politique que auditBackendApi.exportCsv : ne peut pas
   * passer par springHttp (parse toujours en JSON) — fetch dédié réutilisant
   * le même token Keycloak. Retourne le CSV RÉEL généré par le backend,
   * jamais reconstruit depuis des données déjà affichées côté React. */
  exportReportCsv: async (id: string): Promise<Blob> => {
    const token = await getAccessToken()
    const headers: Record<string, string> = {}
    if (token) headers['Authorization'] = `Bearer ${token}`
    let response: Response
    try {
      response = await fetch(`${SPRING_API_URL}${RESOURCE}/${id}/report/export`, { headers })
    } catch {
      throw new ApiError('Impossible de joindre le serveur LoadPilot (Spring Boot) pour l\'export.', 0)
    }
    if (!response.ok) {
      let message = `Erreur ${response.status} sur ${RESOURCE}/${id}/report/export`
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
