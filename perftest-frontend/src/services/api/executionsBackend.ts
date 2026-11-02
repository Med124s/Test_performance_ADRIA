// Appels API des exécutions (/api/executions). Le lancement est asynchrone : POST répond 202 et l'avancement se suit avec getStatus.

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
  BackendResultsRetention,
  BackendExecutionComparison,
  ExecutionResultsQuery,
  ExecutionHistoryFilters,
} from '../../types/backendContracts'

const RESOURCE = '/api/executions'

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
  /** Lance une exécution : réponse 202 avec l'exécution en attente (QUEUED). */
  execute: (data: BackendExecutionRequest) => springHttp.post<BackendExecutionResponse>(RESOURCE, data),
  /** État d'une exécution pour le suivi en direct. */
  getStatus: (id: string) => springHttp.get<BackendExecutionStatusResponse>(`${RESOURCE}/${id}/status`),
  /** Annule une exécution en attente, en cours ou en pause ; 409 si déjà terminée. */
  cancel: (id: string) => springHttp.post<BackendExecutionResponse>(`${RESOURCE}/${id}/cancel`),
  /** Met en pause une Execution RUNNING (plus aucune nouvelle requête) ; 409 sinon. */
  pause: (id: string) => springHttp.post<BackendExecutionResponse>(`${RESOURCE}/${id}/pause`),
  /** Reprend une Execution PAUSED là où elle s'était arrêtée ; 409 sinon. */
  resume: (id: string) => springHttp.post<BackendExecutionResponse>(`${RESOURCE}/${id}/resume`),
  /** Crée une nouvelle exécution du même scénario. */
  retry: (id: string) => springHttp.post<BackendExecutionResponse>(`${RESOURCE}/${id}/retry`),
  /** Historique paginé, filtré et trié côté serveur. */
  getHistory: (filters: ExecutionHistoryFilters) =>
    springHttp.get<BackendPagedResponse<BackendExecutionHistoryResponse>>(`${RESOURCE}/history${buildHistoryQuery(filters)}`),
  /** Page de la liste Exécutions : filtres et compteurs par statut. */
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
  /** Option « Conserver » : true = jamais purgée ; false = suit la durée de conservation. */
  setKeep: (id: string, keep: boolean) => springHttp.patch<BackendResultsRetention>(`${RESOURCE}/${id}/keep`, { keep }),
  /** Suppression groupée : résultat par exécution (succès partiels possibles). */
  bulkDelete: (ids: string[]) => springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-delete`, { ids }),
  /** Rapport statistique d'une exécution. */
  getReport: (id: string) => springHttp.get<BackendExecutionReportResponse>(`${RESOURCE}/${id}/report`),
  /** Excel (.xlsx) : feuilles Résumé, Étapes, Erreurs, Analyse dans le temps (export par défaut). */
  exportReportXlsx: (id: string) => downloadReport(id, 'report/xlsx'),
  /** Rapport PDF d'une exécution terminée, généré par le serveur (journalisé). */
  exportReportPdf: (id: string) => downloadReport(id, 'report/pdf'),
}

/** Fichier d'export d'un rapport (nom donné par le serveur) ; message du serveur en cas de refus. */
async function downloadReport(id: string, path: string): Promise<{ blob: Blob; fileName: string }> {
  const token = await getAccessToken()
  const headers: Record<string, string> = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  let response: Response
  try {
    response = await fetch(`${SPRING_API_URL}${RESOURCE}/${id}/${path}`, { headers })
  } catch {
    throw new ApiError('Impossible de joindre le serveur LoadPilot (Spring Boot) pour l\'export.', 0)
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
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const fileName = /filename="([^"]+)"/.exec(disposition)?.[1] ?? `rapport-${id}`
  return { blob: await response.blob(), fileName }
}
