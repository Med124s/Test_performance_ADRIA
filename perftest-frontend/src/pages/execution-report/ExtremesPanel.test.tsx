import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BackendExecutionExtremes, BackendExecutionRequestRow } from '../../types/backendContracts'

const api = vi.hoisted(() => ({ getExtremes: vi.fn(), getResultDetail: vi.fn() }))
vi.mock('../../services/api/executionsBackend', () => ({ executionsBackendApi: api }))

import ExtremesPanel, { DETAIL_NOT_KEPT_TEXT, formatOffset } from './ExtremesPanel'

const item = (i: number, patch: Partial<BackendExecutionRequestRow> = {}): BackendExecutionExtremes['requests'][number] => ({
  request: {
    resultId: `r${i}`, stepId: 's1', stepName: 'Virement national', method: 'POST' as const, virtualUser: 4, iteration: 2,
    timestamp: '2026-10-06T09:00:02Z', httpStatus: 201, responseTime: 900 - i * 10, success: true, error: null, requestUrl: null,
    detailKind: (i === 0 ? 'SAMPLE' : null) as BackendExecutionRequestRow['detailKind'], ...patch,
  },
  secondsFromStart: 2.4 + i, intervalStartSeconds: 2 + i, activeVirtualUsers: 12, throughput: 68.66, p95Ms: 412,
})

const extremes = (patch: Partial<BackendExecutionExtremes> = {}): BackendExecutionExtremes => ({
  kind: 'MAX', cardValueMs: 900, measuredRequests: 11000, intervalSeconds: 1,
  requests: Array.from({ length: 10 }, (_, i) => item(i)), ...patch,
})

beforeEach(() => vi.clearAllMocks())

describe('panneau Minimum / Maximum', () => {
  it('requête la plus lente : étape, VU, itération, place dans le test, code, temps, contexte, lien vers le détail', async () => {
    api.getExtremes.mockResolvedValue(extremes())
    api.getResultDetail.mockResolvedValue({ summary: { ...item(0).request }, kind: 'SAMPLE', request: { method: 'POST', url: 'http://x/api', headers: {}, body: null, bodyTruncated: false }, response: null, checks: null })
    render(<ExtremesPanel executionId="ex-1" kind="MAX" onClose={() => {}} />)
    const hero = within(await screen.findByTestId('extreme-hero'))
    expect(hero.getByText('900 ms')).toBeTruthy()
    expect(hero.getByText(/Virement national/)).toBeTruthy()
    expect(hero.getByText(/VU 5 · itération 3 · .* \(à 2 s du début\) · HTTP 201/)).toBeTruthy()
    expect(hero.getByText('Au même moment : 12 utilisateur(s) actif(s) · 68,7 req/s · P95 de cette seconde : 412 ms')).toBeTruthy()
    expect(screen.getByText(/Les 10 requêtes les plus lentes/)).toBeTruthy()
    expect(screen.getByText(/sur 11\s000 mesurées/)).toBeTruthy()
    expect(api.getExtremes).toHaveBeenCalledWith('ex-1', 'MAX')

    const rows = screen.getAllByTestId('extreme-row')
    expect(rows).toHaveLength(10)
    expect(within(rows[1]).getByText('Détail non enregistré')).toBeTruthy()

    fireEvent.click(hero.getByRole('button', { name: /Voir la requête/ }))
    expect(api.getResultDetail).toHaveBeenCalledWith('ex-1', 'r0')
  })

  it('requête la plus rapide, détail non enregistré : message clair ; aucune requête ; intervalle de plusieurs secondes', async () => {
    api.getExtremes.mockResolvedValue(extremes({ kind: 'MIN', cardValueMs: 3, intervalSeconds: 2,
      requests: [item(0, { responseTime: 3, detailKind: null })] }))
    const { unmount } = render(<ExtremesPanel executionId="ex-1" kind="MIN" onClose={() => {}} />)
    expect(await screen.findByText(/Minimum : la requête la plus rapide/)).toBeTruthy()
    expect(screen.getByText(DETAIL_NOT_KEPT_TEXT)).toBeTruthy()
    expect(screen.getAllByText(/P95 de intervalle de 2 s : 412 ms/).length).toBeGreaterThan(0)
    unmount()

    api.getExtremes.mockResolvedValue(extremes({ cardValueMs: null, measuredRequests: 0, requests: [] }))
    render(<ExtremesPanel executionId="ex-2" kind="MAX" onClose={() => {}} />)
    expect(await screen.findByText('Aucune requête mesurée pour cette exécution.')).toBeTruthy()
  })

  it('place dans le test lisible', () => {
    expect(formatOffset(2.4)).toBe('à 2 s du début')
    expect(formatOffset(65.2)).toBe('à 1 min 05 s du début')
  })
})
