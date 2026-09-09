import { createContext, useContext, useEffect, useState, ReactNode } from 'react'

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
  /** Source de vérité unique pour le contrôle d'accès en écriture : false
   * pour le rôle Visiteur (accès "Consultation" en lecture seule — voir
   * RolesCatalog.tsx). Tout bouton ou route qui modifie des données doit
   * se fier à cette valeur plutôt que de retester `user.role` lui-même,
   * pour garder une seule règle appliquée partout. */
  canEdit: boolean
  login: (email: string, role: UserRole, remember: boolean, name?: string) => void
  logout: () => void
}

const STORAGE_KEY = 'perftest_auth_user'

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

function getInitials(email: string) {
  const namePart = email.split('@')[0].replace(/[._]/g, ' ')
  const words = namePart.trim().split(' ').filter(Boolean)
  if (words.length === 0) return 'AU'
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return (words[0][0] + words[1][0]).toUpperCase()
}

function readStoredUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY) || sessionStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as AuthUser) : null
  } catch {
    return null
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(() => readStoredUser())

  useEffect(() => {
    // Keep tabs in sync if the user logs out elsewhere
    const onStorage = () => setUser(readStoredUser())
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

  const login = (email: string, role: UserRole, remember: boolean, name?: string) => {
    const displayName =
      name ||
      email
        .split('@')[0]
        .replace(/[._]/g, ' ')
        .replace(/\b\w/g, (c) => c.toUpperCase()) ||
      'Utilisateur'

    const authUser: AuthUser = {
      name: displayName,
      email,
      role,
      initials: getInitials(email),
    }

    setUser(authUser)
    const storage = remember ? localStorage : sessionStorage
    storage.setItem(STORAGE_KEY, JSON.stringify(authUser))
  }

  const logout = () => {
    setUser(null)
    localStorage.removeItem(STORAGE_KEY)
    sessionStorage.removeItem(STORAGE_KEY)
  }

  const canEdit = !!user && user.role !== 'Visiteur'

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: !!user, canEdit, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider')
  return ctx
}
