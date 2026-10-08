import { BackendExecutionResources } from '../../types/backendContracts'
import { MONITORING_SCOPE_TEXT, MONITORING_TYPE_LABELS, formatCpu, formatRam, sparklinePaths } from './monitoringModel'

// ============================================================
// Suivi en direct : CPU et RAM du processus de l'application testée
// (dernière mesure + mini-courbe des dernières mesures ; une fois
// l'exécution terminée : moyenne et maximum enregistrés).
// ============================================================

function Mini({ values, label }: { values: (number | null)[]; label: string }) {
  const paths = sparklinePaths(values, 110, 26)
  if (paths.length === 0) return null
  return (
    <svg width={110} height={26} viewBox="0 0 110 26" role="img" aria-label={label} className="lr-spark">
      {paths.map((d, i) => <path key={i} d={d} fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" />)}
    </svg>
  )
}

export default function LiveResources({ resources, finished }: { resources: BackendExecutionResources | null | undefined; finished: boolean }) {
  if (!resources) return null
  const live = !finished && resources.source != null
  const noData = resources.cpuMaxPercent == null && resources.memoryMaxMb == null && resources.cpuPercent == null && resources.memoryMb == null
  return (
    <div className="pt-card mb-3 lr-card" data-testid="live-resources">
      <div className="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-2">
        <h6 className="m-0" style={{ fontSize: '14px', fontWeight: 600 }}>
          <i className="bi bi-cpu me-2" style={{ color: 'var(--pt-primary)' }}></i>Application testée — CPU et RAM du processus
        </h6>
        <span className="em-muted" title={MONITORING_SCOPE_TEXT} style={{ cursor: 'help' }}>
          <i className="bi bi-info-circle me-1"></i>
          {resources.source ? `${MONITORING_TYPE_LABELS[resources.source]}, toutes les ${resources.intervalSeconds} s` : 'Mesures enregistrées'}
        </span>
      </div>
      {resources.message && (
        <div className="pt-alert-banner warning mb-2" role="status" style={{ fontSize: '12.5px' }}>
          <i className="bi bi-exclamation-triangle"></i>{resources.message}
        </div>
      )}
      {!noData && (
        <div className="lr-grid">
          <div className="em-kpi">
            <strong>{live ? formatCpu(resources.cpuPercent) : formatCpu(resources.cpuAvgPercent)}</strong>
            <span>{live ? 'CPU actuel' : 'CPU moyen'} (% d’un cœur) · max {formatCpu(resources.cpuMaxPercent)}</span>
            {live && <Mini values={resources.cpuHistory} label="CPU des dernières mesures" />}
          </div>
          <div className="em-kpi">
            <strong>{live ? formatRam(resources.memoryMb) : formatRam(resources.memoryMaxMb)}</strong>
            <span>{live ? 'RAM actuelle' : 'RAM maximale'} · max {formatRam(resources.memoryMaxMb)}</span>
            {live && <Mini values={resources.memoryHistory} label="RAM des dernières mesures" />}
          </div>
        </div>
      )}
      {noData && !resources.message && <div className="em-muted">En attente des premières mesures…</div>}
      <div className="em-muted mt-2" style={{ fontSize: '11.5px' }}>{MONITORING_SCOPE_TEXT}</div>
    </div>
  )
}
