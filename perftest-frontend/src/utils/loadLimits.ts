import { settingsBackendApi } from '../services/api/settingsBackend'
import type { LoadLimits } from '../pages/scenario-wizard/wizardModel'

// Limites d'un test appliquées par le serveur (GET /api/settings/load-limits, lisible par tous) :
// plages et validation de l'étape « Charge & résumé ». Une seule requête par session ; relue après
// une modification des limites (Paramètres > Système > Limites).

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
