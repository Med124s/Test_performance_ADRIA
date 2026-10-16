import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, RouterProvider, Routes, createMemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../context/ToastContext'
import { ExecutionMonitorProvider } from '../context/ExecutionMonitorContext'
import { BackendExecutionStatus } from '../types/backendContracts'
import { EXECUTION_STATUSES, EXECUTION_STATUS_META, executionStatusMeta } from '../utils/executionStatus'

// ============================================================
// Chaque page qui affiche un statut d'exécution s'affiche sans erreur avec
// CHAQUE statut connu et avec un statut inconnu (badge gris + son nom) —
// garde-fou contre la régression « Cannot read properties of undefined
// (reading 'cls') » du Détail avec le statut « Seuils dépassés ».
// ============================================================

vi.mock('react-chartjs-2', () => ({ Bar: () => <div />, Line: () => <div />, Doughnut: () => <div /> }))
vi.mock('../components/TopBar', () => ({ default: () => <div /> }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: ['ROLE_PERFORMANCE_ENGINEER'] }) }))
const exec = vi.hoisted(() => ({
  getPage: vi.fn(), getSummary: vi.fn(), getReport: vi.fn(), getStatus: vi.fn(), getTimeline: vi.fn(), getExtremes: vi.fn(),
  compare: vi.fn(), cancel: vi.fn(), pause: vi.fn(), resume: vi.fn(), retry: vi.fn(), deletionImpact: vi.fn(), remove: vi.fn(), bulkDelete: vi.fn(),
}))
const dash = vi.hoisted(() => ({ getOverview: vi.fn(), getScenarioTrend: vi.fn(), getLatestByScenario: vi.fn() }))
const sched = vi.hoisted(() => ({ list: vi.fn(), getById: vi.fn(), history: vi.fn(), preview: vi.fn() }))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: exec }))
vi.mock('../services/api/dashboardBackend', () => ({ dashboardBackendApi: dash }))
vi.mock('../services/api/scheduledExecutionsBackend', () => ({ scheduledExecutionsBackendApi: sched }))
vi.mock('../services/api/metricsBackend', () => ({ metricsBackendApi: { search: () => Promise.resolve([]) } }))
vi.mock('../services/api/applicationsBackend', () => ({ applicationsBackendApi: { getAll: () => Promise.resolve([{ id: 'app-1', name: 'Banking' }]) } }))
vi.mock('../services/api/scenariosBackend', () => ({ scenariosBackendApi: { getAll: () => Promise.resolve([]) } }))
vi.mock('../services/api/stepsBackend', () => ({ stepsBackendApi: { getAll: () => Promise.resolve([]) } }))

import Executions from './Executions'
import ExecutionDetail from './ExecutionDetail'
import ExecutionReport from './ExecutionReport'
import ExecutionLive from './ExecutionLive'
import ExecutionCompare from './ExecutionCompare'
import Dashboard from './Dashboard'
import ScheduledExecutions from './ScheduledExecutions'
import ScheduleHistory from './scheduling/ScheduleHistory'
import RouteErrorPage, { NotFoundPage } from './RouteErrorPage'

const UNKNOWN = 'MYSTERY' as BackendExecutionStatus
const ALL: BackendExecutionStatus[] = [...EXECUTION_STATUSES, UNKNOWN]

const historyRow = (status: BackendExecutionStatus) => ({
  id: 'ex-1', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking', status,
  startedAt: '2026-10-04T10:00:00Z', finishedAt: '2026-10-04T10:01:00Z', duration: 60_000, virtualUsers: 10, rampUpSeconds: 5,
  durationSeconds: 60, iterations: null, totalSteps: 9, successfulSteps: 2000, failedSteps: 60, triggeredByUsername: 'admin', throughput: 33,
  cpuAvgPercent: 30, cpuMaxPercent: 42, memoryMaxMb: 80, p95Ms: 355, statusReason: null, previousP95Ms: 321, previousStartedAt: '2026-10-03T10:00:00Z',
})
const page = (status: BackendExecutionStatus) => ({
  content: [historyRow(status)], page: 0, size: 10, totalElements: 1, totalPages: 1,
  counts: { total: 1, queued: 0, running: 0, paused: 0, success: 0, failed: 0, cancelled: 0, thresholdsExceeded: 0 },
})
const report = (status: BackendExecutionStatus) => ({
  id: 'ex-1', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking', status,
  startedAt: '2026-10-04T10:00:00Z', finishedAt: '2026-10-04T10:01:00Z', duration: 60_000, errorMessage: null, virtualUsers: 10,
  rampUpSeconds: 5, durationSeconds: 60, iterations: null, triggeredByUsername: 'admin', pausedDurationMs: 0,
  statistics: { totalRequests: 2060, successfulRequests: 2000, failedRequests: 60, successRate: 97.1, errorRate: 2.9, minResponseTime: 1,
    maxResponseTime: 560, avgResponseTime: 210, p50: 200, p75: 240, p90: 300, p95: 355, p99: 430, throughput: 33 },
  steps: [], errors: [],
  thresholds: { maxErrorRatePercent: 1, errorRatePercent: 2.9, errorRateOk: false, maxP95Ms: null, p95Ms: 355, p95Ok: null },
})
const summary = (status: BackendExecutionStatus) => ({
  id: '11111111-2222-4333-8444-555555555555', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', status,
  startedAt: '2026-10-04T10:00:00Z', finishedAt: '2026-10-04T10:01:00Z', duration: 60_000, virtualUsers: 10, rampUpSeconds: 5,
  durationSeconds: 60, iterations: null, totalSteps: 9, successfulSteps: 2000, failedSteps: 60, errorMessage: null, results: [],
  requestDetailMode: 'FAILURES_AND_SAMPLE', detailSampleCapReached: false, detailedFailures: 0, detailedSamples: 0,
})
const liveStatus = (status: BackendExecutionStatus) => ({
  id: 'ex-1', status, startedAt: '2026-10-04T10:00:00Z', finishedAt: null, duration: null, virtualUsers: 10, totalRequests: 100,
  successfulRequests: 98, failedRequests: 2, progressPercent: 40, scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', rampUpSeconds: 5,
  durationSeconds: 60, iterations: null, plannedIterations: null, errorMessage: null, pausedAt: null, pausedDurationMs: 0, activeElapsedMs: 12_000,
  live: null,
})
const schedule = (status: BackendExecutionStatus) => ({
  id: 's-1', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking', name: 'Nuit',
  scheduleType: 'RECURRING_CRON', cronExpression: '0 0 2 * * *', runAt: null, timezone: 'UTC', nextRunAt: '2026-10-08T02:00:00Z', enabled: true,
  createdByUsername: 'admin', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z', lastTriggeredAt: null, lastExecutionId: 'ex-1',
  lastTriggerError: null, endAt: null, notifyMode: 'ALWAYS', state: 'ACTIVE', stateReason: null, lastEvent: null,
  lastRun: { executionId: 'ex-1', status, startedAt: '2026-10-04T10:00:00Z', p95Ms: 355, previousP95Ms: 321, p95ChangePercent: 10.6, errorRatePercent: 2.9 },
})

beforeEach(() => {
  vi.clearAllMocks()
  exec.getTimeline.mockResolvedValue({ source: 'NONE', message: 'Aucune requête.' })
  exec.getStatus.mockImplementation(() => Promise.resolve(liveStatus('RUNNING')))
  exec.getExtremes.mockResolvedValue({ kind: 'MAX', cardValueMs: 560, measuredRequests: 0, intervalSeconds: 1, requests: [] })
  dash.getScenarioTrend.mockResolvedValue({ scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', points: [] })
})

const at = (url: string, path: string, element: JSX.Element) => render(
  <MemoryRouter initialEntries={[url]}>
    <ToastProvider>
      <ExecutionMonitorProvider>
        <Routes><Route path={path} element={element} /></Routes>
      </ExecutionMonitorProvider>
    </ToastProvider>
  </MemoryRouter>,
)

describe('Table unique des statuts', () => {
  it('chaque statut connu a un libellé, un badge, une icône ; un statut inconnu s’affiche en gris avec son nom', () => {
    for (const s of EXECUTION_STATUSES) {
      const m = EXECUTION_STATUS_META[s]
      expect(m.label && m.liveLabel && m.detailLabel && m.icon && m.outlineIcon).toBeTruthy()
    }
    expect(executionStatusMeta('THRESHOLDS_EXCEEDED')).toMatchObject({ label: 'Seuils dépassés', pill: 'warning', known: true })
    expect(executionStatusMeta('MYSTERY')).toMatchObject({ label: 'MYSTERY', pill: 'neutral', known: false })
    expect(executionStatusMeta(null).label).toBe('Statut inconnu')
  })
})

describe.each(ALL)('statut %s : chaque page s’affiche sans erreur', (status) => {
  const meta = executionStatusMeta(status)

  it('Exécutions (liste)', async () => {
    exec.getPage.mockResolvedValue(page(status))
    at('/executions', '/executions', <Executions />)
    expect((await screen.findAllByText(meta.label)).length).toBeGreaterThan(0)
  })

  it('Détail', async () => {
    exec.getSummary.mockResolvedValue(summary(status))
    exec.getReport.mockResolvedValue(report(status))
    at('/executions/detail/11111111-2222-4333-8444-555555555555', '/executions/detail/:id', <ExecutionDetail />)
    expect((await screen.findAllByText(new RegExp(meta.detailLabel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))).length).toBeGreaterThan(0)
  })

  it('Rapport', async () => {
    exec.getReport.mockResolvedValue(report(status))
    at('/executions/report/ex-1', '/executions/report/:id', <ExecutionReport />)
    expect((await screen.findAllByText(meta.label)).length).toBeGreaterThan(0)
  })

  it('Suivi en direct', async () => {
    exec.getStatus.mockResolvedValue(liveStatus(status))
    exec.getReport.mockResolvedValue(report(status))
    at('/executions/live/ex-1', '/executions/live/:id', <ExecutionLive />)
    await waitFor(() => expect(screen.getAllByText(meta.liveLabel).length).toBeGreaterThan(0))
  })

  it('Dashboard', async () => {
    dash.getLatestByScenario.mockResolvedValue({ page: 0, size: 10, totalElements: 1, totalPages: 1, content: [{
      executionId: 'ex-1', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking',
      startedAt: '2026-10-04T10:00:00Z', status, virtualUsers: 10, p95Ms: 355, errorRate: 2.9, throughputRps: 33, cpuMaxPercent: 42,
      previousExecutionId: null, previousP95Ms: null, p95ChangePercent: null, trend: 'NONE' }] })
    dash.getOverview.mockResolvedValue({
      applicationId: null, from: null, to: null, kpis: { p95Ms: 355, errorRate: 2.9, requests: 2060, executions: 1, running: 0 },
      statusCounts: { success: 0, failed: 0, cancelled: 0, running: 0, thresholdsExceeded: 0 }, watchItems: [],
      scenarios: [{ scenarioId: 'sc-1', scenarioName: 'Parcours bancaire' }],
      slowestSteps: [], running: [], upcoming: [],
      recent: [{ id: 'ex-1', scenarioName: 'Parcours bancaire', applicationName: 'Banking', status, startedAt: '2026-10-04T10:00:00Z', duration: 60000 }],
    })
    at('/', '/', <Dashboard />)
    expect((await screen.findAllByText(meta.label)).length).toBeGreaterThan(0)
  })

  it('Comparaison', async () => {
    const col = (id: string) => ({ executionId: id, scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationName: 'Banking',
      startedAt: '2026-10-04T10:00:00Z', status, virtualUsers: 10, rampUpSeconds: 5, durationSeconds: 60, iterations: null,
      activeDurationMs: 60_000, requests: 2060, throughput: 33, p95Ms: 355, p99Ms: 430, errorRate: 2.9, cpuMaxPercent: 42, memoryMaxMb: 80 })
    exec.compare.mockResolvedValue({ sameScenario: true, warning: null, executions: [col('ex-1'), col('ex-2')], steps: [] })
    at('/executions/compare?ids=ex-1,ex-2', '/executions/compare', <ExecutionCompare />)
    expect((await screen.findAllByText(meta.label)).length).toBeGreaterThan(0)
  })


  it('Planification (dernière exécution) et son historique', async () => {
    sched.list.mockResolvedValue([schedule(status)])
    at('/planification', '/planification', <ScheduledExecutions />)
    expect((await screen.findAllByText(meta.label)).length).toBeGreaterThan(0)
  })

  it('Historique d’une planification', async () => {
    sched.getById.mockResolvedValue(schedule(status))
    sched.history.mockResolvedValue({ scheduleId: 's-1', truncated: false, items: [{ kind: 'EXECUTION', at: '2026-10-04T10:00:00Z',
      executionId: 'ex-1', status, p95Ms: 355, errorRatePercent: 2.9, throughputRps: 33, totalRequests: 2060, durationMs: 60_000,
      outcome: null, reason: null }] })
    at('/planification/s-1', '/planification/:id', <ScheduleHistory />)
    expect((await screen.findAllByText(meta.label)).length).toBeGreaterThan(0)
  })
})

describe('Page d’erreur des routes', () => {
  it('une erreur dans une page affiche un message clair en français et « Retour aux exécutions »', async () => {
    const Boom = () => { throw new TypeError("Cannot read properties of undefined (reading 'cls')") }
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const router = createMemoryRouter([
      { path: '/', errorElement: <RouteErrorPage fullPage />, children: [
        { errorElement: <RouteErrorPage />, children: [{ path: 'executions/detail/:id', element: <Boom /> }, { path: 'executions', element: <div>LISTE</div> }] },
      ] },
    ], { initialEntries: ['/executions/detail/ex-1'] })
    render(<RouterProvider router={router} />)
    expect(await screen.findByRole('heading', { name: 'Cette page a rencontré un problème' })).toBeTruthy()
    expect(screen.queryByText(/Unexpected Application Error/)).toBeNull()
    expect(screen.getByText(/TypeError : Cannot read properties of undefined/)).toBeTruthy()
    screen.getByRole('link', { name: /Retour aux exécutions/ }).click()
    expect(await screen.findByText('LISTE')).toBeTruthy()
    spy.mockRestore()
  })

  it('adresse inconnue : « Page introuvable »', () => {
    render(<MemoryRouter><NotFoundPage /></MemoryRouter>)
    expect(screen.getByRole('heading', { name: 'Page introuvable' })).toBeTruthy()
    expect(screen.getByRole('link', { name: /Retour aux exécutions/ }).getAttribute('href')).toBe('/executions')
  })
})
