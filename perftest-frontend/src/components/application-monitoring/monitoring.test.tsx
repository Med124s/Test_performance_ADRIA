import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BackendExecutionResources } from '../../types/backendContracts'

const api = vi.hoisted(() => ({ testMonitoring: vi.fn() }))
vi.mock('../../services/api/applicationsBackend', () => ({ applicationsBackendApi: api }))

import MonitoringFields from './MonitoringFields'
import LiveResources from './LiveResources'
import {
  EMPTY_MONITORING_FORM, MonitoringForm, monitoringFormFromResponse, monitoringFormToRequest, sparklinePaths, suggestedMonitoringUrl,
  validateMonitoringForm,
} from './monitoringModel'

beforeEach(() => vi.clearAllMocks())

describe('monitoringModel', () => {
  it('URL proposée, conversion formulaire ↔ requête, secret conservé en modification', () => {
    expect(suggestedMonitoringUrl('PROMETHEUS', 'http://localhost:8888/')).toBe('http://localhost:8888/metrics')
    expect(suggestedMonitoringUrl('ACTUATOR', 'http://localhost:8080')).toBe('http://localhost:8080/actuator')
    const existing = { type: 'PROMETHEUS' as const, url: 'http://h/metrics', authType: 'BEARER' as const, headerName: null, secretConfigured: true, intervalSeconds: 10 }
    const form = monitoringFormFromResponse(existing)
    expect(form).toEqual({ type: 'PROMETHEUS', url: 'http://h/metrics', authType: 'BEARER', headerName: '', secret: '', intervalSeconds: '10' })
    expect(validateMonitoringForm(form, existing)).toEqual({})
    expect(validateMonitoringForm(form, null).secret).toBe('Indiquez le jeton.')
    expect(monitoringFormToRequest(form)).toEqual({ type: 'PROMETHEUS', url: 'http://h/metrics', authType: 'BEARER', headerName: null, secret: null, intervalSeconds: 10 })
    expect(validateMonitoringForm({ ...EMPTY_MONITORING_FORM, type: 'ACTUATOR', url: 'ftp://x', intervalSeconds: '0' })).toEqual({
      url: 'URL invalide : elle doit commencer par http:// ou https://.', intervalSeconds: 'Entre 1 et 60 secondes.',
    })
    expect(validateMonitoringForm(EMPTY_MONITORING_FORM)).toEqual({})
  })

  it('mini-courbe : interrompue par un trou, vide sans valeur, à mi-hauteur si constante', () => {
    expect(sparklinePaths([20, 25, null, 40], 120, 28)).toHaveLength(2)
    expect(sparklinePaths([null, null], 120, 28)).toEqual([])
    expect(sparklinePaths([5, 5, 5], 120, 28)[0]).toBe('M2.0,14.0 L60.0,14.0 L118.0,14.0')
  })
})

function Harness() {
  const [value, setValue] = useState<MonitoringForm>(EMPTY_MONITORING_FORM)
  return <MonitoringFields value={value} onChange={setValue} errors={validateMonitoringForm(value)} showErrors={false} appUrl="http://localhost:8888" />
}

describe('section Monitoring du formulaire', () => {
  it('Prometheus propose /metrics, « Tester le monitoring » affiche le CPU et la RAM lus, puis une erreur claire', async () => {
    render(<Harness />)
    expect(screen.queryByLabelText('URL du monitoring *')).toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: /Prometheus/ }))
    expect((screen.getByLabelText('URL du monitoring *') as HTMLInputElement).value).toBe('http://localhost:8888/metrics')
    expect(screen.getByText(/ni la machine entière, ni LoadPilot/)).toBeTruthy()

    api.testMonitoring.mockResolvedValueOnce({ success: true, message: 'Monitoring opérationnel : CPU 12.5 % d’un cœur, RAM 64.2 Mo.', cpuPercent: 12.5, memoryMb: 64.2, memoryMetric: 'process_resident_memory_bytes' })
    fireEvent.click(screen.getByRole('button', { name: /Tester le monitoring/ }))
    expect((await screen.findByRole('status')).textContent).toMatch(/CPU 12,5\s%.*RAM 64\sMo/)
    expect(api.testMonitoring).toHaveBeenCalledWith(
      { type: 'PROMETHEUS', url: 'http://localhost:8888/metrics', authType: 'NONE', headerName: null, secret: null, intervalSeconds: 5 }, undefined)

    api.testMonitoring.mockResolvedValueOnce({ success: false, message: 'Monitoring injoignable (connexion refusée) : http://localhost:8888/metrics.', cpuPercent: null, memoryMb: null, memoryMetric: null })
    fireEvent.click(screen.getByRole('button', { name: /Tester le monitoring/ }))
    expect((await screen.findByText(/Monitoring injoignable \(connexion refusée\)/)).textContent).toBeTruthy()
  })
})

const resources = (patch: Partial<BackendExecutionResources> = {}): BackendExecutionResources => ({
  source: 'PROMETHEUS', intervalSeconds: 5, cpuPercent: 48.2, memoryMb: 101.5, cpuAvgPercent: 40, cpuMaxPercent: 61.3, memoryMaxMb: 102,
  message: null, cpuHistory: [30, 40, 48.2], memoryHistory: [99, 100, 101.5], ...patch,
})

describe('suivi en direct', () => {
  it('CPU et RAM actuels du processus testé, maximum et mini-courbes ; résumé après la fin ; message si indisponible', () => {
    const { rerender } = render(<LiveResources resources={resources()} finished={false} />)
    expect(screen.getByText('48,2 %')).toBeTruthy()
    expect(screen.getByText(/RAM actuelle · max 102\sMo/)).toBeTruthy()
    expect(screen.getByText(/CPU actuel \(% d’un cœur\) · max 61,3 %/)).toBeTruthy()
    expect(screen.getAllByRole('img')).toHaveLength(2)

    rerender(<LiveResources resources={resources({ source: null, cpuPercent: null, memoryMb: null, cpuHistory: [], memoryHistory: [] })} finished />)
    expect(screen.getByText('40 %')).toBeTruthy()
    expect(screen.getByText(/CPU moyen/)).toBeTruthy()

    rerender(<LiveResources resources={resources({ cpuPercent: null, memoryMb: null, cpuAvgPercent: null, cpuMaxPercent: null, memoryMaxMb: null, cpuHistory: [], memoryHistory: [],
      message: 'Monitoring indisponible : Monitoring injoignable (connexion refusée) : http://localhost:8888/metrics. L’exécution a continué normalement, sans CPU ni RAM.' })} finished={false} />)
    expect(screen.getByRole('status').textContent).toMatch(/L’exécution a continué normalement/)

    rerender(<LiveResources resources={null} finished={false} />)
    expect(screen.queryByTestId('live-resources')).toBeNull()
  })
})
