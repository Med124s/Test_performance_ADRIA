// Appels API des séries d'essais de stress (/api/stress-series) : même test enchaîné 3 ou 5 fois, point de rupture consolidé.

import { springHttp } from './httpClient'
import { downloadFromApi } from './executionsBackend'
import { BackendStressSeriesResponse } from '../../types/backendContracts'

const RESOURCE = '/api/stress-series'

export const stressSeriesBackendApi = {
  /** Lance la série : réponse 202, premier essai en attente (currentExecutionId). */
  start: (scenarioId: string, runs: number) => springHttp.post<BackendStressSeriesResponse>(RESOURCE, { scenarioId, runs }),
  get: (id: string) => springHttp.get<BackendStressSeriesResponse>(`${RESOURCE}/${id}`),
  /** Annule l'essai en cours, ou interrompt la pause ; 409 si la série est déjà terminée. */
  cancel: (id: string) => springHttp.post<BackendStressSeriesResponse>(`${RESOURCE}/${id}/cancel`),
  /** Rapport PDF d'une série terminée : point de rupture consolidé, essais, méthode (409 tant qu'elle est en cours). */
  exportPdf: (id: string) => downloadFromApi(`${RESOURCE}/${id}/report/pdf`, `serie-${id}.pdf`),
}
