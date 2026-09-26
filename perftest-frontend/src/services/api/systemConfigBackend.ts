// ============================================================
// P1-D — accès réel (LECTURE SEULE) à GET /api/system/config (voir
// SystemConfigController côté backend, réservé SUPER_ADMIN). Remplace la
// page Configurations.tsx précédemment un pur placeholder ComingSoonPage —
// aucune configuration mutable n'est créée ici (décision explicite,
// documentée dans le rapport P1-D) : uniquement une vue honnête des
// limites opérationnelles réellement actives.
// ============================================================

import { springHttp } from './httpClient'
import { BackendSystemConfigResponse } from '../../types/backendContracts'

const RESOURCE = '/api/system/config'

export const systemConfigBackendApi = {
  get: () => springHttp.get<BackendSystemConfigResponse>(RESOURCE),
}
