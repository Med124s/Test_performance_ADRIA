import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../context/ToastContext'

// Page Rapports : seulement les exécutions terminées, filtres et pagination dans l'URL, sélection
// pour comparer, export PDF, menu « ⋯ » selon les droits.

const auth = vi.hoisted(() => ({ roles: ['ROLE_PERFORMANCE_ENGINEER'] }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: auth.roles }) }))
const api = vi.hoisted(() => ({
  getPage: vi.fn(), exportReportPdf: vi.fn(), setKeep: vi.fn(), remove: vi.fn(), bulkDelete: vi.fn(),
  getDeletionImpact: vi.fn(),
}))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: api }))
vi.mock('../services/api/scenariosBackend', () => ({ scenariosBackendApi: { getAll: () => Promise.resolve([
  { id: 'sc-1', name: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Bank Adria' },
  { id: 'sc-2', name: 'Login', applicationId: 'app-1', applicationName: 'Bank Adria' },
]) } }))
vi.mock('../services/api/applicationsBackend', () => ({ applicationsBackendApi: { getAll: () => Promise.resolve([
  { id: 'app-1', name: 'Bank Adria' }, { id: 'app-2', name: 'Autre' },
]) } }))
const download = vi.hoisted(() => ({ saveBlob: vi.fn() }))
vi.mock('../utils/download', () => download)

import Reports from './Reports'

const HOUR = 3600_000
const row = (patch: Record<string, unknown> = {}) => ({
  id: 'ex-1', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Bank Adria',
  status: 'SUCCESS', startedAt: new Date(Date.now() - 2 * HOUR).toISOString(), finishedAt: null, duration: 122_812, virtualUsers: 10,
  rampUpSeconds: 20, durationSeconds: 120, iterations: null, totalSteps: 4510, successfulSteps: 4510, failedSteps: 0,
  triggeredByUsername: 'engineer-test', throughput: 36.7, cpuAvgPercent: null, cpuMaxPercent: 18.5, memoryMaxMb: 89.8, p95Ms: 308,
  statusReason: null, maxErrorRatePercent: 1, maxP95Ms: 1000, previousP95Ms: null, previousStartedAt: null, keepResults: false,
  resultsPurgedAt: null, ...patch,
})
const page = (content: unknown[], total = content.length) => ({
  content, page: 0, size: 10, totalElements: total, totalPages: Math.max(1, Math.ceil(total / 10)),
  counts: { total, queued: 0, running: 0, paused: 0, success: 2, failed: 1, cancelled: 0, thresholdsExceeded: 1 },
})

function Location() {
  const l = useLocation()
  return <div data-testid="location">{l.pathname}{l.search}</div>
}
const renderAt = (url = '/rapports') => render(
  <MemoryRouter initialEntries={[url]}>
    <ToastProvider>
      <Routes>
        <Route path="/rapports" element={<Reports />} />
        <Route path="*" element={<p>AUTRE PAGE</p>} />
      </Routes>
      <Routes><Route path="*" element={<Location />} /></Routes>
    </ToastProvider>
  </MemoryRouter>,
)
const lastQuery = () => api.getPage.mock.calls[api.getPage.mock.calls.length - 1][0]
const location = () => screen.getByTestId('location').textContent

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
  api.getPage.mockResolvedValue(page([
    row(),
    row({ id: 'ex-2', status: 'THRESHOLDS_EXCEEDED', statusReason: 'Erreurs : 2,5 % (maximum 1 %)', totalSteps: 200, successfulSteps: 195, failedSteps: 5,
      startedAt: new Date(Date.now() - 26 * HOUR).toISOString(), resultsPurgedAt: '2026-09-01T03:45:00Z', keepResults: false }),
    row({ id: 'ex-3', status: 'FAILED', statusReason: 'Application injoignable', scenarioName: 'Login', scenarioId: 'sc-2' }),
  ], 3))
})

describe('Page Rapports', () => {
  it('liste des exécutions terminées seulement, plus récentes d’abord, colonnes et ligne complète', async () => {
    renderAt()
    await screen.findAllByTestId('report-row')
    expect(lastQuery()).toMatchObject({ finished: true, page: 0, size: 10, sort: 'startedAt,desc' })
    expect(screen.getByRole('heading', { name: 'Rapports' })).toBeTruthy()
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent?.trim()))
      .toEqual(['', 'Scénario', 'Résultat', 'VU', 'P95', 'Taux d’erreurs', 'Débit', 'Date', 'Actions'])
    const [first, second] = screen.getAllByTestId('report-row')
    expect(first.textContent).toContain('Parcours bancaire')
    expect(first.textContent).toContain('Bank Adria')
    expect(first.textContent).toContain('Réussie')
    expect(first.textContent).toContain('308 ms')
    expect(first.textContent).toMatch(/0\s*%/)
    expect(first.textContent).toContain('il y a 2 heures')
    expect(within(first).getByText('il y a 2 heures').getAttribute('title')).toMatch(/\d{4}/)
    // Seuils dépassés : raison en petit, taux d'erreurs en rouge, « Résumé seul » quand le détail est purgé.
    expect(second.textContent).toContain('Seuils dépassés')
    expect(second.textContent).toContain('Erreurs : 2,5 % (maximum 1 %)')
    expect(within(second).getByText(/2,5\s*%/, { selector: '.rp-rate' }).className).toContain('bad')
    expect(second.textContent).toContain('Résumé seul')
    expect(first.textContent).not.toContain('Résumé seul')
    expect(screen.getByText('Affichage de 1 à 3 sur 3 rapports')).toBeTruthy()
  })

  it('filtres simples dans l’URL (recherche, scénario, application, résultat, période), « Réinitialiser » si un filtre est actif', async () => {
    renderAt()
    await screen.findAllByTestId('report-row')
    expect(screen.queryByRole('button', { name: /Réinitialiser/ })).toBeNull()

    fireEvent.change(screen.getByRole('combobox', { name: 'Filtrer par résultat' }), { target: { value: 'FAILED' } })
    await waitFor(() => expect(lastQuery()).toMatchObject({ finished: true, status: 'FAILED' }))
    expect(location()).toBe('/rapports?result=FAILED')

    fireEvent.change(screen.getByRole('combobox', { name: 'Filtrer par application' }), { target: { value: 'app-1' } })
    await waitFor(() => expect(lastQuery()).toMatchObject({ applicationId: 'app-1', status: 'FAILED' }))

    fireEvent.change(screen.getByRole('combobox', { name: 'Période' }), { target: { value: '90d' } })
    await waitFor(() => expect(lastQuery().dateFrom).toBeTruthy())
    const from = new Date(lastQuery().dateFrom).getTime()
    expect(Math.abs(Date.now() - 90 * 24 * HOUR - from)).toBeLessThan(60_000)
    expect(location()).toContain('period=90d')

    fireEvent.change(screen.getByRole('textbox', { name: 'Rechercher un rapport' }), { target: { value: 'bancaire' } })
    await waitFor(() => expect(lastQuery()).toMatchObject({ search: 'bancaire' }), { timeout: 2000 })

    fireEvent.click(screen.getByRole('button', { name: /Réinitialiser/ }))
    await waitFor(() => expect(location()).toBe('/rapports'))
    expect(lastQuery()).toMatchObject({ finished: true })
    expect(lastQuery().status).toBeUndefined()
    expect(lastQuery().dateFrom).toBeUndefined()
  })

  it('période personnalisée, état lu depuis l’URL (page, taille 25, résultat), taille 10 / 25 / 50', async () => {
    api.getPage.mockResolvedValue({ ...page([row()], 30), page: 1, size: 25, totalPages: 2 })
    renderAt('/rapports?page=2&size=25&result=SUCCESS&period=custom&from=2026-10-01&to=2026-10-09')
    await screen.findAllByTestId('report-row')
    expect(lastQuery()).toMatchObject({ page: 1, size: 25, status: 'SUCCESS', finished: true })
    expect(lastQuery().dateFrom).toBe(new Date('2026-10-01T00:00:00').toISOString())
    expect(lastQuery().dateTo).toBe(new Date('2026-10-09T23:59:59.999').toISOString())
    const sizes = screen.getByRole('combobox', { name: 'Nombre d\'éléments par page' })
    expect([...sizes.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['10', '25', '50'])
    fireEvent.change(sizes, { target: { value: '50' } })
    await waitFor(() => expect(lastQuery()).toMatchObject({ size: 50, page: 0 }))
  })

  it('tri par date : plus récents d’abord par défaut, puis plus anciens d’abord', async () => {
    renderAt()
    await screen.findAllByTestId('report-row')
    fireEvent.click(screen.getByRole('button', { name: 'Trier par date : plus anciens d’abord' }))
    await waitFor(() => expect(lastQuery()).toMatchObject({ sort: 'startedAt,asc' }))
    expect(location()).toContain('sort=startedAt%2Casc')
  })

  it('sélection de 2 à 4 rapports : « Comparer » vers la comparaison existante', async () => {
    renderAt()
    await screen.findAllByTestId('report-row')
    fireEvent.click(screen.getAllByRole('checkbox', { name: /Sélectionner le rapport de Parcours bancaire/ })[0])
    const compare = screen.getByRole('button', { name: /Comparer/ })
    expect(compare).toHaveProperty('disabled', true)
    expect(compare.getAttribute('title')).toBe('Sélectionnez de 2 à 4 rapports.')
    fireEvent.click(screen.getAllByRole('checkbox', { name: /Sélectionner le rapport de/ })[1])
    expect(compare).toHaveProperty('disabled', false)
    fireEvent.click(compare)
    await waitFor(() => expect(location()).toBe('/executions/compare?ids=ex-1,ex-2'))
  })

  it('« Exporter en PDF » : fichier généré par le serveur, téléchargé sous son nom', async () => {
    const blob = new Blob(['%PDF-'], { type: 'application/pdf' })
    api.exportReportPdf.mockResolvedValue({ blob, fileName: 'rapport-parcours-bancaire-2026-10-10-1813.pdf' })
    renderAt()
    await screen.findAllByTestId('report-row')
    await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: 'Exporter en PDF le rapport de Parcours bancaire' })[0]) })
    expect(api.exportReportPdf).toHaveBeenCalledWith('ex-1')
    expect(download.saveBlob).toHaveBeenCalledWith(blob, 'rapport-parcours-bancaire-2026-10-10-1813.pdf')
    expect(screen.getByText('Rapport PDF de « Parcours bancaire » téléchargé.')).toBeTruthy()
  })

  it('ligne non cliquable ; « Voir le rapport » (colonne Actions) ouvre la page du rapport', async () => {
    renderAt()
    const rows = await screen.findAllByTestId('report-row')
    const before = location()
    fireEvent.click(rows[0])
    fireEvent.click(rows[0].querySelector('.ex-scenario-name')!)
    expect(location()).toBe(before)
    fireEvent.click(screen.getAllByRole('button', { name: 'Voir le rapport de Parcours bancaire' })[0])
    await waitFor(() => expect(location()).toBe('/executions/report/ex-1'))
  })

  it('droits : Observateur = consulter et exporter (Excel, PDF) seulement ; Ingénieur = aussi Conserver et Supprimer (pas Conserver si purgé)', async () => {
    auth.roles = ['ROLE_VIEWER']
    const { unmount } = renderAt()
    await screen.findAllByTestId('report-row')
    // Plus d'export CSV : le menu « ⋯ » d'un Observateur serait vide, il n'est pas affiché.
    expect(screen.queryByRole('button', { name: /Plus d’actions pour le rapport/ })).toBeNull()
    expect(screen.getAllByRole('button', { name: 'Exporter en PDF le rapport de Parcours bancaire' })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: 'Exporter Excel le rapport de Parcours bancaire' })).toHaveLength(2)
    unmount()

    auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
    renderAt()
    await screen.findAllByTestId('report-row')
    fireEvent.click(screen.getAllByRole('button', { name: 'Plus d’actions pour le rapport de Parcours bancaire' })[0])
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent?.trim())).toEqual(['Conserver', 'Supprimer'])
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(screen.getAllByRole('button', { name: /Plus d’actions pour le rapport de Parcours bancaire/ })[1])
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent?.trim())).toEqual(['Supprimer'])
    expect(screen.queryByText(/CSV|\.zip/)).toBeNull()
  })

  it('aucun rapport : message d’attente ; filtres sans résultat : bouton pour les réinitialiser', async () => {
    api.getPage.mockResolvedValue(page([], 0))
    const { unmount } = renderAt()
    expect(await screen.findByText(/Aucun rapport pour l’instant/)).toBeTruthy()
    unmount()
    renderAt('/rapports?result=CANCELLED')
    expect(await screen.findByText('Aucun rapport ne correspond à ces filtres.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Réinitialiser les filtres' })).toBeTruthy()
  })
})
