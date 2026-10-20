import {
  BackendMonitoringAuthType,
  BackendMonitoringRequest,
  BackendMonitoringResponse,
  BackendMonitoringType,
} from '../../types/backendContracts'

// ============================================================
// Section « Monitoring » d'une application : formulaire ↔ requête,
// validation, libellés. Fonctions pures, testées.
// ============================================================

export interface MonitoringForm {
  type: BackendMonitoringType
  url: string
  authType: BackendMonitoringAuthType
  headerName: string
  secret: string
  intervalSeconds: string
  /** Lecture en direct, sans le proxy de l'application. */
  withoutProxy: boolean
}

export const EMPTY_MONITORING_FORM: MonitoringForm = {
  type: 'NONE', url: '', authType: 'NONE', headerName: '', secret: '', intervalSeconds: '5', withoutProxy: false,
}

export const MONITORING_TYPE_LABELS: Record<BackendMonitoringType, string> = {
  NONE: 'Aucun',
  PROMETHEUS: 'Prometheus',
  ACTUATOR: 'Spring Boot Actuator',
}

/** URL proposée selon le type, à partir de l'URL de l'application. */
export function suggestedMonitoringUrl(type: BackendMonitoringType, appUrl: string): string {
  const base = appUrl.trim().replace(/\/+$/, '')
  if (!base || type === 'NONE') return ''
  return type === 'PROMETHEUS' ? `${base}/metrics` : `${base}/actuator`
}

export function monitoringFormFromResponse(m: BackendMonitoringResponse | undefined | null): MonitoringForm {
  if (!m) return EMPTY_MONITORING_FORM
  return {
    type: m.type, url: m.url ?? '', authType: m.authType, headerName: m.headerName ?? '', secret: '',
    intervalSeconds: String(m.intervalSeconds || 5), withoutProxy: !!m.withoutProxy,
  }
}

export function monitoringFormToRequest(f: MonitoringForm): BackendMonitoringRequest {
  return {
    type: f.type,
    url: f.url.trim() || null,
    authType: f.type === 'NONE' ? 'NONE' : f.authType,
    headerName: f.authType === 'HEADER' ? f.headerName.trim() || null : null,
    secret: f.secret.trim() || null,
    intervalSeconds: Number(f.intervalSeconds) || 5,
    withoutProxy: f.withoutProxy,
  }
}

export type MonitoringErrors = Partial<Record<'url' | 'headerName' | 'secret' | 'intervalSeconds', string>>

/** Mêmes règles que le serveur ; secret vide accepté s'il est déjà enregistré pour la même authentification. */
export function validateMonitoringForm(f: MonitoringForm, existing?: BackendMonitoringResponse | null): MonitoringErrors {
  const errors: MonitoringErrors = {}
  if (f.type === 'NONE') return errors
  if (!f.url.trim()) errors.url = 'Indiquez l’URL du monitoring.'
  else if (!/^https?:\/\/[^\s/]+/i.test(f.url.trim())) errors.url = 'URL invalide : elle doit commencer par http:// ou https://.'
  const interval = Number(f.intervalSeconds)
  if (!Number.isInteger(interval) || interval < 1 || interval > 60) errors.intervalSeconds = 'Entre 1 et 60 secondes.'
  if (f.authType === 'HEADER' && !f.headerName.trim()) errors.headerName = 'Indiquez le nom de l’en-tête.'
  const keepsSecret = !!existing?.secretConfigured && existing.authType === f.authType
  if (f.authType !== 'NONE' && !f.secret.trim() && !keepsSecret) {
    errors.secret = f.authType === 'BEARER' ? 'Indiquez le jeton.' : 'Indiquez la valeur de l’en-tête.'
  }
  return errors
}

/** « 12,5 % » (CPU en % d'un cœur) / « 148 Mo ». */
export const formatCpu = (v: number | null | undefined) => (v == null ? '—' : `${v.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} %`)
export const formatRam = (v: number | null | undefined) => (v == null ? '—' : `${Math.round(v).toLocaleString('fr-FR')} Mo`)

/** Explication commune (formulaire, suivi en direct, analyse dans le temps). */
export const MONITORING_SCOPE_TEXT =
  'CPU et RAM du processus de l’application testée (lus sur son endpoint de monitoring) : ni la machine entière, ni LoadPilot. '
  + 'CPU en % d’un cœur : 100 % = un cœur entièrement occupé (peut dépasser 100 % sur plusieurs cœurs).'

/**
 * Tracé SVG de la mini-courbe (sans axes) : un segment par suite de points non nuls (un trou
 * interrompt la courbe). Échelle verticale du minimum au maximum des valeurs.
 */
export function sparklinePaths(values: (number | null)[], width: number, height: number, pad = 2): string[] {
  const nums = values.filter((v): v is number => v != null)
  if (nums.length === 0) return []
  const min = Math.min(...nums)
  const max = Math.max(...nums)
  const stepX = values.length > 1 ? (width - 2 * pad) / (values.length - 1) : 0
  const y = (v: number) => (max === min ? height / 2 : pad + (height - 2 * pad) * (1 - (v - min) / (max - min)))
  const paths: string[] = []
  let current: string[] = []
  values.forEach((v, i) => {
    if (v == null) {
      if (current.length) paths.push(current.join(' '))
      current = []
      return
    }
    const x = values.length > 1 ? pad + i * stepX : width / 2
    current.push(`${current.length ? 'L' : 'M'}${x.toFixed(1)},${y(v).toFixed(1)}`)
  })
  if (current.length) paths.push(current.join(' '))
  // Point isole : petit trait horizontal pour rester visible.
  return paths.map((p) => (p.includes('L') ? p : `${p} l0.1,0`))
}
