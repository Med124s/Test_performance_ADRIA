import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { BackendExecutionReportResponse } from '../types/backendContracts'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { ApiError } from '../services/api/httpClient'
import { useToast } from '../context/ToastContext'
import { formatPause } from './ExecutionLive'
import { executionStatusMeta } from '../utils/executionStatus'
import { formatNumber, formatPercent } from '../utils/numberFormat'
import TimelineAnalysis from './execution-report/TimelineAnalysis'
import ExtremesPanel from './execution-report/ExtremesPanel'

// ============================================================
// Phase 23 — même politique de double chaîne que ExecutionDetail.tsx : un
// id Spring Boot est toujours un UUID v4 réel, jamais un id JSON Server
// dans ce projet — voir ExecutionDetail.tsx pour la justification complète.
// ============================================================
const SPRING_UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0:
        return err.message
      case 401:
        return 'Vous devez être connecté pour consulter ce rapport.'
      case 403:
        return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      case 404:
        return 'Exécution introuvable (elle a peut-être déjà été supprimée).'
      default:
        return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function formatDurationMs(ms: number | null): string {
  if (ms == null) return '—'
  const totalSec = Math.round(ms / 1000)
  const h = String(Math.floor(totalSec / 3600)).padStart(2, '0')
  const m = String(Math.floor((totalSec % 3600) / 60)).padStart(2, '0')
  const s = String(totalSec % 60).padStart(2, '0')
  return `${h}:${m}:${s}`
}

// ------------------------------------------------------------
// Vue Spring Boot (P1-A, remplace l'ancienne version Phase 23) — rapport
// basé uniquement sur GET /api/executions/{id}/report (voir
// ExecutionController#getReport / PerformanceStatisticsService cote
// backend) : statistiques REELLEMENT calculees, percentiles reels
// (p50/p75/p90/p95/p99 - le backend ne les calculait nulle part avant
// P1-A, voir rapport P0-B), configuration de charge reellement figee au
// lancement, agregation reelle par step, erreurs reelles. Plus aucun appel
// a executionsBackendApi.getById/metricsBackendApi.search : un seul appel
// reseau suffit desormais (evite toute divergence entre deux sources).
// ------------------------------------------------------------
function SpringExecutionReport({ id }: { id: string }) {
  const { showToast } = useToast()
  const [report, setReport] = useState<BackendExecutionReportResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [exporting, setExporting] = useState(false)
  const [extreme, setExtreme] = useState<'MIN' | 'MAX' | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    executionsBackendApi.getReport(id)
      .then((r) => { if (!cancelled) setReport(r) })
      .catch((err) => setError(describeApiError(err, 'Exécution introuvable.')))
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [id])

  const handleExport = async () => {
    if (exporting) return
    setExporting(true)
    try {
      const blob = await executionsBackendApi.exportReportCsv(id)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `execution-report-${id}.csv`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      showToast('Export CSV généré avec succès.', 'success')
    } catch (err) {
      showToast(describeApiError(err, "Erreur lors de l'export."), 'danger')
    } finally {
      setExporting(false)
    }
  }

  if (loading) {
    return (
      <div className="pt-content">
        <div className="pt-empty-state">
          <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
          <p>Chargement du rapport...</p>
        </div>
      </div>
    )
  }

  if (error || !report) {
    return (
      <div className="pt-content">
        <div className="pt-alert-banner danger">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {error ?? 'Exécution introuvable.'}
        </div>
        <Link to="/executions" className="pt-btn-outline mt-3 d-inline-block">
          <i className="bi bi-arrow-left"></i> Retour aux exécutions
        </Link>
      </div>
    )
  }

  // Carte « Statut » et badge : table unique des statuts (utils/executionStatus), statut inconnu en gris.
  const statusInfo = executionStatusMeta(report.status)
  const meta = { cls: statusInfo.pill, color: statusInfo.color, text: statusInfo.label, icon: statusInfo.outlineIcon, iconColor: statusInfo.iconColor }
  const stats = report.statistics
  const fmt = (v: number | null | undefined, unit = ''): string => (v == null ? 'N/D' : `${Math.round(v * 100) / 100}${unit}`)
  const fmtMs = (v: number | null | undefined): string => (v == null ? 'N/D' : `${v} ms`)

  return (
    <div className="pt-content">
      <div className="mb-3">
        <Link to="/executions" className="text-decoration-none d-inline-flex align-items-center gap-1 text-muted small fw-semibold">
          <i className="bi bi-chevron-left"></i> Retour aux exécutions
        </Link>
      </div>

      <div className="pt-page-header">
        <div className="page-title">
          <div className="d-flex align-items-center gap-3 flex-wrap">
            <h1>Rapport exécution — {report.scenarioName}</h1>
            <span className={`pt-pill ${meta.cls}`}>
              <span style={{ width: '6px', height: '6px', borderRadius: '50%', backgroundColor: meta.color, display: 'inline-block' }}></span>
              {meta.text}
            </span>
          </div>
          <p>Exécution lancée le {new Date(report.startedAt).toLocaleString('fr-FR')} sur <strong>{report.applicationName}</strong></p>
        </div>
        <div className="d-flex align-items-center gap-2">
          <button className="pt-btn-outline" onClick={handleExport} disabled={exporting}>
            <i className={`bi ${exporting ? 'bi-arrow-repeat pt-spin' : 'bi-download'}`}></i>
            {exporting ? 'Export...' : 'Exporter CSV'}
          </button>
          <TopBar searchPlaceholder="" />
        </div>
      </div>

      <div className="pt-card mb-3 py-2 px-3">
        <div className="row g-2 align-items-center text-muted" style={{ fontSize: '13px' }}>
          <div className="col-12 col-md-auto d-flex align-items-center gap-2 me-3">
            <i className="bi bi-collection-play text-primary"></i>
            <span>Scénario: <Link to={`/scenarios?q=${encodeURIComponent(report.scenarioName)}`} className="text-dark fw-bold text-decoration-none">{report.scenarioName}</Link></span>
          </div>
        </div>
      </div>

      {/* Configuration de charge REELLEMENT figee au lancement (voir
          Execution.virtualUsers et suivants, P0-A) — jamais celle,
          potentiellement differente, du Scenario aujourd'hui. */}
      <div className="d-flex align-items-center gap-2 mb-2" style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-sliders" style={{ color: 'var(--pt-primary)' }}></i> CONFIGURATION DE CHARGE (figée au lancement)
      </div>
      <div className="d-flex flex-wrap gap-2 mb-4">
        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-people me-1"></i>{report.virtualUsers} utilisateur(s) virtuel(s)</span>
        <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-graph-up-arrow me-1"></i>Ramp-up {report.rampUpSeconds}s</span>
        {report.durationSeconds != null && (
          <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-stopwatch me-1"></i>Durée cible {report.durationSeconds}s</span>
        )}
        {report.iterations != null && (
          <span className="pt-pill neutral" style={{ fontSize: '11.5px' }}><i className="bi bi-arrow-repeat me-1"></i>{report.iterations} itération(s)</span>
        )}
        {/* P1-C — utilisateur ayant réellement lancé cette exécution (voir
            P1-B, Execution.triggeredBy) — absent pour toute exécution
            antérieure à P1-B, jamais deviné. */}
        {report.triggeredByUsername && (
          <span className="pt-pill info" style={{ fontSize: '11.5px' }}><i className="bi bi-person-fill me-1"></i>Lancée par {report.triggeredByUsername}</span>
        )}
        {report.pausedDurationMs > 0 && (
          <span className="pt-pill warning" style={{ fontSize: '11.5px' }}><i className="bi bi-pause-circle me-1"></i>En pause pendant {formatPause(report.pausedDurationMs)}</span>
        )}
      </div>

      {/* Seuils de reussite copies au lancement et leur resultat (absents des executions anterieures). */}
      {report.thresholds && (
        <>
          <div className="d-flex align-items-center gap-2 mb-2" style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)' }}>
            <i className="bi bi-bullseye" style={{ color: 'var(--pt-primary)' }}></i> SEUILS DE RÉUSSITE
          </div>
          <ul className="rp-thresholds mb-4" aria-label="Seuils de réussite">
            <li className={report.thresholds.errorRateOk ? 'ok' : 'ko'}>
              <span className="rp-th-mark" aria-label={report.thresholds.errorRateOk ? 'respecté' : 'dépassé'}>{report.thresholds.errorRateOk ? '✓' : '✕'}</span>
              Taux d’erreurs <strong>{formatPercent(report.thresholds.errorRatePercent)}</strong>
              <span className="rp-th-limit">maximum {formatPercent(report.thresholds.maxErrorRatePercent)}</span>
            </li>
            {report.thresholds.maxP95Ms != null ? (
              <li className={report.thresholds.p95Ok === false ? 'ko' : 'ok'}>
                <span className="rp-th-mark" aria-label={report.thresholds.p95Ok === false ? 'dépassé' : 'respecté'}>{report.thresholds.p95Ok === false ? '✕' : '✓'}</span>
                P95 <strong>{report.thresholds.p95Ms == null ? 'N/D' : `${formatNumber(report.thresholds.p95Ms, 0)} ms`}</strong>
                <span className="rp-th-limit">maximum {formatNumber(report.thresholds.maxP95Ms, 0)} ms</span>
              </li>
            ) : (
              <li className="none"><span className="rp-th-mark">–</span>P95 <span className="rp-th-limit">aucun seuil défini pour ce scénario</span></li>
            )}
          </ul>
        </>
      )}
      {/* Statistiques de temps de reponse — min/max/moyenne (jamais de
          valeur inventee : "N/D" si aucune requete n'a de temps mesurable). */}
      <div className="d-flex align-items-center gap-2 mb-2" style={{ fontSize: '11.5px', fontWeight: 700, letterSpacing: '0.06em', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-speedometer2" style={{ color: 'var(--pt-primary)' }}></i> TEMPS DE RÉPONSE (millisecondes)
      </div>
      <div className="row g-3 mb-4">
        {([
          { label: 'Minimum', value: fmtMs(stats.minResponseTime), icon: 'bi-arrow-down-circle', color: 'green', extreme: 'MIN' },
          { label: 'Moyenne', value: fmtMs(stats.avgResponseTime), icon: 'bi-lightning-charge', color: 'blue' },
          { label: 'Maximum', value: fmtMs(stats.maxResponseTime), icon: 'bi-arrow-up-circle', color: 'red', extreme: 'MAX' },
          { label: 'p95', value: fmtMs(stats.p95), icon: 'bi-graph-up', color: 'orange' },
          { label: 'p99', value: fmtMs(stats.p99), icon: 'bi-graph-up', color: 'purple' },
        ] as { label: string; value: string; icon: string; color: string; extreme?: 'MIN' | 'MAX' }[]).map((kpi, idx) => {
          const card = (
            <div className="pt-stat-card">
              <div className="stat-header">
                <span className="stat-label">{kpi.label}</span>
                <div className={`stat-icon ${kpi.color}`}><i className={`bi ${kpi.icon}`}></i></div>
              </div>
              <div className="stat-value" style={{ fontSize: '20px' }}>{kpi.value}</div>
              {kpi.extreme && stats.totalRequests > 0 && (
                <div className="rp-card-hint"><i className="bi bi-search me-1"></i>{kpi.extreme === 'MAX' ? 'Voir la requête la plus lente' : 'Voir la requête la plus rapide'}</div>
              )}
            </div>
          )
          // Colonnes qui s'élargissent pour remplir chaque ligne : jamais de trou, quelle que soit la largeur.
          return (
            <div className="col" style={{ flex: '1 1 150px' }} key={idx}>
              {kpi.extreme && stats.totalRequests > 0 ? (
                <button type="button" className="rp-card-button w-100" onClick={() => setExtreme(kpi.extreme!)}
                  aria-label={`${kpi.label} ${kpi.value} : voir d’où vient cette valeur`}>{card}</button>
              ) : card}
            </div>
          )
        })}
      </div>

      {/* Summary cards — champs REELS uniquement, issus de PerformanceStatisticsService */}
      <div className="row g-3 mb-4">
        <div className="col" style={{ flex: '1 1 180px' }}>
          <div className="pt-stat-card">
            <div className="stat-header">
              <div style={{ minWidth: 0 }}><div className="stat-label">Statut</div><div className="stat-value" style={{ fontSize: meta.text.length > 12 ? '16px' : '18px', marginTop: '4px', color: meta.color, whiteSpace: 'normal', lineHeight: 1.2 }}>{meta.text}</div></div>
              <div className={`stat-icon ${meta.iconColor}`}><i className={`bi ${meta.icon}`} aria-hidden="true"></i></div>
            </div>
          </div>
        </div>
        <div className="col" style={{ flex: '1 1 180px' }}>
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Durée réelle</div>
                <div className="stat-value">{formatDurationMs(report.duration)}</div>
                {report.pausedDurationMs > 0 && <div className="stat-trend" style={{ color: 'var(--pt-warning)' }}>dont {formatPause(report.pausedDurationMs)} en pause</div>}
              </div>
              <div className="stat-icon purple"><i className="bi bi-stopwatch"></i></div>
            </div>
          </div>
        </div>
        <div className="col" style={{ flex: '1 1 180px' }}>
          <div className="pt-stat-card">
            <div className="stat-header">
              <div>
                <div className="stat-label">Requêtes</div>
                <div className="stat-value">{stats.successfulRequests} / {stats.totalRequests}</div>
                <div className="stat-trend positive">{fmt(stats.successRate, '%')} succès</div>
              </div>
              <div className="stat-icon orange"><i className="bi bi-arrow-repeat"></i></div>
            </div>
          </div>
        </div>
        <div className="col" style={{ flex: '1 1 180px' }}>
          <div className="pt-stat-card">
            <div className="stat-header">
              <div><div className="stat-label">Débit</div><div className="stat-value">{fmt(stats.throughput)}</div><div className="stat-trend positive">{report.pausedDurationMs > 0 ? 'req / s (hors pause)' : 'req / s'}</div></div>
              <div className="stat-icon blue"><i className="bi bi-speedometer"></i></div>
            </div>
          </div>
        </div>
        <div className="col" style={{ flex: '1 1 180px' }}>
          <div className="pt-stat-card">
            <div className="stat-header">
              <div><div className="stat-label">Taux d'erreur</div><div className="stat-value">{fmt(stats.errorRate, '%')}</div></div>
              <div className="stat-icon red"><i className="bi bi-exclamation-triangle"></i></div>
            </div>
          </div>
        </div>
      </div>

      {/* Analyse dans le temps : points clés, utilisateurs / débit / P95, par étape, erreurs. */}
      <TimelineAnalysis executionId={id} />

      {extreme && <ExtremesPanel executionId={id} kind={extreme} onClose={() => setExtreme(null)} />}
    </div>
  )
}

function ExecutionReport() {
  const { id } = useParams()
  return <SpringExecutionReport id={id ?? ''} />
}

export default ExecutionReport
