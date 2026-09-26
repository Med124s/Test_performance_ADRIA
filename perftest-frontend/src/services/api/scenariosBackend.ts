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
import { BackendScenarioRequest, BackendScenarioResponse } from '../../types/backendContracts'

const RESOURCE = '/api/scenarios'

export const scenariosBackendApi = {
  getAll: () => springHttp.get<BackendScenarioResponse[]>(RESOURCE),
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
}
