import { useState } from 'react'
import { useAuth } from '../../context/AuthContext'
import { useToast } from '../../context/ToastContext'
import { executionsBackendApi } from '../../services/api/executionsBackend'
import { ApiError } from '../../services/api/httpClient'
import type { BackendResultsRetention } from '../../types/backendContracts'

// Conservation du détail d'une exécution : mention quand une partie a été supprimée
// (rapport figé conservé) et bouton « Conserver » (jamais purgée).

/** Rôles autorisés à conserver une exécution (mêmes que la suppression, voir ExecutionController). */
export function useCanKeep(): boolean {
  const { authProvider, rawRoles } = useAuth()
  return authProvider === 'keycloak' && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))
}

export function RetentionNotice({ retention }: { retention?: BackendResultsRetention | null }) {
  if (!retention?.notice) return null
  return (
    <div className="pt-alert-banner info mb-3" role="note">
      <i className="bi bi-archive" aria-hidden="true"></i>
      <span>{retention.notice}</span>
    </div>
  )
}

interface KeepToggleProps {
  executionId: string
  retention?: BackendResultsRetention | null
  onChange: (retention: BackendResultsRetention) => void
}

/** Bouton « Conserver » / « Conservée » ; absent une fois le détail supprimé (rien à conserver). */
export function KeepToggle({ executionId, retention, onChange }: KeepToggleProps) {
  const canKeep = useCanKeep()
  const { showToast } = useToast()
  const [busy, setBusy] = useState(false)
  if (!retention || retention.resultsPurgedAt) return null
  const kept = retention.keep
  if (!canKeep) {
    return kept ? <span className="pt-pill neutral" title="Résultats détaillés jamais supprimés"><i className="bi bi-bookmark-check-fill me-1"></i>Conservée</span> : null
  }
  const toggle = async () => {
    setBusy(true)
    try {
      const next = await executionsBackendApi.setKeep(executionId, !kept)
      onChange(next)
      showToast(next.keep ? 'Exécution conservée : ses résultats détaillés ne seront jamais supprimés.'
        : 'Conservation retirée : les résultats détaillés suivront la durée de conservation.', 'success')
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : 'Impossible de modifier la conservation.', 'danger')
    } finally {
      setBusy(false)
    }
  }
  return (
    <button type="button" className={kept ? 'pt-btn-primary' : 'pt-btn-outline'} onClick={toggle} disabled={busy} aria-pressed={kept}
      title={kept ? 'Résultats détaillés jamais supprimés. Cliquer pour suivre à nouveau la durée de conservation.'
        : 'Ne jamais supprimer les résultats détaillés de cette exécution'}>
      <i className={`bi ${kept ? 'bi-bookmark-check-fill' : 'bi-bookmark'}`} aria-hidden="true"></i>
      {kept ? 'Conservée' : 'Conserver'}
    </button>
  )
}
