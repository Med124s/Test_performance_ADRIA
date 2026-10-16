import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendNotificationResponse } from '../types/backendContracts'

// Notifications : pagination côté serveur (filtres combinés, état dans l'URL), compteur de la
// cloche mis à jour tout de suite (marquer lu, tout marquer, ouvrir), synchronisé entre onglets,
// et animation de la cloche une fois par nouvelle notification.

const api = vi.hoisted(() => ({ list: vi.fn(), unreadCount: vi.fn(), markRead: vi.fn(), markAllRead: vi.fn(), delete: vi.fn() }))
vi.mock('../services/api/notificationsBackend', () => ({ notificationsBackendApi: api }))
vi.mock('../components/TopBar', () => ({ default: () => null }))

import NotificationsPage from './NotificationsPage'
import NotificationBell from '../components/NotificationBell'
import { refreshUnread, resetUnreadForTests } from '../services/notifications/unreadNotifications'

const TYPES = ['EXECUTION_SUCCESS', 'EXECUTION_FAILED', 'SCHEDULE_REGRESSION', 'SCHEDULE_TRIGGERED', 'EXECUTION_THRESHOLDS_EXCEEDED'] as const
/** 45 notifications, la plus récente d'abord ; les 3 premières non lues. */
const ALL: BackendNotificationResponse[] = Array.from({ length: 45 }, (_, i) => ({
  id: `n${i + 1}`, type: TYPES[i % TYPES.length], title: `Notification ${i + 1}`, message: null,
  relatedExecutionId: i === 0 ? 'ex-1' : null, relatedScheduleId: null,
  read: i >= 3, createdAt: new Date(Date.now() - i * 60_000).toISOString(), readAt: null,
}) as BackendNotificationResponse)

let unread = 3
beforeEach(() => {
  vi.clearAllMocks()
  resetUnreadForTests()
  unread = 3
  api.unreadCount.mockImplementation(async () => ({ count: unread }))
  api.markRead.mockImplementation(async (id: string) => ({ ...ALL.find((n) => n.id === id)!, read: true }))
  api.markAllRead.mockResolvedValue({ updated: 3 })
  api.list.mockImplementation(async ({ read, types, page = 0, size = 20 }: { read?: boolean; types?: string[]; page?: number; size?: number }) => {
    const rows = ALL.filter((n) => (read == null || n.read === read) && (!types || types.includes(n.type)))
    return { content: rows.slice(page * size, page * size + size), page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size) }
  })
})

function Location() {
  const l = useLocation()
  return <div data-testid="location">{l.pathname}{l.search}</div>
}
const renderPage = (url = '/notifications') => render(
  <MemoryRouter initialEntries={[url]}>
    <NotificationBell />
    <Routes>
      <Route path="/notifications" element={<NotificationsPage />} />
      <Route path="*" element={<p>autre page</p>} />
    </Routes>
    <Location />
  </MemoryRouter>,
)
const bell = () => screen.getByTitle('Notifications')
const location = () => screen.getByTestId('location').textContent

describe('Page Notifications : pagination côté serveur', () => {
  it('20 par page, Précédent / Suivant / numéros, « Affichage de X à Y sur Z notifications », taille 20 / 50 / 100', async () => {
    renderPage()
    expect(await screen.findByText('Affichage de 1 à 20 sur 45 notifications')).toBeTruthy()
    expect(api.list).toHaveBeenLastCalledWith({ read: undefined, types: undefined, page: 0, size: 20 })
    expect(screen.getAllByRole('listitem')).toHaveLength(20)

    fireEvent.click(screen.getByRole('button', { name: '2' }))
    expect(await screen.findByText('Affichage de 21 à 40 sur 45 notifications')).toBeTruthy()
    expect(location()).toBe('/notifications?page=2')
    fireEvent.click(screen.getByTitle('Suivant'))
    expect(await screen.findByText('Affichage de 41 à 45 sur 45 notifications')).toBeTruthy()
    expect(screen.getByTitle('Suivant')).toBeDisabled()
    fireEvent.click(screen.getByTitle('Précédent'))
    expect(await screen.findByText('Affichage de 21 à 40 sur 45 notifications')).toBeTruthy()

    const sizeSelect = screen.getByLabelText('Nombre d\'éléments par page') as HTMLSelectElement
    expect([...sizeSelect.options].map((o) => o.value)).toEqual(['20', '50', '100'])
    fireEvent.change(sizeSelect, { target: { value: '50' } })
    expect(await screen.findByText('Affichage de 1 à 45 sur 45 notifications')).toBeTruthy()
    expect(location()).toBe('/notifications?taille=50')
  })

  it('filtres lues / non lues et type combinés à la pagination, gardés dans l’URL (et relus au chargement)', async () => {
    renderPage()
    await screen.findByText('Affichage de 1 à 20 sur 45 notifications')
    fireEvent.click(screen.getByRole('button', { name: '2' }))
    await screen.findByText('Affichage de 21 à 40 sur 45 notifications')

    // Changer de type revient en page 1.
    fireEvent.change(screen.getByLabelText('Type de notification'), { target: { value: 'echecs' } })
    expect(await screen.findByText('Affichage de 1 à 9 sur 9 notifications')).toBeTruthy()
    expect(api.list).toHaveBeenLastCalledWith({ read: undefined, types: ['EXECUTION_FAILED', 'SCHEDULE_FAILED'], page: 0, size: 20 })
    expect(location()).toBe('/notifications?type=echecs')

    fireEvent.click(screen.getByRole('button', { name: 'Lues' }))
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ read: true, types: ['EXECUTION_FAILED', 'SCHEDULE_FAILED'], page: 0, size: 20 }))
    expect(location()).toBe('/notifications?lu=lues&type=echecs')
    fireEvent.click(screen.getByRole('button', { name: 'Non lues' }))
    expect(await screen.findByText('Affichage de 1 à 1 sur 1 notification')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Non lues' }).getAttribute('aria-pressed')).toBe('true')
  })

  it('état relu depuis l’URL ; page au-delà de la dernière : dernière page', async () => {
    const first = renderPage('/notifications?lu=lues&type=regressions&taille=50&page=1')
    await waitFor(() => expect(api.list).toHaveBeenCalledWith({ read: true, types: ['SCHEDULE_REGRESSION'], page: 0, size: 50 }))
    expect((screen.getByLabelText('Type de notification') as HTMLSelectElement).value).toBe('regressions')
    first.unmount()
    renderPage('/notifications?page=9')
    expect(await screen.findByText('Affichage de 41 à 45 sur 45 notifications')).toBeTruthy()
    expect(location()).toBe('/notifications?page=3')
  })
})

describe('Compteur de la cloche', () => {
  it('« Marquer comme lu » : le compteur baisse tout de suite, sans attendre le serveur', async () => {
    let finish: (v: unknown) => void = () => {}
    api.markRead.mockImplementationOnce(() => new Promise((r) => { finish = r }))
    renderPage()
    await waitFor(() => expect(bell().getAttribute('aria-label')).toBe('Notifications : 3 non lues'))
    fireEvent.click(await screen.findByRole('button', { name: 'Marquer comme lue : Notification 2' }))
    // Le serveur n'a pas encore répondu : la cloche a déjà baissé.
    expect(bell().getAttribute('aria-label')).toBe('Notifications : 2 non lues')
    expect(api.markRead).toHaveBeenCalledWith('n2')
    await act(async () => { finish({ ...ALL[1], read: true }) })
    expect(screen.queryByRole('button', { name: 'Marquer comme lue : Notification 2' })).toBeNull()
  })

  it('« Tout marquer comme lu » : compteur à 0, badge retiré tout de suite', async () => {
    renderPage()
    await waitFor(() => expect(bell().querySelector('.badge-dot')?.textContent).toBe('3'))
    fireEvent.click(screen.getByRole('button', { name: /Tout marquer comme lu/ }))
    expect(bell().getAttribute('aria-label')).toBe('Notifications')
    expect(bell().querySelector('.badge-dot')).toBeNull()
    expect(api.markAllRead).toHaveBeenCalledTimes(1)
  })

  it('ouvrir une notification non lue (page ou cloche) la marque lue et baisse le compteur', async () => {
    renderPage()
    await waitFor(() => expect(bell().getAttribute('aria-label')).toBe('Notifications : 3 non lues'))
    // Page : clic sur la notification (ouvre le rapport de l'exécution).
    fireEvent.click(await screen.findByText('Notification 1'))
    expect(api.markRead).toHaveBeenCalledWith('n1')
    expect(bell().getAttribute('aria-label')).toBe('Notifications : 2 non lues')
    await waitFor(() => expect(location()).toBe('/executions/report/ex-1'))

    // Cloche : clic sur une non lue du panneau.
    fireEvent.click(bell())
    const panel = screen.getByRole('dialog', { name: 'Dernières notifications' })
    fireEvent.click(await within(panel).findByText('Notification 3'))
    expect(api.markRead).toHaveBeenCalledWith('n3')
    expect(bell().getAttribute('aria-label')).toBe('Notifications : 1 non lue')
  })

  it('plusieurs onglets : un changement fait ailleurs met ce compteur à jour, et l’inverse', async () => {
    renderPage()
    await waitFor(() => expect(bell().getAttribute('aria-label')).toBe('Notifications : 3 non lues'))
    const otherTab = new BroadcastChannel('loadpilot-notifications')
    const received: unknown[] = []
    otherTab.onmessage = (e) => received.push(e.data)
    try {
      // Ici → autre onglet.
      fireEvent.click(await screen.findByRole('button', { name: 'Marquer comme lue : Notification 2' }))
      await waitFor(() => expect(received).toEqual([expect.objectContaining({ type: 'unread', count: 2 })]))
      // Autre onglet → ici (« Tout marquer comme lu » là-bas) ; la liste est relue.
      const calls = api.list.mock.calls.length
      otherTab.postMessage({ type: 'unread', count: 0, at: Date.now() })
      await waitFor(() => expect(bell().getAttribute('aria-label')).toBe('Notifications'))
      await waitFor(() => expect(api.list.mock.calls.length).toBeGreaterThan(calls))
    } finally {
      otherTab.close()
    }
  })
})

describe('Nouvelle notification en temps réel', () => {
  it('la cloche bouge et le badge grossit une seule fois par arrivée ; jamais au chargement ni quand le nombre baisse', async () => {
    renderPage()
    await waitFor(() => expect(bell().querySelector('.badge-dot')?.textContent).toBe('3'))
    // Premier chargement : pas d'animation.
    expect(bell().querySelector('.nb-bell')?.classList.contains('ring')).toBe(false)
    expect(bell().querySelector('.badge-dot')?.classList.contains('pop')).toBe(false)

    // Une notification arrive (fin d'exécution, échec, régression ou planification : même compteur).
    unread = 4
    const listCalls = api.list.mock.calls.length
    await act(async () => { await refreshUnread() })
    const icon = bell().querySelector('.nb-bell')!
    const badge = bell().querySelector('.badge-dot')!
    expect(icon.classList.contains('ring')).toBe(true)
    expect(badge.classList.contains('pop')).toBe(true)
    expect(badge.textContent).toBe('4')
    expect(screen.getByRole('status').textContent).toBe('Nouvelle notification : 4 non lues')
    // La liste de la page est relue (la nouvelle notification apparaît sans recharger).
    await waitFor(() => expect(api.list.mock.calls.length).toBeGreaterThan(listCalls))

    // Même nombre au passage suivant : rien ne se rejoue (mêmes éléments, animation déjà jouée).
    await act(async () => { await refreshUnread() })
    expect(bell().querySelector('.nb-bell')).toBe(icon)
    expect(bell().querySelector('.badge-dot')).toBe(badge)
    // Le nombre baisse (lu) : pas de nouvelle animation.
    fireEvent.click(await screen.findByRole('button', { name: 'Marquer comme lue : Notification 2' }))
    expect(bell().querySelector('.nb-bell')).toBe(icon)
    expect(bell().querySelector('.badge-dot')?.textContent).toBe('3')

    // Deuxième arrivée : l'animation se joue de nouveau, une fois.
    unread = 5
    await act(async () => { await refreshUnread() })
    expect(bell().querySelector('.nb-bell')).not.toBe(icon)
    expect(bell().querySelector('.nb-bell')?.classList.contains('ring')).toBe(true)
  })

  it('réponse du serveur partie avant un « marquer lu » : ignorée (pas de remontée ni de fausse animation)', async () => {
    renderPage()
    await waitFor(() => expect(bell().getAttribute('aria-label')).toBe('Notifications : 3 non lues'))
    let answer: (v: { count: number }) => void = () => {}
    api.unreadCount.mockImplementationOnce(() => new Promise((r) => { answer = r }))
    const pending = refreshUnread()
    fireEvent.click(await screen.findByRole('button', { name: 'Marquer comme lue : Notification 2' }))
    await act(async () => { answer({ count: 3 }); await pending })
    expect(bell().getAttribute('aria-label')).toBe('Notifications : 2 non lues')
    expect(bell().querySelector('.nb-bell')?.classList.contains('ring')).toBe(false)
  })
})
