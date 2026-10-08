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

  // Laisse le temps à AuthProvider de traiter un éventuel retour de Keycloak
  // (?code=&state=) avant de décider — sans cette attente, un utilisateur
  // réellement authentifié serait renvoyé vers Keycloak dans une boucle, le
  // temps que l'échange du code se termine.
  if (isLoading) {
    return null
  }

  // Pas de page intermédiaire : direction la page de connexion LoadPilot
  // (thème Keycloak), puis retour sur la page demandée.
  if (!isAuthenticated) {
    return <LoginRedirect returnTo={here} />
  }

  if (returnTo && returnTo !== here) {
    return <Navigate to={returnTo} replace />
  }

  return <>{children}</>
}

export default ProtectedRoute
