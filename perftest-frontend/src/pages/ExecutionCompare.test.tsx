import { render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BackendComparisonColumn, BackendExecutionComparison } from '../types/backendContracts'
import { bestWorst, plateauReading } from './execution-compare/compareModel'

vi.mock('react-chartjs-2', () => ({ Line: ({ data }: { data: { labels: string[]; datasets: { label: string }[] } }) =>
  <div data-testid="line">{data.labels.join(' | ')} :: {data.datasets.map((d) => d.label).join(' | ')}</div> }))
vi.mock('../components/TopBar', () => ({ default: () => <div /> }))
const api = vi.hoisted(() => ({ compare: vi.fn() }))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: api }))

import ExecutionCompare from './ExecutionCompare'

const col = (vu: number, patch: Partial<BackendComparisonColumn> = {}): BackendComparisonColumn => ({
  executionId: `ex-${vu}`, scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationName: 'Banking',
  startedAt: `2026-10-06T10:${String(vu).padStart(2, '0')}:00Z`, status: 'SUCCESS', virtualUsers: vu, rampUpSeconds: 30,
  durationSeconds: 90, iterations: null, activeDurationMs: 90_000, requests: vu * 300, throughput: vu * 3.5, p95Ms: 300 + vu,
  p99Ms: 350 + vu, errorRate: 2, cpuMaxPercent: vu * 2, memoryMaxMb: 90, ...patch,
})

const comparison = (patch: Partial<BackendExecutionComparison> = {}): BackendExecutionComparison => ({
  sameScenario: true, warning: null,
  executions: [col(5), col(10), col(20, { throughput: 70, errorRate: 2.4 }), col(40, { throughput: 90, p95Ms: 520, errorRate: 2.1 })],
  steps: [{ name: 'Virement national', method: 'POST', p95Ms: [310, 312, 330, 560] }, { name: 'Santé', method: 'GET', p95Ms: [5, null, 6, 9] }],
  ...patch,
})

const renderPage = (ids = 'ex-5,ex-10,ex-20,ex-40') => render(
  <MemoryRouter initialEntries={[`/executions/compare?ids=${ids}`]}>
    <Routes><Route path="/executions/compare" element={<ExecutionCompare />} /></Routes>
  </MemoryRouter>,
)

beforeEach(() => vi.clearAllMocks())

describe('règles de la comparaison', () => {
  it('meilleure et moins bonne valeur selon le sens de la mesure ; rien si une seule valeur ou toutes égales', () => {
    expect(bestWorst([17.5, 35, 70, 90], 'higher')).toEqual({ best: 3, worst: 0 })
    expect(bestWorst([305, 310, 320, 520], 'lower')).toEqual({ best: 0, worst: 3 })
    expect(bestWorst([null, 40, null], 'lower')).toEqual({ best: null, worst: null })
    expect(bestWorst([2, 2, 2], 'lower')).toEqual({ best: null, worst: null })
    expect(bestWorst([1, 2], 'neutral')).toEqual({ best: null, worst: null })
  })

  it('plafond de débit : premier palier où le débit ne suit plus la charge', () => {
    expect(plateauReading([{ virtualUsers: 5, throughput: 17.5 }, { virtualUsers: 10, throughput: 35 }, { virtualUsers: 20, throughput: 70 },
      { virtualUsers: 40, throughput: 90 }])).toMatch(/plafonner vers 20–40 utilisateurs/)
    expect(plateauReading([{ virtualUsers: 5, throughput: 17 }, { virtualUsers: 10, throughput: 34 }])).toMatch(/^Pas de plafond visible/)
    expect(plateauReading([{ virtualUsers: 5, throughput: 17 }])).toBeNull()
  })
})

describe('page « Comparer des exécutions »', () => {
  it('une colonne par exécution, meilleures / moins bonnes valeurs, graphique selon la charge, P95 par étape', async () => {
    api.compare.mockResolvedValue(comparison())
    renderPage()
    const table = within(await screen.findByTestId('compare-table'))
    expect(api.compare).toHaveBeenCalledWith(['ex-5', 'ex-10', 'ex-20', 'ex-40'])
    expect(table.getAllByRole('columnheader')).toHaveLength(5)
    const throughputRow = table.getByText('Débit').closest('tr')!
    expect(within(throughputRow).getByText('90 req/s').closest('td')!.className).toContain('cp-best')
    expect(within(throughputRow).getByText('17,5 req/s').closest('td')!.className).toContain('cp-worst')
    const p95Row = table.getByText('P95').closest('tr')!
    expect(within(p95Row).getByText('520 ms').closest('td')!.className).toContain('cp-worst')
    expect(table.getByText('CPU max de l’application')).toBeTruthy()
    expect(within(table.getByText('Requêtes').closest('tr')!).queryAllByLabelText('meilleure')).toHaveLength(0)

    expect(screen.getByTestId('load-chart').textContent).toContain('5 VU | 10 VU | 20 VU | 40 VU :: Débit (req/s) | P95 (ms) | CPU max de l’application (%)')
    expect(screen.getByTestId('plateau-reading').textContent).toMatch(/plafonner vers 20–40 utilisateurs/)
    const steps = within(screen.getByTestId('steps-table'))
    expect(steps.getByText('560 ms').closest('td')!.className).toContain('cp-worst')
    expect(steps.getAllByText('—')).toHaveLength(1)
    expect(screen.queryByTestId('scenario-warning')).toBeNull()
  })

  it('avertissement si les scénarios diffèrent ; erreur serveur et sélection insuffisante expliquées', async () => {
    api.compare.mockResolvedValue(comparison({ sameScenario: false, warning: 'Ces exécutions n’utilisent pas le même scénario (2 scénarios différents).' }))
    const { unmount } = renderPage('ex-5,ex-10')
    expect((await screen.findByTestId('scenario-warning')).textContent).toMatch(/pas le même scénario/)
    unmount()

    const { unmount: u2 } = renderPage('ex-5')
    // Sélection insuffisante (ou ouverture depuis le menu) : écran d'accueil qui renvoie vers Exécutions.
    expect(await screen.findByRole('heading', { name: 'Choisissez les exécutions à comparer' })).toBeTruthy()
    expect(screen.getByRole('link', { name: /Choisir dans Exécutions/ }).getAttribute('href')).toBe('/executions')
    expect(api.compare).toHaveBeenCalledTimes(1)
    u2()
  })
})
