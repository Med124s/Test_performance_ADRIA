import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../../context/ToastContext'
import { ApiError } from '../../services/api/httpClient'

// « Mon compte » (carte utilisateur de la barre du haut, seul accès au compte) : contenu par rôle, lecture seule pour l'Ingénieur performance et l'Observateur,
// « Modifier » (prénom, nom, e-mail) pour le Super administrateur, clavier et fermeture.

type Role = 'SUPER_ADMIN' | 'PERFORMANCE_ENGINEER' | 'VIEWER'
const PEOPLE: Record<Role, { username: string; first: string; last: string; label: string }> = {
  SUPER_ADMIN: { username: 'admin-test', first: 'Admin', last: 'Test', label: 'Super administrateur' },
  PERFORMANCE_ENGINEER: { username: 'engineer-test', first: 'Engineer', last: 'Test', label: 'Ingénieur performance' },
  VIEWER: { username: 'viewer-test', first: 'Viewer', last: 'Test', label: 'Observateur' },
}
const profileOf = (role: Role) => {
  const p = PEOPLE[role]
  return { id: `sub-${role}`, username: p.username, name: `${p.first} ${p.last}`, email: `${p.username}@loadpilot.local`, roles: [`ROLE_${role}`],
    timezone: 'Africa/Casablanca', appRole: role, appRoleLabel: p.label, theme: null, firstName: p.first, lastName: p.last,
    canEditIdentity: role === 'SUPER_ADMIN' }
}

const state = vi.hoisted(() => ({ role: 'SUPER_ADMIN' as string, logout: vi.fn(), refreshUser: vi.fn(async () => {}) }))
vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({
  authProvider: 'keycloak', rawRoles: [`ROLE_${state.role}`], logout: state.logout, refreshUser: state.refreshUser,
  user: { name: 'Nom du jeton', initials: 'NT', email: 'jeton@loadpilot.local', role: 'Admin' },
}) }))
const api = vi.hoisted(() => ({ get: vi.fn(), security: vi.fn(), updateIdentity: vi.fn(), updateTimezone: vi.fn(), passwordChanged: vi.fn() }))
vi.mock('../../services/api/profileBackend', () => ({ profileBackendApi: api }))
const kc = vi.hoisted(() => ({ redirectToAccountAction: vi.fn().mockResolvedValue(undefined), consumeAccountActionResult: vi.fn(() => null) }))
vi.mock('../../services/auth/keycloakClient', () => kc)
vi.mock('../../pages/settings/AppearanceCard', () => ({ default: () => <div>Apparence</div> }))
vi.mock('../../pages/settings/NotificationsCard', () => ({ default: () => <div>Notifications</div> }))
vi.mock('../../pages/settings/SecurityCard', () => ({ default: () => <div>Sécurité</div> }))

vi.mock('../NotificationBell', () => ({ default: () => <button type="button">Notifications</button> }))

import TopBarIcons from '../TopBarIcons'
import Settings from '../../pages/Settings'

function Location() {
  const l = useLocation()
  return <div data-testid="location">{l.pathname}{l.search}</div>
}
const renderTopBar = () => render(
  <MemoryRouter initialEntries={['/scenarios']}>
    <ToastProvider>
      <p>Ailleurs sur la page</p>
      <TopBarIcons onOpenMobileMenu={() => {}} />
      <Routes><Route path="*" element={<Location />} /></Routes>
    </ToastProvider>
  </MemoryRouter>,
)
const openPanel = async () => {
  fireEvent.click(screen.getByRole('button', { name: /^Mon compte : / }))
  const panel = screen.getByRole('dialog', { name: 'Mon compte' })
  await within(panel).findByText(/@\w/)
  return panel
}
const lastLogin = new Date(Date.now() - 2 * 3600_000).toISOString()

beforeEach(() => {
  vi.clearAllMocks()
  state.role = 'SUPER_ADMIN'
  api.get.mockImplementation(async () => profileOf(state.role as Role))
  api.security.mockResolvedValue({ lastLoginAt: lastLogin, lastLoginIp: '10.0.0.1', sessions: [], devicesKnown: false, message: null })
})

describe('Panneau « Mon compte »', () => {
  for (const role of ['SUPER_ADMIN', 'PERFORMANCE_ENGINEER', 'VIEWER'] as Role[]) {
    it(`${PEOPLE[role].label} : identité, rôle en français, statut, dernière connexion, fuseau, liens`, async () => {
      state.role = role
      renderTopBar()
      expect(screen.getByRole('button', { name: /^Mon compte : / }).textContent).toContain(PEOPLE[role].label)
      const panel = await openPanel()
      const text = panel.textContent ?? ''
      expect(text).toContain(`${PEOPLE[role].first} ${PEOPLE[role].last}`)
      expect(text).toContain(`@${PEOPLE[role].username}`)
      expect(text).toContain(`${PEOPLE[role].username}@loadpilot.local`)
      expect(within(panel).getByText(PEOPLE[role].label)).toBeTruthy()
      expect(text).toMatch(/Statut\s*Actif/)
      await waitFor(() => expect(panel.textContent).toMatch(/Dernière connexion\s*il y a 2 heures/))
      expect(text).toContain('Africa/Casablanca')
      expect(within(within(panel).getByRole('group', { name: 'Actions du compte' })).getAllByRole('button').map((b) => b.textContent?.trim()))
        .toEqual(['Mon profil', 'Changer mon mot de passe', 'Déconnexion'])
      // Lecture seule pour l'Ingénieur performance et l'Observateur : aucun champ, aucun « Modifier ».
      expect(within(panel).queryByRole('textbox')).toBeNull()
      if (role === 'SUPER_ADMIN') {
        expect(within(panel).getByRole('button', { name: /Modifier/ })).toBeTruthy()
      } else {
        expect(within(panel).queryByRole('button', { name: /Modifier/ })).toBeNull()
        expect(text).not.toContain('modifiables par un administrateur')
      }
    })
  }

  it('« Changer mon mot de passe » ouvre la page du système d’authentification, retour sur Mon profil', async () => {
    renderTopBar()
    const panel = await openPanel()
    fireEvent.click(within(panel).getByRole('button', { name: /Changer mon mot de passe/ }))
    expect(kc.redirectToAccountAction).toHaveBeenCalledWith('UPDATE_PASSWORD', '/parametres?tab=profil')
  })

  it('clavier : focus dans le panneau, flèches entre les actions, Tab reste dedans, Échap ferme et rend le focus', async () => {
    state.role = 'VIEWER'
    renderTopBar()
    const card = screen.getByRole('button', { name: /^Mon compte : / })
    const panel = await openPanel()
    const [profil, password, logout] = within(within(panel).getByRole('group', { name: 'Actions du compte' })).getAllByRole('button')
    await waitFor(() => expect(document.activeElement).toBe(profil))
    fireEvent.keyDown(profil, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(password)
    fireEvent.keyDown(password, { key: 'ArrowUp' })
    fireEvent.keyDown(profil, { key: 'ArrowUp' })
    expect(document.activeElement).toBe(logout)
    fireEvent.keyDown(logout, { key: 'Tab' })
    expect(document.activeElement).toBe(profil)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog', { name: 'Mon compte' })).toBeNull()
    expect(document.activeElement).toBe(card)
  })

  it('panneau sous la carte de la barre du haut ; clic dedans le garde ouvert, clic à l’extérieur le ferme', async () => {
    renderTopBar()
    const panel = await openPanel()
    expect(panel.parentElement?.classList.contains('tb-account')).toBe(true)
    fireEvent.mouseDown(within(panel).getByText('Statut'))
    expect(screen.getByRole('dialog', { name: 'Mon compte' })).toBeTruthy()
    fireEvent.mouseDown(screen.getByText('Ailleurs sur la page'))
    expect(screen.queryByRole('dialog', { name: 'Mon compte' })).toBeNull()
  })

  it('Super administrateur : « Modifier » prénom, nom et e-mail, mêmes règles que le serveur, enregistré puis affiché', async () => {
    api.updateIdentity.mockResolvedValue({ ...profileOf('SUPER_ADMIN'), name: 'Amina Alaoui', firstName: 'Amina', lastName: 'Alaoui', email: 'amina@loadpilot.local' })
    renderTopBar()
    const panel = await openPanel()
    fireEvent.click(within(panel).getByRole('button', { name: /Modifier/ }))
    const form = within(panel).getByRole('form', { name: 'Modifier mon nom et mon e-mail' })
    expect((within(form).getByLabelText('Prénom') as HTMLInputElement).value).toBe('Admin')
    // Jamais le nom d'utilisateur ni le rôle.
    expect(within(form).queryByLabelText(/utilisateur|Rôle/)).toBeNull()

    fireEvent.change(within(form).getByLabelText('Prénom'), { target: { value: ' ' } })
    fireEvent.change(within(form).getByLabelText('E-mail'), { target: { value: 'pas-un-email' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Enregistrer' }))
    expect(within(form).getByText('Le prénom est obligatoire.')).toBeTruthy()
    expect(within(form).getByText('L’e-mail n’est pas valide.')).toBeTruthy()
    expect(api.updateIdentity).not.toHaveBeenCalled()

    fireEvent.change(within(form).getByLabelText('Prénom'), { target: { value: '  Amina ' } })
    fireEvent.change(within(form).getByLabelText('Nom'), { target: { value: 'Alaoui' } })
    fireEvent.change(within(form).getByLabelText('E-mail'), { target: { value: 'amina@loadpilot.local' } })
    await act(async () => { fireEvent.click(within(form).getByRole('button', { name: 'Enregistrer' })) })
    expect(api.updateIdentity).toHaveBeenCalledWith({ firstName: 'Amina', lastName: 'Alaoui', email: 'amina@loadpilot.local' })
    expect(state.refreshUser).toHaveBeenCalled()
    await waitFor(() => expect(panel.textContent).toContain('Amina Alaoui'))
    expect(screen.getByText('Votre compte a été mis à jour.')).toBeTruthy()
  })

  it('e-mail déjà utilisé : message du serveur, rien n’est changé', async () => {
    api.updateIdentity.mockRejectedValue(new ApiError('Cet e-mail est déjà utilisé par un autre compte.', 409))
    renderTopBar()
    const panel = await openPanel()
    fireEvent.click(within(panel).getByRole('button', { name: /Modifier/ }))
    fireEvent.change(within(panel).getByLabelText('E-mail'), { target: { value: 'engineer-test@loadpilot.local' } })
    await act(async () => { fireEvent.click(within(panel).getByRole('button', { name: 'Enregistrer' })) })
    expect(within(panel).getByRole('alert').textContent).toContain('Cet e-mail est déjà utilisé par un autre compte.')
    expect(state.refreshUser).not.toHaveBeenCalled()
  })
})

describe('Carte utilisateur de la barre du haut', () => {
  it('initiales, nom et rôle en français ; ouvre le même panneau, sous la carte ; Échap ferme', async () => {
    state.role = 'PERFORMANCE_ENGINEER'
    render(<MemoryRouter><ToastProvider><TopBarIcons onOpenMobileMenu={() => {}} /></ToastProvider></MemoryRouter>)
    const card = screen.getByRole('button', { name: 'Mon compte : Nom du jeton' })
    expect(card.textContent).toContain('NT')
    expect(card.textContent).toContain('Ingénieur performance')
    expect(card.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(card)
    const panel = screen.getByRole('dialog', { name: 'Mon compte' })
    expect(panel.closest('.tb-account')).toBeTruthy()
    expect(card.getAttribute('aria-expanded')).toBe('true')
    await within(panel).findByText('@engineer-test')
    expect(within(panel).queryByRole('button', { name: /Modifier/ })).toBeNull()
    fireEvent.click(within(panel).getByRole('button', { name: /Déconnexion/ }))
    expect(state.logout).toHaveBeenCalledTimes(1)
    fireEvent.click(card)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog', { name: 'Mon compte' })).toBeNull()
    expect(document.activeElement).toBe(card)
  })
})

describe('Paramètres > Mon profil : même règle', () => {
  const renderProfile = () => render(<MemoryRouter><ToastProvider><Settings embedded /></ToastProvider></MemoryRouter>)

  it('Ingénieur performance et Observateur : lecture seule (aucun champ, pas de « Modifier »), mot de passe toujours possible', async () => {
    for (const role of ['PERFORMANCE_ENGINEER', 'VIEWER'] as Role[]) {
      state.role = role
      const { unmount } = renderProfile()
      const card = (await screen.findByRole('heading', { name: 'Profil' })).closest('section') as HTMLElement
      expect(card.textContent).toContain(PEOPLE[role].label)
      expect(within(card).queryByRole('textbox')).toBeNull()
      expect(within(card).queryByRole('button', { name: /Modifier/ })).toBeNull()
      expect(card.textContent).not.toContain('modifiables par un administrateur')
      expect(within(card).getByRole('button', { name: /Changer mon mot de passe/ })).toBeTruthy()
      unmount()
    }
  })

  it('Super administrateur : « Modifier » ouvre le formulaire dans la carte Profil', async () => {
    renderProfile()
    const card = (await screen.findByRole('heading', { name: 'Profil' })).closest('section') as HTMLElement
    fireEvent.click(within(card).getByRole('button', { name: /Modifier/ }))
    expect(within(card).getByRole('form', { name: 'Modifier mon nom et mon e-mail' })).toBeTruthy()
    expect((within(card).getByLabelText('E-mail') as HTMLInputElement).value).toBe('admin-test@loadpilot.local')
    fireEvent.click(within(card).getByRole('button', { name: 'Annuler' }))
    expect(within(card).queryByRole('form')).toBeNull()
  })
})
