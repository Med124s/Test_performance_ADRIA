import { inUserZone } from '../../utils/userTimezone'
import { useEffect, useState } from 'react'
import { BackendExecutionRequestDetail } from '../../types/backendContracts'
import { executionsBackendApi } from '../../services/api/executionsBackend'
import { ApiError } from '../../services/api/httpClient'

// ============================================================
// Détail d'UNE requête d'exécution (type Postman) : requête envoyée (URL
// finale avec les variables remplacées, en-têtes, corps), réponse reçue
// (code, temps, taille, en-têtes, corps JSON lisible) et vérifications
// (code attendu, texte attendu, variable capturée). Secrets déjà masqués
// par le serveur ; corps limités à 10 Ko (mention « tronqué »).
// ============================================================

type Tab = 'request' | 'response' | 'checks'

export function prettyBody(body: string | null): { text: string; json: boolean } {
  if (body == null || body === '') return { text: '', json: false }
  try {
    return { text: JSON.stringify(JSON.parse(body), null, 2), json: true }
  } catch {
    return { text: body, json: false }
  }
}

function formatBytes(n: number | null): string {
  if (n == null) return '—'
  if (n < 1024) return `${n} o`
  return `${(n / 1024).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} Ko`
}

function Headers({ headers }: { headers: Record<string, string> | null }) {
  const entries = Object.entries(headers ?? {})
  if (entries.length === 0) return <p className="text-muted mb-0" style={{ fontSize: '12.5px' }}>Aucun en-tête.</p>
  return (
    <table className="pt-table" style={{ fontSize: '12px' }}>
      <tbody>
        {entries.map(([k, v]) => (
          <tr key={k}><td style={{ width: '35%', fontWeight: 600 }}>{k}</td><td style={{ wordBreak: 'break-all', fontFamily: 'monospace' }}>{v}</td></tr>
        ))}
      </tbody>
    </table>
  )
}

function Body({ body, truncated, label }: { body: string | null; truncated: boolean; label: string }) {
  const { text, json } = prettyBody(body)
  return (
    <div>
      <div className="d-flex align-items-center gap-2 mb-1" style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>
        <strong>{label}</strong>
        {json && <span className="pt-pill neutral" style={{ fontSize: '10.5px' }}>JSON</span>}
        {truncated && <span className="pt-pill warning" style={{ fontSize: '10.5px' }}>tronqué (10 Ko conservés)</span>}
      </div>
      {text
        ? <pre className="rd-body" tabIndex={0}>{text}{truncated ? '\n… (tronqué)' : ''}</pre>
        : <p className="text-muted mb-0" style={{ fontSize: '12.5px' }}>Aucun corps.</p>}
    </div>
  )
}

function Check({ ok, label, detail }: { ok: boolean | null; label: string; detail?: string | null }) {
  const icon = ok == null ? 'bi-dash-circle' : ok ? 'bi-check-circle-fill' : 'bi-x-circle-fill'
  const color = ok == null ? 'var(--pt-text-muted)' : ok ? 'var(--pt-success)' : 'var(--pt-danger)'
  return (
    <li className="d-flex gap-2 align-items-start mb-2">
      <i className={`bi ${icon}`} style={{ color, marginTop: '2px' }} aria-hidden="true"></i>
      <span><strong>{label}</strong>{detail ? <> — <span style={{ wordBreak: 'break-all' }}>{detail}</span></> : null}</span>
    </li>
  )
}

export default function RequestDetailPanel({ executionId, resultId, onClose }: { executionId: string; resultId: string; onClose: () => void }) {
  const [detail, setDetail] = useState<BackendExecutionRequestDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('request')

  useEffect(() => {
    let alive = true
    setDetail(null)
    setError(null)
    executionsBackendApi.getResultDetail(executionId, resultId)
      .then((d) => { if (alive) setDetail(d) })
      .catch((err) => { if (alive) setError(err instanceof ApiError ? err.message : 'Détail indisponible.') })
    return () => { alive = false }
  }, [executionId, resultId])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const s = detail?.summary
  const c = detail?.checks
  return (
    <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1060 }}>
      <div className="modal-dialog modal-dialog-centered modal-lg modal-dialog-scrollable">
        <div className="modal-content" role="dialog" aria-modal="true" aria-labelledby="rd-title"
          style={{ borderRadius: 'var(--pt-radius)', border: '1px solid var(--pt-border)', background: 'var(--pt-card-bg)' }}>
          <div className="modal-header">
            <h5 id="rd-title" className="modal-title" style={{ fontSize: '15px', fontWeight: 700 }}>
              {s ? <>
                <span className={`sw-method-badge sw-method-${(detail?.request.method ?? 'get').toLowerCase()}`}>{detail?.request.method}</span>{' '}
                {s.stepName} · VU {(s.virtualUser ?? 0) + 1} · itération {(s.iteration ?? 0) + 1}
              </> : 'Détail de la requête'}
            </h5>
            <button type="button" className="btn-close" onClick={onClose} aria-label="Fermer"></button>
          </div>
          <div className="modal-body">
            {!detail && !error && <p className="text-muted"><span className="spinner-border spinner-border-sm me-2"></span>Chargement…</p>}
            {error && <div className="pt-alert-banner warning mb-0"><i className="bi bi-info-circle"></i>{error}</div>}
            {detail && s && (
              <>
                <div className="d-flex flex-wrap gap-2 mb-3" style={{ fontSize: '12.5px' }}>
                  <span className={`pt-pill ${s.success ? 'success' : 'danger'}`}>{s.success ? 'Réussie' : 'Échec'}</span>
                  <span className="pt-pill neutral">HTTP {s.httpStatus ?? '—'}</span>
                  <span className="pt-pill neutral">{s.responseTime ?? '—'} ms</span>
                  <span className="pt-pill neutral">{new Date(s.timestamp).toLocaleTimeString('fr-FR', inUserZone())}</span>
                  <span className="pt-pill info">{detail.kind === 'FAILURE' ? 'Détail conservé : échec' : 'Détail conservé : échantillon'}</span>
                </div>
                {s.error && <div className="pt-alert-banner danger mb-3"><i className="bi bi-exclamation-octagon"></i>{s.error}</div>}

                <div className="d-flex gap-2 mb-3" role="tablist" aria-label="Détail de la requête">
                  {([['request', 'Requête envoyée'], ['response', 'Réponse reçue'], ['checks', 'Vérifications']] as [Tab, string][]).map(([id, label]) => (
                    <button key={id} type="button" role="tab" aria-selected={tab === id}
                      className={`btn btn-sm ${tab === id ? 'btn-primary' : 'btn-outline-secondary'}`} onClick={() => setTab(id)}>{label}</button>
                  ))}
                </div>

                {tab === 'request' && (
                  <div role="tabpanel" className="d-flex flex-column gap-3">
                    <div>
                      <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)', fontWeight: 700 }}>URL finale</div>
                      <code style={{ wordBreak: 'break-all', fontSize: '12.5px' }}>{detail.request.method} {detail.request.url}</code>
                    </div>
                    <div><div style={{ fontSize: '12px', color: 'var(--pt-text-muted)', fontWeight: 700 }}>En-têtes</div><Headers headers={detail.request.headers} /></div>
                    <Body body={detail.request.body} truncated={detail.request.bodyTruncated} label="Corps envoyé" />
                  </div>
                )}

                {tab === 'response' && (
                  <div role="tabpanel" className="d-flex flex-column gap-3">
                    {detail.response ? (
                      <>
                        <div className="d-flex flex-wrap gap-2" style={{ fontSize: '12.5px' }}>
                          <span className="pt-pill neutral">Code {detail.response.status ?? '—'}</span>
                          <span className="pt-pill neutral">Temps {detail.response.timeMs ?? '—'} ms</span>
                          <span className="pt-pill neutral">Taille {formatBytes(detail.response.sizeBytes)}</span>
                        </div>
                        <div><div style={{ fontSize: '12px', color: 'var(--pt-text-muted)', fontWeight: 700 }}>En-têtes</div><Headers headers={detail.response.headers} /></div>
                        <Body body={detail.response.body} truncated={detail.response.bodyTruncated} label="Corps reçu" />
                      </>
                    ) : (
                      <p className="text-muted mb-0">Aucune réponse reçue{s.error ? ` : ${s.error}` : '.'}</p>
                    )}
                  </div>
                )}

                {tab === 'checks' && (
                  <div role="tabpanel">
                    {c ? (
                      <ul className="list-unstyled mb-0" style={{ fontSize: '13px' }}>
                        <Check ok={c.statusOk} label={`Code attendu : ${c.expectedStatus ?? '2xx-3xx'}`} detail={`reçu ${s.httpStatus ?? 'aucun'}`} />
                        {c.assertionText != null && <Check ok={c.assertionOk} label="Texte attendu dans la réponse" detail={`« ${c.assertionText} »`} />}
                        {c.captureName != null && (
                          <Check ok={c.captureFound} label={`Variable capturée \${${c.captureName}}`}
                            detail={c.captureFound ? `${c.capturePath} = ${c.captureValue}` : `${c.capturePath} : introuvable dans la réponse`} />
                        )}
                      </ul>
                    ) : (
                      <p className="text-muted mb-0">Aucune vérification : la requête n’a pas été envoyée ou aucune réponse n’a été reçue.</p>
                    )}
                  </div>
                )}
              </>
            )}
          </div>
          <div className="modal-footer">
            <button type="button" className="pt-btn-outline" onClick={onClose}>Fermer</button>
          </div>
        </div>
      </div>
    </div>
  )
}
