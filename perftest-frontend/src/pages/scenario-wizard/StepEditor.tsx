import { ChangeEvent, SyntheticEvent, useState } from 'react'
import { BackendHttpMethod } from '../../types/backendContracts'
import {
  DraftStep,
  FieldErrors,
  HTTP_METHODS,
  HeaderRow,
  detectBodyFormat,
  formatJsonBody,
  hasHeader,
} from './wizardModel'
import { urlMismatch } from './testRunModel'
import {
  KnownVariable,
  QueryParamRow,
  ValueSource,
  describeValue,
  detectPathVariables,
  queryRowsFromUrl,
  stepVariableNames,
  urlWithQuery,
  valueWarnings,
} from './variablesModel'

type TabId = 'params' | 'headers' | 'body' | 'validation' | 'variables'

/** Champ dans lequel « Réutiliser une valeur » insère ${nom} (dernier champ édité). */
type InsertTarget =
  | { kind: 'url' }
  | { kind: 'body' }
  | { kind: 'header'; index: number }
  | { kind: 'query'; index: number }
  | { kind: 'path'; name: string }

interface StepEditorProps {
  step: DraftStep
  index: number
  baseUrl: string
  errors: FieldErrors
  showErrors: boolean
  /** Variables utilisables par cette étape, par priorité (captures précédentes, CSV, scénario, authToken). */
  variables: KnownVariable[]
  /** Variables capturées par cette étape ou une étape suivante : nom → numéro d'étape. */
  laterCaptures: Map<string, number>
  hasCsv: boolean
  onChange: (patch: Partial<DraftStep>) => void
}

const METHODS_WITH_BODY: BackendHttpMethod[] = ['POST', 'PUT', 'PATCH', 'DELETE']

function SourceBadge({ source }: { source: ValueSource }) {
  return <span className={`sw-source-badge ${source.tone}`}>{source.label}</span>
}

/** Champ valeur : texte fixe ou ${variable}, liste déroulante des variables disponibles, badge Source. */
function VariableInput({ value, placeholder, variables, laterCaptures, ariaLabel, onChange, onRemember }: {
  value: string
  placeholder?: string
  variables: KnownVariable[]
  laterCaptures: Map<string, number>
  ariaLabel: string
  onChange: (value: string) => void
  onRemember: (e: SyntheticEvent<HTMLInputElement>) => void
}) {
  const effective = value || placeholder || ''
  return (
    <div className="sw-var-input">
      <input
        className="pt-form-control"
        value={value}
        placeholder={placeholder ?? 'Valeur fixe ou ${variable}'}
        onChange={(e) => { onChange(e.target.value); onRemember(e) }}
        onFocus={onRemember}
        onSelect={onRemember}
        spellCheck={false}
        aria-label={ariaLabel}
      />
      <select
        className="sw-var-select"
        value=""
        onChange={(e) => e.target.value && onChange(`\${${e.target.value}}`)}
        aria-label={`Choisir une variable pour ${ariaLabel}`}
        disabled={variables.length === 0}
        title={variables.length === 0 ? 'Aucune variable disponible pour cette étape' : 'Utiliser une variable'}
      >
        <option value="">{'${…}'}</option>
        {variables.map((v) => <option key={v.name} value={v.name}>{v.name} — {v.label}</option>)}
      </select>
      <SourceBadge source={describeValue(effective, variables, laterCaptures)} />
    </div>
  )
}

function StepEditor({ step, index, baseUrl, errors, showErrors, variables, laterCaptures, hasCsv, onChange }: StepEditorProps) {
  const [tab, setTab] = useState<TabId>(METHODS_WITH_BODY.includes(step.method) ? 'body' : 'params')
  const [copied, setCopied] = useState<string | null>(null)
  const [target, setTarget] = useState<{ at: InsertTarget; start: number; end: number } | null>(null)

  const err = (field: string) => (showErrors ? errors[field] : undefined)
  const isAbsolute = /^https?:\/\//i.test(step.url.trim())
  const resolvedUrl = isAbsolute ? step.url.trim() : `${baseUrl.replace(/\/+$/, '')}${step.url.trim().startsWith('/') ? '' : '/'}${step.url.trim()}`
  const mismatch = baseUrl ? urlMismatch(baseUrl, step.url) : null
  const bodyFormat = detectBodyFormat(step.body)
  const needsContentType = bodyFormat === 'json' && !hasHeader(step.headers, 'Content-Type')
  const headerCount = step.headers.filter((h) => h.key.trim()).length
  const hasCapture = !!(step.captureVariableName.trim() && step.captureJsonPath.trim())
  const pathVariables = detectPathVariables(step.url)
  const paramCount = pathVariables.length + step.queryParams.filter((q) => q.enabled && q.key.trim()).length
  const warnings = Array.from(new Set(stepVariableNames(step).flatMap((name) =>
    valueWarnings(`\${${name}}`, variables, laterCaptures, hasCsv))))

  // ------------------------------------------------------------ mises à jour

  const setHeaders = (headers: HeaderRow[]) => onChange({ headers })
  const updateHeader = (i: number, patch: Partial<HeaderRow>) =>
    setHeaders(step.headers.map((h, idx) => (idx === i ? { ...h, ...patch } : h)))
  const addHeader = (row: HeaderRow = { key: '', value: '' }) => {
    if (row.key && hasHeader(step.headers, row.key)) {
      setHeaders(step.headers.map((h) => (h.key.trim().toLowerCase() === row.key.toLowerCase() ? { ...h, value: row.value } : h)))
    } else {
      setHeaders([...step.headers, row])
    }
  }
  /** URL ↔ tableau des paramètres : synchronisés dans les deux sens. */
  const setUrl = (url: string) => onChange({ url, queryParams: queryRowsFromUrl(url, step.queryParams) })
  const setQueryRows = (rows: QueryParamRow[]) => onChange({ queryParams: rows, url: urlWithQuery(step.url, rows) })
  const updateQuery = (i: number, patch: Partial<QueryParamRow>) =>
    setQueryRows(step.queryParams.map((q, idx) => (idx === i ? { ...q, ...patch } : q)))
  const setPathValue = (name: string, value: string) => onChange({ pathParams: { ...step.pathParams, [name]: value } })

  // ------------------------------------------------------------ insertion de ${nom}

  const remember = (at: InsertTarget) => (e: SyntheticEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    const el = e.currentTarget
    setTarget({ at, start: el.selectionStart ?? el.value.length, end: el.selectionEnd ?? el.value.length })
  }
  const valueOf = (at: InsertTarget): string => {
    switch (at.kind) {
      case 'url': return step.url
      case 'body': return step.body
      case 'header': return step.headers[at.index]?.value ?? ''
      case 'query': return step.queryParams[at.index]?.value ?? ''
      case 'path': return step.pathParams[at.name] ?? ''
    }
  }
  const apply = (at: InsertTarget, value: string) => {
    switch (at.kind) {
      case 'url': return setUrl(value)
      case 'body': return onChange({ body: value })
      case 'header': return updateHeader(at.index, { value })
      case 'query': return updateQuery(at.index, { value })
      case 'path': return setPathValue(at.name, value)
    }
  }
  const targetLabel = (at: InsertTarget): string => {
    switch (at.kind) {
      case 'url': return 'l’URL'
      case 'body': return 'le corps'
      case 'header': return `l’en-tête « ${step.headers[at.index]?.key || at.index + 1} »`
      case 'query': return `le paramètre « ${step.queryParams[at.index]?.key || at.index + 1} »`
      case 'path': return `la variable de chemin « ${at.name} »`
    }
  }
  const insertVariable = async (name: string) => {
    const token = `\${${name}}`
    if (target) {
      const current = valueOf(target.at)
      const start = Math.min(target.start, current.length)
      const end = Math.min(Math.max(target.end, start), current.length)
      apply(target.at, current.slice(0, start) + token + current.slice(end))
      setTarget({ ...target, start: start + token.length, end: start + token.length })
      setCopied(name)
      window.setTimeout(() => setCopied(null), 1400)
      return
    }
    try {
      await navigator.clipboard.writeText(token)
      setCopied(name)
      window.setTimeout(() => setCopied(null), 1400)
    } catch {
      // Presse-papiers indisponible : le jeton reste lisible dans la puce.
    }
  }

  const tabs: { id: TabId; label: string; icon: string; badge?: string | number; error?: boolean }[] = [
    { id: 'params', label: 'Paramètres', icon: 'bi-sliders2', badge: paramCount || undefined, error: !!err('queryParams') },
    { id: 'headers', label: 'En-têtes', icon: 'bi-list-ul', badge: headerCount || undefined, error: !!err('headers') },
    { id: 'body', label: 'Corps', icon: 'bi-braces', badge: bodyFormat === 'empty' ? undefined : bodyFormat === 'json' ? 'JSON' : 'Texte', error: !!err('body') },
    { id: 'validation', label: 'Validation', icon: 'bi-check2-circle', badge: step.expectedStatus || undefined, error: !!(err('expectedStatus') || err('assertionBodyContains')) },
    { id: 'variables', label: 'Variables', icon: 'bi-box-arrow-in-down-right', badge: hasCapture ? step.captureVariableName.trim() : undefined, error: !!(err('captureVariableName') || err('captureJsonPath')) },
  ]

  return (
    <div className="sw-editor">
      <div className="sw-editor-head">
        <span className="sw-editor-index">Étape {index + 1}</span>
        <input
          className={`sw-editor-title ${err('name') ? 'is-invalid' : ''}`}
          value={step.name}
          placeholder="Nom de l’étape (ex : Connexion)"
          maxLength={255}
          onChange={(e) => onChange({ name: e.target.value })}
          aria-label="Nom de l’étape"
        />
        {err('name') && <div className="sw-field-error">{err('name')}</div>}
      </div>

      <div className={`sw-request-line ${err('url') ? 'is-invalid' : ''}`}>
        <select
          className={`sw-method-select sw-method-${step.method.toLowerCase()}`}
          value={step.method}
          onChange={(e) => {
            const method = e.target.value as BackendHttpMethod
            onChange({ method })
            if (METHODS_WITH_BODY.includes(method) && tab === 'headers' && !step.headers.length) setTab('body')
          }}
          aria-label="Méthode HTTP"
        >
          {HTTP_METHODS.map((m) => (
            <option key={m} value={m}>{m}</option>
          ))}
        </select>
        {!isAbsolute && baseUrl && <span className="sw-base-url" title={baseUrl}>{baseUrl.replace(/\/+$/, '')}</span>}
        <input
          className="sw-url-input"
          value={step.url}
          placeholder="/api/ressource/:id?page=1"
          onChange={(e: ChangeEvent<HTMLInputElement>) => { setUrl(e.target.value); remember({ kind: 'url' })(e) }}
          onFocus={remember({ kind: 'url' })}
          onSelect={remember({ kind: 'url' })}
          aria-label="Chemin ou URL"
          spellCheck={false}
        />
      </div>
      {err('url') ? (
        <div className="sw-field-error">{err('url')}</div>
      ) : (
        step.url.trim() && (
          <div className="sw-field-hint">
            <i className="bi bi-arrow-return-right"></i> Appel réel : <code>{step.method} {resolvedUrl}</code>
            {(pathVariables.length > 0 || step.url.includes('${')) && <> — valeurs injectées et encodées à l’exécution</>}
          </div>
        )
      )}

      {mismatch && (
        <div className="sw-inline-alert warn">
          <i className="bi bi-signpost-split"></i>
          <span>
            Cette URL absolue appelle <strong>{mismatch.stepOrigin}</strong> alors que l’application testée est{' '}
            <strong>{mismatch.appOrigin}</strong> : l’étape n’atteindra pas l’application. Les chemins relatifs sont recommandés.
          </span>
          <button type="button" className="sw-chip accent" onClick={() => setUrl(mismatch.suggestedPath)}>
            Convertir en {mismatch.suggestedPath}
          </button>
        </div>
      )}

      {warnings.length > 0 && (
        <div className="sw-inline-alert warn">
          <i className="bi bi-exclamation-triangle"></i>
          <span>{warnings.map((w) => <span key={w} className="d-block">{w}</span>)}</span>
        </div>
      )}

      {step.method === 'GET' && step.body.trim() && (
        <div className="sw-inline-alert warn">
          <i className="bi bi-exclamation-triangle"></i>
          <span>Cette étape a un corps mais utilise <strong>GET</strong> : le corps ne sera pas envoyé. Pour envoyer des données (ex : connexion), utilisez POST.</span>
          <button type="button" className="sw-chip accent" onClick={() => onChange({ method: 'POST' })}>Passer en POST</button>
        </div>
      )}

      <div className="sw-tabs" role="tablist">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            className={`sw-tab ${tab === t.id ? 'active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            <i className={`bi ${t.icon}`}></i>
            <span>{t.label}</span>
            {t.error ? <span className="sw-tab-dot" aria-label="Erreur"></span> : t.badge !== undefined && <span className="sw-tab-badge">{t.badge}</span>}
          </button>
        ))}
      </div>

      <div className="sw-tab-panel">
        {tab === 'params' && (
          <div className="d-flex flex-column gap-3">
            <div>
              <div className="sw-section-label">Variables de chemin</div>
              {pathVariables.length === 0 ? (
                <div className="sw-muted-box">Aucune. Écrivez <code>/:nom</code> ou <code>/{'{'}nom{'}'}</code> dans l’URL (ex : <code>/api/transfers/:transferId</code>).</div>
              ) : (
                <div className="d-flex flex-column gap-2">
                  {pathVariables.map((name) => (
                    <div key={name} className="sw-param-row">
                      <code className="sw-param-key">{name}</code>
                      <VariableInput
                        value={step.pathParams[name] ?? ''}
                        placeholder={`\${${name}}`}
                        variables={variables}
                        laterCaptures={laterCaptures}
                        ariaLabel={`Valeur de ${name}`}
                        onChange={(value) => setPathValue(name, value)}
                        onRemember={remember({ kind: 'path', name })}
                      />
                    </div>
                  ))}
                  <div className="sw-field-hint">Vide = <code>${'{'}nom{'}'}</code> de même nom. Valeur encodée dans l’URL à l’exécution.</div>
                </div>
              )}
            </div>
            <div>
              <div className="sw-section-label">Paramètres de requête</div>
              {step.queryParams.length === 0 && <div className="sw-muted-box">Aucun. Ajoutez-en un ou écrivez <code>?cle=valeur</code> dans l’URL.</div>}
              <div className="d-flex flex-column gap-2">
                {step.queryParams.map((q, i) => (
                  <div key={i} className="sw-param-row">
                    <input type="checkbox" checked={q.enabled} onChange={(e) => updateQuery(i, { enabled: e.target.checked })}
                      aria-label={`Activer le paramètre ${q.key || i + 1}`} title="Activé" />
                    <input className="pt-form-control sw-param-key-input" placeholder="Clé" value={q.key} spellCheck={false}
                      onChange={(e) => updateQuery(i, { key: e.target.value })} aria-label={`Clé du paramètre ${i + 1}`} />
                    <VariableInput
                      value={q.value}
                      variables={variables}
                      laterCaptures={laterCaptures}
                      ariaLabel={`Valeur du paramètre ${q.key || i + 1}`}
                      onChange={(value) => updateQuery(i, { value })}
                      onRemember={remember({ kind: 'query', index: i })}
                    />
                    <button type="button" className="sw-icon-btn danger" onClick={() => setQueryRows(step.queryParams.filter((_, idx) => idx !== i))} aria-label="Supprimer le paramètre">
                      <i className="bi bi-x-lg"></i>
                    </button>
                  </div>
                ))}
              </div>
              {err('queryParams') && <div className="sw-field-error">{err('queryParams')}</div>}
              <div className="sw-chip-row mt-2">
                <button type="button" className="sw-chip" onClick={() => setQueryRows([...step.queryParams, { key: '', value: '', enabled: true }])}>
                  <i className="bi bi-plus-lg"></i> Ajouter un paramètre
                </button>
              </div>
            </div>
          </div>
        )}

        {tab === 'headers' && (
          <div className="d-flex flex-column gap-2">
            {step.headers.length === 0 && <div className="sw-muted-box">Aucun en-tête. Ajoutez-en un ou utilisez un raccourci ci-dessous.</div>}
            {step.headers.map((h, i) => (
              <div key={i} className="sw-kv-row">
                <input className="pt-form-control" placeholder="Nom (ex : Authorization)" value={h.key} onChange={(e) => updateHeader(i, { key: e.target.value })} spellCheck={false} />
                <input className="pt-form-control" placeholder="Valeur" value={h.value} spellCheck={false}
                  onChange={(e) => { updateHeader(i, { value: e.target.value }); remember({ kind: 'header', index: i })(e) }}
                  onFocus={remember({ kind: 'header', index: i })} onSelect={remember({ kind: 'header', index: i })}
                  aria-label={`Valeur de l’en-tête ${h.key || i + 1}`} />
                <button type="button" className="sw-icon-btn danger" onClick={() => setHeaders(step.headers.filter((_, idx) => idx !== i))} aria-label="Supprimer l’en-tête">
                  <i className="bi bi-x-lg"></i>
                </button>
              </div>
            ))}
            {err('headers') && <div className="sw-field-error">{err('headers')}</div>}
            <div className="sw-chip-row">
              <button type="button" className="sw-chip" onClick={() => addHeader()}>
                <i className="bi bi-plus-lg"></i> Ajouter un en-tête
              </button>
              {!hasHeader(step.headers, 'Content-Type') && (
                <button type="button" className="sw-chip" onClick={() => addHeader({ key: 'Content-Type', value: 'application/json' })}>
                  Content-Type: JSON
                </button>
              )}
              {variables.filter((v) => v.kind === 'capture' && /token|jwt|access/i.test(v.name)).slice(0, 1).map((v) => (
                <button key={v.name} type="button" className="sw-chip accent" onClick={() => addHeader({ key: 'Authorization', value: `Bearer \${${v.name}}` })}>
                  <i className="bi bi-shield-lock"></i> Authorization: Bearer ${'{'}{v.name}{'}'}
                </button>
              ))}
            </div>
          </div>
        )}

        {tab === 'body' && (
          <div className="d-flex flex-column gap-2">
            {!METHODS_WITH_BODY.includes(step.method) && step.body.trim() === '' && (
              <div className="sw-muted-box">Une requête {step.method} n’envoie généralement pas de corps.</div>
            )}
            <textarea
              className={`sw-code ${err('body') ? 'is-invalid' : ''}`}
              rows={8}
              value={step.body}
              placeholder={'{\n  "cle": "valeur"\n}'}
              onChange={(e) => { onChange({ body: e.target.value }); remember({ kind: 'body' })(e) }}
              onFocus={remember({ kind: 'body' })}
              onSelect={remember({ kind: 'body' })}
              spellCheck={false}
              aria-label="Corps de la requête"
            />
            {err('body') && <div className="sw-field-error">{err('body')}</div>}
            <div className="d-flex align-items-center flex-wrap gap-2">
              {bodyFormat === 'json' && <span className="sw-status-chip ok"><i className="bi bi-check-circle"></i> JSON valide</span>}
              {bodyFormat === 'text' && <span className="sw-status-chip warn"><i className="bi bi-exclamation-circle"></i> Pas du JSON valide (envoyé tel quel)</span>}
              {bodyFormat !== 'empty' && (
                <button
                  type="button"
                  className="sw-chip"
                  disabled={formatJsonBody(step.body) === null}
                  onClick={() => {
                    const formatted = formatJsonBody(step.body)
                    if (formatted !== null) onChange({ body: formatted })
                  }}
                >
                  <i className="bi bi-magic"></i> Formater
                </button>
              )}
            </div>
            {needsContentType && (
              <div className="sw-inline-alert">
                <i className="bi bi-info-circle"></i>
                <span>Le corps est en JSON mais aucun en-tête <code>Content-Type</code> n’est défini : le serveur risque de ne pas le lire.</span>
                <button type="button" className="sw-chip accent" onClick={() => addHeader({ key: 'Content-Type', value: 'application/json' })}>Ajouter</button>
              </div>
            )}
          </div>
        )}

        {tab === 'validation' && (
          <div className="row g-3">
            <div className="col-12 col-md-5">
              <label className="pt-form-label">Code HTTP attendu</label>
              <input
                className={`pt-form-control ${err('expectedStatus') ? 'is-invalid' : ''}`}
                inputMode="numeric"
                placeholder="200–399 par défaut"
                value={step.expectedStatus}
                onChange={(e) => onChange({ expectedStatus: e.target.value })}
              />
              {err('expectedStatus') && <div className="sw-field-error">{err('expectedStatus')}</div>}
              <div className="sw-chip-row mt-2">
                {['200', '201', '204'].map((code) => (
                  <button key={code} type="button" className={`sw-chip ${step.expectedStatus === code ? 'selected' : ''}`} onClick={() => onChange({ expectedStatus: step.expectedStatus === code ? '' : code })}>
                    {code}
                  </button>
                ))}
              </div>
            </div>
            <div className="col-12 col-md-7">
              <label className="pt-form-label">La réponse doit contenir</label>
              <input
                className={`pt-form-control ${err('assertionBodyContains') ? 'is-invalid' : ''}`}
                placeholder='Ex : "success":true'
                value={step.assertionBodyContains}
                maxLength={500}
                onChange={(e) => onChange({ assertionBodyContains: e.target.value })}
              />
              {err('assertionBodyContains') ? (
                <div className="sw-field-error">{err('assertionBodyContains')}</div>
              ) : (
                <div className="sw-field-hint">Optionnel. L’étape échoue si ce texte est absent de la réponse.</div>
              )}
            </div>
          </div>
        )}

        {tab === 'variables' && (
          <div className="d-flex flex-column gap-3">
            <div>
              <div className="sw-section-label">Réutiliser une valeur</div>
              {variables.length === 0 ? (
                <div className="sw-muted-box">
                  Aucune variable disponible pour cette étape. Capturez une valeur dans une étape précédente (ci-dessous),
                  ajoutez un jeu de données CSV ou des variables du scénario.
                </div>
              ) : (
                <>
                  <div className="sw-chip-row">
                    {variables.map((v) => (
                      <button key={`${v.kind}-${v.name}`} type="button" className="sw-var-chip" onClick={() => insertVariable(v.name)}
                        title={target ? `Insérer dans ${targetLabel(target.at)}` : 'Copier'}>
                        <code>${'{'}{v.name}{'}'}</code>
                        <span>{copied === v.name ? (target ? 'Inséré !' : 'Copié !') : v.label}</span>
                      </button>
                    ))}
                  </div>
                  <div className="sw-field-hint mt-1">
                    {target
                      ? <>Un clic insère la variable dans {targetLabel(target.at)} (dernier champ édité), à la position du curseur.</>
                      : 'Cliquez d’abord dans l’URL, un en-tête, le corps ou un paramètre : un clic sur une variable l’y insérera. Sinon, elle est copiée.'}
                    {' '}Priorité : capture, puis CSV, puis scénario, puis authToken.
                  </div>
                </>
              )}
            </div>
            <div>
              <div className="sw-section-label">Capturer une valeur de la réponse</div>
              <div className="row g-2">
                <div className="col-12 col-md-5">
                  <input
                    className={`pt-form-control ${err('captureVariableName') ? 'is-invalid' : ''}`}
                    placeholder="Nom (ex : token)"
                    value={step.captureVariableName}
                    onChange={(e) => onChange({ captureVariableName: e.target.value })}
                    spellCheck={false}
                  />
                  {err('captureVariableName') && <div className="sw-field-error">{err('captureVariableName')}</div>}
                </div>
                <div className="col-12 col-md-7">
                  <input
                    className={`pt-form-control ${err('captureJsonPath') ? 'is-invalid' : ''}`}
                    placeholder="Chemin JSON (ex : $.token ou $.data.id)"
                    value={step.captureJsonPath}
                    onChange={(e) => onChange({ captureJsonPath: e.target.value })}
                    spellCheck={false}
                  />
                  {err('captureJsonPath') && <div className="sw-field-error">{err('captureJsonPath')}</div>}
                </div>
              </div>
              <div className="sw-field-hint mt-1">
                {hasCapture
                  ? <>Les étapes suivantes pourront utiliser <code>${'{'}{step.captureVariableName.trim()}{'}'}</code> (propre à chaque utilisateur virtuel et à chaque itération).</>
                  : 'Chemin simple par points (pas de tableaux ni de filtres).'}
              </div>
            </div>
          </div>
        )}

      </div>
    </div>
  )
}

export default StepEditor
