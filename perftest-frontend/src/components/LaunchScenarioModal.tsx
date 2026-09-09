import { useEffect, useMemo, useState } from 'react'
import { Application, Scenario } from '../types'
import { ScenarioLauncher } from '../hooks/useScenarioLauncher'

interface LaunchScenarioModalProps {
  launcher: ScenarioLauncher
  scenarios: Scenario[]
  applications: Application[]
}

const labelStyle = { fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)', marginBottom: '6px', display: 'block' as const }
const inputStyle = { width: '100%', padding: '8px 12px', borderRadius: 'var(--pt-radius-sm)', border: '1px solid var(--pt-border)', background: 'var(--pt-bg)', color: 'var(--pt-text)', fontSize: '13.5px' }

/**
 * Modale de lancement d'un scénario (configuration rapide → confirmation →
 * progression en direct), partagée entre la page Exécutions et la page
 * Scénarios (qui doit rester sur place plutôt que de naviguer) pour éviter
 * de dupliquer ce flux à deux endroits.
 */
function LaunchScenarioModal({ launcher, scenarios, applications }: LaunchScenarioModalProps) {
  const scenarioById = useMemo(() => new Map(scenarios.map((s) => [s.id, s])), [scenarios])
  const appById = useMemo(() => new Map(applications.map((a) => [a.id, a])), [applications])

  const {
    showLaunchModal, launchStep, setLaunchStep, launching, liveSteps, launchError,
    form, setForm, selectApplication, selectScenario, closeModal, handleLaunch, doneCount, errorCount, progressPct, currentRunning,
    controlState, pauseExecution, resumeExecution, cancelExecution,
  } = launcher

  // Scénarios de l'application choisie uniquement — l'application se
  // choisit toujours en premier (voir selectApplication dans
  // useScenarioLauncher), le scénario ensuite parmi cette liste filtrée.
  const scenariosForApp = useMemo(
    () => scenarios.filter((s) => String(s.applicationId) === String(form.applicationId)),
    [scenarios, form.applicationId]
  )

  // Vue "un VU à la fois" pour la progression en direct — avec plusieurs
  // VUs, une liste plate de toutes les étapes de tous les VUs devient
  // illisible. On pagine par VU, avec un aperçu compact (pastille colorée) pour
  // repérer en un coup d'œil lequel a une erreur sans avoir à l'ouvrir.
  const [selectedVu, setSelectedVu] = useState(0)
  useEffect(() => {
    if (launchStep === 3) setSelectedVu(0)
  }, [launchStep])

  const vuNumbers = useMemo(() => {
    const vus = new Set<number>()
    liveSteps.forEach((s) => { if (s.vu !== undefined) vus.add(s.vu) })
    return Array.from(vus).sort((a, b) => a - b)
  }, [liveSteps])

  const stepsForVu = (vu: number) => liveSteps.filter((s) => s.vu === vu)

  const vuStatus = (vu: number): 'running' | 'error' | 'success' | 'pending' => {
    const steps = stepsForVu(vu)
    if (steps.some((s) => s.status === 'running')) return 'running'
    if (steps.some((s) => s.status === 'error')) return 'error'
    if (steps.length > 0 && steps.every((s) => s.status === 'success' || s.status === 'skipped')) return 'success'
    return 'pending'
  }

  const vuStatusColor: Record<ReturnType<typeof vuStatus>, string> = {
    running: 'var(--pt-primary)',
    error: 'var(--pt-danger)',
    success: 'var(--pt-success)',
    pending: 'var(--pt-text-light)',
  }

  if (!showLaunchModal) return null

  const selectedScenario = scenarioById.get(form.scenarioId)

  const launchCurrentScenario = () => {
    if (!selectedScenario) return
    handleLaunch(selectedScenario)
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem' }}>
      <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '580px', maxWidth: '95vw', maxHeight: '90vh', overflowY: 'auto', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)' }}>
        <div className="d-flex justify-content-between align-items-center mb-1">
          <div>
            <h5 style={{ fontWeight: 700, margin: 0 }}>
              {launchStep === 1 ? '⚙️ Configurer le test' : launchStep === 2 ? '✅ Confirmation' : '🚀 Exécution en direct'}
            </h5>
            <p style={{ fontSize: '13px', color: 'var(--pt-text-muted)', margin: '4px 0 0' }}>
              {launchStep < 3 ? `Étape ${launchStep} sur 2` : 'Progression en temps réel'}
            </p>
          </div>
          {!launching && (
            <button onClick={closeModal} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: 'var(--pt-text-muted)' }}>
              <i className="bi bi-x"></i>
            </button>
          )}
        </div>

        {launchStep < 3 && (
          <div style={{ height: '4px', background: 'var(--pt-border)', borderRadius: '2px', marginBottom: '1.5rem' }}>
            <div style={{ height: '100%', background: 'var(--pt-primary)', borderRadius: '2px', width: `${launchStep === 1 ? 50 : 100}%`, transition: 'width 0.3s ease' }}></div>
          </div>
        )}

        {launchError && (
          <div className="pt-alert-banner danger mb-3">
            <i className="bi bi-exclamation-triangle-fill"></i>
            {launchError}
          </div>
        )}

        {launchStep === 1 && (
          <div className="row g-3">
            <div className="col-12">
              <label style={labelStyle}>Application *</label>
              <select style={inputStyle} value={form.applicationId} onChange={e => selectApplication(e.target.value)}>
                <option value="" disabled>Sélectionner une application…</option>
                {applications.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            </div>
            <div className="col-12">
              <label style={labelStyle}>Scénario *</label>
              <select
                style={{ ...inputStyle, ...(!form.applicationId ? { background: 'var(--pt-border)', cursor: 'not-allowed' } : {}) }}
                value={form.scenarioId}
                disabled={!form.applicationId}
                onChange={e => {
                  selectScenario(scenariosForApp.find((s) => String(s.id) === e.target.value))
                }}
              >
                <option value="" disabled>
                  {form.applicationId ? 'Sélectionner un scénario…' : "Choisissez d'abord une application"}
                </option>
                {scenariosForApp.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
              {form.applicationId && scenariosForApp.length === 0 && (
                <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>
                  Aucun scénario pour cette application.
                </div>
              )}
            </div>
            <div className="col-6">
              <label style={labelStyle} className="d-flex align-items-center gap-2">
                Utilisateurs virtuels (VUs)
                {selectedScenario?.virtualUsers != null && (
                  <span className="pt-pill info" style={{ fontSize: '10.5px', fontWeight: 600 }} title="Valeur pré-remplie depuis le scénario — modifiable uniquement pour cette exécution">
                    Valeur du scénario : {selectedScenario.virtualUsers}
                  </span>
                )}
              </label>
              <input type="number" style={inputStyle} min={1} max={10000} value={form.virtualUsers} onChange={e => setForm(p => ({ ...p, virtualUsers: +e.target.value }))} />
            </div>
            <div className="col-6">
              <label style={labelStyle}>Durée (secondes)</label>
              <input type="number" style={inputStyle} min={10} value={form.duration} onChange={e => setForm(p => ({ ...p, duration: +e.target.value }))} />
            </div>
            <div className="col-6">
              <label style={labelStyle} className="d-flex align-items-center gap-2">
                Ramp-up (secondes)
                {selectedScenario?.rampUpSeconds != null && (
                  <span className="pt-pill info" style={{ fontSize: '10.5px', fontWeight: 600 }} title="Valeur pré-remplie depuis le scénario — modifiable uniquement pour cette exécution">
                    Valeur du scénario : {selectedScenario.rampUpSeconds}
                  </span>
                )}
              </label>
              <input type="number" style={inputStyle} min={0} value={form.rampUp} onChange={e => setForm(p => ({ ...p, rampUp: +e.target.value }))} />
            </div>
            <div className="col-6">
              <label style={labelStyle}>Think time (ms)</label>
              <input type="number" style={inputStyle} min={0} value={form.thinkTime} onChange={e => setForm(p => ({ ...p, thinkTime: +e.target.value }))} />
            </div>
            <div className="col-6">
              <label style={labelStyle}>Débit cible (req/s) <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span></label>
              <input type="number" style={inputStyle} placeholder="Ex: 500" value={form.debit} onChange={e => setForm(p => ({ ...p, debit: e.target.value }))} />
            </div>
            <div className="col-12">
              <label style={labelStyle}>Mode d'arrêt</label>
              <select style={inputStyle} value={form.stopMode} onChange={e => setForm(p => ({ ...p, stopMode: e.target.value as 'auto' | 'manual' }))}>
                <option value="auto">Automatique (durée définie)</option>
                <option value="manual">Manuel</option>
              </select>
            </div>
            <div className="col-12 d-flex justify-content-end gap-2 mt-2">
              <button onClick={closeModal} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px' }}>
                Annuler
              </button>
              <button onClick={() => setLaunchStep(2)} disabled={!form.applicationId || !form.scenarioId} className="pt-btn-primary" style={{ padding: '8px 20px' }}>
                Suivant <i className="bi bi-arrow-right ms-1"></i>
              </button>
            </div>
          </div>
        )}

        {launchStep === 2 && (
          <div>
            <div style={{ background: 'var(--pt-bg)', borderRadius: 'var(--pt-radius-sm)', padding: '1.25rem', border: '1px solid var(--pt-border)', marginBottom: '1.5rem' }}>
              <h6 style={{ fontSize: '13px', fontWeight: 700, color: 'var(--pt-text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '1rem' }}>Résumé de la configuration</h6>
              <div className="row g-2">
                {[
                  ['Scénario', scenarioById.get(form.scenarioId)?.name ?? ''],
                  ['Application', appById.get(form.applicationId)?.name ?? ''],
                  ['Utilisateurs virtuels', `${form.virtualUsers} VUs`],
                  ['Durée', `${form.duration}s (${Math.floor(form.duration / 60)}min ${form.duration % 60}s)`],
                  ['Ramp-up', `${form.rampUp}s`],
                  ['Think time', `${form.thinkTime} ms`],
                  ['Mode arrêt', form.stopMode === 'auto' ? 'Automatique' : 'Manuel'],
                ].map(([k, v]) => (
                  <div key={k} className="col-6">
                    <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginBottom: '2px' }}>{k}</div>
                    <div style={{ fontSize: '13.5px', fontWeight: 600, color: 'var(--pt-text)' }}>{v}</div>
                  </div>
                ))}
              </div>
            </div>
            <div className="d-flex gap-2 justify-content-end">
              <button onClick={() => setLaunchStep(1)} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px' }}>
                <i className="bi bi-arrow-left me-1"></i>Retour
              </button>
              <button onClick={launchCurrentScenario} style={{ background: 'var(--pt-success)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 24px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 700 }}>
                <i className="bi bi-play-fill me-2"></i>Lancer le test
              </button>
            </div>
          </div>
        )}

        {launchStep === 3 && (
          <div>
            <div className="d-flex align-items-center justify-content-between mb-2">
              <span style={{ fontSize: '13px', fontWeight: 600 }}>
                Progression — {doneCount}/{liveSteps.length} étapes
                {controlState === 'paused' && (
                  <span className="pt-pill warning ms-2" style={{ fontSize: '10.5px' }}>
                    <i className="bi bi-pause-fill"></i> Pausée
                  </span>
                )}
              </span>
              <span style={{ fontSize: '13px', fontWeight: 700, color: errorCount > 0 ? 'var(--pt-danger)' : 'var(--pt-primary)' }}>{progressPct}%</span>
            </div>
            <div style={{ height: '8px', background: 'var(--pt-border)', borderRadius: '4px', marginBottom: '1rem', overflow: 'hidden' }}>
              <div style={{ height: '100%', background: errorCount > 0 ? 'var(--pt-warning)' : 'var(--pt-success)', borderRadius: '4px', width: `${progressPct}%`, transition: 'width 0.3s ease' }}></div>
            </div>

            {launching && (
              <div className="d-flex gap-2 mb-3">
                {controlState === 'running' && (
                  <button
                    onClick={pauseExecution}
                    style={{ background: 'var(--pt-warning)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '6px 16px', cursor: 'pointer', fontSize: '12.5px', fontWeight: 600 }}
                  >
                    <i className="bi bi-pause-fill me-1"></i> Pause
                  </button>
                )}
                {controlState === 'paused' && (
                  <button
                    onClick={resumeExecution}
                    style={{ background: 'var(--pt-success)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '6px 16px', cursor: 'pointer', fontSize: '12.5px', fontWeight: 600 }}
                  >
                    <i className="bi bi-play-fill me-1"></i> Reprendre
                  </button>
                )}
                {controlState !== 'cancelled' && (
                  <button
                    onClick={cancelExecution}
                    style={{ background: 'var(--pt-card-bg)', color: 'var(--pt-danger)', border: '1px solid var(--pt-danger)', borderRadius: 'var(--pt-radius-sm)', padding: '6px 16px', cursor: 'pointer', fontSize: '12.5px', fontWeight: 600 }}
                  >
                    <i className="bi bi-stop-fill me-1"></i> Annuler
                  </button>
                )}
              </div>
            )}

            {currentRunning && controlState === 'running' && (
              <p style={{ fontSize: '13px', color: 'var(--pt-text-muted)', marginBottom: '1rem' }}>
                <i className="bi bi-arrow-repeat pt-spin me-1"></i>
                Étape en cours : <strong>{currentRunning.step.name}</strong>
              </p>
            )}
            {!launching && liveSteps.length > 0 && (
              <div className={`pt-alert-banner ${controlState === 'cancelled' ? 'warning' : errorCount > 0 ? 'warning' : 'success'} mb-3`}>
                <i className={`bi ${controlState === 'cancelled' ? 'bi-slash-circle-fill' : errorCount > 0 ? 'bi-exclamation-triangle-fill' : 'bi-check-circle-fill'}`}></i>
                {controlState === 'cancelled'
                  ? 'Exécution annulée.'
                  : errorCount > 0
                  ? `Exécution terminée avec ${errorCount} erreur${errorCount > 1 ? 's' : ''}.`
                  : 'Exécution terminée avec succès !'}
              </div>
            )}

            {vuNumbers.length > 1 && (
              <div className="d-flex align-items-center gap-2 mb-2" style={{ flexWrap: 'wrap' }}>
                <span style={{ fontSize: '11px', color: 'var(--pt-text-muted)', fontWeight: 600, textTransform: 'uppercase' }}>VUs</span>
                {vuNumbers.map((vu) => (
                  <button
                    key={vu}
                    onClick={() => setSelectedVu(vu)}
                    title={`Utilisateur virtuel ${vu + 1}`}
                    style={{
                      display: 'flex', alignItems: 'center', gap: '5px',
                      padding: '3px 9px', borderRadius: '20px', fontSize: '11.5px', fontWeight: 600,
                      border: `1px solid ${vu === selectedVu ? 'var(--pt-primary)' : 'var(--pt-border)'}`,
                      background: vu === selectedVu ? 'var(--pt-primary-light)' : 'var(--pt-card-bg)',
                      color: vu === selectedVu ? 'var(--pt-primary)' : 'var(--pt-text-muted)',
                      cursor: 'pointer',
                    }}
                  >
                    <span style={{ width: '7px', height: '7px', borderRadius: '50%', background: vuStatusColor[vuStatus(vu)], flexShrink: 0 }}></span>
                    VU {vu + 1}
                  </button>
                ))}
              </div>
            )}

            <div className="d-flex align-items-center justify-content-between mb-2">
              <button
                onClick={() => setSelectedVu((v) => Math.max(0, v - 1))}
                disabled={selectedVu === 0}
                style={{ background: 'none', border: 'none', color: selectedVu === 0 ? 'var(--pt-text-light)' : 'var(--pt-primary)', cursor: selectedVu === 0 ? 'not-allowed' : 'pointer', fontSize: '13px' }}
              >
                <i className="bi bi-chevron-left"></i> Précédent
              </button>
              <span style={{ fontSize: '12px', color: 'var(--pt-text-muted)', fontWeight: 600 }}>
                VU {selectedVu + 1} / {vuNumbers.length || 1}
              </span>
              <button
                onClick={() => setSelectedVu((v) => Math.min(vuNumbers.length - 1, v + 1))}
                disabled={selectedVu >= vuNumbers.length - 1}
                style={{ background: 'none', border: 'none', color: selectedVu >= vuNumbers.length - 1 ? 'var(--pt-text-light)' : 'var(--pt-primary)', cursor: selectedVu >= vuNumbers.length - 1 ? 'not-allowed' : 'pointer', fontSize: '13px' }}
              >
                Suivant <i className="bi bi-chevron-right"></i>
              </button>
            </div>

            <div className="d-flex flex-column gap-2" style={{ maxHeight: '280px', overflowY: 'auto' }}>
              {stepsForVu(selectedVu).map((ls, idx) => {
                const meta =
                  ls.status === 'success' ? { icon: 'bi-check-circle-fill', color: 'var(--pt-success)', label: 'Réussie' } :
                  ls.status === 'error' ? { icon: 'bi-x-circle-fill', color: 'var(--pt-danger)', label: 'Échec' } :
                  ls.status === 'skipped' ? { icon: 'bi-skip-forward-fill', color: 'var(--pt-text-muted)', label: 'Ignorée' } :
                  ls.status === 'running' ? { icon: 'bi-arrow-repeat pt-spin', color: 'var(--pt-primary)', label: 'En cours' } :
                  { icon: 'bi-hourglass', color: 'var(--pt-text-light)', label: 'En attente' }
                return (
                  <div key={idx} style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 10px', borderRadius: 'var(--pt-radius-sm)', background: 'var(--pt-bg)', border: '1px solid var(--pt-border)' }}>
                    <i className={`bi ${meta.icon}`} style={{ color: meta.color, fontSize: '15px', flexShrink: 0 }}></i>
                    <span style={{ fontSize: '13px', fontWeight: 600, flex: 1 }}>{ls.step.name}</span>
                    {ls.responseTimeMs !== undefined && (
                      <span style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{ls.responseTimeMs} ms</span>
                    )}
                    {ls.httpStatus !== undefined && (
                      <span className={`pt-pill ${ls.status === 'success' ? 'success' : 'danger'}`} style={{ fontSize: '10.5px' }}>{ls.httpStatus}</span>
                    )}
                    <span style={{ fontSize: '11.5px', color: meta.color, minWidth: '64px', textAlign: 'right' }}>{meta.label}</span>
                  </div>
                )
              })}
            </div>

            {errorCount > 0 && (
              <div className="mt-3">
                <div style={{ fontSize: '11px', fontWeight: 700, color: 'var(--pt-text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Erreurs rencontrées</div>
                <div className="d-flex flex-column gap-1">
                  {liveSteps.filter((s) => s.error).map((s, idx) => (
                    <div key={idx} style={{ fontSize: '12px', color: 'var(--pt-danger)' }}>
                      <i className="bi bi-exclamation-triangle-fill me-1"></i>{s.error}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

export default LaunchScenarioModal
