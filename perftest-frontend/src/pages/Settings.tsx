import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { profileBackendApi } from '../services/api/profileBackend'
import { BackendProfileResponse } from '../types/backendContracts'
import { ApiError } from '../services/api/httpClient'
import { useDarkMode } from '../utils/useDarkMode'

// ============================================================
// P1-D — page RÉELLE (remplace l'ancien ComingSoonPage) : profil réel
// (GET /api/profile, identité/rôles Keycloak) + SEULE préférence
// réellement modifiable, le fuseau horaire (PATCH /api/profile — voir
// ProfileController côté backend). username/name/email sont affichés en
// LECTURE SEULE : ce sont des propriétés de Keycloak, jamais éditables
// depuis LoadPilot (les modifier ici serait silencieusement écrasé au
// prochain login, voir ProfileServiceImpl côté backend).
//
// Le mode clair/sombre (déjà réel, voir Sidebar.tsx) est exposé ici comme
// second réglage réel — désormais persisté (voir utils/useDarkMode.ts).
// ============================================================

const COMMON_TIMEZONES = [
  'UTC', 'Africa/Casablanca', 'Europe/Paris', 'Europe/London', 'America/New_York',
  'America/Los_Angeles', 'Asia/Dubai', 'Asia/Tokyo', 'Australia/Sydney',
]

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0: return err.message
      case 401: return 'Vous devez être connecté (Keycloak) pour accéder à vos paramètres.'
      case 400: return err.message
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

const roleLabel: Record<string, string> = {
  ROLE_SUPER_ADMIN: 'Super Administrateur',
  ROLE_PERFORMANCE_ENGINEER: 'Ingénieur Performance',
  ROLE_VIEWER: 'Observateur',
}

function Settings() {
  const { darkMode, setDarkMode } = useDarkMode()
  const [profile, setProfile] = useState<BackendProfileResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [timezone, setTimezone] = useState('')
  const [savingTimezone, setSavingTimezone] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const browserTimezone = (() => {
    try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' } catch { return 'UTC' }
  })()

  const load = () => {
    setLoading(true)
    setError(null)
    profileBackendApi.get()
      .then((p) => { setProfile(p); setTimezone(p.timezone ?? browserTimezone) })
      .catch((err) => setError(describeApiError(err, 'Impossible de charger votre profil.')))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const handleSaveTimezone = async () => {
    setSavingTimezone(true)
    setSaveError(null)
    setSaved(false)
    try {
      const updated = await profileBackendApi.updateTimezone(timezone)
      setProfile(updated)
      setSaved(true)
      setTimeout(() => setSaved(false), 3000)
    } catch (err) {
      setSaveError(describeApiError(err, 'Impossible d\'enregistrer le fuseau horaire.'))
    } finally {
      setSavingTimezone(false)
    }
  }

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Settings</h1>
          <p>Votre profil et vos préférences réelles</p>
        </div>
        <TopBar searchPlaceholder="" />
      </div>

      {error && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={load}>Réessayer</button>
        </div>
      )}

      {loading ? (
        <div className="pt-empty-state">
          <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
          <p>Chargement de votre profil...</p>
        </div>
      ) : profile && (
        <div className="row g-4">
          <div className="col-12 col-xl-6">
            <div className="pt-card h-100">
              <div className="pt-card-title mb-3"><i className="bi bi-person-badge me-2 text-primary"></i>Profil</div>
              <p className="text-muted mb-3" style={{ fontSize: '12px' }}>
                Identité gérée par Keycloak — en lecture seule ici (modifiable uniquement via votre compte Keycloak).
              </p>
              <div className="d-flex flex-column gap-2" style={{ fontSize: '13.5px' }}>
                <div className="d-flex justify-content-between border-bottom pb-2"><span className="text-muted">Nom</span><strong>{profile.name ?? '—'}</strong></div>
                <div className="d-flex justify-content-between border-bottom pb-2"><span className="text-muted">Nom d'utilisateur</span><strong>{profile.username ?? '—'}</strong></div>
                <div className="d-flex justify-content-between border-bottom pb-2"><span className="text-muted">Email</span><strong>{profile.email ?? '—'}</strong></div>
                <div className="d-flex justify-content-between align-items-center">
                  <span className="text-muted">Rôle(s)</span>
                  <div className="d-flex gap-1 flex-wrap justify-content-end">
                    {profile.roles.map((r) => (
                      <span key={r} className="pt-pill info" style={{ fontSize: '11px' }}>{roleLabel[r] ?? r}</span>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div className="col-12 col-xl-6">
            <div className="pt-card h-100">
              <div className="pt-card-title mb-3"><i className="bi bi-globe me-2 text-primary"></i>Fuseau horaire</div>
              <p className="text-muted mb-3" style={{ fontSize: '12px' }}>
                Utilisé pour vos planifications (voir Planification) et l'affichage des dates. Enregistré réellement sur votre profil.
              </p>
              {saveError && <div className="pt-alert-banner danger mb-2" style={{ fontSize: '12px' }}>{saveError}</div>}
              <div className="d-flex gap-2 flex-wrap align-items-center">
                <select className="pt-form-control" style={{ maxWidth: '280px' }} value={timezone} onChange={(e) => setTimezone(e.target.value)}>
                  {!COMMON_TIMEZONES.includes(timezone) && <option value={timezone}>{timezone}</option>}
                  {COMMON_TIMEZONES.map((tz) => <option key={tz} value={tz}>{tz}</option>)}
                </select>
                <button className="pt-btn-primary" onClick={handleSaveTimezone} disabled={savingTimezone}>
                  {savingTimezone ? 'Enregistrement...' : 'Enregistrer'}
                </button>
                {saved && <span className="pt-pill success" style={{ fontSize: '11px' }}><i className="bi bi-check2 me-1"></i>Enregistré</span>}
              </div>
              <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)', marginTop: '8px' }}>
                Fuseau de votre navigateur : <strong>{browserTimezone}</strong>
              </div>
            </div>
          </div>

          <div className="col-12 col-xl-6">
            <div className="pt-card h-100">
              <div className="pt-card-title mb-3"><i className="bi bi-moon-stars me-2 text-primary"></i>Apparence</div>
              <div className="d-flex align-items-center justify-content-between">
                <span style={{ fontSize: '13.5px' }}>Mode sombre</span>
                <label className="pt-toggle">
                  <input type="checkbox" checked={darkMode} onChange={(e) => setDarkMode(e.target.checked)} />
                  <span className="toggle-slider"></span>
                </label>
              </div>
            </div>
          </div>

          <div className="col-12 col-xl-6">
            <div className="pt-card h-100">
              <div className="pt-card-title mb-3"><i className="bi bi-link-45deg me-2 text-primary"></i>Liens rapides</div>
              <div className="d-flex flex-column gap-2" style={{ fontSize: '13.5px' }}>
                <Link to="/notifications/preferences" className="d-flex justify-content-between align-items-center text-decoration-none">
                  <span><i className="bi bi-bell me-2"></i>Préférences de notifications</span>
                  <i className="bi bi-chevron-right text-muted"></i>
                </Link>
                <Link to="/roles-catalog" className="d-flex justify-content-between align-items-center text-decoration-none border-top pt-2">
                  <span><i className="bi bi-shield-lock me-2"></i>Catalogue des rôles</span>
                  <i className="bi bi-chevron-right text-muted"></i>
                </Link>
                <Link to="/configurations" className="d-flex justify-content-between align-items-center text-decoration-none border-top pt-2">
                  <span><i className="bi bi-gear me-2"></i>Configurations système</span>
                  <i className="bi bi-chevron-right text-muted"></i>
                </Link>
                <Link to="/settings/integrations" className="d-flex justify-content-between align-items-center text-decoration-none border-top pt-2">
                  <span><i className="bi bi-plug me-2"></i>Intégrations</span>
                  <i className="bi bi-chevron-right text-muted"></i>
                </Link>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default Settings
