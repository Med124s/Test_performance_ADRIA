import { createContext, useCallback, useContext, useEffect, useState, ReactNode } from 'react'
import { AUTH_PROVIDER } from '../services/auth/keycloakConfig'
import {
  getCurrentClaims,
  handleRedirectCallback,
  redirectToLogin,
  logout as keycloakLogout,
} from '../services/auth/keycloakClient'
import { backendRolesToFrontendRole } from '../utils/roleMapping'
import { initialsOf } from '../utils/userDisplay'

export type UserRole = 'Visiteur' | 'Testeur' | 'Admin'

export interface AuthUser {
  /** Identifiant Keycloak (claim « sub ») : reconnaît son propre compte dans la page Utilisateurs. */
  id: string
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
   * attendre cette résolution avant de rediriger vers Keycloak, sous peine
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
   * transite par React, voir services/auth/keycloakClient.ts). `returnTo` :
   * page de l'application à rouvrir après la connexion. */
  loginWithKeycloak: (returnTo?: string) => void
  logout: () => void
  /** Page demandée avant la connexion, à rouvrir une seule fois juste après
   * le retour de Keycloak (voir ProtectedRoute) ; `null` sinon. */
  returnTo: string | null
  clearReturnTo: () => void
  /** Échec du retour de Keycloak (échange du code refusé, état invalide…) :
   * la redirection automatique est alors suspendue pour éviter une boucle. */
  authError: string | null
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [rawRoles, setRawRoles] = useState<string[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [returnTo, setReturnTo] = useState<string | null>(null)
  const [authError, setAuthError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      let requestedPath: string | null = null
      try {
        // Traite un éventuel ?code=&state= de retour Keycloak (voir
        // keycloakClient.ts) — no-op si absent (accès direct à l'app).
        requestedPath = await handleRedirectCallback()
      } catch (err) {
        // Jamais de session fabriquée en cas d'échec de l'échange réel.
        console.error('Échec du traitement du retour de connexion :', err)
        if (!cancelled) setAuthError(err instanceof Error ? err.message : 'Échec de la connexion.')
      }
      if (cancelled) return
      setReturnTo(requestedPath)

      const claims = await getCurrentClaims()
      const roles = claims?.realm_access?.roles ?? []
      const role = claims ? backendRolesToFrontendRole(roles) : null

      setRawRoles(roles)
      // Initiales calculées sur le nom AFFICHÉ (« Admin Test » -> « AT »),
      // jamais sur l'e-mail, pour que l'avatar corresponde au nom à côté.
      const name = claims?.name || claims?.preferred_username || 'Utilisateur'
      setUser(claims && role ? { id: claims.sub, name, email: claims.email || '', role, initials: initialsOf(name) } : null)
      setIsLoading(false)
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const loginWithKeycloak = (path?: string) => {
    void redirectToLogin(path)
  }

  const clearReturnTo = useCallback(() => setReturnTo(null), [])

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
        returnTo,
        clearReturnTo,
        authError,
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
