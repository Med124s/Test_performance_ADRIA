import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const auth = vi.hoisted(() => ({ roles: ['ROLE_SUPER_ADMIN'] }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: auth.roles, user: { name: 'A', initials: 'A' } }) }))
const notif = vi.hoisted(() => ({ unreadCount: vi.fn(), list: vi.fn(), markRead: vi.fn(), markAllRead: vi.fn() }))
vi.mock('../services/api/notificationsBackend', () => ({ notificationsBackendApi: notif }))
// Contenu des onglets : remplacé par des repères (chaque page a ses propres tests).
vi.mock('../pages/Settings', () => ({ default: ({ embedded }: { embedded?: boolean }) => <div>PROFIL{embedded ? ' intégré' : ''}</div> }))
vi.mock('../pages/Configurations', () => ({ default: () => <div>SYSTÈME</div> }))
vi.mock('../pages/SettingsIntegrations', () => ({ default: () => <div>INTÉGRATIONS</div> }))
vi.mock('../pages/UsersRoles', () => ({ default: () => <div>LISTE UTILISATEURS</div> }))
vi.mock('../pages/RolesCatalog', () => ({ default: () => <div>CATALOGUE RÔLES</div> }))

import NotificationBell from './NotificationBell'
import Parametres from '../pages/Parametres'
import UtilisateursRoles from '../pages/UtilisateursRoles'

function Location() {
  const l = useLocation()
  return <div data-testid="location">{l.pathname}{l.search}</div>
}

const at = (url: string, element: JSX.Element) => render(
  <MemoryRouter initialEntries={[url]}>
    {element}
    <Routes><Route path="*" element={<Location />} /></Routes>
  </MemoryRouter>,
)

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_SUPER_ADMIN']
})

describe('Cloche du header', () => {
  it('compteur des non lues, panneau des dernières notifications et « Voir toutes les notifications »', async () => {
    notif.unreadCount.mockResolvedValue({ count: 2 })
    notif.markRead.mockResolvedValue({})
    notif.list.mockResolvedValue({ content: [
      { id: 'n1', type: 'EXECUTION_FAILED', title: 'Nuit : échoué · 5 % d’erreurs', message: null, relatedExecutionId: 'ex-1', relatedScheduleId: 's-1',
        read: false, createdAt: new Date().toISOString(), readAt: null },
      { id: 'n2', type: 'SCHEDULE_FAILED', title: 'Nuit : lancement manqué', message: null, relatedExecutionId: null, relatedScheduleId: 's-1',
        read: true, createdAt: new Date().toISOString(), readAt: null },
    ], page: 0, size: 5, totalElements: 2, totalPages: 1 })
    at('/', <NotificationBell />)
    const bell = await screen.findByRole('button', { name: 'Notifications : 2 non lues' })
    fireEvent.click(bell)
    const panel = screen.getByRole('dialog', { name: 'Dernières notifications' })
    expect(await within(panel).findByText('Nuit : échoué · 5 % d’erreurs')).toBeTruthy()
    expect(notif.list).toHaveBeenCalledWith({ page: 0, size: 5 })
    expect(within(panel).getByRole('link', { name: /Voir toutes les notifications/ }).getAttribute('href')).toBe('/notifications')
    // Clic : marquée lue et ouverture du rapport.
    fireEvent.click(within(panel).getByText('Nuit : échoué · 5 % d’erreurs'))
    expect(notif.markRead).toHaveBeenCalledWith('n1')
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/executions/report/ex-1'))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('« Tout marquer comme lu », fermeture par Échap', async () => {
    notif.unreadCount.mockResolvedValue({ count: 1 })
    notif.markAllRead.mockResolvedValue({ updated: 1 })
    notif.list.mockResolvedValue({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 })
    at('/', <NotificationBell />)
    fireEvent.click(await screen.findByRole('button', { name: /Notifications : 1 non lue/ }))
    expect(await screen.findByText('Aucune notification.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Tout marquer comme lu' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Notifications' })).toBeTruthy())
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('Pages fusionnées à onglets', () => {
  it('Paramètres : Mon profil / Système / Intégrations pour le Super Administrateur, onglet dans l’URL, clavier', () => {
    at('/parametres?tab=systeme', <Parametres />)
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((t) => t.textContent)).toEqual(['Mon profil', 'Système', 'Intégrations'])
    expect(screen.getByRole('tab', { name: 'Système' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('tabpanel').textContent).toBe('SYSTÈME')
    fireEvent.keyDown(screen.getByRole('tablist'), { key: 'ArrowRight' })
    expect(screen.getByRole('tabpanel').textContent).toBe('INTÉGRATIONS')
    expect(screen.getByTestId('location').textContent).toBe('/parametres?tab=integrations')
    fireEvent.click(screen.getByRole('tab', { name: 'Mon profil' }))
    expect(screen.getByRole('tabpanel').textContent).toBe('PROFIL intégré')
  })

  it('Paramètres pour un autre rôle : seulement « Mon profil » (un onglet réservé retombe sur le profil)', () => {
    auth.roles = ['ROLE_VIEWER']
    at('/parametres?tab=systeme', <Parametres />)
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Mon profil'])
    expect(screen.getByRole('tabpanel').textContent).toBe('PROFIL intégré')
  })

  it('Utilisateurs et rôles : onglets Utilisateurs / Rôles', () => {
    at('/utilisateurs?tab=roles', <UtilisateursRoles />)
    expect(screen.getByRole('heading', { name: 'Utilisateurs et rôles' })).toBeTruthy()
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Utilisateurs', 'Rôles'])
    expect(screen.getByRole('tabpanel').textContent).toBe('CATALOGUE RÔLES')
    fireEvent.click(screen.getByRole('tab', { name: 'Utilisateurs' }))
    expect(screen.getByRole('tabpanel').textContent).toBe('LISTE UTILISATEURS')
  })
})
