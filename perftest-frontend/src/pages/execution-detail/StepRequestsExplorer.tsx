import { inUserZone } from '../../utils/userTimezone'
import { useEffect, useState } from 'react'
import { BackendExecutionRequestRow, BackendRequestDetailMode, BackendStepReportEntry } from '../../types/backendContracts'
import { executionsBackendApi } from '../../services/api/executionsBackend'
import { ApiError } from '../../services/api/httpClient'
import Pagination from '../../components/Pagination'
import RequestDetailPanel from './RequestDetailPanel'
import './executionDetail.css'

// ============================================================
// « Résultats par étape » : chaque étape s'ouvre sur la liste paginée
// (côté serveur) de ses requêtes — utilisateur virtuel, itération, heure,
// code HTTP, temps, succès / échec — filtrable par VU, par itération et
// « échecs seulement ». Un clic sur une requête dont le détail a été
// conservé ouvre la requête envoyée et la réponse reçue.
// ============================================================

const PAGE_SIZE = 20

const MODE_LABEL: Record<BackendRequestDetailMode, string> = {
  NONE: 'Aucun détail des requêtes',
  FAILURES: 'Détail des échecs',
  FAILURES_AND_SAMPLE: 'Détail des échecs + échantillon des réussites',
}

interface Props {
  executionId: string
  virtualUsers: number
  steps: BackendStepReportEntry[]
  requestDetailMode: BackendRequestDetailMode | null | undefined
  detailSampleCapReached: boolean
  detailedFailures: number
  detailedSamples: number
}

function stepKey(s: BackendStepReportEntry, i: number) {
  return s.stepId ?? `deleted-${i}-${s.stepName}`
}

function StepRequests({ executionId, step, virtualUsers }: { executionId: string; step: BackendStepReportEntry; virtualUsers: number }) {
  const [vu, setVu] = useState('')
  const [iteration, setIteration] = useState('')
  const [failedOnly, setFailedOnly] = useState(false)
  const [sort, setSort] = useState<'TIME' | 'SLOWEST' | 'FASTEST'>('TIME')
  const [page, setPage] = useState(1)
  const [rows, setRows] = useState<BackendExecutionRequestRow[]>([])
  const [total, setTotal] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [openResult, setOpenResult] = useState<string | null>(null)

  useEffect(() => { setPage(1) }, [vu, iteration, failedOnly, sort])

  useEffect(() => {
    let alive = true
    setLoading(true)
    const iter = Number(iteration)
    executionsBackendApi.getResults(executionId, {
      stepId: step.stepId ?? undefined,
      stepName: step.stepId ? undefined : step.stepName,
      virtualUser: vu === '' ? undefined : Number(vu),
      iteration: iteration.trim() === '' || !Number.isFinite(iter) || iter < 1 ? undefined : iter - 1,
      failedOnly,
      sort,
      page: page - 1,
      size: PAGE_SIZE,
    })
      .then((r) => { if (alive) { setRows(r.content); setTotal(r.totalElements); setTotalPages(Math.max(1, r.totalPages)); setError(null) } })
      .catch((err) => { if (alive) setError(err instanceof ApiError ? err.message : 'Chargement impossible.') })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [executionId, step.stepId, step.stepName, vu, iteration, failedOnly, sort, page])

  const from = total === 0 ? 0 : (page - 1) * PAGE_SIZE + 1
  const to = Math.min(page * PAGE_SIZE, total)

  return (
    <div className="ed-step-panel">
      <div className="d-flex flex-wrap align-items-center gap-2 mb-2">
        <select className="pt-form-control" style={{ width: 'auto' }} value={vu} onChange={(e) => setVu(e.target.value)} aria-label="Filtrer par utilisateur virtuel">
          <option value="">Tous les utilisateurs virtuels</option>
          {Array.from({ length: virtualUsers }, (_, i) => <option key={i} value={i}>VU {i + 1}</option>)}
        </select>
        <input className="pt-form-control" style={{ width: '130px' }} type="number" min={1} placeholder="Itération" value={iteration}
          onChange={(e) => setIteration(e.target.value)} aria-label="Filtrer par itération" />
        <label className="d-inline-flex align-items-center gap-2 mb-0" style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', cursor: 'pointer' }}>
          <input type="checkbox" className="form-check-input m-0" checked={failedOnly} onChange={(e) => setFailedOnly(e.target.checked)} />
          Échecs seulement
        </label>
        <select className="pt-form-control" style={{ width: 'auto' }} value={sort} onChange={(e) => setSort(e.target.value as typeof sort)} aria-label="Trier les requêtes">
          <option value="TIME">Ordre chronologique</option>
          <option value="SLOWEST">Plus lentes d’abord</option>
          <option value="FASTEST">Plus rapides d’abord</option>
        </select>
        <span className="ms-auto" style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>
          <i className="bi bi-search"></i> = détail complet conservé
        </span>
      </div>
      {error && <div className="pt-alert-banner danger mb-2"><i className="bi bi-exclamation-octagon"></i>{error}</div>}
      <div className="pt-table-wrapper">
        <table className="pt-table" style={{ fontSize: '12.5px' }} aria-busy={loading}>
          <thead>
            <tr><th>Heure</th><th>VU</th><th>Itération</th><th>Code</th><th className="text-end">Temps</th><th>Résultat</th><th className="text-end">Actions</th></tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr><td colSpan={7} className="text-center text-muted py-3">{loading ? 'Chargement…' : 'Aucune requête pour ces filtres.'}</td></tr>
            ) : rows.map((r) => (
              <tr key={r.resultId} className={r.detailKind ? 'ed-row-clickable' : undefined}
                onClick={r.detailKind ? () => setOpenResult(r.resultId) : undefined}>
                <td>{new Date(r.timestamp).toLocaleTimeString('fr-FR', inUserZone())}</td>
                <td>VU {(r.virtualUser ?? 0) + 1}</td>
                <td>{(r.iteration ?? 0) + 1}</td>
                <td><span className="badge bg-light text-dark border">{r.httpStatus ?? '—'}</span></td>
                <td className="text-end">{r.responseTime ?? '—'} ms</td>
                <td>
                  <span className={r.success ? 'text-success fw-semibold' : 'text-danger fw-semibold'}>{r.success ? 'Réussie' : 'Échec'}</span>
                  {r.error && <div className="text-danger text-truncate" style={{ fontSize: '11px', maxWidth: '320px' }} title={r.error}>{r.error}</div>}
                </td>
                <td className="text-end">
                  {r.detailKind ? (
                    <button type="button" className="topbar-icon" style={{ width: '28px', height: '28px', border: '1px solid var(--pt-border)' }}
                      aria-label={`Voir le détail de la requête de VU ${(r.virtualUser ?? 0) + 1}, itération ${(r.iteration ?? 0) + 1}`}
                      onClick={(e) => { e.stopPropagation(); setOpenResult(r.resultId) }}>
                      <i className="bi bi-search" style={{ fontSize: '12px' }}></i>
                    </button>
                  ) : (
                    <span title="Détail non conservé (seuls les échecs et un échantillon le sont)" style={{ color: 'var(--pt-text-light)' }}>—</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {total > PAGE_SIZE && (
        <Pagination page={page} totalPages={totalPages} onPageChange={setPage} startIndex={from} endIndex={to} totalItems={total}
          itemLabel="requêtes" summary={`Requêtes ${from} à ${to} sur ${total}`} />
      )}
      {openResult && <RequestDetailPanel executionId={executionId} resultId={openResult} onClose={() => setOpenResult(null)} />}
    </div>
  )
}

export default function StepRequestsExplorer({ executionId, virtualUsers, steps, requestDetailMode, detailSampleCapReached, detailedFailures, detailedSamples }: Props) {
  const [openKey, setOpenKey] = useState<string | null>(null)

  return (
    <div className="pt-card mb-4">
      <div className="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-2">
        <div className="pt-card-title mb-0"><i className="bi bi-list-check me-2 text-primary"></i>Résultats par étape</div>
        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}>
          {requestDetailMode ? MODE_LABEL[requestDetailMode] : 'Exécution antérieure : aucun détail des requêtes'}
          {requestDetailMode && requestDetailMode !== 'NONE' ? ` · ${detailedFailures} échec(s) et ${detailedSamples} réussite(s) détaillés` : ''}
        </span>
      </div>
      {detailSampleCapReached && (
        <div className="pt-alert-banner warning mb-2" role="status">
          <i className="bi bi-exclamation-triangle"></i>
          Plafond mémoire du détail des requêtes atteint : l’échantillon des requêtes réussies a été arrêté en cours d’exécution.
          Les échecs ont continué d’être enregistrés.
        </div>
      )}
      {steps.length === 0 ? (
        <p className="text-muted mb-0">Aucune requête enregistrée pour cette exécution.</p>
      ) : (
        <ul className="ed-steps">
          {steps.map((s, i) => {
            const key = stepKey(s, i)
            const open = openKey === key
            return (
              <li key={key} className={open ? 'open' : undefined}>
                <button type="button" className="ed-step-head" aria-expanded={open} onClick={() => setOpenKey(open ? null : key)}>
                  <i className={`bi ${open ? 'bi-chevron-down' : 'bi-chevron-right'}`} aria-hidden="true"></i>
                  <span className="ed-step-name">{i + 1}. {s.method && <span className={`sw-method-badge sw-method-${s.method.toLowerCase()}`}>{s.method}</span>} {s.stepName}</span>
                  <span className="ed-step-stats">
                    {s.total} requête(s) · <span className={s.failed > 0 ? 'text-danger fw-semibold' : 'text-success'}>{s.failed} échec(s)</span>
                    {s.avgResponseTime != null ? ` · moy. ${s.avgResponseTime} ms` : ''}
                  </span>
                </button>
                {open && <StepRequests executionId={executionId} step={s} virtualUsers={virtualUsers} />}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
