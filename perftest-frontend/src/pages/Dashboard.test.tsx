import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BackendDashboardOverview, BackendDashboardScenarioRow } from '../types/backendContracts'
import { formatMs, formatNumber, formatPercent, formatRps, formatSignedPercent } from '../utils/numberFormat'
import { rangeBounds, trendDisplay } from './dashboard/dashboardModel'

vi.mock('react-chartjs-2', () => ({ Line: ({ data }: { data: { labels: string[] } }) => <div data-testid="line">{data.labels.length} points</div> }))
vi.mock('../components/TopBar', () => ({ default: () => <div /> }))
const dash = vi.hoisted(() => ({ getOverview: vi.fn(), getScenarioTrend: vi.fn() }))
const apps = vi.hoisted(() => ({ getAll: vi.fn() }))
const execs = vi.hoisted(() => ({ getStatus: vi.fn() }))
vi.mock('../services/api/dashboardBackend', () => ({ dashboardBackendApi: dash }))
vi.mock('../services/api/applicationsBackend', () => ({ applicationsBackendApi: apps }))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: execs }))

import Dashboard from './Dashboard'

const row = (patch: Partial<BackendDashboardScenarioRow> = {}): BackendDashboardScenarioRow => ({
  executionId: 'ex-2', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking',
  startedAt: '2026-10-06T09:00:00Z', status: 'FAILED', virtualUsers: 20, p95Ms: 412, errorRate: 6.94, throughputRps: 68.66,
  cpuMaxPercent: 53.5, previousExecutionId: 'ex-1', previousP95Ms: 334, p95ChangePercent: 23.4, trend: 'WORSE', ...patch,
})

const overview = (patch: Partial<BackendDashboardOverview> = {}): BackendDashboardOverview => ({
  applicationId: null, from: null, to: null,
  kpis: { p95Ms: 1234, errorRate: 6.9, requests: 8673, executions: 3, running: 0 },
  statusCounts: { success: 1, failed: 2, cancelled: 0, running: 0 },
  watchItems: [
    { kind: 'REGRESSION', severity: 'warning', title: 'Scénario « Parcours bancaire » : P95 en hausse de 23,4 %', detail: 'P95 334 ms → 412 ms', link: '/executions/report/ex-2' },
    { kind: 'APPLICATION_FAILED', severity: 'danger', title: 'Application « Banking » : connexion en échec', detail: 'Connexion refusée', link: '/applications?q=Banking' },
  ],
  latestByScenario: [row(), row({ executionId: 'ex-9', scenarioId: 'sc-2', scenarioName: 'Santé', status: 'SUCCESS', p95Ms: 20, errorRate: 0,
    cpuMaxPercent: null, previousExecutionId: null, previousP95Ms: null, p95ChangePercent: null, trend: 'NONE', startedAt: '2026-10-05T09:00:00Z' })],
  slowestSteps: [{ stepName: 'Virement national', method: 'POST', scenarioName: 'Parcours bancaire', executionId: 'ex-2', p95Ms: 520, requests: 1250 }],
  running: [], recent: [{ id: 'ex-2', scenarioName: 'Parcours bancaire', applicationName: 'Banking', status: 'FAILED', startedAt: '2026-10-06T09:00:00Z', duration: 60000 }],
  upcoming: [{ id: 'sch-1', name: 'Nuit', scenarioName: 'Parcours bancaire', nextRunAt: '2026-10-07T02:00:00Z' }],
  ...patch,
})

const renderDashboard = () => render(
  <MemoryRouter initialEntries={['/']}>
    <Routes>
      <Route path="/" element={<Dashboard />} />
      <Route path="/executions/report/:id" element={<div>RAPPORT</div>} />
    </Routes>
  </MemoryRouter>,
)

beforeEach(() => {
  vi.clearAllMocks()
  apps.getAll.mockResolvedValue([{ id: 'app-1', name: 'Banking' }, { id: 'app-2', name: 'Autre' }])
  dash.getScenarioTrend.mockResolvedValue({ scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', points: [
    { executionId: 'ex-1', startedAt: '2026-10-05T09:00:00Z', status: 'SUCCESS', p95Ms: 334, errorRate: 0, throughputRps: 70, virtualUsers: 20 },
    { executionId: 'ex-2', startedAt: '2026-10-06T09:00:00Z', status: 'FAILED', p95Ms: 412, errorRate: 6.94, throughputRps: 68.7, virtualUsers: 20 },
  ] })
})

describe('format des nombres et règles d’affichage', () => {
  it('nombres en français : virgule, espace des milliers, au plus une décimale ; « — » sans valeur', () => {
    expect(formatNumber(68.66)).toBe('68,7')
    expect(formatNumber(8673, 0)).toMatch(/^8\s673$/)
    expect(formatNumber(null)).toBe('—')
    expect(formatRps(68.66)).toBe('68,7 req/s')
    expect(formatPercent(6.94)).toBe('6,9 %')
    expect(formatMs(1234)).toMatch(/^1\s234 ms$/)
    expect(formatSignedPercent(23.44)).toBe('+23,4 %')
    expect(formatSignedPercent(-8)).toBe('−8 %')
  })

  it('période : presets et dates personnalisées (journées entières, heure locale) ; « Tout » sans borne', () => {
    const now = new Date('2026-10-06T12:00:00Z')
    expect(rangeBounds('24h', '', '', now)).toEqual({ from: '2026-10-05T12:00:00.000Z', to: '2026-10-06T12:00:00.000Z' })
    expect(new Date(rangeBounds('7d', '', '', now).from!).getTime()).toBe(now.getTime() - 7 * 24 * 3600 * 1000)
    expect(rangeBounds('all', '', '', now)).toEqual({})
    const custom = rangeBounds('custom', '2026-10-01', '2026-10-02', now)
    expect(new Date(custom.from!).getHours()).toBe(0)
    expect(new Date(custom.to!).getHours()).toBe(23)
  })

  it('comparaison avec l’exécution précédente : rouge si dégradé, vert si amélioré, gris si faible ou absent', () => {
    expect(trendDisplay('WORSE', 23.44)).toMatchObject({ arrow: '↑', tone: 'bad', text: '+23,4 %' })
    expect(trendDisplay('BETTER', -12)).toMatchObject({ arrow: '↓', tone: 'good', text: '−12 %' })
    expect(trendDisplay('SAME', 3)).toMatchObject({ tone: 'muted' })
    expect(trendDisplay('NONE', null)).toMatchObject({ arrow: '', tone: 'muted', text: '—' })
  })
})

describe('Dashboard', () => {
  it('cartes du haut, badges de statut, à surveiller avec liens, tableau, étapes lentes, récentes et planifiées', async () => {
    dash.getOverview.mockResolvedValue(overview())
    renderDashboard()
    expect(await screen.findByText('Requêtes envoyées')).toBeTruthy()
    expect(screen.getByText(/^1\s234 ms$/)).toBeTruthy()
    expect(screen.getByText('Taux d’erreurs').closest('.db-kpi')!.textContent).toContain('6,9 %')
    expect(screen.getByText(/^8\s673$/)).toBeTruthy()
    expect(screen.getByText('Réussies · 1')).toBeTruthy()
    expect(screen.getByText('Échouées · 2')).toBeTruthy()
    // Retirés : « (Spring Boot) », débit moyen, taux de succès des étapes, grandes cartes.
    expect(document.body.textContent).not.toMatch(/Spring Boot|Débit moyen|Taux de succès des étapes|Étapes exécutées|Répartition des étapes/)

    const watch = within(screen.getByTestId('watch-block'))
    expect(watch.getByText(/P95 en hausse de 23,4 %/)).toBeTruthy()
    expect(watch.getByRole('link', { name: /Voir le rapport/ }).getAttribute('href')).toBe('/executions/report/ex-2')
    expect(watch.getByRole('link', { name: /Ouvrir/ }).getAttribute('href')).toBe('/applications?q=Banking')

    const rows = screen.getAllByTestId('scenario-row')
    expect(rows).toHaveLength(2)
    const first = within(rows[0])
    expect(first.getByText('412 ms')).toBeTruthy()
    expect(first.getByText('68,7 req/s')).toBeTruthy()
    expect(first.getByText('53,5 %')).toBeTruthy()
    expect(first.getByText('+23,4 %').closest('td')!.className).toContain('bad')
    expect(within(rows[1]).getAllByText('—').length).toBeGreaterThan(0)

    expect(within(screen.getByTestId('slowest-steps')).getByText('Virement national')).toBeTruthy()
    expect(await screen.findByTestId('trend-chart')).toBeTruthy()
    expect(screen.getByText('Nuit')).toBeTruthy()

    fireEvent.click(rows[0])
    expect(await screen.findByText('RAPPORT')).toBeTruthy()
  })

  it('filtres application et période envoyés au serveur', async () => {
    dash.getOverview.mockResolvedValue(overview())
    renderDashboard()
    await screen.findByText('Requêtes envoyées')
    expect(dash.getOverview).toHaveBeenLastCalledWith({ applicationId: undefined })
    fireEvent.change(screen.getByLabelText('Application'), { target: { value: 'app-1' } })
    await vi.waitFor(() => expect(dash.getOverview).toHaveBeenLastCalledWith({ applicationId: 'app-1' }))
    fireEvent.click(screen.getByRole('radio', { name: '7 jours' }))
    await vi.waitFor(() => {
      const arg = dash.getOverview.mock.calls[dash.getOverview.mock.calls.length - 1][0]
      expect(arg.applicationId).toBe('app-1')
      expect(new Date(arg.to).getTime() - new Date(arg.from).getTime()).toBe(7 * 24 * 3600 * 1000)
    })
    expect(dash.getScenarioTrend.mock.calls[dash.getScenarioTrend.mock.calls.length - 1][1].from).toBeTruthy()
  })

  it('états vides : aucun point d’attention, aucune exécution, évolution impossible avec moins de deux exécutions', async () => {
    dash.getOverview.mockResolvedValue(overview({ watchItems: [], latestByScenario: [], slowestSteps: [], recent: [], upcoming: [],
      kpis: { p95Ms: null, errorRate: null, requests: 0, executions: 0, running: 0 }, statusCounts: { success: 0, failed: 0, cancelled: 0, running: 0 } }))
    renderDashboard()
    expect(await screen.findByText(/✓ Aucun point d’attention/)).toBeTruthy()
    expect(screen.getAllByText('Aucune exécution sur la période.').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('Aucune étape mesurée sur la période.')).toBeTruthy()
    expect(screen.getByText('Aucune exécution planifiée à venir.')).toBeTruthy()
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2)
    expect(screen.queryByTestId('running-block')).toBeNull()
  })

  it('évolution des performances filtrée par étape : P95 et erreurs de l’étape choisie', async () => {
    dash.getOverview.mockResolvedValue(overview())
    dash.getScenarioTrend.mockImplementation((_id: string, _range: unknown, stepName?: string) => Promise.resolve({
      scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', stepName: stepName ?? null,
      steps: [{ name: 'Consulter le solde', method: 'GET' }, { name: 'Virement national', method: 'POST' }],
      points: [
        { executionId: 'ex-1', startedAt: '2026-10-05T09:00:00Z', status: 'SUCCESS', p95Ms: 334, errorRate: 0, throughputRps: 70, virtualUsers: 20,
          stepP95Ms: stepName ? 300 : null, stepErrorRate: stepName ? 5 : null, stepRequests: stepName ? 400 : null },
        { executionId: 'ex-2', startedAt: '2026-10-06T09:00:00Z', status: 'FAILED', p95Ms: 412, errorRate: 6.94, throughputRps: 68.7, virtualUsers: 20,
          stepP95Ms: stepName ? 450 : null, stepErrorRate: stepName ? 8 : null, stepRequests: stepName ? 410 : null },
      ] }))
    renderDashboard()
    const stepSelect = await screen.findByLabelText('Étape affichée')
    await vi.waitFor(() => expect(within(stepSelect).getAllByRole('option').map((o) => o.textContent)).toEqual(['Toutes les étapes', 'GET Consulter le solde', 'POST Virement national']))
    fireEvent.change(stepSelect, { target: { value: 'Virement national' } })
    await vi.waitFor(() => expect(dash.getScenarioTrend).toHaveBeenLastCalledWith('sc-1', {}, 'Virement national'))
  })

  it('une seule exécution terminée : message clair au lieu du graphique ; exécutions en cours avec « Suivre »', async () => {
    dash.getOverview.mockResolvedValue(overview({ running: [{ id: 'ex-5', scenarioName: 'Parcours bancaire', applicationName: 'Banking', status: 'RUNNING', startedAt: '2026-10-06T10:00:00Z', duration: null }] }))
    dash.getScenarioTrend.mockResolvedValue({ scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', points: [
      { executionId: 'ex-2', startedAt: '2026-10-06T09:00:00Z', status: 'FAILED', p95Ms: 412, errorRate: 6.94, throughputRps: 68.7, virtualUsers: 20 }] })
    execs.getStatus.mockResolvedValue({ id: 'ex-5', status: 'RUNNING', progressPercent: 40, live: null })
    renderDashboard()
    expect(await screen.findByText(/Une seule exécution terminée de ce scénario sur la période/)).toBeTruthy()
    expect(screen.queryByTestId('trend-chart')).toBeNull()
    const running = within(screen.getByTestId('running-block'))
    expect(running.getByRole('link', { name: 'Suivre' }).getAttribute('href')).toBe('/executions/live/ex-5')
  })
})
