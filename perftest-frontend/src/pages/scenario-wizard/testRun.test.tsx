import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { BackendTestRunStepResult } from '../../types/backendContracts'
import { failureHint, formatBytes, statusTone, tokenizeJson, urlMismatch } from './testRunModel'
import TestRunPanel, { INITIAL_TEST_RUN, TestRunState } from './TestRunPanel'
import { DraftStep, newStep } from './wizardModel'

function result(partial: Partial<BackendTestRunStepResult>): BackendTestRunStepResult {
  return {
    index: 1,
    stepId: 's1',
    name: 'Étape',
    status: 'PASSED',
    success: true,
    error: null,
    errorKind: null,
    request: { method: 'GET', url: 'http://localhost:8888/api/account/balance', headers: { Authorization: '••••••' }, body: null, followRedirects: true, timeoutSeconds: 10 },
    response: { status: 200, statusText: 'OK', durationMs: 42, sizeBytes: 2048, contentType: 'application/json', headers: { 'content-type': 'application/json' }, body: '{"success":true,"balance":1500}', truncated: false, json: true },
    checks: { status: { expected: '200', actual: 200, ok: true }, assertion: null, capture: null },
    urlWarning: null,
    warnings: [],
    ...partial,
  }
}

const withResponse = (status: number) => result({ response: { ...result({}).response!, status } })

describe('testRunModel', () => {
  it('colore le statut par famille : 2xx vert, 3xx bleu, 4xx orange, 5xx/erreur rouge', () => {
    expect(statusTone(withResponse(201))).toBe('ok')
    expect(statusTone(withResponse(302))).toBe('info')
    expect(statusTone(withResponse(401))).toBe('warn')
    expect(statusTone(withResponse(500))).toBe('ko')
    expect(statusTone(result({ response: null, status: 'FAILED', success: false }))).toBe('ko')
    expect(statusTone(result({ status: 'SKIPPED', response: null }))).toBe('neutral')
  })

  it('propose une piste selon la cause', () => {
    expect(failureHint({ ...withResponse(401), success: false, errorKind: 'HTTP_STATUS' })).toMatch(/authentification de l’application/)
    expect(failureHint(result({ success: false, response: null, errorKind: 'CONNECTION_REFUSED' }))).toMatch(/lancée sur ce port/)
    expect(failureHint(result({ success: false, response: null, errorKind: 'UNRESOLVED_VARIABLE' }))).toMatch(/Capturez/)
    expect(failureHint(result({}))).toBeNull()
  })

  it('détecte une URL absolue qui ne vise pas l’application et propose le chemin relatif', () => {
    expect(urlMismatch('http://localhost:8888', 'http://localhost:8000/api/auth/login')).toEqual({
      stepOrigin: 'http://localhost:8000',
      appOrigin: 'http://localhost:8888',
      suggestedPath: '/api/auth/login',
    })
    expect(urlMismatch('http://localhost:8888', '/api/auth/login')).toBeNull()
    expect(urlMismatch('http://localhost:8888/', 'http://localhost:8888/api/x')).toBeNull()
    expect(urlMismatch('https://api.test', 'https://api.test:443/x')).toBeNull()
  })

  it('formate les tailles et découpe le JSON en jetons', () => {
    expect(formatBytes(512)).toBe('512 o')
    expect(formatBytes(2048)).toBe('2,0 Ko')
    const kinds = tokenizeJson('{"a": 1, "b": "x", "c": true, "d": null}').filter((t) => t.kind !== 'plain').map((t) => t.kind)
    expect(kinds).toEqual(['key', 'number', 'key', 'string', 'key', 'boolean', 'key', 'null'])
  })
})

describe('TestRunPanel', () => {
  const steps: DraftStep[] = [
    { ...newStep(), uid: 'a', name: 'Consulter le solde', method: 'GET', url: '/api/account/balance' },
    { ...newStep(), uid: 'b', name: 'Connexion', method: 'POST', url: 'http://localhost:8000/api/auth/login' },
  ]
  const props = {
    steps,
    baseUrl: 'http://localhost:8888',
    stale: false,
    onRun: vi.fn(),
    onRunStep: vi.fn(),
    onEditStep: vi.fn(),
    onUseRelativeUrl: vi.fn(),
  }

  it('affiche le résumé, la réponse JSON et la piste de correction', () => {
    const run: TestRunState = {
      ...INITIAL_TEST_RUN,
      phase: 'done',
      durationMs: 320,
      resultsByUid: {
        a: result({}),
        b: result({
          status: 'FAILED', success: false, error: 'Connexion refusée (serveur arrêté ou port fermé) — http://localhost:8000.',
          errorKind: 'CONNECTION_REFUSED', response: null,
          urlWarning: { message: 'Cette étape appelle http://localhost:8000 alors que l’application testée est http://localhost:8888.', suggestedPath: '/api/auth/login' },
        }),
      },
    }
    render(<TestRunPanel {...props} run={run} />)

    expect(screen.getByText('1/2')).toBeTruthy()
    expect(screen.getByText('200 OK')).toBeTruthy()
    expect(screen.getByText(/lancée sur ce port/)).toBeTruthy()
    expect(screen.getByText(/« Suivant » sera disponible quand toutes les étapes réussiront/)).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Utiliser /api/auth/login' }))
    expect(props.onUseRelativeUrl).toHaveBeenCalledWith('b', '/api/auth/login')

    // La carte réussie est repliée : on l'ouvre pour voir le corps JSON.
    fireEvent.click(screen.getAllByRole('button', { name: 'Afficher le détail' })[0])
    expect(screen.getByText('"balance"')).toBeTruthy()
    // La carte en échec est ouverte d'office : deux jeux d'onglets, on vise la première carte.
    fireEvent.click(screen.getAllByRole('tab', { name: 'Requête envoyée' })[0])
    expect(screen.getAllByText('••••••').length).toBeGreaterThan(0)
  })

  it('montre l’étape en cours et désactive les boutons pendant le test', () => {
    render(<TestRunPanel {...props} run={{ ...INITIAL_TEST_RUN, phase: 'running', scope: 'all', runningUid: 'b', runningLabel: 'Connexion' }} />)
    expect(screen.getByText(/Étape 2\/2 en cours : Connexion/)).toBeTruthy()
    expect(screen.getAllByRole('button', { name: /Exécuter/ }).every((b) => (b as HTMLButtonElement).disabled)).toBe(true)
  })
})
