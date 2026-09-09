import { http } from './httpClient'
import { Execution, ExecutionStatus, StepResult } from '../../types'

const RESOURCE = '/executions'

export const executionsApi = {
  getAll: () => http.get<Execution[]>(RESOURCE),
  getByScenario: (scenarioId: string) =>
    http.get<Execution[]>(`${RESOURCE}?scenarioId=${scenarioId}&_sort=startedAt&_order=desc`),
  getByApplication: (applicationId: string) =>
    http.get<Execution[]>(`${RESOURCE}?applicationId=${applicationId}&_sort=startedAt&_order=desc`),
  getById: (id: string) => http.get<Execution>(`${RESOURCE}/${id}`),
  create: (data: Omit<Execution, 'id'>) => http.post<Execution>(RESOURCE, data),
  update: (id: string, data: Partial<Execution>) =>
    http.patch<Execution>(`${RESOURCE}/${id}`, data),
  remove: (id: string) => http.delete<void>(`${RESOURCE}/${id}`),

  /** La dernière exécution réelle d'un scénario donné (ou null s'il n'y en
   * a aucune) — utilisée par la colonne "Détail exécution" et par le
   * statut de connexion des applications. */
  async getLatestForScenario(scenarioId: string): Promise<Execution | null> {
    const list = await executionsApi.getByScenario(scenarioId)
    return list[0] ?? null
  },
}

/**
 * Calcule le statut GLOBAL d'une exécution à partir des statuts réels de
 * ses étapes — c'est la seule source de vérité pour ce statut, jamais
 * saisi manuellement ailleurs :
 *  - une étape encore "running"/"pending"  → l'exécution est "En cours"
 *  - au moins une étape en erreur          → "Avec erreurs" (ou "Échouée"
 *                                             si TOUTES les étapes ont
 *                                             échoué / se sont arrêtées net)
 *  - toutes les étapes réussies            → "Réussie"
 */
export function computeExecutionStatus(stepResults: StepResult[]): ExecutionStatus {
  if (stepResults.length === 0) return 'En cours'
  if (stepResults.some((s) => s.status === 'running' || s.status === 'pending')) {
    return 'En cours'
  }
  const errorCount = stepResults.filter((s) => s.status === 'error').length
  if (errorCount === 0) return 'Réussie'
  if (errorCount === stepResults.length) return 'Échouée'
  return 'Avec erreurs'
}
