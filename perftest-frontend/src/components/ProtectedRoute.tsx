import { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isAuthenticated, isLoading } = useAuth()
  const location = useLocation()

  // Phase 16 : en mode mock, isLoading vaut toujours false (comportement
  // inchangé). En mode keycloak, laisse le temps à AuthProvider de traiter
  // un éventuel retour de Keycloak (?code=&state=) avant de décider — sans
  // cette attente, un utilisateur réellement authentifié serait renvoyé à
  // /login puis à Keycloak dans une boucle, le temps que l'échange du code
  // se termine.
  if (isLoading) {
    return null
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return <>{children}</>
}

export default ProtectedRoute
