// ============================================================
// Runner HTTP réel partagé — envoie une vraie requête fetch() pour une
// étape donnée (méthode, headers activés, body, timeout, redirections,
// think time/pacing configurés) et construit son StepResult à partir de la
// vraie réponse (ou de la vraie erreur réseau/timeout), avec de vraies
// assertions évaluées contre cette réponse. Aucune valeur n'est ici
// générée aléatoirement.
//
// Utilisé par :
// - useScenarioLauncher.ts (exécution réelle d'un scénario, plusieurs VUs)
// - CreateScenario.tsx ("Tester" — plusieurs étapes du scénario en cours d'édition)
// - CreateStep.tsx ("Tester cette étape" — une seule étape en cours d'édition)
// ============================================================

import { Step, StepAssertion, StepResult, StepRunStatus } from '../types'

/** Pause réelle de `ms` millisecondes — interrompue immédiatement si
 * `signal` s'annule pendant l'attente (au lieu d'attendre la fin du délai),
 * pour qu'"Annuler" une exécution en cours n'ait jamais à attendre un Think
 * Time/Pacing/ramp-up déjà en vol. */
export const sleep = (ms: number, signal?: AbortSignal): Promise<void> => {
  if (ms <= 0 || signal?.aborted) return Promise.resolve()
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    signal?.addEventListener(
      'abort',
      () => {
        clearTimeout(timer)
        resolve()
      },
      { once: true }
    )
  })
}

/** Résout l'URL réelle d'une étape : absolue telle quelle, ou relative à
 * l'URL de base de l'application testée. */
export function resolveStepUrl(step: Step, baseUrl: string): string {
  if (/^https?:\/\//i.test(step.url)) return step.url
  const base = baseUrl.replace(/\/+$/, '')
  const path = step.url.startsWith('/') ? step.url : `/${step.url}`
  return `${base}${path}`
}

// ============================================================
// Résolution des Variables de test (${nom}) — partagée par les trois
// points d'entrée du moteur d'exécution (exécution réelle d'un scénario,
// "Tester cette étape", "Tester" le scénario en cours d'édition) afin
// qu'ils résolvent tous exactement la même chose, de la même façon.
// ============================================================

export class UnknownVariableError extends Error {
  constructor(public readonly variableName: string) {
    super(`Variable inconnue "\${${variableName}}" — vérifiez les Variables de test du scénario.`)
    this.name = 'UnknownVariableError'
  }
}

const VARIABLE_PATTERN = /\$\{([a-zA-Z_][a-zA-Z0-9_]*)\}/g

/** Remplace chaque ${nom} par sa valeur dans `variables`. Si une variable
 * référencée n'existe pas, lève UnknownVariableError plutôt que d'envoyer
 * littéralement "${nom}" en silence. Une chaîne sans "${...}" ressort
 * inchangée — aucun impact sur les étapes purement statiques. */
export function resolveVariables(input: string, variables: Record<string, string>): string {
  return input.replace(VARIABLE_PATTERN, (_match, name: string) => {
    if (!(name in variables)) throw new UnknownVariableError(name)
    return variables[name]
  })
}

/** Construit la table nom -> valeur à partir des Variables de test du
 * scénario (ignore les lignes sans nom) — même forme que Scenario.testVariables
 * et le state local de CreateStep/CreateScenario, pour rester réutilisable
 * partout où ces variables existent. */
export function buildVariableMap(testVariables?: { name: string; value: string }[]): Record<string, string> {
  const map: Record<string, string> = {}
  testVariables?.forEach((v) => {
    if (v.name.trim()) map[v.name.trim()] = v.value
  })
  return map
}

/** Applique la résolution de variables partout où elles peuvent apparaître
 * dans une étape : URL (donc aussi ses query params, qui y vivent
 * directement), valeur des headers, body JSON. */
function resolveStepVariables(step: Step, variables: Record<string, string>): Step {
  return {
    ...step,
    url: resolveVariables(step.url, variables),
    headers: step.headers?.map((h) => ({ ...h, value: resolveVariables(h.value, variables) })),
    bodyJson: step.bodyJson ? resolveVariables(step.bodyJson, variables) : step.bodyJson,
  }
}

// ============================================================
// Évaluation réelle des assertions — remplace la convention "2xx = succès"
// par défaut dès qu'au moins une assertion est déclarée. Chaque type lit
// une vraie donnée de la réponse (jamais inventée) ; "JSON path" et
// "Personnalisée" partagent le même résolveur JSONPath minimal ($.a.b[0]).
// ============================================================

function resolveJsonPath(value: unknown, path: string): unknown {
  const tokens = path.replace(/^\$\.?/, '').match(/[^.[\]]+/g) ?? []
  let current: unknown = value
  for (const token of tokens) {
    if (current === null || current === undefined) return undefined
    current = (current as Record<string, unknown>)[token]
  }
  return current
}

function compareValues(actual: unknown, operator: string, expected: string): boolean {
  const op = (operator || '').toLowerCase()
  if (op.includes("n'existe pas")) return actual === undefined
  if (op.includes('existe')) return actual !== undefined
  if (op.includes('contient')) return String(actual ?? '').includes(expected)
  if (op.includes('supérieur')) return Number(actual) > Number(expected)
  if (op.includes('inférieur')) return Number(actual) < Number(expected)
  if (op.includes('différent')) return String(actual ?? '') !== expected
  // "Égal à" (et tout opérateur non reconnu) : comparaison stricte en texte,
  // tolérante à l'encodage historique ("Ã‰gal Ã ") comme isStepSuccessful
  // le faisait déjà pour "Status code".
  return String(actual ?? '') === expected
}

interface AssertionContext {
  httpStatus: number
  responseTimeMs: number
  responseHeaders: Record<string, string>
  bodyJson: unknown
  bodyParseError: boolean
}

function evaluateAssertion(a: StepAssertion, ctx: AssertionContext): { passed: boolean; message: string } {
  switch (a.type) {
    case 'Status code': {
      const passed = compareValues(ctx.httpStatus, a.operator, a.targetValue)
      return { passed, message: `Status code ${a.operator} ${a.targetValue} (obtenu ${ctx.httpStatus})` }
    }
    case 'Response time': {
      const passed = compareValues(ctx.responseTimeMs, a.operator, a.targetValue)
      return { passed, message: `Response time ${a.operator} ${a.targetValue}ms (obtenu ${ctx.responseTimeMs}ms)` }
    }
    case 'Header': {
      const actual = ctx.responseHeaders[(a.property || '').toLowerCase()]
      const passed = compareValues(actual, a.operator, a.targetValue)
      return { passed, message: `Header "${a.property}" ${a.operator} "${a.targetValue}" (obtenu ${actual ?? '—'})` }
    }
    case 'JSON path':
    case 'Personnalisée':
    default: {
      if (ctx.bodyParseError) {
        return { passed: false, message: `"${a.property}" : réponse non-JSON, impossible d'évaluer` }
      }
      const actual = resolveJsonPath(ctx.bodyJson, a.property)
      const passed = compareValues(actual, a.operator, a.targetValue)
      return { passed, message: `${a.property} ${a.operator} ${a.targetValue} (obtenu ${JSON.stringify(actual) ?? '—'})` }
    }
  }
}

/** Évalue toutes les assertions d'une étape contre une vraie réponse. Sans
 * assertion déclarée, retombe sur la convention HTTP standard (2xx =
 * succès) — comportement inchangé pour les étapes qui n'en définissent pas. */
function evaluateStep(assertions: StepAssertion[] | undefined, ctx: AssertionContext): { success: boolean; failures: string[] } {
  if (!assertions || assertions.length === 0) {
    return { success: ctx.httpStatus >= 200 && ctx.httpStatus < 300, failures: [] }
  }
  const results = assertions.map((a) => evaluateAssertion(a, ctx))
  return { success: results.every((r) => r.passed), failures: results.filter((r) => !r.passed).map((r) => r.message) }
}

export interface RunStepOptions {
  /** Table nom -> valeur pour résoudre les ${variables} de l'étape. */
  variables?: Record<string, string>
  /** Index (0-based) de l'utilisateur virtuel qui exécute cette étape —
   * reporté tel quel sur le StepResult, pour distinguer plusieurs VUs
   * rejouant la même étape dans une même Execution. */
  vu?: number
  /** Think Time de repli (ms) si l'étape ne définit pas son propre
   * `pauseBeforeMs` — vient du formulaire de lancement (override ponctuel). */
  thinkTimeOverrideMs?: number
  /** Signal externe (partagé par toute l'exécution, voir useScenarioLauncher)
   * permettant à "Annuler" d'interrompre réellement une requête déjà en
   * vol, pas seulement d'empêcher la suivante de démarrer. */
  signal?: AbortSignal
}

/**
 * Envoie réellement la requête HTTP configurée pour une étape (méthode,
 * headers activés, body, timeout, redirections), après avoir résolu ses
 * ${variables} de test et attendu son Think Time réel, puis évalue ses
 * vraies assertions contre la vraie réponse (ou la vraie erreur réseau/
 * timeout) et attend son Pacing réel avant de rendre la main. Une étape
 * désactivée (`active: false`) n'envoie aucune requête et ressort
 * 'skipped'. Aucune valeur n'est ici générée aléatoirement.
 */
export async function runStep(
  step: Step,
  baseApplicationUrl: string,
  defaultTimeoutMs: number,
  options: RunStepOptions = {}
): Promise<StepResult> {
  const { variables = {}, vu, thinkTimeOverrideMs, signal: externalSignal } = options

  if (externalSignal?.aborted) {
    return { stepId: step.id, status: 'skipped', vu }
  }

  if (step.active === false) {
    console.log(`[stepRunner] "${step.name}" désactivée — ignorée (skipped)`)
    return { stepId: step.id, status: 'skipped', vu }
  }

  let resolvedStep: Step
  try {
    resolvedStep = resolveStepVariables(step, variables)
  } catch (err) {
    const message =
      err instanceof UnknownVariableError
        ? `Impossible d'exécuter l'étape "${step.name}" : ${err.message}`
        : 'Erreur de résolution des variables de test'
    console.error(`[stepRunner] ${message}`)
    return {
      stepId: step.id,
      status: 'error',
      request: { method: step.method, url: step.url },
      error: message,
      vu,
    }
  }

  const url = resolveStepUrl(resolvedStep, baseApplicationUrl)
  const headers: Record<string, string> = {}
  resolvedStep.headers?.forEach((h) => {
    if (h.enabled && h.key) headers[h.key] = h.value
  })
  const methodsWithoutBody = ['GET', 'DELETE', 'HEAD', 'TRACE']
  const hasBody = !methodsWithoutBody.includes(resolvedStep.method) && !!resolvedStep.bodyJson
  if (hasBody && !Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) {
    headers['Content-Type'] = 'application/json'
  }
  const timeoutMs = resolvedStep.timeoutMs && resolvedStep.timeoutMs > 0 ? resolvedStep.timeoutMs : defaultTimeoutMs
  const request = { method: resolvedStep.method, url, body: hasBody ? resolvedStep.bodyJson : undefined }

  const thinkTimeMs = resolvedStep.pauseBeforeMs ?? thinkTimeOverrideMs ?? 0
  if (thinkTimeMs > 0) await sleep(thinkTimeMs, externalSignal)
  // Annulé pendant le Think Time : ne pas envoyer la requête du tout (sinon
  // le seul effet du fix ci-dessus serait de passer plus vite à un fetch()
  // qu'on aurait de toute façon dû éviter).
  if (externalSignal?.aborted) {
    return { stepId: step.id, status: 'skipped', vu }
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const onExternalAbort = () => controller.abort()
  externalSignal?.addEventListener('abort', onExternalAbort)
  const startedAt = performance.now()

  try {
    const response = await fetch(url, {
      method: resolvedStep.method,
      headers,
      body: hasBody ? resolvedStep.bodyJson : undefined,
      redirect: resolvedStep.followRedirects === false ? 'error' : 'follow',
      signal: controller.signal,
    })
    const responseTimeMs = Math.round(performance.now() - startedAt)
    let bodyText = ''
    try {
      bodyText = await response.text()
    } catch {
      // corps de réponse illisible — non bloquant
    }
    const responseHeaders: Record<string, string> = {}
    response.headers.forEach((value, key) => {
      responseHeaders[key.toLowerCase()] = value
    })
    let bodyJson: unknown
    let bodyParseError = false
    if (bodyText) {
      try {
        bodyJson = JSON.parse(bodyText)
      } catch {
        bodyParseError = true
      }
    }
    const { success, failures } = evaluateStep(resolvedStep.assertions, {
      httpStatus: response.status,
      responseTimeMs,
      responseHeaders,
      bodyJson,
      bodyParseError,
    })
    console.log(`[stepRunner] ${resolvedStep.method} ${url} → ${response.status}${vu !== undefined ? ` (VU ${vu + 1})` : ''}`)

    const pacingMs = resolvedStep.pacingAfterMs ?? 0
    if (pacingMs > 0) await sleep(pacingMs, externalSignal)

    return {
      stepId: step.id,
      status: success ? 'success' : 'error',
      httpStatus: response.status,
      responseTimeMs,
      request,
      response: { statusText: response.statusText, body: bodyText.slice(0, 2000), headers: responseHeaders },
      error: success
        ? undefined
        : failures.length > 0
        ? `Assertion(s) échouée(s) sur "${resolvedStep.name}" : ${failures.join(' | ')}`
        : `Échec sur l'étape "${resolvedStep.name}" (HTTP ${response.status} ${response.statusText})`,
      vu,
    }
  } catch (err) {
    const responseTimeMs = Math.round(performance.now() - startedAt)
    const isAbort = err instanceof DOMException && err.name === 'AbortError'
    // TypeError est ce que fetch() lève pour toute erreur réseau/CORS
    // bloquée par le navigateur (y compris une redirection refusée par
    // `redirect: 'error'`) — la requête peut avoir techniquement atteint le
    // serveur, mais le JS n'a jamais accès à la réponse.
    const isNetworkOrCors = err instanceof TypeError
    const message = isAbort
      ? externalSignal?.aborted
        ? `Requête annulée par l'utilisateur sur "${resolvedStep.name}"`
        : `Timeout dépassé (${timeoutMs} ms) sur "${resolvedStep.name}"`
      : isNetworkOrCors
      ? `Requête bloquée (réseau/CORS/redirection refusée) sur "${resolvedStep.name}" : ${err.message}`
      : err instanceof Error
      ? err.message
      : 'Erreur réseau inconnue'
    console.log(`[stepRunner] ${resolvedStep.method} ${url} → ERROR: ${message}`)
    return {
      stepId: step.id,
      status: 'error',
      responseTimeMs,
      request,
      error: message,
      vu,
    }
  } finally {
    clearTimeout(timer)
    externalSignal?.removeEventListener('abort', onExternalAbort)
  }
}

export interface LiveStepState {
  step: Step
  status: StepRunStatus
  httpStatus?: number
  responseTimeMs?: number
  error?: string
  vu?: number
}
