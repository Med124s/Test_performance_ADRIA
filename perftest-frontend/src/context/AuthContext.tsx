import { createContext, useContext, useEffect, useState, ReactNode } from 'react'
import { AUTH_PROVIDER } from '../services/auth/keycloakConfig'
import {
  getCurrentClaims,
  handleRedirectCallback,
  redirectToLogin,
  logout as keycloakLogout,
} from '../services/auth/keycloakClient'
import { backendRolesToFrontendRole } from '../utils/roleMapping'

export type UserRole = 'Visiteur' | 'Testeur' | 'Admin'

export interface AuthUser {
  name: string
  email: string
  role: UserRole
  initials: string
}

interface AuthContextValue {
  user: AuthUser | null
  isAuthenticated: boolean
  /** `true` UNIQUEMENT pendant le traitement initial du retour Keycloak
   * (échange du code d'autorisation contre les tokens, avant de savoir si
   * l'utilisateur est réellement authentifié) — `ProtectedRoute` doit
   * attendre cette résolution avant de rediriger vers `/login`, sous peine
   * de boucle de redirection. */
  isLoading: boolean
  /** Source de vérité unique pour le contrôle d'accès en écriture : false
   * pour le rôle Visiteur (accès "Consultation" en lecture seule — voir
   * RolesCatalog.tsx). Tout bouton ou route qui modifie des données doit
   * se fier à cette valeur plutôt que de retester `user.role` lui-même,
   * pour garder une seule règle appliquée partout. */
  canEdit: boolean
  /** P1-O — conservé comme simple étiquette (de nombreuses pages dérivent
   * `isKeycloak = authProvider === 'keycloak'` avant d'autoriser une
   * écriture Spring Boot) ; ne vaut plus jamais que 'keycloak' depuis le
   * retrait du Mode mock. */
  authProvider: 'keycloak'
  /** Rôles techniques bruts (ROLE_SUPER_ADMIN/ROLE_PERFORMANCE_ENGINEER/
   * ROLE_VIEWER) — alimentés par le vrai JWT Keycloak (voir
   * utils/roleMapping.ts). */
  rawRoles: string[]
  /** Redirige vers la vraie page de login Keycloak (aucun mot de passe ne
   * transite par React, voir services/auth/keycloakClient.ts). */
  loginWithKeycloak: () => void
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

function getInitials(email: string) {
  const namePart = email.split('@')[0].replace(/[._]/g, ' ')
  const words = namePart.trim().split(' ').filter(Boolean)
  if (words.length === 0) return 'AU'
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return (words[0][0] + words[1][0]).toUpperCase()
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [rawRoles, setRawRoles] = useState<string[]>([])
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        // Traite un éventuel ?code=&state= de retour Keycloak (voir
        // keycloakClient.ts) — no-op si absent (accès direct à l'app).
        await handleRedirectCallback()
      } catch (err) {
        // Jamais de session fabriquée en cas d'échec de l'échange réel.
        console.error('Échec du traitement du retour Keycloak :', err)
      }
      if (cancelled) return

      const claims = await getCurrentClaims()
      const roles = claims?.realm_access?.roles ?? []
      const role = claims ? backendRolesToFrontendRole(roles) : null

      setRawRoles(roles)
      setUser(
        claims && role
          ? {
              name: claims.name || claims.preferred_username || 'Utilisateur',
              email: claims.email || '',
              role,
              initials: getInitials(claims.email || claims.preferred_username || claims.sub),
            }
          : null
      )
      setIsLoading(false)
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const loginWithKeycloak = () => {
    void redirectToLogin()
  }

  const logout = () => {
    // Invalide la VRAIE session SSO Keycloak — ne se contente jamais de
    // simuler une déconnexion locale.
    keycloakLogout()
  }

  const canEdit = !!user && user.role !== 'Visiteur'

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: !!user,
        isLoading,
        canEdit,
        authProvider: AUTH_PROVIDER,
        rawRoles,
        loginWithKeycloak,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider')
  return ctx
}
