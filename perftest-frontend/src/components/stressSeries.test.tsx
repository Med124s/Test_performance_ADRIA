import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import StressSeriesPanel from './StressSeriesPanel'
import SeriesRunsChoice from './SeriesRunsChoice'
import { ToastProvider } from '../context/ToastContext'
import { currentRunIndex, runOutcomeLabel, secondsUntilNextRun } from '../utils/stressSeries'
import { BackendStressResult, BackendStressSeriesResponse } from '../types/backendContracts'

const get = vi.fn()
vi.mock('../services/api/stressSeriesBackend', () => ({
  stressSeriesBackendApi: { get: (id: string) => get(id), start: vi.fn(), cancel: vi.fn(), exportPdf: vi.fn() },
}))

const nbsp = (s: string | null | undefined) => (s ?? '').replace(/[  ]/g, ' ')

const rupture = (rps: number, vus: number): BackendStressResult => ({
  outcome: 'BREAKPOINT', limit: null, message: null, peakVirtualUsers: vus + 5, peakOfferedRps: rps + 15, criteria: ['RESPONSE_TIME'],
  onsetSeconds: 100, detectedSeconds: 115, virtualUsers: vus, offeredRps: rps, throughputRps: rps - 4, p95Ms: 940, baselineP95Ms: 300,
  refusalPercent: 0, rampRpsPerSecond: 1,
})

const run = (index: number, rps: number, vus: number) => ({
  index, executionId: `e${index}`, status: 'THRESHOLDS_EXCEEDED' as const, startedAt: '2026-11-27T10:00:00Z', duration: 115_000,
  stress: rupture(rps, vus),
})

const COMPLETED: BackendStressSeriesResponse = {
  id: 's1', scenarioId: 'sc', scenarioName: 'Démo', applicationName: 'Banque', requestedRuns: 3, status: 'COMPLETED', stopReason: null,
  triggeredByUsername: 'm.benyghil', startedAt: '2026-11-27T10:00:00Z', finishedAt: '2026-11-27T10:07:00Z', nextRunAt: null,
  pauseSeconds: 30, currentExecutionId: null, runs: [run(1, 63, 33), run(2, 60, 31), run(3, 66, 35)],
  summary: {
    verdict: 'CONFIRMED', conclusiveRuns: 3, ruptures: 3, meanRps: 63, minRps: 60, maxRps: 66, spreadPercent: 9.5, rampRpsPerSecond: 1,
    precisionRps: 5, message: 'Point de rupture confirmé autour de 63 req/s, entre 60 et 66 req/s sur 3 essais (écart de 9,5 %, précision ± 5 req/s).',
  },
}

const panel = (props: Partial<Parameters<typeof StressSeriesPanel>[0]> = {}) => render(
  <MemoryRouter><ToastProvider><StressSeriesPanel seriesId="s1" {...props} /></ToastProvider></MemoryRouter>)

describe('Série d’essais de stress', () => {
  it('libellés : résultat de chaque essai, rang en cours, compte à rebours de la pause', () => {
    expect(nbsp(runOutcomeLabel(run(1, 63, 33)))).toBe('Rupture vers 63 req/s')
    expect(nbsp(runOutcomeLabel({ ...run(2, 0, 0), status: 'SUCCESS', stress: { ...rupture(0, 0), outcome: 'NO_BREAKPOINT', peakOfferedRps: 500 } })))
      .toBe('Aucune rupture jusqu’à 500 req/s')
    expect(runOutcomeLabel({ ...run(3, 0, 0), status: 'FAILED', stress: { ...rupture(0, 0), outcome: 'BASELINE_UNUSABLE' } }))
      .toBe('Mesure de référence impossible')
    expect(runOutcomeLabel({ ...run(3, 0, 0), status: 'RUNNING', stress: null })).toBe('En cours')

    const paused = { ...COMPLETED, status: 'RUNNING' as const, runs: COMPLETED.runs.slice(0, 1), nextRunAt: '2026-11-27T10:02:30Z' }
    expect(currentRunIndex(paused)).toBe(2)
    expect(secondsUntilNextRun(paused, new Date('2026-11-27T10:02:05Z').getTime())).toBe(25)
    expect(secondsUntilNextRun(COMPLETED, Date.now())).toBeNull()
  })

  it('choix au lancement : 1, 3 ou 5 essais, avec l’explication de la série', () => {
    const onChange = vi.fn()
    const { rerender } = render(<SeriesRunsChoice value={1} onChange={onChange} />)
    expect(screen.getAllByRole('radio').map((r) => (r as HTMLInputElement).value)).toEqual(['1', '3', '5'])
    expect(screen.queryByText(/pause de 30 s/)).toBeNull()
    fireEvent.click(screen.getByLabelText('3 essais'))
    expect(onChange).toHaveBeenCalledWith(3)
    rerender(<SeriesRunsChoice value={3} onChange={onChange} />)
    expect(screen.getByText(/Le même test 3 fois de suite, avec une pause de 30 s/)).toBeTruthy()
  })

  it('série terminée : essais, essai affiché mis en avant, point de rupture consolidé', () => {
    panel({ initial: COMPLETED, executionId: 'e2' })
    const card = screen.getByTestId('stress-series')
    expect(card.textContent).toContain('Série de 3 essais · Terminée')
    expect(screen.getAllByRole('row')).toHaveLength(4)
    expect(nbsp(card.textContent)).toContain('Rupture vers 60 req/s')
    expect(screen.getAllByRole('link', { name: 'Rapport' })).toHaveLength(2)
    expect(screen.getAllByRole('row')[2].getAttribute('aria-current')).toBe('true')
    const summary = screen.getByTestId('series-summary')
    expect(summary.textContent).toContain('Point de rupture confirmé')
    expect(nbsp(summary.textContent)).toContain('autour de 63 req/s, entre 60 et 66 req/s sur 3 essais')
    expect(nbsp(summary.textContent)).toContain('Montée : +1 req/s par seconde · rupture située à ± 5 req/s près.')
    expect(screen.queryByRole('button', { name: /Annuler la série/ })).toBeNull()
    expect(screen.getByRole('button', { name: /Rapport de la série \(PDF\)/ })).toBeTruthy()
    expect(get).not.toHaveBeenCalled()
  })

  it('pause en cours : compte à rebours, résultat provisoire, annulation ; l’essai suivant est annoncé', async () => {
    const nextRunAt = new Date(Date.now() + 20_000).toISOString()
    const paused = { ...COMPLETED, status: 'RUNNING' as const, finishedAt: null, nextRunAt, runs: COMPLETED.runs.slice(0, 1),
      summary: { ...COMPLETED.summary, verdict: 'NOT_CONFIRMED' as const, message: 'Point de rupture non confirmé : un seul essai concluant.' } }
    get.mockResolvedValueOnce(paused)
    const onRunStarted = vi.fn()
    panel({ executionId: 'e1', canWrite: true, onRunStarted })

    await waitFor(() => expect(screen.getByTestId('series-pause').textContent).toMatch(/essai 2\/3 dans (19|20) s/))
    expect(screen.getByTestId('series-summary').textContent).toContain('(provisoire, 1 essai terminé)')
    expect(screen.getByRole('button', { name: /Annuler la série/ })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Rapport de la série/ })).toBeNull()
    expect(onRunStarted).not.toHaveBeenCalled()

    get.mockResolvedValue({ ...paused, nextRunAt: null, currentExecutionId: 'e2',
      runs: [...paused.runs, { ...run(2, 0, 0), status: 'RUNNING' as const, stress: null }] })
    await waitFor(() => expect(onRunStarted).toHaveBeenCalledWith('e2'), { timeout: 4000 })
  })
})
