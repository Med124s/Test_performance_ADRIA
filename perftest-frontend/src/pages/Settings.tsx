import { useEffect, useRef, useState } from 'react'
import TopBar from '../components/TopBar'
import { profileBackendApi } from '../services/api/profileBackend'
import { consumeAccountActionResult, redirectToAccountAction } from '../services/auth/keycloakClient'
import { BackendProfileResponse } from '../types/backendContracts'
import { useToast } from '../context/ToastContext'
import AppearanceCard from './settings/AppearanceCard'
import NotificationsCard from './settings/NotificationsCard'
import SecurityCard from './settings/SecurityCard'
import { describeSettingsError } from './settings/settingsErrors'
import './settings/settings.css'

// ============================================================
// Paramètres > « Mon profil » (tout utilisateur connecté) :
//  - Profil : identité en lecture seule (propriété du système d'authentification), rôle
//    applicatif en français, « Changer mon mot de passe » (page du système d'authentification,
//    thème LoadPilot : le mot de passe n'est jamais saisi dans React) ;
//  - Fuseau horaire, Apparence (Clair · Sombre · Système), Notifications, Sécurité.
// ============================================================

const COMMON_TIMEZONES = [
  'UTC', 'Africa/Casablanca', 'Europe/Paris', 'Europe/London', 'America/New_York',
  'America/Los_Angeles', 'Asia/Dubai', 'Asia/Tokyo', 'Australia/Sydney',
]

/** embedded : affichée dans un onglet (Paramètres), sans son propre en-tête. */
function Settings({ embedded = false }: { embedded?: boolean } = {}) {
  const { showToast } = useToast()
  const [profile, setProfile] = useState<BackendProfileResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [timezone, setTimezone] = useState('')
  const [savingTimezone, setSavingTimezone] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [redirecting, setRedirecting] = useState(false)
  const actionHandled = useRef(false)

  const browserTimezone = (() => {
    try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' } catch { return 'UTC' }
  })()

  const load = () => {
    setLoading(true)
    setError(null)
    profileBackendApi.get()
      .then((p) => { setProfile(p); setTimezone(p.timezone ?? browserTimezone) })
      .catch((err) => setError(describeSettingsError(err, 'Impossible de charger votre profil.')))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  // Retour de la page « changer mon mot de passe » : message et inscription au journal d'audit.
  useEffect(() => {
    if (actionHandled.current) return
    actionHandled.current = true
    const result = consumeAccountActionResult()
    if (!result || result.action !== 'UPDATE_PASSWORD') return
    if (result.status === 'success') {
      profileBackendApi.passwordChanged()
        .then((r) => showToast(r.recorded ? 'Votre mot de passe a été modifié. Le changement est inscrit au journal d’audit.'
          : 'Votre mot de passe a été modifié.', 'success'))
        .catch(() => showToast('Votre mot de passe a été modifié.', 'success'))
    } else if (result.status === 'cancelled') {
      showToast('Changement de mot de passe annulé : votre mot de passe n’a pas changé.', 'info')
    } else {
      showToast('Le changement de mot de passe n’a pas abouti. Réessayez.', 'danger')
    }
  }, [showToast])

  const changePassword = async () => {
    setRedirecting(true)
    try {
      await redirectToAccountAction('UPDATE_PASSWORD', '/parametres?tab=profil')
    } catch {
      setRedirecting(false)
      showToast('Impossible d’ouvrir la page de changement de mot de passe.', 'danger')
    }
  }

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
      setSaveError(describeSettingsError(err, 'Impossible d’enregistrer le fuseau horaire.'))
    } finally {
      setSavingTimezone(false)
    }
  }

  return (
    <div className={embedded ? undefined : 'pt-content'}>
      {!embedded && (
        <div className="pt-page-header">
          <div className="page-title">
            <h1>Mon profil</h1>
            <p>Votre profil et vos préférences</p>
          </div>
          <TopBar searchPlaceholder="" />
        </div>
      )}

      {error && (
        <div className="pt-alert-banner danger mb-3" role="alert">
          <i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>
          {error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={load}>Réessayer</button>
        </div>
      )}

      {loading ? (
        <div className="pt-empty-state">
          <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }} aria-hidden="true"></i>
          <p>Chargement de votre profil…</p>
        </div>
      ) : profile && (
        <div className="row g-4">
          <div className="col-12 col-xl-6">
            <section className="pt-card h-100 st-card" aria-labelledby="st-profile-title">
              <h2 className="st-card-title" id="st-profile-title"><i className="bi bi-person-badge" aria-hidden="true"></i>Profil</h2>
              <dl className="st-facts">
                <dt>Nom</dt><dd>{profile.name ?? '—'}</dd>
                <dt>Nom d’utilisateur</dt><dd>{profile.username ?? '—'}</dd>
                <dt>E-mail</dt><dd>{profile.email ?? '—'}</dd>
                <dt>Rôle</dt>
                <dd><span className="pt-pill info st-role">{profile.appRoleLabel ?? 'Aucun rôle'}</span></dd>
              </dl>
              <p className="st-note">Identité gérée par le service de connexion : pour la modifier, contactez un administrateur.</p>
              <div className="st-actions">
                <button type="button" className="pt-btn-outline" onClick={changePassword} disabled={redirecting}>
                  <i className="bi bi-key" aria-hidden="true"></i> {redirecting ? 'Ouverture…' : 'Changer mon mot de passe'}
                </button>
              </div>
            </section>
          </div>

          <div className="col-12 col-xl-6">
            <section className="pt-card h-100 st-card" aria-labelledby="st-tz-title">
              <h2 className="st-card-title" id="st-tz-title"><i className="bi bi-globe" aria-hidden="true"></i>Fuseau horaire</h2>
              <p className="st-note mt-0">Utilisé pour vos planifications et l’affichage des dates.</p>
              {saveError && <div className="pt-alert-banner danger mb-2" role="alert" style={{ fontSize: '12px' }}>{saveError}</div>}
              <div className="d-flex gap-2 flex-wrap align-items-center">
                <select className="pt-form-control" style={{ maxWidth: '280px' }} value={timezone} aria-label="Fuseau horaire"
                  onChange={(e) => setTimezone(e.target.value)}>
                  {!COMMON_TIMEZONES.includes(timezone) && <option value={timezone}>{timezone}</option>}
                  {COMMON_TIMEZONES.map((tz) => <option key={tz} value={tz}>{tz}</option>)}
                </select>
                <button className="pt-btn-primary" onClick={handleSaveTimezone} disabled={savingTimezone}>
                  {savingTimezone ? 'Enregistrement…' : 'Enregistrer'}
                </button>
                {saved && <span className="pt-pill success" style={{ fontSize: '11px' }}><i className="bi bi-check2 me-1" aria-hidden="true"></i>Enregistré</span>}
              </div>
              <p className="st-note">Fuseau de votre navigateur : <strong>{browserTimezone}</strong></p>
            </section>
          </div>

          <div className="col-12 col-xl-6"><AppearanceCard /></div>
          <div className="col-12 col-xl-6"><SecurityCard /></div>
          <div className="col-12"><NotificationsCard /></div>
        </div>
      )}
    </div>
  )
}

export default Settings
