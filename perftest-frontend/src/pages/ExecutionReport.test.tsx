import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../context/ToastContext'
import { BackendExecutionReportResponse, BackendStepReportEntry } from '../types/backendContracts'

// Graphiques de l'analyse dans le temps (canvas absent de jsdom) et barre du haut hors du périmètre de ces tests.
vi.mock('react-chartjs-2', () => ({ Bar: () => <div />, Line: () => <div /> }))
vi.mock('../components/TopBar', () => ({ default: () => <div /> }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: ['ROLE_PERFORMANCE_ENGINEER'] }) }))
const api = vi.hoisted(() => ({ getReport: vi.fn(), exportReportXlsx: vi.fn(),
  exportReportPdf: vi.fn(), getTimeline: vi.fn(), getExtremes: vi.fn(), getResultDetail: vi.fn() }))
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
  it('cartes Minimum et Maximum cliquables : panneau des requêtes extrêmes, même valeur que la carte', async () => {
    api.getReport.mockResolvedValue(report())
    api.getExtremes.mockImplementation((_id: string, kind: string) => Promise.resolve({
      kind, cardValueMs: kind === 'MAX' ? 412 : 80, measuredRequests: 120, intervalSeconds: 1,
      requests: [{ request: { resultId: 'r1', stepId: 's1', stepName: 'Étape 3', method: 'GET', virtualUser: 0, iteration: 0,
        timestamp: '2026-10-02T10:00:02Z', httpStatus: 200, responseTime: kind === 'MAX' ? 412 : 80, success: true, error: null,
        requestUrl: null, detailKind: null }, secondsFromStart: 2.1, intervalStartSeconds: 2, activeVirtualUsers: 3, throughput: 6, p95Ms: 251 }],
    }))
    renderReport()
    fireEvent.click(await screen.findByRole('button', { name: /Maximum 412 ms : voir d’où vient cette valeur/ }))
    expect(api.getExtremes).toHaveBeenCalledWith('ex-1', 'MAX')
    expect(await screen.findByText(/Maximum : la requête la plus lente/)).toBeTruthy()
    expect(screen.getAllByText('412 ms').length).toBeGreaterThanOrEqual(2)
    fireEvent.click(screen.getByLabelText('Fermer'))
    fireEvent.click(screen.getByRole('button', { name: /Minimum 80 ms : voir d’où vient cette valeur/ }))
    expect(api.getExtremes).toHaveBeenLastCalledWith('ex-1', 'MIN')
    expect(await screen.findByText(/Minimum : la requête la plus rapide/)).toBeTruthy()
  })

  it('exécution sans requête : cartes Minimum / Maximum non cliquables', async () => {
    api.getReport.mockResolvedValue(report({ statistics: { ...report().statistics, totalRequests: 0, successfulRequests: 0, failedRequests: 0,
      minResponseTime: null, maxResponseTime: null } }))
    renderReport()
    await screen.findByText(/TEMPS DE RÉPONSE/)
    expect(screen.queryByRole('button', { name: /voir d’où vient cette valeur/ })).toBeNull()
  })

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

  it('ordre : configuration de charge, puis temps de réponse, puis cartes de synthèse, puis analyse dans le temps', async () => {
    api.getReport.mockResolvedValue(report())
    renderReport()
    await screen.findByText(/TEMPS DE RÉPONSE/)
    await screen.findByText(/Aucune requête enregistrée pour cette exécution/)
    const text = document.body.textContent ?? ''
    const order = ['CONFIGURATION DE CHARGE', 'TEMPS DE RÉPONSE', 'Durée réelle', 'Aucune requête enregistrée'].map((t) => text.indexOf(t))
    expect(order.every((i) => i >= 0)).toBe(true)
    expect([...order].sort((a, b) => a - b)).toEqual(order)
  })

  it('page simplifiée : plus de comparaison des étapes, de résultat par étape, de répartition des réponses ni de tableau des erreurs', async () => {
    api.getReport.mockResolvedValue(report({
      status: 'FAILED', errors: [{ stepName: 'Étape 12', method: 'GET', url: '/api/12', httpStatus: 500, error: 'Erreur interne', timestamp: '2026-10-02T10:00:30Z' }],
    }))
    renderReport()
    await screen.findByText(/TEMPS DE RÉPONSE/)
    for (const removed of ['Comparaison des étapes', 'Résultat par étape', 'Répartition des réponses', 'Erreurs', '1. Étape 1', 'Erreur interne']) {
      expect(screen.queryByText(removed)).toBeNull()
    }
    expect(screen.queryByText(/Étapes .* sur/)).toBeNull()
    expect(screen.queryByText(/au total · .* type\(s\)/)).toBeNull()
  })

  it('exécution échouée : pas de bandeau d’erreur, croix rouge sur le statut, plus de « Percentiles réels », analyse dans le temps', async () => {
    api.getReport.mockResolvedValue(report({ status: 'FAILED', errorMessage: 'Reponse HTTP 409 (attendu 201)', pausedDurationMs: 0 }))
    renderReport()
    expect(await screen.findByText(/Aucune requête enregistrée pour cette exécution : analyse dans le temps impossible/)).toBeTruthy()
    expect(screen.queryByText('Reponse HTTP 409 (attendu 201)')).toBeNull()
    expect(document.querySelector('.stat-icon.red .bi-x-circle')).toBeTruthy()
    expect(document.querySelector('.stat-icon.green .bi-check-circle')).toBeNull()
    expect(screen.queryByText('Percentiles réels')).toBeNull()
    expect(screen.queryByText(/en pause/)).toBeNull()
  })

  it('exports : Excel par défaut, puis PDF ; plus aucun export CSV ni .zip', async () => {
    api.getReport.mockResolvedValue(report())
    const file = (name: string) => ({ blob: new Blob(['x']), fileName: name })
    api.exportReportXlsx.mockResolvedValue(file('Rapport_Parcours_2026-10-10_18h13.xlsx'))
    api.exportReportPdf.mockResolvedValue(file('rapport-parcours-2026-10-10-1813.pdf'))
    URL.createObjectURL = vi.fn(() => 'blob:report')
    URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    renderReport()
    const excel = await screen.findByRole('button', { name: /Exporter Excel/ })
    expect(excel.className).toContain('pt-btn-primary')
    fireEvent.click(excel)
    expect(await screen.findByText('Export Excel téléchargé.')).toBeTruthy()
    expect(api.exportReportXlsx).toHaveBeenCalledWith('ex-1')
    fireEvent.click(screen.getByRole('button', { name: /Exporter en PDF/ }))
    await waitFor(() => expect(api.exportReportPdf).toHaveBeenCalledWith('ex-1'))
    await screen.findByText('Export PDF téléchargé.')
    expect(screen.queryByRole('button', { name: /CSV/ })).toBeNull()
    expect(screen.queryByText(/\.zip|Exporter CSV/)).toBeNull()
    expect(click).toHaveBeenCalledTimes(2)
    click.mockRestore()
  })

  it('seuils de réussite : résultat ✓ / ✕ et statut « Seuils dépassés » ; absent pour une ancienne exécution', async () => {
    api.getReport.mockResolvedValue(report({ status: 'THRESHOLDS_EXCEEDED',
      thresholds: { maxErrorRatePercent: 1, errorRatePercent: 0.8, errorRateOk: true, maxP95Ms: 200, p95Ms: 251, p95Ok: false } }))
    renderReport()
    const list = await screen.findByRole('list', { name: 'Seuils de réussite' })
    const items = Array.from(list.querySelectorAll('li'))
    expect(items[0].className).toBe('ok')
    expect(items[0].textContent).toMatch(/✓Taux d’erreurs 0,8 %maximum 1 %/)
    expect(items[1].className).toBe('ko')
    expect(items[1].textContent).toMatch(/✕P95 251 msmaximum 200 ms/)
    expect(screen.getAllByText('Seuils dépassés').length).toBeGreaterThan(0)
  })

  it('seuils : P95 sans seuil indiqué ; ancienne exécution sans section', async () => {
    api.getReport.mockResolvedValueOnce(report({ thresholds: { maxErrorRatePercent: 1, errorRatePercent: 0.8, errorRateOk: true, maxP95Ms: null, p95Ms: 251, p95Ok: null } }))
    const { unmount } = renderReport()
    expect(await screen.findByText('aucun seuil défini pour ce scénario')).toBeTruthy()
    unmount()
    api.getReport.mockResolvedValueOnce(report({ thresholds: null }))
    renderReport()
    await screen.findByText(/Rapport exécution/)
    expect(screen.queryByRole('list', { name: 'Seuils de réussite' })).toBeNull()
  })
})