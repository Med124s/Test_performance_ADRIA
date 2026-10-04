import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

// Graphiques (canvas absent de jsdom) et barre du haut hors du périmètre de ce test.
vi.mock('react-chartjs-2', () => ({ Bar: () => <div />, Line: () => <div />, Doughnut: () => <div /> }))
vi.mock('../components/TopBar', () => ({ default: () => <div /> }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: ['ROLE_VIEWER'] }) }))
vi.mock('../services/api/metricsBackend', () => ({ metricsBackendApi: { search: () => Promise.resolve([]) } }))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: {
  getSummary: () => Promise.resolve({
    id: '11111111-2222-4333-8444-555555555555', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', status: 'FAILED',
    startedAt: '2026-10-04T10:00:00Z', finishedAt: '2026-10-04T10:01:00Z', duration: 60_000, virtualUsers: 10, rampUpSeconds: 5,
    durationSeconds: 60, iterations: null, totalSteps: 9, successfulSteps: 2115, failedSteps: 37,
    errorMessage: 'Reponse HTTP 409 (attendu 201)', results: [], requestDetailMode: 'FAILURES_AND_SAMPLE',
    detailSampleCapReached: false, detailedFailures: 37, detailedSamples: 120,
  }),
  getReport: () => Promise.resolve({ steps: [] }),
} }))

import ExecutionDetail from './ExecutionDetail'

describe('Détail d’une exécution échouée', () => {
  it('ni bandeau d’erreur, ni badge de statut dans le résumé, titre « PERFORMANCE » seul ; les autres badges restent', async () => {
    render(
      <MemoryRouter initialEntries={['/executions/detail/11111111-2222-4333-8444-555555555555']}>
        <Routes><Route path="/executions/detail/:id" element={<ExecutionDetail />} /></Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByText(/2115 étape\(s\) réussie\(s\)/)).toBeTruthy()
    expect(screen.queryByText('Reponse HTTP 409 (attendu 201)')).toBeNull()
    // « Échouée » une seule fois : à côté du titre.
    expect(screen.getAllByText(/Échouée/)).toHaveLength(1)
    expect(screen.getByText(/37 étape\(s\) échouée\(s\)/)).toBeTruthy()
    expect(screen.getByText(/10 VU/)).toBeTruthy()
    expect(screen.getByText(/Ramp-up 5s/)).toBeTruthy()
    expect(screen.getByText(/Durée 60s/)).toBeTruthy()
    expect(screen.queryByText(/données réelles Spring Boot/)).toBeNull()
    expect(screen.getByText('PERFORMANCE')).toBeTruthy()
  })
})
