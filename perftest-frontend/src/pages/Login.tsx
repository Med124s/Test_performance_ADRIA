import { Navigate, useLocation } from 'react-router-dom'
import LoginRedirect from '../components/LoginRedirect'
import { useAuth } from '../context/AuthContext'
import { safeReturnPath } from '../services/auth/keycloakClient'

/** Ancienne adresse /login : envoie vers la page de connexion, ou dans l'application si l'utilisateur est déjà connecté. */
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
