// Relie la page Configurations (carte "Paramètres par défaut") aux
// formulaires de lancement de test (page Exécutions) : les valeurs
// enregistrées ici servent de valeurs de départ pour tout nouveau
// lancement, au lieu de constantes arbitraires dupliquées dans chaque page.

export interface DefaultTestSettings {
  httpTimeoutSeconds: number // "Timeout HTTP" (ex: 10s)
  stepDelayMs: number // "Délai entre étapes" -> Think time par défaut
  concurrency: number // "Utilisateurs concurrents (par défaut)"
  rampUpSeconds: number // "Montée en charge (Ramp-up)"
  retries: number // "Tentatives en cas d'échec"
}

const KEY = 'pt_default_test_settings'

export const fallbackDefaultTestSettings: DefaultTestSettings = {
  httpTimeoutSeconds: 10,
  stepDelayMs: 500,
  concurrency: 50,
  rampUpSeconds: 60,
  retries: 3,
}

export function saveDefaultTestSettings(settings: DefaultTestSettings) {
  try {
    localStorage.setItem(KEY, JSON.stringify(settings))
  } catch {
    // ignore
  }
}

export function loadDefaultTestSettings(): DefaultTestSettings {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return fallbackDefaultTestSettings
    return { ...fallbackDefaultTestSettings, ...JSON.parse(raw) }
  } catch {
    return fallbackDefaultTestSettings
  }
}
