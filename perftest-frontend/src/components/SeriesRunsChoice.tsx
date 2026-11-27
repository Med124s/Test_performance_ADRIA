import { SERIES_RUNS } from '../utils/stressSeries'

// Lancement d'un test de stress : un seul essai, ou une série de 3 ou 5 essais au point de rupture consolidé.

export default function SeriesRunsChoice({ value, onChange, disabled }: { value: number; onChange: (runs: number) => void; disabled?: boolean }) {
  return (
    <div className="mt-2" data-testid="series-runs">
      <div className="lt-label" id="series-runs-label">Nombre d’essais</div>
      <div className="d-flex gap-3 flex-wrap" role="radiogroup" aria-labelledby="series-runs-label">
        {SERIES_RUNS.map((runs) => (
          <label key={runs} className="form-check form-check-inline mb-0">
            <input className="form-check-input" type="radio" name="series-runs" value={runs} checked={value === runs}
              onChange={() => onChange(runs)} disabled={disabled} />
            <span className="form-check-label">{runs === 1 ? '1 (test seul)' : `${runs} essais`}</span>
          </label>
        ))}
      </div>
      {value > 1 && (
        <p className="lt-muted mt-1 mb-0" style={{ fontSize: '12px' }}>
          Le même test {value} fois de suite, avec une pause de 30 s entre deux essais (le serveur reste réservé) :
          point de rupture consolidé (moyenne, minimum et maximum). Un essai non concluant arrête la série.
        </p>
      )}
    </div>
  )
}
