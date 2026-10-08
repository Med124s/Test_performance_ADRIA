import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../context/ToastContext'
import { BackendScheduleHistoryResponse, BackendScheduledExecutionResponse } from '../types/backendContracts'

const auth = vi.hoisted(() => ({ roles: ['ROLE_PERFORMANCE_ENGINEER'] }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: auth.roles }) }))
const api = vi.hoisted(() => ({
  list: vi.fn(), getById: vi.fn(), create: vi.fn(), update: vi.fn(), enable: vi.fn(), disable: vi.fn(),
  runNow: vi.fn(), delete: vi.fn(), preview: vi.fn(), history: vi.fn(),
}))
vi.mock('../services/api/scheduledExecutionsBackend', () => ({ scheduledExecutionsBackendApi: api }))
vi.mock('../services/api/scenariosBackend', () => ({ scenariosBackendApi: { getAll: () => Promise.resolve([
  { id: 'sc-1', name: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking', status: 'ACTIVE', archived: false,
    applicationArchived: false, virtualUsers: 20, rampUpSeconds: 30, durationSeconds: 90, iterations: null },
  { id: 'sc-2', name: 'Connexion', applicationId: 'app-1', applicationName: 'Banking', status: 'INACTIVE', archived: false,
    applicationArchived: false, virtualUsers: 1, rampUpSeconds: 0, durationSeconds: null, iterations: 1 },
]) } }))
vi.mock('react-chartjs-2', () => ({ Line: ({ data }: { data: { labels: string[] } }) => <div data-testid="chart">{data.labels.length} points</div> }))

import ScheduledExecutions from './ScheduledExecutions'
import ScheduleHistory from './scheduling/ScheduleHistory'

const HOUR = 3600_000
const sched = (patch: Partial<BackendScheduledExecutionResponse>): BackendScheduledExecutionResponse => ({
  id: 's-1', scenarioId: 'sc-1', scenarioName: 'Parcours bancaire', applicationId: 'app-1', applicationName: 'Banking',
  name: 'Test nocturne Banking', scheduleType: 'RECURRING_CRON', cronExpression: '0 0 2 * * *', runAt: null, timezone: 'Africa/Casablanca',
  nextRunAt: new Date(Date.now() + 5 * HOUR).toISOString(), enabled: true, createdByUsername: 'admin-test',
  createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', lastTriggeredAt: null, lastExecutionId: null,
  lastTriggerError: null, endAt: null, notifyMode: 'ALWAYS', state: 'ACTIVE', stateReason: null, lastRun: null, lastEvent: null,
  ...patch,
})

function Location() {
  const l = useLocation()
  return <div data-testid="location">{l.pathname}{l.search}</div>
}

const renderAt = (url = '/planification') => render(
  <MemoryRouter initialEntries={[url]}>
    <ToastProvider>
      <Routes>
        <Route path="/planification" element={<><ScheduledExecutions /><Location /></>} />
        <Route path="/planification/:id" element={<><ScheduleHistory /><Location /></>} />
        <Route path="/executions/report/:id" element={<Location />} />
        <Route path="/scenarios/:id/edit" element={<Location />} />
      </Routes>
    </ToastProvider>
  </MemoryRouter>,
)

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
  api.preview.mockImplementation(() => Promise.resolve({
    nextRuns: ['2026-10-08T01:00:00Z', '2026-10-09T01:00:00Z', '2026-10-10T01:00:00Z'], cronExpression: '0 0 2 * * *',
  }))
})

describe('Planification — liste', () => {
  it('état vide : message et « Créer une planification » au centre ; sous-titre de la page', async () => {
    api.list.mockResolvedValue([])
    renderAt()
    expect(await screen.findByText('Aucune planification')).toBeTruthy()
    expect(screen.getByText('Lancez vos scénarios automatiquement, par exemple chaque nuit.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Créer une planification/ }))
    expect(screen.getByRole('dialog', { name: 'Nouvelle planification' })).toBeTruthy()
  })

  it('colonnes : fréquence en français, prochaine exécution relative, dernière exécution avec P95 et écart, états avec raison', async () => {
    api.list.mockResolvedValue([
      sched({ lastRun: { executionId: 'ex-9', status: 'SUCCESS', startedAt: new Date(Date.now() - 20 * HOUR).toISOString(), p95Ms: 424,
        previousP95Ms: 314, p95ChangePercent: 35, errorRatePercent: 0.2 } }),
      sched({ id: 's-2', name: 'Contrôle midi', scheduleType: 'ONE_TIME', cronExpression: null, runAt: '2026-10-07T11:00:00Z',
        nextRunAt: null, state: 'COMPLETED', stateReason: 'Planification unique : son lancement a eu lieu.' }),
      sched({ id: 's-3', name: 'Connexion nuit', scenarioId: 'sc-2', scenarioName: 'Connexion', enabled: false, state: 'SUSPENDED',
        stateReason: 'Scénario désactivé : la planification reprendra quand il sera réactivé.',
        lastEvent: { outcome: 'SKIPPED_RUNNING', plannedAt: new Date(Date.now() - 2 * HOUR).toISOString(), reason: 'Ignorée : exécution précédente encore en cours.' } }),
    ])
    renderAt()
    const rows = await screen.findAllByTestId('schedule-row')
    const first = within(rows[0])
    expect(first.getByText('Tous les jours à 02:00')).toBeTruthy()
    expect(first.getByText('dans 5 heures').getAttribute('title')).toMatch(/2026 à \d\d:\d\d \(Africa\/Casablanca\)$/)
    expect(first.getByText('P95 424 ms')).toBeTruthy()
    expect(first.getByText('↑ +35 %').className).toContain('worse')
    expect(first.getByRole('link', { name: 'Réussie' }).getAttribute('href')).toBe('/executions/report/ex-9')

    expect(within(rows[1]).getByText(/^Une fois, le 07\/10\/2026 à 12:00$/)).toBeTruthy()
    expect(within(rows[1]).getByText('Terminée')).toBeTruthy()
    expect(within(rows[2]).getByText('Suspendue').getAttribute('title')).toMatch(/^Scénario désactivé/)
    expect(within(rows[2]).getByText(/Ignorée · il y a 2 heures/).getAttribute('title')).toMatch(/^Ignorée : exécution précédente/)
    expect((within(rows[2]).getByRole('button', { name: 'Lancer maintenant Connexion nuit' }) as HTMLButtonElement).disabled).toBe(true)

    // Onglets d'état avec leur nombre, filtre dans l'URL.
    expect(screen.getByRole('tab', { name: /^Toutes\s*3$/ }).getAttribute('aria-selected')).toBe('true')
    fireEvent.click(screen.getByRole('tab', { name: /^Suspendue\s*1$/ }))
    expect(screen.getByTestId('location').textContent).toBe('/planification?state=SUSPENDED')
    expect(screen.getAllByTestId('schedule-row')).toHaveLength(1)
    expect(screen.getByRole('tab', { name: /^Expirée\s*0$/ }).className).toContain('empty')
  })

  it('actions : lancer maintenant, pause / reprise, menu Modifier · Historique · Supprimer avec confirmation', async () => {
    const s = sched({})
    api.list.mockResolvedValue([s])
    api.runNow.mockResolvedValue({ ...s, lastTriggeredAt: new Date().toISOString() })
    api.disable.mockResolvedValue({ ...s, enabled: false, state: 'PAUSED' })
    api.delete.mockResolvedValue(undefined)
    renderAt()
    await screen.findAllByTestId('schedule-row')

    fireEvent.click(screen.getByRole('button', { name: 'Lancer maintenant Test nocturne Banking' }))
    await waitFor(() => expect(api.runNow).toHaveBeenCalledWith('s-1'))
    expect(await screen.findByText(/exécution lancée/)).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Mettre en pause Test nocturne Banking' }))
    await waitFor(() => expect(api.disable).toHaveBeenCalledWith('s-1'))
    expect(await screen.findByText('En pause', { selector: '.pl-state' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Reprendre Test nocturne Banking' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Plus d’actions pour Test nocturne Banking' }))
    const menu = screen.getByRole('menu')
    expect(within(menu).getAllByRole('menuitem').map((m) => m.textContent?.trim())).toEqual(['Modifier', 'Historique', 'Supprimer'])
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Supprimer/ }))
    const dialog = screen.getByRole('dialog', { name: /Supprimer « Test nocturne Banking » \?/ })
    expect(within(dialog).getByText(/exécutions déjà lancées restent consultables/)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Supprimer' }))
    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('s-1'))
    expect(await screen.findByText('Aucune planification')).toBeTruthy()
  })

  it('VIEWER : consultation seulement (ni création, ni lancement, ni pause ; menu réduit à Historique)', async () => {
    auth.roles = ['ROLE_VIEWER']
    api.list.mockResolvedValue([sched({})])
    renderAt()
    await screen.findAllByTestId('schedule-row')
    expect(screen.queryByRole('button', { name: /Nouvelle planification/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Lancer maintenant/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Mettre en pause/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Plus d’actions/ }))
    expect(within(screen.getByRole('menu')).getAllByRole('menuitem').map((m) => m.textContent?.trim())).toEqual(['Historique'])
    // Clic sur la ligne : historique.
    fireEvent.click(screen.getByText('Tous les jours à 02:00'))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/planification/s-1'))
  })
})

describe('Planification — formulaire', () => {
  it('récurrente sans CRON : fréquence, heure, aperçu des 3 prochaines exécutions, charge du scénario en lecture seule', async () => {
    api.list.mockResolvedValue([sched({})])
    api.create.mockImplementation((p) => Promise.resolve(sched({ id: 's-new', name: p.name })))
    renderAt()
    await screen.findAllByTestId('schedule-row')
    fireEvent.click(screen.getByRole('button', { name: /Nouvelle planification/ }))
    const dialog = screen.getByRole('dialog', { name: 'Nouvelle planification' })

    // Validation : nom et scénario obligatoires.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Créer la planification' }))
    expect(within(dialog).getByText(/Donnez un nom à la planification/)).toBeTruthy()
    expect(within(dialog).getByText('Choisissez le scénario à lancer.')).toBeTruthy()
    expect(api.create).not.toHaveBeenCalled()

    fireEvent.change(within(dialog).getByLabelText('Nom'), { target: { value: 'Test nocturne Banking' } })
    const combo = within(dialog).getByRole('combobox', { name: 'Scénario à lancer' })
    fireEvent.focus(combo)
    // Seuls les scénarios actifs sont proposés.
    expect(within(dialog).queryByRole('option', { name: /Connexion/ })).toBeNull()
    fireEvent.mouseDown(within(dialog).getByRole('option', { name: /Parcours bancaire/ }))
    const load = within(dialog).getByLabelText('Charge du scénario')
    expect(load.textContent).toMatch(/20\s*VU/)
    expect(within(load).getByRole('link', { name: 'Voir le scénario' }).getAttribute('href')).toBe('/scenarios/sc-1/edit')

    fireEvent.change(within(dialog).getByLabelText('Fréquence'), { target: { value: 'WEEKDAYS' } })
    fireEvent.change(within(dialog).getByLabelText('Heure'), { target: { value: '06:30' } })
    await waitFor(() => expect(api.preview).toHaveBeenLastCalledWith(expect.objectContaining({
      scheduleType: 'RECURRING_CRON', cronExpression: '0 30 6 * * MON-FRI',
    })))
    const preview = within(dialog).getByRole('region', { name: 'Prochaines exécutions' })
    expect(within(preview).getAllByRole('listitem')).toHaveLength(3)

    fireEvent.click(within(dialog).getByLabelText('Seulement en cas d’échec ou de régression'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Créer la planification' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Test nocturne Banking', scenarioId: 'sc-1', scheduleType: 'RECURRING_CRON', cronExpression: '0 30 6 * * MON-FRI',
      notifyMode: 'PROBLEMS', runAt: null,
    })))
    expect(await screen.findByText('Planification « Test nocturne Banking » créée.')).toBeTruthy()
  })

  it('CRON personnalisé traduit en français ; expression invalide refusée ; une seule fois : date passée refusée', async () => {
    api.list.mockResolvedValue([sched({})])
    renderAt()
    await screen.findAllByTestId('schedule-row')
    fireEvent.click(screen.getByRole('button', { name: /Nouvelle planification/ }))
    const dialog = screen.getByRole('dialog', { name: 'Nouvelle planification' })

    fireEvent.change(within(dialog).getByLabelText('Fréquence'), { target: { value: 'CUSTOM' } })
    const cron = within(dialog).getByLabelText('Expression CRON') as HTMLInputElement
    expect(cron.value).toBe('0 2 * * *')
    expect(within(dialog).getByText('Tous les jours à 02:00')).toBeTruthy()
    fireEvent.change(cron, { target: { value: '*/5 * * * *' } })
    expect(within(dialog).getByText('Toutes les 5 minutes')).toBeTruthy()
    fireEvent.change(cron, { target: { value: 'toutes les nuits' } })
    expect(within(dialog).getByText(/Expression CRON invalide/)).toBeTruthy()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Une seule fois' }))
    const when = within(dialog).getByLabelText('Date et heure') as HTMLInputElement
    fireEvent.change(when, { target: { value: '010120200800' } })
    expect(when.value).toBe('01/01/2020 08:00')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Créer la planification' }))
    expect(within(dialog).getByText('Cette date est déjà passée : choisissez un moment dans le futur.')).toBeTruthy()
  })

  it('modification : la fréquence enregistrée est retrouvée (jours ouvrés à 06:30)', async () => {
    const s = sched({ cronExpression: '0 30 6 * * MON-FRI', notifyMode: 'PROBLEMS' })
    api.list.mockResolvedValue([s])
    api.update.mockResolvedValue(s)
    renderAt()
    await screen.findAllByTestId('schedule-row')
    fireEvent.click(screen.getByRole('button', { name: 'Plus d’actions pour Test nocturne Banking' }))
    fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /Modifier/ }))
    const dialog = screen.getByRole('dialog', { name: 'Modifier la planification' })
    expect((within(dialog).getByLabelText('Fréquence') as HTMLSelectElement).value).toBe('WEEKDAYS')
    expect((within(dialog).getByLabelText('Heure') as HTMLInputElement).value).toBe('06:30')
    expect((within(dialog).getByLabelText('Fuseau horaire') as HTMLSelectElement).value).toBe('Africa/Casablanca')
    expect((within(dialog).getByLabelText('Seulement en cas d’échec ou de régression') as HTMLInputElement).checked).toBe(true)
    await waitFor(() => expect(api.preview).toHaveBeenCalled())
    fireEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(api.update).toHaveBeenCalledWith('s-1', expect.objectContaining({ cronExpression: '0 30 6 * * MON-FRI' })))
  })
})

describe('Planification — historique', () => {
  it('exécutions (P95, erreurs, débit, rapport), lancements ignorés / manqués avec leur raison, graphique', async () => {
    api.getById.mockResolvedValue(sched({}))
    const history: BackendScheduleHistoryResponse = { scheduleId: 's-1', truncated: false, items: [
      { kind: 'EXECUTION', at: '2026-10-07T01:00:00Z', executionId: 'ex-3', status: 'SUCCESS', p95Ms: 424, errorRatePercent: 0,
        throughputRps: 86.3, totalRequests: 8109, durationMs: 94_000, outcome: null, reason: null },
      { kind: 'EVENT', at: '2026-10-06T01:00:00Z', executionId: null, status: null, p95Ms: null, errorRatePercent: null,
        throughputRps: null, totalRequests: null, durationMs: null, outcome: 'MISSED', reason: 'Manquée : LoadPilot n’était pas démarré à l’heure prévue.' },
      { kind: 'EXECUTION', at: '2026-10-05T01:00:00Z', executionId: 'ex-1', status: 'FAILED', p95Ms: 314, errorRatePercent: 5,
        throughputRps: 80, totalRequests: 7000, durationMs: 93_000, outcome: null, reason: null },
    ] }
    api.history.mockResolvedValue(history)
    renderAt('/planification/s-1')
    expect(await screen.findByRole('heading', { name: 'Historique — Test nocturne Banking' })).toBeTruthy()
    const rows = await screen.findAllByTestId('history-row')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('424 ms')).toBeTruthy()
    expect(within(rows[0]).getByText('86,3 req/s')).toBeTruthy()
    expect(within(rows[0]).getByRole('link', { name: 'Rapport' }).getAttribute('href')).toBe('/executions/report/ex-3')
    expect(within(rows[1]).getByText('Manquée')).toBeTruthy()
    expect(within(rows[1]).getByText(/LoadPilot n’était pas démarré/)).toBeTruthy()
    expect(within(rows[2]).getByText('5 %').className).toContain('bad')
    expect(screen.getByTestId('chart').textContent).toBe('2 points')
    expect(screen.getByText(/2 exécutions · 1 ignorée ou manquée/)).toBeTruthy()
  })
})
