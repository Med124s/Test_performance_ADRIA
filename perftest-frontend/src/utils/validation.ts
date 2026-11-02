// Règles de validation partagées par les formulaires.

export const NAME_MAX_LENGTH = 100
export const DESCRIPTION_MAX_LENGTH = 500

export function validateRequired(value: string, label: string): string | null {
  return value.trim() ? null : `${label} est obligatoire.`
}

export function validateMinLength(value: string, min: number, label: string): string | null {
  const len = value.trim().length
  if (len === 0) return null // laisse validateRequired gérer le cas vide
  return len >= min ? null : `${label} doit contenir au moins ${min} caractères.`
}

export function validateMaxLength(value: string, max: number, label: string): string | null {
  return value.trim().length <= max ? null : `${label} ne doit pas dépasser ${max} caractères.`
}

/** URL absolue (http:// ou https://), pour l'URL d'une application. */
export function validateAbsoluteUrl(value: string, label = "L'URL"): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null // laisse validateRequired gérer le cas vide
  if (!/^https?:\/\//i.test(trimmed)) return `${label} doit commencer par http:// ou https://.`
  try {
    new URL(trimmed)
    return null
  } catch {
    return `${label} n'est pas une URL valide.`
  }
}

/** URL d'une étape : chemin relatif ("/auth/login") ou URL absolue, sans espace. */
export function validateStepUrl(value: string, label = 'La ressource'): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  return /\s/.test(trimmed) ? `${label} ne doit pas contenir d'espace.` : null
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function validateEmail(value: string, label = "L'adresse email"): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  return EMAIL_RE.test(trimmed) ? null : `${label} n'est pas valide.`
}

/** Nombre entier strictement positif. */
export function validatePositiveInteger(value: number, label: string): string | null {
  if (!Number.isFinite(value)) return `${label} est obligatoire.`
  if (!Number.isInteger(value)) return `${label} doit être un nombre entier.`
  return value > 0 ? null : `${label} doit être supérieur à 0.`
}

export function validateJson(value: string, label = 'Le contenu JSON'): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  try {
    JSON.parse(trimmed)
    return null
  } catch {
    return `${label} n'est pas un JSON valide.`
  }
}

/** Premier message d'erreur non nul. */
export function firstError(...results: (string | null)[]): string | null {
  return results.find((r) => r !== null) ?? null
}

/** Deux champs à renseigner ensemble ou pas du tout (ex. nom de variable et chemin de capture). */
export function validatePairedFields(valueA: string, valueB: string, label: string): string | null {
  const aFilled = valueA.trim() !== ''
  const bFilled = valueB.trim() !== ''
  return aFilled !== bFilled ? `${label} doivent être renseignés ensemble (ou tous les deux vides).` : null
}
