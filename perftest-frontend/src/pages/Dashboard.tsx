import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Line, Doughnut } from 'react-chartjs-2'
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  ArcElement,
  Title,
  Tooltip,
  Legend,
  Filler,
} from 'chart.js'
import TopBar from '../components/TopBar'
import { Application, Scenario, Execution } from '../types'
import { applicationsApi } from '../services/api/applications'
import { scenariosApi } from '../services/api/scenarios'
import { executionsApi } from '../services/api/executions'
import { useApiList } from '../hooks/useApiResource'
import { getStats, formatDuration } from '../data/dashboardStats'

ChartJS.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  ArcElement,
  Title,
  Tooltip,
  Legend,
  Filler
)

function Dashboard() {
  const navigate = useNavigate()
  // Données réelles depuis JSON Server — rechargées à chaque montage, donc
  // toujours à jour après une création/modification/suppression ailleurs
  // dans l'application.
  const { data: allApplications } = useApiList<Application>(() => applicationsApi.getAll())
  const { data: allScenarios } = useApiList<Scenario>(() => scenariosApi.getAll())
  const { data: allExecutions } = useApiList<Execution>(() => executionsApi.getAll())

  const activeApplications = allApplications.filter((a) => a.status === 'Actif')

  const [selectedAppId, setSelectedAppId] = useState<string | 'all'>('all')

  const realData = useMemo(
    () => ({ applications: allApplications, scenarios: allScenarios, executions: allExecutions }),
    [allApplications, allScenarios, allExecutions]
  )

  const stats = useMemo(() => getStats(selectedAppId, realData), [selectedAppId, realData])

  const selectedAppName =
    selectedAppId === 'all'
      ? 'Toutes les applications'
      : activeApplications.find((a) => String(a.id) === String(selectedAppId))?.name ?? 'Application'

  const dayLabels = ['J-6', 'J-5', 'J-4', 'J-3', 'J-2', 'J-1', "Aujourd'hui"]

  const responseTimeData = {
    labels: dayLabels,
    datasets: [
      {
        label: 'Temps de réponse (ms)',
        data: stats.responseTimeSeries,
        borderColor: '#4F46E5',
        backgroundColor: 'rgba(79,70,229,0.08)',
        borderWidth: 2,
        tension: 0.4,
        fill: true,
        pointBackgroundColor: '#4F46E5',
        pointRadius: 4,
      },
    ],
  }

  const responseTimeOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
    },
    scales: {
      x: {
        grid: { display: false },
        ticks: { color: '#9CA3AF', font: { size: 11 } },
      },
      y: {
        grid: { color: '#F3F4F6' },
        ticks: { color: '#9CA3AF', font: { size: 11 } },
      },
    },
  }

  const executionStatusData = {
    labels: ['Réussies', 'En cours', 'Échouées', 'Annulées'],
    datasets: [
      {
        data: [
          stats.executionStatus.success,
          stats.executionStatus.inProgress,
          stats.executionStatus.failed,
          stats.executionStatus.cancelled,
        ],
        backgroundColor: ['#22C55E', '#3B82F6', '#EF4444', '#A3A3A3'],
        borderWidth: 0,
        cutout: '70%',
      },
    ],
  }

  const executionStatusOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        position: 'right' as const,
        labels: {
          padding: 10,
          usePointStyle: true,
          pointStyle: 'circle',
          font: { size: 11.5 },
          color: '#6B7280',
        },
      },
    },
  }

  const executionsTotal =
    stats.executionStatus.success +
    stats.executionStatus.inProgress +
    stats.executionStatus.failed +
    stats.executionStatus.cancelled

  return (
    <>
      <div className="pt-content">
        {/* Page Header */}
        <div className="pt-page-header">
          <div className="page-title">
            <h1>Dashboard</h1>
            <p>
              Vue d'ensemble de la plateforme — <strong>{selectedAppName}</strong>
            </p>
          </div>
          <div className="d-flex align-items-center gap-2 flex-wrap">
            <select
              className="pt-form-control"
              style={{ width: 'auto', minWidth: '220px' }}
              value={selectedAppId}
              onChange={(e) => setSelectedAppId(e.target.value === 'all' ? 'all' : e.target.value)}
            >
              <option value="all">Toutes les applications</option>
              {activeApplications.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>

            <TopBar searchPlaceholder="" />
          </div>
        </div>

        {/* Metric Cards - Row 1 */}
        <div className="row g-3 mb-4">
          <div className="col-12 col-md-6 col-xl">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">Temps de réponse moyen</div>
                  <div className="stat-value">{stats.avgResponseTime} ms</div>
                </div>
                <div className="stat-icon blue">
                  <i className="bi bi-clock-history"></i>
                </div>
              </div>
            </div>
          </div>
          <div className="col-12 col-md-6 col-xl">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">Throughput moyen</div>
                  <div className="stat-value">{stats.throughput.toLocaleString('fr-FR')} req/s</div>
                </div>
                <div className="stat-icon green">
                  <i className="bi bi-lightning-charge"></i>
                </div>
              </div>
            </div>
          </div>
          <div className="col-12 col-md-6 col-xl">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">Taux d'erreurs</div>
                  <div className="stat-value">{stats.errorRate}%</div>
                </div>
                <div className="stat-icon red">
                  <i className="bi bi-exclamation-triangle"></i>
                </div>
              </div>
            </div>
          </div>
          <div className="col-12 col-md-6 col-xl">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">Utilisateurs actifs</div>
                  <div className="stat-value">{stats.activeUsers}</div>
                </div>
                <div className="stat-icon orange">
                  <i className="bi bi-people"></i>
                </div>
              </div>
            </div>
          </div>
          <div className="col-12 col-md-6 col-xl">
            <div className="pt-stat-card">
              <div className="stat-header">
                <div>
                  <div className="stat-label">Tests en cours</div>
                  <div className="stat-value">{stats.testsInProgress}</div>
                  <div className="stat-trend neutral">actuellement</div>
                </div>
                <div className="stat-icon purple">
                  <i className="bi bi-play-circle"></i>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Charts Row */}
        <div className="row g-3 mb-4">
          <div className="col-12 col-lg-7">
            <div className="pt-card">
              <div className="d-flex justify-content-between align-items-center mb-3">
                <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>
                  Évolution des temps de réponse (ms)
                </h6>
                <span className="pt-pill info">7 jours</span>
              </div>
              <div style={{ height: '280px' }}>
                <Line data={responseTimeData} options={responseTimeOptions} />
              </div>
            </div>
          </div>
          <div className="col-12 col-lg-5">
            <div className="pt-card">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '1rem' }}>
                Statut des exécutions
              </h6>
              <div style={{ position: 'relative', height: '280px' }}>
                <Doughnut data={executionStatusData} options={executionStatusOptions} />
                <div
                  style={{
                    position: 'absolute',
                    top: '50%',
                    left: '38%',
                    transform: 'translate(-50%, -50%)',
                    textAlign: 'center',
                  }}
                >
                  <div style={{ fontSize: '32px', fontWeight: 700, color: 'var(--pt-text)' }}>{executionsTotal}</div>
                  <div style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>Total</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Metric Cards - Row 2 */}
        <div className="row g-3 mb-4">
          <div className="col-6 col-md-3 col-xl">
            <div className="pt-stat-card" role="button" onClick={() => navigate('/scenarios')} style={{ cursor: 'pointer' }}>
              <div className="stat-label">Scénarios</div>
              <div className="stat-value">{stats.scenarios}</div>
            </div>
          </div>
          <div className="col-6 col-md-3 col-xl">
            <div className="pt-stat-card" role="button" onClick={() => navigate('/applications')} style={{ cursor: 'pointer' }}>
              <div className="stat-label">Applications</div>
              <div className="stat-value">{selectedAppId === 'all' ? activeApplications.length : 1}</div>
              <div className="stat-trend neutral">{selectedAppId === 'all' ? 'actives' : selectedAppName}</div>
            </div>
          </div>
          <div className="col-6 col-md-3 col-xl">
            <div className="pt-stat-card" role="button" onClick={() => navigate('/executions')} style={{ cursor: 'pointer' }}>
              <div className="stat-label">Exécutions</div>
              <div className="stat-value">{stats.executions}</div>
            </div>
          </div>
          <div className="col-6 col-md-3 col-xl">
            <div className="pt-stat-card">
              <div className="stat-label">Durée moyenne des tests</div>
              <div className="stat-value" style={{ fontSize: '22px' }}>{formatDuration(stats.avgDurationSec)}</div>
            </div>
          </div>
        </div>

        {/* Bottom Section - 2 columns */}
        <div className="row g-3">
          {/* Recent Executions */}
          <div className="col-12 col-lg-6">
            <div className="pt-card">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '1rem' }}>
                Exécutions récentes
              </h6>
              <div className="d-flex flex-column gap-3">
                {stats.recentExecutions.map((exec, i) => (
                  <div
                    key={i}
                    className="d-flex align-items-center gap-3"
                    role="button"
                    onClick={() => navigate('/executions')}
                    style={{ cursor: 'pointer' }}
                  >
                    <i className={`bi ${exec.icon}`} style={{ color: `var(--pt-${exec.color})`, fontSize: '18px' }}></i>
                    <div className="flex-grow-1">
                      <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)' }}>{exec.name}</div>
                      <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{exec.time}</div>
                    </div>
                    <span className={`pt-pill ${exec.color}`}>{exec.status}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* Most Used Scenarios */}
          <div className="col-12 col-lg-6">
            <div className="pt-card">
              <h6 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '1rem' }}>
                Scénarios les plus utilisés
              </h6>
              <div className="d-flex flex-column gap-3">
                {stats.topScenarios.map((scenario, i) => (
                  <div
                    key={i}
                    role="button"
                    onClick={() => navigate(`/scenarios?q=${encodeURIComponent(scenario.name)}`)}
                    style={{ cursor: 'pointer' }}
                  >
                    <div className="d-flex justify-content-between mb-1">
                      <span style={{ fontSize: '13px', fontWeight: 500, color: 'var(--pt-text)' }}>
                        {i + 1}. {scenario.name}
                      </span>
                      <span style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>{scenario.percent}%</span>
                    </div>
                    <div style={{ height: '6px', background: 'var(--pt-border)', borderRadius: '4px', overflow: 'hidden' }}>
                      <div
                        style={{
                          height: '100%',
                          width: `${scenario.percent}%`,
                          background: 'var(--pt-primary)',
                          borderRadius: '4px',
                        }}
                      ></div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </>
  )
}

export default Dashboard
