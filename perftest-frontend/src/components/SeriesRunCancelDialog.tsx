import { SERIES_RUN_CANCEL_WARNING } from '../utils/stressSeries'

// Confirmation avant d'annuler un essai d'une série de stress (liste et détail des exécutions).

interface Props {
  scenarioName: string
  busy: boolean
  onConfirm: () => void
  onClose: () => void
}

export default function SeriesRunCancelDialog({ scenarioName, busy, onConfirm, onClose }: Props) {
  return (
    <div className="modal fade show d-block em-backdrop" tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="series-cancel-title">
      <div className="modal-dialog modal-dialog-centered">
        <div className="modal-content em-modal">
          <div className="modal-body p-4">
            <h5 id="series-cancel-title">Annuler l’essai de « {scenarioName} » ?</h5>
            <p className="em-muted mb-0" data-testid="series-cancel-warning">{SERIES_RUN_CANCEL_WARNING}</p>
          </div>
          <div className="modal-footer">
            <button type="button" className="pt-btn-ghost" onClick={onClose} disabled={busy}>Continuer la série</button>
            <button type="button" className="pt-btn-danger" onClick={onConfirm} disabled={busy}>
              {busy ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-stop-circle"></i>} Annuler l’essai et la série
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
