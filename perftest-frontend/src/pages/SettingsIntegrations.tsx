import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { settingsBackendApi } from '../services/api/settingsBackend'
import type { BackendIntegrations } from '../types/backendContracts'
import { describeSettingsError } from './settings/settingsErrors'
import './settings/settings.css'

// ============================================================
// Paramètres > « Intégrations » (Super administrateur) — état RÉEL, aucun secret affiché :
//  - serveur e-mail de LoadPilot (notifications par e-mail) : type, expéditeur, connexion
//    vérifiée à l'ouverture, e-mail de test à mon adresse ;
//  - e-mails du service de connexion (mot de passe oublié…) : serveur et expéditeur ;
//  - monitoring CPU / RAM : une lecture réelle par application configurée.
// ============================================================

const MONITORING_TYPE: Record<string, string> = { PROMETHEUS: 'Prometheus', ACTUATOR: 'Spring Boot Actuator' }

/** embedded : affichée dans un onglet (Paramètres), sans son propre en-tête. */
function SettingsIntegrations({ embedded = false }: { embedded?: boolean } = {}) {
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const isSuperAdmin = authProvider === 'keycloak' && rawRoles.includes('ROLE_SUPER_ADMIN')
  const [data, setData] = useState<BackendIntegrations | null>(null)
  const [loading, setLoading] = useState(isSuperAdmin)
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)

  const load = useCallback(() => {
    if (!isSuperAdmin) return
    setLoading(true)
    setError(null)
    settingsBackendApi.integrations()
      .then(setData)
      .catch((err) => setError(describeSettingsError(err, 'Impossible de lire l’état des intégrations.')))
      .finally(() => setLoading(false))
  }, [isSuperAdmin])

  useEffect(() => { load() }, [load])

  const sendTest = async () => {
    setSending(true)
    try {
      const { sentTo } = await settingsBackendApi.sendTestEmail()
      showToast(data?.mail.kind === 'MAILPIT'
        ? `E-mail de test envoyé à ${sentTo} : visible dans Mailpit (http://localhost:8025).`
        : `E-mail de test envoyé à ${sentTo}.`, 'success')
    } catch (err) {
      showToast(describeSettingsError(err, 'L’e-mail de test n’a pas pu être envoyé.'), 'danger')
    } finally {
      setSending(false)
    }
  }

  if (!isSuperAdmin) {
    return (
      <div className="pt-empty-state">
        <i className="bi bi-shield-lock" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }} aria-hidden="true"></i>
        <p>Cette page est réservée au Super administrateur.</p>
      </div>
    )
  }

  const mail = data?.mail
  const monitoring = data?.monitoring ?? []
  const down = monitoring.filter((m) => !m.reachable).length

  return (
    <div className={embedded ? undefined : 'pt-content'}>
      {!embedded && (
        <div className="pt-page-header">
          <div className="page-title">
            <h1>Intégrations</h1>
            <p>Serveur e-mail et monitoring des applications</p>
          </div>
          <TopBar searchPlaceholder="" />
        </div>
      )}

      {error && (
        <div className="pt-alert-banner danger mb-3" role="alert">
          <i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={load}>Réessayer</button>
        </div>
      )}

      {loading && !data ? (
        <div className="pt-empty-state">
          <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }} aria-hidden="true"></i>
          <p>Vérification des intégrations…</p>
        </div>
      ) : data && (
        <div className="row g-4">
          <div className="col-12 col-xl-6">
            <section className="pt-card h-100 st-card" aria-labelledby="st-mail-title">
              <div className="d-flex align-items-start justify-content-between gap-2 flex-wrap">
                <h2 className="st-card-title" id="st-mail-title"><i className="bi bi-envelope" aria-hidden="true"></i>Serveur e-mail</h2>
                {mail && (!mail.enabled ? <span className="st-status off">Désactivé</span>
                  : mail.reachable ? <span className="st-status ok"><i className="bi bi-check-circle-fill" aria-hidden="true"></i>Joignable</span>
                    : <span className="st-status ko"><i className="bi bi-x-circle-fill" aria-hidden="true"></i>Injoignable</span>)}
              </div>
              <p className="st-note mt-0">Utilisé par LoadPilot pour les notifications par e-mail.</p>
              {mail && (
                <dl className="st-facts">
                  <dt>Type</dt>
                  <dd>{mail.kind === 'MAILPIT' ? 'Mailpit (développement)' : 'Serveur SMTP réel'}</dd>
                  <dt>Serveur</dt>
                  <dd>{mail.host}:{mail.port}</dd>
                  <dt>Expéditeur</dt>
                  <dd>{mail.fromName ? `${mail.fromName} <${mail.from}>` : mail.from}</dd>
                  <dt>Authentification</dt>
                  <dd>{mail.authenticated ? 'Oui' : 'Non'}</dd>
                </dl>
              )}
              {mail?.message && <p className="st-note" role="status">{mail.message}</p>}
              <div className="st-actions">
                <button type="button" className="pt-btn-outline" onClick={sendTest} disabled={sending || !mail?.enabled}>
                  <i className="bi bi-send" aria-hidden="true"></i> {sending ? 'Envoi…' : 'Envoyer un e-mail de test'}
                </button>
              </div>
            </section>
          </div>

          <div className="col-12 col-xl-6">
            <section className="pt-card h-100 st-card" aria-labelledby="st-authmail-title">
              <h2 className="st-card-title" id="st-authmail-title"><i className="bi bi-shield-lock" aria-hidden="true"></i>E-mails du service de connexion</h2>
              <p className="st-note mt-0">Mot de passe oublié, lien pour choisir son mot de passe. Réglés sur le service de connexion.</p>
              {data.authenticationMail ? (
                <dl className="st-facts">
                  <dt>Serveur</dt>
                  <dd>{data.authenticationMail.host}{data.authenticationMail.port ? `:${data.authenticationMail.port}` : ''}</dd>
                  <dt>Expéditeur</dt>
                  <dd>{data.authenticationMail.from
                    ? (data.authenticationMail.fromName ? `${data.authenticationMail.fromName} <${data.authenticationMail.from}>` : data.authenticationMail.from)
                    : '—'}</dd>
                  <dt>Authentification</dt>
                  <dd>{data.authenticationMail.authenticated ? 'Oui' : 'Non'}</dd>
                </dl>
              ) : <p className="st-note">Non configuré, ou illisible pour le moment.</p>}
            </section>
          </div>

          <div className="col-12">
            <section className="pt-card st-card" aria-labelledby="st-monitoring-title">
              <div className="d-flex align-items-start justify-content-between gap-2 flex-wrap">
                <h2 className="st-card-title" id="st-monitoring-title"><i className="bi bi-cpu" aria-hidden="true"></i>Monitoring CPU / RAM</h2>
                <button type="button" className="pt-btn-ghost" onClick={load} disabled={loading}>
                  <i className={`bi bi-arrow-clockwise${loading ? ' pt-spin' : ''}`} aria-hidden="true"></i> Vérifier à nouveau
                </button>
              </div>
              <p className="st-note mt-0">
                {monitoring.length === 0 ? 'Aucune application n’a de monitoring configuré.'
                  : `${monitoring.length} application${monitoring.length > 1 ? 's configurées' : ' configurée'}`
                    + (down > 0 ? ` · ${down} ne répond${down > 1 ? 'ent' : ''} pas.` : ' · toutes répondent.')}
                {' '}Le monitoring se configure dans la fiche de chaque application.
              </p>
              {monitoring.length > 0 && (
                <ul className="st-monitoring">
                  {monitoring.map((m) => (
                    <li key={m.applicationId}>
                      <div className="st-monitoring-text">
                        <Link to={`/applications?q=${encodeURIComponent(m.applicationName)}`}>{m.applicationName}</Link>
                        <span className="st-muted">
                          {MONITORING_TYPE[m.type] ?? m.type}{m.endpoint ? ` · ${m.endpoint}` : ''}{m.archived ? ' · archivée' : ''}
                        </span>
                        {!m.reachable && m.message && <span className="st-muted">{m.message}</span>}
                      </div>
                      {m.reachable
                        ? <span className="st-status ok"><i className="bi bi-check-circle-fill" aria-hidden="true"></i>Répond</span>
                        : <span className="st-status ko"><i className="bi bi-x-circle-fill" aria-hidden="true"></i>Ne répond pas</span>}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        </div>
      )}
    </div>
  )
}

export default SettingsIntegrations
