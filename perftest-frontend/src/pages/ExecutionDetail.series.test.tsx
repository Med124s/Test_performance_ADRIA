import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

vi.mock('react-chartjs-2', () => ({ Bar: () => <div />, Line: () => <div />, Doughnut: () => <div /> }))
vi.mock('../components/TopBar', () => ({ default: () => <div /> }))
vi.mock('../components/StressSeriesPanel', () => ({ default: () => null }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: ['ROLE_PERFORMANCE_ENGINEER'] }) }))
vi.mock('../services/api/metricsBackend', () => ({ metricsBackendApi: { search: () => Promise.resolve([]) } }))
const cancel = vi.hoisted(() => vi.fn())
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: {
  getSummary: () => Promise.resolve({
    id: 'ex-2', scenarioId: 'sc-1', scenarioName: 'Démo', status: 'RUNNING', startedAt: '2026-11-28T10:00:00Z', finishedAt: null,
    duration: null, virtualUsers: 30, rampUpSeconds: 0, durationSeconds: null, iterations: null, totalSteps: 1, successfulSteps: 100,
    failedSteps: 0, errorMessage: null, results: [], requestDetailMode: 'FAILURES_AND_SAMPLE', detailSampleCapReached: false,
    detailedFailures: 0, detailedSamples: 0, throughputRps: 40, errorRatePercent: 0, loadMode: 'STRESS', series: { id: 's1', index: 2 },
  }),
  getReport: () => Promise.resolve({ steps: [] }),
  cancel,
} }))

import ExecutionDetail from './ExecutionDetail'

describe('Détail d’un essai de série en cours', () => {
  it('« Annuler » demande confirmation et annonce l’interruption de la série', async () => {
    cancel.mockResolvedValue({ status: 'CANCELLED', finishedAt: '2026-11-28T10:01:00Z', duration: 60_000 })
    render(
      <MemoryRouter initialEntries={['/executions/detail/ex-2']}>
        <Routes><Route path="/executions/detail/:id" element={<ExecutionDetail />} /></Routes>
      </MemoryRouter>,
    )
    fireEvent.click(await screen.findByRole('button', { name: /^.*Annuler$/ }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByTestId('series-cancel-warning').textContent).toMatch(/l’annuler interrompt la série/)
    expect(cancel).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: /Annuler l’essai et la série/ }))
    await waitFor(() => expect(cancel).toHaveBeenCalledWith('ex-2'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
})
