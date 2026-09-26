








































































































































































































































































































































import TopBar from '../components/TopBar'
import { useDarkMode } from '../utils/useDarkMode'

// ============================================================
// P1-D — NETTOYAGE CIBLÉ (voir rapport P1-D, section "Settings", pour
// l'analyse complète). Retiré entièrement, car 100% fictif et sans aucun
// effet réel sur l'application :
//   - Carte "Préférences système" (mode maintenance/génération de données
//     de test/debug verbose/timeout/upload/VUs max) : aucun de ces
//     contrôles n'était lu par quoi que ce soit, ni persisté côté backend.
//     Les VRAIES limites opérationnelles existent (voir /configurations,
//     lecture seule, SUPER_ADMIN) mais ne sont pas éditables ici.
//   - Carte "Général" (nom d'organisation/email de contact/fuseau horaire
//     par défaut) : aucun équivalent backend ; le fuseau horaire RÉEL est
//     désormais sur /settings (par utilisateur, réellement persisté).
//   - Carte "Sécurité" (2FA obligatoire, expiration de session) : LoadPilot
//     délègue entièrement l'authentification à Keycloak — ces réglages ne
//     peuvent pas être honorés depuis ce frontend (conservé uniquement :
//     la mention honnête "Authentification : Keycloak (OIDC)").
//   - Carte "Actions rapides" (export JSON / vider le cache / réinitialiser)
//     : 3 boutons sans aucun gestionnaire, opérant sur des données qui
//     n'existent plus après ce nettoyage.
//   - Sélecteur de thème/couleurs/densité : jamais appliqué à aucun style
//     réel (vérifié : `primaryColor`/`secondaryColor`/`density` n'étaient
//     utilisés que dans l'aperçu de CETTE carte, jamais sur `document`).
//   - Barre d'onglets (General/Securite/.../Sauvegarde) : décorative,
//     aucun contenu ne changeait selon l'onglet sélectionné.
//
// Conservé et rendu RÉEL : la bascule clair/sombre (déjà fonctionnelle
// avant cette phase, mais non persistée — voir utils/useDarkMode.ts, un
// vrai bug corrigé ici) et l'aperçu honnête des intégrations tierces (dont
// aucune n'est réellement implémentée à ce jour).
// ============================================================

const integrations = [
  { name: 'Slack', icon: 'bi-slack', desc: 'Alertes en canal et notifications temps réel' },
  { name: 'Microsoft Teams', icon: 'bi-chat-left-dots-fill', desc: 'Webhooks de notification pour les équipes' },
  { name: 'Webhooks HTTP', icon: 'bi-diagram-3-fill', desc: 'Déclencheurs personnalisés vers des services tiers' },
  { name: 'Jira Software', icon: 'bi-kanban-fill', desc: 'Création automatique de tickets sur anomalie de test' },
  { name: 'PagerDuty', icon: 'bi-bell-fill', desc: "Acheminement des incidents critiques" },
]

function SettingsIntegrations() {
  const { darkMode, setDarkMode } = useDarkMode()

  return (
    <div className="pt-content">
      <div className="d-flex align-items-center justify-content-between mb-4">
        <div>
          <h1 className="h4 fw-bold mb-1" style={{ color: 'var(--pt-text)' }}>Intégrations</h1>
          <p className="text-muted mb-0" style={{ fontSize: '13.5px' }}>
            État réel des intégrations tierces et de l'apparence
          </p>
        </div>
        <TopBar searchPlaceholder="" />
      </div>

      <div className="row g-4">
        <div className="col-12 col-xl-7">
          <div className="pt-card h-100">
            <div className="d-flex align-items-center justify-content-between mb-3">
              <div className="pt-card-title mb-0">
                <i className="bi bi-plug-fill me-2 text-primary fs-5"></i>
                Intégrations tierces
              </div>
              <span className="pt-pill neutral">Aucune active</span>
            </div>
            <div className="pt-alert-banner info mb-3" style={{ fontSize: '12.5px' }}>
              <i className="bi bi-info-circle-fill"></i>
              Aucune intégration tierce n'est actuellement implémentée dans LoadPilot — les notifications restent
              in-app uniquement (voir <a href="/notifications/preferences">Préférences de notifications</a>). Cette
              liste est indicative d'un possible développement futur.
            </div>
            <div className="d-flex flex-column gap-2">
              {integrations.map((item) => (
                <div key={item.name} className="p-3 border rounded d-flex align-items-center gap-3" style={{ background: 'var(--pt-bg)' }}>
                  <div className="rounded-circle d-flex align-items-center justify-content-center" style={{ width: '38px', height: '38px', background: '#F3F4F6', color: 'var(--pt-text-muted)' }}>
                    <i className={`bi ${item.icon} fs-6`}></i>
                  </div>
                  <div>
                    <div className="d-flex align-items-center gap-2">
                      <span className="fw-semibold text-dark" style={{ fontSize: '13.5px' }}>{item.name}</span>
                      <span className="pt-pill neutral" style={{ fontSize: '10.5px' }}>Non implémenté</span>
                    </div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>{item.desc}</small>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="col-12 col-xl-5">
          <div className="d-flex flex-column gap-4 h-100">
            <div className="pt-card">
              <div className="pt-card-title mb-3">
                <i className="bi bi-moon-stars-fill me-2 text-primary fs-5"></i>
                Apparence
              </div>
              <div className="d-flex align-items-center justify-content-between">
                <span style={{ fontSize: '13.5px' }}>Mode sombre</span>
                <label className="pt-toggle">
                  <input type="checkbox" checked={darkMode} onChange={(e) => setDarkMode(e.target.checked)} />
                  <span className="toggle-slider"></span>
                </label>
              </div>
            </div>

            <div className="pt-card">
              <div className="pt-card-title mb-3">
                <i className="bi bi-shield-lock-fill me-2 text-primary fs-5"></i>
                Authentification
              </div>
              {/* Phase 38 — l'authentification réelle du produit est Keycloak
                  (OIDC, Authorization Code + PKCE S256) ; aucune intégration
                  SAML/Okta distincte n'existe, aucune 2FA/expiration de
                  session pilotable depuis ce frontend (géré côté royaume
                  Keycloak). */}
              <span className="pt-pill info w-100 justify-content-center">
                <i className="bi bi-shield-check me-1"></i> Keycloak (OIDC)
              </span>
              <p className="text-muted mt-2 mb-0" style={{ fontSize: '11.5px' }}>
                2FA et durée de session sont configurées dans le royaume Keycloak, pas ici.
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

export default SettingsIntegrations
