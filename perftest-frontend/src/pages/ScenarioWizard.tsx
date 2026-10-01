import React, { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { BackendScenarioRequest, BackendScenarioResponse, BackendStepResponse, BackendStepTestResultResponse, BackendStopMode } from '../types/backendContracts'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { stepsBackendApi } from '../services/api/stepsBackend'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { executionsBackendApi } from '../services/api/executionsBackend'
import { scheduledExecutionsBackendApi } from '../services/api/scheduledExecutionsBackend'
import { ApiError } from '../services/api/httpClient'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { firstError, validateRequired, validateMaxLength, NAME_MAX_LENGTH, DESCRIPTION_MAX_LENGTH } from '../utils/validation'

// ============================================================
// Restauration du design ancien à partir des captures fournies — stepper
// visuel à 5 étapes GLOBALES : Étapes / Configuration / Utilisateurs /
// Planification / Résumé. "Configuration" (détail HTTP d'une étape) reste
// une page séparée (/scenarios/create-step, voir ScenarioStepEditor.tsx) —
// le stepper y est affiché aussi, avec "Configuration" actif.
//
// "Planification" (étape 4) — contrairement à la phase de restauration
// précédente qui l'excluait entièrement, cette version la réintroduit CAR
// une vraie capacité backend équivalente existe réellement :
//   - "Immédiate"  -> POST /api/executions (executionsBackendApi.execute),
//     déjà utilisé ailleurs dans ce projet.
//   - "Planifiée"  -> POST /api/scheduled-executions avec
//     scheduleType: 'ONE_TIME', runAt (ISO, doit être dans le futur).
//   - "Récurrente" -> POST /api/scheduled-executions avec
//     scheduleType: 'RECURRING_CRON'. L'ancien design ne proposait qu'une
//     Fréquence (Quotidien/Hebdomadaire/Mensuel), jamais d'heure précise :
//     une heure fixe (09:00, fuseau du navigateur) est donc utilisée pour
//     construire une vraie expression cron — seule adaptation nécessaire
//     pour connecter ce choix visuel à une vraie planification serveur,
//     jamais une fonctionnalité inventée.
//
// Import CSV fichier (restauration ciblée, étape 3) — lecture PUREMENT
// navigateur (FileReader natif). Le contenu lu remplit le champ RÉEL déjà
// existant `Scenario.csvData` (même contrainte de longueur, même endpoint
// PUT /api/scenarios) — jamais un second modèle de données, jamais un
// nouvel endpoint backend. La saisie manuelle (textarea) reste disponible
// et n'est jamais désactivée par un import fichier.
// ============================================================

const STEPPER_ITEMS = [
  { number: 1, label: 'Étapes' },
  { number: 2, label: 'Configuration' },
  { number: 3, label: 'Utilisateurs' },
  { number: 4, label: 'Planification' },
  { number: 5, label: 'Résumé' },
]

type ExecutionType = 'immediate' | 'scheduled' | 'recurring'
type Recurrence = 'Quotidien' | 'Hebdomadaire' | 'Mensuel'

const RECURRENCE_TO_CRON: Record<Recurrence, string> = {
  // Seconde Minute Heure JourDuMois Mois JourDeLaSemaine — heure fixe 09:00
  // faute d'un champ heure dans l'ancien design pour "Récurrente".
  Quotidien: '0 0 9 * * *',
  Hebdomadaire: '0 0 9 * * MON',
  Mensuel: '0 0 9 1 * *',
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 400: return `Données invalides : ${err.message}`
      case 401: return 'Vous devez être connecté (Keycloak) pour effectuer cette action.'
      case 403: return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      case 404: return 'Scénario ou application introuvable.'
      case 409: return err.message
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

const emptyForm = {
  name: '', applicationId: '', description: '',
  virtualUsers: '1', rampUpSeconds: '0', durationSeconds: '', iterations: '', thinkTimeMs: '0',
  csvData: '', targetRps: '', stopMode: 'AUTO' as BackendStopMode,
}

function ScenarioWizard() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const editId = searchParams.get('edit')
  const newForAppId = searchParams.get('app')
  const initialWizardStep = Number(searchParams.get('wizardStep') || '1')
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const isKeycloak = authProvider === 'keycloak'
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  const [scenarioId, setScenarioId] = useState<string | null>(editId)
  const [applicationName, setApplicationName] = useState<string>('')
  const [form, setForm] = useState(emptyForm)
  const [steps, setSteps] = useState<BackendStepResponse[]>([])
  const [wizardStep, setWizardStep] = useState<1 | 3 | 4 | 5>(([1, 3, 4, 5].includes(initialWizardStep) ? initialWizardStep : 1) as 1 | 3 | 4 | 5)
  const [loading, setLoading] = useState(!!editId)
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [deleteStepConfirm, setDeleteStepConfirm] = useState<BackendStepResponse | null>(null)
  const [deletingStep, setDeletingStep] = useState(false)

  // "Tester les étapes sélectionnées" (passage produit réel, 2026-09-30) —
  // POST /api/steps/test-batch, une VRAIE requête serveur-à-serveur par
  // étape sélectionnée (voir StepController.java) — jamais une Execution,
  // jamais un résultat simulé.
  const [selectedStepIds, setSelectedStepIds] = useState<string[]>([])
  const [testingSteps, setTestingSteps] = useState(false)
  const [stepTestResults, setStepTestResults] = useState<BackendStepTestResultResponse[] | null>(null)
  const [stepTestError, setStepTestError] = useState<string | null>(null)

  // Étape 3 — Import CSV (restauration ciblée, voir en-tête du fichier).
  const [csvFileName, setCsvFileName] = useState('')
  const [csvFileError, setCsvFileError] = useState<string | null>(null)

  // Étape 4 — Planification (voir en-tête du fichier pour le mapping réel).
  const [executionType, setExecutionType] = useState<ExecutionType>('immediate')
  const [scheduledDate, setScheduledDate] = useState('')
  const [scheduledTime, setScheduledTime] = useState('09:00')
  const [recurrence, setRecurrence] = useState<Recurrence>('Quotidien')

  const loadSteps = (id: string) => {
    stepsBackendApi.getByScenario(id).then((list) => setSteps([...list].sort((a, b) => a.order - b.order))).catch(() => setSteps([]))
  }

  const toggleStepSelection = (id: string) => {
    setSelectedStepIds((prev) => (prev.includes(id) ? prev.filter((s) => s !== id) : [...prev, id]))
  }
  const toggleSelectAllSteps = () => {
    setSelectedStepIds((prev) => (prev.length === steps.length ? [] : steps.map((s) => s.id)))
  }

  const handleTestSelectedSteps = async () => {
    if (selectedStepIds.length === 0) return
    setTestingSteps(true)
    setStepTestError(null)
    setStepTestResults(null)
    try {
      const results = await stepsBackendApi.testBatch({ stepIds: selectedStepIds })
      setStepTestResults(results)
      const failCount = results.filter((r) => !r.success).length
      showToast(
        failCount === 0
          ? `${results.length} étape(s) testée(s) — toutes réussies.`
          : `${results.length} étape(s) testée(s) — ${failCount} en échec.`,
        failCount === 0 ? 'success' : 'danger'
      )
    } catch (err) {
      setStepTestError(describeApiError(err, 'Erreur lors du test des étapes sélectionnées.'))
    } finally {
      setTestingSteps(false)
    }
  }

  useEffect(() => {
    if (editId) {
      setLoading(true)
      scenariosBackendApi.getById(editId)
        .then((s) => {
          setForm({
            name: s.name,
            applicationId: s.applicationId,
            description: s.description ?? '',
            virtualUsers: String(s.virtualUsers),
            rampUpSeconds: String(s.rampUpSeconds),
            durationSeconds: s.durationSeconds != null ? String(s.durationSeconds) : '',
            iterations: s.iterations != null ? String(s.iterations) : '',
            thinkTimeMs: String(s.thinkTimeMs),
            csvData: s.csvData ?? '',
            targetRps: s.targetRps != null ? String(s.targetRps) : '',
            stopMode: s.stopMode,
          })
          setApplicationName(s.applicationName)
          setScenarioId(s.id)
          loadSteps(s.id)
        })
        .catch((err) => setActionError(describeApiError(err, 'Impossible de charger le scénario.')))
        .finally(() => setLoading(false))
    } else if (newForAppId) {
      applicationsBackendApi.getById(newForAppId)
        .then((app) => {
          setApplicationName(app.name)
          setForm((p) => ({ ...p, applicationId: app.id }))
        })
        .catch((err) => setActionError(describeApiError(err, "Impossible de charger l'application.")))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editId, newForAppId])

  const nameError = firstError(validateRequired(form.name, 'Le nom du scénario'), validateMaxLength(form.name, NAME_MAX_LENGTH, 'Le nom du scénario'))
  const descriptionError = validateMaxLength(form.description, DESCRIPTION_MAX_LENGTH, 'La description')

  const virtualUsersNum = Number(form.virtualUsers)
  const virtualUsersError = !form.virtualUsers.trim() ? "Le nombre d'utilisateurs virtuels est obligatoire."
    : !Number.isInteger(virtualUsersNum) || virtualUsersNum < 1 || virtualUsersNum > 500 ? 'Doit être un entier entre 1 et 500.' : null
  const rampUpNum = Number(form.rampUpSeconds)
  const rampUpError = !form.rampUpSeconds.trim() ? 'Le ramp-up est obligatoire (0 = démarrage immédiat).'
    : !Number.isInteger(rampUpNum) || rampUpNum < 0 ? 'Doit être un entier positif ou nul.' : null
  const durationNum = form.durationSeconds.trim() ? Number(form.durationSeconds) : null
  const durationError = durationNum !== null && (!Number.isInteger(durationNum) || durationNum < 1) ? "Doit être un entier d'au moins 1 seconde." : null
  const iterationsNum = form.iterations.trim() ? Number(form.iterations) : null
  const iterationsError = iterationsNum !== null && (!Number.isInteger(iterationsNum) || iterationsNum < 1) ? "Doit être un entier d'au moins 1." : null
  const thinkTimeNum = Number(form.thinkTimeMs)
  const thinkTimeError = !form.thinkTimeMs.trim() ? 'Le think time est obligatoire (0 = aucune pause).'
    : !Number.isInteger(thinkTimeNum) || thinkTimeNum < 0 ? 'Doit être un entier positif ou nul.' : null
  const csvDataError = form.csvData.length > 50000 ? 'Les données CSV ne doivent pas dépasser 50000 caractères.' : null
  const targetRpsNum = form.targetRps.trim() ? Number(form.targetRps) : null
  const targetRpsError = targetRpsNum !== null && (!Number.isInteger(targetRpsNum) || targetRpsNum < 1)
    ? "Le débit cible (requêtes/s) doit être un entier d'au moins 1 si renseigné." : null

  const isInfosValid = !nameError && !descriptionError && !!form.applicationId
  const isChargeValid = !virtualUsersError && !rampUpError && !durationError && !iterationsError && !thinkTimeError && !csvDataError && !targetRpsError
  const isScheduleValid = executionType !== 'scheduled' || !!scheduledDate

  const buildPayload = (): BackendScenarioRequest => ({
    applicationId: form.applicationId,
    name: form.name.trim(),
    description: form.description.trim() || null,
    virtualUsers: virtualUsersNum,
    rampUpSeconds: rampUpNum,
    durationSeconds: durationNum,
    iterations: iterationsNum,
    thinkTimeMs: thinkTimeNum,
    csvData: form.csvData.trim() || null,
    targetRps: targetRpsNum,
    stopMode: form.stopMode,
  })

  // Restauration ciblée (import CSV fichier, voir en-tête du fichier) —
  // lecture PUREMENT côté navigateur (FileReader natif, aucune dépendance
  // ajoutée), le contenu lu remplit le champ RÉEL existant `form.csvData`
  // (même API PUT /api/scenarios déjà utilisée par la saisie manuelle,
  // aucun nouvel endpoint, aucun nouveau modèle de données). La validation
  // de longueur (`csvDataError`, 50000 caractères) déjà en place s'applique
  // identiquement, que le contenu vienne d'un fichier ou d'une saisie
  // manuelle.
  const handleCsvFileChange = (file: File | undefined) => {
    setCsvFileError(null)
    if (!file) return
    if (!file.name.toLowerCase().endsWith('.csv')) {
      setCsvFileError('Le fichier doit avoir une extension .csv.')
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      const text = typeof reader.result === 'string' ? reader.result : ''
      const firstLine = text.split(/\r?\n/).find((l) => l.trim().length > 0)
      if (!text.trim() || !firstLine) {
        setCsvFileError('Le fichier CSV est vide ou illisible.')
        return
      }
      setForm((p) => ({ ...p, csvData: text }))
      setCsvFileName(file.name)
    }
    reader.onerror = () => setCsvFileError(`Impossible de lire le fichier ${file.name}.`)
    reader.readAsText(file, 'utf-8')
  }

  const handleRemoveCsvFile = () => {
    setCsvFileName('')
    setCsvFileError(null)
  }

  const goToStepperStep = (n: 1 | 3 | 4 | 5) => {
    setWizardStep(n)
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.set('wizardStep', String(n))
      return next
    })
  }

  const handleNextFromInfos = async () => {
    if (!canWrite) return
    if (!isInfosValid) { setActionError('Veuillez renseigner un nom de scénario valide.'); return }
    setSaving(true)
    setActionError(null)
    try {
      if (scenarioId) {
        await scenariosBackendApi.update(scenarioId, buildPayload())
      } else {
        const created = await scenariosBackendApi.create(buildPayload())
        setScenarioId(created.id)
        setSearchParams((prev) => {
          const next = new URLSearchParams(prev)
          next.set('edit', created.id)
          next.delete('app')
          next.set('wizardStep', '3')
          return next
        })
      }
      goToStepperStep(3)
    } catch (err) {
      showToast(describeApiError(err, "Erreur lors de l'enregistrement du scénario."), 'danger')
    } finally {
      setSaving(false)
    }
  }

  // Étape 5 (Résumé) — enregistre les paramètres de charge puis applique
  // réellement le choix de planification (voir en-tête du fichier).
  const handleFinish = async () => {
    if (!canWrite || !scenarioId) return
    if (!isChargeValid || !isScheduleValid) return
    setSaving(true)
    setActionError(null)
    try {
      await scenariosBackendApi.update(scenarioId, buildPayload())

      if (executionType === 'immediate') {
        if (steps.length === 0) {
          showToast(`Scénario « ${form.name} » enregistré. Ajoutez au moins une étape avant de pouvoir l'exécuter.`, 'success')
        } else {
          await executionsBackendApi.execute({ scenarioId })
          showToast(`Scénario « ${form.name} » enregistré et exécution lancée immédiatement.`, 'success')
        }
      } else if (executionType === 'scheduled') {
        const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone
        const runAt = new Date(`${scheduledDate}T${scheduledTime || '09:00'}:00`).toISOString()
        await scheduledExecutionsBackendApi.create({
          scenarioId,
          name: `${form.name} — planification`,
          scheduleType: 'ONE_TIME',
          runAt,
          timezone,
        })
        showToast(`Scénario « ${form.name} » enregistré et planifié pour le ${scheduledDate} à ${scheduledTime}.`, 'success')
      } else {
        const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone
        await scheduledExecutionsBackendApi.create({
          scenarioId,
          name: `${form.name} — planification récurrente`,
          scheduleType: 'RECURRING_CRON',
          cronExpression: RECURRENCE_TO_CRON[recurrence],
          timezone,
        })
        showToast(`Scénario « ${form.name} » enregistré avec une planification récurrente (${recurrence.toLowerCase()}, 09:00).`, 'success')
      }
      navigate(executionType === 'immediate' ? '/executions' : '/planification')
    } catch (err) {
      showToast(describeApiError(err, "Erreur lors de l'enregistrement."), 'danger')
    } finally {
      setSaving(false)
    }
  }

  const handleDeleteStep = async () => {
    if (!canWrite || !deleteStepConfirm || !scenarioId) return
    setDeletingStep(true)
    try {
      await stepsBackendApi.remove(deleteStepConfirm.id)
      loadSteps(scenarioId)
      showToast(`Étape « ${deleteStepConfirm.name} » supprimée avec succès.`, 'success')
      setDeleteStepConfirm(null)
    } catch (err) {
      showToast(describeApiError(err, "Erreur lors de la suppression de l'étape."), 'danger')
    } finally {
      setDeletingStep(false)
    }
  }

  const stepperItems = useMemo(() => STEPPER_ITEMS.map((s) => ({ ...s, active: s.number === wizardStep, completed: s.number < wizardStep })), [wizardStep])

  if (loading) {
    return (
      <div className="pt-content">
        <div className="pt-empty-state">
          <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
          <p>Chargement du scénario...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div>
          <button
            className="btn btn-link p-0"
            onClick={() => navigate('/scenarios')}
            style={{ textDecoration: 'none', color: 'var(--pt-primary)', fontSize: '13px', fontWeight: 500, display: 'inline-flex', alignItems: 'center', gap: '6px', marginBottom: '8px', border: 'none', background: 'none' }}
          >
            <i className="bi bi-arrow-left"></i> Retour aux scénarios
          </button>
          <div className="page-title">
            <h1>Créer / Modifier un scénario</h1>
          </div>
        </div>
        <TopBar searchPlaceholder="" />
      </div>

      {actionError && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {actionError}
        </div>
      )}

      {/* Stepper Navigation — 5 étapes globales (voir ScenarioStepEditor.tsx
          pour le même stepper affiché sur l'étape "Configuration"). */}
      <div className="pt-card mb-4" style={{ padding: '1.25rem 1.5rem' }}>
        <div className="d-flex align-items-center justify-content-between" style={{ position: 'relative' }}>
          {stepperItems.map((step, index) => {
            const isLast = index === stepperItems.length - 1
            const clickable = step.number === 1 || !!scenarioId
            return (
              <React.Fragment key={step.number}>
                <div
                  className="d-flex align-items-center gap-2"
                  style={{ zIndex: 2, cursor: clickable ? 'pointer' : 'not-allowed' }}
                  onClick={() => {
                    if (!clickable) return
                    if (step.number === 2) {
                      if (steps.length === 0) { showToast('Ajoutez au moins une étape avant de la configurer.', 'danger'); return }
                      navigate(`/scenarios/create-step?scenario=${scenarioId}&step=${steps[0].id}`)
                      return
                    }
                    goToStepperStep(step.number as 1 | 3 | 4 | 5)
                  }}
                >
                  <div
                    style={{
                      width: '36px', height: '36px', borderRadius: '50%',
                      background: step.active ? 'var(--pt-primary)' : step.completed ? 'var(--pt-success)' : 'var(--pt-card-bg)',
                      border: step.active || step.completed ? 'none' : '2px solid var(--pt-border)',
                      color: step.active || step.completed ? 'white' : 'var(--pt-text-muted)',
                      display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700, fontSize: '14px', transition: 'all 0.2s ease',
                    }}
                  >
                    {step.completed ? <i className="bi bi-check-lg"></i> : step.number}
                  </div>
                  <div>
                    <div style={{ fontSize: '13.5px', fontWeight: step.active ? 700 : 500, color: step.active ? 'var(--pt-primary)' : step.completed ? 'var(--pt-text)' : 'var(--pt-text-muted)' }}>
                      {step.label}
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)' }}>Étape {step.number} sur 5</div>
                  </div>
                </div>
                {!isLast && <div style={{ flex: 1, height: '2px', background: step.completed ? 'var(--pt-success)' : 'var(--pt-border)', margin: '0 1rem', alignSelf: 'center' }}></div>}
              </React.Fragment>
            )
          })}
        </div>
      </div>

      {/* ÉTAPE 1 : Étapes (Infos du scénario + table des étapes) */}
      {wizardStep === 1 && (
        <>
          <div className="pt-card mb-4">
            <div className="d-flex align-items-center gap-2 pb-3 mb-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
              <i className="bi bi-info-circle" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
              <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>Infos du scénario</h6>
            </div>
            <fieldset disabled={!canWrite} className="row g-3" style={{ border: 'none', padding: 0, margin: 0 }}>
              <div className="col-12 col-md-6">
                <label className="pt-form-label">Nom du scénario *</label>
                <input className="pt-form-control" value={form.name} onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} placeholder="ex: Transfert national" />
                {nameError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{nameError}</div>}
              </div>
              <div className="col-12 col-md-6">
                <label className="pt-form-label">Application</label>
                <input className="pt-form-control" value={applicationName} disabled title="L'application d'un scénario ne peut pas être changée." />
              </div>
              <div className="col-12">
                <label className="pt-form-label">Description <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span></label>
                <textarea className="pt-form-control" rows={2} placeholder="Envoie un transfert national fictif." value={form.description} onChange={(e) => setForm((p) => ({ ...p, description: e.target.value }))}></textarea>
                {descriptionError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{descriptionError}</div>}
              </div>
              <div className="col-12">
                {canWrite && scenarioId && (
                  <button className="pt-btn-primary" style={{ fontSize: '12.5px' }} onClick={() => navigate(`/scenarios/create-step?scenario=${scenarioId}`)}>
                    <i className="bi bi-plus-lg"></i> Ajouter une étape
                  </button>
                )}
              </div>
            </fieldset>
          </div>

          {scenarioId && (
            <div className="pt-card" style={{ padding: 0 }}>
              <div className="d-flex justify-content-between align-items-center p-3 flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                <h6 style={{ fontSize: '14.5px', fontWeight: 600, margin: 0 }}>Étapes du scénario <span className="pt-pill neutral ms-1">{steps.length} étapes</span></h6>
                {steps.length > 0 && (
                  <button
                    className="pt-btn-outline" style={{ fontSize: '12.5px' }}
                    disabled={selectedStepIds.length === 0 || testingSteps}
                    onClick={handleTestSelectedSteps}
                  >
                    <i className={`bi ${testingSteps ? 'bi-arrow-repeat pt-spin' : 'bi-play-circle'} text-success me-1`}></i>
                    {testingSteps ? 'Test en cours...' : `Tester les étapes sélectionnées (${selectedStepIds.length})`}
                  </button>
                )}
              </div>
              {stepTestError && (
                <div className="pt-alert-banner danger m-3">
                  <i className="bi bi-exclamation-triangle-fill"></i>
                  {stepTestError}
                </div>
              )}
              {stepTestResults && (
                <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                  <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--pt-text-muted)', textTransform: 'uppercase', marginBottom: '8px' }}>
                    Résultats réels du test ({stepTestResults.length})
                  </div>
                  <div className="d-flex flex-column gap-2">
                    {stepTestResults.map((r) => (
                      <div key={r.stepId} style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 10px', borderRadius: 'var(--pt-radius-sm)', background: 'var(--pt-bg)', border: '1px solid var(--pt-border)' }}>
                        <i className={`bi ${r.success ? 'bi-check-circle-fill' : 'bi-x-circle-fill'}`} style={{ color: r.success ? 'var(--pt-success)' : 'var(--pt-danger)', fontSize: '15px', flexShrink: 0 }}></i>
                        <span style={{ fontWeight: 600, fontSize: '13px', flex: 1 }}>{r.stepName}</span>
                        {r.httpStatus != null && <span className={`pt-pill ${r.success ? 'success' : 'danger'}`} style={{ fontSize: '10.5px' }}>HTTP {r.httpStatus}</span>}
                        {r.assertionPassed != null && (
                          <span className={`pt-pill ${r.assertionPassed ? 'success' : 'danger'}`} style={{ fontSize: '10.5px' }}>
                            Assertion {r.assertionPassed ? 'OK' : 'échouée'}
                          </span>
                        )}
                        <span style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{r.responseTimeMs} ms</span>
                        {r.error && <span style={{ fontSize: '11px', color: 'var(--pt-danger)' }} title={r.error}>{r.error}</span>}
                      </div>
                    ))}
                  </div>
                </div>
              )}
              {steps.length === 0 ? (
                <div className="pt-empty-state">
                  <i className="bi bi-list-check" style={{ fontSize: '28px', color: 'var(--pt-text-muted)' }}></i>
                  <p>Aucune étape définie — ajoutez au moins une étape pour pouvoir lancer une exécution.</p>
                </div>
              ) : (
                <div className="pt-table-wrapper">
                  <table className="pt-table">
                    <thead>
                      <tr>
                        <th style={{ width: '36px' }}>
                          <input type="checkbox" checked={steps.length > 0 && selectedStepIds.length === steps.length} onChange={toggleSelectAllSteps} />
                        </th>
                        <th style={{ width: '50px' }}>#</th>
                        <th>Méthode</th>
                        <th>Nom</th>
                        <th>URL / Ressource</th>
                        <th>Attente</th>
                        <th>Statut</th>
                        <th style={{ textAlign: 'right' }}>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {steps.map((st) => (
                        <tr key={st.id}>
                          <td onClick={(e) => e.stopPropagation()}>
                            <input type="checkbox" checked={selectedStepIds.includes(st.id)} onChange={() => toggleStepSelection(st.id)} />
                          </td>
                          <td>{st.order}</td>
                          <td>
                            <span style={{ padding: '0.15rem 0.4rem', borderRadius: '4px', fontSize: '11px', fontWeight: 700, background: st.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)', color: st.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)' }}>
                              {st.method}
                            </span>
                          </td>
                          <td style={{ fontWeight: 600, fontSize: '13px' }}>{st.name}</td>
                          <td><code style={{ fontSize: '11.5px', color: 'var(--pt-primary)' }}>{st.url}</code></td>
                          <td style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>{st.expectedStatus != null ? `Status ${st.expectedStatus}` : '—'}</td>
                          <td>
                            <span className={`pt-pill ${st.status === 'ACTIVE' ? 'success' : 'neutral'}`} style={{ fontSize: '11px' }}>
                              {st.status === 'ACTIVE' ? 'Actif' : 'Inactif'}
                            </span>
                          </td>
                          <td>
                            <div className="d-flex justify-content-end gap-2">
                              {canWrite && (
                                <>
                                  <button className="topbar-icon" title="Modifier" style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-primary)', background: 'var(--pt-primary-light)' }} onClick={() => navigate(`/scenarios/create-step?scenario=${scenarioId}&step=${st.id}`)}>
                                    <i className="bi bi-pencil" style={{ fontSize: '13px', color: 'var(--pt-primary)' }}></i>
                                  </button>
                                  <button className="topbar-icon" title="Supprimer" style={{ width: '30px', height: '30px', borderRadius: '8px', border: '1px solid var(--pt-danger)', background: 'rgba(220,38,38,0.06)' }} onClick={() => setDeleteStepConfirm(st)}>
                                    <i className="bi bi-trash" style={{ fontSize: '13px', color: 'var(--pt-danger)' }}></i>
                                  </button>
                                </>
                              )}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <div className="d-flex justify-content-end p-3" style={{ borderTop: '1px solid var(--pt-border)' }}>
                <button className="pt-btn-primary" onClick={handleNextFromInfos} disabled={!isInfosValid || saving || !canWrite}>
                  {saving ? <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Enregistrement...</> : <>Suivant : Utilisateurs <i className="bi bi-arrow-right ms-1"></i></>}
                </button>
              </div>
            </div>
          )}
          {!scenarioId && canWrite && (
            <div className="d-flex justify-content-end mt-4">
              <button className="pt-btn-primary" onClick={handleNextFromInfos} disabled={!isInfosValid || saving}>
                {saving ? <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Enregistrement...</> : <>Suivant : Ajouter des étapes <i className="bi bi-arrow-right ms-1"></i></>}
              </button>
            </div>
          )}
        </>
      )}

      {/* ÉTAPE 3 : Utilisateurs Virtuels & Données de Test */}
      {wizardStep === 3 && scenarioId && (
        <div className="pt-card mb-4">
          <div className="d-flex align-items-center gap-2 pb-3 mb-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
            <i className="bi bi-people-fill" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
            <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>Utilisateurs Virtuels & Données de Test</h6>
          </div>
          <fieldset disabled={!canWrite} className="row g-4" style={{ border: 'none', padding: 0, margin: 0 }}>
            <div className="col-12 col-lg-6 d-flex flex-column gap-3">
              <div>
                <label className="pt-form-label">Nombre d'utilisateurs virtuels *</label>
                <input type="number" min={1} max={500} className="pt-form-control" value={form.virtualUsers} onChange={(e) => setForm((p) => ({ ...p, virtualUsers: e.target.value }))} />
                {virtualUsersError && <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{virtualUsersError}</div>}
              </div>
              <div>
                <label className="pt-form-label">Montée en charge (Ramp-up)</label>
                <div className="d-flex align-items-center gap-2">
                  <input type="number" min={0} className="pt-form-control" value={form.rampUpSeconds} onChange={(e) => setForm((p) => ({ ...p, rampUpSeconds: e.target.value }))} />
                  <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', width: '90px' }}>secondes</span>
                </div>
                {rampUpError && <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{rampUpError}</div>}
              </div>
              <div className="row g-2">
                <div className="col-6">
                  <label className="pt-form-label">Durée (s) <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span></label>
                  <input type="number" min={1} className="pt-form-control" placeholder="ex: 60" value={form.durationSeconds} onChange={(e) => setForm((p) => ({ ...p, durationSeconds: e.target.value }))} />
                  {durationError && <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{durationError}</div>}
                </div>
                <div className="col-6">
                  <label className="pt-form-label">Itérations <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span></label>
                  <input type="number" min={1} className="pt-form-control" placeholder="ex: 10" value={form.iterations} onChange={(e) => setForm((p) => ({ ...p, iterations: e.target.value }))} />
                  {iterationsError && <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{iterationsError}</div>}
                </div>
              </div>
              <div>
                <label className="pt-form-label">Think time (ms) *</label>
                <input type="number" min={0} className="pt-form-control" value={form.thinkTimeMs} onChange={(e) => setForm((p) => ({ ...p, thinkTimeMs: e.target.value }))} />
                {thinkTimeError && <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{thinkTimeError}</div>}
              </div>
              <div>
                <label className="pt-form-label">Débit cible (req/s) <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span></label>
                <input type="number" min={1} className="pt-form-control" placeholder="aucun pacing si vide" value={form.targetRps} onChange={(e) => setForm((p) => ({ ...p, targetRps: e.target.value }))} />
                {targetRpsError && <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{targetRpsError}</div>}
              </div>
              <div>
                <label className="pt-form-label">Mode d'arrêt</label>
                <select className="pt-form-control" value={form.stopMode} onChange={(e) => setForm((p) => ({ ...p, stopMode: e.target.value as BackendStopMode }))}>
                  <option value="AUTO">Automatique (durée/itérations définies ci-dessus)</option>
                  <option value="MANUAL">Manuel (ignore durée/itérations, tourne jusqu'à annulation)</option>
                </select>
                <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>
                  Réellement appliqué par le moteur d'exécution — en mode Manuel, seul le bouton "Annuler" arrête l'exécution.
                </div>
              </div>
            </div>

            <div className="col-12 col-lg-6">
              <label className="pt-form-label">
                <i className="bi bi-braces me-1"></i> Données CSV <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel — remplace le champ "Variables de test" de l'ancien design (voir note ci-dessous)</span>
              </label>

              {/* Import CSV (restauration ciblée) — remplit le même champ
                  réel que la saisie manuelle ci-dessous, jamais un second
                  modèle de données. */}
              <div className="p-3 rounded d-flex align-items-center justify-content-between mb-2" style={{ background: 'var(--pt-bg)', border: '1px dashed var(--pt-border)' }}>
                <div style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
                  {csvFileName ? (
                    <><i className="bi bi-file-earmark-check-fill me-1" style={{ color: 'var(--pt-success)' }}></i>{csvFileName}</>
                  ) : (
                    'Aucun fichier sélectionné'
                  )}
                </div>
                <div className="d-flex gap-2">
                  <label className="pt-btn-outline" style={{ fontSize: '12px', cursor: 'pointer', margin: 0 }}>
                    <i className="bi bi-upload"></i> Choisir un fichier
                    <input type="file" accept=".csv" style={{ display: 'none' }} onChange={(e) => handleCsvFileChange(e.target.files?.[0])} />
                  </label>
                  {csvFileName && (
                    <button type="button" className="pt-btn-outline" style={{ fontSize: '12px' }} onClick={handleRemoveCsvFile}>
                      <i className="bi bi-x-lg"></i> Retirer
                    </button>
                  )}
                </div>
              </div>
              {csvFileError && (
                <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginBottom: '8px' }}>
                  <i className="bi bi-exclamation-circle me-1"></i>{csvFileError}
                </div>
              )}

              <textarea
                className="pt-form-control" rows={8} style={{ fontFamily: 'monospace', fontSize: '12.5px' }}
                placeholder={'username,password\nuser1,pass1\nuser2,pass2'}
                value={form.csvData}
                onChange={(e) => setForm((p) => ({ ...p, csvData: e.target.value }))}
              />
              {csvDataError && <div style={{ color: 'var(--pt-danger)', fontSize: '11.5px', marginTop: '4px' }}>{csvDataError}</div>}
              <div style={{ color: 'var(--pt-text-muted)', fontSize: '11px', marginTop: '6px' }}>
                Première ligne = noms de variables. Chaque ligne suivante alimente <strong>un utilisateur virtuel distinct</strong> (cyclique si moins de lignes que de VUs), utilisables dans les étapes via <code>{'${nomColonne}'}</code> — une vraie distribution par VU, contrairement à l'ancien import CSV qui n'utilisait que sa première ligne.
              </div>
            </div>
          </fieldset>

          <div className="d-flex justify-content-between mt-4 pt-3" style={{ borderTop: '1px solid var(--pt-border)' }}>
            <button className="pt-btn-outline" onClick={() => goToStepperStep(1)}><i className="bi bi-arrow-left me-1"></i>Précédent</button>
            <button className="pt-btn-primary" onClick={() => goToStepperStep(4)} disabled={!isChargeValid}>Suivant : Planification <i className="bi bi-arrow-right ms-1"></i></button>
          </div>
        </div>
      )}

      {/* ÉTAPE 4 : Planification de l'Exécution */}
      {wizardStep === 4 && scenarioId && (
        <div className="pt-card mb-4">
          <div className="d-flex align-items-center gap-2 pb-3 mb-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
            <i className="bi bi-calendar-event-fill" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
            <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>Planification de l'Exécution</h6>
          </div>
          <div className="row g-4">
            <div className="col-12">
              <label className="pt-form-label">Type d'exécution *</label>
              <div className="d-flex flex-column gap-2 mb-3">
                {([
                  { key: 'immediate' as ExecutionType, label: 'Immédiate', desc: "Lancer le scénario dès l'enregistrement" },
                  { key: 'scheduled' as ExecutionType, label: 'Planifiée', desc: 'Exécuter à une date et heure précises' },
                  { key: 'recurring' as ExecutionType, label: 'Récurrente', desc: 'Répéter selon une fréquence définie' },
                ]).map((opt) => (
                  <div
                    key={opt.key}
                    onClick={() => setExecutionType(opt.key)}
                    className="p-3 rounded d-flex align-items-center gap-3"
                    style={{ cursor: 'pointer', border: `1px solid ${executionType === opt.key ? 'var(--pt-primary)' : 'var(--pt-border)'}`, background: executionType === opt.key ? 'var(--pt-primary-light)' : 'var(--pt-bg)' }}
                  >
                    <input type="radio" checked={executionType === opt.key} onChange={() => setExecutionType(opt.key)} />
                    <div>
                      <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)' }}>{opt.label}</div>
                      <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{opt.desc}</div>
                    </div>
                  </div>
                ))}
              </div>

              {executionType === 'immediate' && steps.length === 0 && (
                <div className="pt-alert-banner mb-2" style={{ fontSize: '12px' }}>
                  <i className="bi bi-info-circle-fill"></i> Ce scénario n'a pas encore d'étape — l'enregistrement se fera sans lancement réel.
                </div>
              )}

              {executionType === 'scheduled' && (
                <div className="row g-3">
                  <div className="col-6">
                    <label className="pt-form-label">Date *</label>
                    <input type="date" className="pt-form-control" value={scheduledDate} onChange={(e) => setScheduledDate(e.target.value)} />
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label">Heure</label>
                    <input type="time" className="pt-form-control" value={scheduledTime} onChange={(e) => setScheduledTime(e.target.value)} />
                  </div>
                  {!scheduledDate && <div className="col-12" style={{ color: 'var(--pt-danger)', fontSize: '12px' }}>La date est obligatoire pour une planification ponctuelle.</div>}
                </div>
              )}

              {executionType === 'recurring' && (
                <div>
                  <label className="pt-form-label">Fréquence</label>
                  <select className="pt-form-control" value={recurrence} onChange={(e) => setRecurrence(e.target.value as Recurrence)}>
                    <option>Quotidien</option>
                    <option>Hebdomadaire</option>
                    <option>Mensuel</option>
                  </select>
                  <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)', marginTop: '6px' }}>
                    Déclenchée réellement chaque jour/semaine/mois à 09:00 (fuseau de votre navigateur) via le vrai planificateur serveur — l'ancien design ne proposait pas de choix d'heure pour ce mode.
                  </div>
                </div>
              )}
            </div>
          </div>
          <div className="d-flex justify-content-between mt-4 pt-3" style={{ borderTop: '1px solid var(--pt-border)' }}>
            <button className="pt-btn-outline" onClick={() => goToStepperStep(3)}><i className="bi bi-arrow-left me-1"></i>Précédent</button>
            <button className="pt-btn-primary" onClick={() => goToStepperStep(5)} disabled={!isScheduleValid}>Suivant : Résumé <i className="bi bi-arrow-right ms-1"></i></button>
          </div>
        </div>
      )}

      {/* ÉTAPE 5 : Résumé */}
      {wizardStep === 5 && scenarioId && (
        <>
          <div className="pt-card mb-4">
            <div className="d-flex align-items-center gap-2 pb-3 mb-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
              <i className="bi bi-clipboard-check-fill" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
              <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>Résumé du scénario</h6>
            </div>
            <div className="row g-4">
              <div className="col-12 col-lg-6">
                <h6 style={{ fontSize: '13px', fontWeight: 700 }}>Étapes ({steps.length})</h6>
                <div className="p-3 rounded d-flex flex-column gap-2" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12.5px' }}>
                  {steps.length === 0 ? <span className="text-muted">Aucune étape.</span> : steps.map((s) => (
                    <div key={s.id} className="d-flex align-items-center gap-2">
                      <span style={{ padding: '0.1rem 0.4rem', borderRadius: '4px', fontSize: '10.5px', fontWeight: 700, background: s.method === 'GET' ? 'var(--pt-primary-light)' : 'var(--pt-success-light)', color: s.method === 'GET' ? 'var(--pt-primary)' : 'var(--pt-success)' }}>{s.method}</span>
                      <span style={{ fontWeight: 600 }}>{s.name}</span>
                      <code style={{ marginLeft: 'auto', color: 'var(--pt-primary)' }}>{s.url}</code>
                    </div>
                  ))}
                </div>
                <h6 style={{ fontSize: '13px', fontWeight: 700, marginTop: '16px' }}>Scénario</h6>
                <div className="p-3 rounded d-flex flex-column gap-2" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12.5px' }}>
                  <div className="d-flex justify-content-between"><span className="text-muted">Nom</span><strong>{form.name || '—'}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Application</span><strong>{applicationName || '—'}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Description</span><strong>{form.description || '—'}</strong></div>
                </div>
              </div>
              <div className="col-12 col-lg-6">
                <h6 style={{ fontSize: '13px', fontWeight: 700 }}>Utilisateurs</h6>
                <div className="p-3 rounded d-flex flex-column gap-2" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12.5px' }}>
                  <div className="d-flex justify-content-between"><span className="text-muted">Utilisateurs virtuels</span><strong>{form.virtualUsers}</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Ramp-up</span><strong>{form.rampUpSeconds} s</strong></div>
                  <div className="d-flex justify-content-between"><span className="text-muted">Source de données</span><strong>{form.csvData.trim() ? 'CSV fourni' : 'Aucune'}</strong></div>
                </div>
                <h6 style={{ fontSize: '13px', fontWeight: 700, marginTop: '16px' }}>Planification</h6>
                <div className="p-3 rounded d-flex flex-column gap-2" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', fontSize: '12.5px' }}>
                  <div className="d-flex justify-content-between">
                    <span className="text-muted">Type</span>
                    <strong>{executionType === 'immediate' ? 'Immédiate' : executionType === 'scheduled' ? 'Planifiée' : 'Récurrente'}</strong>
                  </div>
                  {executionType === 'scheduled' && <div className="d-flex justify-content-between"><span className="text-muted">Date/Heure</span><strong>{scheduledDate || '—'} {scheduledTime}</strong></div>}
                  {executionType === 'recurring' && <div className="d-flex justify-content-between"><span className="text-muted">Fréquence</span><strong>{recurrence}</strong></div>}
                </div>
              </div>
            </div>
          </div>
          <div className="d-flex justify-content-between">
            <button className="pt-btn-outline" onClick={() => goToStepperStep(4)}><i className="bi bi-arrow-left me-1"></i>Précédent</button>
            {canWrite && (
              <button className="pt-btn-primary" onClick={handleFinish} disabled={!isChargeValid || !isScheduleValid || saving}>
                {saving ? <><i className="bi bi-arrow-repeat me-2 pt-spin"></i>Enregistrement...</> : <><i className="bi bi-check2 me-2"></i>Enregistrer le scénario</>}
              </button>
            )}
          </div>
        </>
      )}

      {deleteStepConfirm && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1075, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ background: 'var(--pt-card-bg)', borderRadius: 'var(--pt-radius)', padding: '2rem', width: '400px', maxWidth: '95vw', border: '1px solid var(--pt-border)', boxShadow: '0 25px 50px rgba(0,0,0,0.25)', textAlign: 'center' }}>
            <div style={{ width: '56px', height: '56px', borderRadius: '50%', background: 'var(--pt-danger-light)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 1rem' }}>
              <i className="bi bi-trash" style={{ fontSize: '24px', color: 'var(--pt-danger)' }}></i>
            </div>
            <h5 style={{ fontWeight: 700, marginBottom: '8px' }}>Supprimer l'étape ?</h5>
            <p style={{ color: 'var(--pt-text-muted)', fontSize: '14px', marginBottom: '1.5rem' }}>« {deleteStepConfirm.name} » sera définitivement supprimée.</p>
            <div className="d-flex gap-2 justify-content-center">
              <button onClick={() => setDeleteStepConfirm(null)} disabled={deletingStep} style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', color: 'var(--pt-text)' }}>Annuler</button>
              <button onClick={handleDeleteStep} disabled={deletingStep} style={{ background: 'var(--pt-danger)', color: 'white', border: 'none', borderRadius: 'var(--pt-radius-sm)', padding: '8px 20px', cursor: 'pointer', fontSize: '13.5px', fontWeight: 600 }}>
                {deletingStep ? 'Suppression...' : 'Supprimer'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default ScenarioWizard
