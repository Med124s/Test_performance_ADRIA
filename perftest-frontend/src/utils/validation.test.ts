import { describe, expect, it } from 'vitest'
import {
  validatePairedFields,
  validateRequired,
  validateStepUrl,
  firstError,
} from './validation'

describe('validatePairedFields (master prompt final — Lot B, capture de variable)', () => {
  it('accepte les deux champs vides (aucune capture configurée)', () => {
    expect(validatePairedFields('', '', 'Nom et chemin')).toBeNull()
  })

  it('accepte les deux champs renseignés', () => {
    expect(validatePairedFields('token', '$.token', 'Nom et chemin')).toBeNull()
  })

  it("rejette le nom seul sans chemin", () => {
    expect(validatePairedFields('token', '', 'Nom et chemin')).not.toBeNull()
  })

  it('rejette le chemin seul sans nom', () => {
    expect(validatePairedFields('', '$.token', 'Nom et chemin')).not.toBeNull()
  })

  it('traite les espaces seuls comme vides', () => {
    expect(validatePairedFields('  ', '', 'Nom et chemin')).toBeNull()
    expect(validatePairedFields('token', '   ', 'Nom et chemin')).not.toBeNull()
  })
})

describe('validateStepUrl (utilisé par les formulaires Scenario/Step, incluant les champs pacing/capture)', () => {
  it('accepte un chemin relatif', () => {
    expect(validateStepUrl('/api/login')).toBeNull()
  })

  it("accepte un placeholder de variable dans l'URL", () => {
    expect(validateStepUrl('/users/${userId}')).toBeNull()
  })

  it('rejette une valeur contenant un espace', () => {
    expect(validateStepUrl('/api/ with space')).not.toBeNull()
  })
})

describe('firstError', () => {
  it('renvoie null si tous les validateurs passent', () => {
    expect(firstError(validateRequired('a', 'Champ'), validateStepUrl('a'))).toBeNull()
  })

  it('renvoie le premier message non nul', () => {
    expect(firstError(validateRequired('', 'Champ'), validateStepUrl('a b'))).toContain('obligatoire')
  })
})
