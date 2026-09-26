import { useEffect, useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { BackendExecutionHistoryResponse, BackendExecutionStatus } from '../types/backendContracts'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { ApiError } from '../services/api/httpClient'

// ============================================================
// P1-A — "Rapports" est volontairement un point d'entree RAPIDE vers les
// executions les plus RECENTES deja terminees (SUCCESS/FAILED/CANCELLED —
// un rapport statistique complet n'a de sens que sur une execution
// terminee, jamais QUEUED/RUNNING), pas une seconde copie du filtrage
// exhaustif d'Historique.tsx (qui reste le seul endroit pour
// rechercher/filtrer/trier/paginer - prompt P1-A, section 11 : "Ne crée
// pas deux pages différentes affichant exactement les mêmes données sans
// raison"). Reutilise le MEME endpoint reel GET /api/executions/history
// (juste une page plus petite, non filtree) : aucune nouvelle logique
// backend, aucune donnee fictive.
//
// Chaque rapport ouvre /executions/report/:id, qui reutilise
// ExecutionReport.tsx (enrichi en P1-A de percentiles reels/configuration
// de charge/agregation par step/export CSV) - jamais une page dupliquee.
// ============================================================

const RECENT_COUNT = 10

const statusLabel: Record<BackendExecutionStatus, string> = {
  QUEUED: 'En attente', RUNNING: 'En cours', SUCCESS: 'Réussie', FAILED: 'Échouée', CANCELLED: 'Annulée',
}
const statusPillClass: Record<BackendExecutionStatus, string> = {
  QUEUED: 'neutral', RUNNING: 'info', SUCCESS: 'success', FAILED: 'danger', CANCELLED: 'neutral',
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0: return err.message
      case 401: return 'Vous devez être connecté (Keycloak) pour consulter les rapports.'
      case 403: return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function formatDate(iso: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

function formatDuration(ms: number | null): string {
  if (ms == null) return '—'
  const totalSec = Math.round(ms / 1000)
  const m = String(Math.floor(totalSec / 60)).padStart(2, '0')
  const s = String(totalSec % 60).padStart(2, '0')
  return `${m}:${s}`
}

function Rapports() {
  const navigate = useNavigate()
  const [executions, setExecutions] = useState<BackendExecutionHistoryResponse[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    setLoading(true)
    setError(null)
    // Un seul appel par statut terminal, combines cote client (3 requetes
    // legeres plutot qu'un filtre "status IN (...)" non supporte par
    // l'API actuelle - voir ExecutionController#history, un seul statut a
    // la fois) - jamais un chargement de toute la table.
    Promise.all([
      executionsBackendApi.getHistory({ status: 'SUCCESS', page: 0, size: RECENT_COUNT, sort: 'startedAt,desc' }),
      executionsBackendApi.getHistory({ status: 'FAILED', page: 0, size: RECENT_COUNT, sort: 'startedAt,desc' }),
      executionsBackendApi.getHistory({ status: 'CANCELLED', page: 0, size: RECENT_COUNT, sort: 'startedAt,desc' }),
    ])
      .then(([success, failed, cancelled]) => {
        const merged = [...success.content, ...failed.content, ...cancelled.content]
          .sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime())
          .slice(0, RECENT_COUNT)
        setExecutions(merged)
      })
      .catch((err) => setError(describeApiError(err, 'Impossible de charger les rapports récents.')))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
  }, [])

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Rapports</h1>
          <p>Rapports de tests de performance, générés depuis les vraies exécutions terminées</p>
        </div>
        <div className="d-flex align-items-center gap-2">
          <Link to="/historique" className="pt-btn-outline">
            <i className="bi bi-clock-history me-1"></i>Voir tout l'historique
          </Link>
          <TopBar searchPlaceholder="" />
        </div>
      </div>

      {error && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {error}
          <button className="pt-btn-outline ms-auto" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={load}>Réessayer</button>
        </div>
      )}

      <div className="pt-card" style={{ padding: 0 }}>
        <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Rapports récents</h6>
        </div>

        {loading ? (
          <div className="pt-empty-state">
            <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
            <p>Chargement des rapports...</p>
          </div>
        ) : !executions || executions.length === 0 ? (
          <div className="pt-empty-state">
            <i className="bi bi-file-earmark-bar-graph" style={{ fontSize: '28px', color: 'var(--pt-text-light)' }}></i>
            <p>Aucune exécution terminée pour le moment — lancez un test depuis Scénarios ou Exécutions.</p>
          </div>
        ) : (
          <div className="pt-table-wrapper">
            <table className="pt-table">
              <thead>
                <tr>
                  <th>Scénario</th>
                  <th>Application</th>
                  <th>Statut</th>
                  <th>Terminée le</th>
                  <th>Durée</th>
                  <th>Étapes</th>
                  <th style={{ textAlign: 'right' }}>Rapport</th>
                </tr>
              </thead>
              <tbody>
                {executions.map((exec) => (
                  <tr key={exec.id} style={{ cursor: 'pointer' }} onClick={() => navigate(`/executions/report/${exec.id}`)}>
                    <td><span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-primary)' }}>{exec.scenarioName}</span></td>
                    <td><span style={{ fontSize: '13px', color: 'var(--pt-text-muted)' }}>{exec.applicationName}</span></td>
                    <td><span className={`pt-pill ${statusPillClass[exec.status]}`} style={{ fontSize: '11px' }}>{statusLabel[exec.status]}</span></td>
                    <td><span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>{formatDate(exec.finishedAt)}</span></td>
                    <td><span style={{ fontSize: '13px', fontFamily: 'monospace' }}>{formatDuration(exec.duration)}</span></td>
                    <td>
                      <span style={{ fontSize: '12.5px' }}>
                        <span style={{ color: 'var(--pt-success)', fontWeight: 600 }}>{exec.successfulSteps}</span>
                        {' / '}
                        <span style={{ color: 'var(--pt-danger)', fontWeight: 600 }}>{exec.failedSteps}</span>
                      </span>
                    </td>
                    <td style={{ textAlign: 'right' }} onClick={(e) => e.stopPropagation()}>
                      <button
                        className="topbar-icon"
                        style={{ width: '30px', height: '30px', border: '1px solid var(--pt-border)' }}
                        title="Voir le rapport"
                        onClick={() => navigate(`/executions/report/${exec.id}`)}
                      >
                        <i className="bi bi-arrow-right" style={{ fontSize: '13px' }}></i>
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}

export default Rapports
