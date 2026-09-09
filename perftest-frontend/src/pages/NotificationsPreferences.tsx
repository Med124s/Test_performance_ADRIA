import { useEffect, useState } from 'react'
import TopBar from '../components/TopBar'
import { loadJSON, saveJSON } from '../utils/persistentStore'

interface AlertItem {
  id: string
  name: string
  condition: string
  severity: 'Critique' | 'Élevée' | 'Moyenne' | 'Faible' | 'Info'
  channels: string[]
  enabled: boolean
  description: string
  evaluation: string
  persistence: string
}

function NotificationsPreferences() {
  const [activeTab, setActiveTab] = useState('Alertes')
  const [selectedAlertId, setSelectedAlertId] = useState<string>('2')
  const [schedule, setSchedule] = useState<'continuous' | 'business' | 'custom'>(() =>
    loadJSON('pt_notif_schedule', 'continuous')
  )
  const [lastModified, setLastModified] = useState('Aujourd\'hui à 11:20')
  const [showToast, setShowToast] = useState(false)

  // Notification Channels List
  const [channels] = useState([
    { name: 'Email', status: 'Active', active: true, icon: 'bi-envelope-fill', desc: 'Alertes par courriel direct (admin@perftest.io)' },
    { name: 'In-app', status: 'Active', active: true, icon: 'bi-bell-fill', desc: 'Notifications temps réel dans l\'interface web' },
    { name: 'Slack', status: 'Active', active: true, icon: 'bi-slack', desc: 'Canal #perftest-alerts sur l\'espace Slack' },
    { name: 'Teams', status: 'Désactivé', active: false, icon: 'bi-chat-square-text-fill', desc: 'Webhooks Microsoft Teams' },
    { name: 'Webhooks', status: 'Désactivé', active: false, icon: 'bi-diagram-3-fill', desc: 'Endpoints HTTP POST personnalisés' },
    { name: 'SMS', status: 'Désactivé', active: false, icon: 'bi-phone-fill', desc: 'SMS d\'urgence pour incidents critiques' },
  ])

  // Alert Conditions
  const [alerts, setAlerts] = useState<AlertItem[]>(() => loadJSON('pt_notif_alerts', [
    {
      id: '1',
      name: 'Échec exécution',
      condition: 'Taux échec > 5%',
      severity: 'Critique',
      channels: ['Email', 'Slack', 'In-app'],
      enabled: true,
      description: 'Déclenché immédiatement lorsqu\'un test enregistre un taux d\'échec global supérieur à 5%.',
      evaluation: 'Immédiate à chaque bloc de requêtes',
      persistence: 'Immédiat (1 cycle)',
    },
    {
      id: '2',
      name: 'Temps réponse élevé',
      condition: 'p95 > 2000 ms',
      severity: 'Élevée',
      channels: ['Email', 'Slack'],
      enabled: true,
      description: 'Déclenche une alerte lorsque le temps de réponse p95 dépasse le seuil tolérable de 2000 ms de manière continue.',
      evaluation: 'Toutes les 1 minute',
      persistence: '3 cycles consécutifs (3 minutes)',
    },
    {
      id: '3',
      name: 'Taux erreur élevé',
      condition: 'Erreurs > 1%',
      severity: 'Élevée',
      channels: ['Email', 'Slack'],
      enabled: true,
      description: 'Déclenché si les requêtes renvoient des codes HTTP 5xx supérieurs à 1%.',
      evaluation: 'Toutes les 2 minutes',
      persistence: '2 cycles consécutifs',
    },
    {
      id: '4',
      name: 'CPU élevé',
      condition: 'CPU > 85% (5m)',
      severity: 'Moyenne',
      channels: ['In-app', 'Slack'],
      enabled: true,
      description: 'Surveillance de la charge CPU sur les serveurs d\'application cibles pendant le test.',
      evaluation: 'Toutes les 5 minutes',
      persistence: '5 minutes continues',
    },
    {
      id: '5',
      name: 'Mémoire élevée',
      condition: 'RAM > 90%',
      severity: 'Moyenne',
      channels: ['In-app'],
      enabled: true,
      description: 'Alerte sur la saturation de la mémoire vive du serveur.',
      evaluation: 'Toutes les 5 minutes',
      persistence: '2 cycles',
    },
    {
      id: '6',
      name: 'Aucune exécution',
      condition: 'Aucune exec en 24h',
      severity: 'Faible',
      channels: ['Email'],
      enabled: false,
      description: 'Notifie si aucun test automatisé ou manuel n\'a été lancé durant les dernières 24 heures.',
      evaluation: 'Une fois par jour',
      persistence: '24 heures',
    },
    {
      id: '7',
      name: 'Maintenance',
      condition: 'Événement système',
      severity: 'Info',
      channels: ['In-app'],
      enabled: true,
      description: 'Informations de maintenance planifiée ou de mise à jour de la plateforme.',
      evaluation: 'Sur événement',
      persistence: 'Immédiat',
    },
  ]))

  // Persiste les alertes et le calendrier de silence à chaque changement.
  useEffect(() => {
    saveJSON('pt_notif_alerts', alerts)
  }, [alerts])
  useEffect(() => {
    saveJSON('pt_notif_schedule', schedule)
  }, [schedule])

  const tabs = ['Canaux', 'Alertes', 'Abonnements', 'Calendrier de silence', 'Modèles', 'Widgets', 'Historique']

  const selectedAlert = alerts.find((a) => a.id === selectedAlertId) || alerts[1]

  const toggleAlertStatus = (id: string) => {
    setAlerts((prev) =>
      prev.map((a) => (a.id === id ? { ...a, enabled: !a.enabled } : a))
    )
  }

  const handleSave = () => {
    const now = new Date()
    setLastModified(`Aujourd'hui à ${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`)
    setShowToast(true)
    setTimeout(() => setShowToast(false), 3000)
  }

  const getSeverityBadgeClass = (sev: string) => {
    switch (sev) {
      case 'Critique':
        return 'danger'
      case 'Élevée':
        return 'warning'
      case 'Moyenne':
        return 'info'
      case 'Faible':
        return 'neutral'
      case 'Info':
        return 'info'
      default:
        return 'neutral'
    }
  }

  const getChannelIcon = (ch: string) => {
    switch (ch) {
      case 'Email':
        return 'bi-envelope'
      case 'Slack':
        return 'bi-slack'
      case 'In-app':
        return 'bi-bell'
      case 'Teams':
        return 'bi-chat-square-text'
      case 'Webhooks':
        return 'bi-diagram-3'
      case 'SMS':
        return 'bi-phone'
      default:
        return 'bi-chat'
    }
  }

  return (
    <div className="pt-content">
      {/* Toast Notification */}
      {showToast && (
        <div className="position-fixed top-0 end-0 p-3" style={{ zIndex: 1080 }}>
          <div className="toast show align-items-center text-white bg-success border-0 shadow-lg" role="alert">
            <div className="d-flex">
              <div className="toast-body d-flex align-items-center gap-2">
                <i className="bi bi-check-circle-fill fs-5"></i>
                <span>Préférences de notifications enregistrées !</span>
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

      {/* Top Header */}
      <div className="d-flex align-items-center justify-content-between mb-3">
        <div>
          <h1 className="h4 fw-bold mb-1" style={{ color: 'var(--pt-text)' }}>
            Notifications — Préférences
          </h1>
          <p className="text-muted mb-0" style={{ fontSize: '13.5px' }}>
            Gérez vos préférences de notifications et d'alertes
          </p>
        </div>
        <TopBar searchPlaceholder="Rechercher une alerte..." />
      </div>

      {/* Navigation Tabs */}
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

      {/* SECTION 1: Canaux de notification */}
      <div className="pt-card mb-4">
        <div className="d-flex align-items-center justify-content-between mb-3">
          <div>
            <div className="pt-card-title mb-1">
              <i className="bi bi-broadcast me-2 text-primary fs-5"></i>
              Canaux de notification
            </div>
            <small className="text-muted" style={{ fontSize: '12.5px' }}>
              État des canaux de communication configurés pour l'envoi des alertes
            </small>
          </div>
          <span className="badge bg-success-subtle text-success">3 Canaux Actifs</span>
        </div>

        <div className="row g-3">
          {channels.map((ch, i) => (
            <div className="col-12 col-md-6 col-lg-4" key={i}>
              <div
                className="p-3 border rounded d-flex align-items-start gap-3 h-100"
                style={{ background: 'var(--pt-bg)' }}
              >
                <div
                  className="rounded-circle d-flex align-items-center justify-content-center flex-shrink-0"
                  style={{
                    width: '38px',
                    height: '38px',
                    background: ch.active ? 'var(--pt-primary-light)' : '#F3F4F6',
                    color: ch.active ? 'var(--pt-primary)' : 'var(--pt-text-muted)',
                  }}
                >
                  <i className={`bi ${ch.icon} fs-5`}></i>
                </div>
                <div className="flex-grow-1">
                  <div className="d-flex align-items-center justify-content-between mb-1">
                    <span className="fw-semibold text-dark" style={{ fontSize: '13.5px' }}>
                      {ch.name}
                    </span>
                    <span className={`pt-pill ${ch.active ? 'success' : 'neutral'}`} style={{ fontSize: '11px' }}>
                      {ch.status}
                    </span>
                  </div>
                  <div className="text-muted" style={{ fontSize: '12px' }}>
                    {ch.desc}
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>

        <div className="border-top pt-3 mt-3 text-end">
          <button className="pt-btn-outline" style={{ fontSize: '12.5px' }}>
            <i className="bi bi-gear-fill me-1"></i> Gérer les intégrations
          </button>
        </div>
      </div>

      {/* SECTION 2: Alertes - Conditions (Two-Column) */}
      <div className="row g-4 mb-4">
        {/* LEFT Table Column */}
        <div className="col-12 col-xl-7">
          <div className="pt-card h-100 d-flex flex-column justify-content-between">
            <div>
              <div className="d-flex align-items-center justify-content-between mb-3">
                <div className="pt-card-title mb-0">
                  <i className="bi bi-bell-exclamation-fill me-2 text-warning fs-5"></i>
                  Alertes - Conditions
                </div>
                <span className="text-muted" style={{ fontSize: '12px' }}>7 conditions définies</span>
              </div>

              <div className="pt-table-wrapper">
                <table className="pt-table">
                  <thead>
                    <tr>
                      <th>Nom alerte</th>
                      <th>Condition</th>
                      <th>Gravité</th>
                      <th>Canaux</th>
                      <th>Statut</th>
                      <th className="text-end">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {alerts.map((alt) => {
                      const isSelected = alt.id === selectedAlertId
                      return (
                        <tr
                          key={alt.id}
                          style={{
                            cursor: 'pointer',
                            background: isSelected ? 'var(--pt-primary-light)' : 'transparent',
                          }}
                          onClick={() => setSelectedAlertId(alt.id)}
                        >
                          <td className="fw-semibold text-dark">{alt.name}</td>
                          <td className="font-monospace" style={{ fontSize: '12.5px' }}>
                            {alt.condition}
                          </td>
                          <td>
                            <span className={`pt-pill ${getSeverityBadgeClass(alt.severity)}`} style={{ fontSize: '11px' }}>
                              {alt.severity}
                            </span>
                          </td>
                          <td>
                            <div className="d-flex align-items-center gap-1">
                              {alt.channels.map((ch, idx) => (
                                <i
                                  key={idx}
                                  className={`bi ${getChannelIcon(ch)} text-muted`}
                                  title={ch}
                                  style={{ fontSize: '14px' }}
                                ></i>
                              ))}
                            </div>
                          </td>
                          <td onClick={(e) => e.stopPropagation()}>
                            <label className="pt-toggle">
                              <input
                                type="checkbox"
                                checked={alt.enabled}
                                onChange={() => toggleAlertStatus(alt.id)}
                              />
                              <span className="toggle-slider"></span>
                            </label>
                          </td>
                          <td className="text-end" onClick={(e) => e.stopPropagation()}>
                            <button
                              className="btn btn-sm btn-link text-primary p-0 me-2"
                              title="Inspecter"
                              onClick={() => setSelectedAlertId(alt.id)}
                            >
                              <i className="bi bi-eye"></i>
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="border-top pt-3 mt-3">
              <button className="pt-btn-primary w-100 justify-content-center">
                <i className="bi bi-plus-lg me-1"></i> Ajouter une alerte
              </button>
            </div>
          </div>
        </div>

        {/* RIGHT Column: Detail of Selected Alert */}
        <div className="col-12 col-xl-5">
          <div className="pt-card h-100 d-flex flex-column justify-content-between">
            <div>
              <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2">
                <div className="pt-card-title mb-0">
                  <i className="bi bi-info-circle-fill me-2 text-primary fs-5"></i>
                  Détail de l'alerte
                </div>
                <span className={`pt-pill ${getSeverityBadgeClass(selectedAlert.severity)}`}>
                  {selectedAlert.severity}
                </span>
              </div>

              <h5 className="fw-bold mb-2 text-dark">{selectedAlert.name}</h5>
              <p className="text-muted mb-3" style={{ fontSize: '12.5px', lineHeight: '1.5' }}>
                {selectedAlert.description}
              </p>

              <div className="p-3 border rounded mb-3" style={{ background: 'var(--pt-bg)' }}>
                <div className="d-flex justify-content-between py-2 border-bottom">
                  <span className="text-muted" style={{ fontSize: '12.5px' }}>Condition de déclenchement</span>
                  <span className="fw-bold font-monospace text-danger" style={{ fontSize: '13px' }}>
                    {selectedAlert.condition}
                  </span>
                </div>
                <div className="d-flex justify-content-between py-2 border-bottom">
                  <span className="text-muted" style={{ fontSize: '12.5px' }}>Fréquence d'évaluation</span>
                  <span className="fw-semibold" style={{ fontSize: '12.5px' }}>{selectedAlert.evaluation}</span>
                </div>
                <div className="d-flex justify-content-between py-2 border-bottom">
                  <span className="text-muted" style={{ fontSize: '12.5px' }}>Règle de persistance</span>
                  <span className="fw-semibold" style={{ fontSize: '12.5px' }}>{selectedAlert.persistence}</span>
                </div>
                <div className="d-flex justify-content-between py-2 align-items-center">
                  <span className="text-muted" style={{ fontSize: '12.5px' }}>Canaux diffusés</span>
                  <div className="d-flex gap-1">
                    {selectedAlert.channels.map((ch, idx) => (
                      <span key={idx} className="badge bg-light text-dark border" style={{ fontSize: '11px' }}>
                        <i className={`bi ${getChannelIcon(ch)} me-1 text-primary`}></i>
                        {ch}
                      </span>
                    ))}
                  </div>
                </div>
              </div>

              <div className="d-flex align-items-center justify-content-between p-3 border rounded mb-3">
                <span className="fw-semibold" style={{ fontSize: '13px' }}>Statut de la règle</span>
                <div className="d-flex align-items-center gap-2">
                  <span className={`pt-pill ${selectedAlert.enabled ? 'success' : 'neutral'}`}>
                    {selectedAlert.enabled ? 'Actif' : 'Inactif'}
                  </span>
                  <label className="pt-toggle">
                    <input
                      type="checkbox"
                      checked={selectedAlert.enabled}
                      onChange={() => toggleAlertStatus(selectedAlert.id)}
                    />
                    <span className="toggle-slider"></span>
                  </label>
                </div>
              </div>
            </div>

            <div className="d-flex align-items-center justify-content-end gap-2 border-top pt-3">
              <button className="pt-btn-outline">
                <i className="bi bi-pencil-fill me-1"></i> Modifier
              </button>
              <button className="pt-btn-outline">
                <i className="bi bi-files me-1"></i> Dupliquer
              </button>
              <button className="btn btn-outline-danger btn-sm" style={{ padding: '0.5rem 0.85rem', fontSize: '13px' }}>
                <i className="bi bi-trash-fill me-1"></i> Supprimer
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* SECTION 3: Bottom 3 Cards (Silence, Schedule, Summary) */}
      <div className="row g-4 mb-4">
        {/* Card 1: Calendrier de silence */}
        <div className="col-12 col-md-6 col-xl-4">
          <div className="pt-card h-100 d-flex flex-column justify-content-between">
            <div>
              <div className="pt-card-title mb-3">
                <i className="bi bi-bell-slash-fill me-2 text-secondary fs-5"></i>
                Calendrier de silence
              </div>
              <div className="text-center py-4 my-2 border rounded border-dashed" style={{ background: 'var(--pt-bg)' }}>
                <i className="bi bi-calendar-x text-muted display-6 mb-2 d-block"></i>
                <div className="fw-semibold text-dark mb-1" style={{ fontSize: '13.5px' }}>
                  Aucune plage de silence active
                </div>
                <small className="text-muted px-3 d-block" style={{ fontSize: '12px' }}>
                  Suspendez temporairement les alertes pendant les fenêtres de maintenance planifiées.
                </small>
              </div>
            </div>

            <button className="pt-btn-outline w-100 justify-content-center mt-3">
              <i className="bi bi-plus-lg me-1"></i> Ajouter une plage de silence
            </button>
          </div>
        </div>

        {/* Card 2: Planification */}
        <div className="col-12 col-md-6 col-xl-4">
          <div className="pt-card h-100 d-flex flex-column justify-content-between">
            <div>
              <div className="pt-card-title mb-3">
                <i className="bi bi-calendar-week-fill me-2 text-primary fs-5"></i>
                Planification des réceptions
              </div>
              <p className="text-muted" style={{ fontSize: '12px' }}>
                Choisissez quand vous souhaitez recevoir les notifications non critiques.
              </p>

              <div className="d-flex flex-column gap-3 mt-3">
                <div
                  className="p-3 border rounded d-flex align-items-center gap-3 cursor-pointer"
                  style={{
                    background: schedule === 'continuous' ? 'var(--pt-primary-light)' : 'var(--pt-bg)',
                    borderColor: schedule === 'continuous' ? 'var(--pt-primary)' : 'var(--pt-border)',
                  }}
                  onClick={() => setSchedule('continuous')}
                >
                  <input
                    type="radio"
                    name="schedule"
                    checked={schedule === 'continuous'}
                    onChange={() => setSchedule('continuous')}
                  />
                  <div>
                    <div className="fw-semibold text-dark" style={{ fontSize: '13.5px' }}>En continu (24h/24, 7j/7)</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Réception instantanée de toutes les alertes</small>
                  </div>
                </div>

                <div
                  className="p-3 border rounded d-flex align-items-center gap-3 cursor-pointer"
                  style={{
                    background: schedule === 'business' ? 'var(--pt-primary-light)' : 'var(--pt-bg)',
                    borderColor: schedule === 'business' ? 'var(--pt-primary)' : 'var(--pt-border)',
                  }}
                  onClick={() => setSchedule('business')}
                >
                  <input
                    type="radio"
                    name="schedule"
                    checked={schedule === 'business'}
                    onChange={() => setSchedule('business')}
                  />
                  <div>
                    <div className="fw-semibold text-dark" style={{ fontSize: '13.5px' }}>Heures ouvrables uniquement</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Lun-Ven de 08:00 à 18:00 (GMT+1)</small>
                  </div>
                </div>

                <div
                  className="p-3 border rounded d-flex align-items-center gap-3 cursor-pointer"
                  style={{
                    background: schedule === 'custom' ? 'var(--pt-primary-light)' : 'var(--pt-bg)',
                    borderColor: schedule === 'custom' ? 'var(--pt-primary)' : 'var(--pt-border)',
                  }}
                  onClick={() => setSchedule('custom')}
                >
                  <input
                    type="radio"
                    name="schedule"
                    checked={schedule === 'custom'}
                    onChange={() => setSchedule('custom')}
                  />
                  <div>
                    <div className="fw-semibold text-dark" style={{ fontSize: '13.5px' }}>Plage personnalisée</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Définir des créneaux sur mesure par canal</small>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Card 3: Récapitulatif */}
        <div className="col-12 col-xl-4">
          <div className="pt-card h-100 d-flex flex-column justify-content-between">
            <div>
              <div className="pt-card-title mb-3">
                <i className="bi bi-pie-chart-fill me-2 text-success fs-5"></i>
                Récapitulatif de l'activité
              </div>

              <div className="row g-2">
                <div className="col-6">
                  <div className="p-3 border rounded text-center" style={{ background: 'var(--pt-bg)' }}>
                    <div className="fw-bold text-dark fs-4">7</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Alertes définies</small>
                  </div>
                </div>
                <div className="col-6">
                  <div className="p-3 border rounded text-center" style={{ background: 'var(--pt-bg)' }}>
                    <div className="fw-bold text-primary fs-4">3</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Canaux actifs</small>
                  </div>
                </div>
                <div className="col-6">
                  <div className="p-3 border rounded text-center" style={{ background: 'var(--pt-bg)' }}>
                    <div className="fw-bold text-dark fs-4">0</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Période silence</small>
                  </div>
                </div>
                <div className="col-6">
                  <div className="p-3 border rounded text-center" style={{ background: 'var(--pt-bg)' }}>
                    <div className="fw-bold text-success fs-4">12</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Notifications (24h)</small>
                  </div>
                </div>
                <div className="col-6">
                  <div className="p-3 border rounded text-center" style={{ background: 'var(--pt-bg)' }}>
                    <div className="fw-bold text-success fs-4">98.5%</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Taux livraison</small>
                  </div>
                </div>
                <div className="col-6">
                  <div className="p-3 border rounded text-center" style={{ background: 'var(--pt-bg)' }}>
                    <div className="fw-bold text-info fs-4">1.2 min</div>
                    <small className="text-muted" style={{ fontSize: '11.5px' }}>Délai moyen</small>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Footer Bar */}
      <div
        className="pt-card d-flex flex-wrap align-items-center justify-content-between p-3 gap-3"
        style={{
          borderTop: '2px solid var(--pt-border)',
          background: 'var(--pt-card-bg)',
        }}
      >
        <div className="d-flex align-items-center gap-3 text-muted" style={{ fontSize: '12.5px' }}>
          <span>
            <i className="bi bi-globe me-1 text-primary"></i>
            Fuseau horaire: <strong>Africa/Casablanca (GMT+1)</strong>
          </span>
          <span>•</span>
          <span>
            <i className="bi bi-clock me-1 text-secondary"></i>
            Dernière modification: <strong>{lastModified}</strong>
          </span>
        </div>
        <button className="pt-btn-primary px-4 py-2" onClick={handleSave}>
          <i className="bi bi-floppy-fill me-1"></i> Enregistrer les modifications
        </button>
      </div>
    </div>
  )
}

export default NotificationsPreferences
