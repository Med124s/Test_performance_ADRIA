import React, { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { BackendHttpMethod, BackendStepRequest, BackendStepStatus } from '../types/backendContracts'
import { stepsBackendApi } from '../services/api/stepsBackend'
import { scenariosBackendApi } from '../services/api/scenariosBackend'
import { applicationsBackendApi } from '../services/api/applicationsBackend'
import { ApiError } from '../services/api/httpClient'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { firstError, validateRequired, validateStepUrl, validatePairedFields } from '../utils/validation'

// ============================================================
// Restauration du design ancien à partir des captures fournies — mise en
// page deux colonnes (Paramètres Généraux / Configuration de la Requête),
// stepper global à 5 étapes partagé avec ScenarioWizard.tsx ("Configuration"
// actif ici), choix Headers Tableau/JSON restauré (les deux modes éditent
// la MÊME chaîne réelle "headers" — aucune donnée supplémentaire créée).
//
// Passage produit réel (2026-09-30) — Description, Pacing (attente après
// requête) et Étape active (Step.status) sont désormais RÉELLEMENT
// persistés et honorés par le moteur d'exécution (voir StepRequest.java/
// HttpClientExecutionEngine côté backend — description reste documentaire,
// jamais interprétée ; pacingAfterMs ajoute une vraie pause après l'envoi
// de la requête ; une étape INACTIVE est réellement ignorée à l'exécution).
// "Tester cette étape" reste une vraie requête fetch() envoyée directement
// par le navigateur vers l'application cible (aucun endpoint backend dédié
// au test d'UNE seule étape) — voir "Tester les étapes sélectionnées" dans
// ScenarioWizard.tsx pour l'équivalent serveur-à-serveur (POST /api/steps/
// test-batch).
// ============================================================

const STEPPER_ITEMS = [
  { number: 1, label: 'Étapes' },
  { number: 2, label: 'Configuration' },
  { number: 3, label: 'Utilisateurs' },
  { number: 4, label: 'Planification' },
  { number: 5, label: 'Résumé' },
]

const STEP_METHODS: BackendHttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

type HeaderRow = { id: number; key: string; value: string; enabled: boolean }

const emptyForm = {
  name: '', method: 'GET' as BackendHttpMethod, url: '', order: '1', expectedStatus: '',
  timeoutMs: '', thinkTimeMs: '', followRedirects: '' as '' | 'true' | 'false',
  headers: '', body: '', assertionBodyContains: '', captureVariableName: '', captureJsonPath: '',
  description: '', pacingAfterMs: '',
}

/** Résout l'URL réelle d'une étape pour "Tester cette étape" : absolue
 * telle quelle, ou relative à l'URL de base de l'application cible — même
 * logique que l'ancien resolveStepUrl (services/stepRunner.ts, f04f935). */
function resolveTestUrl(stepUrl: string, baseUrl: string): string {
  if (/^https?:\/\//i.test(stepUrl)) return stepUrl
  const base = baseUrl.replace(/\/+$/, '')
  const path = stepUrl.startsWith('/') ? stepUrl : `/${stepUrl}`
  return `${base}${path}`
}

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 400: return `Données invalides : ${err.message}`
      case 401: return 'Vous devez être connecté (Keycloak) pour effectuer cette action.'
      case 403: return "Action refusée : votre rôle ne dispose pas des permissions nécessaires."
      case 404: return 'Étape ou scénario introuvable (il/elle a peut-être déjà été supprimé(e)).'
      case 409: return err.message
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

/** Parse le texte libre "headers" réel (une paire clé: valeur par ligne) en
 * lignes éditables pour le mode "Tableau" — traduction PUREMENT visuelle,
 * la donnée sauvegardée reste toujours la même chaîne texte. */
function parseHeadersToRows(raw: string): HeaderRow[] {
  return raw.split('\n').filter((l) => l.trim()).map((line, idx) => {
    const sep = line.indexOf(':')
    return {
      id: idx + 1,
      key: sep >= 0 ? line.slice(0, sep).trim() : line.trim(),
      value: sep >= 0 ? line.slice(sep + 1).trim() : '',
      enabled: true,
    }
  })
}

function rowsToHeadersText(rows: HeaderRow[]): string {
  return rows.filter((r) => r.enabled && r.key.trim()).map((r) => `${r.key.trim()}: ${r.value.trim()}`).join('\n')
}

function ScenarioStepEditor() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const scenarioId = searchParams.get('scenario')
  const stepId = searchParams.get('step')
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const isKeycloak = authProvider === 'keycloak'
  const canWrite = isKeycloak && (rawRoles.includes('ROLE_SUPER_ADMIN') || rawRoles.includes('ROLE_PERFORMANCE_ENGINEER'))

  const [form, setForm] = useState(emptyForm)
  const [touched, setTouched] = useState<{ name?: boolean; url?: boolean; order?: boolean; expectedStatus?: boolean }>({})
  const [loading, setLoading] = useState(!!stepId)
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [applicationLabel, setApplicationLabel] = useState<string>('')
  const [applicationBaseUrl, setApplicationBaseUrl] = useState<string>('')
  const [headersFormatMode, setHeadersFormatMode] = useState<'table' | 'json'>('table')
  const [headerRows, setHeaderRows] = useState<HeaderRow[]>([])
  // Valeur RÉELLE de Step.status — lecture seule (voir commentaire d'état
  // plus haut : non modifiable via BackendStepRequest aujourd'hui). ACTIVE
  // par défaut pour une nouvelle étape (défaut réel côté backend).
  const [stepStatus, setStepStatus] = useState<BackendStepStatus>('ACTIVE')
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; httpStatus?: number; responseTimeMs?: number; message: string } | null>(null)

  useEffect(() => {
    if (!scenarioId) return
    scenariosBackendApi.getById(scenarioId)
      .then((s) => applicationsBackendApi.getById(s.applicationId))
      .then((app) => { setApplicationLabel(`${app.name} (${app.url})`); setApplicationBaseUrl(app.url) })
      .catch(() => {})

    if (stepId) {
      setLoading(true)
      stepsBackendApi.getById(stepId)
        .then((step) => {
          const headersText = step.headers ?? ''
          setForm((p) => ({
            ...p,
            name: step.name,
            method: step.method,
            url: step.url,
            order: String(step.order),
            expectedStatus: step.expectedStatus != null ? String(step.expectedStatus) : '',
            timeoutMs: step.timeoutSeconds != null ? String(step.timeoutSeconds * 1000) : '',
            thinkTimeMs: step.thinkTimeMs != null ? String(step.thinkTimeMs) : '',
            followRedirects: step.followRedirects === true ? 'true' : step.followRedirects === false ? 'false' : '',
            headers: headersText,
            body: step.body ?? '',
            assertionBodyContains: step.assertionBodyContains ?? '',
            captureVariableName: step.captureVariableName ?? '',
            captureJsonPath: step.captureJsonPath ?? '',
            description: step.description ?? '',
            pacingAfterMs: step.pacingAfterMs != null ? String(step.pacingAfterMs) : '',
          }))
          setHeaderRows(parseHeadersToRows(headersText))
          setStepStatus(step.status)
        })
        .catch((err) => setActionError(describeApiError(err, "Impossible de charger l'étape.")))
        .finally(() => setLoading(false))
    } else {
      stepsBackendApi.getByScenario(scenarioId)
        .then((steps) => {
          const maxOrder = steps.reduce((max, s) => Math.max(max, s.order), 0)
          setForm((p) => ({ ...p, order: String(maxOrder + 1) }))
        })
        .catch(() => {})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scenarioId, stepId])

  // Les deux modes d'édition (Tableau / JSON brut) restent synchronisés sur
  // le même champ réel `form.headers` — changer de mode ne perd jamais la
  // donnée déjà saisie dans l'autre mode.
  const switchToTableMode = () => {
    setHeaderRows(parseHeadersToRows(form.headers))
    setHeadersFormatMode('table')
  }
  const switchToJsonMode = () => {
    setForm((p) => ({ ...p, headers: rowsToHeadersText(headerRows) }))
    setHeadersFormatMode('json')
  }
  const updateHeaderRow = (id: number, patch: Partial<HeaderRow>) => {
    const updated = headerRows.map((r) => (r.id === id ? { ...r, ...patch } : r))
    setHeaderRows(updated)
    setForm((p) => ({ ...p, headers: rowsToHeadersText(updated) }))
  }
  const addHeaderRow = () => {
    const updated = [...headerRows, { id: Date.now(), key: '', value: '', enabled: true }]
    setHeaderRows(updated)
  }
  const removeHeaderRow = (id: number) => {
    const updated = headerRows.filter((r) => r.id !== id)
    setHeaderRows(updated)
    setForm((p) => ({ ...p, headers: rowsToHeadersText(updated) }))
  }

  const nameError = validateRequired(form.name, "Le nom de l'étape")
  const urlError = firstError(validateRequired(form.url, 'La ressource'), validateStepUrl(form.url))
  const orderNum = Number(form.order)
  const orderError = !form.order.trim()
    ? "L'ordre est obligatoire."
    : !Number.isInteger(orderNum) || orderNum <= 0
    ? "L'ordre doit être un entier strictement positif."
    : null
  const expectedStatusNum = form.expectedStatus.trim() ? Number(form.expectedStatus) : null
  const expectedStatusError =
    expectedStatusNum !== null && (!Number.isInteger(expectedStatusNum) || expectedStatusNum < 100 || expectedStatusNum > 599)
      ? 'Le code de statut attendu doit être compris entre 100 et 599.'
      : null
  const timeoutMsNum = form.timeoutMs.trim() ? Number(form.timeoutMs) : null
  const timeoutError =
    timeoutMsNum !== null && (!Number.isInteger(timeoutMsNum) || timeoutMsNum < 1000)
      ? "Doit être d'au moins 1000 ms (1 seconde) — le backend stocke une précision à la seconde."
      : null
  const thinkTimeNum = form.thinkTimeMs.trim() ? Number(form.thinkTimeMs) : null
  const thinkTimeError =
    thinkTimeNum !== null && (!Number.isInteger(thinkTimeNum) || thinkTimeNum < 0) ? 'Doit être un entier positif ou nul.' : null
  const pacingAfterMsNum = form.pacingAfterMs.trim() ? Number(form.pacingAfterMs) : null
  const pacingAfterMsError =
    pacingAfterMsNum !== null && (!Number.isInteger(pacingAfterMsNum) || pacingAfterMsNum < 0) ? 'Doit être un entier positif ou nul.' : null
  const descriptionError = form.description.length > 1000 ? 'La description ne doit pas dépasser 1000 caractères.' : null
  const assertionError = form.assertionBodyContains.length > 500 ? "L'assertion ne doit pas dépasser 500 caractères." : null
  const captureNameError = form.captureVariableName.length > 255 ? 'Le nom de variable ne doit pas dépasser 255 caractères.' : null
  const captureJsonPathError = form.captureJsonPath.length > 500 ? 'Le chemin de capture ne doit pas dépasser 500 caractères.' : null
  const captureIncompleteError = validatePairedFields(form.captureVariableName, form.captureJsonPath, 'Le nom de variable et le chemin de capture')

  const isValid = !nameError && !urlError && !orderError && !expectedStatusError && !timeoutError && !thinkTimeError &&
    !assertionError && !captureNameError && !captureJsonPathError && !captureIncompleteError &&
    !pacingAfterMsError && !descriptionError

  const stepperItems = useMemo(() => STEPPER_ITEMS.map((s) => ({ ...s, active: s.number === 2, completed: s.number === 1 })), [])

  const handleSave = async () => {
    if (!canWrite || !scenarioId) return
    setTouched({ name: true, url: true, order: true, expectedStatus: true })
    if (!isValid || saving) return
    setSaving(true)
    setActionError(null)
    const payload: BackendStepRequest = {
      scenarioId,
      name: form.name.trim(),
      method: form.method,
      url: form.url.trim(),
      headers: form.headers.trim() || null,
      body: form.body.trim() || null,
      order: orderNum,
      expectedStatus: expectedStatusNum,
      timeoutSeconds: timeoutMsNum !== null ? Math.round(timeoutMsNum / 1000) : null,
      thinkTimeMs: thinkTimeNum,
      followRedirects: form.followRedirects === '' ? null : form.followRedirects === 'true',
      assertionBodyContains: form.assertionBodyContains.trim() || null,
      captureVariableName: form.captureVariableName.trim() || null,
      captureJsonPath: form.captureJsonPath.trim() || null,
      description: form.description.trim() || null,
      pacingAfterMs: pacingAfterMsNum,
      status: stepStatus,
    }
    try {
      if (stepId) {
        await stepsBackendApi.update(stepId, payload)
        showToast('Étape modifiée avec succès.', 'success')
      } else {
        await stepsBackendApi.create(payload)
        showToast('Étape ajoutée avec succès.', 'success')
      }
      navigate(`/scenarios/create?edit=${scenarioId}&wizardStep=1`)
    } catch (err) {
      const message = describeApiError(err, "Erreur lors de l'enregistrement de l'étape.")
      setActionError(message)
      showToast(message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  // "Tester cette étape" — VRAIE requête fetch() envoyée directement par le
  // navigateur vers l'application cible (jamais via le backend Spring Boot,
  // qui n'expose aucun endpoint de test — voir commentaire d'état plus
  // haut). Résultat réel (statut HTTP, temps de réponse, erreur réseau) —
  // jamais simulé. Peut échouer pour une vraie raison CORS si l'application
  // cible n'autorise pas les requêtes cross-origin depuis ce frontend :
  // limitation réelle de cette approche navigateur, pas un bug.
  const handleTestStep = async () => {
    if (!form.url.trim()) {
      showToast("Veuillez renseigner une URL pour tester l'étape.", 'danger')
      return
    }
    if (!applicationBaseUrl) {
      showToast('Application cible introuvable — impossible de tester cette étape.', 'danger')
      return
    }
    setTesting(true)
    setTestResult(null)
    const targetUrl = resolveTestUrl(form.url.trim(), applicationBaseUrl)
    const headers: Record<string, string> = {}
    parseHeadersToRows(form.headers).forEach((h) => {
      if (h.enabled && h.key.trim()) headers[h.key.trim()] = h.value
    })
    const methodsWithoutBody = ['GET', 'DELETE', 'HEAD']
    const hasBody = !methodsWithoutBody.includes(form.method) && !!form.body.trim()
    if (hasBody && !Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) {
      headers['Content-Type'] = 'application/json'
    }
    const timeoutMs = timeoutMsNum ?? 10000
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    const startedAt = performance.now()
    try {
      const res = await fetch(targetUrl, {
        method: form.method,
        headers,
        body: hasBody ? form.body : undefined,
        redirect: form.followRedirects === 'false' ? 'manual' : 'follow',
        signal: controller.signal,
      })
      clearTimeout(timer)
      const responseTimeMs = Math.round(performance.now() - startedAt)
      const bodyText = await res.text().catch(() => '')
      const assertionOk = form.assertionBodyContains.trim() ? bodyText.includes(form.assertionBodyContains.trim()) : true
      const statusOk = expectedStatusNum !== null ? res.status === expectedStatusNum : res.status >= 200 && res.status < 400
      const success = statusOk && assertionOk
      const message = success
        ? 'Requête envoyée avec succès.'
        : !statusOk
        ? `Statut HTTP inattendu (obtenu ${res.status}${expectedStatusNum !== null ? `, attendu ${expectedStatusNum}` : ''}).`
        : `L'assertion "contient ${form.assertionBodyContains}" a échoué.`
      setTestResult({ success, httpStatus: res.status, responseTimeMs, message })
      showToast(success ? "Test de l'étape terminé avec succès !" : "Le test de l'étape a échoué.", success ? 'success' : 'danger')
    } catch (err) {
      clearTimeout(timer)
      const responseTimeMs = Math.round(performance.now() - startedAt)
      const message = controller.signal.aborted
        ? `Délai dépassé (${timeoutMs} ms).`
        : err instanceof Error
        ? `${err.message} (souvent un blocage CORS si l'application cible n'autorise pas ce frontend)`
        : 'Erreur réseau.'
      setTestResult({ success: false, responseTimeMs, message })
      showToast("Le test de l'étape a échoué.", 'danger')
    } finally {
      setTesting(false)
    }
  }

  if (!scenarioId) {
    return (
      <div className="pt-content">
        <div className="pt-alert-banner danger">
          <i className="bi bi-exclamation-triangle-fill"></i>
          Aucun scénario associé — retournez à la liste des scénarios et modifiez un scénario pour lui ajouter des étapes.
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
            onClick={() => navigate(`/scenarios/create?edit=${scenarioId}&wizardStep=1`)}
            style={{ textDecoration: 'none', color: 'var(--pt-primary)', fontSize: '13px', fontWeight: 500, display: 'inline-flex', alignItems: 'center', gap: '6px', marginBottom: '8px', border: 'none', background: 'none' }}
          >
            <i className="bi bi-arrow-left"></i> Retour au scénario
          </button>
          <div className="page-title">
            <h1>Créer / Modifier une étape de scénario</h1>
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

      {/* Stepper global — partagé visuellement avec ScenarioWizard.tsx */}
      <div className="pt-card mb-4" style={{ padding: '1.25rem 1.5rem' }}>
        <div className="d-flex align-items-center justify-content-between" style={{ position: 'relative' }}>
          {stepperItems.map((step, index) => {
            const isLast = index === stepperItems.length - 1
            return (
              <React.Fragment key={step.number}>
                <div className="d-flex align-items-center gap-2" style={{ zIndex: 2 }}>
                  <div
                    style={{
                      width: '36px', height: '36px', borderRadius: '50%',
                      background: step.active ? 'var(--pt-primary)' : step.completed ? 'var(--pt-success)' : 'var(--pt-card-bg)',
                      border: step.active || step.completed ? 'none' : '2px solid var(--pt-border)',
                      color: step.active || step.completed ? 'white' : 'var(--pt-text-muted)',
                      display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700, fontSize: '14px',
                    }}
                  >
                    {step.completed ? <i className="bi bi-check-lg"></i> : step.number}
                  </div>
                  <div>
                    <div style={{ fontSize: '13.5px', fontWeight: step.active ? 700 : 500, color: step.active ? 'var(--pt-primary)' : step.completed ? 'var(--pt-text)' : 'var(--pt-text-muted)' }}>{step.label}</div>
                    <div style={{ fontSize: '11px', color: 'var(--pt-text-muted)' }}>Étape {step.number} sur 5</div>
                  </div>
                </div>
                {!isLast && <div style={{ flex: 1, height: '2px', background: step.completed ? 'var(--pt-success)' : 'var(--pt-border)', margin: '0 1rem', alignSelf: 'center' }}></div>}
              </React.Fragment>
            )
          })}
        </div>
      </div>

      {loading ? (
        <div className="pt-empty-state">
          <i className="bi bi-arrow-repeat pt-spin" style={{ fontSize: '28px', color: 'var(--pt-primary)' }}></i>
          <p>Chargement de l'étape...</p>
        </div>
      ) : (
        <fieldset disabled={!canWrite} style={{ border: 'none', padding: 0, margin: 0 }}>
          <div className="row g-4 mb-4">
            {/* GAUCHE : Paramètres Généraux */}
            <div className="col-12 col-lg-5">
              <div className="pt-card" style={{ height: '100%' }}>
                <div className="pb-3 mb-3 d-flex align-items-center justify-content-between" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                  <div className="d-flex align-items-center gap-2">
                    <i className="bi bi-sliders" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
                    <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>Paramètres Généraux</h6>
                  </div>
                  <span className="pt-pill info">Configuration de l'Étape</span>
                </div>
                <div className="d-flex flex-column gap-3">
                  <div>
                    <label className="pt-form-label">Méthode HTTP *</label>
                    <select className="pt-form-control" value={form.method} onChange={(e) => setForm((p) => ({ ...p, method: e.target.value as BackendHttpMethod }))}>
                      {STEP_METHODS.map((m) => <option key={m} value={m}>{m}</option>)}
                    </select>
                  </div>
                  <div>
                    <label className="pt-form-label">Nom de l'étape *</label>
                    <input
                      type="text" className="pt-form-control" value={form.name}
                      onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))}
                      onBlur={() => setTouched((t) => ({ ...t, name: true }))}
                      placeholder="ex: Transfert national"
                    />
                    {touched.name && nameError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{nameError}</div>}
                  </div>
                  <div>
                    <label className="pt-form-label">URL Cible / Endpoint *</label>
                    <input
                      type="text" className="pt-form-control" style={{ fontFamily: 'monospace', fontSize: '13px' }} value={form.url}
                      onChange={(e) => setForm((p) => ({ ...p, url: e.target.value }))}
                      onBlur={() => setTouched((t) => ({ ...t, url: true }))}
                      placeholder="/api/transfers/national"
                    />
                    {touched.url && urlError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{urlError}</div>}
                  </div>
                  <div>
                    <label className="pt-form-label">Description</label>
                    <textarea
                      className="pt-form-control" rows={2} value={form.description}
                      onChange={(e) => setForm((p) => ({ ...p, description: e.target.value }))}
                      placeholder="Expliquez l'objectif de cette étape..."
                    />
                    {descriptionError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{descriptionError}</div>}
                  </div>

                  <div className="row g-2">
                    <div className="col-6">
                      <label className="pt-form-label">Ordre *</label>
                      <input
                        type="number" min={1} className="pt-form-control" value={form.order}
                        onChange={(e) => setForm((p) => ({ ...p, order: e.target.value }))}
                        onBlur={() => setTouched((t) => ({ ...t, order: true }))}
                      />
                      {touched.order && orderError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{orderError}</div>}
                    </div>
                    <div className="col-6">
                      <label className="pt-form-label">Code de statut attendu</label>
                      <input
                        type="number" className="pt-form-control" placeholder="ex: 200" value={form.expectedStatus}
                        onChange={(e) => setForm((p) => ({ ...p, expectedStatus: e.target.value }))}
                        onBlur={() => setTouched((t) => ({ ...t, expectedStatus: true }))}
                      />
                      {touched.expectedStatus && expectedStatusError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{expectedStatusError}</div>}
                    </div>
                  </div>

                  <div className="p-3 rounded" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)' }}>
                    <div className="d-flex align-items-center justify-content-between">
                      <div>
                        <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)' }}>Suivre les redirections (Follow Redirects)</div>
                        <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>Redirection automatique HTTP 301/302</div>
                      </div>
                      <select className="pt-form-control" style={{ width: 'auto' }} value={form.followRedirects} onChange={(e) => setForm((p) => ({ ...p, followRedirects: e.target.value as '' | 'true' | 'false' }))}>
                        <option value="">Global</option>
                        <option value="true">Oui</option>
                        <option value="false">Non</option>
                      </select>
                    </div>
                  </div>

                  <div className="p-3 rounded" style={{ background: 'var(--pt-bg)', border: '1px solid var(--pt-border)' }}>
                    <div className="d-flex align-items-center justify-content-between">
                      <div>
                        <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--pt-text)' }}>Étape active</div>
                        <div style={{ fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>Inclure cette étape lors de l'exécution</div>
                      </div>
                      <div className="form-check form-switch">
                        <input
                          className="form-check-input" type="checkbox" role="switch"
                          checked={stepStatus === 'ACTIVE'}
                          onChange={(e) => setStepStatus(e.target.checked ? 'ACTIVE' : 'INACTIVE')}
                        />
                      </div>
                    </div>
                    <div style={{ fontSize: '10.5px', color: 'var(--pt-text-muted)', marginTop: '4px' }}>
                      Une étape désactivée est réellement ignorée par le moteur d'exécution (jamais jouée, ni comptée).
                    </div>
                  </div>

                  <div>
                    <label className="pt-form-label">Timeout (en millisecondes)</label>
                    <div className="d-flex align-items-center gap-2">
                      <input
                        type="number" min={1000} step={1000} className="pt-form-control" placeholder="global si vide" value={form.timeoutMs}
                        onChange={(e) => setForm((p) => ({ ...p, timeoutMs: e.target.value }))}
                      />
                      <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', width: '40px' }}>ms</span>
                    </div>
                    {timeoutError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{timeoutError}</div>}
                  </div>

                  <div className="row g-2">
                    <div className="col-6">
                      <label className="pt-form-label">Pause avant étape (Think time, ms)</label>
                      <input
                        type="number" min={0} className="pt-form-control" placeholder="global si vide" value={form.thinkTimeMs}
                        onChange={(e) => setForm((p) => ({ ...p, thinkTimeMs: e.target.value }))}
                      />
                      {thinkTimeError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{thinkTimeError}</div>}
                    </div>
                    <div className="col-6">
                      <label className="pt-form-label">Attente après requête (Pacing, ms)</label>
                      <input
                        type="number" min={0} className="pt-form-control" value={form.pacingAfterMs}
                        onChange={(e) => setForm((p) => ({ ...p, pacingAfterMs: e.target.value }))}
                      />
                      {pacingAfterMsError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{pacingAfterMsError}</div>}
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* DROITE : Configuration de la Requête */}
            <div className="col-12 col-lg-7">
              <div className="pt-card d-flex flex-column gap-4">
                <div className="pb-3 d-flex justify-content-between align-items-center flex-wrap gap-2" style={{ borderBottom: '1px solid var(--pt-border)' }}>
                  <div className="d-flex align-items-center gap-2">
                    <i className="bi bi-hdd-network" style={{ color: 'var(--pt-primary)', fontSize: '18px' }}></i>
                    <h6 style={{ fontSize: '15px', fontWeight: 600, margin: 0 }}>Configuration de la Requête</h6>
                  </div>
                  {applicationLabel && (
                    <div className="d-flex align-items-center gap-2">
                      <span style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', fontWeight: 500 }}>Application cible:</span>
                      <span className="pt-pill neutral" style={{ fontSize: '12.5px', fontWeight: 600 }}><i className="bi bi-globe2 me-1"></i>{applicationLabel}</span>
                    </div>
                  )}
                </div>

                {/* Headers — choix Tableau/JSON restauré (même donnée réelle) */}
                <div>
                  <div className="d-flex justify-content-between align-items-center mb-2">
                    <label className="pt-form-label mb-0" style={{ fontSize: '13.5px' }}><i className="bi bi-list-nested me-1"></i> Headers HTTP ({headerRows.filter((r) => r.enabled && r.key.trim()).length})</label>
                    <div className="d-flex align-items-center gap-2">
                      <div className="btn-group btn-group-sm" role="group">
                        <button type="button" className={`btn btn-sm ${headersFormatMode === 'table' ? 'btn-primary' : 'btn-outline-secondary'}`} style={{ fontSize: '11.5px', padding: '0.2rem 0.5rem' }} onClick={switchToTableMode}>Tableau</button>
                        <button type="button" className={`btn btn-sm ${headersFormatMode === 'json' ? 'btn-primary' : 'btn-outline-secondary'}`} style={{ fontSize: '11.5px', padding: '0.2rem 0.5rem' }} onClick={switchToJsonMode}>JSON</button>
                      </div>
                      {headersFormatMode === 'table' && (
                        <button type="button" className="pt-btn-outline" style={{ padding: '0.25rem 0.6rem', fontSize: '12px' }} onClick={addHeaderRow}>
                          <i className="bi bi-plus-lg"></i> Ajouter
                        </button>
                      )}
                    </div>
                  </div>

                  {headersFormatMode === 'table' ? (
                    <div style={{ border: '1px solid var(--pt-border)', borderRadius: 'var(--pt-radius-sm)', overflow: 'hidden' }}>
                      <table className="pt-table">
                        <thead>
                          <tr style={{ background: 'var(--pt-bg)' }}>
                            <th style={{ width: '30px' }}></th>
                            <th>Clé (Header Name)</th>
                            <th>Valeur (Header Value)</th>
                            <th style={{ width: '40px', textAlign: 'right' }}></th>
                          </tr>
                        </thead>
                        <tbody>
                          {headerRows.map((hdr) => (
                            <tr key={hdr.id}>
                              <td><input type="checkbox" checked={hdr.enabled} onChange={() => updateHeaderRow(hdr.id, { enabled: !hdr.enabled })} /></td>
                              <td><input type="text" className="pt-form-control" style={{ fontSize: '12.5px', padding: '0.25rem 0.5rem', fontFamily: 'monospace' }} value={hdr.key} onChange={(e) => updateHeaderRow(hdr.id, { key: e.target.value })} placeholder="Clé" /></td>
                              <td><input type="text" className="pt-form-control" style={{ fontSize: '12.5px', padding: '0.25rem 0.5rem', fontFamily: 'monospace' }} value={hdr.value} onChange={(e) => updateHeaderRow(hdr.id, { value: e.target.value })} placeholder="Valeur" /></td>
                              <td style={{ textAlign: 'right' }}><button type="button" className="topbar-icon" style={{ width: '28px', height: '28px' }} onClick={() => removeHeaderRow(hdr.id)}><i className="bi bi-x-lg" style={{ fontSize: '12px', color: 'var(--pt-danger)' }}></i></button></td>
                            </tr>
                          ))}
                          {headerRows.length === 0 && (
                            <tr><td colSpan={4} style={{ textAlign: 'center', color: 'var(--pt-text-muted)', fontSize: '12px', padding: '10px' }}>Aucun header — cliquez "Ajouter".</td></tr>
                          )}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <textarea
                      className="pt-form-control" rows={4}
                      style={{ fontFamily: 'Consolas, Monaco, monospace', fontSize: '12.5px', background: '#0f172a', color: '#38bdf8', lineHeight: '1.5', whiteSpace: 'pre', borderRadius: '6px', border: '1px solid var(--pt-border)', padding: '12px' }}
                      value={form.headers}
                      onChange={(e) => setForm((p) => ({ ...p, headers: e.target.value }))}
                      placeholder={'Content-Type: application/json\nAuthorization: Bearer ...'}
                      spellCheck={false}
                    />
                  )}
                </div>

                {/* Body — toujours le même champ réel, présentation "éditeur" restaurée */}
                <div>
                  <div className="d-flex justify-content-between align-items-center mb-2">
                    <label className="pt-form-label mb-0" style={{ fontSize: '13.5px' }}><i className="bi bi-code-square me-1"></i> Body Requête</label>
                    <span className="pt-pill neutral" style={{ fontSize: '11px', fontFamily: 'monospace' }}>application/json</span>
                  </div>
                  <div style={{ borderRadius: '6px', overflow: 'hidden', border: '1px solid var(--pt-border)', background: '#0f172a' }}>
                    <div style={{ background: '#1e293b', color: '#94a3b8', padding: '4px 12px', fontSize: '11px', fontFamily: 'monospace', borderBottom: '1px solid #334155', display: 'flex', justifyContent: 'space-between' }}>
                      <span>JSON Payload</span>
                      <span>Syntax Monospace</span>
                    </div>
                    <textarea
                      className="pt-form-control" rows={6}
                      style={{ fontFamily: 'Consolas, Monaco, "Courier New", monospace', fontSize: '12.5px', background: '#0f172a', color: '#f8fafc', lineHeight: '1.5', whiteSpace: 'pre', border: 'none', borderRadius: 0, padding: '12px', boxShadow: 'none' }}
                      value={form.body}
                      onChange={(e) => setForm((p) => ({ ...p, body: e.target.value }))}
                      spellCheck={false}
                    />
                  </div>
                </div>

                <div>
                  <div className="d-flex justify-content-between align-items-center mb-2">
                    <label className="pt-form-label mb-0" style={{ fontSize: '13.5px' }}><i className="bi bi-shield-check me-1"></i> Assertions & Règles de Validation</label>
                  </div>
                  <label className="pt-form-label">
                    Assertion — la réponse doit contenir <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel, seule assertion réellement supportée par le moteur actuel</span>
                  </label>
                  <input type="text" className="pt-form-control" placeholder={'ex: "status":"ok"'} value={form.assertionBodyContains} onChange={(e) => setForm((p) => ({ ...p, assertionBodyContains: e.target.value }))} />
                  {assertionError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{assertionError}</div>}
                </div>

                <div className="row g-3">
                  <div className="col-6">
                    <label className="pt-form-label">Capturer une variable — nom <span style={{ color: 'var(--pt-text-muted)', fontWeight: 400 }}>optionnel</span></label>
                    <input type="text" className="pt-form-control" placeholder="ex: token" value={form.captureVariableName} onChange={(e) => setForm((p) => ({ ...p, captureVariableName: e.target.value }))} />
                    {captureNameError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{captureNameError}</div>}
                  </div>
                  <div className="col-6">
                    <label className="pt-form-label">Capturer une variable — chemin JSON</label>
                    <input type="text" className="pt-form-control" placeholder={'ex: $.token ou data.token'} value={form.captureJsonPath} onChange={(e) => setForm((p) => ({ ...p, captureJsonPath: e.target.value }))} />
                    {captureJsonPathError && <div style={{ color: 'var(--pt-danger)', fontSize: '12px', marginTop: '4px' }}>{captureJsonPathError}</div>}
                  </div>
                  {captureIncompleteError && <div className="col-12" style={{ color: 'var(--pt-danger)', fontSize: '11.5px' }}>{captureIncompleteError}</div>}
                </div>
              </div>
            </div>
          </div>

          {testResult && (
            <div className={`pt-alert-banner ${testResult.success ? 'success' : 'danger'} mb-3`}>
              <i className={`bi ${testResult.success ? 'bi-check-circle-fill' : 'bi-exclamation-triangle-fill'}`}></i>
              {testResult.message}
              {testResult.httpStatus != null && ` — HTTP ${testResult.httpStatus}`}
              {testResult.responseTimeMs != null && ` — ${testResult.responseTimeMs} ms`}
            </div>
          )}

          <div className="d-flex gap-2 justify-content-between flex-wrap">
            <button className="pt-btn-outline" onClick={() => navigate(`/scenarios/create?edit=${scenarioId}&wizardStep=1`)} disabled={saving}>
              <i className="bi bi-arrow-left me-1"></i> Retour au scénario
            </button>
            <div className="d-flex gap-2">
              <button className="pt-btn-outline" onClick={handleTestStep} disabled={testing || !form.url.trim()}>
                <i className={`bi ${testing ? 'bi-arrow-repeat pt-spin' : 'bi-play-circle'} text-success me-1`}></i>
                {testing ? 'Test en cours...' : 'Tester cette étape'}
              </button>
              {canWrite && (
                <button className="pt-btn-primary" onClick={handleSave} disabled={!isValid || saving}>
                  <i className="bi bi-check-lg me-1"></i>
                  {saving ? 'Enregistrement...' : stepId ? 'Enregistrer les modifications' : "Ajouter l'étape"}
                </button>
              )}
            </div>
          </div>
        </fieldset>
      )}
    </div>
  )
}

export default ScenarioStepEditor
