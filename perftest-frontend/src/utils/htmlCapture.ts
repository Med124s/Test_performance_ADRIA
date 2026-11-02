// Capture dans une page HTML : sélecteur CSS, suivi de « ::attr(NOM) » pour lire un attribut.

const ATTRIBUTE_SUFFIX = /^([\s\S]*?)::attr\(\s*([^)]*?)\s*\)\s*$/
const ATTRIBUTE_NAME = /^[^\s"'>/=]+$/

/** Sélecteur et attribut (vide = texte de l'élément). */
export function splitHtmlCapture(spec: string): { selector: string; attribute: string } {
  const m = ATTRIBUTE_SUFFIX.exec(spec)
  return m ? { selector: m[1], attribute: m[2] } : { selector: spec, attribute: '' }
}

/** « sélecteur::attr(attribut) », ou le seul sélecteur si aucun attribut n'est indiqué. */
export function joinHtmlCapture(selector: string, attribute: string): string {
  return attribute.trim() ? `${selector}::attr(${attribute.trim()})` : selector
}

/** Problème en minuscule et sans point final, ou null (la syntaxe CSS détaillée est vérifiée par le serveur). */
export function htmlCaptureProblem(spec: string): string | null {
  const { selector, attribute } = splitHtmlCapture(spec.trim())
  if (!selector.trim()) return 'indiquez un sélecteur CSS (ex. input[name=csrf_token])'
  if (attribute && !ATTRIBUTE_NAME.test(attribute)) return "nom d'attribut invalide (ex. value ou data-account-id)"
  return null
}
