import { useEffect, useId, useState } from 'react'
import { Link } from 'react-router-dom'
import { useToast } from '../../context/ToastContext'
import { auditBackendApi } from '../../services/api/auditBackend'
import { ApiError } from '../../services/api/httpClient'
import type { BackendAuditSettings, BackendStorageInfo } from '../../types/backendContracts'
import StorageStatus from '../settings/StorageStatus'

// Réglages du journal d'audit (Paramètres > Système, Super administrateur) :
//  - journaliser aussi les consultations (désactivé par défaut) ;
//  - durée de conservation (365 jours par défaut, purge automatique chaque nuit).
// Chaque modification est elle-même inscrite au journal par le serveur.

export const RETENTION_MIN = 30
export const RETENTION_MAX = 3650

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'Réservé au Super administrateur.'
    if (err.status === 0 || err.status >= 500) return 'Le serveur LoadPilot ne répond pas. Réessayez dans un instant.'
    return err.message
  }
  return 'Impossible d’enregistrer les réglages.'
}

export default function AuditSettingsCard({ storage }: { storage?: BackendStorageInfo | null } = {}) {
  const { showToast } = useToast()
  const switchId = useId()
  const retentionId = useId()
  const [saved, setSaved] = useState<BackendAuditSettings | null>(null)
  const [logReads, setLogReads] = useState(false)
  const [retention, setRetention] = useState('365')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    auditBackendApi.settings()
      .then((s) => { setSaved(s); setLogReads(s.logReads); setRetention(String(s.retentionDays)) })
      .catch((err) => setError(describeError(err)))
  }, [])

  const days = Number(retention)
  const retentionValid = /^\d+$/.test(retention.trim()) && days >= RETENTION_MIN && days <= RETENTION_MAX
  const dirty = !!saved && (saved.logReads !== logReads || String(saved.retentionDays) !== retention.trim())

  const save = async () => {
    if (!retentionValid) return
    setSaving(true)
    setError(null)
    try {
      const next = await auditBackendApi.updateSettings({ logReads, retentionDays: days })
      setSaved(next)
      setLogReads(next.logReads)
      setRetention(String(next.retentionDays))
      showToast('Réglages du journal d’audit enregistrés.', 'success')
    } catch (err) {
      setError(describeError(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="pt-card au-settings mt-3" aria-labelledby={`${switchId}-title`}>
      <div className="au-settings-head">
        <i className="bi bi-journal-text" aria-hidden="true"></i>
        <div>
          <h2 id={`${switchId}-title`}>Journal d’audit</h2>
          <p>Ce qui est enregistré et combien de temps c’est conservé. <Link to="/audit-logs">Ouvrir le journal</Link></p>
        </div>
      </div>

      {storage !== undefined && <StorageStatus purge={storage?.audit} />}

      {error && <div className="pt-alert-banner danger mb-3" role="alert"><i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}</div>}

      {!saved && !error ? (
        <p className="au-muted mb-0">Chargement des réglages…</p>
      ) : saved && (
        <>
          <div className="form-check form-switch au-settings-row">
            <input className="form-check-input" type="checkbox" role="switch" id={switchId}
              checked={logReads} onChange={(e) => setLogReads(e.target.checked)} />
            <label className="form-check-label" htmlFor={switchId}>
              <strong>Journaliser aussi les consultations</strong>
              <span>Ouverture des rapports et de l’historique. Désactivé par défaut pour éviter le bruit ; les exports sont toujours enregistrés.</span>
            </label>
          </div>

          <div className="au-settings-row">
            <label htmlFor={retentionId}>
              <strong>Durée de conservation</strong>
              <span>Les entrées plus anciennes sont supprimées automatiquement chaque nuit (entre {RETENTION_MIN} et {RETENTION_MAX} jours).</span>
            </label>
            <div className="au-retention">
              <input id={retentionId} className={`pt-form-control${retentionValid ? '' : ' is-invalid'}`} inputMode="numeric"
                value={retention} onChange={(e) => setRetention(e.target.value)} aria-describedby={`${retentionId}-unit`} />
              <span id={`${retentionId}-unit`}>jours</span>
            </div>
          </div>
          {!retentionValid && <p className="au-filter-error" role="alert">Indiquez un nombre de jours entre {RETENTION_MIN} et {RETENTION_MAX}.</p>}

          <div className="au-settings-actions">
            <button type="button" className="pt-btn-primary" onClick={save} disabled={!dirty || !retentionValid || saving}>
              {saving ? 'Enregistrement…' : 'Enregistrer'}
            </button>
          </div>
        </>
      )}
    </section>
  )
}
