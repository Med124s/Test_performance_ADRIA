import { BackendStressResult } from '../types/backendContracts'
import { CRITERION_LABELS, LIMIT_LABELS, formatElapsed, formatMsValue, formatRps, stressOutcomeTitle, stressRampLabel } from '../utils/stressResult'
import '../pages/execution-report/timelineAnalysis.css'

// Résultat d'un test de stress : point de rupture (niveau de charge au début de l'anomalie), ou raison de son absence.

const TONE: Record<string, string> = { BREAKPOINT: 'warning', NO_BREAKPOINT: 'success', BASELINE_UNUSABLE: 'danger', INCONCLUSIVE: 'neutral' }

export default function StressResultCard({ stress }: { stress?: BackendStressResult | null }) {
  if (!stress) return null
  const tone = stress.outcome ? TONE[stress.outcome] : 'neutral'
  return (
    <div className={`pt-card mb-4 rp-stress rp-stress-${tone}`} data-testid="stress-result">
      <div className="d-flex align-items-center gap-2 mb-1">
        <i className="bi bi-graph-up-arrow" aria-hidden="true"></i>
        <strong style={{ fontSize: '15px' }}>{stressOutcomeTitle(stress)}</strong>
      </div>
      {stress.message && <p className="text-muted mb-2" style={{ fontSize: '13px' }}>{stress.message}</p>}
      {stress.outcome === 'BREAKPOINT' && (
        <dl className="rp-stress-facts mb-0">
          <div><dt>Critère(s)</dt><dd>{stress.criteria.map((c) => CRITERION_LABELS[c]).join(' ; ')}</dd></div>
          <div><dt>Début de l’anomalie</dt><dd>à {formatElapsed(stress.onsetSeconds)}{stress.detectedSeconds != null ? ` (confirmée à ${formatElapsed(stress.detectedSeconds)})` : ''}</dd></div>
          <div><dt>Charge</dt><dd>{stress.virtualUsers ?? '—'} utilisateur(s) virtuel(s), {formatRps(stress.offeredRps)} offertes</dd></div>
          {stress.rampRpsPerSecond != null && <div><dt>Vitesse de montée</dt><dd>{stressRampLabel(stress.rampRpsPerSecond)}</dd></div>}
          <div><dt>Débit obtenu</dt><dd>{formatRps(stress.throughputRps)}</dd></div>
          <div><dt>P95</dt><dd>{formatMsValue(stress.p95Ms)} <span className="text-muted">(référence {formatMsValue(stress.baselineP95Ms)})</span></dd></div>
          <div><dt>Refus</dt><dd>{stress.refusalPercent == null ? '—' : `${stress.refusalPercent.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} %`}</dd></div>
        </dl>
      )}
      {stress.outcome !== 'BREAKPOINT' && stress.peakVirtualUsers != null && (
        <p className="mb-0" style={{ fontSize: '13px' }}>
          Montée jusqu’à {stress.peakVirtualUsers} utilisateur(s) virtuel(s) et {formatRps(stress.peakOfferedRps)} offertes
          {stress.rampRpsPerSecond != null ? ` (${stressRampLabel(stress.rampRpsPerSecond, false)})` : ''}
          {stress.outcome === 'NO_BREAKPOINT' && stress.limit ? `, arrêtée à la limite du serveur (${LIMIT_LABELS[stress.limit]}).` : '.'}
        </p>
      )}
    </div>
  )
}
