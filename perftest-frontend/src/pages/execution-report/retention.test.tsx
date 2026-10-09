import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendResultsRetention } from '../../types/backendContracts'

const auth = vi.hoisted(() => ({ roles: ['ROLE_PERFORMANCE_ENGINEER'] }))
vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ authProvider: 'keycloak', rawRoles: auth.roles }) }))
const executions = vi.hoisted(() => ({ setKeep: vi.fn() }))
vi.mock('../../services/api/executionsBackend', () => ({ executionsBackendApi: executions }))
const settingsApi = vi.hoisted(() => ({ resultsRetention: vi.fn(), updateResultsRetention: vi.fn() }))
vi.mock('../../services/api/settingsBackend', () => ({ settingsBackendApi: settingsApi }))
const toast = vi.hoisted(() => ({ showToast: vi.fn() }))
vi.mock('../../context/ToastContext', () => ({ useToast: () => toast }))

import { KeepToggle, RetentionNotice } from './Retention'
import ResultsRetentionCard from '../audit/ResultsRetentionCard'
import reportSource from '../ExecutionReport.tsx?raw'
import detailSource from '../ExecutionDetail.tsx?raw'
import listSource from '../Executions.tsx?raw'

const retention = (over: Partial<BackendResultsRetention> = {}): BackendResultsRetention => ({
  keep: false, resultsPurgedAt: null, requestDetailsPurgedAt: null, fromSnapshot: false, notice: null, ...over,
})

beforeEach(() => {
  vi.clearAllMocks()
  auth.roles = ['ROLE_PERFORMANCE_ENGINEER']
  settingsApi.resultsRetention.mockResolvedValue({ resultsDays: 90, requestDetailsDays: 30 })
})

describe('Conservation — mention', () => {
  it('affiche la mention du serveur quand le détail a été supprimé, rien sinon', () => {
    const { container, rerender } = render(<RetentionNotice retention={retention()} />)
    expect(container.textContent).toBe('')
    rerender(<RetentionNotice retention={retention({ resultsPurgedAt: '2026-10-01T03:45:00Z', fromSnapshot: true,
      notice: 'Détail des requêtes supprimé après 90 jours, résumé conservé.' })} />)
    expect(screen.getByRole('note').textContent).toBe('Détail des requêtes supprimé après 90 jours, résumé conservé.')
  })

  it('rapport, détail et liste affichent la conservation', () => {
    expect(reportSource).toMatch(/<RetentionNotice retention=\{report\.retention\}/)
    expect(reportSource).toMatch(/<KeepToggle executionId=\{id\}/)
    expect(detailSource).toMatch(/<RetentionNotice retention=\{execution\.retention\}/)
    expect(listSource).toMatch(/'Ne plus conserver' : 'Conserver'/)
    expect(listSource).toMatch(/Résumé seul/)
  })
})

describe('Conservation — bouton « Conserver »', () => {
  it('ingénieur : conserve puis retire la conservation', async () => {
    executions.setKeep.mockResolvedValueOnce(retention({ keep: true }))
    const onChange = vi.fn()
    const { rerender } = render(<KeepToggle executionId="ex-1" retention={retention()} onChange={onChange} />)
    fireEvent.click(screen.getByRole('button', { name: /Conserver/ }))
    await waitFor(() => expect(executions.setKeep).toHaveBeenCalledWith('ex-1', true))
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ keep: true }))
    expect(toast.showToast).toHaveBeenCalledWith(expect.stringContaining('ne seront jamais supprimés'), 'success')

    rerender(<KeepToggle executionId="ex-1" retention={retention({ keep: true })} onChange={onChange} />)
    const pressed = screen.getByRole('button', { name: /Conservée/ })
    expect(pressed.getAttribute('aria-pressed')).toBe('true')
    executions.setKeep.mockResolvedValueOnce(retention({ keep: false }))
    fireEvent.click(pressed)
    await waitFor(() => expect(executions.setKeep).toHaveBeenLastCalledWith('ex-1', false))
  })

  it('absent une fois le détail supprimé ; lecteur : simple badge', () => {
    const { container, rerender } = render(<KeepToggle executionId="ex-1" retention={retention({ resultsPurgedAt: '2026-10-01T00:00:00Z' })} onChange={vi.fn()} />)
    expect(container.textContent).toBe('')
    auth.roles = ['ROLE_VIEWER']
    rerender(<KeepToggle executionId="ex-1" retention={retention({ keep: true })} onChange={vi.fn()} />)
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByText('Conservée')).toBeTruthy()
  })

  it('erreur du serveur affichée', async () => {
    const { ApiError } = await import('../../services/api/httpClient')
    executions.setKeep.mockRejectedValue(new ApiError('Le détail des requêtes de cette exécution a déjà été supprimé : seul le résumé est conservé.', 409))
    render(<KeepToggle executionId="ex-1" retention={retention()} onChange={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /Conserver/ }))
    await waitFor(() => expect(toast.showToast).toHaveBeenCalledWith(expect.stringContaining('seul le résumé est conservé'), 'danger'))
  })
})

describe('Paramètres > Système — Conservation des résultats', () => {
  const renderCard = () => render(<MemoryRouter><ResultsRetentionCard /></MemoryRouter>)

  it('90 / 30 jours par défaut ; enregistrement', async () => {
    settingsApi.updateResultsRetention.mockResolvedValue({ resultsDays: 120, requestDetailsDays: 15 })
    renderCard()
    const results = await screen.findByRole('textbox', { name: /Résultats détaillés/ }) as HTMLInputElement
    const details = screen.getByRole('textbox', { name: /En-têtes et corps/ }) as HTMLInputElement
    expect(results.value).toBe('90')
    expect(details.value).toBe('30')
    const save = screen.getByRole('button', { name: 'Enregistrer' }) as HTMLButtonElement
    expect(save.disabled).toBe(true)
    fireEvent.change(results, { target: { value: '120' } })
    fireEvent.change(details, { target: { value: '15' } })
    fireEvent.click(save)
    await waitFor(() => expect(settingsApi.updateResultsRetention).toHaveBeenCalledWith({ resultsDays: 120, requestDetailsDays: 15 }))
    expect(toast.showToast).toHaveBeenCalledWith('Durées de conservation enregistrées.', 'success')
  })

  it('en-têtes et corps plus longs que les résultats, ou hors limites : refusé', async () => {
    renderCard()
    const details = await screen.findByRole('textbox', { name: /En-têtes et corps/ })
    fireEvent.change(details, { target: { value: '120' } })
    expect(screen.getByText('Les en-têtes et corps ne peuvent pas être conservés plus longtemps que les résultats.')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Enregistrer' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.change(details, { target: { value: '30' } })
    fireEvent.change(screen.getByRole('textbox', { name: /Résultats détaillés/ }), { target: { value: '3' } })
    expect(screen.getByText('Indiquez un nombre de jours dans les limites indiquées.')).toBeTruthy()
  })
})
