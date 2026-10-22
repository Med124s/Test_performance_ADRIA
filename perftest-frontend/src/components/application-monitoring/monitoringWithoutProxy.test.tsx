import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

vi.mock('../../services/api/applicationsBackend', () => ({ applicationsBackendApi: { testMonitoring: vi.fn() } }))

import MonitoringFields from './MonitoringFields'
import { EMPTY_MONITORING_FORM, MonitoringForm, monitoringFormToRequest } from './monitoringModel'
import NetworkFields from '../application-network/NetworkFields'
import { EMPTY_NETWORK_FORM, NetworkForm, networkFormToRequest } from '../application-network/networkModel'
import { BackendProxyMode } from '../../types/backendContracts'

// « Monitoring sans proxy » n'a d'effet que si l'application a un proxy (onglet Réseau) : grisée et
// expliquée sous « Aucun proxy », active sinon, et mise à jour dès que le mode réseau change.

/** Les deux onglets du formulaire d'application, reliés comme dans la page (Applications.tsx). */
function Harness({ mode, withoutProxy = false }: { mode: BackendProxyMode; withoutProxy?: boolean }) {
  const [network, setNetwork] = useState<NetworkForm>({ ...EMPTY_NETWORK_FORM, proxyMode: mode })
  const [monitoring, setMonitoring] = useState<MonitoringForm>({
    ...EMPTY_MONITORING_FORM, type: 'PROMETHEUS', url: 'http://localhost:8888/metrics', withoutProxy,
  })
  return (
    <>
      <NetworkFields value={network} onChange={setNetwork} errors={{}} showErrors={false} />
      <MonitoringFields value={monitoring} onChange={setMonitoring} errors={{}} showErrors={false} appUrl="http://localhost:8888"
        network={networkFormToRequest(network)} />
      <output data-testid="request">{JSON.stringify(monitoringFormToRequest(monitoring))}</output>
    </>
  )
}

const checkbox = () => screen.getByLabelText('Monitoring sans proxy') as HTMLInputElement
const hint = () => screen.queryByText(/Sans effet : aucun proxy n’est configuré pour cette application/)

describe('« Monitoring sans proxy » selon le mode réseau', () => {
  it.each<BackendProxyMode>(['INHERIT', 'CUSTOM'])('active et modifiable avec un proxy (%s)', (mode) => {
    render(<Harness mode={mode} />)
    expect(checkbox().disabled).toBe(false)
    expect(hint()).toBeNull()
    fireEvent.click(checkbox())
    expect(checkbox().checked).toBe(true)
  })

  it('grisée, non modifiable et expliquée avec « Aucun proxy »', () => {
    render(<Harness mode="NONE" />)
    expect(checkbox().disabled).toBe(true)
    expect(checkbox().getAttribute('aria-describedby')).toBe('mon-without-proxy-hint')
    expect(hint()).toBeTruthy()
    // Clic sans effet sur la valeur du formulaire (jsdom bascule l'element DOM meme desactive, pas un navigateur).
    fireEvent.click(checkbox())
    expect(JSON.parse(screen.getByTestId('request').textContent ?? '{}').withoutProxy).toBe(false)
  })

  it('changement de mode réseau après l’avoir cochée : grisée tout de suite, valeur gardée et retrouvée', () => {
    render(<Harness mode="CUSTOM" withoutProxy />)
    expect(checkbox().checked).toBe(true)
    expect(checkbox().disabled).toBe(false)

    fireEvent.click(screen.getByRole('radio', { name: 'Aucun proxy' }))
    expect(checkbox().disabled).toBe(true)
    expect(hint()).toBeTruthy()
    // Valeur conservée (sans effet sans proxy, ignorée aussi par le serveur) : elle reprend effet avec un proxy.
    expect(checkbox().checked).toBe(true)
    expect(JSON.parse(screen.getByTestId('request').textContent ?? '{}').withoutProxy).toBe(true)

    fireEvent.click(screen.getByRole('radio', { name: 'Proxy du serveur' }))
    expect(checkbox().disabled).toBe(false)
    expect(hint()).toBeNull()
    expect(checkbox().checked).toBe(true)
  })

  it('sans information réseau (composant seul), la case reste active', () => {
    render(<MonitoringFields value={{ ...EMPTY_MONITORING_FORM, type: 'PROMETHEUS', url: 'http://x/metrics' }} onChange={() => {}}
      errors={{}} showErrors={false} appUrl="http://x" />)
    expect(checkbox().disabled).toBe(false)
  })
})
