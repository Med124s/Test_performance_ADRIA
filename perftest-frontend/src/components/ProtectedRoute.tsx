import { ReactNode, useEffect } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import LoginRedirect from './LoginRedirect'

function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isAuthenticated, isLoading, returnTo, clearReturnTo } = useAuth()
  const location = useLocation()
  const here = location.pathname + location.search + location.hash

  // La page demandée avant la connexion n'est rouverte qu'une fois.
  useEffect(() => {
    if (returnTo) clearReturnTo()
  }, [returnTo, clearReturnTo])

  // Attend le traitement d'un éventuel retour de Keycloak avant de décider.
  if (isLoading) {
    return null
  }

  // Non connecté : redirection vers la page de connexion, puis retour sur la page demandée.
  if (!isAuthenticated) {
    return <LoginRedirect returnTo={here} />
  }

  if (returnTo && returnTo !== here) {
    return <Navigate to={returnTo} replace />
  }

  return <>{children}</>
}

export default ProtectedRoute
