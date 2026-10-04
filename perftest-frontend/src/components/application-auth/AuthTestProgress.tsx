import { BackendApplicationAuthType, BackendApplicationStatus } from '../../types/backendContracts'

// ============================================================
// Progression "enregistrement → vérification de l'authentification",
// puis résultat animé. Utilisé par la modale (Tester et ajouter /
// Enregistrer et tester) ; accessible (role="status", aria-live).
// ============================================================

export type TestPhase = 'save' | 'verify' | 'test'

export const PHASE_LABELS: Record<TestPhase, string> = {
  save: 'Enregistrement…',
  verify: "Vérification de l'authentification…",
  test: 'Test de connectivité…',
}

/** Étape de test selon le type : les 4 modes authentifiés vérifient
 * réellement les identifiants (login réel, ou endpoint de vérification) ;
 * Aucune -> simple test de connectivité de l'URL. */
export function testPhasesFor(type: BackendApplicationAuthType | undefined): TestPhase[] {
  return !type || type === 'NONE' ? ['test'] : ['verify']
}

/** Étapes affichées dans la modale (enregistrement + étapes du test). */
export function phasesFor(type: BackendApplicationAuthType | undefined): TestPhase[] {
  return ['save', ...testPhasesFor(type)]
}

export const STATUS_LABELS: Record<BackendApplicationStatus, string> = {
  CONNECTED: 'Connectée',
  FAILED: 'Échec',
  ERROR: 'Erreur',
}

interface AuthTestProgressProps {
  phases: TestPhase[]
  current: TestPhase | null
  result: { status: BackendApplicationStatus; message: string } | null
}

function AuthTestProgress({ phases, current, result }: AuthTestProgressProps) {
  const currentIndex = current ? phases.indexOf(current) : -1

  if (result) {
    const ok = result.status === 'CONNECTED'
    return (
      <div className={`aa-result ${ok ? 'ok' : 'ko'}`} role="status" aria-live="assertive">
        <span className="aa-result-icon" aria-hidden="true">
          <i className={`bi ${ok ? 'bi-check-lg' : result.status === 'ERROR' ? 'bi-wifi-off' : 'bi-x-lg'}`}></i>
        </span>
        <div className="aa-result-text">
          <span className="aa-result-badge">{STATUS_LABELS[result.status]}</span>
          <span className="aa-result-message">{result.message}</span>
        </div>
      </div>
    )
  }

  return (
    <div className="aa-progress" role="status" aria-live="polite">
      <span className="aa-spinner" aria-hidden="true"></span>
      <ol className="aa-steps">
        {phases.map((phase, i) => {
          const state = i < currentIndex ? 'done' : i === currentIndex ? 'active' : 'pending'
          return (
            <li key={phase} className={`aa-step ${state}`} aria-current={state === 'active' ? 'step' : undefined}>
              <span className="aa-step-dot" aria-hidden="true">{state === 'done' && <i className="bi bi-check"></i>}</span>
              <span>{PHASE_LABELS[phase]}</span>
            </li>
          )
        })}
      </ol>
      <span className="visually-hidden">{current ? PHASE_LABELS[current] : ''}</span>
    </div>
  )
}

export default AuthTestProgress
