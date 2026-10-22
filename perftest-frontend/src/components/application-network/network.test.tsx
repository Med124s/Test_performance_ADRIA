import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import NetworkFields from './NetworkFields'
import { EMPTY_NETWORK_FORM, NetworkForm, networkFormFromResponse, networkFormToRequest, validateNetworkForm } from './networkModel'

const custom = (patch: Partial<NetworkForm> = {}): NetworkForm => ({
  proxyMode: 'CUSTOM', proxyHost: 'proxy.banque.local', proxyPort: '8080', proxyUsername: 'agent', proxyPassword: 's3cret', ...patch,
})

describe('Réseau d’une application : modèle', () => {
  it('nouvelle application : aucun proxy par défaut', () => {
    expect(EMPTY_NETWORK_FORM.proxyMode).toBe('NONE')
    expect(networkFormFromResponse(undefined)).toEqual(EMPTY_NETWORK_FORM)
    expect(networkFormToRequest(EMPTY_NETWORK_FORM)).toEqual({ proxyMode: 'NONE', proxyHost: null, proxyPort: null, proxyUsername: null, proxyPassword: null })
    expect(validateNetworkForm(EMPTY_NETWORK_FORM)).toEqual({})
  })

  it('application existante : son mode est conservé, et sans mode enregistré c’est le proxy du serveur', () => {
    const base = { proxyHost: null, proxyPort: null, proxyUsername: null, proxyPasswordConfigured: false }
    expect(networkFormFromResponse({ ...base, proxyMode: 'INHERIT' }).proxyMode).toBe('INHERIT')
    expect(networkFormFromResponse({ ...base, proxyMode: null as unknown as 'INHERIT' }).proxyMode).toBe('INHERIT')
  })

  it('proxy de l’application : envoi au serveur, mot de passe vide = conservé, jamais réaffiché', () => {
    expect(networkFormToRequest(custom())).toEqual({ proxyMode: 'CUSTOM', proxyHost: 'proxy.banque.local', proxyPort: 8080, proxyUsername: 'agent', proxyPassword: 's3cret' })
    expect(networkFormToRequest(custom({ proxyPassword: '' })).proxyPassword).toBeNull()
    // Sans identifiant, aucun mot de passe n'est envoyé.
    expect(networkFormToRequest(custom({ proxyUsername: ' ' })).proxyPassword).toBeNull()
    // Aucun proxy : hôte et identifiant non envoyés.
    expect(networkFormToRequest(custom({ proxyMode: 'NONE' }))).toEqual({ proxyMode: 'NONE', proxyHost: null, proxyPort: null, proxyUsername: null, proxyPassword: null })
    expect(networkFormFromResponse({ proxyMode: 'CUSTOM', proxyHost: 'p', proxyPort: 3128, proxyUsername: 'u', proxyPasswordConfigured: true }))
      .toEqual({ proxyMode: 'CUSTOM', proxyHost: 'p', proxyPort: '3128', proxyUsername: 'u', proxyPassword: '', tlsCaCertificate: '' })
  })

  it('autorité de certification : envoyée seulement si saisie, relue, et vérifiée comme sur le serveur', () => {
    const pem = '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----'
    expect(networkFormToRequest(EMPTY_NETWORK_FORM)).not.toHaveProperty('tlsCaCertificate')
    expect(networkFormToRequest({ ...EMPTY_NETWORK_FORM, tlsCaCertificate: `  ${pem}\n` }).tlsCaCertificate).toBe(pem)
    expect(networkFormToRequest(custom({ tlsCaCertificate: pem })).tlsCaCertificate).toBe(pem)
    expect(networkFormFromResponse({ proxyMode: 'NONE', proxyHost: null, proxyPort: null, proxyUsername: null, proxyPasswordConfigured: false, tlsCaCertificate: pem })
      .tlsCaCertificate).toBe(pem)
    expect(validateNetworkForm({ ...EMPTY_NETWORK_FORM, tlsCaCertificate: pem })).toEqual({})
    expect(validateNetworkForm({ ...EMPTY_NETWORK_FORM, tlsCaCertificate: 'bonjour' }).tlsCaCertificate).toMatch(/Format PEM attendu/)
    expect(validateNetworkForm({ ...EMPTY_NETWORK_FORM, tlsCaCertificate: '-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----' })
      .tlsCaCertificate).toMatch(/clé privée/)
  })

  it('mêmes règles que le serveur et les scripts de démarrage', () => {
    expect(validateNetworkForm(custom())).toEqual({})
    expect(validateNetworkForm(custom({ proxyHost: '' })).proxyHost).toMatch(/Indiquez l’hôte/)
    expect(validateNetworkForm(custom({ proxyHost: 'http://proxy' })).proxyHost).toMatch(/sans http/)
    expect(validateNetworkForm(custom({ proxyPort: '' })).proxyPort).toMatch(/Indiquez le port/)
    expect(validateNetworkForm(custom({ proxyPort: '70000' })).proxyPort).toBe('Port entre 1 et 65535.')
    expect(validateNetworkForm(custom({ proxyPort: '0' })).proxyPort).toBe('Port entre 1 et 65535.')
    expect(validateNetworkForm(custom({ proxyUsername: 'a:b' })).proxyUsername).toMatch(/« : »/)
  })
})

describe('Réseau d’une application : formulaire', () => {
  it('choix du proxy, champs du proxy de l’application, mot de passe enregistré conservé', () => {
    const onChange = vi.fn()
    const { rerender } = render(<NetworkFields value={EMPTY_NETWORK_FORM} onChange={onChange} errors={{}} showErrors={false} />)
    expect(screen.getByRole('radio', { name: 'Aucun proxy' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('radio', { name: 'Proxy du serveur' }).getAttribute('aria-checked')).toBe('false')
    expect(screen.queryByLabelText(/Hôte du proxy/)).toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: 'Proxy de l’application' }))
    expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY_NETWORK_FORM, proxyMode: 'CUSTOM' })

    rerender(<NetworkFields value={custom({ proxyPassword: '' })} onChange={onChange} errors={{}} showErrors={false}
      existing={{ proxyMode: 'CUSTOM', proxyHost: 'proxy.banque.local', proxyPort: 8080, proxyUsername: 'agent', proxyPasswordConfigured: true }} />)
    expect((screen.getByLabelText(/Hôte du proxy/) as HTMLInputElement).value).toBe('proxy.banque.local')
    const password = screen.getByLabelText(/Mot de passe/) as HTMLInputElement
    expect(password.type).toBe('password')
    expect(password.value).toBe('')
    expect(password.placeholder).toMatch(/enregistré — laisser vide pour conserver/)
  })

  it('autorité de certification : saisie, résumé des certificats enregistrés, retrait', () => {
    const pem = '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----'
    const onChange = vi.fn()
    const { rerender } = render(<NetworkFields value={EMPTY_NETWORK_FORM} onChange={onChange} errors={{}} showErrors={false} />)
    fireEvent.change(screen.getByLabelText(/Autorité de certification TLS/), { target: { value: pem } })
    expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY_NETWORK_FORM, tlsCaCertificate: pem })
    expect(screen.queryByTestId('saved-cas')).toBeNull()

    rerender(<NetworkFields value={{ ...EMPTY_NETWORK_FORM, tlsCaCertificate: pem }} onChange={onChange} errors={{}} showErrors={false}
      existing={{ proxyMode: 'INHERIT', proxyHost: null, proxyPort: null, proxyUsername: null, proxyPasswordConfigured: false, tlsCaCertificate: pem,
        tlsCaCertificates: [{ subject: 'CN=Banque CA', issuer: 'CN=Banque CA', notAfter: '2030-01-01T00:00:00Z', expired: false }] }} />)
    expect(screen.getByTestId('saved-cas').textContent).toMatch(/CN=Banque CA — valable jusqu’au/)
    fireEvent.click(screen.getByRole('button', { name: 'Retirer' }))
    expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY_NETWORK_FORM, tlsCaCertificate: '' })
  })
})
