import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import StepEditor from './StepEditor'
import { isBlankStep } from './draftModel'
import {
  DEFAULT_EXPECTED_STATUS, DraftStep, expectedStatusAfterMethodChange, hasContentCheck, newStep, stepToRequest,
} from './wizardModel'

// Vérifications d'une étape : code HTTP attendu proposé selon la méthode, avertissement sans
// vérification de contenu.

describe('code HTTP attendu par défaut', () => {
  it('GET 200, POST 201, PUT / PATCH / DELETE 200 ; une nouvelle étape le reçoit et l’envoie au serveur', () => {
    expect(DEFAULT_EXPECTED_STATUS).toEqual({ GET: '200', POST: '201', PUT: '200', PATCH: '200', DELETE: '200' })
    expect(newStep().expectedStatus).toBe('200')
    expect(newStep({ method: 'POST' }).expectedStatus).toBe('201')
    expect(newStep({ method: 'DELETE' }).expectedStatus).toBe('200')
    expect(stepToRequest(newStep({ name: 'Virement', method: 'POST', url: '/api/transfers' }), 'sc', 1).expectedStatus).toBe(201)
    expect(isBlankStep(newStep()), 'une étape vide reste « vide » malgré le code proposé').toBe(true)
  })

  it('suit la méthode tant qu’il n’est pas personnalisé ; une valeur saisie est conservée', () => {
    expect(expectedStatusAfterMethodChange({ method: 'GET', expectedStatus: '200' }, 'POST')).toBe('201')
    expect(expectedStatusAfterMethodChange({ method: 'POST', expectedStatus: '201' }, 'PATCH')).toBe('200')
    expect(expectedStatusAfterMethodChange({ method: 'GET', expectedStatus: '' }, 'POST')).toBe('201')
    expect(expectedStatusAfterMethodChange({ method: 'GET', expectedStatus: '204' }, 'POST')).toBe('204')
    expect(hasContentCheck({ assertionBodyContains: '  ' })).toBe(false)
    expect(hasContentCheck({ assertionBodyContains: '"success":true' })).toBe(true)
  })
})

function Harness({ initial }: { initial: DraftStep }) {
  const [step, setStep] = useState(initial)
  return (
    <StepEditor step={step} index={0} baseUrl="http://localhost:8888" errors={{}} showErrors={false} variables={[]}
      laterCaptures={new Map()} hasCsv={false} onChange={(patch) => setStep((s) => ({ ...s, ...patch }))} />
  )
}

describe('éditeur d’étape', () => {
  it('change le code proposé avec la méthode ; avertit sans vérification de contenu jusqu’à ce qu’elle soit saisie', () => {
    render(<Harness initial={newStep({ name: 'Solde', url: '/api/account/balance' })} />)
    expect(screen.getByTestId('no-content-check').textContent).toMatch(/Aucune vérification de contenu : seul le code HTTP 200 est contrôlé/)

    fireEvent.change(screen.getByLabelText('Méthode HTTP'), { target: { value: 'POST' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter une vérification' }))
    expect((screen.getByLabelText('Code HTTP attendu') as HTMLInputElement).value).toBe('201')
    expect(screen.getByText(/Proposé selon la méthode \(POST → 201\)/)).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Code HTTP attendu'), { target: { value: '202' } })
    fireEvent.change(screen.getByLabelText('Méthode HTTP'), { target: { value: 'PUT' } })
    expect((screen.getByLabelText('Code HTTP attendu') as HTMLInputElement).value, 'valeur personnalisée conservée').toBe('202')

    fireEvent.change(screen.getByLabelText('La réponse doit contenir'), { target: { value: '"success":true' } })
    expect(screen.queryByTestId('no-content-check')).toBeNull()
  })
})
