import { useState } from 'react'
import TopBar from '../components/TopBar'
import { loadJSON, saveJSON } from '../utils/persistentStore'

interface IntegrationsPageSettings {
  systemPrefs: { maintenance: boolean; testData: boolean; debugMode: boolean; timeout: number; maxUpload: number; maxUsers: number }
  theme: string
  primaryColor: string
  secondaryColor: string
  density: string
  orgName: string
  contactEmail: string
  twoFactorEnforced: boolean
}

const defaultIntegrationsSettings: IntegrationsPageSettings = {
  systemPrefs: { maintenance: false, testData: true, debugMode: false, timeout: 60, maxUpload: 50, maxUsers: 10000 },
  theme: 'Clair',
  primaryColor: '#4F46E5',
  secondaryColor: '#4338CA',
  density: 'Confortable',
  orgName: 'PERFTEST Corp',
  contactEmail: 'admin@perftest.io',
  twoFactorEnforced: true,
}

function SettingsIntegrations() {
  const [activeTab, setActiveTab] = useState('Integrations')
  const [savedTime, setSavedTime] = useState('26/05/2025 09:30:15')
  const [showToast, setShowToast] = useState(false)

  // Toutes les préférences de cette page sont rechargées depuis le
  // stockage persistant au montage, et ré-enregistrées ensemble au clic
  // sur "Enregistrer".
  const saved = loadJSON('pt_settings_integrations_page', defaultIntegrationsSettings)

  // Form state for System Preferences
  const [systemPrefs, setSystemPreferences] = useState(saved.systemPrefs)

  // Form state for Appearance
  const [theme, setTheme] = useState(saved.theme)
  const [primaryColor, setPrimaryColor] = useState(saved.primaryColor)
  const [secondaryColor, setSecondaryColor] = useState(saved.secondaryColor)
  const [density, setDensity] = useState(saved.density)

  // Form state for General & Security
  const [orgName, setOrgName] = useState(saved.orgName)
  const [contactEmail, setContactEmail] = useState(saved.contactEmail)
  const [twoFactorEnforced, setTwoFactorEnforced] = useState(saved.twoFactorEnforced)

  // Integrations list
  const integrations = [
    { name: 'Slack', status: 'Connecté', connected: true, icon: 'bi-slack', desc: 'Alertes en canal et notifications temps réel' },
    { name: 'Microsoft Teams', status: 'Connecté', connected: true, icon: 'bi-chat-left-dots-fill', desc: 'Webhooks de notification pour les équipes' },
    { name: 'Webhooks HTTP', status: 'Non connecté', connected: false, icon: 'bi-diagram-3-fill', desc: 'Déclencheurs personnalisés vers des services tiers' },
    { name: 'Jira Software', status: 'Connecté', connected: true, icon: 'bi-kanban-fill', desc: 'Création automatique de tickets sur anomalie de test' },
    { name: 'PagerDuty', status: 'Non connecté', connected: false, icon: 'bi-bell-fill', desc: 'Acheminement des incidents critiques aux d’astreinte' },
  ]

  const tabs = ['General', 'Securite', 'Notifications', 'Integrations', 'Stockage', 'Apparence', 'Systeme', 'Sauvegarde']

  const handleSave = () => {
    saveJSON('pt_settings_integrations_page', {
      systemPrefs, theme, primaryColor, secondaryColor, density, orgName, contactEmail, twoFactorEnforced,
    })
    const now = new Date()
    const formatted = now.toLocaleDateString('fr-FR') + ' ' + now.toLocaleTimeString('fr-FR')
    setSavedTime(formatted)
    setShowToast(true)
    setTimeout(() => setShowToast(false), 3000)
  }

  return (
    <div className="pt-content">
      {/* Toast Alert */}
      {showToast && (
        <div
          className="position-fixed top-0 end-0 p-3"
          style={{ zIndex: 1080 }}
        >
          <div className="toast show align-items-center text-white bg-success border-0 shadow-lg" role="alert">
            <div className="d-flex">
              <div className="toast-body d-flex align-items-center gap-2">
                <i className="bi bi-check-circle-fill fs-5"></i>
                <span>Configuration sauvegardée avec succès !</span>
              </div>
              <button
                type="button"
                className="btn-close btn-close-white me-2 m-auto"
                onClick={() => setShowToast(false)}
              ></button>
            </div>
          </div>
        </div>
      )}

      {/* TopBar Header */}
      <div className="d-flex align-items-center justify-content-between mb-3">
        <div>
          <h1 className="h4 fw-bold mb-1" style={{ color: 'var(--pt-text)' }}>
            Settings
          </h1>
          <p className="text-muted mb-0" style={{ fontSize: '13.5px' }}>
            Personnalisez et configurez votre plateforme Cadence
          </p>
        </div>
        <TopBar searchPlaceholder="Rechercher une configuration..." />
      </div>

      {/* Settings Navigation Tabs */}
      <div className="border-bottom mb-4">
        <ul className="nav nav-tabs border-0" style={{ gap: '0.5rem' }}>
          {tabs.map((tab) => (
            <li className="nav-item" key={tab}>
              <button
                className={`nav-link border-0 fw-medium px-3 py-2 ${
                  activeTab === tab ? 'active border-bottom border-primary text-primary fw-semibold' : 'text-muted'
                }`}
                style={{
                  background: 'transparent',
                  borderBottom: activeTab === tab ? '3px solid var(--pt-primary)' : 'none',
                  borderRadius: 0,
                  fontSize: '13.5px',
                }}
                onClick={() => setActiveTab(tab)}
              >
                {tab}
              </button>
            </li>
          ))}
        </ul>
      </div>

      {/* Main Grid Layout of Settings Cards */}
      <div className="row g-4 mb-4">
        {/* Card 1: Integrations */}
        <div className="col-12 col-xl-6">
          <div className="pt-card h-100 d-flex flex-column justify-content-between">
            <div>
              <div className="d-flex align-items-center justify-content-between mb-3">
                <div className="pt-card-title mb-0">
                  <i className="bi bi-plug-fill me-2 text-primary fs-5"></i>
                  Intégrations
                </div>
                <span className="badge bg-primary-subtle text-primary">3 / 5 Actives</span>
              </div>
              <p className="text-muted" style={{ fontSize: '12.5px' }}>
                Connectez Cadence à vos outils de communication, gestion de tickets et monitoring.
              </p>

              <div className="d-flex flex-column gap-3 mt-3">
                {integrations.map((item, idx) => (
                  <div
                    key={idx}
                    className="p-3 border rounded d-flex align-items-center justify-content-between"
                    style={{ background: 'var(--pt-bg)' }}
                  >
                    <div className="d-flex align-items-center gap-3">
                      <div
                        className="rounded-circle d-flex align-items-center justify-content-center"
                        style={{
                          width: '40px',
                          height: '40px',
                          background: item.connected ? 'var(--pt-primary-light)' : '#F3F4F6',
                          color: item.connected ? 'var(--pt-primary)' : 'var(--pt-text-muted)',
                        }}
                      >
                        <i className={`bi ${item.icon} fs-5`}></i>
                      </div>
                      <div>
                        <div className="d-flex align-items-center gap-2">
                          <span className="fw-semibold text-dark" style={{ fontSize: '14px' }}>
                            {item.name}
                          </span>
                          <span className={`pt-pill ${item.connected ? 'success' : 'neutral'}`} style={{ fontSize: '11px' }}>
                            {item.status}
                          </span>
                        </div>
                        <small className="text-muted" style={{ fontSize: '12px' }}>
                          {item.desc}
                        </small>
                      </div>
                    </div>
                    <button className="pt-btn-outline" style={{ padding: '0.3rem 0.75rem', fontSize: '12.5px' }}>
                      Configurer
                    </button>
                  </div>
                ))}
              </div>
            </div>

            <div className="border-top pt-3 mt-4 text-center">
              <button className="pt-btn-outline w-100 justify-content-center">
                <i className="bi bi-grid-3x3-gap-fill me-1"></i> Voir toutes les intégrations
              </button>
            </div>
          </div>
        </div>

        {/* Card 2: Apparence */}
        <div className="col-12 col-md-6 col-xl-3">
          <div className="pt-card h-100 d-flex flex-column justify-content-between">
            <div>
              <div className="pt-card-title mb-3">
                <i className="bi bi-palette-fill me-2 text-primary fs-5"></i>
                Apparence
              </div>

              {/* Theme select */}
              <div className="mb-3">
                <label className="pt-form-label">Thème d'interface</label>
                <select
                  className="pt-form-control"
                  value={theme}
                  onChange={(e) => setTheme(e.target.value)}
                >
                  <option value="Clair">Clair (Défaut)</option>
                  <option value="Sombre">Sombre (Dark)</option>
                  <option value="Automatique">Système (Auto)</option>
                </select>
              </div>

              {/* Primary Color Picker */}
              <div className="mb-3">
                <label className="pt-form-label">Couleur principale</label>
                <div className="d-flex align-items-center gap-2">
                  <input
                    type="color"
                    className="form-control form-control-color"
                    value={primaryColor}
                    onChange={(e) => setPrimaryColor(e.target.value)}
                    style={{ width: '42px', height: '38px', padding: '0.2rem', cursor: 'pointer' }}
                  />
                  <input
                    type="text"
                    className="pt-form-control font-monospace"
                    value={primaryColor}
                    onChange={(e) => setPrimaryColor(e.target.value)}
                  />
                </div>
              </div>

              {/* Secondary Color Picker */}
              <div className="mb-3">
                <label className="pt-form-label">Couleur secondaire</label>
                <div className="d-flex align-items-center gap-2">
                  <input
                    type="color"
                    className="form-control form-control-color"
                    value={secondaryColor}
                    onChange={(e) => setSecondaryColor(e.target.value)}
                    style={{ width: '42px', height: '38px', padding: '0.2rem', cursor: 'pointer' }}
                  />
                  <input
                    type="text"
                    className="pt-form-control font-monospace"
                    value={secondaryColor}
                    onChange={(e) => setSecondaryColor(e.target.value)}
                  />
                </div>
              </div>

              {/* Density */}
              <div className="mb-3">
                <label className="pt-form-label">Interface density</label>
                <select
                  className="pt-form-control"
                  value={density}
                  onChange={(e) => setDensity(e.target.value)}
                >
                  <option value="Confortable">Confortable (Recommandé)</option>
                  <option value="Compact">Compact (Haute densité)</option>
                </select>
              </div>

              {/* Preview Mockup Box */}
              <div className="mt-3">
                <label className="pt-form-label">Aperçu du style</label>
                <div
                  className="p-3 rounded border text-center"
                  style={{
                    background: theme === 'Sombre' ? '#111827' : '#F9FAFB',
                    borderColor: 'var(--pt-border)',
                  }}
                >
                  <div className="d-flex align-items-center justify-content-center gap-2 mb-2">
                    <span
                      className="badge px-3 py-2 text-white"
                      style={{ background: primaryColor }}
                    >
                      Bouton Primaire
                    </span>
                    <span
                      className="badge px-3 py-2 text-white"
                      style={{ background: secondaryColor }}
                    >
                      Bouton Secondaire
                    </span>
                  </div>
                  <small className="text-muted" style={{ fontSize: '11px' }}>
                    Aperçu thème {theme} • Mode {density}
                  </small>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Card 3: Preferences système */}
        <div className="col-12 col-md-6 col-xl-3">
          <div className="pt-card h-100 d-flex flex-column justify-content-between">
            <div>
              <div className="pt-card-title mb-3">
                <i className="bi bi-sliders me-2 text-primary fs-5"></i>
                Préférences système
              </div>

              {/* Toggles */}
              <div className="d-flex flex-column gap-3 mb-4">
                <div className="d-flex align-items-center justify-content-between">
                  <div>
                    <div className="fw-semibold" style={{ fontSize: '13px' }}>Mode Maintenance</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Bloque les exécutions</small>
                  </div>
                  <label className="pt-toggle">
                    <input
                      type="checkbox"
                      checked={systemPrefs.maintenance}
                      onChange={(e) => setSystemPreferences({ ...systemPrefs, maintenance: e.target.checked })}
                    />
                    <span className="toggle-slider"></span>
                  </label>
                </div>

                <div className="d-flex align-items-center justify-content-between">
                  <div>
                    <div className="fw-semibold" style={{ fontSize: '13px' }}>Génération Test Data</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Données synthétiques</small>
                  </div>
                  <label className="pt-toggle">
                    <input
                      type="checkbox"
                      checked={systemPrefs.testData}
                      onChange={(e) => setSystemPreferences({ ...systemPrefs, testData: e.target.checked })}
                    />
                    <span className="toggle-slider"></span>
                  </label>
                </div>

                <div className="d-flex align-items-center justify-content-between">
                  <div>
                    <div className="fw-semibold" style={{ fontSize: '13px' }}>Mode Debug Verbose</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Logs détaillés</small>
                  </div>
                  <label className="pt-toggle">
                    <input
                      type="checkbox"
                      checked={systemPrefs.debugMode}
                      onChange={(e) => setSystemPreferences({ ...systemPrefs, debugMode: e.target.checked })}
                    />
                    <span className="toggle-slider"></span>
                  </label>
                </div>
              </div>

              {/* Inputs */}
              <div className="mb-3">
                <label className="pt-form-label">Timeout d'exécution (sec)</label>
                <input
                  type="number"
                  className="pt-form-control"
                  value={systemPrefs.timeout}
                  onChange={(e) => setSystemPreferences({ ...systemPrefs, timeout: Number(e.target.value) })}
                />
              </div>

              <div className="mb-3">
                <label className="pt-form-label">Taille max upload (MB)</label>
                <input
                  type="number"
                  className="pt-form-control"
                  value={systemPrefs.maxUpload}
                  onChange={(e) => setSystemPreferences({ ...systemPrefs, maxUpload: Number(e.target.value) })}
                />
              </div>

              <div className="mb-3">
                <label className="pt-form-label">Utilisateurs virtuels max (VUs)</label>
                <input
                  type="number"
                  className="pt-form-control"
                  value={systemPrefs.maxUsers}
                  onChange={(e) => setSystemPreferences({ ...systemPrefs, maxUsers: Number(e.target.value) })}
                />
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Additional Settings Cards: General, Securite, Actions Rapides, Besoin d'aide */}
      <div className="row g-4 mb-4">
        {/* Card General */}
        <div className="col-12 col-md-6 col-xl-3">
          <div className="pt-card h-100">
            <div className="pt-card-title mb-3">
              <i className="bi bi-building me-2 text-primary fs-5"></i>
              Général
            </div>
            <div className="mb-3">
              <label className="pt-form-label">Nom de l'organisation</label>
              <input
                type="text"
                className="pt-form-control"
                value={orgName}
                onChange={(e) => setOrgName(e.target.value)}
              />
            </div>
            <div className="mb-3">
              <label className="pt-form-label">Email de contact</label>
              <input
                type="email"
                className="pt-form-control"
                value={contactEmail}
                onChange={(e) => setContactEmail(e.target.value)}
              />
            </div>
            <div>
              <label className="pt-form-label">Fuseau horaire par défaut</label>
              <select className="pt-form-control" defaultValue="Africa/Casablanca">
                <option value="Africa/Casablanca">Africa/Casablanca (GMT+1)</option>
                <option value="Europe/Paris">Europe/Paris (GMT+2)</option>
                <option value="UTC">UTC (GMT+0)</option>
              </select>
            </div>
          </div>
        </div>

        {/* Card Securite */}
        <div className="col-12 col-md-6 col-xl-3">
          <div className="pt-card h-100">
            <div className="pt-card-title mb-3">
              <i className="bi bi-shield-lock-fill me-2 text-primary fs-5"></i>
              Sécurité
            </div>
            <div className="d-flex align-items-center justify-content-between mb-3">
              <div>
                <div className="fw-semibold" style={{ fontSize: '13px' }}>2FA Obligatoire</div>
                <small className="text-muted" style={{ fontSize: '11.5px' }}>Pour tous les membres</small>
              </div>
              <label className="pt-toggle">
                <input
                  type="checkbox"
                  checked={twoFactorEnforced}
                  onChange={(e) => setTwoFactorEnforced(e.target.checked)}
                />
                <span className="toggle-slider"></span>
              </label>
            </div>
            <div className="mb-3">
              <label className="pt-form-label">Expiration session (minutes)</label>
              <input type="number" className="pt-form-control" defaultValue={30} />
            </div>
            <div>
              <label className="pt-form-label">SSO Single Sign-On</label>
              <span className="pt-pill success w-100 justify-content-center">
                <i className="bi bi-check-circle-fill"></i> SAML 2.0 / Okta Actif
              </span>
            </div>
          </div>
        </div>

        {/* Card Actions Rapides */}
        <div className="col-12 col-md-6 col-xl-3">
          <div className="pt-card h-100">
            <div className="pt-card-title mb-3">
              <i className="bi bi-lightning-charge-fill me-2 text-warning fs-5"></i>
              Actions Rapides
            </div>
            <div className="d-flex flex-column gap-2">
              <button className="pt-btn-outline w-100 justify-content-start">
                <i className="bi bi-download me-2"></i> Exporter la configuration JSON
              </button>
              <button className="pt-btn-outline w-100 justify-content-start">
                <i className="bi bi-trash me-2 text-danger"></i> Vider le cache du système
              </button>
              <button className="pt-btn-outline w-100 justify-content-start text-warning">
                <i className="bi bi-arrow-counterclockwise me-2"></i> Réinitialiser les paramètres
              </button>
            </div>
          </div>
        </div>

        {/* Card Besoin d'aide */}
        <div className="col-12 col-md-6 col-xl-3">
          <div className="pt-card h-100 bg-primary-subtle border-primary-subtle">
            <div className="pt-card-title text-primary mb-2">
              <i className="bi bi-question-circle-fill me-2 fs-5"></i>
              Besoin d'aide ?
            </div>
            <p className="text-muted" style={{ fontSize: '12.5px' }}>
              Consultez notre centre de documentation ou contactez le support technique 24/7.
            </p>
            <div className="d-flex flex-column gap-2 mt-3">
              <a href="#docs" className="btn btn-sm btn-outline-primary text-start">
                <i className="bi bi-book me-2"></i> Documentation API
              </a>
              <a href="#support" className="btn btn-sm btn-primary text-start">
                <i className="bi bi-headset me-2"></i> Contacter le Support
              </a>
            </div>
          </div>
        </div>
      </div>

      {/* Sticky / Fixed Footer Bar */}
      <div
        className="pt-card d-flex align-items-center justify-content-between p-3"
        style={{
          borderTop: '2px solid var(--pt-border)',
          background: 'var(--pt-card-bg)',
        }}
      >
        <div className="d-flex align-items-center gap-2 text-muted" style={{ fontSize: '13px' }}>
          <i className="bi bi-clock-history text-primary"></i>
          <span>Dernière sauvegarde: <strong>{savedTime}</strong></span>
        </div>
        <button className="pt-btn-primary px-4 py-2" onClick={handleSave}>
          <i className="bi bi-floppy-fill me-1"></i> Sauvegarder maintenant
        </button>
      </div>
    </div>
  )
}

export default SettingsIntegrations
