import { http } from './httpClient'
import { User } from '../../types'

const RESOURCE = '/users'

export const usersApi = {
  getAll: () => http.get<User[]>(RESOURCE),
  create: (data: Omit<User, 'id'>) => http.post<User>(RESOURCE, data),
}
