import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendUserSummaryResponse } from '../../types/backendContracts'

const auth = vi.hoisted(() => ({ roles: ['ROLE_SUPER_ADMIN'], me: { id: 'u-admin', name: 'Admin Test', initials: 'AT' } }))
vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: auth.roles, user: auth.me }) }))
const api = vi.hoisted(() => ({ list: vi.fn(), updateRole: vi.fn(), updateStatus: vi.fn(), resetPassword: vi.fn(), create: vi.fn(),
  setPassword: vi.fn(), passwordPolicy: vi.fn() }))
vi.mock('../../services/api/usersBackend', () => ({ usersBackendApi: api }))
const toast = vi.hoisted(() => ({ showToast: vi.fn() }))
vi.mock('../../context/ToastContext', () => ({ useToast: () => toast }))

import UsersRoles from '../UsersRoles'
import RolesCatalog from '../RolesCatalog'
import { ApiError } from '../../services/api/httpClient'
import { resetPasswordPolicyCache } from './usePasswordPolicy'

const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString()
const USERS: BackendUserSummaryResponse[] = [
  { id: 'u-admin', username: 'admin-test', email: 'admin-test@loadpilot.local', firstName: 'Admin', lastName: 'Test', displayName: 'Admin Test',
    enabled: true, role: 'SUPER_ADMIN', createdAt: '2026-09-01T08:00:00Z', lastLoginAt: minutesAgo(5), executionCount: 12 },
  { id: 'u-eng', username: 'engineer-test', email: 'engineer-test@loadpilot.local', firstName: 'Eric', lastName: 'Engineer', displayName: 'Eric Engineer',
    enabled: true, role: 'PERFORMANCE_ENGINEER', createdAt: '2026-09-02T08:00:00Z', lastLoginAt: minutesAgo(60 * 26), executionCount: 3 },
  { id: 'u-view', username: 'viewer-test', email: 'viewer-test@loadpilot.local', firstName: null, lastName: null, displayName: 'viewer-test',
    enabled: true, role: 'VIEWER', createdAt: '2026-09-03T08:00:00Z', lastLoginAt: null, executionCount: 0 },
]

const renderUsers = () => render(<MemoryRouter><UsersRoles embedded /></MemoryRouter>)
const rows = () => screen.getAllByRole('row').slice(1)
const row = (name: string) => rows().find((r) => within(r).queryByText(name))!

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_SUPER_ADMIN']
  api.list.mockResolvedValue(USERS.map((u) => ({ ...u })))
  // Politique réelle du realm LoadPilot.
  api.passwordPolicy.mockResolvedValue({ minLength: 8, maxLength: 64, minUpperCase: 1, minLowerCase: 1, minDigits: 1, minSpecialChars: 0, notUsername: true, notEmail: true })
  resetPasswordPolicyCache()
})

describe('Onglet Utilisateurs', () => {
  it('résumé compact cliquable : « 3 utilisateurs · 3 actifs · 1 administrateur · 1 ingénieur · 1 lecteur »', async () => {
    renderUsers()
    const summary = await screen.findByRole('navigation', { name: /Résumé des utilisateurs/ })
    expect(Array.from(summary.children).map((c) => c.textContent?.trim()).join(' ')).toBe('3 utilisateurs · 3 actifs · 1 administrateur · 1 ingénieur · 1 lecteur')
    fireEvent.click(within(summary).getByRole('button', { name: '1 lecteur' }))
    expect(rows()).toHaveLength(1)
    expect(within(rows()[0]).getByText('viewer-test')).toBeTruthy()
    expect(within(summary).getByRole('button', { name: '1 lecteur' }).getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(within(summary).getByRole('button', { name: '3 utilisateurs' }))
    expect(rows()).toHaveLength(3)
  })

  it('tableau : nom affiché + e-mail, rôle en français avec icône, statut, dernière connexion relative (date complète au survol)', async () => {
    const { container } = renderUsers()
    await screen.findByText('Admin Test')
    const admin = row('Admin Test')
    expect(within(admin).getByText('admin-test@loadpilot.local')).toBeTruthy()
    expect(within(admin).getByText('Administrateur').closest('.ur-role')?.className).toContain('ur-role--admin')
    expect(within(admin).getByText('Actif')).toBeTruthy()
    const time = within(admin).getByText('il y a 5 minutes')
    expect(time.getAttribute('title')).toMatch(/\d{4} à \d{2}:\d{2}/)
    expect(within(row('Eric Engineer')).getByText('Ingénieur performance').closest('.ur-role')?.className).toContain('ur-role--engineer')
    expect(within(row('Eric Engineer')).getByText('hier')).toBeTruthy()
    expect(within(row('viewer-test')).getByText('Lecteur')).toBeTruthy()
    expect(within(row('viewer-test')).getByText('Jamais')).toBeTruthy()
    // Jamais le code technique d'un rôle, plus de colonne « Créé le ».
    expect(container.textContent).not.toMatch(/SUPER_ADMIN|PERFORMANCE_ENGINEER|VIEWER|Créé le/)
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Utilisateur', 'Rôle', 'Statut', 'Dernière connexion', 'Actions'])
  })

  it('avatars : initiales du nom affiché et couleur stable propre à chaque utilisateur', async () => {
    const { unmount } = renderUsers()
    await screen.findByText('Admin Test')
    const avatar = (name: string) => row(name).querySelector('.ur-avatar')!
    expect(avatar('Admin Test').textContent).toBe('AT')
    expect(avatar('Eric Engineer').textContent).toBe('EE')
    expect(avatar('viewer-test').textContent).toBe('VT')
    const tones = ['Admin Test', 'Eric Engineer', 'viewer-test'].map((n) => avatar(n).className)
    unmount()
    renderUsers()
    await screen.findByText('Admin Test')
    expect(['Admin Test', 'Eric Engineer', 'viewer-test'].map((n) => avatar(n).className)).toEqual(tones)
  })

  it('filtres : recherche (sans accents), rôle et statut', async () => {
    api.list.mockResolvedValue([...USERS, { ...USERS[2], id: 'u-off', username: 'ancien', displayName: 'Élodie Ancienne', email: 'elodie@loadpilot.local', enabled: false }])
    renderUsers()
    await screen.findByText('Admin Test')
    fireEvent.change(screen.getByLabelText('Rechercher un utilisateur'), { target: { value: 'elodie' } })
    expect(rows()).toHaveLength(1)
    expect(within(rows()[0]).getByText('Désactivé')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Effacer les filtres/ }))
    fireEvent.change(screen.getByLabelText('Filtrer par statut'), { target: { value: 'disabled' } })
    expect(rows()).toHaveLength(1)
    fireEvent.change(screen.getByLabelText('Filtrer par statut'), { target: { value: 'all' } })
    fireEvent.change(screen.getByLabelText('Filtrer par rôle'), { target: { value: 'PERFORMANCE_ENGINEER' } })
    expect(rows().map((r) => r.querySelector('.ur-person-name')?.textContent)).toEqual(['Eric Engineer'])
  })

  it('clic sur une ligne : panneau latéral (identité, activité) ; changement de rôle avec description et confirmation', async () => {
    api.updateRole.mockResolvedValue({ ...USERS[2], role: 'PERFORMANCE_ENGINEER' })
    renderUsers()
    fireEvent.click(await screen.findByText('viewer-test'))
    const drawer = screen.getByRole('dialog', { name: 'viewer-test' })
    // Sans prénom ni nom, le nom affiché est déjà le username : il n'est pas répété sous l'e-mail.
    expect(within(drawer).getByText('viewer-test@loadpilot.local')).toBeTruthy()
    expect(within(drawer).getByText('Exécutions lancées').nextElementSibling?.textContent).toBe('0')
    expect(within(drawer).getByText(/Consulte les tableaux de bord/)).toBeTruthy()
    fireEvent.click(within(drawer).getByRole('radio', { name: /Ingénieur performance/ }))
    fireEvent.click(within(drawer).getByRole('button', { name: /Enregistrer le rôle/ }))
    const confirm = screen.getByRole('dialog', { name: 'Changer le rôle' })
    expect(api.updateRole).not.toHaveBeenCalled()
    fireEvent.click(within(confirm).getByRole('button', { name: 'Changer le rôle' }))
    await waitFor(() => expect(api.updateRole).toHaveBeenCalledWith('u-view', 'PERFORMANCE_ENGINEER'))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Changer le rôle' })).toBeNull())
    expect(toast.showToast).toHaveBeenCalledWith('viewer-test est maintenant ingénieur performance.', 'success')
    expect(within(row('viewer-test')).getByText('Ingénieur performance')).toBeTruthy()
  })

  it('son propre compte : rôle Administrateur non retirable, désactivation impossible (panneau et menu)', async () => {
    renderUsers()
    await screen.findByText('Admin Test')
    fireEvent.click(within(row('Admin Test')).getByRole('button', { name: 'Actions pour Admin Test' }))
    expect(screen.getByRole('menuitem', { name: /Désactiver/ }).hasAttribute('disabled')).toBe(true)
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(within(row('Admin Test')).getByText('Admin Test'))
    const drawer = screen.getByRole('dialog', { name: /Admin Test/ })
    expect(within(drawer).getByText(/vous ne pouvez pas retirer votre rôle Administrateur/)).toBeTruthy()
    expect((within(drawer).getByRole('radio', { name: /Lecteur/ }) as HTMLInputElement).disabled).toBe(true)
    expect((within(drawer).getByRole('radio', { name: /Ingénieur performance/ }) as HTMLInputElement).disabled).toBe(true)
    expect(within(drawer).getByRole('button', { name: /Désactiver le compte/ }).hasAttribute('disabled')).toBe(true)
  })

  it('menu « ⋯ » : désactiver (rouge, avec confirmation) puis réinitialiser le mot de passe par e-mail', async () => {
    api.updateStatus.mockResolvedValue({ ...USERS[1], enabled: false })
    api.resetPassword.mockResolvedValue(undefined)
    renderUsers()
    await screen.findByText('Eric Engineer')
    fireEvent.click(within(row('Eric Engineer')).getByRole('button', { name: 'Actions pour Eric Engineer' }))
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Modifier le rôle', 'Réinitialiser le mot de passe', 'Désactiver'])
    expect(screen.getByRole('menuitem', { name: /Désactiver/ }).className).toContain('danger')
    fireEvent.click(screen.getByRole('menuitem', { name: /Désactiver/ }))
    const confirm = screen.getByRole('dialog', { name: 'Désactiver le compte' })
    expect(within(confirm).getByText(/ne pourra plus se connecter/)).toBeTruthy()
    fireEvent.click(within(confirm).getByRole('button', { name: 'Désactiver' }))
    await waitFor(() => expect(api.updateStatus).toHaveBeenCalledWith('u-eng', false))
    await waitFor(() => expect(within(row('Eric Engineer')).getByText('Désactivé')).toBeTruthy())

    fireEvent.click(within(row('viewer-test')).getByRole('button', { name: 'Actions pour viewer-test' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /Réinitialiser le mot de passe/ }))
    const reset = screen.getByRole('dialog', { name: 'Réinitialiser le mot de passe' })
    // Mode par défaut : saisir / générer ; on choisit l'envoi du lien.
    expect((within(reset).getByRole('radio', { name: /Saisir ou générer un mot de passe/ }) as HTMLInputElement).checked).toBe(true)
    fireEvent.click(within(reset).getByRole('radio', { name: /Envoyer un lien par e-mail/ }))
    expect(within(reset).getByText(/viewer-test@loadpilot.local recevra un lien/)).toBeTruthy()
    fireEvent.click(within(reset).getByRole('button', { name: 'Envoyer le lien' }))
    await waitFor(() => expect(api.resetPassword).toHaveBeenCalledWith('u-view'))
    expect(api.setPassword).not.toHaveBeenCalled()
    expect(toast.showToast).toHaveBeenCalledWith('Lien de réinitialisation envoyé à viewer-test@loadpilot.local.', 'success')
  })

  it('réinitialiser : nouveau mot de passe saisi (non temporaire), règles vérifiées en direct, jamais par e-mail', async () => {
    api.setPassword.mockResolvedValue(undefined)
    renderUsers()
    await screen.findByText('Eric Engineer')
    fireEvent.click(within(row('Eric Engineer')).getByRole('button', { name: 'Actions pour Eric Engineer' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /Réinitialiser le mot de passe/ }))
    const reset = screen.getByRole('dialog', { name: 'Réinitialiser le mot de passe' })
    await waitFor(() => expect(within(reset).getByText('Au moins 8 caractères')).toBeTruthy())
    const pwd = within(reset).getByLabelText(/^Mot de passe/) as HTMLInputElement
    fireEvent.change(pwd, { target: { value: 'court' } })
    const rules = within(reset).getByRole('list', { name: 'Règles du mot de passe' })
    expect(within(rules).getByText('Au moins 8 caractères').closest('li')?.className).not.toContain('ok')
    expect(within(rules).getByText('Au moins 1 minuscule').closest('li')?.className).toContain('ok')
    fireEvent.click(within(reset).getByRole('button', { name: 'Enregistrer le mot de passe' }))
    expect(within(reset).getByRole('alert').textContent).toMatch(/au moins 8 caractères/)
    expect(api.setPassword).not.toHaveBeenCalled()
    fireEvent.change(pwd, { target: { value: 'Nouveau-Mdp-2026' } })
    fireEvent.change(within(reset).getByLabelText(/^Confirmation/), { target: { value: 'Nouveau-Mdp-2026' } })
    fireEvent.click(within(reset).getByRole('checkbox', { name: /changer à sa première connexion/ }))
    await act(async () => { fireEvent.click(within(reset).getByRole('button', { name: 'Enregistrer le mot de passe' })) })
    expect(api.setPassword).toHaveBeenCalledWith('u-eng', 'Nouveau-Mdp-2026', false)
    expect(api.resetPassword).not.toHaveBeenCalled()
    expect(toast.showToast).toHaveBeenCalledWith('Nouveau mot de passe défini pour Eric Engineer.', 'success')
  })

  it('réinitialiser : erreur de politique renvoyée par le serveur affichée telle quelle', async () => {
    api.setPassword.mockRejectedValue(new ApiError('Ce mot de passe a déjà été utilisé récemment : choisissez-en un autre.', 400))
    renderUsers()
    await screen.findByText('Eric Engineer')
    fireEvent.click(within(row('Eric Engineer')).getByRole('button', { name: 'Actions pour Eric Engineer' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /Réinitialiser le mot de passe/ }))
    const reset = screen.getByRole('dialog', { name: 'Réinitialiser le mot de passe' })
    await waitFor(() => expect(within(reset).getByText('Au moins 8 caractères')).toBeTruthy())
    fireEvent.click(within(reset).getByRole('button', { name: /Générer/ }))
    await act(async () => { fireEvent.click(within(reset).getByRole('button', { name: 'Enregistrer le mot de passe' })) })
    expect(within(reset).getByRole('alert').textContent).toContain('Ce mot de passe a déjà été utilisé récemment')
    expect(api.setPassword.mock.calls[0][2]).toBe(true)
  })

  it('erreur du serveur lors d’une action : message affiché, fenêtre gardée ouverte', async () => {
    api.updateStatus.mockRejectedValue(new ApiError('Vous ne pouvez pas désactiver votre propre compte.', 409))
    renderUsers()
    await screen.findByText('Eric Engineer')
    fireEvent.click(within(row('Eric Engineer')).getByRole('button', { name: 'Actions pour Eric Engineer' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /Désactiver/ }))
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Désactiver le compte' })).getByRole('button', { name: 'Désactiver' }))
    await waitFor(() => expect(toast.showToast).toHaveBeenCalledWith('Vous ne pouvez pas désactiver votre propre compte.', 'danger'))
    expect(screen.getByRole('dialog', { name: 'Désactiver le compte' })).toBeTruthy()
  })

  const CREATED = { id: 'u-new', username: 'lina.dupont', email: 'lina@loadpilot.local', firstName: 'Lina', lastName: 'Dupont', displayName: 'Lina Dupont',
    enabled: true, role: 'PERFORMANCE_ENGINEER' as const, createdAt: new Date().toISOString(), lastLoginAt: null, executionCount: 0 }

  const openCreate = async (onCreateClose = vi.fn()) => {
    const { rerender } = render(<MemoryRouter><UsersRoles embedded createOpen={false} onCreateClose={onCreateClose} /></MemoryRouter>)
    await screen.findByText('Admin Test')
    rerender(<MemoryRouter><UsersRoles embedded createOpen onCreateClose={onCreateClose} /></MemoryRouter>)
    const dialog = screen.getByRole('dialog', { name: 'Ajouter un utilisateur' })
    await waitFor(() => expect(within(dialog).getByText('Au moins 8 caractères')).toBeTruthy())
    return dialog
  }

  it('« Ajouter un utilisateur » (par défaut) : mot de passe généré, temporaire, sans e-mail obligatoire', async () => {
    api.create.mockResolvedValue({ user: { ...CREATED, email: null }, invitationSent: false, passwordSet: true })
    const onCreateClose = vi.fn()
    const dialog = await openCreate(onCreateClose)
    expect((within(dialog).getByRole('radio', { name: /Définir le mot de passe maintenant/ }) as HTMLInputElement).checked).toBe(true)
    const pwd = within(dialog).getByLabelText(/^Mot de passe/) as HTMLInputElement
    expect(pwd.type).toBe('password')
    expect((within(dialog).getByRole('checkbox', { name: /changer à sa première connexion/ }) as HTMLInputElement).checked).toBe(true)
    fireEvent.click(within(dialog).getByRole('button', { name: /Créer l’utilisateur/ }))
    expect(within(dialog).getByText('Le nom d’utilisateur est obligatoire.')).toBeTruthy()
    expect(within(dialog).queryByText(/L’e-mail est obligatoire/)).toBeNull()
    fireEvent.change(within(dialog).getByLabelText(/Nom d’utilisateur/), { target: { value: 'lina.dupont' } })
    fireEvent.click(within(dialog).getByRole('button', { name: /Générer/ }))
    // Mot de passe fort, affiché, recopié dans la confirmation, toutes les règles respectées.
    expect(pwd.type).toBe('text')
    expect(pwd.value).toHaveLength(16)
    expect((within(dialog).getByLabelText(/^Confirmation/) as HTMLInputElement).value).toBe(pwd.value)
    const rules = within(dialog).getByRole('list', { name: 'Règles du mot de passe' })
    expect(within(rules).getAllByRole('listitem').every((li) => li.className.includes('ok'))).toBe(true)
    fireEvent.click(within(dialog).getByRole('button', { name: /Afficher|Masquer/ }))
    expect(pwd.type).toBe('password')
    fireEvent.click(within(dialog).getByRole('radio', { name: /Ingénieur performance/ }))
    const generated = pwd.value
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: /Créer l’utilisateur/ })) })
    expect(api.create).toHaveBeenCalledWith({ username: 'lina.dupont', email: null, firstName: null, lastName: null, role: 'PERFORMANCE_ENGINEER',
      password: generated, temporaryPassword: true })
    // Le formulaire oublie le mot de passe dès l'envoi.
    expect(pwd.value).toBe('')
    expect(onCreateClose).toHaveBeenCalled()
    expect(toast.showToast).toHaveBeenCalledWith('Lina Dupont a été ajouté avec le mot de passe défini.', 'success')
    expect(row('Lina Dupont')).toBeTruthy()
  })

  it('« Ajouter un utilisateur » : mot de passe saisi refusé tant qu’il ne respecte pas la politique ou la confirmation', async () => {
    const dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText(/Nom d’utilisateur/), { target: { value: 'lina' } })
    fireEvent.change(within(dialog).getByLabelText(/^Mot de passe/), { target: { value: 'Lina' } })
    const rules = within(dialog).getByRole('list', { name: 'Règles du mot de passe' })
    expect(within(rules).getAllByRole('listitem').map((li) => li.textContent?.replace(/ : .*$/, ''))).toEqual([
      'Au moins 8 caractères', 'Au moins 1 majuscule', 'Au moins 1 minuscule', 'Au moins 1 chiffre', 'Au plus 64 caractères',
      'Différent du nom d’utilisateur', 'Différent de l’adresse e-mail'])
    expect(within(rules).getByText('Différent du nom d’utilisateur').closest('li')?.className).not.toContain('ok')
    fireEvent.change(within(dialog).getByLabelText(/^Mot de passe/), { target: { value: 'Lina-2026-ok' } })
    fireEvent.change(within(dialog).getByLabelText(/^Confirmation/), { target: { value: 'Lina-2026-ko' } })
    expect(within(dialog).getByText('Les deux mots de passe ne sont pas identiques.')).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: /Créer l’utilisateur/ }))
    expect(api.create).not.toHaveBeenCalled()
  })

  it('« Ajouter un utilisateur » : « Envoyer un lien par e-mail » (e-mail obligatoire, aucun mot de passe envoyé)', async () => {
    api.create.mockResolvedValue({ user: CREATED, invitationSent: true, passwordSet: false })
    const dialog = await openCreate()
    fireEvent.click(within(dialog).getByRole('radio', { name: /Envoyer un lien par e-mail/ }))
    expect(dialog.querySelector('input[type="password"]')).toBeNull()
    fireEvent.change(within(dialog).getByLabelText(/Nom d’utilisateur/), { target: { value: 'lina.dupont' } })
    fireEvent.click(within(dialog).getByRole('button', { name: /Créer et envoyer le lien/ }))
    expect(within(dialog).getByText('L’e-mail est obligatoire pour envoyer le lien.')).toBeTruthy()
    fireEvent.change(within(dialog).getByLabelText(/E-mail/), { target: { value: 'lina@loadpilot.local' } })
    fireEvent.change(within(dialog).getByLabelText('Prénom'), { target: { value: 'Lina' } })
    fireEvent.change(within(dialog).getByLabelText('Nom'), { target: { value: 'Dupont' } })
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: /Créer et envoyer le lien/ })) })
    expect(api.create).toHaveBeenCalledWith({ username: 'lina.dupont', email: 'lina@loadpilot.local', firstName: 'Lina', lastName: 'Dupont', role: 'VIEWER' })
    expect(toast.showToast).toHaveBeenCalledWith('Lina Dupont a été ajouté. Un lien pour choisir son mot de passe a été envoyé à lina@loadpilot.local.', 'success')
  })

  it('« Copier » : copie le mot de passe dans le presse-papiers', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const dialog = await openCreate()
    expect(within(dialog).getByRole('button', { name: /Copier/ }).hasAttribute('disabled')).toBe(true)
    fireEvent.click(within(dialog).getByRole('button', { name: /Générer/ }))
    const value = (within(dialog).getByLabelText(/^Mot de passe/) as HTMLInputElement).value
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: /Copier/ })) })
    expect(writeText).toHaveBeenCalledWith(value)
    expect(within(dialog).getByRole('button', { name: /Copié/ })).toBeTruthy()
  })

  it('réservé aux administrateurs', () => {
    auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
    renderUsers()
    expect(screen.getByText('Accès restreint')).toBeTruthy()
    expect(api.list).not.toHaveBeenCalled()
  })
})

describe('Onglet Rôles', () => {
  it('une carte par rôle (nom français, description, nombre d’utilisateurs) et le tableau des permissions', async () => {
    const { container } = render(<MemoryRouter><RolesCatalog embedded /></MemoryRouter>)
    const cards = await screen.findAllByRole('article')
    expect(cards.map((c) => within(c).getByRole('heading').textContent)).toEqual(['Administrateur', 'Ingénieur performance', 'Lecteur'])
    await waitFor(() => expect(within(cards[0]).getByText('1 utilisateur')).toBeTruthy())
    const table = screen.getByRole('table', { name: 'Permissions de chaque rôle' })
    const line = (label: RegExp) => within(table).getByRole('row', { name: label })
    const answers = (r: HTMLElement) => within(r).getAllByRole('cell').map((c) => c.textContent?.replace('—', '').trim())
    expect(answers(line(/Gérer les utilisateurs/))).toEqual(['Oui', 'Non', 'Non'])
    expect(answers(line(/Lancer, mettre en pause/))).toEqual(['Oui', 'Oui', 'Non'])
    expect(answers(line(/Consulter le tableau de bord/))).toEqual(['Oui', 'Oui', 'Oui'])
    expect(answers(line(/Supprimer définitivement/))).toEqual(['Oui', 'Non', 'Non'])
    expect(container.textContent).not.toMatch(/SUPER_ADMIN|PERFORMANCE_ENGINEER|VIEWER/)
  })
})
