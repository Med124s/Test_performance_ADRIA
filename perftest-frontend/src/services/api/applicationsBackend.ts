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
  BackendApplicationRequest,
  BackendApplicationResponse,
  BackendApplicationTestResponse,
} from '../../types/backendContracts'

const RESOURCE = '/api/applications'

export const applicationsBackendApi = {
  getAll: () => springHttp.get<BackendApplicationResponse[]>(RESOURCE),
  getById: (id: string) => springHttp.get<BackendApplicationResponse>(`${RESOURCE}/${id}`),
  /** Le backend genere id/status/createdBy/createdAt/updatedAt - jamais envoyes ici. */
  create: (data: BackendApplicationRequest) => springHttp.post<BackendApplicationResponse>(RESOURCE, data),
  update: (id: string, data: BackendApplicationRequest) =>
    springHttp.put<BackendApplicationResponse>(`${RESOURCE}/${id}`, data),
  remove: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
  /** Lance un vrai test HTTP cote serveur (voir ApplicationServiceImpl) -
   * jamais un fetch() direct depuis le frontend pour cette action. */
  test: (id: string) => springHttp.post<BackendApplicationTestResponse>(`${RESOURCE}/${id}/test`),
}
