import { describe, expect, it } from 'vitest'
import { htmlCaptureProblem, joinHtmlCapture, splitHtmlCapture } from '../../utils/htmlCapture'
import { newStep, stepToRequest, validateStep } from './wizardModel'
import { stepFromBackend } from './draftModel'
import type { BackendStepResponse } from '../../types/backendContracts'

describe('Capture depuis une page HTML', () => {
  it('compose et découpe « sélecteur::attr(attribut) » ; sans attribut = texte de l’élément', () => {
    expect(joinHtmlCapture('input[name=csrf_token]', 'value')).toBe('input[name=csrf_token]::attr(value)')
    expect(joinHtmlCapture('span.solde', '')).toBe('span.solde')
    expect(splitHtmlCapture('#compte::attr(data-account-id)')).toEqual({ selector: '#compte', attribute: 'data-account-id' })
    expect(splitHtmlCapture('span.solde')).toEqual({ selector: 'span.solde', attribute: '' })
  })

  it('garde les espaces du sélecteur pendant la saisie (« form input »)', () => {
    let spec = joinHtmlCapture('form ', 'value')
    spec = joinHtmlCapture(splitHtmlCapture(spec).selector + 'input', splitHtmlCapture(spec).attribute)
    expect(spec).toBe('form input::attr(value)')
  })

  it('valide le sélecteur et le nom d’attribut', () => {
    expect(htmlCaptureProblem('input[name=csrf_token]::attr(value)')).toBeNull()
    expect(htmlCaptureProblem('::attr(value)')).toBe('indiquez un sélecteur CSS (ex. input[name=csrf_token])')
    expect(htmlCaptureProblem('input::attr(data account)')).toBe("nom d'attribut invalide (ex. value ou data-account-id)")
  })

  it('validateStep : sélecteur obligatoire quand la source est « Page HTML »', () => {
    const base = { name: 'Formulaire', url: '/login', captureVariableName: 'csrf', captureSource: 'HTML' as const }
    expect(validateStep(newStep({ ...base, captureJsonPath: 'input[name=csrf_token]::attr(value)' })).captureJsonPath).toBeUndefined()
    expect(validateStep(newStep({ ...base, captureJsonPath: '' })).captureJsonPath)
      .toBe('Indiquez le sélecteur CSS de l’élément (ex : input[name=csrf_token]).')
  })

  it('stepToRequest et stepFromBackend conservent la source « Page HTML »', () => {
    const step = newStep({ name: 'Formulaire', url: '/login', captureVariableName: 'csrf', captureJsonPath: 'input[name=csrf_token]::attr(value)', captureSource: 'HTML' })
    expect(stepToRequest(step, 'sc-1', 1)).toMatchObject({ captureSource: 'HTML', captureJsonPath: 'input[name=csrf_token]::attr(value)' })

    const backend = {
      id: 's-1', scenarioId: 'sc-1', scenarioName: 'Scénario', name: 'Formulaire', method: 'GET', url: '/login', headers: null, body: null,
      order: 1, expectedStatus: 200, thinkTimeMs: null, timeoutSeconds: null, followRedirects: null, assertionBodyContains: null,
      captureVariableName: 'csrf', captureJsonPath: 'input[name=csrf_token]::attr(value)', captureSource: 'HTML', pathParams: null, queryParams: null,
      status: 'ACTIVE', createdAt: '2026-10-17T00:00:00Z', updatedAt: '2026-10-17T00:00:00Z',
    } as BackendStepResponse
    expect(stepFromBackend(backend).captureSource).toBe('HTML')
  })
})
