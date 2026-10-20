import { useEffect, useState } from 'react'
import { executionsBackendApi } from '../../services/api/executionsBackend'
import { ApiError } from '../../services/api/httpClient'
import { BackendBulkActionResponse, BackendExecutionDeletionImpact } from '../../types/backendContracts'
import type { ToastType } from '../../context/ToastContext'

// ============================================================
// Suppression d'exécutions, partagée par les pages Exécutions et Rapports :
// fenêtre de confirmation (impact réel : exécutions, résultats, métriques ;
// définitif), message d'erreur clair et compte rendu d'une suppression
// groupée (succès partiels expliqués). Les règles (droits, exécution active,
// « réessayez dans quelques secondes », audit) sont celles du serveur.
// ============================================================

export function describeExecutionError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 401:
        return 'Vous devez être connecté pour effectuer cette action.'
      case 403:
        return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      case 404:
        return err.message.startsWith('Erreur 404') ? 'Scénario ou exécution introuvable (déjà supprimé ?).' : err.message
      case 429:
        // Limite de capacité (utilisateurs virtuels globaux / exécutions simultanées) : le backend précise laquelle.
        return `Limite de capacité LoadPilot atteinte : ${err.message}`
      default:
        // 400 (brouillon, scénario inactif…) et 409 (archivé, exécution en cours, enregistrement en cours…) : message clair du backend.
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

export function plural(n: number, singular: string, pluralForm = `${singular}s`) {
  return `${n} ${n > 1 ? pluralForm : singular}`
}

/** Compte rendu d'une suppression groupée : tout supprimé, ou succès partiel avec la raison de chaque refus. */
export function bulkDeletionMessage(result: BackendBulkActionResponse): { message: string; type: ToastType } {
  if (result.failed === 0) {
    return { message: `${plural(result.succeeded, 'exécution supprimée', 'exécutions supprimées')} définitivement.`, type: 'success' }
  }
  const failures = result.results.filter((r) => !r.success).map((r) => `${r.name ? r.name.split(' (')[0] : 'Exécution'} : ${r.message}`).join(' ; ')
  return {
    message: `${plural(result.succeeded, 'exécution supprimée', 'exécutions supprimées')}, ${result.failed} non supprimée(s) — ${failures}`,
    type: result.succeeded > 0 ? 'warning' : 'danger',
  }
}

/** Confirmation de suppression : impact réel (résultats, métriques), définitif. */
export default function ExecutionDeletionDialog({ ids, onConfirm, onClose }: {
  ids: string[]
  onConfirm: () => Promise<void>
  onClose: () => void
}) {
  const [impact, setImpact] = useState<BackendExecutionDeletionImpact | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    executionsBackendApi.deletionImpact(ids)
      .then((r) => { if (alive) setImpact(r) })
      .catch((err) => { if (alive) setLoadError(describeExecutionError(err, 'Impact indisponible.')) })
    return () => { alive = false }
  }, [ids])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !busy) onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [busy, onClose])

  const deletable = impact ? impact.executions - impact.active : 0
  const submit = async () => {
    setBusy(true)
    setActionError(null)
    try {
      await onConfirm()
    } catch (err) {
      setActionError(describeExecutionError(err, 'Suppression impossible.'))
      setBusy(false)
    }
  }

  return (
    <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1060 }}>
      <div className="modal-dialog modal-dialog-centered">
        <div className="modal-content" role="dialog" aria-modal="true" aria-labelledby="ex-del-title"
          style={{ borderRadius: 'var(--pt-radius)', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}>
          <div className="modal-body p-4">
            <h5 id="ex-del-title" style={{ fontWeight: 700 }}>
              <i className="bi bi-trash3 text-danger me-2"></i>
              Supprimer {ids.length > 1 ? `${ids.length} exécutions` : 'cette exécution'} ?
            </h5>
            {!impact && !loadError && <p className="text-muted mb-0"><span className="spinner-border spinner-border-sm me-2"></span>Calcul de l’impact…</p>}
            {loadError && <div className="pt-alert-banner danger mb-0"><i className="bi bi-exclamation-octagon"></i>{loadError}</div>}
            {impact && (
              <>
                <p className="mb-2" style={{ fontSize: '13.5px' }}>Seront supprimés <strong>définitivement</strong> :</p>
                <ul style={{ fontSize: '13.5px' }} aria-label="Impact">
                  <li><strong>{deletable}</strong> {deletable > 1 ? 'exécutions' : 'exécution'}</li>
                  <li><strong>{impact.results}</strong> {impact.results > 1 ? 'résultats de requêtes' : 'résultat de requête'}</li>
                  {/* Anciennes lignes de la table metric seulement (plus aucune copie aujourd'hui) : affichees s'il en reste. */}
                  {impact.metrics > 0 && <li><strong>{impact.metrics}</strong> {impact.metrics > 1 ? 'métriques' : 'métrique'}</li>}
                </ul>
                {impact.active > 0 && (
                  <div className="pt-alert-banner warning mb-2" role="alert" style={{ fontSize: '12.5px' }}>
                    <i className="bi bi-hourglass-split"></i>
                    {plural(impact.active, 'exécution est', 'exécutions sont')} en attente, en cours ou en pause : {impact.active > 1 ? 'elles ne seront pas supprimées' : 'elle ne sera pas supprimée'} (annulez-la d’abord).
                  </div>
                )}
                <p className="mb-0" style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
                  Cette action est irréversible : rapports et détails de ces exécutions ne seront plus consultables.
                </p>
              </>
            )}
            {actionError && <div className="pt-alert-banner danger mt-2 mb-0"><i className="bi bi-exclamation-octagon"></i>{actionError}</div>}
          </div>
          <div className="modal-footer">
            <button type="button" className="pt-btn-ghost" onClick={onClose} disabled={busy}>Annuler</button>
            <button type="button" className="pt-btn-danger" onClick={submit} disabled={busy || !impact || deletable === 0}>
              {busy ? <span className="spinner-border spinner-border-sm me-1"></span> : <i className="bi bi-trash3"></i>} Supprimer définitivement
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
