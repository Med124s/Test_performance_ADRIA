import { ReactNode, useEffect } from 'react'
import { useAuth } from '../context/AuthContext'
import { redirectToLogin } from '../services/auth/keycloakClient'

/**
 * Plus d'écran intermédiaire React (ancien /login) : un utilisateur non
 * authentifié est redirigé DIRECTEMENT vers la vraie page Keycloak (thème
 * "cadence", voir keycloak-local-dev/keycloak-26.7.3/themes/cadence) — même
 * flux réel Authorization Code + PKCE qu'avant, juste sans bouton
 * intermédiaire à cliquer.
 */
function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isAuthenticated, isLoading } = useAuth()

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      void redirectToLogin()
    }
  }, [isLoading, isAuthenticated])

  if (isLoading || !isAuthenticated) {
    return null
  }

  return <>{children}</>
}

export default ProtectedRoute
