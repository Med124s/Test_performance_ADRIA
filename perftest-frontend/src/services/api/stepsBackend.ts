// ============================================================
// Phase 19 — CRUD réel contre le backend Spring Boot pour le module Steps.
// Distinct de services/api/steps.ts (JSON Server) : depuis P1-G (retrait de
// l'assistant CreateScenario/CreateStep/scenarioSave), services/api/steps.ts
// n'est plus utilisé QUE par les écrans de consultation de l'historique
// legacy (onglet Legacy d'Executions, ExecutionDetail, ExecutionReport,
// useScenarioLauncher) — plus par aucune création, les deux jeux de Steps
// ne partageant toujours pas le même espace d'id.
//
// Passage produit reel (2026-09-30) — StepController expose desormais
// POST /api/steps/test-batch (voir testBatch ci-dessous) : envoie une VRAIE
// requete serveur-a-serveur par etape selectionnee, jamais une Execution.
//
// Utilise le même client HTTP central (springHttp, voir httpClient.ts).
// ============================================================

import { springHttp } from './httpClient'
import {
  BackendStepRequest,
  BackendStepResponse,
  BackendStepTestBatchRequest,
  BackendStepTestResultResponse,
} from '../../types/backendContracts'

const RESOURCE = '/api/steps'

export const stepsBackendApi = {
  getAll: () => springHttp.get<BackendStepResponse[]>(RESOURCE),
  getByScenario: (scenarioId: string) =>
    springHttp.get<BackendStepResponse[]>(`${RESOURCE}?scenarioId=${scenarioId}`),
  getById: (id: string) => springHttp.get<BackendStepResponse>(`${RESOURCE}/${id}`),
  /** Le backend genere id/status/createdAt/updatedAt - jamais envoyes ici. */
  create: (data: BackendStepRequest) => springHttp.post<BackendStepResponse>(RESOURCE, data),
  update: (id: string, data: BackendStepRequest) =>
    springHttp.put<BackendStepResponse>(`${RESOURCE}/${id}`, data),
  remove: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
  /** Reserve a SUPER_ADMIN/PERFORMANCE_ENGINEER cote backend. Toutes les
   * etapes doivent appartenir au meme scenario (409 sinon, verifie cote
   * serveur). */
  testBatch: (data: BackendStepTestBatchRequest) =>
    springHttp.post<BackendStepTestResultResponse[]>(`${RESOURCE}/test-batch`, data),
}
