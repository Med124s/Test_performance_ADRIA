import { useEffect } from 'react'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../context/ToastContext'
import { ExecutionMonitorProvider, progressOf, useExecutionMonitor } from '../context/ExecutionMonitorContext'
import { BackendExecutionReportResponse, BackendExecutionStatusResponse } from '../types/backendContracts'

const auth = vi.hoisted(() => ({ roles: ['ROLE_PERFORMANCE_ENGINEER'] }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: auth.roles }) }))
const api = vi.hoisted(() => ({ getStatus: vi.fn(), getReport: vi.fn(), cancel: vi.fn(), pause: vi.fn(), resume: vi.fn() }))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: api }))

import ExecutionLive, { describeActionError } from './ExecutionLive'
import { ApiError } from '../services/api/httpClient'

const status = (patch: Partial<BackendExecutionStatusResponse> = {}): BackendExecutionStatusResponse => ({
  id: 'ex-1', status: 'RUNNING', startedAt: '2026-10-02T10:00:00Z', finishedAt: null, duration: null, virtualUsers: 5,
  totalRequests: 42, successfulRequests: 40, failedRequests: 2, progressPercent: 40, scenarioId: 'sc-1',
  scenarioName: 'Parcours bancaire', rampUpSeconds: 5, durationSeconds: 30, iterations: null, plannedIterations: null,
  errorMessage: null, pausedAt: null, pausedDurationMs: 0, activeElapsedMs: 12_400,
  live: {
    elapsedMs: 12_400, activeVirtualUsers: 4, startedVirtualUsers: 5, completedIterations: 7, requests: 42, successes: 40,
    failures: 2, throughputRps: 8.6, avgResponseTimeMs: 214,
    steps: [
      { stepId: 's1', name: 'Connexion', method: 'POST', requests: 22, successes: 22, failures: 0, avgResponseTimeMs: 180 },
      { stepId: 's2', name: 'Virement', method: 'POST', requests: 20, successes: 18, failures: 2, avgResponseTimeMs: 250 },
    ],
  },
  ...patch,
})

const report = {
  id: 'ex-1', status: 'SUCCESS', steps: [], errors: [],
  statistics: { totalRequests: 300, successfulRequests: 300, failedRequests: 0, successRate: 100, errorRate: 0, minResponseTime: 100,
    maxResponseTime: 400, avgResponseTime: 210.4, p50: 200, p75: 230, p90: 260, p95: 288, p99: 350, throughput: 9.8 },
} as unknown as BackendExecutionReportResponse

function Tracker({ id }: { id: string }) {
  const { track } = useExecutionMonitor()
  useEffect(() => { track(id, 'Parcours bancaire') }, [id, track])
  return <div>Autre page</div>
}

const renderAt = (url: string) => render(
  <MemoryRouter initialEntries={[url]}>
    <ToastProvider>
      <ExecutionMonitorProvider>
        <Routes>
          <Route path="/executions/live/:id" element={<ExecutionLive />} />
          <Route path="/autre" element={<Tracker id="ex-1" />} />
        </Routes>
      </ExecutionMonitorProvider>
    </ToastProvider>
  </MemoryRouter>,
)

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
  window.sessionStorage.clear()
  api.getReport.mockResolvedValue(report)
})

describe('progressOf', () => {
  it('temps écoulé / durée, itérations faites / prévues, terminé', () => {
    expect(progressOf(status())).toEqual({ percent: 40, label: '12 s / 30 s' })
    expect(progressOf(status({ durationSeconds: null, plannedIterations: 10, progressPercent: 70 }))).toEqual({ percent: 70, label: '7 / 10 itérations' })
    expect(progressOf(status({ status: 'QUEUED', live: null })).label).toBe('En attente de démarrage')
    expect(progressOf(status({ status: 'SUCCESS', live: null, progressPercent: 100 }))).toEqual({ percent: 100, label: 'Terminé — succès' })
  })

  it('pause : temps de travail réel (pauses exclues), libellé « En pause »', () => {
    // 20 s écoulées depuis le démarrage dont 8 s de pause : 12 s de travail.
    const paused = status({ status: 'PAUSED', activeElapsedMs: 12_000, pausedDurationMs: 8_000, live: { ...status().live!, elapsedMs: 20_000 } })
    expect(progressOf(paused)).toEqual({ percent: 40, label: 'En pause · 12 s / 30 s' })
  })
})

describe('Suivi en direct', () => {
  it('page de suivi : statut, utilisateurs actifs, compteurs, débit, temps moyen et compteur par étape', async () => {
    api.getStatus.mockResolvedValue(status())
    renderAt('/executions/live/ex-1')

    expect(await screen.findByText('4 / 5')).toBeTruthy()
    expect(screen.getByText('Parcours bancaire')).toBeTruthy()
    expect(screen.getByText(/12 s \/ 30 s · 40 %/)).toBeTruthy()
    expect(screen.getByText('8.6')).toBeTruthy()
    expect(screen.getByText('214 ms')).toBeTruthy()
    const rows = screen.getAllByRole('row')
    expect(within(rows[2]).getByText(/Virement/)).toBeTruthy()
    expect(within(rows[2]).getByTitle('Échecs partiels')).toBeTruthy()
  })

  it('annulation avec confirmation', async () => {
    api.getStatus.mockResolvedValue(status())
    api.cancel.mockResolvedValue({})
    renderAt('/executions/live/ex-1')
    fireEvent.click(await screen.findByRole('button', { name: /Annuler l’exécution/ }))
    expect(screen.getByText('Annuler l’exécution ?')).toBeTruthy()
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /Annuler l’exécution/ }))
    await waitFor(() => expect(api.cancel).toHaveBeenCalledWith('ex-1'))
  })

  it('panneau flottant sur une autre page, puis modale de résultat avec liens détail / rapport ; suivi conservé en sessionStorage', async () => {
    api.getStatus.mockResolvedValueOnce(status()).mockResolvedValue(status({ status: 'SUCCESS', live: null, progressPercent: 100, totalRequests: 300 }))
    renderAt('/autre')

    const dock = await screen.findByRole('region', { name: 'Exécutions en cours' })
    expect(within(dock).getByText('Parcours bancaire')).toBeTruthy()
    expect(within(dock).getByRole('link', { name: 'Suivre' }).getAttribute('href')).toBe('/executions/live/ex-1')
    expect(JSON.parse(window.sessionStorage.getItem('loadpilot.monitored-executions')!)).toEqual([{ id: 'ex-1', scenarioName: 'Parcours bancaire' }])

    const modal = await screen.findByRole('dialog', {}, { timeout: 3000 })
    expect(within(modal).getByText('SUCCESS')).toBeTruthy()
    await within(modal).findByText('100 %')
    expect(within(modal).getByText('288 ms')).toBeTruthy()
    expect(within(modal).getByText('210.4 ms')).toBeTruthy()
    expect(within(modal).getByRole('link', { name: 'Voir le détail de l’exécution' }).getAttribute('href')).toBe('/executions/detail/ex-1')
    expect(within(modal).getByRole('link', { name: 'Voir le rapport' }).getAttribute('href')).toBe('/executions/report/ex-1')

    fireEvent.click(within(modal).getByRole('button', { name: 'Fermer' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(JSON.parse(window.sessionStorage.getItem('loadpilot.monitored-executions')!)).toEqual([])
  })

  it('Pause : bouton à côté de « Annuler », puis bandeau orange « En pause » avec le temps écoulé et bouton « Reprendre »', async () => {
    api.getStatus.mockResolvedValue(status())
    api.pause.mockResolvedValue({})
    renderAt('/executions/live/ex-1')
    fireEvent.click(await screen.findByRole('button', { name: /Pause/ }))
    await waitFor(() => expect(api.pause).toHaveBeenCalledWith('ex-1'))

    const pausedAt = new Date(Date.now() - 65_000).toISOString()
    api.getStatus.mockResolvedValue(status({ status: 'PAUSED', pausedAt, pausedDurationMs: 65_000 }))
    api.resume.mockResolvedValue({})
    await waitFor(() => expect(document.querySelector('.em-paused-banner')).toBeTruthy(), { timeout: 3000 })
    const banner = document.querySelector('.em-paused-banner') as HTMLElement
    expect(within(banner).getByText('En pause')).toBeTruthy()
    expect(banner.textContent).toMatch(/depuis 1 min 0[4-8] s/)
    expect(screen.getByText(/En pause · 12 s \/ 30 s/)).toBeTruthy()
    // Toujours annulable en pause.
    expect(screen.getByRole('button', { name: /Annuler l’exécution/ })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Reprendre/ }))
    await waitFor(() => expect(api.resume).toHaveBeenCalledWith('ex-1'))
  })

  it('pause refusée : message clair (jamais « Erreur 404 » brut) ; bouton Pause masqué une fois terminée', async () => {
    api.getStatus.mockResolvedValue(status())
    api.pause.mockRejectedValue(new ApiError('Cette exécution est déjà terminée, elle ne peut plus être mise en pause.', 409))
    renderAt('/executions/live/ex-1')
    fireEvent.click(await screen.findByRole('button', { name: /Pause/ }))
    expect(await screen.findByText('Cette exécution est déjà terminée, elle ne peut plus être mise en pause.')).toBeTruthy()

    api.getStatus.mockResolvedValue(status({ status: 'FAILED', live: null, progressPercent: 100 }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /Pause/ })).toBeNull(), { timeout: 3000 })
  })

  it('describeActionError : 404 sans explication du serveur -> backend à mettre à jour', () => {
    expect(describeActionError(new ApiError('Erreur 404 sur /api/executions/x/pause', 404), 'pause'))
      .toBe('Mise en pause indisponible : le serveur LoadPilot ne connaît pas encore cette action. Redémarrez le backend avec la dernière version.')
    expect(describeActionError(new ApiError('Exécution introuvable (elle a peut-être été supprimée).', 404), 'pause'))
      .toBe('Exécution introuvable (elle a peut-être été supprimée).')
    expect(describeActionError(new ApiError('Erreur 403 sur /api/executions/x/resume', 403), 'resume')).toBe('Reprise refusée : votre rôle ne le permet pas.')
  })

  it('lecteur : ni Pause ni Annuler', async () => {
    auth.roles = ['ROLE_VIEWER']
    api.getStatus.mockResolvedValue(status())
    renderAt('/executions/live/ex-1')
    expect(await screen.findByText('4 / 5')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Pause/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Annuler/ })).toBeNull()
  })

  it('au-delà de 10 étapes : 10 par page, repère rouge sur la page en échec, page conservée pendant les mises à jour, filtre « en échec »', async () => {
    const steps = (failing: number[], requests: number) => Array.from({ length: 25 }, (_, i) => ({
      stepId: `s${i + 1}`, name: `Étape ${i + 1}`, method: 'GET' as const, requests,
      successes: failing.includes(i + 1) ? requests - 1 : requests, failures: failing.includes(i + 1) ? 1 : 0, avgResponseTimeMs: 100,
    }))
    const withSteps = (failing: number[], requests: number) => status({ live: { ...status().live!, steps: steps(failing, requests) } })
    api.getStatus.mockResolvedValue(withSteps([14], 3))
    renderAt('/executions/live/ex-1')

    expect(await screen.findByText('Étapes 1 à 10 sur 25')).toBeTruthy()
    expect(screen.getByText('1. Étape 1', { exact: false })).toBeTruthy()
    expect(screen.queryByText(/11\. .*Étape 11/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Page 2 — contient des étapes en échec' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '1' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Page 2 — contient des étapes en échec' }))
    expect(screen.getByText('Étapes 11 à 20 sur 25')).toBeTruthy()
    // Nouvelles valeurs en direct : on reste sur la page 2.
    api.getStatus.mockResolvedValue(withSteps([14, 3], 9))
    await waitFor(() => expect(screen.getAllByText('9').length).toBeGreaterThan(0), { timeout: 3000 })
    expect(screen.getByText('Étapes 11 à 20 sur 25')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Page 1 — contient des étapes en échec' })).toBeTruthy()

    fireEvent.click(screen.getByLabelText(/Afficher seulement les étapes en échec/))
    expect(screen.getByText(/14\. .*Étape 14/)).toBeTruthy()
    expect(screen.getByText(/3\. .*Étape 3/)).toBeTruthy()
    expect(screen.queryByText(/1\. .*Étape 1$/)).toBeNull()
    // 2 étapes en échec : pas de pagination.
    expect(screen.queryByText(/Étapes .* sur/)).toBeNull()
  })

  it('reprend le suivi au rechargement (sessionStorage)', async () => {
    window.sessionStorage.setItem('loadpilot.monitored-executions', JSON.stringify([{ id: 'ex-9', scenarioName: 'Repris' }]))
    api.getStatus.mockResolvedValue(status({ id: 'ex-9', scenarioName: 'Repris' }))
    render(<MemoryRouter><ToastProvider><ExecutionMonitorProvider><div /></ExecutionMonitorProvider></ToastProvider></MemoryRouter>)
    expect(await screen.findByText('Repris')).toBeTruthy()
    expect(api.getStatus).toHaveBeenCalledWith('ex-9')
  })
})
