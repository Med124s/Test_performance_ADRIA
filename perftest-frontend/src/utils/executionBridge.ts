// Relie la page Scénarios à la page Exécutions : quand on clique sur
// "Exécuter" pour un scénario donné, on mémorise ici le scénario + son
// application le temps de la navigation, pour pré-remplir et ouvrir
// automatiquement la modale de lancement sur /executions.

export interface ExecutionPrefill {
  scenario: string
  application: string
}

const KEY = 'pt_execution_prefill'

export function setExecutionPrefill(prefill: ExecutionPrefill) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(prefill))
  } catch {
    // ignore
  }
}

export function consumeExecutionPrefill(): ExecutionPrefill | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    sessionStorage.removeItem(KEY)
    return JSON.parse(raw) as ExecutionPrefill
  } catch {
    return null
  }
}
