// ============================================================
// Phase 25 — accès réel au backend Spring Boot pour l'administration des
// utilisateurs Keycloak. Isolé (comme les services *Backend.ts des phases
// précédentes) : services/api/users.ts (JSON Server, db.json.users, mots
// de passe en clair) reste INCHANGÉ et continue d'être utilisé UNIQUEMENT
// par le flux de connexion "mock" de Login.tsx (mode authProvider !==
// 'keycloak') — jamais redirigé, jamais touché.
//
// Contrat réel (voir UserController.java, lu avant d'écrire ce fichier) :
//   GET  /api/users            -> UserSummaryResponse[] (réservé SUPER_ADMIN)
//   PUT  /api/users/{id}/role  -> UserSummaryResponse (modifie réellement
//                                  les realm roles Keycloak)
//   PUT  /api/users/{id}/status -> UserSummaryResponse (modifie réellement
//                                  le champ "enabled" Keycloak)
// Keycloak est l'unique source de vérité — le backend n'invente ni ne
// stocke aucune donnée utilisateur (voir service.keycloak.
// HttpKeycloakAdminClient côté backend, compte de service dédié, jamais
// le compte admin Keycloak ni un mot de passe transmis par ce frontend).
//
// Utilise le même client HTTP central (springHttp, voir httpClient.ts).
// ============================================================

import { springHttp } from './httpClient'
import { BackendUserSummaryResponse, BackendAppRole, BackendCreateUserRequest, BackendUserCreatedResponse, BackendPasswordPolicy } from '../../types/backendContracts'

const RESOURCE = '/api/users'

//   POST /api/users                   -> BackendUserCreatedResponse (compte +
//                                         e-mail Keycloak pour choisir le mot de passe)
//   POST /api/users/{id}/reset-password -> 202 (e-mail Keycloak de réinitialisation)
export const usersBackendApi = {
  list: () => springHttp.get<BackendUserSummaryResponse[]>(RESOURCE),
  create: (request: BackendCreateUserRequest) => springHttp.post<BackendUserCreatedResponse>(RESOURCE, request),
  resetPassword: (id: string) => springHttp.post<void>(`${RESOURCE}/${id}/reset-password`),
  /** Mot de passe défini par l'administrateur : jamais renvoyé ni conservé par LoadPilot. */
  setPassword: (id: string, password: string, temporary: boolean) =>
    springHttp.put<void>(`${RESOURCE}/${id}/password`, { password, temporary }),
  passwordPolicy: () => springHttp.get<BackendPasswordPolicy>(`${RESOURCE}/password-policy`),
  updateRole: (id: string, role: BackendAppRole) =>
    springHttp.put<BackendUserSummaryResponse>(`${RESOURCE}/${id}/role`, { role }),
  updateStatus: (id: string, enabled: boolean) =>
    springHttp.put<BackendUserSummaryResponse>(`${RESOURCE}/${id}/status`, { enabled }),
}
