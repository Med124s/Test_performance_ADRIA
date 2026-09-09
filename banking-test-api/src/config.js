// ============================================================
// Configuration centralisée — latence simulée et probabilités des codes
// d'erreur "métier" (409/500) appliqués aux requêtes déjà valides et déjà
// authentifiées. Rien de codé en dur dans les routes : tout se règle ici.
//
// 400 (validation) et 401 (auth) restent DÉTERMINISTES — ils dépendent des
// vraies données envoyées (champs manquants, token absent/invalide), pas
// du hasard. Seuls 409/500 sont simulés aléatoirement en plus, pour
// représenter une vraie API qui échoue parfois même sur une requête
// valide (conflit métier, panne ponctuelle) — c'est ça qui rend les
// mesures de PERFTEST (error rate, etc.) réalistes plutôt que 100% vertes.
// ============================================================

export const latencyConfig = {
  // Latence par défaut si la requête n'envoie pas ?latency=<ms>.
  defaultMinMs: 100,
  defaultMaxMs: 300,
  // Plafond dur, même si ?latency= demande plus (évite qu'un scénario mal
  // configuré ne bloque indéfiniment un test PERFTEST).
  maxAllowedMs: 5000,
}

// Probabilités appliquées SEULEMENT après validation + auth réussies, sur
// les endpoints d'écriture (transferts, facture, versement). Ajuste ces
// valeurs pour rendre les scénarios plus ou moins "instables".
export const errorSimulationConfig = {
  conflict409Probability: 0.05, // 5% — ex. opération considérée en conflit
  serverError500Probability: 0.02, // 2% — ex. panne interne simulée
}

/**
 * Tire un résultat pondéré parmi { 409: proba, 500: proba, success: reste }.
 * Retourne 409, 500, ou null (= succès normal).
 */
export function rollSimulatedOutcome() {
  const r = Math.random()
  if (r < errorSimulationConfig.conflict409Probability) return 409
  if (r < errorSimulationConfig.conflict409Probability + errorSimulationConfig.serverError500Probability) return 500
  return null
}
