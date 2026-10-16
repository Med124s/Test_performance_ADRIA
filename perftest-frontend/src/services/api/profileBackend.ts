// ============================================================
// Profil de l'utilisateur connecté (GET/PATCH /api/profile) et sécurité de son compte :
// sessions ouvertes, déconnexion de tous les appareils, journalisation du changement de
// mot de passe fait sur la page du système d'authentification (jamais le mot de passe).
// ============================================================

import { springHttp } from './httpClient'
import { BackendProfileIdentityRequest, BackendProfileResponse, BackendProfileSecurity, BackendProfileUpdateRequest } from '../../types/backendContracts'

const RESOURCE = '/api/profile'

export const profileBackendApi = {
  get: () => springHttp.get<BackendProfileResponse>(RESOURCE),
  updateTimezone: (timezone: string) =>
    springHttp.patch<BackendProfileResponse>(RESOURCE, { timezone } satisfies BackendProfileUpdateRequest),
  updateTheme: (theme: 'LIGHT' | 'DARK' | 'SYSTEM') =>
    springHttp.patch<BackendProfileResponse>(RESOURCE, { theme } satisfies BackendProfileUpdateRequest),
  /** Super administrateur seulement (403 sinon) : journalisé avant → après. */
  updateIdentity: (identity: BackendProfileIdentityRequest) =>
    springHttp.put<BackendProfileResponse>(`${RESOURCE}/identity`, identity),
  security: () => springHttp.get<BackendProfileSecurity>(`${RESOURCE}/security`),
  logoutEverywhere: () => springHttp.post<void>(`${RESOURCE}/logout-everywhere`),
  passwordChanged: () => springHttp.post<{ recorded: boolean }>(`${RESOURCE}/password-changed`),
}
