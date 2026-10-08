import { BackendStepEvolution } from '../../types/backendContracts'
import { TREND_META, TREND_RULE, formatDeltaMs, formatDeltaPercent, formatSeconds, rowTooltip, sortTrends } from './stepEvolutionModel'

// ============================================================
// « Évolution des étapes pendant le test » : une ligne par étape,
// P95 au début et à la fin, évolution et tendance.
// Remplace le graphique par étape (illisible avec beaucoup d'étapes),
// qui reste disponible replié sous le tableau.
// ============================================================

export default function StepEvolutionTable({ evolution, long }: { evolution: BackendStepEvolution; long: boolean }) {
  if (!evolution.comparable) {
    return (
      <p className="se-empty" role="status">
        <i className="bi bi-info-circle me-2" aria-hidden="true"></i>{evolution.message}
      </p>
    )
  }
  const rows = sortTrends(evolution.steps)
  const period = (from: number, to: number) => `${formatSeconds(from, long)} – ${formatSeconds(to, long)}`
  return (
    <>
      <p className="ta-note mb-2">
        P95 comparé entre le début ({period(evolution.startFromSeconds, evolution.startToSeconds)}) et la fin
        ({period(evolution.endFromSeconds, evolution.endToSeconds)}) de la phase stable, soit les premiers et les derniers 20 %,
        hors pauses et hors départ des utilisateurs. Étapes triées de la plus dégradée à la plus stable.
      </p>
      <div className="se-wrap">
        <table className="se-table">
          <thead>
            <tr>
              <th scope="col">Étape</th>
              <th scope="col" className="se-num">P95 début</th>
              <th scope="col" className="se-num">P95 fin</th>
              <th scope="col" className="se-num">Évolution</th>
              <th scope="col" className="se-trend">
                Tendance <i className="bi bi-question-circle se-help" title={TREND_RULE} aria-label={`Règle : ${TREND_RULE}`} role="img"></i>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((s) => {
              const meta = TREND_META[s.trend]
              return (
                <tr key={s.key} title={rowTooltip(s, evolution, long)} data-testid="step-evolution-row">
                  <td className="se-name" data-label="Étape">
                    {s.method && <span className="se-method">{s.method}</span>}{s.name}
                  </td>
                  <td className="se-num" data-label="P95 début">{s.startP95Ms != null ? `${s.startP95Ms} ms` : '—'}</td>
                  <td className="se-num" data-label="P95 fin">{s.endP95Ms != null ? `${s.endP95Ms} ms` : '—'}</td>
                  <td className="se-num" data-label="Évolution">
                    {s.deltaMs != null ? (
                      <span>
                        <span className={`se-delta se-${s.trend.toLowerCase()}`}>{formatDeltaMs(s.deltaMs)}</span>
                        {s.deltaPercent != null && <small className="se-pct">{formatDeltaPercent(s.deltaPercent)}</small>}
                      </span>
                    ) : '—'}
                  </td>
                  <td className="se-trend" data-label="Tendance">
                    <span className={`pt-pill ${meta.pill} se-badge`} title={TREND_RULE}>
                      <i className={`bi ${meta.icon}`} aria-hidden="true"></i>{meta.label}
                    </span>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </>
  )
}
