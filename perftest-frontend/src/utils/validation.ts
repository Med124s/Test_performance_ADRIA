// ============================================================
// Règles de validation centralisées, partagées par tous les formulaires
// du projet (Applications, Scénarios, Étapes, Connexion...) — pour que
// "obligatoire", la longueur d'un nom, le format d'une URL ou d'un email
// soient vérifiés de la même façon partout, avec les mêmes messages en
// français, plutôt que d'être réécrits différemment dans chaque page.
//
// Chaque validateur retourne un message d'erreur en français (à afficher
// sous le champ concerné) ou `null` si la valeur est valide.
// ============================================================

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

// URL absolue (champ "URL de l'application") : http:// ou https:// obligatoire.
// Ne s'applique volontairement PAS aux URLs d'étapes de scénario, qui sont
// légitimement des chemins relatifs à l'application (ex: "/auth/login") —
// voir validateStepUrl ci-dessous.
//
// Utilise le constructeur URL natif plutôt qu'une regex maison : l'ancienne
// regex exigeait un "." après le schéma (`[^\s]+\.[^\s]+`), ce qui rejetait
// à tort des cibles de test locales parfaitement valides comme
// "http://localhost:6000" ou "http://localhost:4001" — bloquant justement
// le genre d'URL qu'on utilise le plus pour tester une API locale.
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

// URL/ressource d'une étape : chemin relatif ("/auth/login") ou absolu
// (http://, https://) tous deux acceptés — seule une valeur non vide avec
// aucun espace est exigée.
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

/** Nombre entier strictement positif (Timeout, VUs, Ramp-up, Durée...) —
 * un champ HTML `type="number"` laisse passer une valeur vide, négative,
 * décimale ou "0" tant que rien ne le vérifie explicitement côté JS. */
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

/** Retourne le premier message d'erreur non nul parmi plusieurs
 * validations pour un même champ (ex: obligatoire + longueur max). */
export function firstError(...results: (string | null)[]): string | null {
  return results.find((r) => r !== null) ?? null
}
