import { settingsBackendApi } from '../services/api/settingsBackend'
import type { LoadLimits } from '../pages/scenario-wizard/wizardModel'

// Limites de charge d'un test (GET /api/settings/load-limits), lues une fois par session.

let cached: LoadLimits | null = null
let inFlight: Promise<LoadLimits | null> | null = null

export function loadLoadLimits(): Promise<LoadLimits | null> {
  if (cached) return Promise.resolve(cached)
  if (!inFlight) {
    inFlight = settingsBackendApi.loadLimits()
      .then((l) => { cached = l; return l })
      .catch(() => null)
      .finally(() => { inFlight = null })
  }
  return inFlight
}

export function resetLoadLimitsCache() {
  cached = null
  inFlight = null
}
