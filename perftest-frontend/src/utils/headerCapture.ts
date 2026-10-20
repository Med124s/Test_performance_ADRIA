// ============================================================
// Lecture d'une valeur dans un EN-TÊTE de réponse (capture d'une étape, jeton de la Connexion
// automatique) : le « chemin » est le nom de l'en-tête (ex. X-CSRF-Token), ou « Set-Cookie:NOM »
// pour la valeur du cookie NOM. Mêmes règles que StepRules.headerCaptureProblem (backend).
// ============================================================

const HEADER_NAME = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/

/** Nom de l'en-tête et, pour Set-Cookie, nom du cookie (« Set-Cookie:NOM »). */
export function splitHeaderCapture(spec: string): { header: string; cookie: string } {
  const colon = spec.indexOf(':')
  return colon < 0 ? { header: spec, cookie: '' } : { header: spec.slice(0, colon), cookie: spec.slice(colon + 1) }
}

export const isSetCookie = (header: string) => header.trim().toLowerCase() === 'set-cookie'

/** « Set-Cookie:NOM » quand un cookie est précisé pour Set-Cookie, sinon le seul nom de l'en-tête. */
export function joinHeaderCapture(header: string, cookie: string): string {
  return isSetCookie(header) && cookie.trim() ? `${header.trim()}:${cookie.trim()}` : header
}

/** Problème en minuscule et sans point final (préfixé par l'appelant), ou null. */
export function headerCaptureProblem(spec: string): string | null {
  const { header, cookie } = splitHeaderCapture(spec.trim())
  // Messages identiques au backend (apostrophes droites).
  if (!HEADER_NAME.test(header.trim())) return "nom d'en-tête invalide (pas d'espace ni de caractère spécial, ex. X-CSRF-Token)"
  if (spec.includes(':')) {
    if (!isSetCookie(header)) return "un nom de cookie ne s'indique que pour Set-Cookie (ex. Set-Cookie:SESSIONID)"
    if (!HEADER_NAME.test(cookie.trim())) return 'nom de cookie invalide (ex. Set-Cookie:SESSIONID)'
  }
  return null
}
