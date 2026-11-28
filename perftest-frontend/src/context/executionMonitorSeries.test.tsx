import { useEffect } from 'react'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ExecutionMonitorProvider, useExecutionMonitor } from './ExecutionMonitorContext'
import { BackendExecutionStatusResponse, BackendStressSeriesResponse } from '../types/backendContracts'

const backend = vi.hoisted(() => ({
  statuses: {} as Record<string, BackendExecutionStatusResponse>,
  series: null as BackendStressSeriesResponse | null,
}))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: {
  getStatus: vi.fn((id: string) => Promise.resolve(backend.statuses[id])),
  getReport: vi.fn(() => Promise.resolve(null)),
} }))
const getSeries = vi.hoisted(() => vi.fn(() => Promise.resolve(backend.series)))
vi.mock('../services/api/stressSeriesBackend', () => ({ stressSeriesBackendApi: { get: getSeries } }))

import { executionsBackendApi } from '../services/api/executionsBackend'

const status = (id: string, index: number, patch: Partial<BackendExecutionStatusResponse> = {}): BackendExecutionStatusResponse => ({
  id, status: 'RUNNING', startedAt: '2026-11-28T10:00:00Z', finishedAt: null, duration: null, virtualUsers: 10,
  totalRequests: 100, successfulRequests: 100, failedRequests: 0, progressPercent: 0, scenarioId: 'sc', scenarioName: 'Démo',
  rampUpSeconds: 0, durationSeconds: null, iterations: null, plannedIterations: null, errorMessage: null, pausedAt: null,
  pausedDurationMs: 0, activeElapsedMs: 10_000, live: null, loadMode: 'STRESS', series: { id: 's1', index }, ...patch,
})

const done = (id: string, index: number) => status(id, index, { status: 'THRESHOLDS_EXCEEDED', finishedAt: '2026-11-28T10:02:00Z', progressPercent: 100 })

const run = (index: number, executionId: string, running = false) => ({
  index, executionId, status: running ? 'RUNNING' as const : 'THRESHOLDS_EXCEEDED' as const, startedAt: '2026-11-28T10:00:00Z',
  duration: running ? null : 115_000, stress: null,
})

const series = (patch: Partial<BackendStressSeriesResponse>): BackendStressSeriesResponse => ({
  id: 's1', scenarioId: 'sc', scenarioName: 'Démo', applicationName: 'Banque', requestedRuns: 2, status: 'RUNNING', stopReason: null,
  triggeredByUsername: 'admin', startedAt: '2026-11-28T10:00:00Z', finishedAt: null, nextRunAt: null, pauseSeconds: 30,
  currentExecutionId: null, runs: [], summary: null, ...patch,
} as BackendStressSeriesResponse)

function Tracker() {
  const { track } = useExecutionMonitor()
  useEffect(() => { track('e1', 'Démo') }, [track])
  return <div>Autre page</div>
}

beforeEach(() => {
  vi.clearAllMocks()
  window.sessionStorage.clear()
})

describe('Panneau de suivi : série d’essais de stress', () => {
  it('pause, essai suivant suivi hors de la page de suivi, une seule fenêtre de résultat (consolidée) à la fin', async () => {
    backend.statuses = { e1: done('e1', 1) }
    backend.series = series({ nextRunAt: new Date(Date.now() + 20_000).toISOString(), runs: [run(1, 'e1')] })
    render(
      <MemoryRouter initialEntries={['/autre']}>
        <ExecutionMonitorProvider>
          <Routes><Route path="/autre" element={<Tracker />} /></Routes>
        </ExecutionMonitorProvider>
      </MemoryRouter>,
    )

    // Pause : compte à rebours de l'essai suivant, pas de fenêtre de résultat pour l'essai 1.
    const pause = await screen.findByTestId('em-series-pause', {}, { timeout: 4000 })
    expect(pause.textContent).toMatch(/essai 2\/2 dans (19|20) s/)
    expect(within(pause).getByRole('link', { name: 'Suivre' }).getAttribute('href')).toBe('/executions/live/e1')
    expect(screen.queryByRole('dialog')).toBeNull()

    // Essai 2 lancé par le serveur : suivi sans passer par la page de suivi.
    backend.statuses.e2 = status('e2', 2)
    backend.series = series({ currentExecutionId: 'e2', runs: [run(1, 'e1'), run(2, 'e2', true)] })
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Exécutions en cours' })).getByText('Démo · essai 2/2')).toBeTruthy(),
      { timeout: 4000 })
    expect(executionsBackendApi.getStatus).toHaveBeenCalledWith('e2')
    expect(screen.queryByTestId('em-series-pause')).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()

    // Fin de la série : fenêtre du dernier essai, avec le résultat consolidé.
    backend.statuses.e2 = done('e2', 2)
    backend.series = series({ status: 'COMPLETED', finishedAt: '2026-11-28T10:05:00Z', runs: [run(1, 'e1'), run(2, 'e2')],
      summary: { verdict: 'CONFIRMED', message: 'Point de rupture confirmé à 63 req/s sur les 2 essais.' } as BackendStressSeriesResponse['summary'] })
    const modal = await screen.findByRole('dialog', {}, { timeout: 5000 })
    await waitFor(() => expect(within(modal).getByTestId('em-series-summary').textContent)
      .toBe('Série de 2 essais : Point de rupture confirmé à 63 req/s sur les 2 essais.'), { timeout: 4000 })
    fireEvent.click(within(modal).getByRole('button', { name: 'Fermer' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(JSON.parse(window.sessionStorage.getItem('loadpilot.monitored-executions')!)).toEqual([])
  }, 20_000)
})
