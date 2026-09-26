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
import { BackendUserSummaryResponse, BackendAppRole } from '../../types/backendContracts'

const RESOURCE = '/api/users'

export const usersBackendApi = {
  list: () => springHttp.get<BackendUserSummaryResponse[]>(RESOURCE),
  updateRole: (id: string, role: BackendAppRole) =>
    springHttp.put<BackendUserSummaryResponse>(`${RESOURCE}/${id}/role`, { role }),
  updateStatus: (id: string, enabled: boolean) =>
    springHttp.put<BackendUserSummaryResponse>(`${RESOURCE}/${id}/status`, { enabled }),
}
