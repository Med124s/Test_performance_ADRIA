import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import LiveInjector from './LiveInjector'
import { BackendInjectorStatus } from '../types/backendContracts'

const injector = (patch: Partial<BackendInjectorStatus> = {}): BackendInjectorStatus => ({
  cpuPercent: 42.5, cpuMaxPercent: 61, machineCpuPercent: 70, heapUsedMb: 512, heapMaxMb: 4096,
  activeVirtualThreads: 120, platformThreads: 48, cpuWarningPercent: 85, overloaded: false, warning: null, ...patch,
})

describe('Suivi en direct : injecteur (LoadPilot)', () => {
  it('CPU, mémoire et threads virtuels actifs, sans alerte sous le seuil', () => {
    render(<LiveInjector injector={injector()} />)
    const panel = screen.getByTestId('live-injector')
    expect(panel.textContent).toMatch(/CPU 42,5\s%/)
    expect(panel.textContent).toMatch(/max 61\s%/)
    expect(panel.textContent).toMatch(/Mémoire 512 \/ 4\s096 Mo/)
    expect(panel.textContent).toMatch(/120 threads virtuels actifs/)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('injecteur saturé : avertissement visible', () => {
    render(<LiveInjector injector={injector({ cpuPercent: 91, cpuMaxPercent: 93, overloaded: true,
      warning: 'Injecteur saturé : le CPU de LoadPilot a atteint 93 % (seuil 85 %).' })} />)
    expect(screen.getByRole('alert').textContent).toMatch(/Injecteur saturé : le CPU de LoadPilot a atteint 93 %/)
  })

  it('rien hors exécution en cours', () => {
    const { container } = render(<LiveInjector injector={null} />)
    expect(container.textContent).toBe('')
  })
})
