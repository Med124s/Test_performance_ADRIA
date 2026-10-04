// ============================================================
// Variables (type Postman) côté assistant : variables de chemin et paramètres
// de requête d'une étape, variables du scénario, sources disponibles et
// avertissements. Mêmes règles que le moteur (backend : RequestBuilder,
// VariableContext, ScenarioVariables) :
//   priorité capture (étape précédente) > CSV > scénario > ${authToken}.
// ============================================================

export interface QueryParamRow {
  key: string
  value: string
  enabled: boolean
}

export interface VariableRow {
  key: string
  value: string
}

export type VariableKind = 'capture' | 'csv' | 'scenario' | 'automatic'

export interface KnownVariable {
  name: string
  kind: VariableKind
  /** Libellé de la source : « Capture (étape 3) », « CSV », « Scénario », « Automatique ». */
  label: string
}

/** Étape vue par ce module (sous-ensemble de DraftStep, pour éviter une dépendance circulaire). */
export interface StepLike {
  url: string
  body: string
  headers: { key: string; value: string }[]
  pathParams: Record<string, string>
  queryParams: QueryParamRow[]
  captureVariableName: string
  captureJsonPath: string
}

export const VARIABLE_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/
const VARIABLE_REF = /\$\{([^}]+)\}/g
const PATH_VARIABLE = /(?<=\/):([A-Za-z_][A-Za-z0-9_]*)|(?<!\$)\{([A-Za-z_][A-Za-z0-9_]*)\}/g

// ---------------------------------------------------------------- URL et paramètres

/** Partie chemin (préfixe absolu compris) et query d'une URL saisie. */
export function splitUrl(url: string): { path: string; query: string | null } {
  const hash = url.indexOf('#')
  const clean = hash >= 0 ? url.slice(0, hash) : url
  const q = clean.indexOf('?')
  return q >= 0 ? { path: clean.slice(0, q), query: clean.slice(q + 1) } : { path: clean, query: null }
}

/** Variables de chemin « :nom » (après un /) et « {nom} », dans l'ordre, sans doublon. */
export function detectPathVariables(url: string): string[] {
  let { path } = splitUrl(url.trim())
  const absolute = /^https?:\/\/[^/?#]*/i.exec(path)
  if (absolute) path = path.slice(absolute[0].length)
  const names: string[] = []
  for (const m of path.matchAll(PATH_VARIABLE)) {
    const name = m[1] ?? m[2]
    if (!names.includes(name)) names.push(name)
  }
  return names
}

export function parseQueryString(query: string | null): QueryParamRow[] {
  if (!query) return []
  return query.split('&').filter(Boolean).map((pair) => {
    const eq = pair.indexOf('=')
    return eq >= 0 ? { key: pair.slice(0, eq), value: pair.slice(eq + 1), enabled: true } : { key: pair, value: '', enabled: true }
  })
}

/** URL → tableau : paramètres actifs lus dans l'URL, paramètres désactivés conservés. */
export function queryRowsFromUrl(url: string, previous: QueryParamRow[]): QueryParamRow[] {
  return [...parseQueryString(splitUrl(url).query), ...previous.filter((r) => !r.enabled)]
}

/** Tableau → URL : seuls les paramètres actifs (avec un nom) apparaissent dans l'URL. */
export function urlWithQuery(url: string, rows: QueryParamRow[]): string {
  const { path } = splitUrl(url)
  const active = rows.filter((r) => r.enabled && r.key.trim())
  return active.length ? `${path}?${active.map((r) => (r.value ? `${r.key}=${r.value}` : r.key)).join('&')}` : path
}

export function referencedVariables(text: string): string[] {
  return Array.from(text.matchAll(VARIABLE_REF), (m) => m[1].trim())
}

/** Variables substituées par le moteur dans une étape : URL, chemin, query, en-têtes, corps. */
export function stepVariableNames(step: StepLike): string[] {
  const pathDefaults = detectPathVariables(step.url).map((name) => step.pathParams[name] ?? `\${${name}}`)
  const texts = [splitUrl(step.url).path, step.body, ...pathDefaults,
    ...step.queryParams.filter((r) => r.enabled).flatMap((r) => [r.key, r.value]),
    ...step.headers.flatMap((h) => [h.key, h.value])]
  return Array.from(new Set(texts.flatMap(referencedVariables)))
}

// ---------------------------------------------------------------- variables disponibles

/** Variables utilisables par l'étape d'index `index`, par ordre de priorité (une entrée par nom). */
export function availableVariables(steps: StepLike[], index: number, csvColumns: string[], scenario: VariableRow[],
  authToken: boolean): KnownVariable[] {
  const result: KnownVariable[] = []
  const add = (name: string, kind: VariableKind, label: string) => {
    if (name && !result.some((v) => v.name === name)) result.push({ name, kind, label })
  }
  for (let i = index - 1; i >= 0; i--) {
    const s = steps[i]
    if (s.captureVariableName.trim() && s.captureJsonPath.trim()) add(s.captureVariableName.trim(), 'capture', `Capture (étape ${i + 1})`)
  }
  csvColumns.forEach((c) => add(c, 'csv', 'CSV'))
  scenario.filter((v) => VARIABLE_NAME.test(v.key.trim())).forEach((v) => add(v.key.trim(), 'scenario', 'Scénario'))
  if (authToken) add('authToken', 'automatic', 'Automatique')
  return result
}

/** Variables capturées par cette étape ou une étape suivante : nom → numéro d'étape (1..n). */
export function laterCaptures(steps: StepLike[], index: number): Map<string, number> {
  const later = new Map<string, number>()
  for (let i = index; i < steps.length; i++) {
    const name = steps[i].captureVariableName.trim()
    if (name && steps[i].captureJsonPath.trim() && !later.has(name)) later.set(name, i + 1)
  }
  return later
}

export interface ValueSource {
  label: string
  tone: 'fixed' | 'ok' | 'warn' | 'ko'
}

/** Badge « Source » d'un champ valeur : valeur fixe, ou source de la variable référencée. */
export function describeValue(value: string, available: KnownVariable[], later: Map<string, number>): ValueSource {
  const refs = referencedVariables(value)
  if (refs.length === 0) return { label: 'Valeur fixe', tone: 'fixed' }
  const labels = refs.map((name) => {
    const known = available.find((v) => v.name === name)
    if (known) return { label: known.label, tone: 'ok' as const }
    if (later.has(name)) return { label: `Capturée à l'étape ${later.get(name)}`, tone: 'warn' as const }
    return { label: 'Inconnue', tone: 'ko' as const }
  })
  const worst = labels.find((l) => l.tone === 'ko') ?? labels.find((l) => l.tone === 'warn') ?? labels[0]
  return refs.length === 1 && !value.replace(/\$\{[^}]+\}/, '').trim() ? worst : { label: labels.map((l) => l.label).join(' + '), tone: worst.tone }
}

/** Avertissements pour toutes les variables référencées par une valeur. */
export function valueWarnings(value: string, available: KnownVariable[], later: Map<string, number>, hasCsv: boolean): string[] {
  return referencedVariables(value).flatMap((name) => {
    if (available.some((v) => v.name === name)) return []
    if (later.has(name)) return [`\${${name}} n’est capturée qu’à l’étape ${later.get(name)} : elle ne sera pas disponible ici.`]
    return [hasCsv ? `\${${name}} est absente du CSV et des variables connues.` : `\${${name}} est une variable inconnue.`]
  })
}

// ---------------------------------------------------------------- variables du scénario

export function validateScenarioVariables(rows: VariableRow[], csvColumns: string[]): { errors: string[]; warnings: string[] } {
  const errors: string[] = []
  const warnings: string[] = []
  const seen = new Set<string>()
  rows.forEach((row, i) => {
    const key = row.key.trim()
    if (!key && !row.value.trim()) return
    if (!key) errors.push(`Variable ${i + 1} : donnez-lui un nom.`)
    else if (!VARIABLE_NAME.test(key)) errors.push(`Variable « ${key} » : lettres, chiffres et _ uniquement, sans commencer par un chiffre.`)
    else if (seen.has(key)) errors.push(`Variable « ${key} » en double.`)
    else if (csvColumns.includes(key)) warnings.push(`« ${key} » est aussi une colonne du CSV : la valeur du CSV sera prioritaire.`)
    if (row.value.length > 2000) errors.push(`Variable « ${key} » : 2000 caractères au maximum.`)
    seen.add(key)
  })
  if (rows.filter((r) => r.key.trim()).length > 100) errors.push('100 variables du scénario au maximum.')
  return { errors, warnings }
}

export function variablesToJson(rows: VariableRow[]): string {
  return JSON.stringify(Object.fromEntries(rows.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value])))
}

export function variablesFromJson(json: string | null | undefined): VariableRow[] {
  if (!json) return []
  try {
    return Object.entries(JSON.parse(json) as Record<string, unknown>).map(([key, value]) => ({ key, value: String(value ?? '') }))
  } catch {
    return []
  }
}
