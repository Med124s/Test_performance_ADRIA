// ============================================================
// Client pour le "Local Test Server" — PAS JSON Server. C'est un tout
// petit service Node séparé (voir /local-monitoring-server à la racine
// du projet) qui expose les vraies métriques CPU/RAM/Disk/Network/Health
// du PC Windows sur lequel tourne ce projet, pour un test local sans VPS.
//
// Point de branchement volontairement isolé : demain, un agent Spring
// Boot exposera la même forme de réponse sur un autre `monitoringUrl`
// (voir Server.monitoringUrl) — seul ce fichier changerait, jamais
// serverSnapshot.ts ni les composants qui le consomment.
// ============================================================

export interface LocalServerMetrics {
  cpu: number
  ram: number
  disk: number
  network: number
  health: 'Healthy' | 'Degraded' | 'Critical'
  capturedAt: string
}

const FETCH_TIMEOUT_MS = 2000

export const localMonitoringApi = {
  /** Interroge le service de monitoring à `monitoringUrl`. Ne lève jamais :
   * un service éteint/injoignable rend simplement `null` (les valeurs de
   * l'exécution restent alors `null`, jamais inventées).
   *
   * `externalSignal`, si fourni (voir useScenarioLauncher, appelé après
   * chaque étape pour le CPU/RAM par étape), permet d'interrompre CET APPEL
   * immédiatement en cas d'Annulation de l'exécution — sans lui, cliquer
   * "Annuler" pouvait rester bloqué jusqu'à `FETCH_TIMEOUT_MS` de plus,
   * le temps que cet appel de monitoring se termine tout seul. */
  async fetchMetrics(monitoringUrl: string, externalSignal?: AbortSignal): Promise<LocalServerMetrics | null> {
    if (externalSignal?.aborted) return null
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS)
    const onExternalAbort = () => controller.abort()
    externalSignal?.addEventListener('abort', onExternalAbort)
    try {
      const response = await fetch(monitoringUrl, { signal: controller.signal })
      if (!response.ok) return null
      return (await response.json()) as LocalServerMetrics
    } catch {
      return null
    } finally {
      clearTimeout(timeout)
      externalSignal?.removeEventListener('abort', onExternalAbort)
    }
  },
}
