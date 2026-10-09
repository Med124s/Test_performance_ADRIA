import { settingsBackendApi } from '../services/api/settingsBackend'
import type { BackendLoadDefaults } from '../types/backendContracts'

// Charge par défaut (Paramètres > Système) lue une fois par session : pré-remplit l'étape
// « Charge & résumé » d'un nouveau scénario et donne la limite d'utilisateurs du serveur.
// En cas d'échec : null (l'assistant garde ses valeurs, le serveur reste juge au final).

let cached: BackendLoadDefaults | null = null
let inFlight: Promise<BackendLoadDefaults | null> | null = null

export function loadLoadDefaults(): Promise<BackendLoadDefaults | null> {
  if (cached) return Promise.resolve(cached)
  if (!inFlight) {
    inFlight = settingsBackendApi.loadDefaults()
      .then((d) => { cached = d; return d })
      .catch(() => null)
      .finally(() => { inFlight = null })
  }
  return inFlight
}

export function setCachedLoadDefaults(d: BackendLoadDefaults) {
  cached = d
}

/** Pour les tests. */
export function resetLoadDefaultsCache() {
  cached = null
  inFlight = null
}
