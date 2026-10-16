import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Pagination from '../../components/Pagination'
import { dashboardBackendApi } from '../../services/api/dashboardBackend'
import { BackendDashboardScenarioRow, BackendPagedResponse } from '../../types/backendContracts'
import { formatMs, formatPercent, formatRps } from '../../utils/numberFormat'
import { executionStatusMeta, formatDateTime, trendDisplay } from './dashboardModel'

// ============================================================
// Dashboard > « Dernière exécution par scénario » : paginé CÔTÉ SERVEUR
// (GET /api/dashboard/latest-by-scenario), 10 lignes par page, de la plus récente à la plus
// ancienne, mêmes filtres que le reste du Dashboard (application, période).
// Changement de page : l'ancienne page reste visible (atténuée) pendant le chargement, puis la
// nouvelle apparaît par un léger fondu (aucun saut de hauteur, aucune animation lourde).
// ============================================================

export const DASHBOARD_PAGE_SIZE = 10

interface Props {
  applicationId?: string
  range: { from?: string; to?: string }
  /** Augmente quand le Dashboard se rafraîchit tout seul (toutes les 10 s pendant une exécution) : page relue sans fondu. */
  refreshKey?: number
}

export default function LatestByScenarioTable({ applicationId, range, refreshKey }: Props) {
  const navigate = useNavigate()
  // Page liée aux filtres : un nouveau filtre ramène en page 1 tout de suite (une seule requête, jamais
  // une première avec l'ancienne page).
  const filterKey = `${applicationId ?? ''}|${range.from ?? ''}|${range.to ?? ''}`
  const [pageState, setPageState] = useState({ key: filterKey, page: 1 })
  const page = pageState.key === filterKey ? pageState.page : 1
  const setPage = (p: number) => setPageState({ key: filterKey, page: p })
  const [data, setData] = useState<BackendPagedResponse<BackendDashboardScenarioRow> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  /** Augmente à chaque nouvelle page affichée : rejoue le fondu une fois (jamais pour un simple rafraîchissement). */
  const [shown, setShown] = useState(0)
  const request = useRef(0)
  const lastPage = useRef<number | null>(null)

  useEffect(() => {
    const id = ++request.current
    setLoading(true)
    dashboardBackendApi.getLatestByScenario({ applicationId, ...range, page: page - 1, size: DASHBOARD_PAGE_SIZE })
      .then((res) => {
        if (id !== request.current) return
        // Page devenue vide (moins de scénarios sur la période) : dernière page existante.
        if (res.content.length === 0 && res.totalPages > 0 && page > res.totalPages) { setPage(res.totalPages); return }
        setData(res)
        setError(null)
        if (lastPage.current !== page) { lastPage.current = page; setShown((n) => n + 1) }
      })
      .catch((err) => { if (id === request.current) setError(err instanceof Error ? err.message : 'Erreur inconnue') })
      .finally(() => { if (id === request.current) setLoading(false) })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey, page, refreshKey])

  const rows = data?.content ?? []
  const total = data?.totalElements ?? 0
  const start = total === 0 ? 0 : (page - 1) * DASHBOARD_PAGE_SIZE + 1

  return (
    <div className="pt-card mb-3 db-card" style={{ padding: 0 }}>
      <div className="db-card-head" style={{ padding: '1rem 1rem 0.5rem' }}>
        <h2 className="db-title mb-0"><i className="bi bi-list-check"></i>Dernière exécution par scénario</h2>
        <span className="db-muted">Clic sur une ligne : rapport de l’exécution</span>
      </div>
      {error && !data ? (
        <p className="db-empty" role="alert" style={{ padding: '0 1rem 1rem' }}>Impossible de charger les scénarios : {error}</p>
      ) : !data ? (
        <p className="db-empty" style={{ padding: '0 1rem 1rem' }}>Chargement…</p>
      ) : rows.length === 0 ? (
        <p className="db-empty" style={{ padding: '0 1rem 1rem' }}>Aucune exécution sur la période.</p>
      ) : (
        <>
          <div className={`pt-table-wrapper db-page${loading ? ' loading' : ''}`} aria-busy={loading || undefined}>
            <table className="pt-table db-table">
              <thead>
                <tr>
                  <th>Scénario</th><th>Application</th><th>Dernière exécution</th><th>Statut</th>
                  <th className="text-end">P95</th><th className="text-end">Erreurs</th><th className="text-end">Débit</th>
                  <th className="text-end" title="CPU maximal du processus de l’application testée (si monitoring configuré), en % d’un cœur">CPU max</th>
                  <th className="text-end" title="Écart du P95 avec l’exécution précédente du même scénario">vs précédente</th>
                </tr>
              </thead>
              {/* key : nouveau tbody à chaque page affichée → le fondu se joue une fois. */}
              <tbody key={shown} className="db-fade-in">
                {rows.map((r) => {
                  const t = trendDisplay(r.trend, r.p95ChangePercent)
                  const open = () => navigate(`/executions/report/${r.executionId}`)
                  return (
                    <tr key={r.executionId} className="db-row" tabIndex={0} onClick={open} onKeyDown={(e) => { if (e.key === 'Enter') open() }}
                      data-testid="scenario-row">
                      <td data-label="Scénario" className="fw-semibold">{r.scenarioName}</td>
                      <td data-label="Application" className="db-muted">{r.applicationName}</td>
                      <td data-label="Dernière exécution">{formatDateTime(r.startedAt)}</td>
                      <td data-label="Statut"><span className={`pt-pill ${executionStatusMeta(r.status).pill}`}>{executionStatusMeta(r.status).label}</span></td>
                      <td data-label="P95" className="text-end">{formatMs(r.p95Ms)}</td>
                      <td data-label="Erreurs" className="text-end">{formatPercent(r.errorRate)}</td>
                      <td data-label="Débit" className="text-end">{formatRps(r.throughputRps)}</td>
                      <td data-label="CPU max" className="text-end">{r.cpuMaxPercent == null ? '—' : formatPercent(r.cpuMaxPercent)}</td>
                      <td data-label="vs précédente" className={`text-end db-trend ${t.tone}`} title={t.title}>
                        {t.arrow && <span aria-hidden="true">{t.arrow} </span>}{t.text}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={Math.max(1, data.totalPages)} onPageChange={setPage} startIndex={start}
            endIndex={Math.min(total, start + rows.length - 1)} totalItems={total} itemLabel={total > 1 ? 'scénarios' : 'scénario'} />
        </>
      )}
    </div>
  )
}
