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
  BackendExecutionPageResponse,
  BackendExecutionDeletionImpact,
  BackendBulkActionResponse,
  BackendExecutionRequestDetail,
  BackendExecutionTimeline,
  BackendExecutionRequestRow,
  BackendExecutionExtremes,
  BackendExecutionComparison,
  ExecutionResultsQuery,
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
  if (filters.finished) params.set('finished', 'true')
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

export const executionsBackendApi = {
  getAll: () => springHttp.get<BackendExecutionResponse[]>(RESOURCE),
  getByScenario: (scenarioId: string) =>
    springHttp.get<BackendExecutionResponse[]>(`${RESOURCE}?scenarioId=${scenarioId}`),
  getById: (id: string) => springHttp.get<BackendExecutionDetailResponse>(`${RESOURCE}/${id}`),
  /** Détail sans la liste complète des requêtes (voir getResults, paginé). */
  getSummary: (id: string) => springHttp.get<BackendExecutionDetailResponse>(`${RESOURCE}/${id}?includeResults=false`),
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
  /** Met en pause une Execution RUNNING (plus aucune nouvelle requête) ; 409 sinon. */
  pause: (id: string) => springHttp.post<BackendExecutionResponse>(`${RESOURCE}/${id}/pause`),
  /** Reprend une Execution PAUSED là où elle s'était arrêtée ; 409 sinon. */
  resume: (id: string) => springHttp.post<BackendExecutionResponse>(`${RESOURCE}/${id}/resume`),
  /** Crée une NOUVELLE Execution pour le même scénario (le backend ne modifie
   * jamais l'exécution d'origine) — jamais un clone local côté React. */
  retry: (id: string) => springHttp.post<BackendExecutionResponse>(`${RESOURCE}/${id}/retry`),
  /** P1-A — historique paginé/filtré/trié CÔTÉ BACKEND (voir
   * ExecutionController#history) — jamais un chargement complet suivi d'un
   * filtrage/tri en React. */
  getHistory: (filters: ExecutionHistoryFilters) =>
    springHttp.get<BackendPagedResponse<BackendExecutionHistoryResponse>>(`${RESOURCE}/history${buildHistoryQuery(filters)}`),
  /** Page de la liste Exécutions : filtres serveur + compteurs par statut (jamais auditée). */
  getPage: (filters: ExecutionHistoryFilters) =>
    springHttp.get<BackendExecutionPageResponse>(`${RESOURCE}/page${buildHistoryQuery(filters)}`),
  /** Requêtes d'une exécution, paginées côté serveur (filtres étape, VU, itération, échecs). */
  getResults: (id: string, q: ExecutionResultsQuery) => {
    const params = new URLSearchParams({ page: String(q.page), size: String(q.size) })
    if (q.stepId) params.set('stepId', q.stepId)
    else if (q.stepName) params.set('stepName', q.stepName)
    if (q.virtualUser != null) params.set('virtualUser', String(q.virtualUser))
    if (q.iteration != null) params.set('iteration', String(q.iteration))
    if (q.failedOnly) params.set('failedOnly', 'true')
    if (q.sort && q.sort !== 'TIME') params.set('sort', q.sort)
    return springHttp.get<BackendPagedResponse<BackendExecutionRequestRow>>(`${RESOURCE}/${id}/results?${params}`)
  },
  /** Comparaison de 2 à 4 exécutions terminées (mêmes calculs que leurs rapports). */
  compare: (ids: string[]) => springHttp.get<BackendExecutionComparison>(`${RESOURCE}/compare?ids=${ids.map(encodeURIComponent).join(',')}`),
  /** Les 10 requêtes les plus rapides (MIN) ou les plus lentes (MAX) : d'où viennent les cartes Minimum / Maximum. */
  getExtremes: (id: string, kind: 'MIN' | 'MAX') =>
    springHttp.get<BackendExecutionExtremes>(`${RESOURCE}/${id}/extremes?kind=${kind}`),
  /** Analyse dans le temps (calculée côté serveur, en cache pour une exécution terminée). */
  getTimeline: (id: string) => springHttp.get<BackendExecutionTimeline>(`${RESOURCE}/${id}/timeline`),
  /** Détail complet d'une requête (404 si non conservé). */
  getResultDetail: (id: string, resultId: string) =>
    springHttp.get<BackendExecutionRequestDetail>(`${RESOURCE}/${id}/results/${resultId}/detail`),
  /** Impact d'une suppression (résultats, métriques, exécutions actives ignorées). */
  deletionImpact: (ids: string[]) => springHttp.post<BackendExecutionDeletionImpact>(`${RESOURCE}/deletion-impact`, { ids }),
  /** Suppression définitive d'une exécution terminée ; 409 si active. */
  remove: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
  /** Suppression groupée : résultat par exécution (succès partiels possibles). */
  bulkDelete: (ids: string[]) => springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-delete`, { ids }),
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
