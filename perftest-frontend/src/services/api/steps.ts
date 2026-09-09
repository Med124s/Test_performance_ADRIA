import { http } from './httpClient'
import { Step } from '../../types'

const RESOURCE = '/steps'

export const stepsApi = {
  getAll: () => http.get<Step[]>(RESOURCE),
  getByScenario: (scenarioId: string) =>
    http.get<Step[]>(`${RESOURCE}?scenarioId=${scenarioId}&_sort=order`),
  getById: (id: string) => http.get<Step>(`${RESOURCE}/${id}`),
  create: (data: Omit<Step, 'id'>) => http.post<Step>(RESOURCE, data),
  update: (id: string, data: Partial<Step>) => http.patch<Step>(`${RESOURCE}/${id}`, data),
  remove: (id: string) => http.delete<void>(`${RESOURCE}/${id}`),

  /**
   * Remplace entièrement la liste des étapes d'un scénario : supprime les
   * étapes qui ne sont plus présentes, met à jour celles qui existent déjà
   * (id existant) et crée les nouvelles. Utilisé par l'enregistrement du
   * scénario depuis l'assistant de création.
   */
  async replaceForScenario(scenarioId: string, steps: (Step | Omit<Step, 'id'>)[]): Promise<Step[]> {
    const existing = await stepsApi.getByScenario(scenarioId)
    const keptIds = new Set(
      steps.filter((s): s is Step => 'id' in s && !!s.id).map((s) => s.id)
    )
    // Suppressions séquentielles plutôt qu'en parallèle : json-server
    // (--watch, mono-thread) peut réinitialiser une connexion sous plusieurs
    // écritures concurrentes, laissant une étape orpheline en base.
    for (const s of existing.filter((s) => !keptIds.has(s.id))) {
      await stepsApi.remove(s.id)
    }

    const saved: Step[] = []
    for (const step of steps) {
      if ('id' in step && step.id) {
        saved.push(await stepsApi.update(step.id, step))
      } else {
        saved.push(await stepsApi.create({ ...step, scenarioId }))
      }
    }
    return saved
  },
}
