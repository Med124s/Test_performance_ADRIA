import { BackendStressLive } from '../types/backendContracts'
import InfoTip from './InfoTip'
import { LIMIT_LABELS, STRESS_PHASES, formatElapsed, formatMsValue, formatRps } from '../utils/stressResult'

// Suivi en direct de la montée d'un test de stress : phase, débit offert, utilisateurs virtuels, mesure de référence.

export default function LiveStress({ live }: { live?: BackendStressLive | null }) {
  if (!live) return null
  const current = STRESS_PHASES.findIndex((p) => p.key === live.phase)
  return (
    <div className="pt-card mb-3" data-testid="live-stress" style={{ padding: '0.75rem 1rem' }}>
      <div className="d-flex align-items-center flex-wrap gap-2 mb-2" style={{ fontSize: '13px' }}>
        <strong>
          <i className="bi bi-graph-up-arrow me-1"></i>Recherche du point de rupture
          <InfoTip text="LoadPilot augmente le débit tout seul et compare chaque fenêtre de 5 s à la mesure de référence. Le test s’arrête dès qu’une rupture est confirmée, ou à une limite du serveur." />
        </strong>
        <ol className="em-stress-phases" aria-label="Phases de la montée">
          {STRESS_PHASES.map((p, i) => (
            <li key={p.key} className={i < current ? 'done' : i === current ? 'current' : ''} aria-current={i === current ? 'step' : undefined}>{p.label}</li>
          ))}
        </ol>
      </div>
      <div className="d-flex align-items-center flex-wrap gap-3" style={{ fontSize: '13px' }}>
        <span>Débit offert <strong>{formatRps(live.offeredRps)}</strong> <span className="text-muted">(jusqu’à {formatRps(live.maxRps)})</span></span>
        <span>Utilisateurs virtuels <strong>{live.virtualUsers.toLocaleString('fr-FR')}</strong> <span className="text-muted">(jusqu’à {live.maxVirtualUsers.toLocaleString('fr-FR')})</span></span>
        <span>P95 de référence <strong>{live.baselineP95Ms == null ? 'en cours de mesure' : formatMsValue(live.baselineP95Ms)}</strong></span>
        <span className="text-muted">{formatElapsed(live.elapsedSeconds)} écoulées sur {formatElapsed(live.maxDurationSeconds)} au plus</span>
      </div>
      {live.phase === 'HOLD' && live.holdingLimit && (
        <div className="mt-2 text-muted" style={{ fontSize: '12.5px' }}>
          <i className="bi bi-info-circle me-1"></i>Limite du serveur atteinte ({LIMIT_LABELS[live.holdingLimit]}) : la charge est maintenue quelques secondes
          pour confirmer, puis le test se termine s’il n’y a pas de rupture.
        </div>
      )}
    </div>
  )
}
