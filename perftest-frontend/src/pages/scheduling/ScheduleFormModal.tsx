import { useEffect, useId, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import SearchSelect from '../../components/SearchSelect'
import { scheduledExecutionsBackendApi } from '../../services/api/scheduledExecutionsBackend'
import { ApiError } from '../../services/api/httpClient'
import {
  BackendScenarioResponse, BackendScheduleNotifyMode, BackendScheduleType, BackendScheduledExecutionRequest,
  BackendScheduledExecutionResponse,
} from '../../types/backendContracts'
import FrDateInput from '../executions/FrDateInput'
import {
  FREQUENCY_OPTIONS, Frequency, RecurrenceForm, WEEK_DAYS, browserTimezone, buildCron, describeCron, formatFrDay,
  formatInZone, isoToZoned, maskFrDateTime, parseCron, parseFrDateTime, parseTime, timezoneOptions, toFiveFields, zonedToIso,
} from './scheduleModel'

// ============================================================
// Formulaire « Nouvelle planification » / « Modifier la planification ».
// Aucune expression CRON à connaître : une fréquence (chaque jour, jours
// ouvrés, chaque semaine, chaque mois) + une heure ; « Personnalisée »
// pour un CRON libre, traduit en français sous le champ. Les 3 prochaines
// exécutions viennent du serveur (même calcul que le déclencheur).
// ============================================================

interface Props {
  schedule: BackendScheduledExecutionResponse | null
  scenarios: BackendScenarioResponse[]
  onClose: () => void
  onSaved: (saved: BackendScheduledExecutionResponse, created: boolean) => void
}

function message(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'Action refusée : votre rôle ne permet pas de gérer les planifications.'
    if (err.status >= 500) return 'Erreur du serveur LoadPilot. Réessayez plus tard.'
    return err.message
  }
  return err instanceof Error ? err.message : fallback
}

export default function ScheduleFormModal({ schedule, scenarios, onClose, onSaved }: Props) {
  const editing = schedule != null
  const titleId = useId()
  const timezones = useMemo(() => timezoneOptions(), [])

  const initialTz = schedule?.timezone ?? browserTimezone
  const [name, setName] = useState(schedule?.name ?? '')
  const [scenarioId, setScenarioId] = useState(schedule?.scenarioId ?? '')
  const [type, setType] = useState<BackendScheduleType>(schedule?.scheduleType ?? 'RECURRING_CRON')
  const [timezone, setTimezone] = useState(initialTz)
  const [oneTimeText, setOneTimeText] = useState(() => {
    if (!schedule?.runAt) return ''
    const z = isoToZoned(schedule.runAt, initialTz)
    return `${formatFrDay(z.day)} ${z.time}`
  })
  const [recurrence, setRecurrence] = useState<RecurrenceForm>(() =>
    schedule?.scheduleType === 'RECURRING_CRON' ? parseCron(schedule.cronExpression)
      : { frequency: 'DAILY', time: '02:00', weekDays: [1], monthDay: 1, customCron: '0 2 * * *' })
  const [endDay, setEndDay] = useState(() => (schedule?.endAt ? isoToZoned(schedule.endAt, initialTz).day : ''))
  const [notifyMode, setNotifyMode] = useState<BackendScheduleNotifyMode>(schedule?.notifyMode ?? 'ALWAYS')
  const [submitted, setSubmitted] = useState(false)
  const [saving, setSaving] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)
  const [preview, setPreview] = useState<{ runs: string[]; error: string | null; loading: boolean }>({ runs: [], error: null, loading: false })

  // Scénarios planifiables : actifs et non archivés (+ celui de la planification modifiée).
  const selectable = useMemo(() => scenarios.filter((s) =>
    (s.status === 'ACTIVE' && !s.archived && !s.applicationArchived) || s.id === schedule?.scenarioId), [scenarios, schedule])
  const scenarioOptions = useMemo(() => selectable.map((s) => ({ value: s.id, label: s.name, hint: s.applicationName })), [selectable])
  const scenario = scenarios.find((s) => s.id === scenarioId) ?? null

  // ---- valeurs calculées et validation
  const oneTime = parseFrDateTime(oneTimeText)
  const runAtIso = oneTime ? zonedToIso(oneTime.day, oneTime.time, timezone) : null
  const cron = type === 'RECURRING_CRON' ? buildCron(recurrence) : null
  const cronText = recurrence.frequency === 'CUSTOM' ? describeCron(recurrence.customCron) : null
  // Fin de journée choisie, dans le fuseau de la planification.
  const endAtIso = type === 'RECURRING_CRON' && endDay ? (() => {
    const start = zonedToIso(endDay, '23:59', timezone)
    return start ? new Date(new Date(start).getTime() + 59_999).toISOString() : null
  })() : null

  const errors: Record<string, string> = {}
  if (!name.trim()) errors.name = 'Donnez un nom à la planification (ex. « Test nocturne Banking »).'
  if (!scenarioId) errors.scenario = 'Choisissez le scénario à lancer.'
  if (type === 'ONE_TIME') {
    if (!oneTimeText.trim()) errors.runAt = 'Indiquez la date et l’heure (jj/mm/aaaa hh:mm).'
    else if (!oneTime || !runAtIso) errors.runAt = 'Date ou heure invalide : format jj/mm/aaaa hh:mm.'
    else if (new Date(runAtIso).getTime() <= Date.now()) errors.runAt = 'Cette date est déjà passée : choisissez un moment dans le futur.'
  } else if (recurrence.frequency === 'CUSTOM') {
    if (!recurrence.customCron.trim()) errors.cron = 'Saisissez une expression CRON (ex. « 0 2 * * * » pour chaque jour à 02:00).'
    else if (!cron || !cronText) errors.cron = 'Expression CRON invalide : 5 champs attendus (minute heure jour mois jour-de-semaine).'
  } else {
    if (!parseTime(recurrence.time)) errors.time = 'Heure invalide (hh:mm).'
    if (recurrence.frequency === 'WEEKLY' && recurrence.weekDays.length === 0) errors.days = 'Choisissez au moins un jour.'
  }
  const scheduleReady = type === 'ONE_TIME' ? !errors.runAt : !errors.cron && !errors.time && !errors.days

  // ---- aperçu des 3 prochaines exécutions (serveur, après une courte pause de saisie)
  useEffect(() => {
    if (!scheduleReady) { setPreview({ runs: [], error: null, loading: false }); return }
    let alive = true
    setPreview((p) => ({ ...p, loading: true }))
    const timer = window.setTimeout(() => {
      scheduledExecutionsBackendApi.preview({
        scheduleType: type, timezone,
        runAt: type === 'ONE_TIME' ? runAtIso : null,
        cronExpression: type === 'RECURRING_CRON' ? cron : null,
        endAt: endAtIso,
      })
        .then((r) => { if (alive) setPreview({ runs: r.nextRuns, error: null, loading: false }) })
        .catch((err) => { if (alive) setPreview({ runs: [], error: message(err, 'Aperçu indisponible.'), loading: false }) })
    }, 350)
    return () => { alive = false; window.clearTimeout(timer) }
  }, [scheduleReady, type, timezone, runAtIso, cron, endAtIso])

  // Passage à « Personnalisée » : le champ CRON part de la fréquence déjà choisie (forme standard à 5 champs).
  const setFreq = (frequency: Frequency) => setRecurrence((r) => {
    const current = r.frequency !== 'CUSTOM' ? buildCron(r) : null
    return { ...r, frequency, customCron: frequency === 'CUSTOM' && current ? toFiveFields(current) : r.customCron }
  })
  const toggleDay = (d: number) => setRecurrence((r) => ({
    ...r, weekDays: r.weekDays.includes(d) ? r.weekDays.filter((x) => x !== d) : [...r.weekDays, d],
  }))

  const submit = async () => {
    setSubmitted(true)
    setServerError(null)
    if (Object.keys(errors).length > 0 || preview.error) return
    const payload: BackendScheduledExecutionRequest = {
      scenarioId, name: name.trim(), scheduleType: type, timezone,
      runAt: type === 'ONE_TIME' ? runAtIso : null,
      cronExpression: type === 'RECURRING_CRON' ? cron : null,
      endAt: endAtIso,
      notifyMode,
    }
    setSaving(true)
    try {
      const saved = editing
        ? await scheduledExecutionsBackendApi.update(schedule.id, payload)
        : await scheduledExecutionsBackendApi.create(payload)
      onSaved(saved, !editing)
    } catch (err) {
      setServerError(message(err, 'Impossible d’enregistrer cette planification.'))
    } finally {
      setSaving(false)
    }
  }

  const show = (key: string) => (submitted ? errors[key] : undefined)
  const differentTz = timezone !== browserTimezone

  return (
    <div className="pl-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget && !saving) onClose() }}>
      <div className="pl-modal" role="dialog" aria-modal="true" aria-labelledby={titleId}
        onKeyDown={(e) => { if (e.key === 'Escape' && !saving) onClose() }}>
        <div className="pl-modal-head">
          <h2 id={titleId}>{editing ? 'Modifier la planification' : 'Nouvelle planification'}</h2>
          <button type="button" className="ex-icon-btn" aria-label="Fermer" onClick={onClose} disabled={saving}><i className="bi bi-x-lg"></i></button>
        </div>

        <div className="pl-modal-body">
          {serverError && (
            <div className="pt-alert-banner danger" role="alert"><i className="bi bi-exclamation-triangle-fill"></i>{serverError}</div>
          )}

          <div className="pl-field">
            <label htmlFor={`${titleId}-name`}>Nom</label>
            <input id={`${titleId}-name`} className={`pt-form-control ${show('name') ? 'is-invalid' : ''}`} value={name} maxLength={255}
              placeholder="Ex. Test nocturne Banking" onChange={(e) => setName(e.target.value)} />
            {show('name') && <div className="pl-error">{show('name')}</div>}
          </div>

          <div className="pl-field">
            <span className="pl-legend">Scénario</span>
            <SearchSelect options={scenarioOptions} value={scenarioId} onChange={setScenarioId} allLabel="Choisir un scénario actif…"
              ariaLabel="Scénario à lancer" width="100%" />
            {show('scenario') && <div className="pl-error">{show('scenario')}</div>}
            {selectable.length === 0 && <div className="pl-hint">Aucun scénario actif : activez un scénario pour pouvoir le planifier.</div>}
            {scenario && (
              <div className="pl-load mt-2" aria-label="Charge du scénario">
                <span><strong>{scenario.virtualUsers}</strong> VU</span>
                <span>montée <strong>{scenario.rampUpSeconds} s</strong></span>
                {scenario.durationSeconds != null && <span>durée <strong>{scenario.durationSeconds} s</strong></span>}
                {scenario.iterations != null && <span><strong>{scenario.iterations}</strong> itération(s)</span>}
                <span>· {scenario.applicationName}</span>
                <Link to={`/scenarios/${scenario.id}/edit`}>Voir le scénario</Link>
              </div>
            )}
            {scenario && <div className="pl-hint">La charge vient du scénario : modifiez le scénario pour la changer.</div>}
          </div>

          <div className="pl-field">
            <span className="pl-legend" id={`${titleId}-type`}>Type</span>
            <div className="pl-segmented" role="group" aria-labelledby={`${titleId}-type`}>
              <button type="button" aria-pressed={type === 'ONE_TIME'} onClick={() => setType('ONE_TIME')}>Une seule fois</button>
              <button type="button" aria-pressed={type === 'RECURRING_CRON'} onClick={() => setType('RECURRING_CRON')}>Récurrente</button>
            </div>
          </div>

          {type === 'ONE_TIME' ? (
            <div className="pl-field">
              <label htmlFor={`${titleId}-runat`}>Date et heure</label>
              <input id={`${titleId}-runat`} className={`pt-form-control ${show('runAt') ? 'is-invalid' : ''}`} inputMode="numeric"
                placeholder="jj/mm/aaaa hh:mm" maxLength={16} value={oneTimeText}
                onChange={(e) => setOneTimeText(maskFrDateTime(e.target.value))} />
              {show('runAt') ? <div className="pl-error">{show('runAt')}</div> : <div className="pl-hint">Format jj/mm/aaaa hh:mm, dans le fuseau choisi plus bas.</div>}
            </div>
          ) : (
            <>
              <div className="pl-row">
                <div className="pl-field">
                  <label htmlFor={`${titleId}-freq`}>Fréquence</label>
                  <select id={`${titleId}-freq`} className="pt-form-control" value={recurrence.frequency}
                    onChange={(e) => setFreq(e.target.value as Frequency)}>
                    {FREQUENCY_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </div>
                {recurrence.frequency !== 'CUSTOM' && (
                  <div className="pl-field">
                    <label htmlFor={`${titleId}-time`}>Heure</label>
                    <input id={`${titleId}-time`} type="time" className={`pt-form-control ${show('time') ? 'is-invalid' : ''}`}
                      value={recurrence.time} onChange={(e) => setRecurrence((r) => ({ ...r, time: e.target.value }))} />
                    {show('time') && <div className="pl-error">{show('time')}</div>}
                  </div>
                )}
              </div>

              {recurrence.frequency === 'WEEKLY' && (
                <div className="pl-field">
                  <span className="pl-legend" id={`${titleId}-days`}>Jours</span>
                  <div className="pl-days" role="group" aria-labelledby={`${titleId}-days`}>
                    {WEEK_DAYS.map((d) => (
                      <button key={d.value} type="button" className="pl-day" aria-pressed={recurrence.weekDays.includes(d.value)}
                        aria-label={d.long} onClick={() => toggleDay(d.value)}>{d.short}</button>
                    ))}
                  </div>
                  {errors.days && <div className="pl-error">{errors.days}</div>}
                </div>
              )}

              {recurrence.frequency === 'MONTHLY' && (
                <div className="pl-field">
                  <label htmlFor={`${titleId}-dom`}>Jour du mois</label>
                  <select id={`${titleId}-dom`} className="pt-form-control" value={recurrence.monthDay}
                    onChange={(e) => setRecurrence((r) => ({ ...r, monthDay: Number(e.target.value) }))}>
                    {Array.from({ length: 31 }, (_, i) => i + 1).map((d) => <option key={d} value={d}>{d === 1 ? '1er' : d}</option>)}
                  </select>
                  {recurrence.monthDay > 28 && <div className="pl-hint">Les mois qui n’ont pas de {recurrence.monthDay} sont sautés.</div>}
                </div>
              )}

              {recurrence.frequency === 'CUSTOM' && (
                <div className="pl-field">
                  <label htmlFor={`${titleId}-cron`}>Expression CRON</label>
                  <input id={`${titleId}-cron`} className={`pt-form-control pl-mono ${errors.cron && recurrence.customCron.trim() ? 'is-invalid' : ''}`}
                    value={recurrence.customCron} placeholder="0 2 * * *" spellCheck={false}
                    onChange={(e) => setRecurrence((r) => ({ ...r, customCron: e.target.value }))} />
                  {cronText ? (
                    <div className="pl-cron-text" aria-live="polite"><i className="bi bi-translate"></i><span>{cronText}</span></div>
                  ) : (
                    (recurrence.customCron.trim() || submitted) && errors.cron && <div className="pl-error">{errors.cron}</div>
                  )}
                  <div className="pl-hint">minute · heure · jour du mois · mois · jour de semaine — ex. « */5 * * * * » toutes les 5 minutes.</div>
                </div>
              )}

              <div className="pl-field">
                <span className="pl-legend">Date de fin (facultative)</span>
                <FrDateInput value={endDay} onChange={setEndDay} label="Date de fin" />
                <div className="pl-hint">Plus aucun lancement après ce jour. Laissez vide pour ne jamais s’arrêter.</div>
              </div>
            </>
          )}

          <div className="pl-field">
            <label htmlFor={`${titleId}-tz`}>Fuseau horaire</label>
            <select id={`${titleId}-tz`} className="pt-form-control" value={timezone} onChange={(e) => setTimezone(e.target.value)}>
              {timezones.map((z) => <option key={z} value={z}>{z}{z === browserTimezone ? ' (le vôtre)' : ''}</option>)}
            </select>
          </div>

          <fieldset className="pl-field">
            <legend className="pl-legend">Notifications</legend>
            <div className="pl-radio">
              <label>
                <input type="radio" name={`${titleId}-notify`} checked={notifyMode === 'ALWAYS'} onChange={() => setNotifyMode('ALWAYS')} />
                <span>À chaque fin d’exécution</span>
              </label>
              <label>
                <input type="radio" name={`${titleId}-notify`} checked={notifyMode === 'PROBLEMS'} onChange={() => setNotifyMode('PROBLEMS')} />
                <span>Seulement en cas d’échec ou de régression</span>
              </label>
            </div>
          </fieldset>

          <section className="pl-preview" aria-label="Prochaines exécutions" aria-live="polite">
            <h3>{type === 'ONE_TIME' ? 'Exécution prévue' : '3 prochaines exécutions'}</h3>
            {!scheduleReady ? (
              <p className="mb-0 pl-local">Complétez la date ou la fréquence pour voir l’aperçu.</p>
            ) : preview.error ? (
              <div className="pl-error mt-0">{preview.error}</div>
            ) : preview.loading && preview.runs.length === 0 ? (
              <p className="mb-0 pl-local">Calcul…</p>
            ) : (
              <ol>
                {preview.runs.map((r) => (
                  <li key={r}>
                    {formatInZone(r, timezone)}
                    {differentTz && <span className="pl-local"> — {formatInZone(r, browserTimezone)} chez vous</span>}
                  </li>
                ))}
              </ol>
            )}
          </section>
        </div>

        <div className="pl-modal-foot">
          <button type="button" className="pt-btn-ghost" onClick={onClose} disabled={saving}>Annuler</button>
          <button type="button" className="pt-btn-primary" onClick={submit} disabled={saving}>
            {saving && <span className="spinner-border spinner-border-sm me-1"></span>}
            {editing ? 'Enregistrer' : 'Créer la planification'}
          </button>
        </div>
      </div>
    </div>
  )
}
