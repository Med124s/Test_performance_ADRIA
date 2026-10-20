import { useState } from 'react'
import { BackendMonitoringResponse, BackendMonitoringTestResponse, BackendMonitoringType, BackendNetworkRequest } from '../../types/backendContracts'
import { applicationsBackendApi } from '../../services/api/applicationsBackend'
import { ApiError } from '../../services/api/httpClient'
import {
  MONITORING_SCOPE_TEXT, MONITORING_TYPE_LABELS, MonitoringErrors, MonitoringForm, formatCpu, formatRam,
  monitoringFormToRequest, suggestedMonitoringUrl,
} from './monitoringModel'
import InfoTip from '../InfoTip'
import '../application-auth/applicationAuth.css'

// ============================================================
// Section « Monitoring » (optionnelle) du formulaire d'application :
// type, URL, authentification, intervalle, « Tester le monitoring ».
// ============================================================

const TYPES: { type: BackendMonitoringType; hint: string }[] = [
  { type: 'NONE', hint: 'Aucune mesure CPU / RAM.' },
  { type: 'PROMETHEUS', hint: 'Endpoint Prometheus, ex : /metrics.' },
  { type: 'ACTUATOR', hint: 'URL de base Actuator, ex : /actuator.' },
]

interface Props {
  value: MonitoringForm
  onChange: (value: MonitoringForm) => void
  errors: MonitoringErrors
  showErrors: boolean
  existing?: BackendMonitoringResponse | null
  appUrl: string
  applicationId?: string
  /** Proxy saisi dans le formulaire (utilisé par « Tester le monitoring », sauf « sans proxy »). */
  network?: BackendNetworkRequest
}

export default function MonitoringFields({ value, onChange, errors, showErrors, existing, appUrl, applicationId, network }: Props) {
  const [testing, setTesting] = useState(false)
  const [result, setResult] = useState<BackendMonitoringTestResponse | null>(null)
  const set = (patch: Partial<MonitoringForm>) => {
    setResult(null)
    onChange({ ...value, ...patch })
  }
  const err = (k: keyof MonitoringErrors) => (showErrors || value.url ? errors[k] : undefined)
  const current = TYPES.find((t) => t.type === value.type) ?? TYPES[0]
  const keptSecret = !!existing?.secretConfigured && existing.authType === value.authType

  const chooseType = (type: BackendMonitoringType) => {
    const previousSuggestion = suggestedMonitoringUrl(value.type, appUrl)
    const url = !value.url.trim() || value.url.trim() === previousSuggestion ? suggestedMonitoringUrl(type, appUrl) : value.url
    set({ type, url })
  }

  const runTest = async () => {
    setTesting(true)
    setResult(null)
    try {
      setResult(await applicationsBackendApi.testMonitoring(monitoringFormToRequest(value), applicationId, network))
    } catch (e) {
      setResult({ success: false, message: e instanceof ApiError ? e.message : 'Test du monitoring impossible.', cpuPercent: null, memoryMb: null, memoryMetric: null })
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="aa-root">
      <div className="aa-segmented" role="radiogroup" aria-label="Type de monitoring">
        {TYPES.map((t) => (
          <button key={t.type} type="button" role="radio" aria-checked={value.type === t.type}
            className={value.type === t.type ? 'active' : ''} onClick={() => chooseType(t.type)}>
            {MONITORING_TYPE_LABELS[t.type]}
          </button>
        ))}
      </div>
      <div className="aa-hint">{current.hint}</div>

      {value.type !== 'NONE' && (
        <div className="aa-fields">
          <div className="aa-row">
            <div className="aa-grow">
              <label className="aa-label" htmlFor="mon-url">
                URL du monitoring *
                <InfoTip text={`${MONITORING_SCOPE_TEXT} Si le monitoring ne répond pas, l’exécution continue normalement.`} />
              </label>
              <input id="mon-url" className={`pt-form-control aa-mono ${err('url') ? 'is-invalid' : ''}`} value={value.url}
                placeholder={suggestedMonitoringUrl(value.type, appUrl || 'http://localhost:8888')} onChange={(e) => set({ url: e.target.value })} />
              {err('url') && <div className="aa-error">{err('url')}</div>}
            </div>
            <div style={{ width: '130px' }}>
              <label className="aa-label" htmlFor="mon-interval">Lecture toutes les</label>
              <div className="d-flex align-items-center gap-1">
                <input id="mon-interval" className={`pt-form-control ${err('intervalSeconds') ? 'is-invalid' : ''}`} inputMode="numeric"
                  value={value.intervalSeconds} onChange={(e) => set({ intervalSeconds: e.target.value })} style={{ width: '70px' }} />
                <span className="text-muted" style={{ fontSize: '12.5px' }}>s</span>
              </div>
              {err('intervalSeconds') && <div className="aa-error">{err('intervalSeconds')}</div>}
            </div>
          </div>

          <div className="form-check mb-2">
            <input className="form-check-input" type="checkbox" id="mon-without-proxy" checked={value.withoutProxy}
              onChange={(e) => set({ withoutProxy: e.target.checked })} />
            <label className="form-check-label" htmlFor="mon-without-proxy" style={{ fontSize: '13px' }}>
              Monitoring sans proxy
            </label>
            <InfoTip text="Lecture en direct, sans le proxy de l’application : utile si l’URL de monitoring est interne." />
          </div>

          <label className="aa-label">Authentification du monitoring</label>
          <div className="aa-segmented" role="radiogroup" aria-label="Authentification du monitoring">
            {(['NONE', 'BEARER', 'HEADER'] as const).map((a) => (
              <button key={a} type="button" role="radio" aria-checked={value.authType === a}
                className={value.authType === a ? 'active' : ''} onClick={() => set({ authType: a })}>
                {a === 'NONE' ? 'Aucune' : a === 'BEARER' ? 'Jeton (Bearer)' : 'En-tête'}
              </button>
            ))}
          </div>
          {value.authType === 'HEADER' && (
            <>
              <label className="aa-label" htmlFor="mon-header">Nom de l’en-tête *</label>
              <input id="mon-header" className={`pt-form-control aa-mono ${err('headerName') ? 'is-invalid' : ''}`} placeholder="ex : X-Monitoring-Key"
                value={value.headerName} onChange={(e) => set({ headerName: e.target.value })} />
              {err('headerName') && <div className="aa-error">{err('headerName')}</div>}
            </>
          )}
          {value.authType !== 'NONE' && (
            <>
              <label className="aa-label" htmlFor="mon-secret">{value.authType === 'BEARER' ? 'Jeton *' : 'Valeur de l’en-tête *'}</label>
              <input id="mon-secret" className={`pt-form-control aa-mono ${err('secret') ? 'is-invalid' : ''}`} type="password" autoComplete="off"
                placeholder={keptSecret ? 'Inchangé (laisser vide pour conserver)' : ''} value={value.secret} onChange={(e) => set({ secret: e.target.value })} />
              {err('secret') && <div className="aa-error">{err('secret')}</div>}
            </>
          )}

          <div className="d-flex align-items-center gap-2 flex-wrap mt-1">
            <button type="button" className="pt-btn-outline" onClick={runTest} disabled={testing || Object.keys(errors).length > 0}>
              {testing ? <><span className="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>Lecture…</> : <><i className="bi bi-activity me-1"></i>Tester le monitoring</>}
            </button>
            {result && (
              <span role="status" className={result.success ? 'text-success' : 'text-danger'} style={{ fontSize: '12.5px' }}>
                <i className={`bi ${result.success ? 'bi-check-circle-fill' : 'bi-x-circle-fill'} me-1`}></i>
                {result.success ? <>CPU {formatCpu(result.cpuPercent)} · RAM {formatRam(result.memoryMb)}</> : result.message}
              </span>
            )}
          </div>
          {result?.success && <div className="aa-hint mb-0">{result.message}</div>}
        </div>
      )}
    </div>
  )
}
