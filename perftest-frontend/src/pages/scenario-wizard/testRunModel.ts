import { BackendTestRunStepResult } from '../../types/backendContracts'

// ============================================================
// Aides d'affichage du test détaillé (étape « Tester ») : couleur du statut,
// piste de correction, taille lisible, coloration JSON et cohérence des URL.
// ============================================================

export type StatusTone = 'ok' | 'info' | 'warn' | 'ko' | 'neutral'

/** 2xx vert, 3xx bleu, 4xx orange, 5xx / erreur réseau rouge, non exécutée gris. */
export function statusTone(result: BackendTestRunStepResult): StatusTone {
  if (result.status === 'SKIPPED') return 'neutral'
  const code = result.response?.status
  if (code == null) return 'ko'
  if (code >= 500) return 'ko'
  if (code >= 400) return 'warn'
  if (code >= 300) return 'info'
  return 'ok'
}

export function statusLabel(result: BackendTestRunStepResult): string {
  if (result.status === 'SKIPPED') return 'Non exécutée'
  if (!result.response) return 'Pas de réponse'
  return `${result.response.status}${result.response.statusText ? ` ${result.response.statusText}` : ''}`
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} o`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0).replace('.', ',')} Ko`
  return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} Mo`
}

/** Piste concrète selon la cause de l'échec (null si l'étape a réussi). */
export function failureHint(result: BackendTestRunStepResult): string | null {
  if (result.success) return null
  const code = result.response?.status
  switch (result.errorKind) {
    case 'CONNECTION_REFUSED':
      return result.urlWarning
        ? `L’application est-elle lancée sur ce port ? L’URL de l’étape ne pointe pas vers l’application : utilisez ${result.urlWarning.suggestedPath}.`
        : 'L’application est-elle lancée sur ce port ? Vérifiez l’URL de l’application.'
    case 'DNS':
      return 'Nom d’hôte introuvable : vérifiez l’orthographe de l’URL (ou le réseau / VPN).'
    case 'TLS':
      return 'Erreur TLS : l’application attend-elle http:// plutôt que https:// (ou un certificat valide) ?'
    case 'TIMEOUT':
      return 'Le serveur ne répond pas à temps : augmentez le délai de l’étape ou vérifiez qu’il n’est pas bloqué.'
    case 'GLOBAL_TIMEOUT':
      return 'Le test complet est limité à 60 s : allégez les étapes lentes ou testez-les une par une.'
    case 'UNRESOLVED_VARIABLE':
      return 'Capturez cette variable dans une étape précédente (onglet Variables) ou ajoutez-la comme colonne du CSV.'
    case 'INVALID_REQUEST':
      return 'Vérifiez l’URL (http:// ou https://) et les noms des en-têtes de l’étape.'
    case 'AUTH_FAILED':
      return 'Vérifiez l’authentification de l’application (page Applications → Tester la connectivité).'
    case 'PREVIOUS_FAILED':
      return 'Corrigez d’abord l’étape en échec ; celle-ci sera exécutée au prochain test.'
    case 'ASSERTION':
      return 'Le contenu attendu est absent : comparez avec l’onglet « Réponse » (majuscules, espaces, guillemets).'
  }
  if (code === 401 || code === 403) return 'Vérifiez l’authentification de l’application (ou l’en-tête Authorization de l’étape, prioritaire).'
  if (code === 404) return 'Ressource introuvable : vérifiez le chemin de l’URL et les variables ${…} utilisées.'
  if (code === 405) return 'Méthode non acceptée par cet endpoint : vérifiez GET / POST / PUT / PATCH / DELETE.'
  if (code === 400 || code === 422) return 'Requête refusée : vérifiez le corps envoyé (champs obligatoires, JSON valide) et l’en-tête Content-Type.'
  if (code === 409) return 'Conflit côté serveur (état de la ressource) : relancez le test, l’erreur peut être ponctuelle.'
  if (code != null && code >= 500) return 'Erreur côté serveur : consultez les journaux de l’application testée.'
  if (code != null && code >= 300 && code < 400) return 'Redirection reçue : indiquez le code attendu ou activez le suivi des redirections.'
  return null
}

const ABSOLUTE_URL = /^(https?):\/\/([^/?#]+)(.*)$/i

function origin(scheme: string, authority: string): string {
  const s = scheme.toLowerCase()
  const hostPort = authority.slice(authority.lastIndexOf('@') + 1).toLowerCase()
  return `${s}://${/:\d+$/.test(hostPort) ? hostPort : `${hostPort}:${s === 'https' ? 443 : 80}`}`
}

export interface UrlMismatch {
  stepOrigin: string
  appOrigin: string
  suggestedPath: string
}

/** Même règle que le backend (StepTestRunner.urlWarning) : URL absolue d'une autre origine que l'application. */
export function urlMismatch(baseUrl: string, stepUrl: string): UrlMismatch | null {
  const step = ABSOLUTE_URL.exec(stepUrl.trim())
  const base = ABSOLUTE_URL.exec(baseUrl.trim())
  if (!step || !base) return null
  const stepOrigin = origin(step[1], step[2])
  const appOrigin = origin(base[1], base[2])
  if (stepOrigin === appOrigin) return null
  let path = step[3] || '/'
  const basePath = base[3].replace(/\/+$/, '')
  if (basePath && path.startsWith(`${basePath}/`)) path = path.slice(basePath.length)
  return { stepOrigin, appOrigin, suggestedPath: path }
}

export function prettyJson(body: string): string {
  try {
    return JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    return body
  }
}

export type JsonToken = { text: string; kind: 'key' | 'string' | 'number' | 'boolean' | 'null' | 'plain' }

/** Découpe un JSON indenté en jetons colorables (aucun HTML injecté). */
export function tokenizeJson(json: string): JsonToken[] {
  const tokens: JsonToken[] = []
  const re = /("(?:[^"\\]|\\.)*")(\s*:)?|\b(true|false)\b|\bnull\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g
  let last = 0
  for (let m = re.exec(json); m; m = re.exec(json)) {
    if (m.index > last) tokens.push({ text: json.slice(last, m.index), kind: 'plain' })
    if (m[1]) {
      tokens.push({ text: m[1], kind: m[2] ? 'key' : 'string' })
      if (m[2]) tokens.push({ text: m[2], kind: 'plain' })
    } else if (m[3]) {
      tokens.push({ text: m[0], kind: 'boolean' })
    } else if (m[0] === 'null') {
      tokens.push({ text: m[0], kind: 'null' })
    } else {
      tokens.push({ text: m[0], kind: 'number' })
    }
    last = m.index + m[0].length
  }
  if (last < json.length) tokens.push({ text: json.slice(last), kind: 'plain' })
  return tokens
}
