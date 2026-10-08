import { Navigate, useLocation } from 'react-router-dom'
import LoginRedirect from '../components/LoginRedirect'
import { useAuth } from '../context/AuthContext'
import { safeReturnPath } from '../services/auth/keycloakClient'

/**
 * Ancienne adresse /login : il n'y a plus de page intermédiaire. La page de
 * connexion est le thème Keycloak « loadpilot » (keycloak/themes/loadpilot) ;
 * un utilisateur non connecté y est envoyé directement, un utilisateur déjà
 * connecté revient dans l'application.
 */
function Login() {
  const { isAuthenticated, isLoading } = useAuth()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from
  const target = safeReturnPath(from) ?? '/'

  if (isLoading) return null
  if (isAuthenticated) return <Navigate to={target} replace />
  return <LoginRedirect returnTo={target} />
}

export default Login
