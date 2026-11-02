import { createContext, useCallback, useContext, useEffect, useState, ReactNode } from 'react'
import { AUTH_PROVIDER } from '../services/auth/keycloakConfig'
import {
  endLocalSession,
  getCurrentClaims,
  handleRedirectCallback,
  hasStoredSession,
  onSessionEnded,
  redirectToLogin,
  refreshTokensNow,
  startBackgroundRefresh,
  verifySession,
  logout as keycloakLogout,
} from '../services/auth/keycloakClient'
import { onLogoutAnnounced } from '../services/auth/sessionSync'

/** Vérification de la session Keycloak pendant que l'onglet est affiché. */
export const SESSION_CHECK_INTERVAL_MS = 30_000
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
  /** `true` pendant le traitement du retour de Keycloak. */
  isLoading: boolean
  /** false pour le rôle Observateur (lecture seule). */
  canEdit: boolean
  /** Fournisseur d'authentification. */
  authProvider: 'keycloak'
  /** Rôles techniques du jeton (ROLE_SUPER_ADMIN, ROLE_PERFORMANCE_ENGINEER, ROLE_VIEWER). */
  rawRoles: string[]
  /** Redirige vers la page de connexion ; `returnTo` : page à rouvrir après la connexion. */
  loginWithKeycloak: (returnTo?: string) => void
  logout: () => void
  /** Page demandée avant la connexion, à rouvrir une fois ; `null` sinon. */
  returnTo: string | null
  clearReturnTo: () => void
  /** Échec du retour de Keycloak ; la redirection automatique est alors suspendue. */
  authError: string | null
  /** Recharge l'identité affichée depuis un nouveau jeton (après « Modifier » dans Mon compte). */
  refreshUser: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [rawRoles, setRawRoles] = useState<string[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [returnTo, setReturnTo] = useState<string | null>(null)
  const [authError, setAuthError] = useState<string | null>(null)

  /** Identité et rôles affichés, lus dans le jeton courant. */
  const applyClaims = async () => {
    const claims = await getCurrentClaims()
    const roles = claims?.realm_access?.roles ?? []
    const role = claims ? backendRolesToFrontendRole(roles) : null
    setRawRoles(roles)
    // Initiales calculées sur le nom affiché (« Admin Test » -> « AT »).
    const name = claims?.name || claims?.preferred_username || 'Utilisateur'
    setUser(claims && role ? { id: claims.sub, name, email: claims.email || '', role, initials: initialsOf(name) } : null)
  }

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      let requestedPath: string | null = null
      try {
        // Traite un éventuel retour de Keycloak (?code=&state=).
        requestedPath = await handleRedirectCallback()
      } catch (err) {
        console.error('Échec du traitement du retour de connexion :', err)
        if (!cancelled) setAuthError(err instanceof Error ? err.message : 'Échec de la connexion.')
      }
      if (cancelled) return
      setReturnTo(requestedPath)

      await applyClaims()
      setIsLoading(false)
    })()
    return () => {
      cancelled = true
    }
  }, [])

  // Session terminée (ici ou dans un autre onglet) : l'écran est vidé.
  useEffect(() => {
    const offEnded = onSessionEnded(() => {
      setUser(null)
      setRawRoles([])
    })
    // Un autre onglet ou une autre fenêtre de ce navigateur vient de se déconnecter.
    const offAnnounced = onLogoutAnnounced(() => endLocalSession('logged_out'))
    // Session fermée ailleurs : vérifiée au retour sur l'onglet et régulièrement.
    const check = () => {
      if (document.visibilityState === 'visible' && hasStoredSession()) void verifySession()
    }
    const timer = window.setInterval(check, SESSION_CHECK_INTERVAL_MS)
    // Jeton d'accès renouvelé en tâche de fond avant sa fin (onglet affiché), sans action de l'utilisateur.
    const stopRefresh = startBackgroundRefresh()
    document.addEventListener('visibilitychange', check)
    window.addEventListener('focus', check)
    // « Précédent » après la déconnexion : une page restaurée depuis le cache est rechargée.
    const onPageShow = (event: PageTransitionEvent) => {
      if (!event.persisted) return
      if (!hasStoredSession()) {
        document.documentElement.style.visibility = 'hidden'
        window.location.reload()
      } else {
        check()
      }
    }
    window.addEventListener('pageshow', onPageShow)
    return () => {
      offEnded()
      offAnnounced()
      window.clearInterval(timer)
      stopRefresh()
      document.removeEventListener('visibilitychange', check)
      window.removeEventListener('focus', check)
      window.removeEventListener('pageshow', onPageShow)
    }
  }, [])

  const refreshUser = useCallback(async () => {
    await refreshTokensNow()
    await applyClaims()
  }, [])

  const loginWithKeycloak = (path?: string) => {
    void redirectToLogin(path)
  }

  const clearReturnTo = useCallback(() => setReturnTo(null), [])

  const logout = () => {
    // Ferme la session Keycloak.
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
        refreshUser,
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
