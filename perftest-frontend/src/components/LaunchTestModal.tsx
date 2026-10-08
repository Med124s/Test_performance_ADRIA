import { useEffect, useId, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import SearchSelect from './SearchSelect'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { stepsBackendApi } from '../services/api/stepsBackend'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { ApiError } from '../services/api/httpClient'
import { BackendScenarioResponse } from '../types/backendContracts'
import { useExecutionMonitor } from '../context/ExecutionMonitorContext'
import { useToast } from '../context/ToastContext'

// ============================================================
// « Lancer un test » (barre latérale) : choix du scénario avec recherche
// (scénarios actifs, non archivés, avec au moins une étape), aperçu de la
// charge, puis lancement et ouverture du suivi en direct. Même moteur et
// mêmes règles que « Nouvelle exécution » de la page Exécutions.
// ============================================================

function describe(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 429) return `Limite de capacité LoadPilot atteinte : ${err.message}`
    if (err.status === 403) return 'Action refusée : votre rôle ne permet pas de lancer un test.'
    return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
  }
  return err instanceof Error ? err.message : 'Lancement impossible.'
}

export default function LaunchTestModal({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const monitor = useExecutionMonitor()
  const { showToast } = useToast()
  const titleId = useId()
  const [scenarios, setScenarios] = useState<BackendScenarioResponse[] | null>(null)
  const [stepCounts, setStepCounts] = useState<Map<string, number>>(new Map())
  const [scenarioId, setScenarioId] = useState('')
  const [launching, setLaunching] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    Promise.all([scenariosBackendApi.getAll(), stepsBackendApi.getAll()])
      .then(([scen, steps]) => {
        if (!alive) return
        const counts = new Map<string, number>()
        for (const s of steps) counts.set(s.scenarioId, (counts.get(s.scenarioId) ?? 0) + 1)
        setStepCounts(counts)
        setScenarios(scen)
      })
      .catch((err) => { if (alive) { setScenarios([]); setError(describe(err)) } })
    return () => { alive = false }
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !launching) onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [launching, onClose])

  const launchable = useMemo(() => (scenarios ?? []).filter((s) =>
    s.status === 'ACTIVE' && !s.archived && !s.applicationArchived && (stepCounts.get(s.id) ?? 0) > 0), [scenarios, stepCounts])
  const options = useMemo(() => launchable.map((s) => ({ value: s.id, label: s.name, hint: s.applicationName }))
    .sort((a, b) => a.label.localeCompare(b.label, 'fr')), [launchable])
  const selected = launchable.find((s) => s.id === scenarioId) ?? null

  const launch = async () => {
    if (!selected || launching) return
    setLaunching(true)
    setError(null)
    try {
      const result = await executionsBackendApi.execute({ scenarioId: selected.id })
      monitor.track(result.id, result.scenarioName)
      showToast(`Exécution de « ${result.scenarioName} » lancée.`, 'success')
      onClose()
      navigate(`/executions/live/${result.id}`)
    } catch (err) {
      setError(describe(err))
      setLaunching(false)
    }
  }

  return (
    <div className="lt-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget && !launching) onClose() }}>
      <div className="lt-modal" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className="lt-head">
          <h2 id={titleId}><i className="bi bi-lightning-charge-fill" aria-hidden="true"></i> Lancer un test</h2>
          <button type="button" className="ex-icon-btn" aria-label="Fermer" onClick={onClose} disabled={launching}><i className="bi bi-x-lg"></i></button>
        </div>
        <div className="lt-body">
          <label className="lt-label">Scénario</label>
          {scenarios == null ? (
            <p className="lt-muted">Chargement des scénarios…</p>
          ) : (
            <SearchSelect options={options} value={scenarioId} onChange={setScenarioId} allLabel="Rechercher un scénario…"
              ariaLabel="Scénario à lancer" width="100%" />
          )}
          {scenarios != null && launchable.length === 0 && !error && (
            <p className="lt-muted mt-2">Aucun scénario prêt : il faut un scénario actif avec au moins une étape.</p>
          )}
          {selected && (
            <div className="lt-load" aria-label="Charge du scénario">
              <span><strong>{selected.virtualUsers}</strong> utilisateurs virtuels</span>
              <span>montée <strong>{selected.rampUpSeconds} s</strong></span>
              {selected.durationSeconds != null && <span>durée <strong>{selected.durationSeconds} s</strong></span>}
              {selected.iterations != null && <span><strong>{selected.iterations}</strong> itération(s)</span>}
              <span>· {selected.applicationName}</span>
            </div>
          )}
          {error && <div className="pt-alert-banner danger mt-2 mb-0" role="alert"><i className="bi bi-exclamation-octagon"></i>{error}</div>}
          <p className="lt-muted mt-2 mb-0">Le suivi en direct s’ouvre dès le lancement.</p>
        </div>
        <div className="lt-foot">
          <button type="button" className="pt-btn-ghost" onClick={onClose} disabled={launching}>Annuler</button>
          <button type="button" className="pt-btn-primary" onClick={launch} disabled={!selected || launching}>
            {launching ? <span className="spinner-border spinner-border-sm"></span> : <i className="bi bi-play-fill"></i>} Lancer
          </button>
        </div>
      </div>
    </div>
  )
}
