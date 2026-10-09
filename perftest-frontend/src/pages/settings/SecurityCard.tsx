import { useEffect, useState } from 'react'
import { profileBackendApi } from '../../services/api/profileBackend'
import { forgetSessionAndLogin } from '../../services/auth/keycloakClient'
import type { BackendProfileSecurity } from '../../types/backendContracts'
import { fullDate, relativeTime } from '../../utils/userDisplay'
import { describeSettingsError } from './settingsErrors'

// Sécurité (Paramètres > Mon profil) : dernière connexion, sessions ouvertes et
// « Se déconnecter de tous les appareils » (avec confirmation, y compris cet appareil).

export default function SecurityCard() {
  const [security, setSecurity] = useState<BackendProfileSecurity | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    profileBackendApi.security().then(setSecurity).catch((err) => setError(describeSettingsError(err, 'Impossible de lire vos sessions.')))
  }, [])

  useEffect(() => {
    if (!confirming) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !busy) setConfirming(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [confirming, busy])

  const logoutEverywhere = async () => {
    setBusy(true)
    setError(null)
    try {
      await profileBackendApi.logoutEverywhere()
      await forgetSessionAndLogin('/parametres?tab=profil')
    } catch (err) {
      setError(describeSettingsError(err, 'La déconnexion de tous les appareils a échoué.'))
      setBusy(false)
      setConfirming(false)
    }
  }

  const sessions = security?.sessions ?? []
  return (
    <section className="pt-card h-100 st-card" aria-labelledby="st-security-title">
      <h2 className="st-card-title" id="st-security-title"><i className="bi bi-shield-lock" aria-hidden="true"></i>Sécurité</h2>
      {error && <div className="pt-alert-banner danger mb-2" role="alert"><i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}</div>}
      {!security ? (
        !error && <p className="st-note">Chargement…</p>
      ) : (
        <>
          <dl className="st-facts">
            <dt>Dernière connexion</dt>
            <dd>
              {security.lastLoginAt
                ? <><time dateTime={security.lastLoginAt} title={fullDate(security.lastLoginAt) ?? undefined}>{relativeTime(security.lastLoginAt)}</time>
                  {security.lastLoginIp && <span className="st-muted"> · {security.lastLoginIp}</span>}</>
                : <span className="st-muted">Inconnue</span>}
            </dd>
          </dl>
          {security.message && <p className="st-note">{security.message}</p>}
          <h3 className="st-subtitle">Sessions ouvertes ({sessions.length})</h3>
          {sessions.length === 0 ? (
            <p className="st-note">Aucune session ouverte trouvée.</p>
          ) : (
            <ul className="st-sessions">
              {sessions.map((s) => (
                <li key={s.id} className={s.current ? 'current' : undefined}>
                  <i className={`bi ${s.mobile ? 'bi-phone' : 'bi-laptop'}`} aria-hidden="true"></i>
                  <div className="st-session-text">
                    <strong>
                      {s.device ?? (security.devicesKnown ? 'Appareil inconnu' : 'Navigateur')}
                      {s.current && <span className="st-badge">Cet appareil</span>}
                    </strong>
                    <span className="st-muted">
                      {s.ip ?? 'Adresse inconnue'}
                      {s.started && <> · ouverte <time dateTime={s.started} title={fullDate(s.started) ?? undefined}>{relativeTime(s.started)}</time></>}
                      {s.lastAccess && <> · active <time dateTime={s.lastAccess} title={fullDate(s.lastAccess) ?? undefined}>{relativeTime(s.lastAccess)}</time></>}
                    </span>
                  </div>
                </li>
              ))}
            </ul>
          )}
          {!security.devicesKnown && sessions.length > 0 && (
            <p className="st-note">Le navigateur et le système de chaque session ne sont pas disponibles.</p>
          )}
        </>
      )}
      <div className="st-actions">
        <button type="button" className="pt-btn-outline st-btn-danger" onClick={() => setConfirming(true)} disabled={busy}>
          <i className="bi bi-box-arrow-right" aria-hidden="true"></i> Se déconnecter de tous les appareils
        </button>
      </div>

      {confirming && (
        <div className="st-dialog-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget && !busy) setConfirming(false) }}>
          <div className="st-dialog" role="alertdialog" aria-modal="true" aria-labelledby="st-logout-title" aria-describedby="st-logout-text">
            <h2 id="st-logout-title">Se déconnecter de tous les appareils ?</h2>
            <p id="st-logout-text">
              Toutes vos sessions seront fermées, <strong>y compris celle-ci</strong> : vous devrez vous reconnecter sur chaque appareil.
            </p>
            <div className="st-dialog-actions">
              <button type="button" className="pt-btn-ghost" onClick={() => setConfirming(false)} disabled={busy} autoFocus>Annuler</button>
              <button type="button" className="pt-btn-primary st-btn-danger-solid" onClick={logoutEverywhere} disabled={busy}>
                {busy ? 'Déconnexion…' : 'Tout déconnecter'}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}
