import { useEffect, useState } from 'react'
import { BackendExecutionExtremes } from '../../types/backendContracts'
import { executionsBackendApi } from '../../services/api/executionsBackend'
import { ApiError } from '../../services/api/httpClient'
import { formatMs, formatNumber, formatRps } from '../../utils/numberFormat'
import RequestDetailPanel from '../execution-detail/RequestDetailPanel'
import './timelineAnalysis.css'

// ============================================================
// D'où vient la carte Minimum / Maximum : la requête la plus rapide (ou la plus lente), puis les
// 10 plus rapides (ou plus lentes), avec leur place dans le test et, au même moment, utilisateurs
// actifs, débit et P95 de l'intervalle de l'analyse dans le temps. Calcul côté serveur.
// ============================================================

export const DETAIL_NOT_KEPT_TEXT =
  'Détail non enregistré pour cette requête : seuls les échecs et un échantillon des requêtes réussies le sont.'

type Item = BackendExecutionExtremes['requests'][number]

/** « à 2 s du début » / « à 1 min 05 s du début ». */
export function formatOffset(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  if (s < 60) return `à ${s} s du début`
  return `à ${Math.floor(s / 60)} min ${String(s % 60).padStart(2, '0')} s du début`
}

function moment(item: Item, intervalSeconds: number): string {
  const label = intervalSeconds > 1 ? `intervalle de ${intervalSeconds} s` : 'cette seconde'
  return `${formatNumber(item.activeVirtualUsers, 0)} utilisateur(s) actif(s) · ${formatRps(item.throughput)} · P95 de ${label} : ${formatMs(item.p95Ms)}`
}

export default function ExtremesPanel({ executionId, kind, onClose }: { executionId: string; kind: 'MIN' | 'MAX'; onClose: () => void }) {
  const [data, setData] = useState<BackendExecutionExtremes | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [openResult, setOpenResult] = useState<string | null>(null)
  const isMax = kind === 'MAX'

  useEffect(() => {
    let alive = true
    executionsBackendApi.getExtremes(executionId, kind)
      .then((d) => { if (alive) setData(d) })
      .catch((e) => { if (alive) setError(e instanceof ApiError ? e.message : 'Chargement impossible.') })
    return () => { alive = false }
  }, [executionId, kind])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !openResult) onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose, openResult])

  const first = data?.requests[0]
  const title = isMax ? 'Maximum : la requête la plus lente' : 'Minimum : la requête la plus rapide'

  const link = (item: Item) => item.request.detailKind
    ? <button type="button" className="pt-btn-outline xp-link" onClick={() => setOpenResult(item.request.resultId)}><i className="bi bi-search me-1"></i>Voir la requête</button>
    : <span className="xp-muted" title={DETAIL_NOT_KEPT_TEXT}>Détail non enregistré</span>

  return (
    <>
      <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1055 }}>
        <div className="modal-dialog modal-dialog-centered modal-xl modal-dialog-scrollable">
          <div className="modal-content" role="dialog" aria-modal="true" aria-labelledby="xp-title"
            style={{ borderRadius: 'var(--pt-radius)', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}>
            <div className="modal-header">
              <h5 id="xp-title" className="modal-title" style={{ fontSize: '15px', fontWeight: 700 }}>
                <i className={`bi ${isMax ? 'bi-arrow-up-circle text-danger' : 'bi-arrow-down-circle text-success'} me-2`}></i>{title}
              </h5>
              <button type="button" className="btn-close" onClick={onClose} aria-label="Fermer"></button>
            </div>
            <div className="modal-body">
              {!data && !error && <p className="text-muted"><span className="spinner-border spinner-border-sm me-2"></span>Chargement…</p>}
              {error && <div className="pt-alert-banner warning mb-0"><i className="bi bi-info-circle"></i>{error}</div>}
              {data && !first && <p className="text-muted mb-0" role="status">Aucune requête mesurée pour cette exécution.</p>}
              {data && first && (
                <>
                  <div className="xp-hero" data-testid="extreme-hero">
                    <div className="xp-hero-value">{formatMs(first.request.responseTime)}</div>
                    <div className="xp-hero-main">
                      <div className="fw-semibold">
                        {first.request.method && <span className={`sw-method-badge sw-method-${first.request.method.toLowerCase()}`}>{first.request.method}</span>}{' '}
                        {first.request.stepName}
                      </div>
                      <div className="xp-muted">
                        VU {(first.request.virtualUser ?? 0) + 1} · itération {(first.request.iteration ?? 0) + 1} ·{' '}
                        {new Date(first.request.timestamp).toLocaleTimeString('fr-FR')} ({formatOffset(first.secondsFromStart)}) ·{' '}
                        HTTP {first.request.httpStatus ?? '—'}{first.request.success === false && ' (échec)'}
                      </div>
                      <div className="xp-muted">Au même moment : {moment(first, data.intervalSeconds)}</div>
                      {first.request.error && <div className="text-danger" style={{ fontSize: '12px' }}>{first.request.error}</div>}
                    </div>
                    <div className="xp-hero-link">
                      {first.request.detailKind ? link(first) : <span className="xp-muted">{DETAIL_NOT_KEPT_TEXT}</span>}
                    </div>
                  </div>

                  <h6 className="xp-subtitle">
                    Les {data.requests.length} requêtes les plus {isMax ? 'lentes' : 'rapides'}
                    <span className="xp-muted"> sur {formatNumber(data.measuredRequests, 0)} mesurées</span>
                  </h6>
                  <div className="pt-table-wrapper">
                    <table className="pt-table xp-table" style={{ fontSize: '12.5px' }}>
                      <thead>
                        <tr>
                          <th>#</th><th>Étape</th><th>VU</th><th>Itér.</th><th>Heure</th><th>Dans le test</th><th>Code</th>
                          <th className="text-end">Temps</th><th>Au même moment</th><th className="text-end">Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.requests.map((it, i) => (
                          <tr key={it.request.resultId} data-testid="extreme-row">
                            <td>{i + 1}</td>
                            <td>{it.request.method && <span className="xp-method">{it.request.method}</span>}{it.request.stepName}</td>
                            <td>{(it.request.virtualUser ?? 0) + 1}</td>
                            <td>{(it.request.iteration ?? 0) + 1}</td>
                            <td>{new Date(it.request.timestamp).toLocaleTimeString('fr-FR')}</td>
                            <td>{formatOffset(it.secondsFromStart).replace('à ', '')}</td>
                            <td><span className={it.request.success === false ? 'text-danger fw-semibold' : ''}>{it.request.httpStatus ?? '—'}</span></td>
                            <td className="text-end fw-semibold">{formatMs(it.request.responseTime)}</td>
                            <td className="xp-muted">{moment(it, data.intervalSeconds)}</td>
                            <td className="text-end">{link(it)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <p className="xp-muted mb-0 mt-2">
                    Heure et place dans le test = fin de la requête (comme l’analyse dans le temps). Ex aequo : la plus ancienne d’abord.
                  </p>
                </>
              )}
            </div>
          </div>
        </div>
      </div>
      {openResult && <RequestDetailPanel executionId={executionId} resultId={openResult} onClose={() => setOpenResult(null)} />}
    </>
  )
}
