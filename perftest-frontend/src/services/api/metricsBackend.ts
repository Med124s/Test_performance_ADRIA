// ============================================================
// Phase 21 — accès en LECTURE SEULE au vrai backend Spring Boot pour le
// module Metrics. Il n'existait AUCUN service `metricsApi`/`metrics.ts`
// avant cette phase (vérifié par recherche globale) : la page Metriques.tsx
// calculait jusqu'ici tout côté client à partir des Executions JSON Server
// (stepResults). Ce fichier est donc entièrement nouveau, isolé (comme
// applicationsBackend.ts/scenariosBackend.ts/stepsBackend.ts/
// executionsBackend.ts des phases précédentes), utilisé UNIQUEMENT par
// Metriques.tsx migré.
//
// Contrat réel (voir MetricController.java, lu avant d'écrire ce fichier) :
// UN SEUL endpoint `GET /api/metrics`, avec 4 filtres optionnels combinables
// en ET (applicationId/scenarioId/stepId/executionId) — aucune route
// dédiée `/api/metrics/applications/{id}` etc. n'existe. Aucune création/
// modification/suppression n'est exposée : les Metric sont générées
// automatiquement par le backend après chaque Execution
// (MetricGenerationService), jamais créées depuis le frontend.
//
// Utilise le même client HTTP central (springHttp, voir httpClient.ts).
// ============================================================

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
  /** Aucun filtre = toutes les Metric (voir MetricController.list). */
  getAll: () => springHttp.get<BackendMetricResponse[]>(RESOURCE),
  /** Filtres combinables en ET, tous optionnels — reflète exactement le
   * seul endpoint réel (un id de filtre inexistant renvoie 404). */
  search: (filters: MetricFilters) => springHttp.get<BackendMetricResponse[]>(`${RESOURCE}${buildQuery(filters)}`),
  getById: (id: string) => springHttp.get<BackendMetricResponse>(`${RESOURCE}/${id}`),
}
