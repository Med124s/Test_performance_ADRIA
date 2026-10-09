import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../context/ToastContext'
import { BackendExecutionHistoryResponse, BackendExecutionPageResponse } from '../types/backendContracts'
import {
  cpuLevel, formatHumanDuration, formatRequests, maskFrDate, p95Delta, parseFrDate, periodBounds, relativeTime, requestsOf,
} from './executions/executionsModel'

const auth = vi.hoisted(() => ({ roles: ['ROLE_PERFORMANCE_ENGINEER'] }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: auth.roles }) }))
const api = vi.hoisted(() => ({
  getPage: vi.fn(), getStatus: vi.fn(), getById: vi.fn(), retry: vi.fn(), cancel: vi.fn(), execute: vi.fn(),
  deletionImpact: vi.fn(), remove: vi.fn(), bulkDelete: vi.fn(),
}))
vi.mock('../services/api/executionsBackend', () => ({ executionsBackendApi: api }))
vi.mock('../services/api/scenariosBackend', () => ({ scenariosBackendApi: { getAll: () => Promise.resolve([
  { id: 'sc-1', name: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking', status: 'ACTIVE', virtualUsers: 5, rampUpSeconds: 5, durationSeconds: 30, iterations: null },
  { id: 'sc-2', name: 'Connexion', applicationId: 'app-1', applicationName: 'Banking', status: 'ACTIVE', virtualUsers: 1, rampUpSeconds: 0, durationSeconds: null, iterations: 1 },
]) } }))
vi.mock('../services/api/applicationsBackend', () => ({ applicationsBackendApi: { getAll: () => Promise.resolve([{ id: 'app-1', name: 'Banking' }]) } }))
vi.mock('../services/api/stepsBackend', () => ({ stepsBackendApi: { getAll: () => Promise.resolve([]) } }))

import Executions from './Executions'

const row = (patch: Partial<BackendExecutionHistoryResponse>): BackendExecutionHistoryResponse => ({
  id: 'ex-1', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking', status: 'SUCCESS',
  startedAt: '2026-10-04T10:00:00Z', finishedAt: '2026-10-04T10:00:30Z', duration: 94_000, virtualUsers: 5, rampUpSeconds: 5,
  durationSeconds: 30, iterations: null, totalSteps: 9, successfulSteps: 7907, failedSteps: 202, triggeredByUsername: 'admin', throughput: 86.346,
  cpuAvgPercent: 47, cpuMaxPercent: 66.4, memoryMaxMb: 85.7, p95Ms: 550,
  ...patch,
})

const page = (content: BackendExecutionHistoryResponse[], counts: Partial<BackendExecutionPageResponse['counts']> = {}): BackendExecutionPageResponse => ({
  content, page: 0, size: 10, totalElements: content.length, totalPages: 1,
  counts: { total: content.length, queued: 0, running: 0, paused: 0, success: 0, failed: 0, cancelled: 0, ...counts },
})

function Location() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname}{location.search}</div>
}

const renderAt = (url = '/executions') => render(
  <MemoryRouter initialEntries={[url]}>
    <ToastProvider>
      <Routes>
        <Route path="/executions" element={<><Executions /><Location /></>} />
        <Route path="/executions/live/:id" element={<Location />} />
        <Route path="/executions/report/:id" element={<Location />} />
        <Route path="/executions/detail/:id" element={<Location />} />
        <Route path="/executions/compare" element={<Location />} />
      </Routes>
    </ToastProvider>
  </MemoryRouter>,
)

const lastQuery = () => api.getPage.mock.calls[api.getPage.mock.calls.length - 1][0]
const openMenu = (rowEl: HTMLElement) => fireEvent.click(within(rowEl).getByRole('button', { name: /Plus d’actions/ }))

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
  api.getStatus.mockResolvedValue({ id: 'ex-run', status: 'RUNNING', progressPercent: 40, durationSeconds: 30, plannedIterations: null,
    activeElapsedMs: 12_000, successfulRequests: 50, failedRequests: 1, live: { elapsedMs: 12_000, activeVirtualUsers: 5, startedVirtualUsers: 5, completedIterations: 4, requests: 51, successes: 50, failures: 1, throughputRps: 8, avgResponseTimeMs: 200, steps: [] } })
})

describe('formats de la page Exécutions', () => {
  it('durée, date relative, requêtes et taux d’erreur, dates françaises, périodes', () => {
    expect(formatHumanDuration(94_000)).toBe('1 min 34 s')
    expect(formatHumanDuration(8_400)).toBe('8 s')
    expect(formatHumanDuration(3_723_000)).toBe('1 h 02 min')
    const now = new Date('2026-10-06T12:00:00Z')
    expect(relativeTime('2026-10-06T10:00:00Z', now)).toBe('il y a 2 heures')
    expect(relativeTime('2026-10-05T12:00:00Z', now)).toBe('hier')
    expect(relativeTime('2026-10-06T11:59:50Z', now)).toBe('à l’instant')
    const req = requestsOf({ successfulSteps: 7907, failedSteps: 202 })
    expect(formatRequests(req.total, req.errorRate).total).toMatch(/^8\s109$/)
    expect(formatRequests(req.total, req.errorRate).rate).toBe('2,5 %')
    expect(requestsOf({ successfulSteps: 0, failedSteps: 0 }).errorRate).toBeNull()
    expect(maskFrDate('05102026')).toBe('05/10/2026')
    expect(parseFrDate('05/10/2026')).toBe('2026-10-05')
    expect(parseFrDate('31/02/2026')).toBeNull()
    expect(periodBounds('24h', '', '', now)).toEqual({ dateFrom: '2026-10-05T12:00:00.000Z' })
    expect(periodBounds('', '2026-10-01', '', now)).toEqual({})
    const custom = periodBounds('custom', '2026-10-01', '2026-10-05', now)
    expect(new Date(custom.dateFrom!).getHours()).toBe(0)
    expect(new Date(custom.dateTo!).getHours()).toBe(23)
  })
})

describe('Page Exécutions', () => {
  it('en-tête avec « Nouvelle exécution », plus de cartes ; onglets avec leur nombre (seulement > 0), statut à 0 en gris', async () => {
    api.getPage.mockResolvedValue(page([row({})], { total: 12, success: 8, failed: 3, cancelled: 1, thresholdsExceeded: 2 }))
    renderAt()
    expect(await screen.findByRole('tab', { name: /^Réussie\s*8$/ })).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Exécutions' })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Nouvelle exécution/ })).toBeTruthy()
    expect(screen.queryByText('Total exécutions')).toBeNull()
    expect(screen.getByRole('tab', { name: /^Toutes\s*12$/ }).getAttribute('aria-selected')).toBe('true')
    // Statut à 0 : visible, gris, sans badge de nombre.
    const paused = screen.getByRole('tab', { name: 'En pause' })
    expect(paused.className).toContain('empty')
    expect(paused.querySelector('.ex-tab-count')).toBeNull()
    expect(screen.getByRole('tab', { name: /^Seuils dépassés\s*2$/ }).className).not.toContain('empty')
  })

  it('filtres côté serveur dans l’URL (statut + scénario + recherche), retour page 1, réinitialisation', async () => {
    api.getPage.mockResolvedValue(page([row({})], { total: 12, success: 8, failed: 3, cancelled: 1 }))
    renderAt('/executions?page=2')
    fireEvent.click(await screen.findByRole('tab', { name: /^Échouée/ }))
    await waitFor(() => expect(api.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'FAILED', page: 0 })))
    expect(screen.getByTestId('location').textContent).toBe('/executions?status=FAILED')

    fireEvent.focus(screen.getByRole('combobox', { name: 'Filtrer par scénario' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'Filtrer par scénario' }), { target: { value: 'conn' } })
    fireEvent.mouseDown(await screen.findByRole('option', { name: /Connexion/ }))
    await waitFor(() => expect(api.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'FAILED', scenarioId: 'sc-2' })))

    fireEvent.change(screen.getByLabelText('Rechercher une exécution'), { target: { value: 'banking' } })
    await waitFor(() => expect(api.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'FAILED', scenarioId: 'sc-2', search: 'banking' })), { timeout: 2000 })
    expect(screen.getByTestId('location').textContent).toContain('q=banking')

    fireEvent.click(screen.getByRole('button', { name: /Réinitialiser/ }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions'))
    expect(screen.queryByRole('button', { name: /Réinitialiser/ })).toBeNull()
  })

  it('période : liste (24 h, 7 j, 30 j), « Personnalisée » en jj/mm/aaaa ; tri par colonne ; retour à la page 1', async () => {
    api.getPage.mockResolvedValue({ ...page([row({})], { success: 1 }), totalPages: 3, totalElements: 25 })
    renderAt('/executions?page=3')
    await screen.findAllByTestId('execution-row')
    expect(lastQuery()).toEqual(expect.objectContaining({ page: 2, sort: 'startedAt,desc' }))

    fireEvent.change(screen.getByLabelText('Période'), { target: { value: '7d' } })
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions?period=7d'))
    await waitFor(() => expect(Date.now() - new Date(lastQuery().dateFrom).getTime()).toBeGreaterThan(7 * 24 * 3600 * 1000 - 60_000))
    expect(lastQuery().page).toBe(0)

    fireEvent.change(screen.getByLabelText('Période'), { target: { value: 'custom' } })
    fireEvent.change(await screen.findByLabelText('Lancées à partir du'), { target: { value: '01102026' } })
    expect((screen.getByLabelText('Lancées à partir du') as HTMLInputElement).value).toBe('01/10/2026')
    fireEvent.change(screen.getByLabelText('Lancées jusqu’au'), { target: { value: '05/10/2026' } })
    await waitFor(() => {
      const q = lastQuery()
      expect(new Date(q.dateFrom).getDate()).toBe(1)
      expect(new Date(q.dateTo).getDate()).toBe(5)
    })
    expect(screen.getByTestId('location').textContent).toBe('/executions?period=custom&from=2026-10-01&to=2026-10-05')

    fireEvent.click(screen.getByRole('button', { name: 'Trier par durée' }))
    await waitFor(() => expect(lastQuery()).toEqual(expect.objectContaining({ sort: 'duration,desc', page: 0 })))
    fireEvent.click(screen.getByRole('button', { name: 'Trier par durée' }))
    await waitFor(() => expect(lastQuery().sort).toBe('duration,asc'))
    expect(screen.getByRole('button', { name: 'Trier par durée' }).closest('th')!.getAttribute('aria-sort')).toBe('ascending')

    fireEvent.click(screen.getByRole('button', { name: /^Réinitialiser$/ }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions?sort=duration%2Casc'))
  })

  it('tableau : scénario + application, requêtes et taux d’erreur, débit, P95, CPU max, durée, date relative ; pas de RAM ; clic = rapport', async () => {
    api.getPage.mockResolvedValue(page([row({}), row({ id: 'ex-2', failedSteps: 0, cpuMaxPercent: null, cpuAvgPercent: null, memoryMaxMb: null })], { success: 2 }))
    renderAt()
    const rows = await screen.findAllByTestId('execution-row')
    const first = within(rows[0])
    expect(first.getByText('Parcours bancaire')).toBeTruthy()
    expect(first.getByText('Banking')).toBeTruthy()
    expect(first.getByText('2,5 %').className).toContain('bad')
    expect(first.getByText('86,3 req/s')).toBeTruthy()
    expect(first.getByText('550 ms')).toBeTruthy()
    expect(first.getByText('66 %').closest('td')!.getAttribute('title')).toMatch(/moyenne 47 %, maximum 66,4 %/)
    expect(first.queryByText('86 Mo')).toBeNull()
    expect(screen.queryByRole('columnheader', { name: /RAM/ })).toBeNull()
    expect(first.getByText('1 min 34 s')).toBeTruthy()
    const started = rows[0].querySelector('.ex-started')!
    expect(started.getAttribute('title')).toMatch(/2026 à .* — lancée par admin$/)
    expect(within(rows[1]).getByText('0 %').className).not.toContain('bad')
    // CPU sans monitoring « — » ; écart de P95 sans exécution précédente « — » gris.
    expect(within(rows[1]).getAllByText('—').length).toBe(2)
    expect(screen.queryByRole('columnheader', { name: 'Application' })).toBeNull()

    // La case à cocher ne déclenche pas l'ouverture ; le reste de la ligne ouvre le rapport.
    fireEvent.click(within(rows[0]).getByRole('checkbox'))
    expect(screen.getByTestId('location').textContent).toBe('/executions')
    fireEvent.click(first.getByText('550 ms'))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions/report/ex-1'))
  })

  it('actions : détail et rapport en icônes ; Relancer et Supprimer dans « ⋯ » ; en cours : progression, « Suivre », suppression impossible', async () => {
    api.getPage.mockResolvedValue(page([row({ id: 'ex-run', status: 'RUNNING', duration: null }), row({ id: 'ex-1' })], { running: 1, success: 1 }))
    api.retry.mockResolvedValue({ id: 'ex-new', scenarioName: 'Parcours bancaire', virtualUsers: 5 })
    renderAt()
    expect((await screen.findByRole('link', { name: 'Suivre' })).getAttribute('href')).toBe('/executions/live/ex-run')
    expect(await screen.findByText('40 %')).toBeTruthy()
    const rows = screen.getAllByTestId('execution-row')
    expect(within(rows[1]).getByRole('button', { name: 'Voir les détails' })).toBeTruthy()

    openMenu(rows[0])
    const menu = screen.getByRole('menu')
    expect((within(menu).getByRole('menuitem', { name: /Supprimer/ }) as HTMLButtonElement).disabled).toBe(true)
    expect(within(menu).getByRole('menuitem', { name: /Annuler l’exécution/ })).toBeTruthy()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()

    openMenu(rows[1])
    expect(within(screen.getByRole('menu')).getByRole('menuitem', { name: /Supprimer/ }).className).toContain('danger')
    fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /Relancer/ }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/nouvelle exécution/)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: /Relancer/ }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions/live/ex-new'))
    expect(api.retry).toHaveBeenCalledWith('ex-1')
  })

  it('suppression simple (menu) et groupée : impact, confirmation, succès partiel expliqué', async () => {
    api.getPage.mockResolvedValue(page([row({ id: 'ex-1' }), row({ id: 'ex-2', status: 'FAILED' })], { success: 1, failed: 1 }))
    api.deletionImpact.mockResolvedValue({ executions: 2, results: 1200, metrics: 1200, active: 0 })
    api.bulkDelete.mockResolvedValue({ succeeded: 1, failed: 1, results: [
      { id: 'ex-1', name: 'Parcours bancaire (SUCCESS, …)', success: true, message: 'Supprimée.' },
      { id: 'ex-2', name: 'Parcours bancaire (FAILED, …)', success: false, message: 'L\'exécution termine l\'enregistrement de ses résultats : réessayez dans quelques secondes.' },
    ] })
    renderAt()
    const rows = await screen.findAllByTestId('execution-row')
    openMenu(rows[0])
    fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /Supprimer/ }))
    expect(await screen.findByRole('dialog')).toBeTruthy()
    await waitFor(() => expect(api.deletionImpact).toHaveBeenCalledWith(['ex-1']))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Annuler' }))

    fireEvent.click(screen.getByLabelText('Sélectionner toutes les exécutions de la page'))
    const bar = screen.getByRole('region', { name: 'Actions sur la sélection' })
    expect(within(bar).getByText('2 sélectionnées')).toBeTruthy()
    fireEvent.click(within(bar).getByRole('button', { name: /Supprimer/ }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findAllByText('1200')).toHaveLength(2)
    fireEvent.click(within(dialog).getByRole('button', { name: /Supprimer définitivement/ }))
    await waitFor(() => expect(api.bulkDelete).toHaveBeenCalledWith(['ex-1', 'ex-2']))
    expect(await screen.findByText(/1 exécution supprimée, 1 non supprimée/)).toBeTruthy()
  })

  it('relance refusée : message clair du serveur', async () => {
    const { ApiError } = await import('../services/api/httpClient')
    api.getPage.mockResolvedValue(page([row({})], { success: 1 }))
    api.retry.mockRejectedValue(new ApiError('Le scénario est inactif, activez-le pour l\'exécuter.', 400))
    renderAt()
    openMenu((await screen.findAllByTestId('execution-row'))[0])
    fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /Relancer/ }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /Relancer/ }))
    expect(await screen.findByText('Le scénario est inactif, activez-le pour l\'exécuter.')).toBeTruthy()
  })

  it('états : lignes squelettes pendant le chargement, aucun résultat avec « Réinitialiser les filtres »', async () => {
    let resolve: (v: BackendExecutionPageResponse) => void = () => {}
    api.getPage.mockReturnValueOnce(new Promise((r) => { resolve = r }))
    const { container } = renderAt('/executions?status=FAILED')
    expect(container.querySelectorAll('.ex-skeleton-row').length).toBeGreaterThan(0)
    resolve(page([], {}))
    expect(await screen.findByText('Aucune exécution ne correspond à ces filtres.')).toBeTruthy()
    api.getPage.mockResolvedValue(page([row({})], { success: 1 }))
    fireEvent.click(screen.getByRole('button', { name: 'Réinitialiser les filtres' }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions'))
  })

  it('lecteur : peut sélectionner pour comparer, mais ni menu d’actions ni « Nouvelle exécution »', async () => {
    auth.roles = ['ROLE_VIEWER']
    api.getPage.mockResolvedValue(page([row({ id: 'ex-1' }), row({ id: 'ex-2' })], { success: 2 }))
    renderAt()
    await screen.findAllByTestId('execution-row')
    fireEvent.click(screen.getByLabelText('Sélectionner toutes les exécutions de la page'))
    const bar = screen.getByRole('region', { name: 'Actions sur la sélection' })
    expect(within(bar).getByRole('button', { name: /Comparer/ })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Supprimer/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Plus d’actions/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Nouvelle exécution/ })).toBeNull()
  })

  it('« Comparer » : de 2 à 4 exécutions terminées, puis page de comparaison', async () => {
    api.getPage.mockResolvedValue(page([row({ id: 'ex-run', status: 'RUNNING', duration: null }), row({ id: 'ex-1' }), row({ id: 'ex-2', status: 'FAILED' })],
      { running: 1, success: 1, failed: 1 }))
    renderAt()
    const boxes = await screen.findAllByLabelText(/Sélectionner l’exécution de Parcours bancaire/)
    fireEvent.click(boxes[1])
    const compare = () => within(screen.getByRole('region', { name: 'Actions sur la sélection' })).getByRole('button', { name: /Comparer/ }) as HTMLButtonElement
    expect(compare().disabled).toBe(true)
    expect(compare().title).toBe('Sélectionnez au moins 2 exécutions terminées.')
    fireEvent.click(boxes[0])
    expect(compare().title).toBe('Seules les exécutions terminées peuvent être comparées.')
    fireEvent.click(boxes[0])
    fireEvent.click(boxes[2])
    expect(compare().disabled).toBe(false)
    fireEvent.click(compare())
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions/compare?ids=ex-1,ex-2'))
  })
})

describe('Exécutions : indicateurs d’un coup d’œil', () => {
  it('écart de P95 : seuil de régression (20 % par défaut, réglable), flèche et couleur', () => {
    expect(p95Delta(360, 300)).toEqual({ text: '↑ +20 %', tone: 'same', percent: 20 })
    expect(p95Delta(361, 300)).toEqual({ text: '↑ +20,3 %', tone: 'worse', percent: 20.3 })
    expect(p95Delta(230, 300)).toEqual({ text: '↓ −23,3 %', tone: 'better', percent: -23.3 })
    expect(p95Delta(331, 300).tone).toBe('same')
    // Seuil réglé dans Paramètres > Système : 10 %.
    expect(p95Delta(331, 300, 10)).toEqual({ text: '↑ +10,3 %', tone: 'worse', percent: 10.3 })
    expect(p95Delta(260, 300, 10).tone).toBe('better')
    expect(p95Delta(300, 300).tone).toBe('same')
    expect(p95Delta(300, null)).toEqual({ text: '—', tone: 'none', percent: null })
    expect(p95Delta(null, 300).tone).toBe('none')
  })

  it('couleur de la barre CPU : vert < 70 %, orange 70–90 %, rouge > 90 %', () => {
    expect(cpuLevel(69.9)).toBe('ok')
    expect(cpuLevel(70)).toBe('warn')
    expect(cpuLevel(90)).toBe('warn')
    expect(cpuLevel(90.1)).toBe('high')
    expect(cpuLevel(140)).toBe('high')
  })

  it('ligne : écart de P95 avec infobulle (P95 et date de la précédente), barre CPU colorée, raison sous le statut', async () => {
    api.getPage.mockResolvedValue(page([
      row({ id: 'ex-1', status: 'THRESHOLDS_EXCEEDED', statusReason: 'Erreurs : 2,5 %', p95Ms: 550, previousP95Ms: 400,
        previousStartedAt: '2026-10-03T09:15:00Z', cpuMaxPercent: 95 }),
      row({ id: 'ex-2', status: 'SUCCESS', statusReason: null, p95Ms: 300, previousP95Ms: 400, previousStartedAt: '2026-10-03T09:00:00Z', cpuMaxPercent: 75 }),
      row({ id: 'ex-3', status: 'FAILED', statusReason: 'Authentification échouée', p95Ms: 405, previousP95Ms: 400, previousStartedAt: '2026-10-03T08:00:00Z', cpuMaxPercent: 20 }),
      row({ id: 'ex-4', status: 'CANCELLED', statusReason: 'Annulée par admin-test', p95Ms: null, previousP95Ms: null }),
    ], { thresholdsExceeded: 1, success: 1, failed: 1, cancelled: 1 }))
    renderAt()
    const rows = await screen.findAllByTestId('execution-row')

    const worse = within(rows[0]).getByText('↑ +37,5 %')
    expect(worse.className).toContain('worse')
    expect(worse.getAttribute('title')).toMatch(/^Exécution précédente du scénario : P95 400 ms, le 03\/10\/2026 /)
    expect(within(rows[1]).getByText('↓ −25 %').className).toContain('better')
    expect(within(rows[2]).getByText('↑ +1,3 %').className).toContain('same')
    expect(within(rows[3]).getByTitle('Pas d’exécution précédente terminée pour ce scénario.').className).toContain('none')

    expect(rows[0].querySelector('.ex-cpu-bar')!.className).toContain('high')
    expect(rows[1].querySelector('.ex-cpu-bar')!.className).toContain('warn')
    expect(rows[2].querySelector('.ex-cpu-bar')!.className).toContain('ok')
    expect((rows[2].querySelector('.ex-cpu-bar') as HTMLElement).style.width).toBe('20%')

    expect(within(rows[0]).getByText('Seuils dépassés').closest('.pt-pill')!.className).toContain('warning')
    expect(within(rows[0]).getByText('Erreurs : 2,5 %').className).toContain('ex-status-reason')
    expect(within(rows[2]).getByText('Authentification échouée')).toBeTruthy()
    expect(within(rows[3]).getByText('Annulée par admin-test')).toBeTruthy()
    expect(rows[1].querySelector('.ex-status-reason')).toBeNull()
  })

  it('onglet « Seuils dépassés » : filtre serveur', async () => {
    api.getPage.mockResolvedValue(page([row({ status: 'THRESHOLDS_EXCEEDED' })], { thresholdsExceeded: 1 }))
    renderAt()
    fireEvent.click(await screen.findByRole('tab', { name: /^Seuils dépassés/ }))
    await waitFor(() => expect(api.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'THRESHOLDS_EXCEEDED' })))
    expect(screen.getByTestId('location').textContent).toBe('/executions?status=THRESHOLDS_EXCEEDED')
  })
})