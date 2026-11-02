// Tableau de bord (lecture seule).

import { springHttp } from './httpClient'
import {
  BackendApplicationDashboardResponse,
  BackendDashboardOverview,
  BackendDashboardResponse,
  BackendDashboardScenarioRow,
  BackendPagedResponse,
  BackendScenarioTrend,
} from '../../types/backendContracts'

const RESOURCE = '/api/dashboard'

export const dashboardBackendApi = {
  getGlobal: (range?: { from?: string; to?: string }) => {
    const params = new URLSearchParams()
    if (range?.from) params.set('from', range.from)
    if (range?.to) params.set('to', range.to)
    const qs = params.toString()
    return springHttp.get<BackendDashboardResponse>(`${RESOURCE}${qs ? `?${qs}` : ''}`)
  },
  getByApplication: (applicationId: string) =>
    springHttp.get<BackendApplicationDashboardResponse>(`${RESOURCE}/applications/${applicationId}`),
  /** Tableau de bord filtré par application (optionnelle) et période. */
  getOverview: (filter: { applicationId?: string; from?: string; to?: string }) =>
    springHttp.get<BackendDashboardOverview>(`${RESOURCE}/overview${query(filter)}`),
  /** « Dernière exécution par scénario », paginé côté serveur (page à partir de 0, 10 lignes par défaut). */
  getLatestByScenario: (filter: { applicationId?: string; from?: string; to?: string; page: number; size: number }) =>
    springHttp.get<BackendPagedResponse<BackendDashboardScenarioRow>>(`${RESOURCE}/latest-by-scenario${query({
      applicationId: filter.applicationId, from: filter.from, to: filter.to, page: String(filter.page), size: String(filter.size),
    })}`),
  /** Évolution du P95 et du taux d'erreurs d'un scénario sur la période. */
  getScenarioTrend: (scenarioId: string, range: { from?: string; to?: string }, stepName?: string) =>
    springHttp.get<BackendScenarioTrend>(`${RESOURCE}/scenario-trend${query({ scenarioId, ...range, stepName })}`),
}

function query(params: Record<string, string | undefined>): string {
  const p = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => { if (v) p.set(k, v) })
  const qs = p.toString()
  return qs ? `?${qs}` : ''
}
