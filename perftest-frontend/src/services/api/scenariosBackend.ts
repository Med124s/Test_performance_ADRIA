// ============================================================
// Phase 18 — CRUD réel contre le backend Spring Boot pour le module
// Scénarios. Distinct de services/api/scenarios.ts (JSON Server) : depuis
// P1-G (retrait de l'assistant de création), services/api/scenarios.ts
// n'est plus utilisé QUE par les écrans de consultation de l'historique
// legacy (Applications, Dashboard, Metriques, onglet Legacy d'Executions,
// ExecutionDetail, ExecutionReport) — plus par aucune création, les deux
// jeux de Scénarios ne partageant toujours pas le même espace d'id.
//
// Utilise le même client HTTP central (springHttp, voir httpClient.ts) —
// même gestion d'erreurs, même en-tête Authorization: Bearer automatique
// que tout le reste de l'application.
// ============================================================

import { springHttp } from './httpClient'
import {
  BackendBulkActionResponse,
  BackendScenarioFullRequest,
  BackendScenarioFullResponse,
  BackendPagedResponse,
  ScenarioPageQuery,
  BackendDeletionImpact,
  BackendScenarioRequest,
  BackendScenarioResponse,
  BackendTestRunEvent,
  BackendTestRunResponse,
} from '../../types/backendContracts'

const RESOURCE = '/api/scenarios'

export const scenariosBackendApi = {
  /** Scénarios visibles, ou vue "archivés" (archivés ou d'une application archivée). */
  getAll: (archived = false) => springHttp.get<BackendScenarioResponse[]>(archived ? `${RESOURCE}?archived=true` : RESOURCE),
  getByApplication: (applicationId: string) =>
    springHttp.get<BackendScenarioResponse[]>(`${RESOURCE}?applicationId=${applicationId}`),
  getById: (id: string) => springHttp.get<BackendScenarioResponse>(`${RESOURCE}/${id}`),
  /** Le backend genere id/status/createdBy/createdAt/updatedAt - jamais envoyes ici. */
  create: (data: BackendScenarioRequest) => springHttp.post<BackendScenarioResponse>(RESOURCE, data),
  update: (id: string, data: BackendScenarioRequest) =>
    springHttp.put<BackendScenarioResponse>(`${RESOURCE}/${id}`, data),
  /** Renvoie 409 (voir ScenarioServiceImpl) si des Steps Spring Boot sont
   * encore rattaches - jamais contourne ni masque depuis le frontend. */
  remove: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
  deletionImpact: (id: string) => springHttp.get<BackendDeletionImpact>(`${RESOURCE}/${id}/deletion-impact`),
  archive: (id: string) => springHttp.post<BackendScenarioResponse>(`${RESOURCE}/${id}/archive`),
  restore: (id: string) => springHttp.post<BackendScenarioResponse>(`${RESOURCE}/${id}/restore`),
  /** Page filtrée et triée côté serveur (mêmes conventions que les applications). */
  getPage: (query: ScenarioPageQuery) => {
    const params = new URLSearchParams({ page: String(query.page), size: String(query.size) })
    if (query.sort) params.set('sort', query.sort)
    if (query.search?.trim()) params.set('search', query.search.trim())
    if (query.status) params.set('status', query.status)
    if (query.archived) params.set('archived', 'true')
    return springHttp.get<BackendPagedResponse<BackendScenarioResponse>>(`${RESOURCE}?${params.toString()}`)
  },
  /** Statut INACTIVE : non exécutable, planifications suspendues (pausedSchedules). */
  activate: (id: string) => springHttp.post<BackendScenarioResponse>(`${RESOURCE}/${id}/activate`),
  deactivate: (id: string) => springHttp.post<BackendScenarioResponse>(`${RESOURCE}/${id}/deactivate`),
  resumeSchedules: (id: string) => springHttp.post<{ resumed: number; expired: number }>(`${RESOURCE}/${id}/resume-schedules`),
  /** Actions groupées : résultat par scénario (succès partiels possibles). */
  bulkDeletionImpact: (ids: string[]) => springHttp.post<BackendDeletionImpact>(`${RESOURCE}/bulk-deletion-impact`, { ids }),
  bulkArchive: (ids: string[]) => springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-archive`, { ids }),
  bulkRestore: (ids: string[]) => springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-restore`, { ids }),
  /** Suppression définitive — SUPER_ADMIN uniquement. */
  bulkDelete: (ids: string[]) => springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-delete`, { ids }),
  bulkStatus: (ids: string[], status: 'ACTIVE' | 'INACTIVE') =>
    springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-status`, { ids, status }),
  bulkResumeSchedules: (ids: string[]) => springHttp.post<BackendBulkActionResponse>(`${RESOURCE}/bulk-resume-schedules`, { ids }),
  /** Brouillon → actif, après validation complète côté backend (400 avec message précis sinon). */
  publish: (id: string) => springHttp.post<BackendScenarioResponse>(`${RESOURCE}/${id}/publish`),
  /** Suppression d'un brouillon (DRAFT uniquement) — autorisée aux rôles Admin et Performance Engineer. */
  removeDraft: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}/draft`),
  /** Modification complète transactionnelle (informations + étapes) ; 409 si une exécution est en cours. */
  updateFull: (id: string, data: BackendScenarioFullRequest) =>
    springHttp.put<BackendScenarioFullResponse>(`${RESOURCE}/${id}/full`, data),
  /** Test détaillé d'une version NON enregistrée ; upToStep : ne jouer que les étapes 1..n. */
  testRunDraft: (id: string, draft: BackendScenarioFullRequest, upToStep?: number) =>
    springHttp.post<BackendTestRunResponse>(`${RESOURCE}/${id}/test-run${upToStep ? `?upToStep=${upToStep}` : ''}`, draft),
  testRunDraftStream: (id: string, draft: BackendScenarioFullRequest, onEvent: (event: BackendTestRunEvent) => void) =>
    springHttp.postStream<BackendTestRunEvent>(`${RESOURCE}/${id}/test-run?stream=true`, draft, onEvent),
  /** Test détaillé (1 utilisateur, 1 passage) : aucune exécution dans l'historique, aucune métrique. */
  testRun: (id: string) => springHttp.post<BackendTestRunResponse>(`${RESOURCE}/${id}/test-run`),
  /** Même test, progression en direct (étape en cours) via un flux NDJSON. */
  testRunStream: (id: string, onEvent: (event: BackendTestRunEvent) => void) =>
    springHttp.postStream<BackendTestRunEvent>(`${RESOURCE}/${id}/test-run?stream=true`, undefined, onEvent),
}
