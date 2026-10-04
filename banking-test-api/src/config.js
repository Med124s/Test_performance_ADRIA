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
// les endpoints d'écriture (transferts, facture, versement).
//
// Réglables au démarrage par variables d'environnement (valeur actuelle par défaut) :
//   ERROR_RATE         taux total d'erreurs aléatoires, réparti 5/7 en 409 et 2/7 en 500 (défaut 0.07)
//   CONFLICT_RATE      taux de 409 seul (prioritaire sur la répartition de ERROR_RATE)
//   SERVER_ERROR_RATE  taux de 500 seul (prioritaire sur la répartition de ERROR_RATE)
// Une valeur s'écrit 0.07, 7 ou 7% (au-dessus de 1, elle est lue comme un pourcentage).
// ERROR_RATE=0 désactive complètement les erreurs aléatoires.
const DEFAULT_CONFLICT = 0.05
const DEFAULT_SERVER_ERROR = 0.02

export function parseRate(name, raw) {
  if (raw === undefined || String(raw).trim() === '') return undefined
  const text = String(raw).trim().replace(/%$/, '').replace(',', '.')
  let value = Number(text)
  if (!Number.isFinite(value) || value < 0) {
    console.warn(`[config] ${name}="${raw}" ignoré : attendu un nombre entre 0 et 1 (ou un pourcentage).`)
    return undefined
  }
  if (value > 1 || String(raw).trim().endsWith('%')) value = value / 100
  return Math.min(value, 1)
}

export function buildErrorSimulationConfig(env = process.env) {
  const total = parseRate('ERROR_RATE', env.ERROR_RATE)
  const share = DEFAULT_CONFLICT / (DEFAULT_CONFLICT + DEFAULT_SERVER_ERROR)
  let conflict = total === undefined ? DEFAULT_CONFLICT : total * share
  let serverError = total === undefined ? DEFAULT_SERVER_ERROR : total - conflict
  conflict = parseRate('CONFLICT_RATE', env.CONFLICT_RATE) ?? conflict
  serverError = parseRate('SERVER_ERROR_RATE', env.SERVER_ERROR_RATE) ?? serverError
  if (conflict + serverError > 1) {
    console.warn('[config] Somme des taux d\'erreur > 100 % : ramenée à 100 %.')
    serverError = 1 - conflict
  }
  return { conflict409Probability: conflict, serverError500Probability: serverError }
}

export const errorSimulationConfig = buildErrorSimulationConfig()

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
