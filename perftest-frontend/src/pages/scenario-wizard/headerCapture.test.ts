import { describe, expect, it } from 'vitest'
import { headerCaptureProblem, joinHeaderCapture, splitHeaderCapture } from '../../utils/headerCapture'
import { newStep, stepToRequest, validateStep } from './wizardModel'
import { stepFromBackend } from './draftModel'
import type { BackendStepResponse } from '../../types/backendContracts'

describe('Capture depuis un en-tête de réponse', () => {
  it('compose et découpe « Set-Cookie:NOM » ; un autre en-tête garde son seul nom', () => {
    expect(joinHeaderCapture('Set-Cookie', 'SESSIONID')).toBe('Set-Cookie:SESSIONID')
    expect(joinHeaderCapture('set-cookie', '')).toBe('set-cookie')
    expect(joinHeaderCapture('X-CSRF-Token', 'ignoré')).toBe('X-CSRF-Token')
    expect(splitHeaderCapture('Set-Cookie:SESSIONID')).toEqual({ header: 'Set-Cookie', cookie: 'SESSIONID' })
    expect(splitHeaderCapture('X-CSRF-Token')).toEqual({ header: 'X-CSRF-Token', cookie: '' })
  })

  it('valide le nom de l’en-tête et du cookie (mêmes règles que le serveur)', () => {
    expect(headerCaptureProblem('X-CSRF-Token')).toBeNull()
    expect(headerCaptureProblem('x-csrf-token')).toBeNull()
    expect(headerCaptureProblem('Set-Cookie:SESSIONID')).toBeNull()
    expect(headerCaptureProblem('X CSRF')).toBe("nom d'en-tête invalide (pas d'espace ni de caractère spécial, ex. X-CSRF-Token)")
    expect(headerCaptureProblem('X-CSRF-Token:abc')).toBe("un nom de cookie ne s'indique que pour Set-Cookie (ex. Set-Cookie:SESSIONID)")
    expect(headerCaptureProblem('Set-Cookie:')).toBe('nom de cookie invalide (ex. Set-Cookie:SESSIONID)')
  })

  it('validateStep : en-tête obligatoire et valide quand la source est « En-tête »', () => {
    const base = { name: 'Formulaire', url: '/form', captureVariableName: 'csrf', captureSource: 'HEADER' as const }
    expect(validateStep(newStep({ ...base, captureJsonPath: 'X-CSRF-Token' })).captureJsonPath).toBeUndefined()
    expect(validateStep(newStep({ ...base, captureJsonPath: '' })).captureJsonPath).toBe('Indiquez le nom de l’en-tête (ex : X-CSRF-Token).')
    expect(validateStep(newStep({ ...base, captureJsonPath: 'X CSRF' })).captureJsonPath)
      .toBe("Nom d'en-tête invalide (pas d'espace ni de caractère spécial, ex. X-CSRF-Token).")
  })

  it('aucune régression JSON : sans source, chemin JSON validé comme avant et aucune source envoyée', () => {
    const step = newStep({ name: 'Connexion', url: '/login', captureVariableName: 'token', captureJsonPath: '$.data.accounts[0].id' })
    expect(validateStep(step).captureJsonPath).toBeUndefined()
    expect(validateStep({ ...step, captureJsonPath: '' }).captureJsonPath).toBe('Indiquez où lire la valeur (ex : $.token).')
    const request = stepToRequest(step, 'sc-1', 1)
    expect(request.captureJsonPath).toBe('$.data.accounts[0].id')
    expect('captureSource' in request).toBe(false)
  })

  it('stepToRequest et stepFromBackend conservent la source « En-tête »', () => {
    const step = newStep({ name: 'Formulaire', url: '/form', captureVariableName: 'sid', captureJsonPath: 'Set-Cookie:SESSIONID', captureSource: 'HEADER' })
    expect(stepToRequest(step, 'sc-1', 1)).toMatchObject({ captureSource: 'HEADER', captureJsonPath: 'Set-Cookie:SESSIONID' })

    const backend = {
      id: 's-1', scenarioId: 'sc-1', scenarioName: 'Scénario', name: 'Formulaire', method: 'GET', url: '/form', headers: null, body: null,
      order: 1, expectedStatus: 200, thinkTimeMs: null, timeoutSeconds: null, followRedirects: null, assertionBodyContains: null,
      captureVariableName: 'sid', captureJsonPath: 'Set-Cookie:SESSIONID', captureSource: 'HEADER', pathParams: null, queryParams: null,
      status: 'ACTIVE', createdAt: '2026-10-16T00:00:00Z', updatedAt: '2026-10-16T00:00:00Z',
    } as BackendStepResponse
    expect(stepFromBackend(backend).captureSource).toBe('HEADER')
    expect(stepFromBackend({ ...backend, captureSource: null, captureJsonPath: '$.token' }).captureSource).toBeUndefined()
  })
})
