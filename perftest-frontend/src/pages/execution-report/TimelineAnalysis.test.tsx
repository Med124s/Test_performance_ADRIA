import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BackendExecutionTimeline } from '../../types/backendContracts'
import { errorsByType, formatT, perSecond, slowestSteps } from './timelineModel'

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
  message: null,
  ...patch,
})

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

  it('par étape : toutes par défaut, « Les 3 plus lentes », case à cocher, débit à la place du P95', async () => {
    api.getTimeline.mockResolvedValue(timeline())
    render(<TimelineAnalysis executionId="ex-1" />)
    await screen.findByText(/Points clés/)
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
