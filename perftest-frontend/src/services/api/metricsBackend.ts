// Métriques (lecture seule).

import { springHttp } from './httpClient'
import { BackendMetricResponse } from '../../types/backendContracts'

const RESOURCE = '/api/metrics'

export interface MetricFilters {
  applicationId?: string
  scenarioId?: string
  stepId?: string
  executionId?: string
}

function buildQuery(filters: MetricFilters): string {
  const params = new URLSearchParams()
  if (filters.applicationId) params.set('applicationId', filters.applicationId)
  if (filters.scenarioId) params.set('scenarioId', filters.scenarioId)
  if (filters.stepId) params.set('stepId', filters.stepId)
  if (filters.executionId) params.set('executionId', filters.executionId)
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

export const metricsBackendApi = {
  getAll: () => springHttp.get<BackendMetricResponse[]>(RESOURCE),
  /** Filtres combinables, tous optionnels ; un id inexistant renvoie 404. */
  search: (filters: MetricFilters) => springHttp.get<BackendMetricResponse[]>(`${RESOURCE}${buildQuery(filters)}`),
  getById: (id: string) => springHttp.get<BackendMetricResponse>(`${RESOURCE}/${id}`),
}
