// Appels API des étapes (/api/steps).

import { springHttp } from './httpClient'
import { BackendStepRequest, BackendStepResponse, BackendTestRunResponse } from '../../types/backendContracts'

const RESOURCE = '/api/steps'

export const stepsBackendApi = {
  getAll: () => springHttp.get<BackendStepResponse[]>(RESOURCE),
  getByScenario: (scenarioId: string) =>
    springHttp.get<BackendStepResponse[]>(`${RESOURCE}?scenarioId=${scenarioId}`),
  getById: (id: string) => springHttp.get<BackendStepResponse>(`${RESOURCE}/${id}`),
  create: (data: BackendStepRequest) => springHttp.post<BackendStepResponse>(RESOURCE, data),
  update: (id: string, data: BackendStepRequest) =>
    springHttp.put<BackendStepResponse>(`${RESOURCE}/${id}`, data),
  remove: (id: string) => springHttp.delete<void>(`${RESOURCE}/${id}`),
  /** Test détaillé d'une seule étape, avec les variables déjà connues (ex. transferId). */
  testRun: (id: string, variables: Record<string, string> = {}) =>
    springHttp.post<BackendTestRunResponse>(`${RESOURCE}/${id}/test-run`, { variables }),
}
