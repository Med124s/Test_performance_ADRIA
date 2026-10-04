import { useEffect, useMemo, useRef, useState } from 'react'
import {
  BackendApplicationResponse,
  BackendScenarioResponse,
  BackendStepResponse,
  BackendTestRunResponse,
} from '../../types/backendContracts'
import { scenariosBackendApi } from '../../services/api/scenariosBackend'
import { stepsBackendApi } from '../../services/api/stepsBackend'
import StepEditor from './StepEditor'
import TestResultCard from './TestResultCard'
import { analyzeCsv } from './csvModel'
import { fullRequest, loadFromScenario, savedInfo, stepFromBackend } from './draftModel'
import { availableVariables, laterCaptures } from './variablesModel'
import { DraftStep, describeApiError, hasErrors, newStep, stepToRequest, validateStep } from './wizardModel'
import { isBlankStep } from './draftModel'
import './scenarioWizard.css'

interface StepDrawerProps {
  scenario: BackendScenarioResponse
  application: BackendApplicationResponse | undefined
  /** Étapes enregistrées du scénario, dans l'ordre. */
  steps: BackendStepResponse[]
  /** Étape à modifier ; null = ajout en fin de scénario. */
  editing: BackendStepResponse | null
  onClose: () => void
  onSaved: (message: string) => void
}

/**
 * Panneau latéral « Ajouter une étape » / « Modifier l'étape » (page Scénarios) : même éditeur
 * que l'assistant (StepEditor), « Tester cette étape » sur la version affichée (les étapes
 * précédentes sont jouées pour leurs captures, rien n'est enregistré), puis enregistrement.
 */
function StepDrawer({ scenario, application, steps, editing, onClose, onSaved }: StepDrawerProps) {
  const [step, setStep] = useState<DraftStep>(() => (editing ? stepFromBackend(editing) : newStep()))
  const [attempted, setAttempted] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [test, setTest] = useState<BackendTestRunResponse | null>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const titleId = editing ? 'step-drawer-edit' : 'step-drawer-add'

  // Étapes du scénario avec celle en cours d'édition (remplacée, ou ajoutée en fin).
  const position = editing ? steps.findIndex((s) => s.id === editing.id) : steps.length
  const allSteps = useMemo(() => {
    const drafts = steps.map(stepFromBackend)
    return editing ? drafts.map((d, i) => (i === position ? step : d)) : [...drafts, step]
  }, [steps, editing, position, step])
  const info = useMemo(() => savedInfo(scenario), [scenario])
  const csv = analyzeCsv(info.csvData)
  const csvColumns = csv.error ? [] : csv.columns
  const errors = validateStep(step)
  const showErrors = attempted || !isBlankStep(step)

  // Accessibilité : focus initial dans le panneau, Échap pour fermer.
  useEffect(() => {
    panelRef.current?.querySelector<HTMLInputElement>('input')?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !saving && !testing) onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose, saving, testing])

  const save = async () => {
    setAttempted(true)
    if (hasErrors(errors)) return
    setSaving(true)
    setError(null)
    try {
      const request = stepToRequest(step, scenario.id, editing ? editing.order : steps.length + 1)
      if (editing) await stepsBackendApi.update(editing.id, request)
      else await stepsBackendApi.create(request)
      onSaved(editing ? `Étape « ${step.name.trim()} » modifiée.` : `Étape « ${step.name.trim()} » ajoutée.`)
    } catch (err) {
      setError(describeApiError(err, 'Enregistrement de l’étape impossible.'))
    } finally {
      setSaving(false)
    }
  }

  const runTest = async () => {
    setAttempted(true)
    if (hasErrors(errors)) return
    setTesting(true)
    setError(null)
    try {
      const draft = fullRequest(scenario.id, info, allSteps, loadFromScenario(scenario))
      setTest(await scenariosBackendApi.testRunDraft(scenario.id, draft, position + 1))
    } catch (err) {
      setError(describeApiError(err, 'Test de l’étape impossible.'))
    } finally {
      setTesting(false)
    }
  }

  const result = test?.steps[test.steps.length - 1]
  const previous = test ? test.steps.slice(0, -1) : []
  const previousFailed = previous.find((r) => !r.success)

  return (
    <div className="sw-drawer-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget && !saving && !testing) onClose() }}>
      <div ref={panelRef} className="sw-drawer" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="sw-drawer-head">
          <div>
            <h2 id={titleId}>{editing ? 'Modifier l’étape' : 'Ajouter une étape'}</h2>
            <p>{scenario.name} · étape {position + 1}{application ? ` · ${application.url}` : ''}</p>
          </div>
          <button type="button" className="btn-close" onClick={onClose} aria-label="Fermer" disabled={saving || testing}></button>
        </header>

        <div className="sw-drawer-body">
          <StepEditor
            step={step}
            index={position}
            baseUrl={application?.url ?? ''}
            errors={errors}
            showErrors={showErrors}
            variables={availableVariables(allSteps, position, csvColumns, info.variables, application?.auth?.type === 'LOGIN_TOKEN')}
            laterCaptures={laterCaptures(allSteps, position)}
            hasCsv={csvColumns.length > 0}
            onChange={(patch) => { setStep((s) => ({ ...s, ...patch })); setTest(null) }}
          />

          {(testing || result) && (
            <section className="sw-drawer-test" aria-live="polite">
              <div className="sw-section-label">Résultat du test</div>
              {testing && <div className="sw-muted-box"><span className="spinner-border spinner-border-sm me-2"></span>Test en cours…</div>}
              {!testing && test && !test.auth.success && (
                <div className="sw-inline-alert ko"><i className="bi bi-shield-x"></i><span>{test.auth.message}</span></div>
              )}
              {!testing && previous.length > 0 && (
                <div className="sw-field-hint mb-2">
                  {previousFailed
                    ? <>Étape {previousFailed.index} « {previousFailed.name} » en échec : cette étape n’a pas pu être exécutée.</>
                    : <>{previous.length} étape(s) précédente(s) exécutée(s) pour fournir leurs captures.</>}
                </div>
              )}
              {!testing && result && (
                <ol className="sw-result-list">
                  <TestResultCard step={step} index={position} baseUrl={application?.url ?? ''} result={result} running={false}
                    disabled={testing || saving} onRunStep={runTest} onEditStep={() => panelRef.current?.querySelector<HTMLInputElement>('.sw-url-input')?.focus()}
                    onUseRelativeUrl={(path) => setStep((s) => ({ ...s, url: path }))} />
                </ol>
              )}
            </section>
          )}
          {error && <div className="sw-inline-alert ko mt-3"><i className="bi bi-exclamation-octagon"></i><span>{error}</span></div>}
        </div>

        <footer className="sw-drawer-foot">
          <button type="button" className="pt-btn-ghost" onClick={onClose} disabled={saving || testing}>Annuler</button>
          <div className="d-flex gap-2 flex-wrap">
            <button type="button" className="pt-btn-outline" onClick={runTest} disabled={saving || testing}>
              {testing ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-play-circle"></i>} Tester cette étape
            </button>
            <button type="button" className="pt-btn-primary" onClick={save} disabled={saving || testing}>
              {saving ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-check-lg"></i>} {editing ? 'Enregistrer l’étape' : 'Ajouter l’étape'}
            </button>
          </div>
        </footer>
      </div>
    </div>
  )
}

export default StepDrawer
