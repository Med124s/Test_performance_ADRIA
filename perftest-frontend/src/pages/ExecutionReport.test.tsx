import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../context/ToastContext'
import { BackendExecutionReportResponse, BackendStepReportEntry } from '../types/backendContracts'

// Graphiques (canvas absent de jsdom) et barre du haut hors du périmètre de ces tests.
vi.mock('react-chartjs-2', () => ({ Bar: () => <div />, Line: () => <div />, Doughnut: () => <div /> }))
vi.mock('../components/TopBar', () => ({ default: () => <div /> }))
const api = vi.hoisted(() => ({ getReport: vi.fn(), exportReportCsv: vi.fn(), getTimeline: vi.fn() }))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: api }))

import ExecutionReport from './ExecutionReport'

const step = (n: number, failed = 0): BackendStepReportEntry => ({
  stepId: `s${n}`, stepName: `Étape ${n}`, method: 'GET', url: `/api/${n}`, total: 10, success: 10 - failed, failed,
  avgResponseTime: 120 + n, minResponseTime: 80, maxResponseTime: 300 + n, p95: 250, p99: 290, errorRate: failed * 10,
})

const report = (patch: Partial<BackendExecutionReportResponse> = {}): BackendExecutionReportResponse => ({
  id: 'ex-1', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking',
  status: 'SUCCESS', startedAt: '2026-10-02T10:00:00Z', finishedAt: '2026-10-02T10:01:00Z', duration: 60_000, errorMessage: null,
  virtualUsers: 3, rampUpSeconds: 3, durationSeconds: 20, iterations: null, triggeredByUsername: 'admin-test', pausedDurationMs: 40_000,
  statistics: { totalRequests: 120, successfulRequests: 119, failedRequests: 1, successRate: 99.17, errorRate: 0.83, minResponseTime: 80,
    maxResponseTime: 412, avgResponseTime: 133, p50: 120, p75: 140, p90: 200, p95: 251, p99: 377, throughput: 6 },
  steps: Array.from({ length: 12 }, (_, i) => step(i + 1, i + 1 === 12 ? 1 : 0)),
  errors: [],
  ...patch,
})

const renderReport = () => render(
  <MemoryRouter initialEntries={['/executions/report/ex-1']}>
    <ToastProvider>
      <Routes><Route path="/executions/report/:id" element={<ExecutionReport />} /></Routes>
    </ToastProvider>
  </MemoryRouter>,
)

beforeEach(() => {
  vi.clearAllMocks()
  api.getTimeline.mockResolvedValue({ source: 'NONE', message: 'Aucune requête enregistrée pour cette exécution : analyse dans le temps impossible.' })
})

describe('Rapport d’exécution', () => {
  it('plus d’écart-type : minimum, moyenne, maximum, P95 et P99 seulement', async () => {
    api.getReport.mockResolvedValue(report())
    renderReport()
    expect((await screen.findAllByText('Minimum')).length).toBeGreaterThan(0)
    for (const label of ['Moyenne', 'Maximum', 'p95', 'p99']) expect(screen.getAllByText(label).length).toBeGreaterThan(0)
    expect(screen.getByText('412 ms')).toBeTruthy()
    expect(screen.getByText('377 ms')).toBeTruthy()
    expect(screen.queryByText(/[ÉE]cart-type/i)).toBeNull()
    expect(document.body.textContent).not.toMatch(/stdDev|cart-type/i)
  })

  it('temps passé en pause affiché ; débit indiqué hors pause', async () => {
    api.getReport.mockResolvedValue(report())
    renderReport()
    expect(await screen.findByText('dont 40 s en pause')).toBeTruthy()
    expect(screen.getByText('En pause pendant 40 s')).toBeTruthy()
    expect(screen.getByText('req / s (hors pause)')).toBeTruthy()
  })

  it('tableau des étapes : 10 par page, repère rouge sur la page en échec, filtre « en échec »', async () => {
    api.getReport.mockResolvedValue(report())
    renderReport()
    expect(await screen.findByText('Étapes 1 à 10 sur 12')).toBeTruthy()
    expect(screen.getByText('1. Étape 1')).toBeTruthy()
    expect(screen.queryByText('11. Étape 11')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Page 2 — contient des étapes en échec' }))
    expect(screen.getByText('Étapes 11 à 12 sur 12')).toBeTruthy()
    expect(screen.getByText('12. Étape 12')).toBeTruthy()

    fireEvent.click(screen.getByLabelText(/Afficher seulement les étapes en échec/))
    expect(screen.getByText('12. Étape 12')).toBeTruthy()
    expect(screen.queryByText('1. Étape 1')).toBeNull()
    expect(screen.queryByText(/Étapes .* sur/)).toBeNull()
  })

  it('exécution échouée : pas de bandeau d’erreur, croix rouge sur le statut, erreurs regroupées, plus de « Percentiles réels », analyse dans le temps', async () => {
    api.getReport.mockResolvedValue(report({
      status: 'FAILED', errorMessage: 'Reponse HTTP 409 (attendu 201)', errors: [],
      errorGroups: [
        { httpStatus: 409, stepName: 'Virement national', method: 'POST', error: 'Reponse HTTP 409 (attendu 201)', count: 12, firstAt: '2026-10-02T10:00:18Z' },
        { httpStatus: null, stepName: 'Dépôt', method: 'POST', error: 'Delai d\'attente depasse (10s) - message complet tres long', count: 1, firstAt: '2026-10-02T10:00:40Z' },
      ],
    }))
    renderReport()
    expect(await screen.findByText('13 au total · 2 type(s)')).toBeTruthy()
    expect(screen.queryByText('Reponse HTTP 409 (attendu 201)', { selector: '.pt-alert-banner' })).toBeNull()
    expect(document.querySelector('.stat-icon.red .bi-x-circle')).toBeTruthy()
    expect(document.querySelector('.stat-icon.green .bi-check-circle')).toBeNull()
    expect(screen.getByText('12')).toBeTruthy()
    const longMessage = screen.getByTitle(/message complet tres long/)
    expect(longMessage).toBeTruthy()
    expect(screen.queryByText('Percentiles réels')).toBeNull()
    expect(await screen.findByText(/Aucune requête enregistrée pour cette exécution : analyse dans le temps impossible/)).toBeTruthy()
  })

  it('10 étapes ou moins : pas de pagination', async () => {
    api.getReport.mockResolvedValue(report({ steps: [step(1), step(2)], pausedDurationMs: 0 }))
    renderReport()
    expect(await screen.findByText('2. Étape 2')).toBeTruthy()
    expect(screen.queryByText(/Étapes .* sur/)).toBeNull()
    expect(screen.queryByText(/en pause/)).toBeNull()
  })
})
