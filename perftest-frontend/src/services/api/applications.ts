import { http } from './httpClient'
import { Application, ApplicationConnectionStatus, Execution } from '../../types'

const RESOURCE = '/applications'

export const applicationsApi = {
  getAll: () => http.get<Application[]>(RESOURCE),
  getById: (id: string) => http.get<Application>(`${RESOURCE}/${id}`),
  create: (data: Omit<Application, 'id'>) => http.post<Application>(RESOURCE, data),
  update: (id: string, data: Partial<Application>) =>
    http.patch<Application>(`${RESOURCE}/${id}`, data),
  replace: (id: string, data: Application) => http.put<Application>(`${RESOURCE}/${id}`, data),
  remove: (id: string) => http.delete<void>(`${RESOURCE}/${id}`),
}

/**
 * Dérive le statut de connexion affiché dans la colonne "Statut" de la
 * liste des applications, à partir de la dernière exécution réelle de cette
 * application — jamais stocké en dur, toujours recalculé depuis les vraies
 * données. Ne reflète QUE l'accessibilité réseau réelle de l'application
 * cible, pas la réussite du test métier lui-même (voir StepResult.httpStatus
 * : présent dès qu'une vraie réponse HTTP a été reçue, quel que soit son
 * code — absent uniquement en cas d'échec réseau/CORS/timeout réel, voir
 * stepRunner.ts). Une étape qui répond 500 ou échoue une assertion prouve
 * quand même que l'application est bien CONNECTÉE.
 */
export function deriveConnectionStatus(
  _app: Application,
  latestExecution: Execution | null
): ApplicationConnectionStatus {
  // Pas encore d'exécution réelle : aucune preuve d'inaccessibilité, on ne
  // présume pas le pire.
  if (!latestExecution) return 'Connectée'
  const sentResults = latestExecution.stepResults.filter((r) => r.status !== 'skipped')
  if (sentResults.length === 0) return 'Connectée'
  const gotRealResponse = sentResults.some((r) => r.httpStatus !== undefined)
  return gotRealResponse ? 'Connectée' : 'Non connectée'
}
