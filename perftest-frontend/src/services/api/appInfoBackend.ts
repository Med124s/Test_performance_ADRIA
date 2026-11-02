// Nom et version de LoadPilot : GET /api/version -> { name, version }.

import { springHttp } from './httpClient'

export interface AppInfo {
  name: string
  version: string
}

export const appInfoBackendApi = {
  get: () => springHttp.get<AppInfo>('/api/version'),
}
