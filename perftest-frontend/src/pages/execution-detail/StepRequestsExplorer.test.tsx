import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BackendExecutionRequestDetail, BackendExecutionRequestRow, BackendStepReportEntry } from '../../types/backendContracts'

const api = vi.hoisted(() => ({ getResults: vi.fn(), getResultDetail: vi.fn() }))
vi.mock('../../services/api/executionsBackend', () => ({ executionsBackendApi: api }))

import StepRequestsExplorer from './StepRequestsExplorer'

const steps: BackendStepReportEntry[] = [
  { stepId: 'st-1', stepName: 'Connexion', method: 'POST', url: '/api/auth/login', total: 25, success: 25, failed: 0, avgResponseTime: 180, minResponseTime: 100, maxResponseTime: 300, p95: 250, p99: 290, errorRate: 0 },
  { stepId: 'st-2', stepName: 'Virement', method: 'POST', url: '/api/transfers/national', total: 25, success: 23, failed: 2, avgResponseTime: 210, minResponseTime: 120, maxResponseTime: 400, p95: 320, p99: 390, errorRate: 8 },
]

const row = (patch: Partial<BackendExecutionRequestRow>): BackendExecutionRequestRow => ({
  resultId: 'r-1', stepId: 'st-2', stepName: 'Virement', method: 'POST', virtualUser: 0, iteration: 0, timestamp: '2026-10-04T10:00:00Z',
  httpStatus: 201, responseTime: 210, success: true, error: null, requestUrl: 'http://localhost:8888/api/transfers/national', detailKind: 'SAMPLE', ...patch,
})

const detail = (patch: Partial<BackendExecutionRequestDetail>): BackendExecutionRequestDetail => ({
  summary: row({}), kind: 'SAMPLE',
  request: { method: 'POST', url: 'http://localhost:8888/api/transfers/national', headers: { Authorization: '••••••', 'Content-Type': 'application/json' },
    body: '{"sourceAccount":"ACC001","amount":60}', bodyTruncated: false },
  response: { status: 201, timeMs: 210, sizeBytes: 98, headers: { 'Content-Type': 'application/json' }, body: '{"success":true,"transferId":"TRF002"}', bodyTruncated: false },
  checks: { expectedStatus: '201', statusOk: true, assertionText: '"status":"completed"', assertionOk: true, captureName: 'transferId', capturePath: '$.transferId', captureValue: 'TRF002', captureFound: true },
  ...patch,
})

const renderExplorer = (capReached = false) => render(
  <StepRequestsExplorer executionId="ex-1" virtualUsers={5} steps={steps} requestDetailMode="FAILURES_AND_SAMPLE"
    detailSampleCapReached={capReached} detailedFailures={2} detailedSamples={30} />,
)

beforeEach(() => vi.clearAllMocks())

describe('Résultats par étape', () => {
  it('ouvre une étape : requêtes paginées côté serveur, filtres VU / itération / échecs seulement', async () => {
    api.getResults.mockResolvedValue({ content: [row({}), row({ resultId: 'r-2', virtualUser: 3, iteration: 4, detailKind: null })], page: 0, size: 20, totalElements: 45, totalPages: 3 })
    renderExplorer()
    expect(screen.getByText(/Détail des échecs \+ échantillon des réussites · 2 échec\(s\) et 30 réussite\(s\) détaillés/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Virement/ }))
    await waitFor(() => expect(api.getResults).toHaveBeenLastCalledWith('ex-1', expect.objectContaining({ stepId: 'st-2', page: 0, size: 20, failedOnly: false })))
    expect(await screen.findByText('VU 4', { selector: 'td' })).toBeTruthy()
    expect(screen.getByText('Requêtes 1 à 20 sur 45')).toBeTruthy()
    // Requête sans détail conservé : pas de bouton.
    expect(screen.getAllByRole('button', { name: /Voir le détail de la requête/ })).toHaveLength(1)

    fireEvent.change(screen.getByLabelText('Filtrer par utilisateur virtuel'), { target: { value: '2' } })
    await waitFor(() => expect(api.getResults).toHaveBeenLastCalledWith('ex-1', expect.objectContaining({ virtualUser: 2, page: 0 })))
    fireEvent.change(screen.getByLabelText('Filtrer par itération'), { target: { value: '3' } })
    await waitFor(() => expect(api.getResults).toHaveBeenLastCalledWith('ex-1', expect.objectContaining({ virtualUser: 2, iteration: 2 })))
    fireEvent.click(screen.getByLabelText('Échecs seulement'))
    await waitFor(() => expect(api.getResults).toHaveBeenLastCalledWith('ex-1', expect.objectContaining({ failedOnly: true })))
    fireEvent.click(screen.getByRole('button', { name: '2' }))
    await waitFor(() => expect(api.getResults).toHaveBeenLastCalledWith('ex-1', expect.objectContaining({ page: 1 })))
    // Tri par temps de réponse (côté serveur), retour à la page 1.
    expect(api.getResults).toHaveBeenLastCalledWith('ex-1', expect.objectContaining({ sort: 'TIME' }))
    fireEvent.change(screen.getByLabelText('Trier les requêtes'), { target: { value: 'SLOWEST' } })
    await waitFor(() => expect(api.getResults).toHaveBeenLastCalledWith('ex-1', expect.objectContaining({ sort: 'SLOWEST', page: 0 })))
    fireEvent.change(screen.getByLabelText('Trier les requêtes'), { target: { value: 'FASTEST' } })
    await waitFor(() => expect(api.getResults).toHaveBeenLastCalledWith('ex-1', expect.objectContaining({ sort: 'FASTEST' })))
  })

  it('requête réussie : requête envoyée, réponse en JSON lisible, vérifications ; secrets masqués', async () => {
    api.getResults.mockResolvedValue({ content: [row({})], page: 0, size: 20, totalElements: 1, totalPages: 1 })
    api.getResultDetail.mockResolvedValue(detail({}))
    renderExplorer()
    fireEvent.click(screen.getByRole('button', { name: /Virement/ }))
    fireEvent.click(await screen.findByRole('button', { name: /Voir le détail de la requête de VU 1, itération 1/ }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('Authorization')).toBeTruthy()
    expect(within(dialog).getByText('••••••')).toBeTruthy()
    expect(within(dialog).getByText(/POST http:\/\/localhost:8888\/api\/transfers\/national/)).toBeTruthy()

    fireEvent.click(within(dialog).getByRole('tab', { name: 'Réponse reçue' }))
    expect(within(dialog).getByText('Code 201')).toBeTruthy()
    expect(within(dialog).getByText(/"transferId": "TRF002"/)).toBeTruthy()

    fireEvent.click(within(dialog).getByRole('tab', { name: 'Vérifications' }))
    expect(within(dialog).getByText('Code attendu : 201')).toBeTruthy()
    expect(within(dialog).getByText(/\$\.transferId = TRF002/)).toBeTruthy()
  })

  it('requête en échec : message d’erreur, corps tronqué signalé, aucune réponse si erreur réseau', async () => {
    api.getResults.mockResolvedValue({ content: [row({ resultId: 'r-9', success: false, httpStatus: 409, detailKind: 'FAILURE', error: 'Reponse HTTP 409 (attendu 201)' })], page: 0, size: 20, totalElements: 1, totalPages: 1 })
    api.getResultDetail.mockResolvedValue(detail({
      kind: 'FAILURE', summary: row({ success: false, httpStatus: 409, error: 'Reponse HTTP 409 (attendu 201)', detailKind: 'FAILURE' }),
      response: { status: 409, timeMs: 150, sizeBytes: 30_500, headers: {}, body: '{"error":"conflit"', bodyTruncated: true },
      checks: { expectedStatus: '201', statusOk: false, assertionText: null, assertionOk: null, captureName: null, capturePath: null, captureValue: null, captureFound: null },
    }))
    renderExplorer()
    fireEvent.click(screen.getByRole('button', { name: /Virement/ }))
    fireEvent.click(await screen.findByRole('button', { name: /Voir le détail/ }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('Reponse HTTP 409 (attendu 201)')).toBeTruthy()
    expect(within(dialog).getByText('Détail conservé : échec')).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('tab', { name: 'Réponse reçue' }))
    expect(within(dialog).getByText('tronqué (10 Ko conservés)')).toBeTruthy()
    expect(within(dialog).getByText('Taille 29,8 Ko')).toBeTruthy()
  })

  it('plafond mémoire atteint : signalé', () => {
    renderExplorer(true)
    expect(screen.getByText(/Plafond mémoire du détail des requêtes atteint/)).toBeTruthy()
  })
})
