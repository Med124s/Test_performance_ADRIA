// Correspondance entre les statuts du backend (anglais) et les libellés du frontend (français).

import type {
  BackendApplicationStatus,
  BackendExecutionStatus,
  BackendScenarioStatus,
  BackendStepStatus,
} from '../types/backendContracts'

/** Libellés français des statuts d'exécution. */
export type ExecutionStatus = 'Réussie' | 'Avec erreurs' | 'Échouée' | 'En cours' | 'En pause' | 'Suspendue' | 'Annulée'
export type ApplicationConnectionStatus = 'Connectée' | 'Non connectée'

// ---- Exécution ----

/** Statut backend -> libellé français (QUEUED et RUNNING -> « En cours »). */
export function backendToFrontendExecutionStatus(status: BackendExecutionStatus): ExecutionStatus {
  switch (status) {
    case 'QUEUED':
    case 'RUNNING':
      return 'En cours'
    case 'PAUSED':
      return 'En pause'
    case 'SUCCESS':
      return 'Réussie'
    // Un seuil dépassé est un résultat non conforme.
    case 'THRESHOLDS_EXCEEDED':
    case 'FAILED':
      return 'Échouée'
    case 'CANCELLED':
      return 'Annulée'
    default:
      // Statut inconnu.
      return 'Avec erreurs'
  }
}

/** Libellé français -> statut backend ; null quand il n'y a pas d'équivalent. */
export function frontendToBackendExecutionStatus(status: ExecutionStatus): BackendExecutionStatus | null {
  switch (status) {
    case 'En cours':
      return 'RUNNING'
    case 'En pause':
      return 'PAUSED'
    case 'Réussie':
      return 'SUCCESS'
    case 'Échouée':
      return 'FAILED'
    case 'Avec erreurs':
      return 'FAILED'
    case 'Annulée':
      return 'CANCELLED'
    case 'Suspendue':
      return null
  }
}

// ---- Application ----

/** Statut de connexion : CONNECTED -> Connectée, FAILED / ERROR -> Non connectée, null -> Connectée. */
export function backendToFrontendConnectionStatus(
  status: BackendApplicationStatus | null
): ApplicationConnectionStatus {
  if (status === 'CONNECTED' || status === null) return 'Connectée'
  return 'Non connectée'
}

// ---- Scénario / étape (ACTIVE / INACTIVE <-> Actif / Inactif) ----

export function backendToFrontendActiveStatus(status: BackendScenarioStatus | BackendStepStatus): 'Actif' | 'Inactif' {
  return status === 'ACTIVE' ? 'Actif' : 'Inactif'
}

export function frontendToBackendActiveStatus(status: 'Actif' | 'Inactif'): BackendScenarioStatus | BackendStepStatus {
  return status === 'Actif' ? 'ACTIVE' : 'INACTIVE'
}
