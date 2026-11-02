// Réglages de LoadPilot (Paramètres > Système et Intégrations, Super administrateur).

import { springHttp } from './httpClient'
import type {
  BackendExecutionLimits, BackendIntegrations, BackendLimitKey, BackendLoadLimits, BackendResultsRetentionSettings, BackendStorageInfo, BackendThresholdSettings,
} from '../../types/backendContracts'

export const settingsBackendApi = {
  resultsRetention: () => springHttp.get<BackendResultsRetentionSettings>('/api/settings/results-retention'),
  updateResultsRetention: (settings: BackendResultsRetentionSettings) =>
    springHttp.put<BackendResultsRetentionSettings>('/api/settings/results-retention', settings),
  thresholds: () => springHttp.get<BackendThresholdSettings>('/api/settings/thresholds'),
  updateThresholds: (settings: BackendThresholdSettings) => springHttp.put<BackendThresholdSettings>('/api/settings/thresholds', settings),
  limits: () => springHttp.get<BackendExecutionLimits>('/api/settings/limits'),
  updateLimits: (values: Record<BackendLimitKey, number>) => springHttp.put<BackendExecutionLimits>('/api/settings/limits', values),
  /** Limites d'un test lues par l'assistant (tout utilisateur connecté). */
  loadLimits: () => springHttp.get<BackendLoadLimits>('/api/settings/load-limits'),
  storage: () => springHttp.get<BackendStorageInfo>('/api/settings/storage'),
  integrations: () => springHttp.get<BackendIntegrations>('/api/settings/integrations'),
  sendTestEmail: () => springHttp.post<{ sentTo: string }>('/api/settings/integrations/mail-test'),
}
