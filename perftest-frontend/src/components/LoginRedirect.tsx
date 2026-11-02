import { useEffect, useRef } from 'react'
import { useAuth } from '../context/AuthContext'

/** Redirige l'utilisateur non connecté vers la page de connexion ; `returnTo` : page à rouvrir après la connexion. */
function LoginRedirect({ returnTo }: { returnTo?: string }) {
  const { loginWithKeycloak, authError } = useAuth()
  const started = useRef(false)

  useEffect(() => {
    if (authError || started.current) return
    started.current = true
    loginWithKeycloak(returnTo)
  }, [authError, loginWithKeycloak, returnTo])

  if (authError) {
    return (
      <div className="pt-auth-redirect" role="alert">
        <span className="pt-auth-redirect-logo"><i className="bi bi-speedometer2" aria-hidden="true"></i></span>
        <h1>Connexion impossible</h1>
        <p>La connexion à LoadPilot n’a pas pu aboutir. Réessayez ; si le problème persiste, contactez un administrateur.</p>
        <button type="button" className="pt-btn-primary" onClick={() => loginWithKeycloak(returnTo)}>
          <i className="bi bi-arrow-repeat" aria-hidden="true"></i> Réessayer la connexion
        </button>
      </div>
    )
  }

  return (
    <div className="pt-auth-redirect" role="status" aria-live="polite">
      <span className="pt-auth-redirect-logo"><i className="bi bi-speedometer2" aria-hidden="true"></i></span>
      <p>Redirection vers la page de connexion…</p>
    </div>
  )
}

export default LoginRedirect
