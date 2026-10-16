import { useEffect, useId, useState } from 'react'
import { useToast } from '../../context/ToastContext'
import { settingsBackendApi } from '../../services/api/settingsBackend'
import type { BackendExecutionLimits, BackendLimitKey } from '../../types/backendContracts'
import { resetLoadLimitsCache } from '../../utils/loadLimits'
import { formatInt } from '../scenario-wizard/wizardModel'
import { formatDuration } from './formatDuration'
import { describeSettingsError } from './settingsErrors'

// Limites des tests (Paramètres > Système, Super administrateur), sur deux niveaux :
//  - plafond du serveur (variable du serveur), jamais modifiable ici ;
//  - limite applicative, modifiable, toujours ≤ plafond (par défaut = plafond).
// Effet immédiat sur les prochains enregistrements, lancements et planifications ; aucun effet sur
// les exécutions en cours. Chaque changement est inscrit au journal d'audit (avant → après).

/** Explication d'une phrase par limite (infobulle ⓘ). */
export const LIMIT_HELP: Record<BackendLimitKey, string> = {
  virtualUsersPerTest: 'Utilisateurs simulés au plus par un seul test.',
  virtualUsersTotal: 'Utilisateurs simulés au plus par tous les tests en cours réunis.',
  concurrentTests: 'Tests qui peuvent tourner en même temps ; au-delà, le lancement est refusé.',
  maxDurationSeconds: 'Durée au plus d’un test, et du ramp-up.',
  maxIterations: 'Passages du scénario au plus par utilisateur.',
  maxTargetRps: 'Débit cible au plus d’un test, en requêtes par seconde.',
}

/** Message identique au serveur (ExecutionLimitsService.problem), null si correct. */
export function limitProblem(label: string, raw: string, ceiling: number): string | null {
  if (!raw.trim()) return `${label} : valeur obligatoire.`
  if (!/^\d+$/.test(raw.trim())) return `${label} : nombre entier attendu.`
  const n = Number(raw)
  if (n < 1) return `${label} : au moins 1.`
  if (n > ceiling) return `${label} : maximum ${formatInt(ceiling)} (plafond du serveur).`
  return null
}

/**
 * « Par test » jamais au-dessus de « au total » (même règle que le serveur, ExecutionLimitsService.update) :
 *  - « par test » modifié et au-dessus du total : refus (error) ;
 *  - « par test » laissé tel quel, total baissé en dessous : ramené au total à l'enregistrement (notice).
 * Rien si l'un des deux champs n'est pas un nombre valide (son propre message s'affiche déjà).
 */
export function perTestRule(perTestRaw: string, totalRaw: string, shownPerTest: number): { error?: string; notice?: string } {
  if (!/^\d+$/.test(perTestRaw.trim()) || !/^\d+$/.test(totalRaw.trim())) return {}
  const perTest = Number(perTestRaw)
  const total = Number(totalRaw)
  if (perTest <= total) return {}
  if (perTest !== shownPerTest) return { error: `La limite par test ne peut pas dépasser la limite au total (${formatInt(total)}).` }
  return { notice: `Sera ramenée à ${formatInt(total)} à l’enregistrement : la limite par test ne peut pas dépasser la limite au total.` }
}

function HelpTip({ text, label }: { text: string; label: string }) {
  return (
    <button type="button" className="st-help" aria-label={`À propos : ${label}`}>
      <i className="bi bi-info-circle" aria-hidden="true"></i>
      <span className="st-tip" role="tooltip">{text}</span>
    </button>
  )
}

export default function LimitsCard({ onSaved }: { onSaved?: () => void }) {
  const { showToast } = useToast()
  const id = useId()
  const [data, setData] = useState<BackendExecutionLimits | null>(null)
  const [values, setValues] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const fill = (d: BackendExecutionLimits) => {
    setData(d)
    setValues(Object.fromEntries(d.limits.map((l) => [l.key, String(l.value)])))
  }

  useEffect(() => {
    settingsBackendApi.limits().then(fill).catch((err) => setError(describeSettingsError(err, 'Impossible de charger les limites.')))
  }, [])

  const shownPerTest = data?.limits.find((l) => l.key === 'virtualUsersPerTest')?.value ?? 0
  const rule = perTestRule(values.virtualUsersPerTest ?? '', values.virtualUsersTotal ?? '', shownPerTest)
  const problems = data ? data.limits.map((l) => ({
    key: l.key,
    problem: limitProblem(l.label, values[l.key] ?? '', l.ceiling) ?? (l.key === 'virtualUsersPerTest' ? rule.error ?? null : null),
  })) : []
  const valid = problems.every((p) => !p.problem)
  const dirty = !!data && data.limits.some((l) => (values[l.key] ?? '').trim() !== String(l.value))

  const save = async () => {
    if (!data || !valid) return
    setSaving(true)
    setError(null)
    try {
      const body = Object.fromEntries(data.limits.map((l) => [l.key, Number(values[l.key])])) as Record<BackendLimitKey, number>
      const saved = await settingsBackendApi.updateLimits(body)
      fill(saved)
      resetLoadLimitsCache()
      onSaved?.()
      showToast('Limites enregistrées : elles s’appliquent aux prochains enregistrements, lancements et planifications.', 'success')
      // Ajustement fait par le serveur (ex. « par test » ramenée au total) : expliqué à l'administrateur.
      for (const notice of saved.notices ?? []) showToast(notice, 'info')
    } catch (err) {
      setError(describeSettingsError(err, 'Impossible d’enregistrer les limites.'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="pt-card au-settings st-card" aria-labelledby={`${id}-title`}>
      <div className="au-settings-head">
        <i className="bi bi-hdd-stack" aria-hidden="true"></i>
        <div>
          <h2 id={`${id}-title`}>Limites</h2>
          <p>Jamais au-dessus du plafond fixé par l’administrateur du serveur. Sans effet sur les tests déjà en cours.</p>
        </div>
      </div>
      {error && <div className="pt-alert-banner danger mb-2" role="alert"><i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}</div>}
      {!data ? (!error && <p className="st-note">Chargement…</p>) : (
        <>
          <div className="st-fields">
            {data.limits.map((l) => {
              const problem = problems.find((p) => p.key === l.key)?.problem
              const notice = l.key === 'virtualUsersPerTest' && !problem ? rule.notice : undefined
              return (
                <div className="st-field" key={l.key}>
                  <label htmlFor={`${id}-${l.key}`}>
                    <strong>{l.label}<HelpTip text={LIMIT_HELP[l.key]} label={l.label} /></strong>
                    <span className={problem ? 'st-error' : undefined}>{problem ?? `Maximum ${formatInt(l.ceiling)} (plafond du serveur).`}</span>
                    {notice && <span className="st-warn" role="status"><i className="bi bi-arrow-down-circle" aria-hidden="true"></i> {notice}</span>}
                  </label>
                  <div className="st-unit">
                    <input id={`${id}-${l.key}`} className={`pt-form-control${problem ? ' is-invalid' : ''}`} inputMode="numeric"
                      value={values[l.key] ?? ''} aria-invalid={problem ? true : undefined}
                      onChange={(e) => setValues((v) => ({ ...v, [l.key]: e.target.value }))} />
                    {l.unit}
                  </div>
                </div>
              )
            })}
            <div className="st-field">
              <span className="st-readonly-label"><strong>Délai de réponse d’une requête
                <HelpTip label="Délai de réponse d’une requête" text="Pendant un test, une requête sans réponse après ce délai compte comme une erreur." /></strong>
                <span>Fixé par l’administrateur du serveur.</span></span>
              <strong className="st-readonly-value">{formatDuration(data.executionTimeoutSeconds * 1000)}</strong>
            </div>
            <div className="st-field">
              <span className="st-readonly-label"><strong>Délai du test de disponibilité
                <HelpTip label="Délai du test de disponibilité" text="Temps accordé à une application pour répondre avant d’être considérée injoignable." /></strong>
                <span>Fixé par l’administrateur du serveur.</span></span>
              <strong className="st-readonly-value">{formatDuration(data.availabilityTimeoutSeconds * 1000)}</strong>
            </div>
          </div>
          <div className="au-settings-actions">
            <button type="button" className="pt-btn-primary" onClick={save} disabled={!dirty || !valid || saving}>
              {saving ? 'Enregistrement…' : 'Enregistrer'}
            </button>
          </div>
        </>
      )}
    </section>
  )
}
