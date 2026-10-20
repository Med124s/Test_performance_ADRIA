import { BackendInjectorStatus } from '../types/backendContracts'
import InfoTip from './InfoTip'

// ============================================================
// Suivi en direct : LoadPilot lui-même (l'injecteur). Un injecteur
// saturé fausse les temps de réponse mesurés : avertissement visible.
// ============================================================

const pct = (v: number | null | undefined) => (v == null ? '—' : `${v.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} %`)

export default function LiveInjector({ injector }: { injector?: BackendInjectorStatus | null }) {
  if (!injector) return null
  return (
    <div className="pt-card mb-3" data-testid="live-injector" style={{ padding: '0.75rem 1rem' }}>
      <div className="d-flex align-items-center flex-wrap gap-3" style={{ fontSize: '13px' }}>
        <strong style={{ fontSize: '13px' }}>
          <i className="bi bi-cpu me-1"></i>Injecteur (LoadPilot)
          <InfoTip text="Ressources de LoadPilot lui-même pendant le test. S’il sature, les temps de réponse mesurés peuvent être faussés." />
        </strong>
        <span>CPU {pct(injector.cpuPercent)} <span className="text-muted">(max {pct(injector.cpuMaxPercent)})</span></span>
        <span className="text-muted">Machine {pct(injector.machineCpuPercent)}</span>
        <span>Mémoire {injector.heapUsedMb.toLocaleString('fr-FR')} / {injector.heapMaxMb.toLocaleString('fr-FR')} Mo</span>
        <span>{injector.activeVirtualThreads.toLocaleString('fr-FR')} threads virtuels actifs</span>
      </div>
      {injector.overloaded && injector.warning && (
        <div role="alert" className="mt-2" style={{ fontSize: '12.5px', color: 'var(--pt-danger)' }}>
          <i className="bi bi-exclamation-triangle-fill me-1"></i>{injector.warning}
        </div>
      )}
    </div>
  )
}
