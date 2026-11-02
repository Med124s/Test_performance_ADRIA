import { useEffect, useState } from 'react'
import { appInfoBackendApi } from '../services/api/appInfoBackend'

// Version de LoadPilot lue sur le serveur (GET /api/version), une fois par session.

let cached: string | null = null
let inFlight: Promise<string | null> | null = null

export function loadAppVersion(): Promise<string | null> {
  if (cached) return Promise.resolve(cached)
  if (!inFlight) {
    inFlight = appInfoBackendApi.get()
      .then((info) => { cached = info.version || null; return cached })
      .catch(() => null)
      .finally(() => { inFlight = null })
  }
  return inFlight
}

export function resetAppVersionCache() {
  cached = null
  inFlight = null
}

/** Version de l'application, ou null tant qu'elle n'est pas connue (rien n'est alors affiché). */
export function useAppVersion(): string | null {
  const [version, setVersion] = useState<string | null>(cached)
  useEffect(() => {
    let alive = true
    loadAppVersion().then((v) => { if (alive) setVersion(v) })
    return () => { alive = false }
  }, [])
  return version
}
