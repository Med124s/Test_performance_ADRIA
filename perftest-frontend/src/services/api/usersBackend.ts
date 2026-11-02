// Administration des utilisateurs (comptes Keycloak).

import { springHttp } from './httpClient'
import { BackendUserSummaryResponse, BackendAppRole, BackendCreateUserRequest, BackendUserCreatedResponse, BackendPasswordPolicy } from '../../types/backendContracts'

const RESOURCE = '/api/users'

export const usersBackendApi = {
  list: () => springHttp.get<BackendUserSummaryResponse[]>(RESOURCE),
  create: (request: BackendCreateUserRequest) => springHttp.post<BackendUserCreatedResponse>(RESOURCE, request),
  resetPassword: (id: string) => springHttp.post<void>(`${RESOURCE}/${id}/reset-password`),
  /** Mot de passe défini par l'administrateur, transmis à Keycloak. */
  setPassword: (id: string, password: string, temporary: boolean) =>
    springHttp.put<void>(`${RESOURCE}/${id}/password`, { password, temporary }),
  passwordPolicy: () => springHttp.get<BackendPasswordPolicy>(`${RESOURCE}/password-policy`),
  updateRole: (id: string, role: BackendAppRole) =>
    springHttp.put<BackendUserSummaryResponse>(`${RESOURCE}/${id}/role`, { role }),
  updateStatus: (id: string, enabled: boolean) =>
    springHttp.put<BackendUserSummaryResponse>(`${RESOURCE}/${id}/status`, { enabled }),
}
