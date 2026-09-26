import { useAuth } from '../context/AuthContext'

/**
 * P1-O — Point d'entrée réel de la route /login. Le Mode mock
 * (MockLoginScreen, JSON Server, mot de passe en clair) a été retiré : voir
 * le rapport P1-O pour la preuve runtime complète (realm/clients/rôles
 * Keycloak réels, login/refresh/logout réels, RBAC réel SUPER_ADMIN/
 * PERFORMANCE_ENGINEER/VIEWER validé contre le backend Spring Boot réel).
 * L'authentification est désormais exclusivement Keycloak (OAuth2
 * Authorization Code + PKCE S256) — aucun mot de passe ne transite par
 * React (voir services/auth/keycloakClient.ts).
 */
function Login() {
  const { loginWithKeycloak } = useAuth()
  return (
    <div className="pt-auth-page">
      <div className="pt-auth-brand">
        <div className="pt-auth-brand-inner">
          <div className="pt-auth-logo">
            <div className="pt-auth-logo-icon">
              <i className="bi bi-speedometer2"></i>
            </div>
            <div>
              <h4>Cadence</h4>
              <span>Performance Testing Platform</span>
            </div>
          </div>
        </div>
      </div>
      <div className="pt-auth-form-panel">
        <div className="pt-auth-form-wrapper">
          <div className="pt-auth-form-header">
            <h2>Connexion</h2>
            <p>L'authentification est gérée par Keycloak.</p>
          </div>
          <button type="button" className="pt-btn-primary w-100 justify-content-center" onClick={loginWithKeycloak}>
            <i className="bi bi-box-arrow-in-right"></i> Se connecter avec Keycloak
          </button>
        </div>
      </div>
    </div>
  )
}

export default Login
