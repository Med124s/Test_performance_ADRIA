import { describe, expect, it } from 'vitest'
import {
  backendToFrontendActiveStatus,
  backendToFrontendConnectionStatus,
  backendToFrontendExecutionStatus,
  frontendToBackendActiveStatus,
  frontendToBackendExecutionStatus,
} from './statusMapping'

describe('backendToFrontendExecutionStatus', () => {
  it('mappe QUEUED et RUNNING sur "En cours" (pas de notion de file distincte côté frontend)', () => {
    expect(backendToFrontendExecutionStatus('QUEUED')).toBe('En cours')
    expect(backendToFrontendExecutionStatus('RUNNING')).toBe('En cours')
  })

  it('mappe les statuts terminaux sans perte', () => {
    expect(backendToFrontendExecutionStatus('SUCCESS')).toBe('Réussie')
    expect(backendToFrontendExecutionStatus('FAILED')).toBe('Échouée')
    expect(backendToFrontendExecutionStatus('CANCELLED')).toBe('Annulée')
  })
})

describe('frontendToBackendExecutionStatus', () => {
  it('mappe les statuts avec équivalent backend direct', () => {
    expect(frontendToBackendExecutionStatus('En cours')).toBe('RUNNING')
    expect(frontendToBackendExecutionStatus('Réussie')).toBe('SUCCESS')
    expect(frontendToBackendExecutionStatus('Échouée')).toBe('FAILED')
    expect(frontendToBackendExecutionStatus('Annulée')).toBe('CANCELLED')
  })

  it('mappe "Avec erreurs" sur FAILED (choix conservateur documenté, pas un statut inventé)', () => {
    expect(frontendToBackendExecutionStatus('Avec erreurs')).toBe('FAILED')
  })

  it('renvoie null pour "Suspendue" (aucun équivalent backend, jamais deviné)', () => {
    expect(frontendToBackendExecutionStatus('Suspendue')).toBeNull()
  })
})

describe('backendToFrontendConnectionStatus', () => {
  it('traite CONNECTED comme Connectée', () => {
    expect(backendToFrontendConnectionStatus('CONNECTED')).toBe('Connectée')
  })

  it('traite null (aucun test lancé) comme Connectée, jamais comme le pire cas', () => {
    expect(backendToFrontendConnectionStatus(null)).toBe('Connectée')
  })

  it('traite tout statut non-CONNECTED comme Non connectée', () => {
    expect(backendToFrontendConnectionStatus('FAILED')).toBe('Non connectée')
  })
})

describe('active status round-trip (Scenario/Step)', () => {
  it('backend -> frontend', () => {
    expect(backendToFrontendActiveStatus('ACTIVE')).toBe('Actif')
    expect(backendToFrontendActiveStatus('INACTIVE')).toBe('Inactif')
  })

  it('frontend -> backend', () => {
    expect(frontendToBackendActiveStatus('Actif')).toBe('ACTIVE')
    expect(frontendToBackendActiveStatus('Inactif')).toBe('INACTIVE')
  })
})
