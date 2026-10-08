import { useEffect, useState } from 'react'
import type { BackendPasswordPolicy } from '../../types/backendContracts'
import { usersBackendApi } from '../../services/api/usersBackend'

// Politique de mot de passe du realm, chargée une fois par session de page.
// En cas d'échec : null (seuls « saisi » et « confirmé » sont vérifiés ici ;
// le serveur applique de toute façon la politique et répond en français).

let cache: Promise<BackendPasswordPolicy | null> | null = null

export function resetPasswordPolicyCache() {
  cache = null
}

export function usePasswordPolicy(): BackendPasswordPolicy | null {
  const [policy, setPolicy] = useState<BackendPasswordPolicy | null>(null)
  useEffect(() => {
    let alive = true
    cache ??= usersBackendApi.passwordPolicy().catch(() => { cache = null; return null })
    cache.then((p) => { if (alive) setPolicy(p) })
    return () => { alive = false }
  }, [])
  return policy
}
