import { http } from './httpClient'
import { Role } from '../../types'

const RESOURCE = '/roles'

export const rolesApi = {
  getAll: () => http.get<Role[]>(RESOURCE),
  getById: (id: string) => http.get<Role>(`${RESOURCE}/${id}`),
  create: (data: Omit<Role, 'id'>) => http.post<Role>(RESOURCE, data),
  update: (id: string, data: Partial<Role>) => http.patch<Role>(`${RESOURCE}/${id}`, data),
  remove: (id: string) => http.delete<void>(`${RESOURCE}/${id}`),
}
