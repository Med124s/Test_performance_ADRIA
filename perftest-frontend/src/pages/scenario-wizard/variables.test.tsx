import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import StepEditor from './StepEditor'
import { DraftStep, newStep, stepToRequest } from './wizardModel'
import {
  availableVariables,
  describeValue,
  detectPathVariables,
  laterCaptures,
  queryRowsFromUrl,
  urlWithQuery,
  validateScenarioVariables,
  valueWarnings,
  variablesFromJson,
  variablesToJson,
} from './variablesModel'

describe('variablesModel', () => {
  it('détecte les variables de chemin :nom et {nom}, jamais le port ni ${…}', () => {
    expect(detectPathVariables('/api/transfers/:transferId/notes/{note}?x=:y')).toEqual(['transferId', 'note'])
    expect(detectPathVariables('http://localhost:8888/api/:id')).toEqual(['id'])
    expect(detectPathVariables('/api/${id}')).toEqual([])
  })

  it('synchronise URL et tableau des paramètres dans les deux sens (désactivés conservés)', () => {
    const rows = queryRowsFromUrl('/api/agent/history?page=1&size=${size}', [{ key: 'debug', value: '1', enabled: false }])
    expect(rows).toEqual([
      { key: 'page', value: '1', enabled: true },
      { key: 'size', value: '${size}', enabled: true },
      { key: 'debug', value: '1', enabled: false },
    ])
    expect(urlWithQuery('/api/agent/history?old=1', rows)).toBe('/api/agent/history?page=1&size=${size}')
    expect(urlWithQuery('/x?a=1', [])).toBe('/x')
  })

  const steps = [
    newStep({ url: '/login', captureVariableName: 'token', captureJsonPath: '$.token' }),
    newStep({ url: '/transfers', captureVariableName: 'transferId', captureJsonPath: '$.transferId' }),
    newStep({ url: '/transfers/:transferId' }),
  ]

  it('liste les variables disponibles par priorité, avec leur source', () => {
    const vars = availableVariables(steps, 2, ['amount', 'token'], [{ key: 'currency', value: 'MAD' }, { key: 'amount', value: '1' }], true)
    expect(vars.map((v) => `${v.name}:${v.label}`)).toEqual([
      'transferId:Capture (étape 2)', 'token:Capture (étape 1)', 'amount:CSV', 'currency:Scénario', 'authToken:Automatique',
    ])
  })

  it('badge Source et avertissements (capture postérieure, absente du CSV, inconnue)', () => {
    const vars = availableVariables(steps, 1, ['amount'], [], false)
    const later = laterCaptures(steps, 1)
    expect(describeValue('150', vars, later)).toEqual({ label: 'Valeur fixe', tone: 'fixed' })
    expect(describeValue('${token}', vars, later)).toEqual({ label: 'Capture (étape 1)', tone: 'ok' })
    expect(describeValue('${transferId}', vars, later)).toEqual({ label: "Capturée à l'étape 2", tone: 'warn' })
    expect(describeValue('${nope}', vars, later).tone).toBe('ko')
    expect(valueWarnings('${transferId}', vars, later, true)[0]).toMatch(/capturée qu’à l’étape 2/)
    expect(valueWarnings('${nope}', vars, later, true)[0]).toMatch(/absente du CSV/)
    expect(valueWarnings('${nope}', vars, later, false)[0]).toMatch(/variable inconnue/)
  })

  it('valide les variables du scénario et signale un doublon avec le CSV', () => {
    const { errors, warnings } = validateScenarioVariables([
      { key: 'currency', value: 'MAD' }, { key: 'mon montant', value: '1' }, { key: 'currency', value: 'EUR' }, { key: 'amount', value: '2' },
    ], ['amount'])
    expect(errors).toEqual([
      'Variable « mon montant » : lettres, chiffres et _ uniquement, sans commencer par un chiffre.',
      'Variable « currency » en double.',
    ])
    expect(warnings[0]).toMatch(/« amount » est aussi une colonne du CSV/)
    expect(variablesFromJson(variablesToJson([{ key: 'currency', value: 'MAD' }, { key: '', value: '' }]))).toEqual([{ key: 'currency', value: 'MAD' }])
  })

  it('envoie au backend le chemin seul, les variables de chemin et les paramètres', () => {
    const req = stepToRequest(newStep({ name: 'Lire', url: '/api/transfers/:transferId?page=1',
      queryParams: [{ key: 'page', value: '1', enabled: true }, { key: 'debug', value: '1', enabled: false }] }), 'sc', 1)
    expect(req.url).toBe('/api/transfers/:transferId')
    expect(req.pathParams).toBe('{"transferId":"${transferId}"}')
    expect(JSON.parse(req.queryParams!)).toEqual([{ key: 'page', value: '1', enabled: true }, { key: 'debug', value: '1', enabled: false }])
  })
})

function Harness({ initial }: { initial: DraftStep }) {
  const [step, setStep] = useState(initial)
  const all = [
    newStep({ url: '/login', captureVariableName: 'token', captureJsonPath: '$.token' }),
    newStep({ url: '/transfers', captureVariableName: 'transferId', captureJsonPath: '$.transferId' }),
    step,
    newStep({ url: '/x', captureVariableName: 'late', captureJsonPath: '$.late' }),
  ]
  return (
    <>
      <StepEditor step={step} index={2} baseUrl="http://localhost:8888" errors={{}} showErrors
        variables={availableVariables(all, 2, ['amount'], [{ key: 'currency', value: 'MAD' }], true)}
        laterCaptures={laterCaptures(all, 2)} hasCsv onChange={(patch) => setStep((s) => ({ ...s, ...patch }))} />
      <output data-testid="url">{step.url}</output>
      <output data-testid="body">{step.body}</output>
    </>
  )
}

describe('StepEditor — onglet Paramètres et insertion de variables', () => {
  it('variables de chemin et paramètres de requête synchronisés avec l’URL, badges Source', () => {
    render(<Harness initial={newStep({ method: 'GET', url: '/api/transfers/:transferId?page=1', queryParams: [{ key: 'page', value: '1', enabled: true }] })} />)
    fireEvent.click(screen.getByRole('tab', { name: /Paramètres/ }))

    expect(screen.getByText('transferId', { selector: 'code' })).toBeTruthy()
    expect(screen.getByText('Capture (étape 2)')).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Valeur du paramètre page'), { target: { value: '${currency}' } })
    expect(screen.getByTestId('url').textContent).toBe('/api/transfers/:transferId?page=${currency}')
    expect(screen.getByText('Scénario')).toBeTruthy()

    fireEvent.click(screen.getByLabelText('Activer le paramètre page'))
    expect(screen.getByTestId('url').textContent).toBe('/api/transfers/:transferId')

    fireEvent.change(screen.getByLabelText('Chemin ou URL'), { target: { value: '/api/transfers/:transferId?size=${late}' } })
    expect(screen.getByLabelText('Valeur du paramètre size')).toBeTruthy()
    expect(screen.getByText(/\$\{late\} n’est capturée qu’à l’étape 4/)).toBeTruthy()
  })

  it('« Réutiliser une valeur » insère ${nom} dans le dernier champ édité, à la position du curseur', () => {
    render(<Harness initial={newStep({ method: 'POST', url: '/transfers', body: '{"amount": }' })} />)
    const body = screen.getByLabelText('Corps de la requête') as HTMLTextAreaElement
    body.setSelectionRange(11, 11)
    fireEvent.select(body)
    fireEvent.click(screen.getByRole('tab', { name: /Variables/ }))
    fireEvent.click(screen.getByRole('button', { name: /\$\{amount\}/ }))
    expect(screen.getByTestId('body').textContent).toBe('{"amount": ${amount}}')
  })
})
