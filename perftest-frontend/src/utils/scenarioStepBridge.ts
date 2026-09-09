// Petit pont de données entre la page "Étapes" (CreateScenario) et la page
// "Configuration détaillée d'une étape" (CreateStep). Ces deux pages sont des
// routes séparées sans store partagé : on utilise le sessionStorage pour
// relier les deux écrans (liste d'étapes + détails avancés par étape).

export interface CoreStep {
  id: string
  order: number
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS' | 'TRACE'
  name: string
  url: string
  timeout: string
  status: 'Actif' | 'Inactif'
}

export interface StepDetails {
  description: string
  followRedirects: boolean
  activeStep: boolean
  timeoutMs: number
  headers: { id: number; key: string; value: string; enabled: boolean }[]
  bodyJson: string
  assertions: {
    id: number
    type: string
    property: string
    operator: string
    targetValue: string
    status: 'passed' | 'failed' | 'pending'
  }[]
  pauseBeforeMs: number
  pacingAfterMs: number
}

export interface ScenarioMeta {
  name: string
  application: string
  /** ID réel (JSON Server) de l'application liée — sans lui, revenir de
   * CreateStep en mode édition perdrait la sélection (voir CreateScenario). */
  applicationId?: string
  description: string
}

/** État des étapes 2 (Configuration), 3 (Utilisateurs) et 4 (Planification)
 * du wizard scénario — vit dans CreateStep.tsx, mais doit survivre à un
 * aller-retour complet vers CreateScenario.tsx (Étape 1, autre route/autre
 * montage React) exactement comme ScenarioMeta et les étapes elles-mêmes. */
export interface ScenarioWizardConfig {
  status: 'Actif' | 'Inactif'
  virtualUsers: number
  rampUpSeconds: number
  userProfile: string
  dataSource: 'manual' | 'csv'
  csvFileName: string
  testVariables: { id: number; name: string; value: string }[]
  executionType: 'immediate' | 'scheduled' | 'recurring'
  scheduledDate: string
  scheduledTime: string
  recurrence: string
}

const STEPS_KEY = 'pt_scenario_steps'
const EDITING_ID_KEY = 'pt_editing_step_id'
const DETAILS_KEY_PREFIX = 'pt_step_details_'
const NEW_STEP_FLAG_KEY = 'pt_editing_step_is_new'
const META_KEY = 'pt_scenario_meta'
const EDITING_SCENARIO_ID_KEY = 'pt_editing_scenario_id'
const WIZARD_CONFIG_KEY = 'pt_scenario_wizard_config'

/**
 * Identifiant réel (JSON Server) du scénario en cours de modification, s'il
 * y en a un. CreateStep n'a aucune trace de "?edit=<id>" dans sa propre URL
 * (CreateScenario y navigue sans jamais transmettre ce paramètre) : c'est ce
 * pont qui permet à CreateStep de reconstruire une navigation de retour vers
 * "/scenarios/create?edit=<id>" plutôt que de perdre le contexte d'édition
 * (ce qui provoquerait la création d'un scénario en double au lieu de la
 * mise à jour de l'existant).
 */
export function saveEditingScenarioId(id: string | null) {
  try {
    if (id) sessionStorage.setItem(EDITING_SCENARIO_ID_KEY, id)
    else sessionStorage.removeItem(EDITING_SCENARIO_ID_KEY)
  } catch {
    // ignore
  }
}

export function loadEditingScenarioId(): string | null {
  try {
    return sessionStorage.getItem(EDITING_SCENARIO_ID_KEY)
  } catch {
    return null
  }
}

const RETURNING_FROM_STEP_KEY = 'pt_returning_from_step_config'

/**
 * Signal ponctuel posé par CreateStep juste avant de revenir vers
 * CreateScenario en mode édition. Sans lui, CreateScenario ne pourrait pas
 * distinguer "je reviens de configurer une étape, garde mes modifications
 * locales non enregistrées" de "l'utilisateur vient tout juste de cliquer
 * sur Modifier, va chercher les vraies données à jour" — et risquerait soit
 * d'écraser un brouillon en cours, soit de rester bloqué sur un brouillon
 * périmé d'une session d'édition précédemment abandonnée.
 *
 * Lecture non destructive à dessein (peek + clear séparés) : l'effet qui
 * l'utilise peut être invoqué deux fois d'affilée par React.StrictMode en
 * développement, et une lecture "consommante" romprait ce doublon.
 */
export function markReturningFromStepConfig() {
  try {
    sessionStorage.setItem(RETURNING_FROM_STEP_KEY, '1')
  } catch {
    // ignore
  }
}

export function peekReturningFromStepConfig(): boolean {
  try {
    return sessionStorage.getItem(RETURNING_FROM_STEP_KEY) === '1'
  } catch {
    return false
  }
}

export function clearReturningFromStepConfig() {
  try {
    sessionStorage.removeItem(RETURNING_FROM_STEP_KEY)
  } catch {
    // ignore
  }
}

export function saveScenarioMeta(meta: ScenarioMeta) {
  try {
    sessionStorage.setItem(META_KEY, JSON.stringify(meta))
  } catch {
    // ignore
  }
}

export function loadScenarioMeta(): ScenarioMeta | null {
  try {
    const raw = sessionStorage.getItem(META_KEY)
    return raw ? (JSON.parse(raw) as ScenarioMeta) : null
  } catch {
    return null
  }
}

export function saveWizardConfig(config: ScenarioWizardConfig) {
  try {
    sessionStorage.setItem(WIZARD_CONFIG_KEY, JSON.stringify(config))
  } catch {
    // ignore
  }
}

export function loadWizardConfig(): ScenarioWizardConfig | null {
  try {
    const raw = sessionStorage.getItem(WIZARD_CONFIG_KEY)
    return raw ? (JSON.parse(raw) as ScenarioWizardConfig) : null
  } catch {
    return null
  }
}

export function saveSteps(steps: CoreStep[]) {
  try {
    sessionStorage.setItem(STEPS_KEY, JSON.stringify(steps))
  } catch {
    // sessionStorage indisponible : on ignore silencieusement
  }
}

export function loadSteps(): CoreStep[] | null {
  try {
    const raw = sessionStorage.getItem(STEPS_KEY)
    return raw ? (JSON.parse(raw) as CoreStep[]) : null
  } catch {
    return null
  }
}

export function setEditingStepId(id: string, isNew: boolean = false) {
  try {
    sessionStorage.setItem(EDITING_ID_KEY, String(id))
    sessionStorage.setItem(NEW_STEP_FLAG_KEY, isNew ? '1' : '0')
  } catch {
    // ignore
  }
}

export function getEditingStepId(): string | null {
  try {
    return sessionStorage.getItem(EDITING_ID_KEY)
  } catch {
    return null
  }
}

export function isEditingStepNew(): boolean {
  try {
    return sessionStorage.getItem(NEW_STEP_FLAG_KEY) === '1'
  } catch {
    return false
  }
}

export function saveStepDetails(id: string, details: StepDetails) {
  try {
    sessionStorage.setItem(DETAILS_KEY_PREFIX + id, JSON.stringify(details))
  } catch {
    // ignore
  }
}

export function loadStepDetails(id: string): StepDetails | null {
  try {
    const raw = sessionStorage.getItem(DETAILS_KEY_PREFIX + id)
    return raw ? (JSON.parse(raw) as StepDetails) : null
  } catch {
    return null
  }
}

export function updateStepCore(id: string, patch: Partial<CoreStep>) {
  const steps = loadSteps()
  if (!steps) return
  // Comparaison en string des deux côtés : voir CreateScenario.tsx, la
  // même classe de bug (id numérique de json-server vs id string attendu)
  // faisait déjà échouer silencieusement ce genre de lookup ailleurs.
  const updated = steps.map((s) => (String(s.id) === String(id) ? { ...s, ...patch } : s))
  saveSteps(updated)
}

// Réinitialise complètement le pont (nouveau scénario) : on efface la
// liste d'étapes précédente ainsi que tous les détails avancés déjà
// enregistrés, pour éviter qu'un nouveau scénario n'hérite par erreur des
// données (headers/body/assertions) d'un scénario précédent qui utiliserait
// les mêmes numéros d'étape.
export function resetAll() {
  try {
    const keysToRemove: string[] = []
    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i)
      if (key && key.startsWith('pt_')) {
        keysToRemove.push(key)
      }
    }
    keysToRemove.forEach((key) => sessionStorage.removeItem(key))
  } catch {
    // ignore
  }
}
