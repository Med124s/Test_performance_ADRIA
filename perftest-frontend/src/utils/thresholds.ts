import { useEffect, useState } from 'react'
import { settingsBackendApi } from '../services/api/settingsBackend'
import type { BackendThresholdSettings } from '../types/backendContracts'

// Seuils par défaut (Paramètres > Système), lus une fois par session.

// Valeurs initiales du serveur (AppSettingsService).
export const DEFAULT_THRESHOLDS: Required<BackendThresholdSettings> = {
  maxErrorRatePercent: 1, maxP95Ms: 0, regressionPercent: 20,
  errorBrakeEnabled: false, errorBrakeRatePercent: 20, errorBrakeWindowSeconds: 30,
  throttleOn429: true, throttleDefaultDelaySeconds: 5, throttleMaxRetries: 3, requestDetailMode: 'FAILURES_AND_SAMPLE',
}

/** Réglages complets : un champ absent prend sa valeur initiale. */
export function withDefaults(t: BackendThresholdSettings): Required<BackendThresholdSettings> {
  return { ...DEFAULT_THRESHOLDS, ...Object.fromEntries(Object.entries(t).filter(([, v]) => v !== undefined && v !== null)) }
}

let cached: BackendThresholdSettings | null = null
let inFlight: Promise<BackendThresholdSettings> | null = null
const listeners = new Set<(t: BackendThresholdSettings) => void>()

export function loadThresholds(): Promise<BackendThresholdSettings> {
  if (cached) return Promise.resolve(cached)
  if (!inFlight) {
    inFlight = settingsBackendApi.thresholds()
      .then((t) => { cached = t; listeners.forEach((l) => l(t)); return t })
      .catch(() => DEFAULT_THRESHOLDS)
      .finally(() => { inFlight = null })
  }
  return inFlight
}

/** Après un enregistrement dans Paramètres : toutes les pages ouvertes utilisent la nouvelle valeur. */
export function setCachedThresholds(t: BackendThresholdSettings) {
  cached = t
  listeners.forEach((l) => l(t))
}

export function useThresholds(): BackendThresholdSettings {
  const [value, setValue] = useState<BackendThresholdSettings>(cached ?? DEFAULT_THRESHOLDS)
  useEffect(() => {
    listeners.add(setValue)
    loadThresholds().then(setValue)
    return () => { listeners.delete(setValue) }
  }, [])
  return value
}

/** Seuil de régression (%) en cours. */
export function useRegressionThreshold(): number {
  return useThresholds().regressionPercent
}

/** Pour les tests. */
export function resetThresholdsCache() {
  cached = null
  inFlight = null
}
