import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BackendExecutionTimeline, BackendStepEvolution, BackendStepTrend } from '../../types/backendContracts'
import { errorsByType, formatT, perSecond, slowestSteps } from './timelineModel'
import { formatDeltaMs, formatDeltaPercent, rowTooltip, sortTrends } from './stepEvolutionModel'

// Graphiques remplacés par leurs données (canvas absent de jsdom).
vi.mock('react-chartjs-2', () => ({
  Line: ({ data }: { data: { datasets: { label: string }[] } }) => <div data-testid="line">{data.datasets.map((d) => d.label).join(' | ')}</div>,
  Bar: ({ data }: { data: { datasets: { label: string; data: number[] }[] } }) => (
    <div data-testid="bar">{data.datasets.map((d) => `${d.label}=${d.data.join(',')}`).join(' | ')}</div>
  ),
}))
const api = vi.hoisted(() => ({ getTimeline: vi.fn() }))
vi.mock('../../services/api/executionsBackend', () => ({ executionsBackendApi: api }))

import TimelineAnalysis from './TimelineAnalysis'

const timeline = (patch: Partial<BackendExecutionTimeline> = {}): BackendExecutionTimeline => ({
  source: 'RECORDED', virtualUsersAvailable: true, bucketSeconds: 1, durationMs: 4000, t: [0, 1, 2, 3],
  activeVirtualUsers: [1, 2, 3, 3], requests: [5, 10, 15, 15], throughput: [5, 10, 15, 15], avgMs: [100, 110, 150, 200],
  p95Ms: [120, 130, 200, 300], errors: [0, 0, 2, 3], pausedRatio: [0, 0, 0, 0],
  steps: [
    { key: 's1', name: 'Solde', method: 'GET', requests: [3, 5, 8, 8], throughput: [3, 5, 8, 8], p95Ms: [100, 100, 110, 120], overallP95Ms: 120 },
    { key: 's2', name: 'Virement national', method: 'POST', requests: [2, 5, 7, 7], throughput: [2, 5, 7, 7], p95Ms: [220, 300, 500, 600], overallP95Ms: 600 },
    { key: 's3', name: 'Facture', method: 'POST', requests: [0, 0, 0, 0], throughput: [0, 0, 0, 0], p95Ms: [null, null, null, null], overallP95Ms: 80 },
    { key: 's4', name: 'Dépôt', method: 'POST', requests: [0, 0, 0, 0], throughput: [0, 0, 0, 0], p95Ms: [null, null, null, null], overallP95Ms: 90 },
  ],
  errorTypes: ['HTTP 409', 'HTTP 500'],
  errorPoints: [{ bucket: 2, step: 1, type: 0, count: 2 }, { bucket: 3, step: 1, type: 0, count: 2 }, { bucket: 3, step: 0, type: 1, count: 1 }],
  pauses: [],
  insights: [
    { kind: 'PLATEAU', text: 'Le débit plafonne autour de 15 req/s à partir de 3 utilisateurs virtuels (à 2 s).' },
    { kind: 'DEGRADATION', text: 'L\'étape la plus dégradée est Virement national : P95 de 220 ms au début, 600 ms à la fin.' },
    { kind: 'ERRORS', text: 'Les premières erreurs apparaissent à 2 s (HTTP 409 sur Virement national) ; 5 erreur(s) au total.' },
  ],
  stepEvolution: evolution(),
  message: null,
  ...patch,
})

const trend = (patch: Partial<BackendStepTrend>): BackendStepTrend => ({
  key: 'k', name: 'Étape', method: 'GET', trend: 'STABLE', startP95Ms: 100, endP95Ms: 100, startRequests: 40, endRequests: 42,
  totalRequests: 200, deltaMs: 0, deltaPercent: 0, ...patch,
})

function evolution(patch: Partial<BackendStepEvolution> = {}): BackendStepEvolution {
  return {
    comparable: true, message: null, stableSeconds: 210, startFromSeconds: 0, startToSeconds: 42, endFromSeconds: 168, endToSeconds: 210,
    // Volontairement dans le désordre : l'affichage trie de la plus dégradée à la plus stable.
    steps: [
      trend({ key: 's3', name: 'Santé', trend: 'STABLE', startP95Ms: 20, endP95Ms: 40, deltaMs: 20, deltaPercent: 100 }),
      trend({ key: 's2', name: 'Historique', trend: 'WATCH', startP95Ms: 200, endP95Ms: 250, deltaMs: 50, deltaPercent: 25 }),
      trend({ key: 's1', name: 'Consulter le solde', trend: 'DEGRADED', startP95Ms: 283, endP95Ms: 420, startRequests: 1234, endRequests: 1300,
        totalRequests: 16072, deltaMs: 137, deltaPercent: 48.4 }),
    ],
    ...patch,
  }
}

beforeEach(() => vi.clearAllMocks())

describe('timelineModel', () => {
  it('libellés de temps, étapes les plus lentes, erreurs par type filtrées par étape, erreurs par seconde', () => {
    expect(formatT(16, false)).toBe('16 s')
    expect(formatT(125, true)).toBe('2:05')
    expect(slowestSteps(timeline(), 2)).toEqual([1, 0])
    expect(errorsByType(timeline(), null)).toEqual({ types: ['HTTP 409', 'HTTP 500'], series: [[0, 0, 2, 2], [0, 0, 0, 1]] })
    expect(errorsByType(timeline(), 0)).toEqual({ types: ['HTTP 500'], series: [[0, 0, 0, 1]] })
    expect(perSecond(25, 5)).toBe(5)
  })

  it('évolution des étapes : tri, écarts, infobulle', () => {
    const ev = evolution()
    expect(sortTrends(ev.steps).map((s) => s.name)).toEqual(['Consulter le solde', 'Historique', 'Santé'])
    // Même tendance : la plus forte hausse en ms d'abord ; sans comparaison possible : en dernier.
    expect(sortTrends([
      trend({ key: 'a', name: 'A', trend: 'DEGRADED', deltaMs: 120 }),
      trend({ key: 'n', name: 'N', trend: 'INSUFFICIENT', deltaMs: null }),
      trend({ key: 'b', name: 'B', trend: 'DEGRADED', deltaMs: 240 }),
    ]).map((s) => s.name)).toEqual(['B', 'A', 'N'])
    expect(formatDeltaMs(137)).toBe('+137 ms')
    expect(formatDeltaMs(-15)).toBe('−15 ms')
    expect(formatDeltaPercent(48.4)).toBe('+48 %')
    expect(rowTooltip(ev.steps[2], ev, true)).toContain('de 2:48 à 3:30')
  })
})

describe('Analyse dans le temps', () => {
  it('points clés, graphique global (utilisateurs, débit, P95, moyenne en option)', async () => {
    api.getTimeline.mockResolvedValue(timeline())
    render(<TimelineAnalysis executionId="ex-1" />)
    expect(await screen.findByText(/Le débit plafonne autour de 15 req\/s/)).toBeTruthy()
    expect(screen.getByText(/L'étape la plus dégradée est Virement national/)).toBeTruthy()
    expect(screen.getByText(/Les premières erreurs apparaissent à 2 s/)).toBeTruthy()
    const [global] = screen.getAllByTestId('line')
    expect(global.textContent).toBe('Utilisateurs virtuels actifs | Débit (req/s) | P95 (ms)')
    fireEvent.click(screen.getByLabelText(/Afficher la moyenne/))
    expect(screen.getAllByTestId('line')[0].textContent).toContain('Moyenne (ms)')
  })

  it('graphique détaillé replié derrière « Voir le graphique détaillé » : toutes, « Les 3 plus lentes », case à cocher, débit', async () => {
    api.getTimeline.mockResolvedValue(timeline())
    render(<TimelineAnalysis executionId="ex-1" />)
    await screen.findByText(/Points clés/)
    expect(screen.getAllByTestId('line')).toHaveLength(1)
    const toggle = screen.getByRole('button', { name: /Voir le graphique détaillé/ })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(toggle)
    expect(screen.getByRole('button', { name: /Masquer le graphique détaillé/ }).getAttribute('aria-expanded')).toBe('true')
    const stepChart = () => screen.getAllByTestId('line')[1].textContent
    expect(stepChart()).toBe('Solde | Virement national | Facture | Dépôt')
    fireEvent.click(screen.getByRole('button', { name: 'Les 3 plus lentes' }))
    expect(stepChart()).toBe('Solde | Virement national | Dépôt')
    fireEvent.click(screen.getByRole('checkbox', { name: /Solde/ }))
    expect(stepChart()).toBe('Virement national | Dépôt')
    fireEvent.click(screen.getByRole('radio', { name: /Débit \(req\/s\)/ }))
    expect((screen.getByRole('radio', { name: /Débit \(req\/s\)/ }) as HTMLInputElement).checked).toBe(true)
    expect(stepChart()).toBe('Virement national | Dépôt')
  })

  it('évolution des étapes : une ligne par étape, triée de la plus dégradée à la plus stable, badges, survol, sans mini-courbe', async () => {
    api.getTimeline.mockResolvedValue(timeline())
    render(<TimelineAnalysis executionId="ex-1" />)
    await screen.findByText('Évolution des étapes pendant le test')
    const rows = screen.getAllByTestId('step-evolution-row')
    expect(rows.map((r) => within(r).getAllByRole('cell')[0].textContent)).toEqual(['GETConsulter le solde', 'GETHistorique', 'GETSanté'])
    const first = within(rows[0])
    expect(first.getByText('283 ms')).toBeTruthy()
    expect(first.getByText('420 ms')).toBeTruthy()
    expect(first.getByText('+137 ms')).toBeTruthy()
    expect(first.getByText('+48 %')).toBeTruthy()
    expect(first.getByText('Dégradée')).toBeTruthy()
    expect(within(rows[1]).getByText('À surveiller')).toBeTruthy()
    expect(within(rows[2]).getByText('Stable')).toBeTruthy()
    // Colonnes : Étape, P95 début, P95 fin, Évolution, Tendance (plus de « P95 dans le temps »).
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent?.trim())).toEqual(['Étape', 'P95 début', 'P95 fin', 'Évolution', 'Tendance'])
    expect(within(rows[0]).getAllByRole('cell')).toHaveLength(5)
    expect(screen.queryByText('P95 dans le temps')).toBeNull()
    expect(rows[0].querySelector('svg')).toBeNull()
    // Survol : P95 de début et de fin, et nombre de requêtes de l'étape.
    expect(rows[0].getAttribute('title')).toMatch(/P95 au début : 283 ms \(1\s234 requêtes, de 0 s à 42 s\)/)
    expect(rows[0].getAttribute('title')).toContain('P95 à la fin : 420 ms')
    expect(rows[0].getAttribute('title')).toMatch(/Requêtes de l’étape : 16\s072/)
    // Règle des badges expliquée en infobulle.
    expect(first.getByText('Dégradée').closest('span')!.getAttribute('title')).toContain('au moins 100 ms et d’au moins 30 %')
  })

  it('exécution trop courte : message clair à la place du tableau, graphique détaillé toujours disponible', async () => {
    api.getTimeline.mockResolvedValue(timeline({ stepEvolution: evolution({ comparable: false,
      message: 'Exécution trop courte pour comparer le début et la fin du test : 4 s de charge stable (au moins 20 s nécessaires).' }) }))
    render(<TimelineAnalysis executionId="ex-1" />)
    expect(await screen.findByText(/Exécution trop courte pour comparer le début et la fin du test/)).toBeTruthy()
    expect(screen.queryAllByTestId('step-evolution-row')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: /Voir le graphique détaillé/ }))
    expect(screen.getAllByTestId('line')).toHaveLength(2)
  })

  it('repères des requêtes la plus lente et la plus rapide sur le graphique global', async () => {
    api.getTimeline.mockResolvedValue(timeline({ slowest: { bucket: 2, responseTimeMs: 1234, atSeconds: 2.4 }, fastest: { bucket: 0, responseTimeMs: 3, atSeconds: 0.2 } }))
    render(<TimelineAnalysis executionId="ex-1" />)
    const legend = await screen.findByTestId('extreme-legend')
    expect(legend.textContent).toMatch(/Requête la plus lente : 1\s234 ms à 2 s/)
    expect(legend.textContent).toMatch(/Requête la plus rapide : 3 ms à 0 s/)
  })

  it('CPU et RAM de l’application testée sur le même axe du temps ; sans monitoring : explication', async () => {
    api.getTimeline.mockResolvedValue(timeline({ resources: { source: 'PROMETHEUS', intervalSeconds: 5, memoryMetric: 'process_resident_memory_bytes',
      message: null, sampleCount: 2, cpuPercent: [null, 35.5, null, 52], memoryMb: [null, 98, null, 104] } }))
    const { unmount } = render(<TimelineAnalysis executionId="ex-1" />)
    const card = await screen.findByTestId('resources-card')
    expect(card.textContent).toMatch(/Prometheus, une mesure toutes les 5 s/)
    expect(card.textContent).toMatch(/ni la machine entière, ni LoadPilot/)
    expect(screen.getAllByTestId('line')[1].textContent).toBe('CPU de l’application (% d’un cœur) | RAM de l’application (Mo)')
    unmount()

    api.getTimeline.mockResolvedValue(timeline())
    render(<TimelineAnalysis executionId="ex-2" />)
    expect((await screen.findByTestId('resources-card')).textContent).toMatch(/Aucun monitoring configuré pour cette application/)
  })

  it('erreurs par seconde et par type, filtrables par étape', async () => {
    api.getTimeline.mockResolvedValue(timeline())
    render(<TimelineAnalysis executionId="ex-1" />)
    expect((await screen.findByTestId('bar')).textContent).toBe('HTTP 409=0,0,2,2 | HTTP 500=0,0,0,1')
    fireEvent.change(screen.getByLabelText('Filtrer les erreurs par étape'), { target: { value: '0' } })
    expect(screen.getByTestId('bar').textContent).toBe('HTTP 500=0,0,0,1')
    fireEvent.change(screen.getByLabelText('Filtrer les erreurs par étape'), { target: { value: '2' } })
    expect(screen.getByText('Aucune erreur sur cette étape.')).toBeTruthy()
  })

  it('ancienne exécution (utilisateurs déduits), sans utilisateurs, ou sans données : message clair', async () => {
    api.getTimeline.mockResolvedValue(timeline({ source: 'DERIVED' }))
    const { unmount } = render(<TimelineAnalysis executionId="ex-1" />)
    expect(await screen.findByText(/Utilisateurs actifs déduits des requêtes/)).toBeTruthy()
    unmount()

    api.getTimeline.mockResolvedValue(timeline({ source: 'DERIVED', virtualUsersAvailable: false, activeVirtualUsers: [null, null, null, null] }))
    const second = render(<TimelineAnalysis executionId="ex-2" />)
    expect(await screen.findByText(/Utilisateurs virtuels actifs non disponibles/)).toBeTruthy()
    expect(screen.getAllByTestId('line')[0].textContent).toBe('Débit (req/s) | P95 (ms)')
    second.unmount()

    api.getTimeline.mockResolvedValue(timeline({ source: 'NONE', message: 'Aucune requête enregistrée pour cette exécution : analyse dans le temps impossible.' }))
    render(<TimelineAnalysis executionId="ex-3" />)
    expect(await screen.findByText(/Aucune requête enregistrée pour cette exécution/)).toBeTruthy()
    expect(screen.queryByTestId('line')).toBeNull()
  })
})
