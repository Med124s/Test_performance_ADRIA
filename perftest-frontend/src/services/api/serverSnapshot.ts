import { localMonitoringApi } from './localMonitoring'
import { Application, ServerHealth, ServerSnapshot } from '../../types'

// ============================================================
// Point de branchement unique pour le futur backend Spring Boot + JMeter.
//
// AUJOURD'HUI, pour l'application testée, trois cas par ordre de priorité :
//   1. `Application.monitoringUrl` renseigné et joignable (Local Test
//      Server, PC Windows — voir /local-monitoring-server) : métriques
//      réelles, `simulated: false`.
//   2. Sinon (pas de monitoringUrl, ou service injoignable) : métriques
//      `null`, rien n'est inventé.
//
// DEMAIN : un agent Spring Boot exposera la même forme de réponse sur son
// propre `monitoringUrl` — seul localMonitoring.ts changerait de cible,
// aucun appelant (useScenarioLauncher, ExecutionDetail) n'a besoin de
// changer, ils ne dépendent que de la forme `ServerSnapshot`.
// ============================================================

const HEALTH_LABELS: Record<string, ServerHealth> = {
  Healthy: 'Sain',
  Degraded: 'Dégradé',
  Critical: 'Critique',
}

const LIVE_SOURCE_LABEL = 'Local Test Server'

export const serverSnapshotApi = {
  /** Capture les métriques du monitoring éventuellement branché sur cette
   * application. Tableau vide = pas de `monitoringUrl` configuré ; un seul
   * élément sinon (une application = un monitoringUrl). */
  async captureForApplication(application: Application): Promise<ServerSnapshot[]> {
    if (!application.monitoringUrl) return []

    const base = {
      applicationId: application.id,
      applicationName: application.name,
    }

    const liveMetrics = await localMonitoringApi.fetchMetrics(application.monitoringUrl)

    if (liveMetrics) {
      return [{
        ...base,
        capturedAt: liveMetrics.capturedAt,
        cpu: liveMetrics.cpu,
        ram: liveMetrics.ram,
        disk: liveMetrics.disk,
        network: liveMetrics.network,
        health: HEALTH_LABELS[liveMetrics.health] ?? null,
        source: LIVE_SOURCE_LABEL,
        simulated: false,
      }]
    }

    return [{
      ...base,
      capturedAt: new Date().toISOString(),
      cpu: null,
      ram: null,
      disk: null,
      network: null,
      health: null,
      source: null,
      simulated: false,
    }]
  },
}
