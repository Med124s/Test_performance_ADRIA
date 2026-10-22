import { useEffect, useState } from 'react'
import { settingsBackendApi } from '../services/api/settingsBackend'
import type { BackendThresholdSettings } from '../types/backendContracts'

// ============================================================
// Seuils par défaut (Paramètres > Système) lus UNE fois par session et partagés :
// le seuil de régression colore les écarts de P95 partout (Dashboard côté serveur,
// liste des exécutions, planification, notifications) ; les seuils proposés pré-remplissent
// l'assistant de scénario. Valeurs par défaut du serveur tant que la réponse n'est pas arrivée.
// ============================================================

/** Valeurs initiales du serveur (AppSettingsService), aussi celles codées jusqu'ici dans l'assistant. */
export const DEFAULT_THRESHOLDS: Required<BackendThresholdSettings> = {
  maxErrorRatePercent: 1, maxP95Ms: 0, regressionPercent: 20,
  errorBrakeEnabled: false, errorBrakeRatePercent: 20, errorBrakeWindowSeconds: 30,
  throttleOn429: true, throttleDefaultDelaySeconds: 5, throttleMaxRetries: 3, requestDetailMode: 'FAILURES_AND_SAMPLE',
}

/** Réglages complets : un champ absent de la réponse (serveur plus ancien) prend sa valeur initiale. */
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
