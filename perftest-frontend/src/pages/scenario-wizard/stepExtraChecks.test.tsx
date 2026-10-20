import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import StepEditor from './StepEditor'
import TestResultCard from './TestResultCard'
import { isBlankStep, stepFromBackend } from './draftModel'
import {
  DraftStep, hasContentCheck, isValidJsonPath, jsonAssertionProblem, newStep, stepToRequest, validateStep,
} from './wizardModel'
import type { BackendStepResponse, BackendTestRunStepResult } from '../../types/backendContracts'

// Contrôles optionnels d'une étape : temps de réponse maximal et assertion JSON simple.

const base = () => newStep({ name: 'Solde', url: '/api/balance' })

describe('validation et envoi au serveur', () => {
  it('sans ces contrôles : rien n’est envoyé (comportement d’avant)', () => {
    const req = stepToRequest(base(), 'sc', 1)
    expect(req).not.toHaveProperty('maxResponseTimeMs')
    expect(req).not.toHaveProperty('assertionJsonPath')
    expect(validateStep(base())).toEqual({})
  })

  it('envoie le temps maximal et l’assertion (EQUALS par défaut)', () => {
    const req = stepToRequest({ ...base(), maxResponseTimeMs: '800', assertionJsonPath: ' $.status ', assertionJsonValue: 'OK' }, 'sc', 1)
    expect(req.maxResponseTimeMs).toBe(800)
    expect(req.assertionJsonPath).toBe('$.status')
    expect(req.assertionJsonOperator).toBe('EQUALS')
    expect(req.assertionJsonValue).toBe('OK')
  })

  it('refuse un temps invalide et une assertion incomplète, avec les mêmes règles que le serveur', () => {
    expect(validateStep({ ...base(), maxResponseTimeMs: '0' }).maxResponseTimeMs).toMatch(/au moins 1/)
    expect(validateStep({ ...base(), maxResponseTimeMs: 'abc' }).maxResponseTimeMs).toMatch(/nombre entier/)
    expect(jsonAssertionProblem({ assertionJsonValue: 'OK' })?.message).toBe('Indiquez le champ à vérifier (ex : $.status).')
    expect(jsonAssertionProblem({ assertionJsonPath: 'items[x]' })?.field).toBe('assertionJsonPath')
    expect(jsonAssertionProblem({ assertionJsonPath: '$.msg', assertionJsonOperator: 'CONTAINS', assertionJsonValue: '' })?.field).toBe('assertionJsonValue')
    expect(jsonAssertionProblem({ assertionJsonPath: '$.status', assertionJsonOperator: 'EQUALS', assertionJsonValue: '' })).toBeNull()
    expect(isValidJsonPath('data.items[0].id')).toBe(true)
    expect(isValidJsonPath('$[0].id')).toBe(true)
    expect(isValidJsonPath('items[-1]')).toBe(false)
  })

  it('relit les contrôles enregistrés ; une assertion JSON compte comme vérification de contenu', () => {
    const step = stepFromBackend({
      id: 's1', scenarioId: 'sc', scenarioName: 'S', name: 'Solde', method: 'GET', url: '/api/balance', headers: null, body: null,
      order: 1, expectedStatus: 200, thinkTimeMs: null, timeoutSeconds: null, followRedirects: null, assertionBodyContains: null,
      captureVariableName: null, captureJsonPath: null, pathParams: null, queryParams: null, status: 'ACTIVE',
      createdAt: '', updatedAt: '', maxResponseTimeMs: 500, assertionJsonPath: '$.items', assertionJsonOperator: 'CONTAINS', assertionJsonValue: 'a',
    } as BackendStepResponse)
    expect(step.maxResponseTimeMs).toBe('500')
    expect(step.assertionJsonOperator).toBe('CONTAINS')
    expect(hasContentCheck(step)).toBe(true)
    expect(isBlankStep({ ...newStep(), maxResponseTimeMs: '500' })).toBe(false)
  })
})

function Harness({ initial }: { initial: DraftStep }) {
  const [step, setStep] = useState(initial)
  return (
    <>
      <StepEditor step={step} index={0} baseUrl="http://localhost:8888" errors={{}} showErrors={false} variables={[]}
        laterCaptures={new Map()} hasCsv={false} onChange={(patch) => setStep((s) => ({ ...s, ...patch }))} />
      <output data-testid="request">{JSON.stringify(stepToRequest(step, 'sc', 1))}</output>
    </>
  )
}

describe('éditeur d’étape', () => {
  it('saisit l’assertion JSON et le temps maximal dans l’onglet Validation', () => {
    render(<Harness initial={base()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter une vérification' }))
    fireEvent.change(screen.getByLabelText('Champ JSON à vérifier'), { target: { value: '$.status' } })
    fireEvent.change(screen.getByLabelText('Comparaison de l’assertion JSON'), { target: { value: 'CONTAINS' } })
    fireEvent.change(screen.getByLabelText('Valeur attendue de l’assertion JSON'), { target: { value: 'OK' } })
    fireEvent.change(screen.getByLabelText('Temps de réponse maximal (ms)'), { target: { value: '750' } })
    const req = JSON.parse(screen.getByTestId('request').textContent ?? '{}')
    expect(req).toMatchObject({ assertionJsonPath: '$.status', assertionJsonOperator: 'CONTAINS', assertionJsonValue: 'OK', maxResponseTimeMs: 750 })
    expect(screen.queryByTestId('no-content-check'), 'l’assertion JSON compte comme vérification').toBeNull()
  })
})

describe('résultat de « Tester »', () => {
  it('affiche les deux contrôles et la raison d’un échec', () => {
    const result = {
      index: 0, stepId: 's1', name: 'Solde', status: 'FAILED', success: false,
      error: 'Assertion JSON : $.status vaut « KO », attendu « OK ».', errorKind: 'ASSERTION',
      request: { method: 'GET', url: 'http://x/api', headers: {}, body: null, followRedirects: true, timeoutSeconds: 30 },
      response: { status: 200, statusText: 'OK', durationMs: 912, sizeBytes: 15, contentType: 'application/json', headers: {}, body: '{"status":"KO"}', truncated: false, json: true },
      checks: {
        status: { expected: '200', actual: 200, ok: true }, assertion: null, capture: null,
        responseTime: { maxMs: 500, actualMs: 912, ok: false },
        json: { path: '$.status', operator: 'EQUALS', expected: 'OK', ok: false, message: 'Assertion JSON : $.status vaut « KO », attendu « OK ».' },
      },
      urlWarning: null, warnings: [],
    } as unknown as BackendTestRunStepResult
    render(<TestResultCard step={base()} index={0} baseUrl="http://x" result={result} running={false} disabled={false}
      onRunStep={() => {}} onEditStep={() => {}} onUseRelativeUrl={() => {}} />)
    fireEvent.click(screen.getByRole('tab', { name: /Vérifications/ }))
    expect(screen.getByText('Assertion JSON')).toBeTruthy()
    expect(screen.getAllByText(/vaut « KO », attendu « OK »/).length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('Temps de réponse')).toBeTruthy()
    expect(screen.getByText('Temps de réponse').closest('li')?.textContent).toMatch(/maximum 500 ms · mesuré 912 ms/)
  })
})
