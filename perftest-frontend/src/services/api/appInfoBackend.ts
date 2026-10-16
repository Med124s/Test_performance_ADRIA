// ============================================================
// Nom et version de LoadPilot (tout utilisateur connecté) :
//   GET /api/version -> { name, version }
// La version n'est définie qu'à un seul endroit : <version> de backend/pom.xml.
// ============================================================

import { springHttp } from './httpClient'

export interface AppInfo {
  name: string
  version: string
}

export const appInfoBackendApi = {
  get: () => springHttp.get<AppInfo>('/api/version'),
}
