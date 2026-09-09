import { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

/**
 * Bloque l'accès à une route de création/modification pour le rôle
 * Visiteur (Consultation, lecture seule) — y compris en cas de navigation
 * directe par URL, pas seulement en masquant les boutons qui y mènent.
 * `/scenarios/create` et `/scenarios/create-step` n'ont aucune utilité en
 * lecture seule (la consultation d'un scénario se fait via la modale de
 * détail sur /scenarios), donc on redirige plutôt que d'afficher un
 * formulaire à moitié désactivé.
 */
function RequireEditAccess({ children, fallback = '/scenarios' }: { children: ReactNode; fallback?: string }) {
  const { canEdit } = useAuth()
  if (!canEdit) return <Navigate to={fallback} replace />
  return <>{children}</>
}

export default RequireEditAccess
