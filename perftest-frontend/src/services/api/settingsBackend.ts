// ============================================================
// Réglages modifiables de LoadPilot (Paramètres > Système et Intégrations, Super administrateur) :
//   GET/PUT /api/settings/results-retention -> durées de conservation du détail des exécutions
//   GET/PUT /api/settings/thresholds        -> seuils par défaut (lecture pour tout utilisateur connecté)
//   GET     /api/settings/storage           -> taille de la base, dernière et prochaine purge
//   GET     /api/settings/integrations      -> serveur e-mail, monitoring (aucun secret)
//   POST    /api/settings/integrations/mail-test -> e-mail de test à mon adresse
// (les réglages du journal d'audit sont dans auditBackend.ts).
// ============================================================

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
