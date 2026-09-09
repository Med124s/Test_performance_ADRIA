import { http } from './httpClient'
import { Scenario } from '../../types'

const RESOURCE = '/scenarios'

export const scenariosApi = {
  getAll: () => http.get<Scenario[]>(RESOURCE),
  getByApplication: (applicationId: string) =>
    http.get<Scenario[]>(`${RESOURCE}?applicationId=${applicationId}`),
  getById: (id: string) => http.get<Scenario>(`${RESOURCE}/${id}`),
  create: (data: Omit<Scenario, 'id'>) => http.post<Scenario>(RESOURCE, data),
  update: (id: string, data: Partial<Scenario>) =>
    http.patch<Scenario>(`${RESOURCE}/${id}`, data),
  remove: (id: string) => http.delete<void>(`${RESOURCE}/${id}`),
}
