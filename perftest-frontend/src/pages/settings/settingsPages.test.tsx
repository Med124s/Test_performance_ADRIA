import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendIntegrations, BackendNotificationPreferenceResponse } from '../../types/backendContracts'

const auth = vi.hoisted(() => ({ roles: ['ROLE_SUPER_ADMIN'] }))
vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: auth.roles }) }))
const toast = vi.hoisted(() => ({ showToast: vi.fn() }))
vi.mock('../../context/ToastContext', () => ({ useToast: () => toast }))
vi.mock('../../components/TopBar', () => ({ default: () => null }))
const profileApi = vi.hoisted(() => ({ get: vi.fn(), updateTimezone: vi.fn(), updateTheme: vi.fn(), security: vi.fn(), logoutEverywhere: vi.fn(), passwordChanged: vi.fn() }))
vi.mock('../../services/api/profileBackend', () => ({ profileBackendApi: profileApi }))
const prefsApi = vi.hoisted(() => ({ list: vi.fn(), update: vi.fn(), channels: vi.fn(), setEnabled: vi.fn() }))
vi.mock('../../services/api/notificationPreferencesBackend', () => ({ notificationPreferencesBackendApi: prefsApi }))
const settingsApi = vi.hoisted(() => ({ thresholds: vi.fn(), updateThresholds: vi.fn(), storage: vi.fn(), integrations: vi.fn(), sendTestEmail: vi.fn(),
  limits: vi.fn(), updateLimits: vi.fn(),
  resultsRetention: vi.fn(), updateResultsRetention: vi.fn() }))
vi.mock('../../services/api/settingsBackend', () => ({ settingsBackendApi: settingsApi }))
const systemApi = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../services/api/systemConfigBackend', () => ({ systemConfigBackendApi: systemApi }))
const auditApi = vi.hoisted(() => ({ settings: vi.fn(), updateSettings: vi.fn() }))
vi.mock('../../services/api/auditBackend', () => ({ auditBackendApi: auditApi }))
const kc = vi.hoisted(() => ({ redirectToAccountAction: vi.fn(), consumeAccountActionResult: vi.fn(), forgetSessionAndLogin: vi.fn() }))
vi.mock('../../services/auth/keycloakClient', () => kc)

import Settings from '../Settings'
import Configurations, { formatDuration } from '../Configurations'
import SettingsIntegrations from '../SettingsIntegrations'
import { formatBytes, formatNextRun, formatRunDate } from './StorageStatus'
import { validateThresholds } from './ThresholdsCard'
import { TOTAL_BELOW_PER_TEST, totalBelowPerTest } from './LimitsCard'
import { resetThemeForTests, setThemeSaver } from '../../utils/theme'
import { useDarkMode } from '../../utils/useDarkMode'
import { resetThresholdsCache } from '../../utils/thresholds'
import settingsSource from '../Settings.tsx?raw'

const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString()
const PREFS: BackendNotificationPreferenceResponse[] = ['EXECUTION_SUCCESS', 'EXECUTION_THRESHOLDS_EXCEEDED', 'EXECUTION_FAILED', 'EXECUTION_CANCELLED',
  'SCHEDULE_TRIGGERED', 'SCHEDULE_FAILED', 'SCHEDULE_REGRESSION'].map((type) => ({ type, enabled: true, emailEnabled: false }) as BackendNotificationPreferenceResponse)

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_SUPER_ADMIN']
  try { localStorage.clear() } catch { /* */ }
  resetThemeForTests()
  resetThresholdsCache()
  profileApi.get.mockResolvedValue({ id: 'u1', username: 'engineer-test', name: 'Eric Engineer', email: 'eric@loadpilot.local',
    roles: ['ROLE_PERFORMANCE_ENGINEER', 'offline_access', 'uma_authorization', 'default-roles-loadpilot'], timezone: 'UTC',
    appRole: 'PERFORMANCE_ENGINEER', appRoleLabel: 'Ingénieur performance', theme: null })
  profileApi.updateTheme.mockResolvedValue({})
  profileApi.security.mockResolvedValue({ lastLoginAt: minutesAgo(30), lastLoginIp: 'localhost', devicesKnown: true, message: null, sessions: [
    { id: 's1', device: 'Chrome sur Windows 10', mobile: false, ip: 'localhost', started: minutesAgo(30), lastAccess: minutesAgo(1), current: true },
    { id: 's2', device: 'Firefox sur Android', mobile: true, ip: '10.0.0.5', started: minutesAgo(60 * 26), lastAccess: minutesAgo(60 * 25), current: false },
  ] })
  prefsApi.list.mockResolvedValue(PREFS.map((p) => ({ ...p })))
  prefsApi.channels.mockResolvedValue({ inApp: true, email: true, emailServer: true, emailAddress: 'eric@loadpilot.local' })
  prefsApi.update.mockImplementation((type, ch) => Promise.resolve({ type, enabled: ch.enabled ?? true, emailEnabled: ch.emailEnabled ?? false }))
  settingsApi.thresholds.mockResolvedValue({ maxErrorRatePercent: 1, maxP95Ms: 0, regressionPercent: 20 })
  kc.consumeAccountActionResult.mockReturnValue(null)
})

const renderProfile = () => render(<MemoryRouter><Settings embedded /></MemoryRouter>)

describe('Mon profil', () => {
  it('rôle applicatif seul, en français (jamais offline_access, uma_authorization, default-roles-…)', async () => {
    const { container } = renderProfile()
    expect(await screen.findByText('Ingénieur performance')).toBeTruthy()
    expect(container.textContent).not.toMatch(/offline_access|uma_authorization|default-roles/)
    expect(settingsSource).not.toMatch(/profile\.roles\.map/)
  })

  it('« Changer mon mot de passe » ouvre la page du service de connexion et revient sur Paramètres', async () => {
    renderProfile()
    fireEvent.click(await screen.findByRole('button', { name: /Changer mon mot de passe/ }))
    expect(kc.redirectToAccountAction).toHaveBeenCalledWith('UPDATE_PASSWORD', '/parametres?tab=profil')
  })

  it('retour réussi : message de confirmation et inscription au journal ; annulé : message neutre', async () => {
    kc.consumeAccountActionResult.mockReturnValueOnce({ action: 'UPDATE_PASSWORD', status: 'success' })
    profileApi.passwordChanged.mockResolvedValue({ recorded: true })
    const { unmount } = renderProfile()
    await waitFor(() => expect(toast.showToast).toHaveBeenCalledWith('Votre mot de passe a été modifié. Le changement est inscrit au journal d’audit.', 'success'))
    expect(profileApi.passwordChanged).toHaveBeenCalledTimes(1)
    unmount()
    kc.consumeAccountActionResult.mockReturnValueOnce({ action: 'UPDATE_PASSWORD', status: 'cancelled' })
    renderProfile()
    await waitFor(() => expect(toast.showToast).toHaveBeenCalledWith('Changement de mot de passe annulé : votre mot de passe n’a pas changé.', 'info'))
  })

  it('apparence : Clair · Sombre · Système, même état que le bouton de la barre latérale, mémorisée sur le profil', async () => {
    const saver = vi.fn()
    setThemeSaver(saver)
    let toggle: () => void = () => {}
    function NavButton() {
      const { darkMode, toggleDarkMode } = useDarkMode()
      toggle = toggleDarkMode
      return <span data-testid="nav">{darkMode ? 'sombre' : 'clair'}</span>
    }
    render(<MemoryRouter><Settings embedded /><NavButton /></MemoryRouter>)
    const group = await screen.findByRole('radiogroup', { name: 'Apparence' })
    expect(within(group).getAllByRole('radio').map((r) => r.closest('label')?.textContent)).toEqual(
      ['ClairToujours clair', 'SombreToujours sombre', 'SystèmeSuit l’appareil'])
    fireEvent.click(within(group).getByRole('radio', { name: /Sombre/ }))
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(screen.getByTestId('nav').textContent).toBe('sombre')
    expect(saver).toHaveBeenLastCalledWith('dark')
    act(() => toggle())
    expect((within(group).getByRole('radio', { name: /Clair/ }) as HTMLInputElement).checked).toBe(true)

    Object.defineProperty(window, 'matchMedia', { configurable: true, value: () => ({ matches: true, addEventListener: () => {} }) })
    fireEvent.click(within(group).getByRole('radio', { name: /Système/ }))
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(saver).toHaveBeenLastCalledWith('system')
    expect(screen.getByText(/Affichage actuel : sombre, comme votre appareil/)).toBeTruthy()
    setThemeSaver(null)
  })

  it('notifications : quatre familles, application et e-mail, lien vers toutes mes notifications', async () => {
    renderProfile()
    const table = await screen.findByRole('table')
    expect(within(table).getAllByRole('rowheader').map((r) => r.querySelector('.st-notif-label')?.textContent)).toEqual(
      ['Fin d’exécution', 'Échec', 'Régression', 'Planification'])
    expect(within(table).getByText(/hausse de plus de 20 %/)).toBeTruthy()
    fireEvent.click(within(table).getByRole('checkbox', { name: 'Échec : par e-mail' }))
    await waitFor(() => expect(prefsApi.update).toHaveBeenCalledWith('EXECUTION_FAILED', { emailEnabled: true }))
    fireEvent.click(within(table).getByRole('checkbox', { name: 'Fin d’exécution : dans l’application' }))
    await waitFor(() => expect(prefsApi.update).toHaveBeenCalledTimes(4))
    expect(prefsApi.update.mock.calls.slice(1).map((c) => c[0])).toEqual(['EXECUTION_SUCCESS', 'EXECUTION_THRESHOLDS_EXCEEDED', 'EXECUTION_CANCELLED'])
    expect(screen.getByText('eric@loadpilot.local', { selector: 'strong' })).toBeTruthy()
    expect(screen.getByRole('link', { name: /Voir toutes mes notifications/ }).getAttribute('href')).toBe('/notifications')
    expect(screen.queryByText('Liens rapides')).toBeNull()
  })

  it('notifications : e-mail indisponible sans serveur e-mail', async () => {
    prefsApi.channels.mockResolvedValue({ inApp: true, email: false, emailServer: false, emailAddress: '' })
    renderProfile()
    const table = await screen.findByRole('table')
    await waitFor(() => expect((within(table).getByRole('checkbox', { name: 'Échec : par e-mail' }) as HTMLInputElement).disabled).toBe(true))
    expect(screen.getByText('Aucun serveur e-mail n’est configuré sur LoadPilot.')).toBeTruthy()
  })

  it('sécurité : dernière connexion, sessions, déconnexion de tous les appareils avec confirmation', async () => {
    profileApi.logoutEverywhere.mockResolvedValue(undefined)
    renderProfile()
    const card = (await screen.findByRole('heading', { name: 'Sécurité' })).closest('section')!
    await within(card).findByText('Chrome sur Windows 10')
    expect(within(card).getByText('Cet appareil')).toBeTruthy()
    expect(within(card).getByText('Firefox sur Android')).toBeTruthy()
    expect(within(card).getByText('Sessions ouvertes (2)')).toBeTruthy()
    fireEvent.click(within(card).getByRole('button', { name: /Se déconnecter de tous les appareils/ }))
    const dialog = screen.getByRole('alertdialog')
    expect(dialog.textContent).toContain('y compris celle-ci')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Annuler' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(profileApi.logoutEverywhere).not.toHaveBeenCalled()
    fireEvent.click(within(card).getByRole('button', { name: /Se déconnecter de tous les appareils/ }))
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Tout déconnecter' }))
    await waitFor(() => expect(kc.forgetSessionAndLogin).toHaveBeenCalledWith('/parametres?tab=profil'))
  })
})

const LIMITS_RESPONSE = {
  limits: [
    // Plafonds par défaut cohérents : 200 par test ≤ 500 au total (ordre du serveur : « par test » d'abord).
    { key: 'virtualUsersPerTest', label: 'Utilisateurs virtuels par test', unit: 'utilisateurs', value: 200, ceiling: 200 },
    { key: 'virtualUsersTotal', label: 'Utilisateurs virtuels au total', unit: 'utilisateurs', value: 500, ceiling: 500 },
    { key: 'concurrentTests', label: 'Tests en même temps', unit: 'tests', value: 10, ceiling: 10 },
    { key: 'maxDurationSeconds', label: 'Durée maximale d’un test', unit: 's', value: 3600, ceiling: 3600 },
    { key: 'maxIterations', label: 'Itérations maximales par utilisateur', unit: 'itérations', value: 10000, ceiling: 10000 },
    { key: 'maxTargetRps', label: 'Débit cible maximal', unit: 'req/s', value: 500, ceiling: 500 },
  ],
  executionTimeoutSeconds: 30, availabilityTimeoutSeconds: 5, effectiveVirtualUsersPerTest: 200,
}

describe('Système', () => {
  beforeEach(() => {
    settingsApi.limits.mockResolvedValue(LIMITS_RESPONSE)
    settingsApi.updateLimits.mockImplementation((v) => Promise.resolve({ ...LIMITS_RESPONSE,
      limits: LIMITS_RESPONSE.limits.map((l) => ({ ...l, value: v[l.key] })) }))
    settingsApi.storage.mockResolvedValue({ databaseSizeBytes: 91_226_112,
      results: { lastRunAt: minutesAgo(1), lastDeleted: 0, lastSummary: '0 requête supprimée', nextRunAt: new Date(Date.now() + 3_600_000).toISOString() },
      audit: { lastRunAt: null, lastDeleted: null, lastSummary: null, nextRunAt: null } })
    settingsApi.resultsRetention.mockResolvedValue({ resultsDays: 90, requestDetailsDays: 30 })
    auditApi.settings.mockResolvedValue({ logReads: false, retentionDays: 365 })
    settingsApi.updateThresholds.mockImplementation((t) => Promise.resolve(t))
  })
  const renderSystem = () => render(<MemoryRouter><Configurations embedded /></MemoryRouter>)

  it('limites : plafond du serveur indiqué, valeur au-delà refusée, délais en lecture seule, enregistrement', async () => {
    const { container } = renderSystem()
    const card = (await screen.findByRole('heading', { name: 'Limites' })).closest('section')!
    const field = (label: RegExp) => within(card).getByRole('textbox', { name: label }) as HTMLInputElement
    await waitFor(() => expect(field(/Durée maximale d’un test/).value).toBe('3600'))
    expect(within(card).getByText('Maximum 3 600 (plafond du serveur).')).toBeTruthy()
    expect(field(/Utilisateurs virtuels par test/).closest('.st-field')!.textContent).toContain('Maximum 200 (plafond du serveur).')
    // Valeurs cohérentes : plus d'estimation « réellement applicable ».
    expect(card.textContent).not.toMatch(/réellement applicable/)
    expect(within(card).getAllByRole('textbox')).toHaveLength(6)
    // Délais : lecture seule, unités lisibles ; plus de « Vérification des planifications ».
    expect(within(card).getByText('30 s')).toBeTruthy()
    expect(within(card).getByText('5 s')).toBeTruthy()
    expect(container.textContent).not.toMatch(/Vérification des planifications|variable d.environnement/)
    expect(within(card).getAllByRole('tooltip').length).toBe(8)
    const save = within(card).getByRole('button', { name: 'Enregistrer' }) as HTMLButtonElement
    expect(save.disabled).toBe(true)

    fireEvent.change(field(/Durée maximale d’un test/), { target: { value: '7200' } })
    expect(within(card).getByText('Durée maximale d’un test : maximum 3 600 (plafond du serveur).')).toBeTruthy()
    expect(save.disabled).toBe(true)
    fireEvent.change(field(/Durée maximale d’un test/), { target: { value: '1800' } })
    fireEvent.change(field(/Tests en même temps/), { target: { value: '3' } })
    expect(save.disabled).toBe(false)
    fireEvent.click(save)
    await waitFor(() => expect(settingsApi.updateLimits).toHaveBeenCalledWith({ virtualUsersPerTest: 200, virtualUsersTotal: 500,
      concurrentTests: 3, maxDurationSeconds: 1800, maxIterations: 10000, maxTargetRps: 500 }))
    expect(toast.showToast).toHaveBeenCalledWith('Limites enregistrées : elles s’appliquent aux prochains enregistrements, lancements et planifications.', 'success')
  })

  it('« au total » inférieur à « par test » : toujours refusé, message rouge sous les deux champs, enregistrement bloqué', async () => {
    renderSystem()
    const card = (await screen.findByRole('heading', { name: 'Limites' })).closest('section')!
    const perTest = await within(card).findByRole('textbox', { name: /Utilisateurs virtuels par test/ }) as HTMLInputElement
    const total = within(card).getByRole('textbox', { name: /Utilisateurs virtuels au total/ }) as HTMLInputElement
    await waitFor(() => expect(perTest.value).toBe('200'))
    const save = within(card).getByRole('button', { name: 'Enregistrer' }) as HTMLButtonElement
    const fieldOf = (input: HTMLInputElement) => input.closest('.st-field') as HTMLElement

    // Total baissé seul sous « par test » : refusé aussi (plus d'ajustement automatique).
    fireEvent.change(total, { target: { value: '150' } })
    const messages = within(card).getAllByText('Le total doit être supérieur ou égal au maximum par test.')
    expect(messages).toHaveLength(2)
    expect(within(fieldOf(total)).getByText('Le total doit être supérieur ou égal au maximum par test.').className).toContain('st-error')
    expect(within(fieldOf(perTest)).getByText('Le total doit être supérieur ou égal au maximum par test.').className).toContain('st-error')
    expect(total.getAttribute('aria-invalid')).toBe('true')
    expect(perTest.getAttribute('aria-invalid')).toBe('true')
    expect(save.disabled).toBe(true)
    expect(within(card).queryByRole('status')).toBeNull()

    // Corrigé en baissant « par test » : messages disparus, enregistrement possible.
    fireEvent.change(perTest, { target: { value: '150' } })
    expect(within(card).queryByText(/Le total doit être supérieur ou égal/)).toBeNull()
    expect(perTest.getAttribute('aria-invalid')).toBeNull()
    expect(save.disabled).toBe(false)

    // « Par test » monté au-dessus du total : refusé.
    fireEvent.change(total, { target: { value: '400' } })
    fireEvent.change(perTest, { target: { value: '180' } })
    expect(save.disabled).toBe(false)
    fireEvent.change(total, { target: { value: '170' } })
    expect(within(card).getAllByText('Le total doit être supérieur ou égal au maximum par test.')).toHaveLength(2)
    expect(save.disabled).toBe(true)
    // Chaque champ garde son propre plafond (message du plafond en premier).
    fireEvent.change(perTest, { target: { value: '600' } })
    expect(within(card).getByText('Utilisateurs virtuels par test : maximum 200 (plafond du serveur).')).toBeTruthy()
    expect(settingsApi.updateLimits).not.toHaveBeenCalled()

    // Égal : accepté et envoyé.
    fireEvent.change(perTest, { target: { value: '170' } })
    expect(save.disabled).toBe(false)
    fireEvent.click(save)
    await waitFor(() => expect(settingsApi.updateLimits).toHaveBeenCalledWith(expect.objectContaining({ virtualUsersPerTest: 170, virtualUsersTotal: 170 })))
  })

  it('ordre d’affichage : « au total » en premier, puis « par test » ; le reste inchangé', async () => {
    renderSystem()
    const card = (await screen.findByRole('heading', { name: 'Limites' })).closest('section')!
    await within(card).findByRole('textbox', { name: /Utilisateurs virtuels au total/ })
    // Libellé de chaque champ (le texte de l'infobulle ⓘ qui le suit est ignoré).
    const labels = within(card).getAllByRole('textbox').map((t) => t.closest('.st-field')!.querySelector('strong')!.firstChild!.textContent?.trim())
    expect(labels).toEqual(['Utilisateurs virtuels au total', 'Utilisateurs virtuels par test', 'Tests en même temps', 'Durée maximale d’un test',
      'Itérations maximales par utilisateur', 'Débit cible maximal'])
  })

  it('règle « au total ≥ par test » (même logique et même message que le serveur)', () => {
    expect(totalBelowPerTest('200', '200')).toBeNull()
    expect(totalBelowPerTest('200', '500')).toBeNull()
    expect(totalBelowPerTest('200', '150')).toBe('Le total doit être supérieur ou égal au maximum par test.')
    expect(totalBelowPerTest('1500', '1200')).toBe(TOTAL_BELOW_PER_TEST)
    expect(totalBelowPerTest('', '150')).toBeNull()
    expect(totalBelowPerTest('200', 'abc')).toBeNull()
  })

  it('taille de la base, dernière et prochaine purge', async () => {
    renderSystem()
    expect(await screen.findByText('87 Mo')).toBeTruthy()
    expect(screen.getByText('0 requête supprimée', { exact: false })).toBeTruthy()
    expect(screen.getAllByText(/^Aujourd’hui à \d\d:\d\d$/).length).toBeGreaterThan(0)
    expect(screen.getByText('pas encore de passage enregistré')).toBeTruthy()
    expect(screen.getByText('désactivée')).toBeTruthy()
  })

  it('seuils par défaut : validation et enregistrement', async () => {
    renderSystem()
    const regression = await screen.findByRole('textbox', { name: /Seuil de régression/ })
    const card = screen.getByRole('heading', { name: 'Seuils par défaut' }).closest('section')!
    const save = () => within(card).getByRole('button', { name: 'Enregistrer' }) as HTMLButtonElement
    expect(save().disabled).toBe(true)
    fireEvent.change(regression, { target: { value: '0' } })
    expect(screen.getByText('Seuil de régression : entre 1 et 500 %.')).toBeTruthy()
    fireEvent.change(regression, { target: { value: '15' } })
    fireEvent.change(screen.getByRole('textbox', { name: /P95 maximal/ }), { target: { value: '800' } })
    fireEvent.change(screen.getByRole('textbox', { name: /Taux d’erreurs maximal/ }), { target: { value: '2,5' } })
    fireEvent.click(save())
    await waitFor(() => expect(settingsApi.updateThresholds).toHaveBeenCalledWith(
      expect.objectContaining({ maxErrorRatePercent: 2.5, maxP95Ms: 800, regressionPercent: 15 })))
    expect(toast.showToast).toHaveBeenCalledWith('Seuils par défaut enregistrés.', 'success')
  })

  it('nouveaux scénarios : frein, 429 et détail des requêtes, valeurs initiales, validation et enregistrement', async () => {
    renderSystem()
    const card = (await screen.findByRole('heading', { name: 'Seuils par défaut' })).closest('section')!
    // Serveur sans ces champs (réponse à 3 valeurs) : valeurs initiales, celles codées jusqu'ici.
    const brake = within(card).getByRole('switch', { name: /Arrêter le test si trop d’erreurs/ }) as HTMLInputElement
    const throttle = within(card).getByRole('switch', { name: /Ralentir quand l’application demande d’attendre/ }) as HTMLInputElement
    expect(brake.checked).toBe(false)
    expect(throttle.checked).toBe(true)
    expect((within(card).getByRole('textbox', { name: /Taux d’erreurs du frein/ }) as HTMLInputElement).value).toBe('20')
    expect((within(card).getByRole('textbox', { name: /Fenêtre du frein/ }) as HTMLInputElement).value).toBe('30')
    expect((within(card).getByRole('textbox', { name: /Attente sans Retry-After/ }) as HTMLInputElement).value).toBe('5')
    expect((within(card).getByRole('textbox', { name: /Rejeux de l’étape au plus/ }) as HTMLInputElement).value).toBe('3')
    expect((within(card).getByRole('combobox', { name: /Détail des requêtes/ }) as HTMLSelectElement).value).toBe('FAILURES_AND_SAMPLE')

    const save = () => within(card).getByRole('button', { name: 'Enregistrer' }) as HTMLButtonElement
    fireEvent.change(within(card).getByRole('textbox', { name: /Fenêtre du frein/ }), { target: { value: '2' } })
    expect(within(card).getByText('Fenêtre du frein automatique : entre 5 et 300 secondes.')).toBeTruthy()
    expect(save().disabled).toBe(true)
    fireEvent.change(within(card).getByRole('textbox', { name: /Fenêtre du frein/ }), { target: { value: '60' } })
    fireEvent.change(within(card).getByRole('textbox', { name: /Rejeux de l’étape au plus/ }), { target: { value: '11' } })
    expect(within(card).getByText('Rejeux après un 429 : entre 0 et 10.')).toBeTruthy()
    fireEvent.change(within(card).getByRole('textbox', { name: /Rejeux de l’étape au plus/ }), { target: { value: '1' } })

    fireEvent.click(brake)
    fireEvent.change(within(card).getByRole('textbox', { name: /Taux d’erreurs du frein/ }), { target: { value: '35,5' } })
    fireEvent.click(throttle)
    fireEvent.change(within(card).getByRole('textbox', { name: /Attente sans Retry-After/ }), { target: { value: '12' } })
    fireEvent.change(within(card).getByRole('combobox', { name: /Détail des requêtes/ }), { target: { value: 'FAILURES' } })
    fireEvent.click(save())
    await waitFor(() => expect(settingsApi.updateThresholds).toHaveBeenCalledWith({
      maxErrorRatePercent: 1, maxP95Ms: 0, regressionPercent: 20,
      errorBrakeEnabled: true, errorBrakeRatePercent: 35.5, errorBrakeWindowSeconds: 60,
      throttleOn429: false, throttleDefaultDelaySeconds: 12, throttleMaxRetries: 1, requestDetailMode: 'FAILURES',
    }))
  })

  it('plus de carte « Charge par défaut » : Limites, Seuils par défaut, Conservation, Journal d’audit', async () => {
    renderSystem()
    await screen.findByRole('heading', { name: 'Seuils par défaut' })
    const titles = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(titles).not.toContain('Charge par défaut')
    expect(titles.indexOf('Limites')).toBe(titles.indexOf('Seuils par défaut') - 1)
    expect(screen.queryByText(/Charge par défaut/)).toBeNull()
  })

  it('utilitaires', () => {
    expect(formatDuration(500)).toBe('500 ms')
    expect(formatDuration(5000)).toBe('5 s')
    expect(formatDuration(90_000)).toBe('1 min 30 s')
    expect(formatDuration(7_200_000)).toBe('2 h')
    expect(formatBytes(91_226_112)).toBe('87 Mo')
    expect(formatBytes(1_288_490_189)).toBe('1,2 Go')
    expect(formatBytes(null)).toBeNull()
    const now = new Date(2026, 9, 8, 10, 0)
    expect(formatNextRun(new Date(2026, 9, 9, 3, 45).toISOString(), now)).toBe('demain à 03:45')
    expect(formatRunDate(new Date(2026, 9, 8, 3, 30).toISOString(), now)).toBe('Aujourd’hui à 03:30')
    expect(formatRunDate(new Date(2026, 9, 7, 3, 30).toISOString(), now)).toBe('Hier à 03:30')
    expect(formatRunDate(new Date(2026, 9, 1, 3, 30).toISOString(), now)).toBe('Le 01/10 à 03:30')
    expect(validateThresholds('1', '', '20')).toBeNull()
    expect(validateThresholds('150', '', '20')).toBe('Taux d’erreurs maximal : entre 0 et 100 %.')
  })
})

describe('Intégrations', () => {
  const DATA: BackendIntegrations = {
    mail: { enabled: true, kind: 'MAILPIT', host: 'localhost', port: 1025, from: 'no-reply@loadpilot.local', fromName: 'LoadPilot',
      authenticated: false, reachable: true, message: null },
    authenticationMail: { host: 'mailpit', port: '1025', from: 'no-reply@loadpilot.local', fromName: 'LoadPilot', authenticated: false },
    monitoring: [
      { applicationId: 'a1', applicationName: 'Banking API', archived: false, type: 'PROMETHEUS', endpoint: 'http://localhost:8085/metrics', reachable: true, message: null },
      { applicationId: 'a2', applicationName: 'Ancienne appli', archived: true, type: 'ACTUATOR', endpoint: 'http://localhost:9/actuator', reachable: false,
        message: 'Endpoint injoignable.' },
    ],
  }

  it('serveur e-mail, e-mail de test, monitoring de chaque application avec lien', async () => {
    settingsApi.integrations.mockResolvedValue(DATA)
    settingsApi.sendTestEmail.mockResolvedValue({ sentTo: 'admin@loadpilot.local' })
    const { container } = render(<MemoryRouter><SettingsIntegrations embedded /></MemoryRouter>)
    expect(await screen.findByText('Mailpit (développement)')).toBeTruthy()
    expect(screen.getByText('Joignable')).toBeTruthy()
    // Même expéditeur pour LoadPilot et pour le service de connexion (Mailpit en développement).
    expect(screen.getAllByText('LoadPilot <no-reply@loadpilot.local>', { selector: 'dd' })).toHaveLength(2)
    expect(screen.getByText(/2 applications configurées · 1 ne répond pas/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Banking API' }).getAttribute('href')).toBe('/applications?q=Banking%20API')
    expect(screen.getByText('Ne répond pas')).toBeTruthy()
    expect(screen.getByText('Endpoint injoignable.')).toBeTruthy()
    expect(container.textContent).not.toMatch(/Slack|Teams|PagerDuty|Non implémenté|Mode sombre|password|mot de passe :/i)
    fireEvent.click(screen.getByRole('button', { name: /Envoyer un e-mail de test/ }))
    await waitFor(() => expect(toast.showToast).toHaveBeenCalledWith(
      'E-mail de test envoyé à admin@loadpilot.local : visible dans Mailpit (http://localhost:8025).', 'success'))
  })

  it('réservé au Super administrateur', () => {
    auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
    render(<MemoryRouter><SettingsIntegrations embedded /></MemoryRouter>)
    expect(screen.getByText('Cette page est réservée au Super administrateur.')).toBeTruthy()
    expect(settingsApi.integrations).not.toHaveBeenCalled()
  })
})
