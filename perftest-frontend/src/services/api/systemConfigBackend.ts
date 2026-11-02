// Limites de charge du serveur (lecture seule, Super administrateur).

import { springHttp } from './httpClient'
import { BackendSystemConfigResponse } from '../../types/backendContracts'

const RESOURCE = '/api/system/config'

export const systemConfigBackendApi = {
  get: () => springHttp.get<BackendSystemConfigResponse>(RESOURCE),
}
