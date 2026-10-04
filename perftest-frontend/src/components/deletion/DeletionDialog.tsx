import { useEffect, useState } from 'react'
import { BackendDeletionImpact } from '../../types/backendContracts'
import { ApiError } from '../../services/api/httpClient'
import './deletionDialog.css'

// ============================================================
// Modale de suppression cohérente : affiche l'impact réel (scénarios,
// étapes, exécutions…) AVANT de confirmer, propose l'archivage par défaut
// (historique conservé, restaurable) et la suppression définitive en
// cascade (SUPER_ADMIN, confirmation par saisie du nom). Toute action est
// impossible tant qu'une exécution est en cours.
// ============================================================

export type DeletionMode = 'ARCHIVE' | 'PERMANENT'

interface DeletionDialogProps {
  /** Ex. « l'application « Banking » » ou « 3 applications ». */
  subject: string
  /** Texte à saisir pour confirmer une suppression définitive (nom, ou « SUPPRIMER »). */
  confirmText: string
  loadImpact: () => Promise<BackendDeletionImpact>
  canPermanent: boolean
  /** Masque le choix « Archiver » (élément déjà archivé). */
  archiveAvailable?: boolean
  onConfirm: (mode: DeletionMode) => Promise<void>
  onClose: () => void
}

/** Message lisible : jamais une erreur technique brute. */
function readableError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return "Action refusée : votre rôle ne permet pas cette opération."
    if (err.status === 404) return 'Élément introuvable (il a peut-être déjà été supprimé).'
    if (err.status === 0) return err.message
    if (err.status >= 500) return 'Erreur du serveur LoadPilot. Réessayez plus tard.'
    return err.message
  }
  return 'Erreur inattendue, réessayez.'
}

function plural(n: number, singular: string, pluralForm = `${singular}s`) {
  return `${n} ${n > 1 ? pluralForm : singular}`
}

function DeletionDialog({ subject, confirmText, loadImpact, canPermanent, archiveAvailable = true, onConfirm, onClose }: DeletionDialogProps) {
  const [impact, setImpact] = useState<BackendDeletionImpact | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [mode, setMode] = useState<DeletionMode>(archiveAvailable ? 'ARCHIVE' : 'PERMANENT')
  const [typed, setTyped] = useState('')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    loadImpact()
      .then((r) => { if (alive) setImpact(r) })
      .catch((err) => { if (alive) setLoadError(readableError(err)) })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busy) onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [busy, onClose])

  const running = !!impact?.hasRunningExecution
  const typedOk = typed.trim() === confirmText.trim()
  const canSubmit = !!impact && !running && !busy && (mode === 'ARCHIVE' || (canPermanent && typedOk))

  const submit = async () => {
    if (!canSubmit) return
    setBusy(true)
    setActionError(null)
    try {
      await onConfirm(mode)
    } catch (err) {
      setActionError(readableError(err))
      setBusy(false)
    }
  }

  return (
    <div className="dd-backdrop">
      <div className="dd-dialog" role="dialog" aria-modal="true" aria-labelledby="dd-title" aria-busy={busy || !impact}>
        <div className="dd-header">
          <span className="dd-icon"><i className="bi bi-trash3"></i></span>
          <div>
            <h5 id="dd-title">Supprimer {subject} ?</h5>
            <p>Voici ce qui est concerné. L’historique des exécutions est conservé si vous archivez.</p>
          </div>
        </div>

        {!impact && !loadError && (
          <div className="dd-loading" role="status"><span className="spinner-border spinner-border-sm"></span> Calcul de l’impact…</div>
        )}
        {loadError && <div className="dd-alert ko" role="alert"><i className="bi bi-exclamation-octagon"></i> {loadError}</div>}

        {impact && (
          <>
            <ul className="dd-impact" aria-label="Impact">
              {impact.applications > 1 && <li><strong>{impact.applications}</strong> applications</li>}
              <li><strong>{impact.scenarios}</strong> {impact.scenarios > 1 ? 'scénarios' : 'scénario'}</li>
              <li><strong>{impact.steps}</strong> {impact.steps > 1 ? 'étapes' : 'étape'}</li>
              <li><strong>{impact.executions}</strong> {impact.executions > 1 ? 'exécutions' : 'exécution'}</li>
              {impact.scheduledExecutions > 0 && <li><strong>{impact.scheduledExecutions}</strong> {impact.scheduledExecutions > 1 ? 'planifications' : 'planification'}</li>}
            </ul>

            {running && (
              <div className="dd-alert ko" role="alert">
                <i className="bi bi-hourglass-split"></i>
                <span>Une exécution est en cours ({plural(impact.runningExecutions, 'exécution')}) : arrêtez-la avant de supprimer.</span>
              </div>
            )}

            {!running && (
              <div className="dd-choices" role="radiogroup" aria-label="Type de suppression">
                {archiveAvailable && (
                  <label className={`dd-choice ${mode === 'ARCHIVE' ? 'selected' : ''}`}>
                    <input type="radio" name="dd-mode" checked={mode === 'ARCHIVE'} onChange={() => setMode('ARCHIVE')} />
                    <span>
                      <strong><i className="bi bi-archive"></i> Archiver <em>(recommandé)</em></strong>
                      <small>Masqué des listes, historique consultable, restaurable à tout moment.{impact.scheduledExecutions > 0 ? ' Les planifications sont désactivées.' : ''}</small>
                    </span>
                  </label>
                )}
                <label className={`dd-choice danger ${mode === 'PERMANENT' ? 'selected' : ''} ${canPermanent ? '' : 'disabled'}`}>
                  <input type="radio" name="dd-mode" checked={mode === 'PERMANENT'} disabled={!canPermanent} onChange={() => setMode('PERMANENT')} />
                  <span>
                    <strong><i className="bi bi-exclamation-triangle"></i> Supprimer définitivement</strong>
                    <small>
                      {canPermanent
                        ? 'Supprime tout ce qui est listé ci-dessus, historique compris. Action irréversible.'
                        : 'Réservé au rôle Administrateur.'}
                    </small>
                  </span>
                </label>
              </div>
            )}

            {!running && mode === 'PERMANENT' && canPermanent && (
              <div className="dd-confirm">
                <label htmlFor="dd-confirm-input">Pour confirmer, saisissez <code>{confirmText}</code></label>
                <input
                  id="dd-confirm-input"
                  className="pt-form-control"
                  value={typed}
                  onChange={(e) => setTyped(e.target.value)}
                  autoComplete="off"
                  spellCheck={false}
                  autoFocus
                />
              </div>
            )}
          </>
        )}

        {actionError && <div className="dd-alert ko" role="alert"><i className="bi bi-exclamation-octagon"></i> {actionError}</div>}

        <div className="dd-footer">
          <button type="button" className="pt-btn-ghost" onClick={onClose} disabled={busy}>Annuler</button>
          <button
            type="button"
            className={mode === 'PERMANENT' ? 'pt-btn-danger' : 'pt-btn-primary'}
            onClick={submit}
            disabled={!canSubmit}
            aria-busy={busy}
          >
            {busy && <span className="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>}
            {mode === 'PERMANENT' ? 'Supprimer définitivement' : 'Archiver'}
          </button>
        </div>
      </div>
    </div>
  )
}

export default DeletionDialog
