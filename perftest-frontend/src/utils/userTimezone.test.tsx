import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../context/ToastContext'

// Fuseau horaire de Mon profil : après « Enregistrer », il devient tout de suite (sans recharger la
// page) le fuseau proposé d'une nouvelle planification et celui de l'affichage des dates.

const profile = vi.hoisted(() => ({ timezone: 'Africa/Casablanca' as string | null }))
const api = vi.hoisted(() => ({
  get: vi.fn(),
  updateTimezone: vi.fn(),
  security: vi.fn(),
  passwordChanged: vi.fn(),
}))
vi.mock('../services/api/profileBackend', () => ({ profileBackendApi: api }))
vi.mock('../services/auth/keycloakClient', () => ({ redirectToAccountAction: vi.fn(), consumeAccountActionResult: () => null }))
vi.mock('../pages/settings/AppearanceCard', () => ({ default: () => <div>Apparence</div> }))
vi.mock('../pages/settings/NotificationsCard', () => ({ default: () => <div>Notifications</div> }))
vi.mock('../pages/settings/SecurityCard', () => ({ default: () => <div>Sécurité</div> }))
vi.mock('../services/api/scheduledExecutionsBackend', () => ({ scheduledExecutionsBackendApi: {
  preview: () => Promise.resolve({ nextRuns: [] }), create: vi.fn(), update: vi.fn(),
} }))

import Settings from '../pages/Settings'
import ScheduleFormModal from '../pages/scheduling/ScheduleFormModal'
import { fullDate } from '../pages/executions/executionsModel'
import { formatDateTime } from '../pages/dashboard/dashboardModel'
import { browserTimezone, getUserTimezone, setUserTimezone } from './userTimezone'

const profileResponse = () => ({ id: 'u-1', username: 'engineer-test', name: 'Engineer Test', email: 'e@loadpilot.local',
  roles: ['ROLE_PERFORMANCE_ENGINEER'], timezone: profile.timezone, appRole: 'PERFORMANCE_ENGINEER',
  appRoleLabel: 'Ingénieur performance', theme: null, canEditIdentity: false, firstName: 'Engineer', lastName: 'Test' })

const STARTED = '2026-10-11T12:00:00Z' // 13:00 à Casablanca, 08:00 à New York, 05:00 à Los Angeles

beforeEach(() => {
  vi.clearAllMocks()
  profile.timezone = 'Africa/Casablanca'
  api.get.mockImplementation(async () => profileResponse())
  api.updateTimezone.mockImplementation(async (tz: string) => { profile.timezone = tz; return profileResponse() })
})

const openNewSchedule = () => render(<MemoryRouter>
  <ScheduleFormModal schedule={null} scenarios={[]} onClose={() => {}} onSaved={() => {}} />
</MemoryRouter>)

describe('Fuseau horaire de Mon profil', () => {
  it('« Enregistrer » : nouvelle planification et dates passent au nouveau fuseau, sans recharger la page', async () => {
    const page = render(<MemoryRouter><ToastProvider><Settings embedded /></ToastProvider></MemoryRouter>)
    const select = await screen.findByRole('combobox', { name: 'Fuseau horaire' }) as HTMLSelectElement
    expect(select.value).toBe('Africa/Casablanca')
    expect(getUserTimezone()).toBe('Africa/Casablanca')
    expect(fullDate(STARTED)).toBe('dimanche 11 octobre 2026 à 13:00')

    fireEvent.change(select, { target: { value: 'America/New_York' } })
    // Pas encore enregistré : rien ne change ailleurs.
    expect(getUserTimezone()).toBe('Africa/Casablanca')
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' })) })
    expect(api.updateTimezone).toHaveBeenCalledWith('America/New_York')
    expect(await screen.findByText('Enregistré')).toBeTruthy()

    // Dates (page Exécutions, Dashboard) : tout de suite dans le nouveau fuseau.
    expect(getUserTimezone()).toBe('America/New_York')
    expect(fullDate(STARTED)).toBe('dimanche 11 octobre 2026 à 08:00')
    expect(formatDateTime(STARTED)).toBe('11/10/2026 08:00')
    page.unmount()

    // « Nouvelle planification » ouverte ensuite : le fuseau proposé est celui du profil, en tête de liste.
    const modal = openNewSchedule()
    const tz = within(screen.getByRole('dialog')).getByLabelText('Fuseau horaire') as HTMLSelectElement
    expect(tz.value).toBe('America/New_York')
    expect(tz.options[0].textContent).toBe('America/New_York (le vôtre)')
    modal.unmount()

    // Changé à nouveau : la nouvelle valeur remplace la précédente.
    render(<MemoryRouter><ToastProvider><Settings embedded /></ToastProvider></MemoryRouter>)
    const again = await screen.findByRole('combobox', { name: 'Fuseau horaire' }) as HTMLSelectElement
    expect(again.value).toBe('America/New_York')
    fireEvent.change(again, { target: { value: 'America/Los_Angeles' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' })) })
    expect(fullDate(STARTED)).toBe('dimanche 11 octobre 2026 à 05:00')
  })

  it('fuseau lu sur le profil à l’ouverture ; sans choix (ou fuseau inconnu) : celui du navigateur', () => {
    setUserTimezone('Asia/Tokyo')
    openNewSchedule()
    expect((within(screen.getByRole('dialog')).getByLabelText('Fuseau horaire') as HTMLSelectElement).value).toBe('Asia/Tokyo')
    expect(formatDateTime(STARTED)).toBe('11/10/2026 21:00')
    setUserTimezone(null)
    expect(getUserTimezone()).toBe(browserTimezone())
    setUserTimezone('Pas/Un_Fuseau')
    expect(getUserTimezone()).toBe(browserTimezone())
  })
})
