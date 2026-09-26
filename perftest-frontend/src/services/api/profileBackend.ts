// ============================================================
// P1-D — accès réel à GET/PATCH /api/profile (voir ProfileController côté
// backend). Aucun service frontend n'existait pour ce endpoint avant cette
// phase (vérifié par recherche exhaustive) — Settings.tsx était un pur
// placeholder ComingSoonPage. Seul "timezone" est modifiable (username/
// name/email restent la propriété de Keycloak, jamais éditables ici).
// ============================================================

import { springHttp } from './httpClient'
import { BackendProfileResponse, BackendProfileUpdateRequest } from '../../types/backendContracts'

const RESOURCE = '/api/profile'

export const profileBackendApi = {
  get: () => springHttp.get<BackendProfileResponse>(RESOURCE),
  updateTimezone: (timezone: string) =>
    springHttp.patch<BackendProfileResponse>(RESOURCE, { timezone } satisfies BackendProfileUpdateRequest),
}
