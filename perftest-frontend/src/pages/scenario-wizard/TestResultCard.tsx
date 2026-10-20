import { ReactNode, useState } from 'react'
import { BackendTestRunStepResult } from '../../types/backendContracts'
import { DraftStep, formatMs } from './wizardModel'
import {
  failureHint,
  formatBytes,
  prettyJson,
  statusLabel,
  statusTone,
  tokenizeJson,
  urlMismatch,
} from './testRunModel'

type TabId = 'response' | 'headers' | 'request' | 'checks'

interface TestResultCardProps {
  step: DraftStep
  index: number
  baseUrl: string
  result?: BackendTestRunStepResult
  running: boolean
  disabled: boolean
  onRunStep: () => void
  onEditStep: () => void
  onUseRelativeUrl: (path: string) => void
}

function CodeView({ text, json }: { text: string; json: boolean }) {
  const [copied, setCopied] = useState(false)
  const shown = json ? prettyJson(text) : text
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(shown)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1400)
    } catch {
      // Presse-papiers indisponible : le texte reste sélectionnable.
    }
  }
  return (
    <div className="sw-code-view">
      <button type="button" className="sw-chip sw-code-copy" onClick={copy}>
        <i className={`bi ${copied ? 'bi-check2' : 'bi-clipboard'}`}></i> {copied ? 'Copié' : 'Copier'}
      </button>
      <pre>
        {json
          ? tokenizeJson(shown).map((t, i) => (t.kind === 'plain' ? t.text : <span key={i} className={`sw-json-${t.kind}`}>{t.text}</span>))
          : shown}
      </pre>
    </div>
  )
}

function HeaderTable({ headers, empty }: { headers: Record<string, string>; empty: string }) {
  const entries = Object.entries(headers)
  if (entries.length === 0) return <div className="sw-muted-box">{empty}</div>
  return (
    <table className="sw-header-table">
      <tbody>
        {entries.map(([name, value]) => (
          <tr key={name}>
            <th>{name}</th>
            <td>{value}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function CheckRow({ ok, label, children }: { ok: boolean | null; label: string; children: ReactNode }) {
  return (
    <li className={`sw-check ${ok === null ? 'pending' : ok ? 'ok' : 'ko'}`}>
      <i className={`bi ${ok === null ? 'bi-dash-circle' : ok ? 'bi-check-circle-fill' : 'bi-x-circle-fill'}`}></i>
      <span className="sw-check-label">{label}</span>
      <span className="sw-check-detail">{children}</span>
    </li>
  )
}

function TestResultCard({ step, index, baseUrl, result, running, disabled, onRunStep, onEditStep, onUseRelativeUrl }: TestResultCardProps) {
  const [expanded, setExpanded] = useState<boolean | null>(null)
  const [tab, setTab] = useState<TabId | null>(null)

  const tone = result ? statusTone(result) : null
  const executed = !!result && result.status !== 'SKIPPED'
  const isOpen = executed && (expanded ?? !result!.success)
  const activeTab: TabId = tab ?? (result?.response ? 'response' : 'request')
  const draftMismatch = urlMismatch(baseUrl, step.url)
  const mismatch = result?.urlWarning ?? (draftMismatch && {
    message: `Cette étape appelle ${draftMismatch.stepOrigin} alors que l’application testée est ${draftMismatch.appOrigin}.`,
    suggestedPath: draftMismatch.suggestedPath,
  })
  const hint = result ? failureHint(result) : null

  const tabs: { id: TabId; label: string; hidden?: boolean }[] = [
    { id: 'response', label: 'Réponse', hidden: !result?.response },
    { id: 'headers', label: 'En-têtes', hidden: !result?.response },
    { id: 'request', label: 'Requête envoyée' },
    { id: 'checks', label: 'Vérifications', hidden: !result?.checks },
  ]

  return (
    <li className={`sw-result-card ${tone ?? 'idle'} ${running ? 'running' : ''}`}>
      <div className="sw-result-head">
        <span className="sw-step-num">{index + 1}</span>
        <span className={`sw-method-badge sw-method-${step.method.toLowerCase()}`}>{step.method}</span>
        <span className="sw-timeline-name text-truncate">{step.name || `Étape ${index + 1}`}</span>
        <span className="sw-result-meta">
          {running && <span className="sw-status-chip neutral"><span className="spinner-border spinner-border-sm"></span> En cours…</span>}
          {!running && result && <span className={`sw-status-chip ${tone}`}>{statusLabel(result)}</span>}
          {!running && result?.response && (
            <>
              <span className="sw-status-chip neutral" title="Temps de réponse"><i className="bi bi-stopwatch"></i> {formatMs(result.response.durationMs)}</span>
              <span className="sw-status-chip neutral" title="Taille du corps de la réponse"><i className="bi bi-file-earmark"></i> {formatBytes(result.response.sizeBytes)}</span>
            </>
          )}
          <button type="button" className="sw-chip" onClick={onRunStep} disabled={disabled} title="Exécuter uniquement cette étape (avec les variables déjà capturées)">
            <i className="bi bi-play-fill"></i> Exécuter
          </button>
          {result && !result.success && (
            <button type="button" className="sw-chip" onClick={onEditStep} disabled={disabled}>
              <i className="bi bi-pencil"></i> Corriger
            </button>
          )}
          {executed && (
            <button type="button" className="sw-icon-btn" onClick={() => setExpanded(!isOpen)} aria-expanded={isOpen} aria-label={isOpen ? 'Masquer le détail' : 'Afficher le détail'}>
              <i className={`bi bi-chevron-${isOpen ? 'up' : 'down'}`}></i>
            </button>
          )}
        </span>
      </div>

      <div className="sw-timeline-url">{result?.request ? `${result.request.method} ${result.request.url}` : step.url}</div>

      {mismatch && (
        <div className="sw-inline-alert warn mt-2">
          <i className="bi bi-signpost-split"></i>
          <span>{mismatch.message} Les chemins relatifs (ex. <code>{mismatch.suggestedPath}</code>) sont recommandés.</span>
          <button type="button" className="sw-chip accent" onClick={() => onUseRelativeUrl(mismatch.suggestedPath)} disabled={disabled}>
            Utiliser {mismatch.suggestedPath}
          </button>
        </div>
      )}

      {result && !result.success && result.error && (
        <div className={`sw-result-error ${result.status === 'SKIPPED' ? 'muted' : ''}`}>
          <div><i className={`bi ${result.status === 'SKIPPED' ? 'bi-skip-forward' : 'bi-bug'}`}></i> {result.error}</div>
          {hint && <div className="sw-result-hint"><i className="bi bi-lightbulb"></i> {hint}</div>}
        </div>
      )}
      {result && result.warnings.length > 0 && (
        <ul className="sw-result-warnings">
          {result.warnings.map((w) => <li key={w}><i className="bi bi-exclamation-triangle"></i> {w}</li>)}
        </ul>
      )}

      {isOpen && result && (
        <div className="sw-result-detail">
          <div className="sw-tabs" role="tablist">
            {tabs.filter((t) => !t.hidden).map((t) => (
              <button key={t.id} type="button" role="tab" aria-selected={activeTab === t.id}
                className={`sw-tab ${activeTab === t.id ? 'active' : ''}`} onClick={() => setTab(t.id)}>
                {t.label}
              </button>
            ))}
          </div>
          <div className="sw-tab-panel">
            {activeTab === 'response' && result.response && (
              <>
                {result.response.contentType && <div className="sw-field-hint mb-2">Content-Type : <code>{result.response.contentType}</code></div>}
                {result.response.truncated && (
                  <div className="sw-inline-alert warn mb-2"><i className="bi bi-scissors"></i>
                    <span>Réponse tronquée : 100 Ko affichés sur {formatBytes(result.response.sizeBytes)}.</span></div>
                )}
                {result.response.body ? <CodeView text={result.response.body} json={result.response.json} /> : <div className="sw-muted-box">Corps de réponse vide.</div>}
              </>
            )}
            {activeTab === 'headers' && result.response && (
              <HeaderTable headers={result.response.headers} empty="Aucun en-tête de réponse." />
            )}
            {activeTab === 'request' && result.request && (
              <div className="d-flex flex-column gap-2">
                <code className="sw-request-summary">{result.request.method} {result.request.url}</code>
                <div className="sw-section-label m-0">En-têtes envoyés</div>
                <HeaderTable headers={result.request.headers} empty="Aucun en-tête ajouté (le client HTTP ajoute Host, Content-Length…)." />
                <div className="sw-section-label m-0">Corps envoyé</div>
                {result.request.body ? <CodeView text={result.request.body} json={/^\s*[{[]/.test(result.request.body)} /> : <div className="sw-muted-box">Aucun corps.</div>}
                <div className="sw-field-hint">Délai : {result.request.timeoutSeconds} s · Redirections {result.request.followRedirects ? 'suivies' : 'non suivies'} · secrets masqués (••••••)</div>
              </div>
            )}
            {activeTab === 'checks' && result.checks && (
              <ul className="sw-checks">
                <CheckRow ok={result.checks.status.actual == null ? null : result.checks.status.ok} label="Code HTTP">
                  attendu <strong>{result.checks.status.expected}</strong> · reçu <strong>{result.checks.status.actual ?? '—'}</strong>
                </CheckRow>
                {result.checks.assertion && (
                  <CheckRow ok={result.checks.assertion.ok} label="La réponse contient">
                    <code>{result.checks.assertion.expectedContent}</code>
                    {result.checks.assertion.ok === null ? ' — non évaluée' : result.checks.assertion.ok ? ' — OK' : ' — KO'}
                  </CheckRow>
                )}
                {result.checks.capture && (
                  <CheckRow ok={result.checks.capture.found ? true : result.response ? false : null} label="Variable capturée">
                    <code>{'${'}{result.checks.capture.variableName}{'}'}</code> ← <code>{result.checks.capture.jsonPath}</code>
                    {' '}= {result.checks.capture.found ? <strong>{result.checks.capture.value}</strong> : result.checks.capture.message}
                  </CheckRow>
                )}
                {result.checks.json && (
                  <CheckRow ok={result.checks.json.ok} label="Assertion JSON">
                    <code>{result.checks.json.path}</code> {result.checks.json.operator === 'CONTAINS' ? 'contient' : 'égale'}{' '}
                    <code>{result.checks.json.expected}</code>
                    {result.checks.json.ok === null ? ' — non évaluée' : result.checks.json.ok ? ' — OK' : ` — ${result.checks.json.message ?? 'KO'}`}
                  </CheckRow>
                )}
                {result.checks.responseTime && (
                  <CheckRow ok={result.checks.responseTime.ok} label="Temps de réponse">
                    maximum <strong>{result.checks.responseTime.maxMs} ms</strong> · mesuré{' '}
                    <strong>{result.checks.responseTime.actualMs == null ? '—' : `${result.checks.responseTime.actualMs} ms`}</strong>
                  </CheckRow>
                )}
                {!result.checks.assertion && !result.checks.capture && !result.checks.json && !result.checks.responseTime && (
                  <li className="sw-field-hint">Aucune assertion ni capture configurée pour cette étape.</li>
                )}
              </ul>
            )}
          </div>
        </div>
      )}
    </li>
  )
}

export default TestResultCard
