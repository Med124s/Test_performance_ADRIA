import { useState, FormEvent } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useAuth, UserRole } from '../context/AuthContext'
import { usersApi } from '../services/api/users'
import { validateRequired, validateEmail, firstError } from '../utils/validation'

type ViewMode = 'login' | 'register' | 'forgot' | 'sent'

function Login() {
  const navigate = useNavigate()
  const location = useLocation()
  const { login } = useAuth()

  const [view, setView] = useState<ViewMode>('login')
  const [email, setEmail] = useState('admin@perftest.com')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [remember, setRemember] = useState(true)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [forgotEmail, setForgotEmail] = useState('')
  const [forgotError, setForgotError] = useState('')
  const [forgotLoading, setForgotLoading] = useState(false)

  // Inscription — compte réel, créé et vérifié via JSON Server (voir
  // services/api/users.ts). Rôle par défaut "Testeur" : un nouveau compte
  // doit pouvoir créer/lancer des tests sans devoir d'abord se faire
  // promouvoir Admin.
  const [registerName, setRegisterName] = useState('')
  const [registerEmail, setRegisterEmail] = useState('')
  const [registerPassword, setRegisterPassword] = useState('')
  const [registerConfirm, setRegisterConfirm] = useState('')
  const [registerRole, setRegisterRole] = useState<UserRole>('Testeur')
  const [registerError, setRegisterError] = useState('')
  const [registerLoading, setRegisterLoading] = useState(false)

  const redirectTo = (location.state as { from?: string })?.from || '/'

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    setError('')

    const emailError = firstError(validateRequired(email, "L'adresse email"), validateEmail(email))
    if (emailError) {
      setError(emailError)
      return
    }
    const passwordError = firstError(
      validateRequired(password, 'Le mot de passe'),
      password.trim().length > 0 && password.trim().length < 4
        ? 'Le mot de passe doit contenir au moins 4 caractères.'
        : null
    )
    if (passwordError) {
      setError(passwordError)
      return
    }

    if (loading) return
    setLoading(true)
    const normalizedEmail = email.trim().toLowerCase()
    usersApi
      .getAll()
      .then((users) => {
        const account = users.find((u) => u.email.toLowerCase() === normalizedEmail)
        if (!account || account.password !== password) {
          setError('Email ou mot de passe incorrect.')
          setLoading(false)
          return
        }
        login(account.email, account.role, remember, account.name)
        setLoading(false)
        navigate(redirectTo, { replace: true })
      })
      .catch(() => {
        setError('Impossible de vérifier vos identifiants pour le moment. Réessayez.')
        setLoading(false)
      })
  }

  const handleRegisterSubmit = (e: FormEvent) => {
    e.preventDefault()
    setRegisterError('')

    const nameError = validateRequired(registerName, 'Le nom')
    const emailError = firstError(validateRequired(registerEmail, "L'adresse email"), validateEmail(registerEmail))
    const passwordError = firstError(
      validateRequired(registerPassword, 'Le mot de passe'),
      registerPassword.trim().length > 0 && registerPassword.trim().length < 4
        ? 'Le mot de passe doit contenir au moins 4 caractères.'
        : null
    )
    const confirmError =
      registerPassword !== registerConfirm ? 'Les mots de passe ne correspondent pas.' : null
    const firstIssue = nameError || emailError || passwordError || confirmError
    if (firstIssue) {
      setRegisterError(firstIssue)
      return
    }

    if (registerLoading) return
    setRegisterLoading(true)
    const normalizedEmail = registerEmail.trim().toLowerCase()
    usersApi
      .getAll()
      .then((users) => {
        if (users.some((u) => u.email.toLowerCase() === normalizedEmail)) {
          setRegisterError('Un compte existe déjà avec cet email.')
          setRegisterLoading(false)
          return
        }
        return usersApi
          .create({
            name: registerName.trim(),
            email: normalizedEmail,
            password: registerPassword,
            role: registerRole,
            createdAt: new Date().toISOString(),
          })
          .then((created) => {
            login(created.email, created.role, true, created.name)
            setRegisterLoading(false)
            navigate(redirectTo, { replace: true })
          })
      })
      .catch(() => {
        setRegisterError("Impossible de créer le compte pour le moment. Réessayez.")
        setRegisterLoading(false)
      })
  }

  const handleForgotSubmit = (e: FormEvent) => {
    e.preventDefault()
    setForgotError('')
    const emailError = firstError(validateRequired(forgotEmail, "L'adresse email"), validateEmail(forgotEmail))
    if (emailError) {
      setForgotError(emailError)
      return
    }
    if (forgotLoading) return
    setForgotLoading(true)
    setTimeout(() => {
      setForgotLoading(false)
      setView('sent')
    }, 500)
  }

  const quickFill = (demoRole: UserRole) => {
    const emails: Record<UserRole, string> = {
      Admin: 'admin@perftest.com',
      Testeur: 'testeur@perftest.com',
      Visiteur: 'visiteur@perftest.com',
    }
    setEmail(emails[demoRole])
    setPassword('demo1234')
  }

  return (
    <div className="pt-auth-page">
      {/* Left branding panel */}
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

      {/* Right form panel */}
      <div className="pt-auth-form-panel">
        <div className="pt-auth-form-wrapper">
          {view === 'login' && (
            <>
              <div className="pt-auth-form-header">
                <h2>Connexion</h2>
                <p>Accédez à votre espace Cadence</p>
              </div>

              {error && (
                <div className="pt-auth-alert">
                  <i className="bi bi-exclamation-circle-fill"></i>
                  {error}
                </div>
              )}

              <form onSubmit={handleSubmit} noValidate>
                <div className="mb-3">
                  <label className="pt-form-label">Adresse email</label>
                  <div className="pt-auth-input-group">
                    <i className="bi bi-envelope"></i>
                    <input
                      type="email"
                      className="pt-form-control"
                      placeholder="vous@entreprise.com"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      autoComplete="email"
                    />
                  </div>
                </div>

                <div className="mb-2">
                  <label className="pt-form-label">Mot de passe</label>
                  <div className="pt-auth-input-group">
                    <i className="bi bi-lock"></i>
                    <input
                      type={showPassword ? 'text' : 'password'}
                      className="pt-form-control"
                      placeholder="••••••••"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      autoComplete="current-password"
                    />
                    <button
                      type="button"
                      className="pt-auth-eye-btn"
                      onClick={() => setShowPassword(!showPassword)}
                      tabIndex={-1}
                    >
                      <i className={`bi ${showPassword ? 'bi-eye-slash' : 'bi-eye'}`}></i>
                    </button>
                  </div>
                </div>

                <div className="d-flex justify-content-between align-items-center mb-3 mt-2">
                  <label className="pt-auth-checkbox">
                    <input
                      type="checkbox"
                      checked={remember}
                      onChange={(e) => setRemember(e.target.checked)}
                    />
                    <span>Se souvenir de moi</span>
                  </label>
                  <button
                    type="button"
                    className="pt-auth-link"
                    onClick={() => { setForgotError(''); setView('forgot') }}
                  >
                    Mot de passe oublié ?
                  </button>
                </div>

                <button type="submit" className="pt-btn-primary w-100 justify-content-center" disabled={loading}>
                  {loading ? (
                    <>
                      <span className="spinner-border spinner-border-sm me-2" role="status" />
                      Connexion en cours...
                    </>
                  ) : (
                    <>
                      Se connecter <i className="bi bi-arrow-right"></i>
                    </>
                  )}
                </button>
              </form>

              <div className="pt-auth-divider">
                <span>Comptes de démonstration</span>
              </div>

              <div className="pt-auth-demo-roles">
                <button type="button" className="pt-auth-demo-card" onClick={() => quickFill('Admin')}>
                  <span className="pt-auth-demo-icon admin">
                    <i className="bi bi-shield-lock-fill"></i>
                  </span>
                  <div>
                    <strong>Admin</strong>
                    <small>Accès complet à la plateforme</small>
                  </div>
                </button>
                <button type="button" className="pt-auth-demo-card" onClick={() => quickFill('Testeur')}>
                  <span className="pt-auth-demo-icon tester">
                    <i className="bi bi-person-check-fill"></i>
                  </span>
                  <div>
                    <strong>Testeur</strong>
                    <small>Créer et lancer des tests</small>
                  </div>
                </button>
                <button type="button" className="pt-auth-demo-card" onClick={() => quickFill('Visiteur')}>
                  <span className="pt-auth-demo-icon viewer">
                    <i className="bi bi-eye-fill"></i>
                  </span>
                  <div>
                    <strong>Visiteur</strong>
                    <small>Consultation en lecture seule</small>
                  </div>
                </button>
              </div>

              <p className="pt-auth-footer-text">
                Pas encore de compte ?{' '}
                <button
                  type="button"
                  className="pt-auth-link"
                  onClick={() => { setRegisterError(''); setView('register') }}
                >
                  S'inscrire
                </button>
              </p>
            </>
          )}

          {view === 'register' && (
            <>
              <button type="button" className="pt-auth-back-btn" onClick={() => setView('login')}>
                <i className="bi bi-arrow-left"></i> Retour à la connexion
              </button>

              <div className="pt-auth-form-header">
                <h2>Créer un compte</h2>
                <p>Accédez à votre espace Cadence</p>
              </div>

              {registerError && (
                <div className="pt-auth-alert">
                  <i className="bi bi-exclamation-circle-fill"></i>
                  {registerError}
                </div>
              )}

              <form onSubmit={handleRegisterSubmit} noValidate>
                <div className="mb-3">
                  <label className="pt-form-label">Nom complet</label>
                  <div className="pt-auth-input-group">
                    <i className="bi bi-person"></i>
                    <input
                      type="text"
                      className="pt-form-control"
                      placeholder="Votre nom"
                      value={registerName}
                      onChange={(e) => setRegisterName(e.target.value)}
                      autoComplete="name"
                    />
                  </div>
                </div>

                <div className="mb-3">
                  <label className="pt-form-label">Adresse email</label>
                  <div className="pt-auth-input-group">
                    <i className="bi bi-envelope"></i>
                    <input
                      type="email"
                      className="pt-form-control"
                      placeholder="vous@entreprise.com"
                      value={registerEmail}
                      onChange={(e) => setRegisterEmail(e.target.value)}
                      autoComplete="email"
                    />
                  </div>
                </div>

                <div className="mb-3">
                  <label className="pt-form-label">Mot de passe</label>
                  <div className="pt-auth-input-group">
                    <i className="bi bi-lock"></i>
                    <input
                      type="password"
                      className="pt-form-control"
                      placeholder="••••••••"
                      value={registerPassword}
                      onChange={(e) => setRegisterPassword(e.target.value)}
                      autoComplete="new-password"
                    />
                  </div>
                </div>

                <div className="mb-3">
                  <label className="pt-form-label">Confirmer le mot de passe</label>
                  <div className="pt-auth-input-group">
                    <i className="bi bi-lock"></i>
                    <input
                      type="password"
                      className="pt-form-control"
                      placeholder="••••••••"
                      value={registerConfirm}
                      onChange={(e) => setRegisterConfirm(e.target.value)}
                      autoComplete="new-password"
                    />
                  </div>
                </div>

                <div className="mb-3">
                  <label className="pt-form-label">Rôle</label>
                  <select
                    className="pt-form-control"
                    value={registerRole}
                    onChange={(e) => setRegisterRole(e.target.value as UserRole)}
                  >
                    <option value="Testeur">Testeur — créer et lancer des tests</option>
                    <option value="Admin">Admin — accès complet</option>
                    <option value="Visiteur">Visiteur — consultation en lecture seule</option>
                  </select>
                </div>

                <button type="submit" className="pt-btn-primary w-100 justify-content-center" disabled={registerLoading}>
                  {registerLoading ? (
                    <>
                      <span className="spinner-border spinner-border-sm me-2" role="status" />
                      Création du compte...
                    </>
                  ) : (
                    <>
                      Créer mon compte <i className="bi bi-arrow-right"></i>
                    </>
                  )}
                </button>
              </form>
            </>
          )}

          {view === 'forgot' && (
            <>
              <button type="button" className="pt-auth-back-btn" onClick={() => setView('login')}>
                <i className="bi bi-arrow-left"></i> Retour à la connexion
              </button>

              <div className="pt-auth-form-header">
                <h2>Mot de passe oublié</h2>
                <p>Recevez un lien de réinitialisation par email</p>
              </div>

              {forgotError && (
                <div className="pt-auth-alert">
                  <i className="bi bi-exclamation-circle-fill"></i>
                  {forgotError}
                </div>
              )}

              <form onSubmit={handleForgotSubmit} noValidate>
                <div className="mb-3">
                  <label className="pt-form-label">Adresse email</label>
                  <div className="pt-auth-input-group">
                    <i className="bi bi-envelope"></i>
                    <input
                      type="email"
                      className="pt-form-control"
                      placeholder="vous@entreprise.com"
                      value={forgotEmail}
                      onChange={(e) => setForgotEmail(e.target.value)}
                    />
                  </div>
                </div>

                <button type="submit" className="pt-btn-primary w-100 justify-content-center" disabled={forgotLoading}>
                  {forgotLoading ? (
                    <>
                      <span className="spinner-border spinner-border-sm me-2" role="status" />
                      Envoi en cours...
                    </>
                  ) : (
                    'Envoyer le lien de réinitialisation'
                  )}
                </button>
              </form>
            </>
          )}

          {view === 'sent' && (
            <div className="pt-auth-success">
              <div className="pt-auth-success-icon">
                <i className="bi bi-envelope-check-fill"></i>
              </div>
              <h2>Email envoyé</h2>
              <p>
                Si un compte existe pour <strong>{forgotEmail}</strong>, un lien de
                réinitialisation vient de lui être envoyé.
              </p>
              <button type="button" className="pt-btn-outline" onClick={() => setView('login')}>
                <i className="bi bi-arrow-left"></i> Retour à la connexion
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

export default Login
