import { useEffect, useId, useState } from 'react'
import { useToast } from '../../context/ToastContext'
import { settingsBackendApi } from '../../services/api/settingsBackend'
import { ApiError } from '../../services/api/httpClient'
import type { BackendResultsRetentionSettings, BackendStorageInfo } from '../../types/backendContracts'
import StorageStatus from '../settings/StorageStatus'

// Conservation des résultats détaillés (Paramètres > Système, Super administrateur) :
//  - résultats (une ligne par requête) : 90 jours par défaut ;
//  - en-têtes et corps des requêtes : 30 jours par défaut, jamais plus que les résultats.
// Purge automatique chaque nuit ; rapports figés conservés ; exécutions « Conservées » et en cours jamais purgées.

export const RESULTS_MIN = 7
export const DETAILS_MIN = 1
export const DAYS_MAX = 3650

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'Réservé au Super administrateur.'
    if (err.status === 0 || err.status >= 500) return 'Le serveur LoadPilot ne répond pas. Réessayez dans un instant.'
    return err.message
  }
  return 'Impossible d’enregistrer les réglages.'
}

const isDays = (v: string, min: number) => /^\d+$/.test(v.trim()) && Number(v) >= min && Number(v) <= DAYS_MAX

export default function ResultsRetentionCard({ storage }: { storage?: BackendStorageInfo | null } = {}) {
  const { showToast } = useToast()
  const id = useId()
  const [saved, setSaved] = useState<BackendResultsRetentionSettings | null>(null)
  const [results, setResults] = useState('90')
  const [details, setDetails] = useState('30')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    settingsBackendApi.resultsRetention()
      .then((s) => { setSaved(s); setResults(String(s.resultsDays)); setDetails(String(s.requestDetailsDays)) })
      .catch((err) => setError(describeError(err)))
  }, [])

  const resultsOk = isDays(results, RESULTS_MIN)
  const detailsOk = isDays(details, DETAILS_MIN)
  const orderOk = !resultsOk || !detailsOk || Number(details) <= Number(results)
  const valid = resultsOk && detailsOk && orderOk
  const dirty = !!saved && (String(saved.resultsDays) !== results.trim() || String(saved.requestDetailsDays) !== details.trim())

  const save = async () => {
    if (!valid) return
    setSaving(true)
    setError(null)
    try {
      const next = await settingsBackendApi.updateResultsRetention({ resultsDays: Number(results), requestDetailsDays: Number(details) })
      setSaved(next)
      setResults(String(next.resultsDays))
      setDetails(String(next.requestDetailsDays))
      showToast('Durées de conservation enregistrées.', 'success')
    } catch (err) {
      setError(describeError(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="pt-card au-settings mt-3" aria-labelledby={`${id}-title`}>
      <div className="au-settings-head">
        <i className="bi bi-archive" aria-hidden="true"></i>
        <div>
          <h2 id={`${id}-title`}>Conservation des résultats</h2>
          <p>Le détail des exécutions est supprimé automatiquement chaque nuit ; le résumé et le rapport de chaque exécution sont toujours conservés.
            Les exécutions « Conservées » et celles en cours ne sont jamais concernées.</p>
        </div>
      </div>

      {storage !== undefined && <StorageStatus purge={storage?.results} databaseSizeBytes={storage?.databaseSizeBytes ?? null} />}

      {error && <div className="pt-alert-banner danger mb-3" role="alert"><i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}</div>}

      {!saved && !error ? (
        <p className="au-muted mb-0">Chargement des réglages…</p>
      ) : saved && (
        <>
          <div className="au-settings-row">
            <label htmlFor={`${id}-results`}>
              <strong>Résultats détaillés</strong>
              <span>Chaque requête envoyée pendant un test (liste des requêtes, temps de réponse). Entre {RESULTS_MIN} et {DAYS_MAX} jours.</span>
            </label>
            <div className="au-retention">
              <input id={`${id}-results`} className={`pt-form-control${resultsOk ? '' : ' is-invalid'}`} inputMode="numeric"
                value={results} onChange={(e) => setResults(e.target.value)} />
              <span>jours</span>
            </div>
          </div>
          <div className="au-settings-row">
            <label htmlFor={`${id}-details`}>
              <strong>En-têtes et corps des requêtes</strong>
              <span>Détail complet des requêtes conservées (échecs et échantillon). Entre {DETAILS_MIN} et {DAYS_MAX} jours, jamais plus que les résultats.</span>
            </label>
            <div className="au-retention">
              <input id={`${id}-details`} className={`pt-form-control${detailsOk && orderOk ? '' : ' is-invalid'}`} inputMode="numeric"
                value={details} onChange={(e) => setDetails(e.target.value)} />
              <span>jours</span>
            </div>
          </div>
          {(!resultsOk || !detailsOk) && <p className="au-filter-error" role="alert">Indiquez un nombre de jours dans les limites indiquées.</p>}
          {!orderOk && <p className="au-filter-error" role="alert">Les en-têtes et corps ne peuvent pas être conservés plus longtemps que les résultats.</p>}

          <div className="au-settings-actions">
            <button type="button" className="pt-btn-primary" onClick={save} disabled={!dirty || !valid || saving}>
              {saving ? 'Enregistrement…' : 'Enregistrer'}
            </button>
          </div>
        </>
      )}
    </section>
  )
}
