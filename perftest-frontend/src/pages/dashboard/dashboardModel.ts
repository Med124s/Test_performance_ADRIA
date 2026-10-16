import { inUserZone } from '../../utils/userTimezone'
import { BackendDashboardTrend } from '../../types/backendContracts'
import { formatSignedPercent } from '../../utils/numberFormat'

// ============================================================
// Dashboard : période, libellés de statut, lecture de la comparaison
// avec l'exécution précédente (fonctions pures, testées). Les seuils
// eux-mêmes sont appliqués par le serveur (DashboardRules).
// ============================================================

export type RangePreset = 'all' | '24h' | '7d' | '30d' | 'custom'

export const RANGE_OPTIONS: { key: RangePreset; label: string }[] = [
  { key: 'all', label: 'Tout' },
  { key: '24h', label: '24h' },
  { key: '7d', label: '7 jours' },
  { key: '30d', label: '30 jours' },
  { key: 'custom', label: 'Personnalisé' },
]

/** Bornes envoyées au serveur (ISO) ; dates personnalisées en heure locale, journées entières. */
export function rangeBounds(preset: RangePreset, customFrom: string, customTo: string, now = new Date()): { from?: string; to?: string } {
  const hours = preset === '24h' ? 24 : preset === '7d' ? 7 * 24 : preset === '30d' ? 30 * 24 : 0
  if (hours) return { from: new Date(now.getTime() - hours * 3600 * 1000).toISOString(), to: now.toISOString() }
  if (preset === 'custom') {
    return {
      from: customFrom ? new Date(`${customFrom}T00:00:00`).toISOString() : undefined,
      to: customTo ? new Date(`${customTo}T23:59:59.999`).toISOString() : undefined,
    }
  }
  return {}
}

/** Libellé et badge d'un statut : voir utils/executionStatus (table unique, statut inconnu en gris). */
export { executionStatusMeta } from '../../utils/executionStatus'

/** Flèche, couleur et texte de la comparaison avec l'exécution précédente du même scénario. */
export function trendDisplay(trend: BackendDashboardTrend, changePercent: number | null): { arrow: string; tone: string; text: string; title: string } {
  if (trend === 'NONE' || changePercent == null) {
    return { arrow: '', tone: 'muted', text: '—', title: 'Pas d’exécution précédente comparable (réussie ou échouée) pour ce scénario.' }
  }
  const arrow = changePercent > 0 ? '↑' : changePercent < 0 ? '↓' : '→'
  const tone = trend === 'WORSE' ? 'bad' : trend === 'BETTER' ? 'good' : 'muted'
  const meaning = trend === 'WORSE' ? 'P95 dégradé' : trend === 'BETTER' ? 'P95 amélioré' : 'écart faible (moins de 5 %)'
  return { arrow, tone, text: formatSignedPercent(changePercent), title: `${meaning} par rapport à l’exécution précédente du même scénario` }
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('fr-FR', inUserZone({ day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }))
}
