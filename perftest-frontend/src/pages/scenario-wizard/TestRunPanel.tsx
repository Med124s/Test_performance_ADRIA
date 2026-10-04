import { BackendTestRunAuth, BackendTestRunStepResult } from '../../types/backendContracts'
import { DraftStep, formatMs } from './wizardModel'
import TestResultCard from './TestResultCard'

export type TestPhase = 'idle' | 'saving' | 'running' | 'done' | 'error'

export interface TestRunState {
  phase: TestPhase
  /** 'all' : tout le scénario ; 'step' : une seule étape (bouton « Exécuter »). */
  scope: 'all' | 'step' | null
  /** Étape réellement en cours d'exécution (uid local) et son libellé. */
  runningUid: string | null
  runningLabel: string | null
  /** Résultats détaillés réels indexés par uid d'étape locale. */
  resultsByUid: Record<string, BackendTestRunStepResult>
  auth: BackendTestRunAuth | null
  durationMs: number | null
  timedOut: boolean
  /** Variables connues après le dernier test (captures + CSV, sans secrets) : réutilisées par « Exécuter ». */
  variables: Record<string, string>
  errorMessage: string | null
}

export const INITIAL_TEST_RUN: TestRunState = {
  phase: 'idle',
  scope: null,
  runningUid: null,
  runningLabel: null,
  resultsByUid: {},
  auth: null,
  durationMs: null,
  timedOut: false,
  variables: {},
  errorMessage: null,
}

interface TestRunPanelProps {
  steps: DraftStep[]
  baseUrl: string
  run: TestRunState
  stale: boolean
  onRun: () => void
  onRunStep: (uid: string) => void
  onEditStep: (uid: string) => void
  onUseRelativeUrl: (uid: string, path: string) => void
}

function TestRunPanel({ steps, baseUrl, run, stale, onRun, onRunStep, onEditStep, onUseRelativeUrl }: TestRunPanelProps) {
  const busy = run.phase === 'saving' || run.phase === 'running'
  const results = steps.map((s) => run.resultsByUid[s.uid])
  const passed = results.filter((r) => r?.status === 'PASSED').length
  const failed = results.filter((r) => r?.status === 'FAILED').length
  const tested = results.filter(Boolean).length > 0
  const allPassed = run.phase === 'done' && passed === steps.length && steps.length > 0
  const runningPosition = run.runningUid ? steps.findIndex((s) => s.uid === run.runningUid) + 1 : 0
  const variableNames = Object.keys(run.variables)

  return (
    <div className="sw-test">
      <div className={`sw-test-hero ${allPassed ? 'ok' : run.phase === 'error' || (run.phase === 'done' && tested) ? 'ko' : ''}`}>
        <div className="sw-test-hero-icon">
          {busy ? (
            <span className="spinner-border spinner-border-sm" role="status"></span>
          ) : allPassed ? (
            <i className="bi bi-check-lg"></i>
          ) : run.phase === 'done' || run.phase === 'error' ? (
            <i className="bi bi-exclamation-lg"></i>
          ) : (
            <i className="bi bi-play-fill"></i>
          )}
        </div>
        <div className="flex-grow-1">
          <div className="sw-test-hero-title" aria-live="polite">
            {run.phase === 'idle' && 'Vérifiez votre scénario avant de lancer la charge'}
            {run.phase === 'saving' && 'Enregistrement du scénario…'}
            {run.phase === 'running' && (run.runningLabel
              ? <>Étape {runningPosition || '…'}{run.scope === 'all' ? `/${steps.length}` : ''} en cours : {run.runningLabel}</>
              : 'Connexion à l’application…')}
            {run.phase === 'done' && (allPassed ? 'Toutes les étapes ont réussi' : `${failed || steps.length - passed} étape(s) à corriger`)}
            {run.phase === 'error' && 'Le test n’a pas pu aboutir'}
          </div>
          <div className="sw-test-hero-sub">
            {run.phase === 'idle' && <>Un utilisateur virtuel exécute chaque étape une fois, avec de vraies requêtes vers <code>{baseUrl || 'l’application'}</code>. Aucune exécution n’est ajoutée à l’historique.</>}
            {busy && 'Un utilisateur virtuel, un passage — limité à 60 s.'}
            {run.phase === 'done' && (
              <>
                <strong>{passed}/{steps.length}</strong> étape(s) réussie(s)
                {run.durationMs != null && <> · temps total {formatMs(run.durationMs)}</>}
                {run.timedOut && <> · délai global de 60 s atteint</>}
              </>
            )}
            {run.phase === 'error' && run.errorMessage}
          </div>
        </div>
        <button type="button" className={allPassed && !stale ? 'pt-btn-outline' : 'pt-btn-primary'} onClick={onRun} disabled={busy || steps.length === 0}>
          {busy ? 'En cours…' : !tested ? (<><i className="bi bi-play-fill"></i> Lancer le test</>) : (<><i className="bi bi-arrow-repeat"></i> Relancer le test</>)}
        </button>
      </div>

      {/* Connexion de l'application : affichée uniquement en cas d'échec. */}
      {run.auth && !run.auth.success && (
        <div className={`sw-inline-alert ${run.auth.success ? '' : 'ko'}`}>
          <i className={`bi ${run.auth.success ? 'bi-shield-check' : 'bi-shield-x'}`}></i>
          <span>{run.auth.message}</span>
        </div>
      )}

      {stale && tested && (
        <div className="sw-inline-alert warn">
          <i className="bi bi-pencil"></i>
          <span>Le scénario a été modifié depuis ce test : relancez-le pour vérifier vos changements.</span>
        </div>
      )}

      {run.phase === 'done' && tested && !allPassed && (
        <div className="sw-inline-alert warn">
          <i className="bi bi-exclamation-triangle"></i>
          <span>Corrigez les étapes en échec puis relancez le test : « Suivant » sera disponible quand toutes les étapes réussiront.</span>
        </div>
      )}

      {variableNames.length > 0 && (
        <div className="sw-field-hint">
          <i className="bi bi-box-arrow-in-down-right"></i> Variables disponibles pour « Exécuter » :{' '}
          {variableNames.map((n) => <code key={n} className="me-1">{'${'}{n}{'}'} = {run.variables[n]}</code>)}
        </div>
      )}

      <ol className="sw-result-list">
        {steps.map((step, i) => (
          <TestResultCard
            key={step.uid}
            step={step}
            index={i}
            baseUrl={baseUrl}
            result={results[i]}
            running={run.phase === 'running' && run.runningUid === step.uid}
            disabled={busy}
            onRunStep={() => onRunStep(step.uid)}
            onEditStep={() => onEditStep(step.uid)}
            onUseRelativeUrl={(path) => onUseRelativeUrl(step.uid, path)}
          />
        ))}
      </ol>
    </div>
  )
}

export default TestRunPanel
