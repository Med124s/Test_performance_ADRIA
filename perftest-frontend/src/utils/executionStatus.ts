// ============================================================
// Statuts d'exécution : SEULE table de correspondance statut -> libellé,
// couleur de badge, icône, utilisée par toutes les pages (Exécutions,
// Détail, Rapport, suivi en direct, Dashboard, Comparaison, Rapports,
// Planification…). Un nouveau statut s'ajoute ici et nulle part ailleurs ;
// le type Record<BackendExecutionStatus, …> fait échouer la compilation
// s'il en manque un.
//
// Un statut inconnu (serveur plus récent que l'interface, donnée
// inattendue) ne fait jamais planter une page : badge gris avec son nom.
// ============================================================

import { BackendExecutionStatus } from '../types/backendContracts'

export interface ExecutionStatusMeta {
  /** Libellé court : badges, onglets, listes (« Réussie », « Seuils dépassés »). */
  label: string
  /** Libellé du suivi en direct (« Terminé — succès »). */
  liveLabel: string
  /** Libellé de la page Détail (« Terminée avec succès »). */
  detailLabel: string
  /** Classe de badge pt-pill : success, warning, danger, info, neutral. */
  pill: 'success' | 'warning' | 'danger' | 'info' | 'neutral'
  /** Couleur CSS du point / du texte. */
  color: string
  /** Icône Bootstrap pleine (badges) et contour (cartes). */
  icon: string
  outlineIcon: string
  /** Couleur de la pastille d'icône des cartes de statistiques (stat-icon). */
  iconColor: 'green' | 'orange' | 'red' | 'blue'
  /** Ton du résultat : ok (réussie), warn (seuils dépassés), ko (échouée), neutral, active. */
  tone: 'ok' | 'warn' | 'ko' | 'neutral' | 'active'
  /** Statut connu de l'interface. */
  known: boolean
}

export const EXECUTION_STATUS_META: Record<BackendExecutionStatus, ExecutionStatusMeta> = {
  QUEUED: {
    label: 'En attente', liveLabel: 'En file', detailLabel: 'En attente de démarrage', pill: 'neutral', color: 'var(--pt-neutral)',
    icon: 'bi-hourglass-split', outlineIcon: 'bi-hourglass-split', iconColor: 'blue', tone: 'active', known: true,
  },
  RUNNING: {
    label: 'En cours', liveLabel: 'En cours', detailLabel: 'En cours', pill: 'info', color: 'var(--pt-info)',
    icon: 'bi-play-circle-fill', outlineIcon: 'bi-play-circle', iconColor: 'blue', tone: 'active', known: true,
  },
  PAUSED: {
    label: 'En pause', liveLabel: 'En pause', detailLabel: 'En pause', pill: 'warning', color: 'var(--pt-warning)',
    icon: 'bi-pause-circle-fill', outlineIcon: 'bi-pause-circle', iconColor: 'orange', tone: 'active', known: true,
  },
  SUCCESS: {
    label: 'Réussie', liveLabel: 'Terminé — succès', detailLabel: 'Terminée avec succès', pill: 'success', color: 'var(--pt-success)',
    icon: 'bi-check-circle-fill', outlineIcon: 'bi-check-circle', iconColor: 'green', tone: 'ok', known: true,
  },
  THRESHOLDS_EXCEEDED: {
    label: 'Seuils dépassés', liveLabel: 'Terminé — seuils dépassés', detailLabel: 'Terminée : seuils de réussite dépassés', pill: 'warning',
    color: 'var(--pt-warning)', icon: 'bi-exclamation-triangle-fill', outlineIcon: 'bi-exclamation-triangle', iconColor: 'orange', tone: 'warn', known: true,
  },
  FAILED: {
    label: 'Échouée', liveLabel: 'Terminé — échec', detailLabel: 'Échouée', pill: 'danger', color: 'var(--pt-danger)',
    icon: 'bi-x-circle-fill', outlineIcon: 'bi-x-circle', iconColor: 'red', tone: 'ko', known: true,
  },
  CANCELLED: {
    label: 'Annulée', liveLabel: 'Annulé', detailLabel: 'Annulée', pill: 'neutral', color: 'var(--pt-neutral)',
    icon: 'bi-slash-circle-fill', outlineIcon: 'bi-slash-circle', iconColor: 'blue', tone: 'neutral', known: true,
  },
}

/** Tous les statuts connus, dans l'ordre d'affichage (onglets, filtres). */
export const EXECUTION_STATUSES = Object.keys(EXECUTION_STATUS_META) as BackendExecutionStatus[]
export const ACTIVE_EXECUTION_STATUSES: BackendExecutionStatus[] = ['QUEUED', 'RUNNING', 'PAUSED']
export const TERMINAL_EXECUTION_STATUSES: BackendExecutionStatus[] = ['SUCCESS', 'THRESHOLDS_EXCEEDED', 'FAILED', 'CANCELLED']

/** Correspondance d'un statut, avec une valeur par défaut grise (nom brut affiché) pour un statut inconnu ou absent. */
export function executionStatusMeta(status: string | null | undefined): ExecutionStatusMeta {
  const known = status ? (EXECUTION_STATUS_META as Record<string, ExecutionStatusMeta | undefined>)[status] : undefined
  if (known) return known
  const name = status || 'Statut inconnu'
  return {
    label: name, liveLabel: name, detailLabel: name, pill: 'neutral', color: 'var(--pt-neutral)',
    icon: 'bi-question-circle-fill', outlineIcon: 'bi-question-circle', iconColor: 'blue', tone: 'neutral', known: false,
  }
}

export const statusLabel = (status: string | null | undefined) => executionStatusMeta(status).label
export const statusPill = (status: string | null | undefined) => executionStatusMeta(status).pill
export const isActiveStatus = (status: string | null | undefined) => (ACTIVE_EXECUTION_STATUSES as string[]).includes(status ?? '')
export const isTerminalStatus = (status: string | null | undefined) => (TERMINAL_EXECUTION_STATUSES as string[]).includes(status ?? '')
