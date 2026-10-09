import { ApiError } from '../../services/api/httpClient'

/** Message français pour une erreur d'appel des pages Paramètres. */
export function describeSettingsError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 0) return 'Le serveur LoadPilot ne répond pas. Réessayez dans un instant.'
    if (err.status === 401) return 'Votre session a expiré : reconnectez-vous.'
    if (err.status === 403) return 'Action réservée au Super administrateur.'
    if (err.status >= 500) return 'Erreur du serveur LoadPilot. Réessayez plus tard.'
    return err.message || fallback
  }
  return fallback
}
