// ============================================================
// Phase 15 — Harmonisation des vocabulaires de statuts entre le frontend
// actuel (JSON Server, français) et le backend Spring Boot réel (anglais).
//
// Ces fonctions ne sont appelées par AUCUNE page/service actuel : elles
// préparent la future migration (Phase 16+) sans rien changer au
// comportement présent. Écrire cette logique maintenant, une seule fois,
// évite de la redéterminer sous pression pendant la migration réelle.
//
// Certaines correspondances sont VOLONTAIREMENT PARTIELLES (voir chaque
// fonction) : le frontend actuel modélise des états (Suspendue, Avec
// erreurs) que le backend ne connaît pas, et inversement le statut
// `Application.status` actuel (Actif/Inactif, un simple bascule) ne
// correspond à AUCUN champ backend — voir types/backendContracts.ts pour
// le détail des DTO.
// ============================================================

import type {
  BackendApplicationStatus,
  BackendExecutionStatus,
  BackendScenarioStatus,
  BackendStepStatus,
} from '../types/backendContracts'

// P1-O — anciennement importés depuis le module de types JSON Server legacy
// (retiré) : ce sont de simples chaînes françaises affichées à l'écran,
// jamais des formes de données legacy — conservées ici telles quelles, seul
// endroit qui en a encore besoin après le retrait du legacy.
export type ExecutionStatus = 'Réussie' | 'Avec erreurs' | 'Échouée' | 'En cours' | 'Suspendue' | 'Annulée'
export type ApplicationConnectionStatus = 'Connectée' | 'Non connectée'

// ------------------------------------------------------------
// Execution
// ------------------------------------------------------------

/**
 * Backend -> Frontend : correspondance directe et sans perte pour les 4
 * statuts backend historiques (le backend n'a pas de notion de pause :
 * `RUNNING` couvre aussi bien "en cours" que le frontend actuel appellerait
 * "en cours").
 *
 * P0-A — `QUEUED` (moteur de charge asynchrone, voir ExecutionStatus.java)
 * est mappé sur `'En cours'` : le type `ExecutionStatus` partagé avec le
 * monde JSON Server legacy n'a pas de notion de file d'attente distincte, et
 * QUEUED est bien plus proche de "en cours" (le test va démarrer/vient de
 * démarrer) que d'un état terminal — jamais mappé sur un état inventé.
 */
export function backendToFrontendExecutionStatus(status: BackendExecutionStatus): ExecutionStatus {
  switch (status) {
    case 'QUEUED':
    case 'RUNNING':
      return 'En cours'
    case 'SUCCESS':
      return 'Réussie'
    case 'FAILED':
      return 'Échouée'
    case 'CANCELLED':
      return 'Annulée'
  }
}

/**
 * Frontend -> Backend : PARTIELLE ET AVEC PERTE.
 *
 * - `'Suspendue'` (pause réelle, voir useScenarioLauncher) n'a AUCUN
 *   équivalent backend (moteur synchrone, voir Phase 9) — renvoie `null`,
 *   à traiter explicitement lors de la migration plutôt que de deviner un
 *   statut backend qui n'existe pas.
 * - `'Avec erreurs'` (échec partiel : certaines étapes ont réussi, d'autres
 *   non) n'a pas non plus d'équivalent : le backend s'arrête à la première
 *   étape en échec (stop-on-failure) et ne connaît que SUCCESS/FAILED en
 *   sortie — mappé ici sur `'FAILED'` par choix conservateur (documenté),
 *   à valider explicitement lors de la migration.
 */
export function frontendToBackendExecutionStatus(status: ExecutionStatus): BackendExecutionStatus | null {
  switch (status) {
    case 'En cours':
      return 'RUNNING'
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

// ------------------------------------------------------------
// Application
// ------------------------------------------------------------

/**
 * Backend -> Frontend (`ApplicationConnectionStatus`, colonne "Statut" de
 * la page Applications) : `CONNECTED` -> Connectée, `FAILED`/`ERROR` ->
 * Non connectée. `null` (aucun test lancé) traité comme "Connectée" par
 * cohérence avec `deriveConnectionStatus` actuel (services/api/
 * applications.ts), qui ne présume jamais le pire en l'absence de preuve.
 *
 * NE couvre PAS `Application.status` actuel (`Actif`/`Inactif`) : ce champ
 * frontend est un bascule activé/désactivé déclaratif, sans équivalent
 * backend — il n'y a rien à mapper, cette distinction doit être tranchée
 * explicitement lors de la migration (voir rapport Phase 15).
 */
export function backendToFrontendConnectionStatus(
  status: BackendApplicationStatus | null
): ApplicationConnectionStatus {
  if (status === 'CONNECTED' || status === null) return 'Connectée'
  return 'Non connectée'
}

// ------------------------------------------------------------
// Scenario / Step (ACTIVE/INACTIVE <-> Actif/Inactif — correspondance directe)
// ------------------------------------------------------------

export function backendToFrontendActiveStatus(status: BackendScenarioStatus | BackendStepStatus): 'Actif' | 'Inactif' {
  return status === 'ACTIVE' ? 'Actif' : 'Inactif'
}

export function frontendToBackendActiveStatus(status: 'Actif' | 'Inactif'): BackendScenarioStatus | BackendStepStatus {
  return status === 'Actif' ? 'ACTIVE' : 'INACTIVE'
}
