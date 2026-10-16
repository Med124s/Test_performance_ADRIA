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
    // Initiales calculées sur le nom AFFICHÉ (« Admin Test » -> « AT »),
    // jamais sur l'e-mail, pour que l'avatar corresponde au nom à côté.
    const name = claims?.name || claims?.preferred_username || 'Utilisateur'
    setUser(claims && role ? { id: claims.sub, name, email: claims.email || '', role, initials: initialsOf(name) } : null)
  }

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

      await applyClaims()
      setIsLoading(false)
    })()
    return () => {
      cancelled = true
    }
  }, [])

  // Déconnexion globale : une session terminée (ici, dans un autre onglet, ou ailleurs) vide
  // aussitôt l'écran — ProtectedRoute repart alors vers la page de connexion.
  useEffect(() => {
    const offEnded = onSessionEnded(() => {
      setUser(null)
      setRawRoles([])
    })
    // Un autre onglet ou une autre fenêtre de ce navigateur vient de se déconnecter.
    const offAnnounced = onLogoutAnnounced(() => endLocalSession('logged_out'))
    // Session fermée ailleurs (« tous les appareils », expiration, compte désactivé) : vérifiée au
    // retour sur l'onglet et régulièrement tant qu'il est affiché.
    const check = () => {
      if (document.visibilityState === 'visible' && hasStoredSession()) void verifySession()
    }
    const timer = window.setInterval(check, SESSION_CHECK_INTERVAL_MS)
    // Jeton d'accès renouvelé en tâche de fond avant sa fin (onglet affiché), sans action de l'utilisateur.
    const stopRefresh = startBackgroundRefresh()
    document.addEventListener('visibilitychange', check)
    window.addEventListener('focus', check)
    // « Précédent » après la déconnexion : une page restaurée depuis le cache du navigateur
    // n'est jamais montrée sans session (masquée puis rechargée -> page de connexion).
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
